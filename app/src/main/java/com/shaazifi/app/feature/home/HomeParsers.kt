package com.shaazifi.app.feature.home

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.Locale

/**
 * Turns router replies into Home values.
 *
 * Every formula here is copied from UnTangled's own source
 * (reference/untangled/src/lib/net.ts) so that Home shows the same numbers as
 * the Router tab. Do not "improve" a formula without changing it there too.
 */
object HomeParsers {

    private val MAC_REGEX = Regex("^([0-9a-f]{2}:){5}[0-9a-f]{2}$", RegexOption.IGNORE_CASE)
    private val IFNAME_REGEX = Regex("^[A-Za-z0-9_.-]{1,32}$")
    private val WHITESPACE = Regex("\\s+")

    // ---- small JSON helpers (never throw) ----

    private fun JsonElement?.asObj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.asArr(): JsonArray? = this as? JsonArray

    /** A JSON string, or null. Numbers and booleans are not strings. */
    private fun JsonElement?.asStr(): String? {
        val p = this as? JsonPrimitive ?: return null
        return if (p.isString) p.content else null
    }

    /** A JSON number, or 0. Same as UnTangled's num(). */
    private fun JsonElement?.asNum(): Double {
        val p = this as? JsonPrimitive ?: return 0.0
        if (p.isString) return 0.0
        return p.doubleOrNull ?: 0.0
    }

    private fun JsonElement?.isTrue(): Boolean {
        val p = this as? JsonPrimitive ?: return false
        if (p.isString) return false
        return p.booleanOrNull == true
    }

    private fun JsonElement?.isJsonNumber(): Boolean {
        val p = this as? JsonPrimitive ?: return false
        return !p.isString && p.doubleOrNull != null
    }

    private fun String?.orNullIfEmpty(): String? = if (this.isNullOrEmpty()) null else this

    // ---- system board ----

    fun parseBoard(board: JsonObject): BoardInfo {
        val release = board["release"].asObj()
        val description = release?.get("description").asStr().orNullIfEmpty()
        val joined = listOfNotNull(
            release?.get("distribution").asStr().orNullIfEmpty(),
            release?.get("version").asStr().orNullIfEmpty()
        ).joinToString(" ").orNullIfEmpty()
        val firmware = description ?: joined ?: board["kernel"].asStr().orNullIfEmpty() ?: "OpenWRT"
        val model = board["model"].asStr().orNullIfEmpty()
            ?: board["board_name"].asStr().orNullIfEmpty()
            ?: "Router"
        val hostname = board["hostname"].asStr().orNullIfEmpty() ?: "openwrt"
        return BoardInfo(model = model, hostname = hostname, firmware = firmware)
    }

    // ---- system info ----

    fun parseUptimeSec(info: JsonObject): Long = info["uptime"].asNum().toLong()

    /** round(((total - available) / total) * 100); uses `free` when `available` is absent. */
    fun parseMemUsedPct(info: JsonObject): Int {
        val mem = info["memory"].asObj() ?: return 0
        val total = mem["total"].asNum()
        if (total == 0.0) return 0
        val availableField = mem["available"]
        val available = if (availableField.isJsonNumber()) availableField.asNum() else mem["free"].asNum()
        return Math.round(((total - available) / total) * 100.0).toInt()
    }

    // ---- CPU from /proc/stat ----

    /** Reads the "cpu " line: user nice system idle iowait irq softirq steal. */
    fun parseCpuSample(procStat: String): CpuSample? {
        val line = procStat.split("\n").firstOrNull { it.startsWith("cpu ") } ?: return null
        val fields = line.trim().split(WHITESPACE).drop(1).take(8)
        if (fields.size < 4) return null
        val times = fields.map { it.toLongOrNull() ?: return null }
        val total = times.sum()
        val idle = times[3] + (times.getOrNull(4) ?: 0L)
        return CpuSample(busy = total - idle, total = total)
    }

    /** Share of non-idle time between two samples. Null until two usable samples exist. */
    fun cpuUsedPct(previous: CpuSample?, current: CpuSample?): Int? {
        if (previous == null || current == null) return null
        if (current.total <= previous.total) return null
        val pct = (current.busy - previous.busy).toDouble() / (current.total - previous.total).toDouble() * 100.0
        return Math.round(pct.coerceIn(0.0, 100.0)).toInt()
    }

    // ---- WAN ----

    fun parseWan(dump: JsonObject): WanStatus {
        val list = dump["interface"].asArr()?.mapNotNull { it.asObj() } ?: emptyList()
        val wan = list.firstOrNull { it["interface"].asStr() == "wan" }
            ?: list.firstOrNull { hasDefaultRoute(it) }
            ?: list.firstOrNull { it["interface"].asStr() != "loopback" && it["up"].isTrue() }
            ?: return WanStatus(up = false, proto = "?", device = "", ipv4 = null, gateway = null, uptimeSec = 0)
        val ipv4 = wan["ipv4-address"].asArr()?.firstOrNull().asObj()?.get("address").asStr()
        val gateway = wan["route"].asArr()
            ?.mapNotNull { it.asObj() }
            ?.firstOrNull { it["target"].asStr() == "0.0.0.0" }
            ?.get("nexthop").asStr()
        return WanStatus(
            up = wan["up"].isTrue(),
            proto = wan["proto"].asStr().orNullIfEmpty() ?: "?",
            device = wan["l3_device"].asStr().orNullIfEmpty() ?: wan["device"].asStr().orNullIfEmpty() ?: "",
            ipv4 = ipv4,
            gateway = gateway,
            uptimeSec = wan["uptime"].asNum().toLong()
        )
    }

    private fun hasDefaultRoute(iface: JsonObject): Boolean {
        val routes = iface["route"].asArr() ?: return false
        return routes.any {
            val target = it.asObj()?.get("target").asStr()
            target == "0.0.0.0" || target == "::"
        }
    }

    // ---- traffic counters ----

    fun parseDeviceCounters(deviceStatus: JsonObject, deviceName: String): TrafficCounters? {
        if (deviceName.isEmpty()) return null
        val stats = deviceStatus[deviceName].asObj()?.get("statistics").asObj() ?: return null
        return TrafficCounters(
            rxBytes = stats["rx_bytes"].asNum().toLong(),
            txBytes = stats["tx_bytes"].asNum().toLong()
        )
    }

    // ---- Wi-Fi ----

    /** What `network.wireless status` (or luci-rpc getWirelessDevices) says about access points. */
    data class WirelessPlan(
        /** No radios at all: a wired-only router. */
        val wifiAbsent: Boolean,
        /** Number of interfaces in access-point mode. */
        val accessPoints: Int,
        /** Access-point interface names that are safe to query. */
        val ifnames: List<String>
    )

    fun parseWirelessPlan(status: JsonObject): WirelessPlan {
        var accessPoints = 0
        val ifnames = mutableListOf<String>()
        for (radio in status.values) {
            val interfaces = radio.asObj()?.get("interfaces").asArr() ?: continue
            for (element in interfaces) {
                val iface = element.asObj() ?: continue
                val config = iface["config"].asObj()
                val mode = config?.get("mode").asStr() ?: "ap"
                if (mode != "ap") continue
                accessPoints++
                val ifname = iface["ifname"].asStr().orNullIfEmpty()
                    ?: config?.get("ifname").asStr().orNullIfEmpty()
                    ?: iface["iface"].asStr().orNullIfEmpty()
                if (ifname != null && isSafeIfname(ifname)) ifnames.add(ifname)
            }
        }
        return WirelessPlan(wifiAbsent = status.isEmpty(), accessPoints = accessPoints, ifnames = ifnames)
    }

    /**
     * Owner feedback: the link chips on Home. Each logical interface of `network.interface dump`
     * with its `up` flag, without the loopback.
     */
    fun parseInterfaces(dump: JsonObject): List<Pair<String, Boolean>> =
        dump["interface"].asArr().orEmpty().mapNotNull { e ->
            val o = e.asObj() ?: return@mapNotNull null
            val name = o["interface"].asStr().orNullIfEmpty() ?: return@mapNotNull null
            if (name == "loopback") null else name to o["up"].isTrue()
        }

    private fun bandFromConfig(band: String?): String = when (band) {
        "2g" -> "2.4 GHz"
        "5g" -> "5 GHz"
        "6g" -> "6 GHz"
        "60g" -> "60 GHz"
        else -> ""
    }

    private fun bandFromFrequency(freqMhz: Double?): String = when {
        freqMhz == null -> ""
        freqMhz in 2400.0..2500.0 -> "2.4 GHz"
        freqMhz in 5000.0..5900.0 -> "5 GHz"
        freqMhz in 5925.0..7125.0 -> "6 GHz"
        freqMhz >= 57000.0 -> "60 GHz"
        else -> ""
    }

    private fun JsonElement?.freqMhz(): Double? = when {
        this == null -> null
        isJsonNumber() -> asNum()
        else -> asStr()?.toDoubleOrNull()
    }

    /**
     * Owner feedback: one card per Wi-Fi network (access point), from `network.wireless status` or
     * `luci-rpc getWirelessDevices` (same radio -> interfaces shape; the latter adds `iwinfo`).
     * [clientsByIfname] are the counts read for each access point, when known.
     */
    fun parseRadios(status: JsonObject, clientsByIfname: Map<String, Int>): List<RadioSummary> {
        val out = mutableListOf<RadioSummary>()
        for ((radioId, radio) in status) {
            val r = radio.asObj() ?: continue
            val rc = r["config"].asObj()
            val configBand = bandFromConfig(rc?.get("band").asStr())
            val radioFreqBand = bandFromFrequency(r["iwinfo"].asObj()?.get("frequency").freqMhz())
            val radioUp = r["up"].isTrue() && !r["disabled"].isTrue()
            for (element in r["interfaces"].asArr().orEmpty()) {
                val iface = element.asObj() ?: continue
                val config = iface["config"].asObj()
                if ((config?.get("mode").asStr() ?: "ap") != "ap") continue
                val iw = iface["iwinfo"].asObj()
                val ssid = config?.get("ssid").asStr().orNullIfEmpty() ?: iw?.get("ssid").asStr().orNullIfEmpty() ?: continue
                val band = configBand.ifEmpty { bandFromFrequency(iw?.get("frequency").freqMhz()) }.ifEmpty { radioFreqBand }
                val ch = iw?.get("channel")
                val channel = if (ch.isJsonNumber()) ch.asNum().toLong().toString()
                    else ch.asStr().orNullIfEmpty() ?: rc?.get("channel").asStr().orEmpty()
                val ifname = iface["ifname"].asStr().orNullIfEmpty()
                out.add(RadioSummary(ssid, band, channel, radioUp && !iface["disabled"].isTrue() && !config?.get("disabled").isTrue(),
                    ifname?.let { clientsByIfname[it] }, WifiNetworkKey.from(radioId, iface["section"].asStr().orNullIfEmpty(), ifname)))
            }
        }
        return out
    }

    /** The name becomes part of a ubus object name, so only plain interface names pass. */
    fun isSafeIfname(ifname: String): Boolean = IFNAME_REGEX.matches(ifname)

    /** MACs (upper case) associated to one access point. Null when the reply has no clients object. */
    fun parseHostapdClients(reply: JsonObject): Set<String>? {
        val clients = reply["clients"].asObj() ?: return null
        val out = mutableSetOf<String>()
        for ((mac, client) in clients) {
            val assoc = client.asObj()?.get("assoc") as? JsonPrimitive
            val notAssociated = assoc != null && !assoc.isString && assoc.booleanOrNull == false
            if (notAssociated) continue
            out.add(mac.uppercase(Locale.ROOT))
        }
        return out
    }

    /** MACs (upper case) from `iwinfo assoclist`, the fallback when hostapd does not answer. */
    fun parseIwinfoAssoc(reply: JsonObject): Set<String> {
        val results = reply["results"].asArr() ?: return emptySet()
        return results.mapNotNull { it.asObj()?.get("mac").asStr().orNullIfEmpty()?.uppercase(Locale.ROOT) }.toSet()
    }

    // ---- connected devices ----

    /**
     * Counts devices as UnTangled's getClients does: merge host hints and DHCP
     * leases by MAC address and keep the ones that have an IPv4 address.
     * A device is on Wi-Fi when an access point lists it. Otherwise it is wired,
     * but only when Wi-Fi actually answered ([wifiOk]).
     */
    fun summarizeClients(
        hostHints: JsonObject,
        dhcpLeases: JsonObject,
        wifiMacs: Set<String>,
        wifiOk: Boolean
    ): ClientSummary {
        val ipByMac = LinkedHashMap<String, String>()
        fun put(mac: String, ip: String) {
            if (!MAC_REGEX.matches(mac)) return
            val key = mac.uppercase(Locale.ROOT)
            val current = ipByMac[key] ?: ""
            ipByMac[key] = if (ip.isNotEmpty()) ip else current
        }
        for ((mac, hint) in hostHints) {
            val h = hint.asObj()
            val ip = h?.get("ipv4").asStr().orNullIfEmpty()
                ?: h?.get("ipaddrs").asArr()?.firstOrNull().asStr().orNullIfEmpty()
                ?: ""
            put(mac, ip)
        }
        for (element in dhcpLeases["dhcp_leases"].asArr() ?: JsonArray(emptyList())) {
            val lease = element.asObj() ?: continue
            val mac = lease["macaddr"].asStr().orNullIfEmpty() ?: continue
            put(mac, lease["ipaddr"].asStr() ?: "")
        }
        val withIp = ipByMac.filterValues { it.isNotEmpty() }.keys
        val wifi = withIp.count { it in wifiMacs }
        return ClientSummary(
            total = withIp.size,
            wifi = wifi,
            wired = if (wifiOk) withIp.size - wifi else 0,
            wifiKnown = wifiOk
        )
    }

    // ---- text ----

    /** Same text as UnTangled: "2d 3h 4m", "3h 4m", "4m", or a dash for zero. */
    fun formatUptime(sec: Long): String {
        if (sec <= 0L) return "—"
        val d = sec / 86400
        val h = (sec % 86400) / 3600
        val m = (sec % 3600) / 60
        return when {
            d > 0 -> "${d}d ${h}h ${m}m"
            h > 0 -> "${h}h ${m}m"
            else -> "${m}m"
        }
    }

    /** Same text as UnTangled's formatBytes (decimal units). */
    fun formatBytes(bytes: Long): String {
        val b = bytes.toDouble()
        return when {
            b >= 1e12 -> String.format(Locale.US, "%.2f TB", b / 1e12)
            b >= 1e9 -> String.format(Locale.US, "%.2f GB", b / 1e9)
            b >= 1e6 -> String.format(Locale.US, "%.1f MB", b / 1e6)
            b >= 1e3 -> String.format(Locale.US, "%.0f KB", b / 1e3)
            else -> "$bytes B"
        }
    }

    /** Speed as a number and a unit, for example ("42.6", "Mbps"). */
    fun formatRate(bitsPerSecond: Double): Pair<String, String> = when {
        bitsPerSecond >= 1e9 -> String.format(Locale.US, "%.2f", bitsPerSecond / 1e9) to "Gbps"
        bitsPerSecond >= 1e6 -> String.format(Locale.US, "%.1f", bitsPerSecond / 1e6) to "Mbps"
        bitsPerSecond >= 1e3 -> String.format(Locale.US, "%.0f", bitsPerSecond / 1e3) to "Kbps"
        else -> String.format(Locale.US, "%.0f", bitsPerSecond) to "bps"
    }
}
