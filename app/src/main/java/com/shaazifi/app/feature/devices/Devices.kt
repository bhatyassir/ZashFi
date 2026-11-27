package com.shaazifi.app.feature.devices

import com.shaazifi.app.feature.home.HomeParsers
import com.shaazifi.app.feature.router.RouterReader
import com.shaazifi.app.feature.router.UciConfig
import com.shaazifi.app.feature.router.FeatureView
import com.shaazifi.app.feature.router.BandwidthMeter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.util.Locale

enum class Connection(val label: String) { WIFI_24("Wi-Fi 2.4 GHz"), WIFI_5("Wi-Fi 5 GHz"), WIFI_6("Wi-Fi 6 GHz"), WIFI("Wi-Fi"), WIRED("Wired"), UNKNOWN("Unknown") }

/** One device on the network, merged from DHCP leases, host hints, Wi-Fi clients, fixed addresses and block rules. */
data class Device(
    val mac: String,
    val name: String,
    val ip: String,
    val online: Boolean,
    val connection: Connection,
    val fixedIp: String?,
    val blocked: Boolean,
    /** dhcp `host` section holding the fixed address / name, if any. */
    val reserveId: String? = null,
    /** firewall `rule` section blocking this device, if any. */
    val blockId: String? = null,
    val network: DeviceNetwork? = null,
    /** Explicit custom name configured in UCI `dhcp.host`, if any. */
    val customName: String? = null
)

data class DeviceList(
    val devices: List<Device>,
    val wifiKnown: Boolean,
    val accessRules: List<AccessRule>? = null,
    val wifiClients: Map<String, Set<String>> = emptyMap(),
    val extraEgressZones: List<String> = emptyList()
) {
    val online: Int get() = devices.count { it.online }
}

internal fun Device.uniqueOnlineIp(devices: List<Device>): String? =
    ip.takeIf { online && it.isNotBlank() && devices.count { device -> device.ip == it } == 1 }

internal fun Device.splitTraffic(devices: List<Device>, view: FeatureView?): BandwidthMeter.DeviceRate? {
    val rates = view?.bandwidth?.devices.orEmpty()
    return rates.firstOrNull { it.mac.equals(mac, ignoreCase = true) }
        ?: uniqueOnlineIp(devices)?.let { currentIp -> rates.firstOrNull { it.mac == null && it.address == currentIp } }
}

internal fun matchDeviceRate(device: Device, devices: List<Device>, view: FeatureView?): Double? =
    device.splitTraffic(devices, view)?.let { it.downBytesPerSec + it.upBytesPerSec }
        ?: device.uniqueOnlineIp(devices)?.let { view?.combinedTraffic?.get(it) }

/**
 * Pure merge and sort, same rules as UnTangled's Devices screen (src/lib/devices.ts, src/lib/net.ts,
 * read as facts): live clients from host hints and DHCP leases; fixed addresses are dhcp `host`
 * sections (mac, ip, name); "Block internet" rules are firewall `rule` sections whose name starts
 * with "Block:" and carry `src_mac`. Blocked first, then fixed address, then online, then by name.
 */
object DevicesParsers {

    private val MAC = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() && it != "null" }

    fun bandFromFreq(mhz: Double): Connection = when {
        mhz >= 5925 -> Connection.WIFI_6
        mhz >= 4900 -> Connection.WIFI_5
        mhz >= 2400 -> Connection.WIFI_24
        else -> Connection.WIFI
    }

    fun merge(
        hostHints: JsonObject?,
        dhcpLeases: JsonObject?,
        wifiByMac: Map<String, Connection>,
        wifiKnown: Boolean,
        dhcp: UciConfig?,
        firewall: UciConfig?
    ): DeviceList {
        data class Acc(
            var name: String = "",
            var ip: String = "",
            var online: Boolean = false,
            var fixedIp: String? = null,
            var blocked: Boolean = false,
            var reserveId: String? = null,
            var blockId: String? = null,
            var customName: String? = null
        )
        val byMac = LinkedHashMap<String, Acc>()
        fun acc(mac: String) = byMac.getOrPut(mac.uppercase(Locale.ROOT)) { Acc() }

        for ((mac, hint) in hostHints.orEmpty()) {
            if (!MAC.matches(mac)) continue
            val h = hint as? JsonObject
            val ip = h?.get("ipv4").str() ?: (h?.get("ipaddrs") as? JsonArray)?.firstOrNull().str()
                ?: (h?.get("ip6addrs") as? JsonArray)?.firstOrNull().str() ?: continue
            val a = acc(mac)
            a.ip = ip
            a.online = true
            h?.get("name").str()?.let { if (a.name.isEmpty()) a.name = it }
        }
        for (el in (dhcpLeases?.get("dhcp_leases") as? JsonArray).orEmpty()) {
            val lease = el as? JsonObject ?: continue
            val mac = lease["macaddr"].str() ?: continue
            if (!MAC.matches(mac)) continue
            val expires = (lease["expires"] as? JsonPrimitive)?.doubleOrNull
            if (expires != null && expires <= 0.0) continue
            val a = acc(mac)
            lease["ipaddr"].str()?.let { if (a.ip.isEmpty()) a.ip = it }
            lease["hostname"].str()?.takeIf { it != "*" }?.let { if (a.name.isEmpty()) a.name = it }
        }
        wifiByMac.keys.filter(MAC::matches).forEach { acc(it).online = true }
        for (s in dhcp?.ofType("host").orEmpty()) {
            val mac = s.opt("mac")?.takeIf { MAC.matches(it) } ?: continue
            val a = acc(mac)
            a.fixedIp = s.opt("ip")?.takeIf { it.isNotBlank() }
            a.reserveId = s.id
            if (a.ip.isEmpty()) a.ip = s.opt("ip") ?: ""
            val hostName = s.opt("name")?.takeIf { it.isNotBlank() }
            if (hostName != null) {
                // Explicit custom UCI dhcp.host names take precedence over DHCP lease/hint names.
                a.name = hostName
                a.customName = hostName
            }
        }
        val rules = firewall?.let(AccessRule::parse)
        for (rule in rules.orEmpty()) {
            val a = acc(rule.mac)
            if (!rule.scheduled) {
                a.blocked = a.blocked || rule.enabled
                if (rule.family == null && (a.blockId == null || rule.enabled)) a.blockId = rule.id
            }
        }
        val extraEgress = firewall?.ofType("zone")?.mapNotNull { z ->
            z.opt("name")?.takeIf { n -> n != "lan" && n != "wan" && (n.startsWith("wan") || z.flag("masq", false)) }
        }?.distinct().orEmpty()

        val devices = byMac.map { (mac, a) ->
            val conn = wifiByMac[mac] ?: when {
                !a.online -> Connection.UNKNOWN
                wifiKnown -> Connection.WIRED
                else -> Connection.UNKNOWN
            }
            Device(mac, a.name.ifBlank { a.ip.ifBlank { mac } }, a.ip, a.online, conn, a.fixedIp, a.blocked, a.reserveId, a.blockId, customName = a.customName)
        }.sortedWith(
            compareBy<Device> { !it.blocked }
                .thenBy { it.fixedIp == null }
                .thenBy { !it.online }
                .thenBy { it.name.lowercase(Locale.ROOT) }
        )
        return DeviceList(devices, wifiKnown, rules, extraEgressZones = extraEgress)
    }

    /** `hostapd.<if> get_clients` reply: `{"freq": 5745, "clients": {<mac>: {...}}}` (fixture m7_hostapd_*). */
    fun wifiFromHostapd(reply: JsonObject): Map<String, Connection>? {
        val clients = reply["clients"] as? JsonObject ?: return null
        val band = bandFromFreq((reply["freq"] as? JsonPrimitive)?.doubleOrNull ?: 0.0)
        return clients.keys.filter { MAC.matches(it) }.associate { it.uppercase(Locale.ROOT) to band }
    }
}

/** Reads the devices list. READ-ONLY, through [RouterReader]. */
suspend fun loadDevices(reader: RouterReader): DeviceList {
    // First call doubles as the session check.
    val hints = reader.required("luci-rpc", "getHostHints", sessionGate = true)
    val leases = reader.optional("luci-rpc", "getDHCPLeases")
    val dhcp = reader.uci("dhcp", required = false)
    val firewall = reader.uci("firewall", required = false)
    val networks = reader.optional("network.interface", "dump")

    val wifi = mutableMapOf<String, Connection>()
    val clientsByInterface = mutableMapOf<String, Set<String>>()
    var wifiKnown = false
    val status = reader.optional("network.wireless", "status") ?: reader.optional("luci-rpc", "getWirelessDevices")
    if (status != null) {
        val plan = HomeParsers.parseWirelessPlan(status)
        for (ifname in plan.ifnames) {
            val fromHostapd = reader.optional("hostapd.$ifname", "get_clients")?.let { DevicesParsers.wifiFromHostapd(it) }
            if (fromHostapd != null) {
                wifi.putAll(fromHostapd)
                clientsByInterface[ifname] = fromHostapd.keys
                continue
            }
            val fromIwinfo = reader.optional("iwinfo", "assoclist", buildJsonObject { put("device", ifname) })
            if (fromIwinfo != null && fromIwinfo["results"] is JsonArray) {
                val clients = HomeParsers.parseIwinfoAssoc(fromIwinfo)
                clientsByInterface[ifname] = clients
                clients.forEach { wifi[it] = Connection.WIFI }
            }
        }
        wifiKnown = !plan.wifiAbsent && plan.accessPoints == clientsByInterface.size
    }
    return DevicesParsers.merge(hints, leases, wifi, wifiKnown, dhcp, firewall)
        .copy(wifiClients = clientsByInterface).withNetworks(networks, firewall)
}

/** Offline sample matching the mock's device list (docs/design/router-app-mock.html). Example addresses only. */
val DemoDevices = DeviceList(
    listOf(
        Device("02:00:00:00:00:04", "Kid's tablet", "192.168.1.57", true, Connection.WIFI_24, null, true),
        Device("02:00:00:00:00:01", "Smart TV", "192.168.1.20", true, Connection.WIFI_5, "192.168.1.20", false),
        Device("02:00:00:00:00:02", "Office laptop", "192.168.1.31", true, Connection.WIRED, null, false),
        Device("02:00:00:00:00:03", "Pixel 8", "192.168.1.42", true, Connection.WIFI_5, null, false),
        Device("02:00:00:00:00:05", "Printer", "192.168.1.60", true, Connection.WIRED, "192.168.1.60", false),
        Device("02:00:00:00:00:06", "Desktop PC", "192.168.1.12", false, Connection.UNKNOWN, null, false)
    ).map { device ->
        if (device.name == "Printer") device.copy(ip = "192.0.2.60", fixedIp = "192.0.2.60",
            network = DeviceNetwork("studio", "192.0.2.0/24", "studio"))
        else device.copy(network = DeviceNetwork("lan", "192.168.1.0/24", "lan"))
    },
    wifiKnown = true,
    accessRules = listOf(
        AccessRule("tablet", "02:00:00:00:00:04", true),
        AccessRule("family_tablet", "02:00:00:00:00:04", false,
            family = FamilyTag("f123456789abcdef", "member", "Kids' devices")),
        AccessRule("family_tv", "02:00:00:00:00:01", false,
            family = FamilyTag("f123456789abcdef", "member", "Kids' devices")),
        AccessRule("family_bedtime_tab", "02:00:00:00:00:04", true, "Mon Tue Wed Thu Fri", "21:00", "07:00",
            FamilyTag("f123456789abcdef", "schedule-123456789abcdef0", "Kids' devices")),
        AccessRule("family_bedtime_tv", "02:00:00:00:00:01", true, "Mon Tue Wed Thu Fri", "21:00", "07:00",
            FamilyTag("f123456789abcdef", "schedule-123456789abcdef0", "Kids' devices"))
    ),
    wifiClients = mapOf(
        "wlan0" to setOf("02:00:00:00:00:04"),
        "wlan1" to setOf("02:00:00:00:00:01", "02:00:00:00:00:03")
    )
)
