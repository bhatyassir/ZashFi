package com.shaazifi.app.feature.router

import com.shaazifi.app.ui.theme.PillTone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Phase 3 (T-20261117-C0594015): interfaces, live traffic, WireGuard peers, kernel log, and the
 * login's permissions. Reply shapes from openwrt/luci master (Apache-2.0, read 2026-11-17):
 * libs/rpcd-mod-luci/src/luci.c (getNetworkDevices), luci-base rpcd ucode `luci`
 * (getRealtimeStats -> luci-bwc rows), status/bandwidth.js (rate maths), luci-proto-wireguard
 * rpcd `luci.wireguard` (getWgInstances), status/dmesg.js (`dmesg -r`); openwrt/rpcd session.c
 * (`session access` without object/function returns the ACL by scope). Credited in CREDITS.md.
 */

private fun JsonElement?.obj() = this as? JsonObject
private fun JsonElement?.arr() = this as? JsonArray
private fun JsonElement?.str() = (this as? JsonPrimitive)?.content
private fun JsonElement?.num() = (this as? JsonPrimitive)?.doubleOrNull
private fun JsonElement?.long() = (this as? JsonPrimitive)?.longOrNull ?: (this as? JsonPrimitive)?.content?.toLongOrNull()
private fun JsonElement?.bool() = (this as? JsonPrimitive)?.booleanOrNull

/** Bytes in decimal units with one decimal: 2.0 GB, 500.0 MB. */
internal fun bytesText(b: Double): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = b
    var i = 0
    while (v >= 1000 && i < units.lastIndex) { v /= 1000; i++ }
    return if (i == 0) "${v.toLong()} B" else String.format(java.util.Locale.ROOT, "%.1f %s", v, units[i])
}

internal fun agoText(seconds: Long): String = when {
    seconds < 60 -> "$seconds s ago"
    seconds < 3600 -> "${seconds / 60} min ago"
    seconds < 86400 -> "${seconds / 3600} h ago"
    else -> "${seconds / 86400} d ago"
}

// ------------------------------------------------------------------------------- live traffic

data class Rates(
    val currentRx: Double, val currentTx: Double, val averageRx: Double, val averageTx: Double,
    val peakRx: Double, val peakTx: Double, val history: List<TrafficSample> = emptyList()
)

object RealtimeStats {
    /** Bytes per second from luci-bwc rows [time, rx bytes, rx packets, tx bytes, tx packets]. Null with fewer than two rows. */
    fun rates(reply: JsonObject): Rates? {
        val rows = reply["result"].arr()?.mapNotNull { r -> r.arr()?.mapNotNull { it.num() }?.takeIf { it.size >= 5 } }.orEmpty()
        if (rows.size < 2) return null
        val history = mutableListOf<TrafficSample>()
        for (i in 1 until rows.size) {
            val dt = rows[i][0] - rows[i - 1][0]
            if (dt <= 0) continue
            val received = rows[i][1] - rows[i - 1][1]
            val sent = rows[i][3] - rows[i - 1][3]
            if (received < 0 || sent < 0) continue
            history += TrafficSample((rows[i][0] * 1e9).toLong(), received / dt, sent / dt)
        }
        val latest = history.lastOrNull() ?: return null
        val rx = history.map { it.downBytesPerSec }
        val tx = history.map { it.upBytesPerSec }
        return Rates(latest.downBytesPerSec, latest.upBytesPerSec, rx.average(), tx.average(), rx.max(), tx.max(), history)
    }

    /** Bits per second, decimal units. */
    fun rateText(bytesPerSecond: Double): String {
        val bits = bytesPerSecond * 8
        return when {
            bits >= 1e9 -> String.format(java.util.Locale.ROOT, "%.1f Gbit/s", bits / 1e9)
            bits >= 1e6 -> String.format(java.util.Locale.ROOT, "%.1f Mbit/s", bits / 1e6)
            bits >= 1e3 -> String.format(java.util.Locale.ROOT, "%.1f kbit/s", bits / 1e3)
            else -> "${bits.toLong()} bit/s"
        }
    }

    fun view(byDevice: Map<String, Rates?>, internetPort: String? = null): FeatureView {
        val rows = byDevice.map { (dev, r) ->
            if (r == null) ViewRow(dev, "No data yet")
            else ViewRow(dev, "↓ ${rateText(r.currentRx)}  ↑ ${rateText(r.currentTx)}",
                sub = "Average ↓ ${rateText(r.averageRx)} ↑ ${rateText(r.averageTx)} · peak ↓ ${rateText(r.peakRx)} ↑ ${rateText(r.peakTx)}")
        }
        return FeatureView(
            summary = "Traffic right now, measured by the router over the last few minutes. Updates every 3 seconds.",
            sections = listOf(ViewSection("Now", rows, "No ports to measure.")),
            portTraffic = byDevice, internetPort = internetPort
        )
    }
}

// ------------------------------------------------------------------------------- WireGuard

object WireGuardPeers {
    fun rows(instances: JsonObject, nowSeconds: Long): List<ViewRow> = instances.values.mapNotNull { it.obj() }.flatMap { inst ->
        inst["peers"].arr()?.mapNotNull { it.obj() }.orEmpty().map { p ->
            val key = p["public_key"].str().orEmpty()
            val name = p["name"].str().orEmpty().ifBlank { "Peer ${key.take(8)}…" }
            val hs = p["latest_handshake"].long() ?: 0
            val ago = if (hs > 0) (nowSeconds - hs).coerceAtLeast(0) else null
            val endpoint = p["endpoint"].str()?.takeIf { it != "(none)" }
            ViewRow(
                label = name,
                value = ago?.let { agoText(it) } ?: "Never",
                sub = listOfNotNull(
                    inst["name"].str(),
                    endpoint,
                    "↓ ${bytesText((p["transfer_rx"].long() ?: 0).toDouble())} ↑ ${bytesText((p["transfer_tx"].long() ?: 0).toDouble())}",
                    ago?.let { "last handshake ${agoText(it)}" }
                ).joinToString(" · "),
                // WireGuard re-handshakes about every 2 minutes while traffic flows.
                pill = if (ago != null && ago <= 180) ViewPill("Connected", PillTone.Ok) else ViewPill("Idle", PillTone.Off)
            )
        }
    }
}

// ------------------------------------------------------------------------------- permissions

/** What the signed-in login may call (rpcd ACL). Patterns use * and ? as in rpcd. */
class Capabilities private constructor(private val acl: Map<String, Map<String, List<String>>>?) {

    private fun glob(pattern: String, s: String): Boolean =
        Regex("^" + pattern.split("*").joinToString(".*") { part -> part.split("?").joinToString(".") { Regex.escape(it) } } + "$").matches(s)

    private fun allowed(scope: String, obj: String, fn: String): Boolean {
        val table = acl ?: return true
        val entries = table[scope] ?: return false
        return entries.any { (o, fns) -> glob(o, obj) && fns.any { glob(it, fn) } }
    }

    fun ubus(obj: String, fn: String) = allowed("ubus", obj, fn)
    fun uciRead(config: String) = allowed("uci", config, "read")

    /** False only when the ACL is known and lacks what the feature needs. */
    fun feature(id: String): Boolean {
        val need = NEEDS[id] ?: return true
        return need.all { (o, f) -> ubus(o, f) }
    }

    companion object {
        /** No answer from the router: hide nothing. */
        val UNKNOWN = Capabilities(null)

        private val NEEDS: Map<String, List<Pair<String, String>>> = mapOf(
            "services" to listOf("rc" to "list"),
            "interfaces" to listOf("luci-rpc" to "getNetworkDevices"),
            "live" to listOf("luci" to "getRealtimeStats"),
            "bandwidth" to listOf("luci" to "getConntrackList"),
            "kernellog" to listOf("file" to "exec"),
            "log" to listOf("log" to "read"),
            "wireless" to listOf("luci-rpc" to "getWirelessDevices"),
            "guest" to listOf("luci-rpc" to "getWirelessDevices"),
            "extend" to listOf("luci-rpc" to "getWirelessDevices"),
            "health" to listOf("system" to "info"),
            "software" to listOf("file" to "exec"),
            "usage" to listOf("file" to "exec"),
            "traffic" to listOf("file" to "exec"),
            "tailscale" to listOf("file" to "exec"),
            "mesh" to listOf("untangled.mesh" to "info")
        )

        fun parse(reply: JsonObject): Capabilities = Capabilities(
            reply.mapNotNull { (scope, v) ->
                val objs = v.obj() ?: return@mapNotNull null
                scope to objs.mapValues { (_, fns) -> fns.arr()?.mapNotNull { it.str() }.orEmpty() }
            }.toMap().takeIf { it.isNotEmpty() }
        )
    }
}
