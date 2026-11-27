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

import android.os.Environment
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.shaazifi.app.ui.components.Note
import com.shaazifi.app.ui.components.SectionTitle
import com.shaazifi.app.ui.components.SfList
import com.shaazifi.app.ui.components.SfListRow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Router pages that complete LuCI's Status and System menus but have no dedicated native screen
 * yet. Everything here is read-only except the explicit LED mode action, which goes through the
 * same confirm-and-run path as every other change in the app.
 */
object RouterTools {

    private fun List<String>.asRows(label: String, limit: Int): List<ViewRow> =
        take(limit).mapIndexed { i, line -> ViewRow("$label ${i + 1}", line.take(160).trim(), mono = true) }

    /** LuCI Status: Processes, live Routing table, Firewall (nftables) and Wireless channels. */
    suspend fun inspector(r: RouterReader): FeatureView {
        val sections = mutableListOf<ViewSection>()

        r.exec("/bin/ps", listOf("-w"))?.stdout?.let { output ->
            val lines = output.lineSequence().filter { it.isNotBlank() }.take(40).toList()
            sections += ViewSection("Processes", lines.asRows("PID", 40),
                emptyText = "The router did not report its processes.")
        }

        val v4 = r.exec("/sbin/ip", listOf("-4", "route", "show"))?.stdout
        val v6 = r.exec("/sbin/ip", listOf("-6", "route", "show"))?.stdout
        val routes = listOfNotNull(v4, v6).flatMap { it.lineSequence().filter { line -> line.isNotBlank() }.toList() }
        sections += ViewSection("Routing table", routes.asRows("Route", 40),
            emptyText = "The router did not report any routes.")

        r.exec("/usr/sbin/nft", listOf("list", "ruleset"))?.stdout?.let { output ->
            val lines = output.lineSequence().filter { it.isNotBlank() }.take(80).toList()
            sections += ViewSection("Firewall rules (nftables)", lines.asRows("Rule", 80),
                emptyText = "This router does not use nftables.")
        }

        val radios = r.optional("luci-rpc", "getWirelessDevices")?.keys
            ?.filter { WifiRadio.isRadioName(it) }.orEmpty()
        val channels = radios.flatMap { radio ->
            val reply = r.optional("iwinfo", "freqlist", buildJsonObject { put("device", JsonPrimitive(radio)) })
            // iwinfo freqlist answers {"results":[{"channel":36,"frequency":5180,"band":5,...}, ...]}.
            val results = reply?.get("results") as? JsonArray
            results.orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { entry ->
                (entry["channel"] as? JsonPrimitive)?.intOrNull?.let { channel ->
                    val frequency = (entry["frequency"] as? JsonPrimitive)?.intOrNull
                        ?: (entry["mhz"] as? JsonPrimitive)?.intOrNull
                    if (frequency != null) "$radio: channel $channel ($frequency MHz)" else "$radio: channel $channel"
                }
            }
        }
        sections += ViewSection("Wi-Fi channels", channels.asRows("Channel", 40),
            emptyText = "No channel list was reported.")

        return FeatureView(
            summary = "Live router state that LuCI shows under Status.",
            sections = sections,
            note = "Showing the first lines only. Use “Open in LuCI” for the full, interactive pages."
        )
    }

    /** LuCI System: cron jobs, DNS settings, bandwidth monitor configuration and LED mode. */
    suspend fun configuration(r: RouterReader): FeatureView {
        val sections = mutableListOf<ViewSection>()
        val currentCron = r.readFile("/etc/crontabs/root").orEmpty()

        val cron = r.readFile("/etc/crontabs/root")
            ?.lineSequence()?.filter { it.isNotBlank() && !it.trimStart().startsWith("#") }?.toList().orEmpty()
        sections += ViewSection("Scheduled tasks", cron.mapIndexed { i, line ->
            ViewRow("Job ${i + 1}", line.take(160), mono = true)
        }, emptyText = "No scheduled tasks are configured. Your LED night mode runs from this list.")

        val dns = r.uci("dhcp", required = false)
        val dnsKeys = listOf("local_use", "local_domain", "rebind_protection", "server", "domain", "port")
        val dnsRows = dns?.sections.orEmpty()
            .filter { it.type == "dnsmasq" }
            .flatMap { section -> dnsKeys.mapNotNull { key -> section.opt(key)?.let { ViewRow(key, it) } } }
        sections += ViewSection("DNS (dnsmasq)", dnsRows, emptyText = "No dnsmasq DNS settings were reported.")

        val nlbw = r.uci("nlbwmon", required = false)
        sections += ViewSection("Bandwidth monitor", nlbw?.sections.orEmpty().flatMap { section ->
            listOf("database_directory", "commit_interval", "refresh_interval", "protocol")
                .mapNotNull { key -> section.opt(key)?.let { ViewRow(key, it) } }
        }, emptyText = "The bandwidth monitor is not configured on this router.")

        val led = r.exec("/usr/bin/led-nightmode", listOf("check"))?.stdout?.trim().orEmpty()
        sections += ViewSection(
            "LED night mode",
            listOf(
                ViewRow("Current", led.ifBlank { "Unknown" }),
                ViewRow("Set night", "LEDs off", extra = RowAction("Run") { Phase4.ledMode("night") }),
                ViewRow("Set day", "Green LED on", extra = RowAction("Run") { Phase4.ledMode("day") })
            ),
            emptyText = "The led-nightmode script is not installed."
        )

        return FeatureView(
            summary = "Scheduled tasks, DNS and the router's own tools.",
            sections = sections,
            note = "DNS and bandwidth settings are read-only here. The LED schedule form below writes only the two led-nightmode lines.",
            forms = listOf(FeatureForm(
                "LED schedule", listOf(
                    FormField("offHour", "Lights off (hour 0–23)", "23", numeric = true),
                    FormField("offMinute", "Lights off (minute 0–59)", "0", numeric = true),
                    FormField("onHour", "Lights on (hour 0–23)", "7", numeric = true),
                    FormField("onMinute", "Lights on (minute 0–59)", "0", numeric = true)
                ), "Save schedule"
            ) { v ->
                fun field(key: String): Int = v[key]?.toIntOrNull() ?: -1
                val schedule = LedSchedule(field("offHour"), field("offMinute"), field("onHour"), field("onMinute"))
                CronSchedule.plan(schedule, currentCron)
            })
        )
    }

    /** A readable UCI snapshot. Restore is deliberately left to LuCI: it can change the LAN address. */
    suspend fun exportSettings(r: RouterReader): String = buildString {
        append("# ZashFi settings snapshot\n")
        append("# ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n\n")
        for (config in RouterReader.UCI_CONFIGS.sorted()) {
            val uci = r.uci(config, required = false) ?: continue
            append("# config $config\n")
            uci.sections.forEach { section ->
                append("config '${section.type}' '${section.id}'\n")
                section.options.forEach { (key, values) ->
                    values.forEach { value -> append("\toption '$key' '${value.replace("'", "'\\''")}'\n") }
                }
            }
            append('\n')
        }
    }
}

/** Download button for the configuration snapshot, shown under the Administration screen. */
@Composable
fun ConfigBackupCard(reader: RouterReader?, enabled: Boolean, isDemo: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status = remember { mutableStateOf<String?>(null) }

    SectionTitle("Configuration backup")
    SfList {
        SfListRow(
            title = "Download settings snapshot",
            subtitle = "A readable copy of the router's settings",
            onClick = {
                if (isDemo || reader == null) {
                    status.value = "This is demo data; there is no router to export."
                } else if (!enabled) {
                    status.value = "This login may not read the router settings."
                } else {
                    scope.launch {
                        status.value = try {
                            val text = RouterTools.exportSettings(reader)
                            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "zashfi")
                                .apply { mkdirs() }
                            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                            val file = File(dir, "zashfi-settings-$stamp.txt")
                            file.writeText(text)
                            "Saved ${file.name} to the app's Downloads folder."
                        } catch (e: Exception) {
                            "Export failed: ${e.message ?: "unknown error"}"
                        }
                    }
                }
            }
        )
    }
    status.value?.let { Note(it) }
    Note("Restore is done in LuCI → System → Backup / Flash Firmware. The app never restores a snapshot, because a restore could change the LAN address.")
}
