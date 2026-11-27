/*
 * Copyright (C) 2026 ZashFi contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.shaazifi.app.feature.router

import com.shaazifi.app.feature.home.HomeParsers
import com.shaazifi.app.ui.theme.PillTone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/*
 * LuCI Status > Realtime Graphs and Channel Analysis, as read-only native panels.
 *
 * Only calls already granted to RouterReader are used:
 *   luci-rpc getWirelessDevices       (radio ids, filtered by WifiRadio.isRadioName)
 *   iwinfo freqlist {device: radio}   (available channels; see WifiRadio.radioInfos)
 *   iwinfo assoclist {device: ifname} (clients per access point; see HomeParsers.parseIwinfoAssoc)
 *   network.device status             (per-port rx_bytes / tx_bytes)
 *   network.interface dump            (ipv4-address address / mask rows)
 * No form, no PlanResult: this object only reads.
 */

/** One LuCI access point: its safe interface name and (when reported) SSID. */
private data class RealtimeAp(val ifname: String, val ssid: String?)

/** One `iwinfo freqlist` entry. */
private data class RealtimeChannel(
    val number: Int,
    val frequency: Int?,
    val restricted: Boolean,
    val noIr: Boolean
)

object RealtimePanels {

    private val ON = ViewPill("Up", PillTone.Ok)
    private val OFF = ViewPill("Down", PillTone.Off)

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull
    private fun JsonElement?.long(): Long = (this as? JsonPrimitive)?.longOrNull ?: 0L
    private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
    private fun deviceArg(device: String) = buildJsonObject { put("device", JsonPrimitive(device)) }

    /**
     * Every call here is optional: a router that lacks or refuses one of them must not stop the
     * other sections from being shown.
     */
    private suspend fun read(
        r: RouterReader,
        ubusObject: String,
        method: String,
        args: JsonObject = buildJsonObject {}
    ): JsonObject? = try {
        r.optional(ubusObject, method, args)
    } catch (e: FeatureLoadException) {
        null
    }

    /** Band of one radio from `getWirelessDevices`, or empty when the router did not say. */
    private fun bandLabel(radio: JsonObject?): String {
        val configured = radio?.get("config").obj()?.get("band").str()
        if (!configured.isNullOrBlank()) return WifiRadio.bandLabel(configured)
        val frequency = radio?.get("iwinfo").obj()?.get("frequency").int()
            ?: radio?.get("interfaces").arr()?.firstOrNull().obj()?.get("iwinfo").obj()?.get("frequency").int()
        val band = WifiRadio.bandFromFrequency(frequency)
        return if (band.isBlank()) "" else WifiRadio.bandLabel(band)
    }

    private fun accessPoints(radio: JsonObject?): List<RealtimeAp> =
        radio?.get("interfaces").arr().orEmpty().mapNotNull { element ->
            val ap = element.obj() ?: return@mapNotNull null
            val config = ap["config"].obj()
            if ((config?.get("mode").str() ?: "ap") != "ap") return@mapNotNull null
            val ifname = ap["ifname"].str() ?: config?.get("ifname").str() ?: ap["iface"].str() ?: return@mapNotNull null
            if (!HomeParsers.isSafeIfname(ifname)) return@mapNotNull null
            RealtimeAp(ifname, config?.get("ssid").str() ?: ap["iwinfo"].obj()?.get("ssid").str())
        }.distinctBy { it.ifname }

    /** Channel Analysis: one short row per radio, with the individual channels folded under Details. */
    private fun channelRows(radio: String, band: String, reply: JsonObject?): List<ViewRow> {
        val label = if (band.isBlank()) radio else "$radio ($band)"
        val entries = reply?.get("results").arr().orEmpty().mapNotNull { it.obj() }
        if (entries.isEmpty()) {
            return listOf(ViewRow(label, "No channel list", sub = "iwinfo gave no frequencies", mono = true))
        }
        val channels = entries.mapNotNull { entry ->
            val channel = entry["channel"].int() ?: return@mapNotNull null
            RealtimeChannel(
                number = channel,
                frequency = entry["frequency"].int(),
                restricted = entry["restricted"].bool() == true,
                noIr = entry["flags"].arr()?.any { it.str() == "no_ir" } == true
            )
        }.distinctBy { it.number }.sortedBy { it.number }
        if (channels.isEmpty()) {
            return listOf(ViewRow(label, "No channel list", sub = "iwinfo gave no frequencies", mono = true))
        }
        // LuCI's channel picker does not offer a channel that cannot transmit here (no_ir).
        val available = channels.filterNot { it.restricted && it.noIr }.map { it.number }
        val listed = available.take(12).joinToString(", ") +
            if (available.size > 12) " +${available.size - 12} more" else ""
        val summary = ViewRow(
            label = label,
            value = if (available.isEmpty()) "No available channels" else "${available.size} channels",
            sub = listed.takeIf { available.isNotEmpty() },
            mono = true
        )
        val details = channels.map { (channel, frequency, restricted, noIr) ->
            ViewRow(
                label = "$radio · ch $channel",
                value = frequency?.let { "$it MHz" } ?: "Channel $channel",
                sub = when {
                    restricted && noIr -> "Restricted (no IR)"
                    restricted -> "Restricted"
                    else -> "Available"
                },
                mono = true,
                detail = true,
                pill = if (restricted) ViewPill("Limited", PillTone.Warn) else null
            )
        }
        return listOf(summary) + details
    }

    /** Connected clients per AP, summarised for each radio (individual APs under Details). */
    private suspend fun clientRows(r: RouterReader, wifi: JsonObject?, radios: List<String>): List<ViewRow> {
        val rows = mutableListOf<ViewRow>()
        for (radio in radios) {
            val radioReply = wifi?.get(radio).obj()
            val band = bandLabel(radioReply)
            val label = if (band.isBlank()) radio else "$radio ($band)"
            val counts = accessPoints(radioReply).map { ap ->
                val reply = read(r, "iwinfo", "assoclist", deviceArg(ap.ifname))
                val clients = reply?.let { HomeParsers.parseIwinfoAssoc(it) }.orEmpty().size
                ap to clients
            }
            val total = counts.sumOf { it.second }
            rows += ViewRow(
                label = label,
                value = if (counts.isEmpty()) "No access point" else if (total == 1) "1 client" else "$total clients",
                sub = counts.joinToString(" · ") { (ap, n) -> "${ap.ifname}: $n" }.takeIf { counts.isNotEmpty() },
                mono = true
            )
            counts.forEach { (ap, n) ->
                rows += ViewRow(
                    label = ap.ssid?.let { "${ap.ifname} · $it" } ?: ap.ifname,
                    value = if (n == 1) "1 client" else "$n clients",
                    mono = true,
                    detail = true
                )
            }
        }
        return rows
    }

    /** `network.device status`: rx/tx counters of each real port (both LuCI statistics shapes). */
    private fun portRows(status: JsonObject?): List<ViewRow> =
        status.orEmpty().mapNotNull { (name, element) ->
            val device = element.obj() ?: return@mapNotNull null
            if (name == "lo" || device["flags"].obj()?.get("loopback").bool() == true) return@mapNotNull null
            val stats = device["statistics"].obj() ?: device["stats"].obj() ?: return@mapNotNull null
            val rx = stats["rx_bytes"].long().coerceAtLeast(0L)
            val tx = stats["tx_bytes"].long().coerceAtLeast(0L)
            name to (rx to tx)
        }.sortedByDescending { (_, counters) -> counters.first + counters.second }.map { (name, counters) ->
            val (rx, tx) = counters
            ViewRow(
                label = name,
                value = HomeParsers.formatBytes(rx + tx),
                sub = "↓ ${HomeParsers.formatBytes(rx)} · ↑ ${HomeParsers.formatBytes(tx)}",
                mono = true
            )
        }

    /** `network.interface dump`: one row per IPv4 address, with its mask. */
    private fun interfaceRows(dump: JsonObject?): List<ViewRow> =
        dump?.get("interface").arr().orEmpty().mapNotNull { it.obj() }
            .filter { (it["interface"].str() ?: "") != "loopback" }
            .flatMap { logical ->
                val name = logical["interface"].str() ?: return@flatMap emptyList()
                val device = logical["l3_device"].str() ?: logical["device"].str()
                val protocol = FeatureMappers.protoLabel(logical["proto"].str())
                val pill = if (logical["up"].bool() == true) ON else OFF
                val addresses = logical["ipv4-address"].arr().orEmpty().mapNotNull { it.obj() }
                addresses.map { address ->
                    val ip = address["address"].str().orEmpty()
                    val mask = address["mask"].int()?.toString() ?: address["mask"].str()
                    ViewRow(
                        label = if (device.isNullOrBlank()) name else "$name ($device)",
                        value = listOfNotNull(ip.takeIf { it.isNotBlank() }, mask?.takeIf { it.isNotBlank() }).joinToString("/"),
                        sub = listOfNotNull(device, protocol.takeIf { it.isNotBlank() && it != "Not used" })
                            .joinToString(" · ").takeIf { it.isNotBlank() },
                        mono = true,
                        pill = pill
                    )
                }
            }

    /**
     * LuCI's Status > Realtime Graphs (port traffic and interfaces) and Channel Analysis
     * (available channels and clients per radio). Read-only; a refused call leaves its section
     * empty rather than failing the whole view.
     */
    suspend fun realtimeAndChannels(r: RouterReader): FeatureView {
        val wifi = read(r, "luci-rpc", "getWirelessDevices")
        val radios = wifi?.keys?.filter { WifiRadio.isRadioName(it) }?.sorted().orEmpty()

        val channels = radios.flatMap { radio ->
            val reply = read(r, "iwinfo", "freqlist", deviceArg(radio))
            channelRows(radio, bandLabel(wifi?.get(radio).obj()), reply)
        }
        val clients = clientRows(r, wifi, radios)
        val ports = portRows(read(r, "network.device", "status"))
        val interfaces = interfaceRows(read(r, "network.interface", "dump"))

        return FeatureView(
            summary = "LuCI Status > Realtime Graphs and Channel Analysis.",
            pill = ViewPill(if (radios.size == 1) "1 radio" else "${radios.size} radios", PillTone.Off),
            sections = listOf(
                ViewSection("Channel analysis", channels, "No radio reported a channel list."),
                ViewSection("Wi-Fi clients per radio", clients, "No access point reported connected clients."),
                ViewSection("Port traffic", ports, "No port traffic counters were reported."),
                ViewSection("Interfaces", interfaces, "No interface reported an IPv4 address.")
            ),
            note = "Port counters are totals since the router last started. This panel only reads; it never changes settings."
        )
    }
}
