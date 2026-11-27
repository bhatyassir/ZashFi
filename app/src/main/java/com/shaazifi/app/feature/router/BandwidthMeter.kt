package com.shaazifi.app.feature.router

import java.util.Locale

/**
 * Owner feedback 2026-11-18: "Who is using the internet" with live download AND upload per device.
 *
 * Source (read 2026-11-18): openwrt/luci luci-base ucode sys.uc `conntrack_list` reads
 * /proc/net/nf_conntrack and adds both directions' `bytes` together. Each line of that file holds
 * the original direction first (src, dst, sport, dport, packets, bytes) and then the reply
 * direction, so reading the file itself keeps upload and download apart.
 * Byte counts are present only when the router accounts for the connection.
 */
object Conntrack {
    data class Flow(
        val l3: String,
        val proto: String,
        val origSrc: String,
        val origDst: String,
        val origSport: String,
        val origDport: String,
        val origBytes: Long,
        val replySrc: String,
        val replyDst: String,
        val replyBytes: Long,
        val flowId: String = "",
        val replySport: String = "",
        val replyDport: String = ""
    ) {
        val key: String
            get() = "$l3|$proto|$origSrc|$origSport|$origDst|$origDport|$flowId|$replySrc|$replySport|$replyDst|$replyDport"
    }

    private val whitespace = Regex("""\s+""")
    private val source = Regex("""\bsrc=\S+""")
    private val fields = Regex("""(\w+)=(\S+)""")

    /** Null means there is no trustworthy set of directional counters in this reading. */
    fun parse(text: String): List<Flow>? {
        val flows = mutableListOf<Flow>()
        for (line in text.lineSequence()) {
            val trimmed = line.trimStart()
            if (trimmed.isEmpty()) continue
            val parts = trimmed.split(whitespace, limit = 4)
            if (parts[0] != "ipv4" && parts[0] != "ipv6") continue
            val starts = source.findAll(line).take(2).map { it.range.first }.toList()
            if (parts.size < 4 || starts.size != 2) continue
            val original = tuple(line.substring(starts[0], starts[1]))
            val reply = tuple(line.substring(starts[1]))
            val origSrc = original["src"] ?: continue
            val origDst = original["dst"] ?: continue
            val origBytes = original["bytes"].byteCount() ?: continue
            val replySrc = reply["src"] ?: continue
            val replyDst = reply["dst"] ?: continue
            val replyBytes = reply["bytes"].byteCount() ?: continue
            val idParts = listOfNotNull(
                original["type"]?.let { "t=$it" },
                original["code"]?.let { "c=$it" },
                original["id"]?.let { "id=$it" },
                reply["id"]?.takeIf { it != original["id"] }?.let { "rid=$it" },
                original["srckey"]?.let { "sk=$it" },
                original["dstkey"]?.let { "dk=$it" },
                reply["zone"]?.let { "z=$it" }
            )
            flows += Flow(
                l3 = parts[0],
                proto = parts[2],
                origSrc = origSrc,
                origDst = origDst,
                origSport = original["sport"].orEmpty(),
                origDport = original["dport"].orEmpty(),
                origBytes = origBytes,
                replySrc = replySrc,
                replyDst = replyDst,
                replyBytes = replyBytes,
                flowId = idParts.joinToString("/"),
                replySport = reply["sport"].orEmpty(),
                replyDport = reply["dport"].orEmpty()
            )
        }
        return flows.takeIf { it.isNotEmpty() || text.isBlank() }
    }

    private fun tuple(text: String): Map<String, String> =
        fields.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }

    private fun String?.byteCount(): Long? = this?.toLongOrNull()?.takeIf { it >= 0 }
}

/** Which addresses are devices at home, and their names (from host hints and DHCP leases). */
class HostIndex(
    val devices: List<Host>,
    /** The router's own addresses (all interfaces): its own traffic is not a device's. */
    val routerAddresses: Set<String>,
    /** Home networks as "a.b.c.d/n" (IPv4). */
    val homeNets: List<String>
) {
    data class Host(val mac: String, val name: String, val addresses: Set<String>)

    private val byAddress: Map<String, Host> = devices.flatMap { h -> h.addresses.map { it.lowercase(Locale.ROOT) to h } }.toMap()

    fun host(address: String): Host? = byAddress[address.lowercase(Locale.ROOT)]

    private fun ipv4(a: String): Long? {
        val p = a.split('.')
        if (p.size != 4) return null
        var v = 0L
        for (x in p) v = v * 256 + (x.toIntOrNull()?.takeIf { it in 0..255 } ?: return null)
        return v
    }

    private fun inNet(address: String, net: String): Boolean {
        val (base, bits) = net.split('/').let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 32) }
        val a = ipv4(address) ?: return false
        val b = ipv4(base) ?: return false
        if (bits !in 0..32) return false
        val mask = if (bits == 0) 0L else (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
        return (a and mask) == (b and mask)
    }

    /** A device at home (not the router itself, not a link-local address). */
    fun isDevice(address: String): Boolean {
        val a = address.lowercase(Locale.ROOT)
        if (a in routerAddresses || a.startsWith("fe80:")) return false
        if (host(a) != null) return true
        if (homeNets.any { inNet(a, it) }) return true
        return a.startsWith("fd") || a.startsWith("fc")
    }

    fun isLocal(address: String): Boolean = address.lowercase(Locale.ROOT) in routerAddresses || isDevice(address)
}

object BandwidthMeter {
    data class Remote(val remote: String, val port: String, val proto: String, val downBytesPerSec: Double, val upBytesPerSec: Double)

    data class DeviceRate(
        val name: String,
        val mac: String?,
        val address: String,
        val downBytesPerSec: Double,
        val upBytesPerSec: Double,
        val connections: Int,
        /** Busiest internet addresses this device talks to, largest first (at most 3). */
        val top: List<Remote>
    ) {
        /** Same device across readings: its hardware address, else its IP address. */
        val id: String get() = mac ?: address
    }

    data class Usage(val devices: List<DeviceRate>, val totalDownBytesPerSec: Double, val totalUpBytesPerSec: Double)

    private class Acc(val name: String, val mac: String?, var address: String) {
        var down = 0L
        var up = 0L
        var connections = 0
        val remotes = mutableMapOf<String, LongArray>() // remote|port|proto -> [down, up]
    }

    fun usage(before: List<Conntrack.Flow>, after: List<Conntrack.Flow>, seconds: Double, hosts: HostIndex): Usage {
        val prev = before.associateBy { it.key }
        val acc = linkedMapOf<String, Acc>()
        for (f in after) {
            val p = prev[f.key]
            // A connection first seen now counts in full; a counter that went down started again.
            val dOrig = if (p != null && f.origBytes >= p.origBytes) f.origBytes - p.origBytes else f.origBytes
            val dReply = if (p != null && f.replyBytes >= p.replyBytes) f.replyBytes - p.replyBytes else f.replyBytes
            val device: String
            val remote: String
            val down: Long
            val up: Long
            when {
                hosts.isDevice(f.origSrc) && !hosts.isLocal(f.origDst) -> {
                    device = f.origSrc; remote = f.origDst; up = dOrig; down = dReply
                }
                hosts.isDevice(f.replySrc) && !hosts.isLocal(f.origSrc) -> {
                    // Opened from outside (a port forward): the device sends the reply direction.
                    device = f.replySrc; remote = f.origSrc; down = dOrig; up = dReply
                }
                else -> continue
            }
            val h = hosts.host(device)
            val key = h?.mac ?: device
            val a = acc.getOrPut(key) { Acc(h?.name ?: device, h?.mac, device) }
            if (device.contains('.') && !a.address.contains('.')) a.address = device
            a.down += down
            a.up += up
            a.connections++
            val r = a.remotes.getOrPut("$remote|${f.origDport}|${f.proto}") { LongArray(2) }
            r[0] += down
            r[1] += up
        }
        val secs = seconds.coerceAtLeast(0.1)
        val devices = acc.values.map { a ->
            DeviceRate(
                name = a.name,
                mac = a.mac,
                address = a.address,
                downBytesPerSec = a.down / secs,
                upBytesPerSec = a.up / secs,
                connections = a.connections,
                top = a.remotes.entries.sortedByDescending { it.value[0] + it.value[1] }.take(3).map { (k, v) ->
                    val (rem, port, proto) = k.split('|')
                    Remote(rem, port, proto, v[0] / secs, v[1] / secs)
                }
            )
        }.sortedWith(compareByDescending<DeviceRate> { it.downBytesPerSec + it.upBytesPerSec }.thenBy { it.name })
        return Usage(devices, devices.sumOf { it.downBytesPerSec }, devices.sumOf { it.upBytesPerSec })
    }
}

/** What the live usage screen draws: devices now, and each device's recent (down, up) rates. */
data class TrafficSample(val timeNs: Long, val downBytesPerSec: Double, val upBytesPerSec: Double)

data class BandwidthPicture(
    val devices: List<BandwidthMeter.DeviceRate>,
    val totalDownBytesPerSec: Double,
    val totalUpBytesPerSec: Double,
    val history: Map<String, List<Pair<Double, Double>>>,
    val timedHistory: Map<String, List<TrafficSample>> = emptyMap()
)

/** The previous sample and graphs for one active router session. */
class LiveUsageMeter {
    data class Measurement(
        val usage: BandwidthMeter.Usage,
        val history: Map<String, List<Pair<Double, Double>>>,
        val timedHistory: Map<String, List<TrafficSample>>
    )

    private var last: Pair<Long, List<Conntrack.Flow>>? = null
    private val history = mutableMapOf<String, ArrayDeque<TrafficSample>>()

    @Synchronized
    fun clear() {
        last = null
        history.clear()
    }

    @Synchronized
    fun sample(nowNs: Long, flows: List<Conntrack.Flow>, hosts: HostIndex): Measurement? {
        val before = last
        last = nowNs to flows
        if (before == null || nowNs - before.first !in 1..15_000_000_000L) {
            history.clear()
            return null
        }
        val usage = BandwidthMeter.usage(before.second, flows, (nowNs - before.first) / 1e9, hosts)
        usage.devices.forEach { d ->
            val q = history.getOrPut(d.id) { ArrayDeque() }
            q.addLast(TrafficSample(nowNs, d.downBytesPerSec, d.upBytesPerSec))
        }
        history.values.forEach { q ->
            while (q.isNotEmpty() && (nowNs - q.first().timeNs > 300_000_000_000L || q.size > 300)) {
                q.removeFirst()
            }
        }
        history.values.removeAll { it.isEmpty() }
        return Measurement(
            usage,
            usage.devices.associate { d -> d.id to history[d.id].orEmpty().takeLast(30).map { it.downBytesPerSec to it.upBytesPerSec } },
            history.mapValues { it.value.toList() }
        )
    }
}
