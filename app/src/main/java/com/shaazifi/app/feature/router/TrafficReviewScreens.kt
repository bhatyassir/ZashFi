package com.shaazifi.app.feature.router

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shaazifi.app.feature.devices.Device
import com.shaazifi.app.feature.devices.TrafficChart
import com.shaazifi.app.feature.devices.uniqueOnlineIp
import com.shaazifi.app.ui.components.HistoryRange
import com.shaazifi.app.ui.components.MetricTile
import com.shaazifi.app.ui.components.Note
import com.shaazifi.app.ui.components.PageHeading
import com.shaazifi.app.ui.components.Pill
import com.shaazifi.app.ui.components.SectionTitle
import com.shaazifi.app.ui.components.SfCard
import com.shaazifi.app.ui.components.SfDivider
import com.shaazifi.app.ui.components.SfList
import com.shaazifi.app.ui.components.SfListRow
import com.shaazifi.app.ui.components.TrafficReading
import com.shaazifi.app.ui.icons.SfIcons
import com.shaazifi.app.ui.theme.PillTone
import com.shaazifi.app.ui.theme.SfHue
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme
import java.util.Locale

private val TEMP_REGEX = Regex("""(-?\d+(?:\.\d+)?)""")

private fun parseTempCelsius(text: String): Double? =
    TEMP_REGEX.find(text)?.groupValues?.get(1)?.toDoubleOrNull()

private fun thermalPill(tempCelsius: Double): ViewPill = when {
    tempCelsius >= 85.0 -> ViewPill("Hot", PillTone.Bad)
    tempCelsius >= 75.0 -> ViewPill("Warm", PillTone.Warn)
    else -> ViewPill("Healthy", PillTone.Ok)
}

private fun thermalHue(tone: PillTone?): SfHue = when (tone) {
    PillTone.Bad -> SfHue.RED
    PillTone.Warn -> SfHue.ORANGE
    else -> SfHue.TEAL
}

@Composable
internal fun SystemHealth(
    view: FeatureView,
    routerModel: String,
    routerAddress: String,
    thermals: List<ThermalSensor> = emptyList()
) {
    val thermalSections = view.sections.filter {
        it.heading.contains("Thermal", ignoreCase = true) || it.heading.contains("Temperature", ignoreCase = true)
    }
    val nonThermalFields = view.sections.filterNot { it in thermalSections }.flatMap { it.rows }
    val systemName = nonThermalFields.firstOrNull {
        it.label.equals("Firmware", ignoreCase = true) ||
            it.label.equals("System", ignoreCase = true) ||
            it.label.equals("OS", ignoreCase = true)
    }?.value?.takeIf { it.isNotBlank() } ?: "OpenWrt"
    val resources = nonThermalFields.filter {
        !it.detail &&
            it.label != "Running for" &&
            !it.label.equals("Firmware", ignoreCase = true) &&
            !it.label.equals("System", ignoreCase = true) &&
            !it.label.equals("OS", ignoreCase = true)
    }
    PageHeading("System health", routerModel, view.pill?.text, view.pill?.tone ?: PillTone.Off)
    if (resources.isNotEmpty()) {
        SfCard(verticalSpacing = 10.dp) {
            SectionTitle("Resources")
            val rows = if (resources.size <= 3) listOf(resources) else resources.chunked(2)
            rows.forEach { group ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    group.forEach {
                        val isTemp = it.label.contains("Temp", ignoreCase = true) || it.value.contains("°C")
                        if (isTemp) {
                            HealthTempTile(if (it.label.equals("Temperature", ignoreCase = true)) "Temp" else it.label, it.value, it.sub, Modifier.weight(1f))
                        } else {
                            MetricTile(
                                if (it.label == "Processor") "CPU" else it.label,
                                it.value,
                                Modifier.weight(1f),
                                percent = it.value.removeSuffix("%").toIntOrNull(),
                                blue = it.label == "Memory"
                            )
                        }
                    }
                    if (rows.size > 1 && group.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
    SfCard(verticalSpacing = 10.dp) {
        SectionTitle("Router")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricTile("Uptime", nonThermalFields.firstOrNull { it.label == "Running for" }?.value ?: "Unavailable", Modifier.weight(1f))
            MetricTile("System", systemName, Modifier.weight(1f))
            if (routerAddress.isBlank()) Spacer(Modifier.weight(1f))
            else MetricTile("Address", routerAddress, Modifier.weight(1.5f))
        }
    }
    val thermalRows: List<ViewRow> = when {
        thermalSections.isNotEmpty() -> thermalSections.flatMap { it.rows }
        thermals.isNotEmpty() -> thermals.map { sensor ->
            val thresholds = listOfNotNull(
                sensor.critCelsius?.let { "Crit ${String.format(Locale.US, "%.0f°C", it)}" },
                sensor.maxCelsius?.let { "Max ${String.format(Locale.US, "%.0f°C", it)}" }
            ).joinToString(" · ").ifEmpty { null }
            ViewRow(
                label = sensor.label,
                value = String.format(Locale.US, "%.1f°C", sensor.tempCelsius),
                sub = thresholds,
                pill = thermalPill(sensor.tempCelsius)
            )
        }
        else -> emptyList()
    }
    if (thermalRows.isNotEmpty()) {
        SectionTitle("Thermals")
        SfList {
            thermalRows.forEachIndexed { index, row ->
                if (index > 0) SfDivider()
                val pill = row.pill ?: parseTempCelsius(row.value)?.let(::thermalPill)
                SfListRow(
                    row.label,
                    subtitle = row.sub,
                    icon = SfIcons.Chip,
                    hue = thermalHue(pill?.tone),
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(row.value, color = SfTheme.colors.ink, style = SfText.mono)
                            if (pill != null) Pill(pill.text, pill.tone)
                        }
                    }
                )
            }
        }
    }
    val details = nonThermalFields.filter { it.detail }
    if (details.isNotEmpty()) {
        SectionTitle("System details")
        SfList {
            details.forEachIndexed { index, row ->
                if (index > 0) SfDivider()
                SfListRow(row.label, subtitle = row.sub, icon = SfIcons.Chip, hue = SfHue.SLATE,
                    trailing = { Text(row.value, color = SfTheme.colors.muted, style = SfText.mono) })
            }
        }
    }
}

private data class HealthTempStyle(val bg: Color, val border: Color, val fg: Color, val note: String?)

@Composable
private fun HealthTempTile(label: String, value: String, note: String?, modifier: Modifier = Modifier) {
    val c = SfTheme.colors
    val tempC = parseTempCelsius(value)
    val style = when {
        tempC != null && tempC >= 85.0 -> HealthTempStyle(c.badSoft, c.bad, c.bad, note ?: "Hot")
        tempC != null && tempC >= 75.0 -> HealthTempStyle(c.warnSoft, c.warn, c.warn, note ?: "Warm")
        else -> HealthTempStyle(c.sunk, c.line, c.ink, note)
    }
    val shape = RoundedCornerShape(11.dp)
    Column(
        modifier.clip(shape).background(style.bg).border(1.dp, style.border, shape).padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, color = c.muted, style = MaterialTheme.typography.bodySmall)
        Text(value, color = style.fg, style = MaterialTheme.typography.titleLarge)
        if (!style.note.isNullOrBlank()) Text(style.note, color = style.fg, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun LiveTrafficPage(view: FeatureView) {
    PageHeading("Live traffic", "Download and upload across the router.")
    val wan = view.internetPort?.let { view.portTraffic[it] }
    if (wan != null) TrafficPanel("Internet traffic", wan.currentRx, wan.currentTx, wan.history)
    else MissingTraffic("The router has no usable internet port readings.")
    SectionTitle("Interfaces")
    SfList {
        view.portTraffic.entries.forEachIndexed { index, (name, rate) ->
            if (index > 0) SfDivider()
            val kind = when (name) {
                view.internetPort -> "Internet"
                "br-lan" -> "Local network"
                else -> null
            }
            SfListRow(name, subtitle = kind, icon = SfIcons.Port, hue = SfHue.BLUE, trailing = {
                if (rate == null) Text("Unavailable", style = SfText.sub, color = SfTheme.colors.muted)
                else RatePair(rate.currentRx, rate.currentTx)
            })
        }
    }
}

@Composable
internal fun BandwidthPage(
    view: FeatureView,
    devices: List<Device>,
    onDevice: ((String) -> Unit)?,
    flowOffloading: FlowOffloadingStatus? = null
) {
    val c = SfTheme.colors
    PageHeading("Who is using the internet", "See how much bandwidth each device is using.")
    val picture = view.bandwidth
    val combined = view.combinedTraffic
    val hwOffloadBanner = resolveHardwareOffloadNote(view, flowOffloading)
    when {
        picture != null -> {
            val history = picture.timedHistory.values.flatten().groupBy { it.timeNs }.toSortedMap()
                .map { (time, entries) -> TrafficSample(time, entries.sumOf { it.downBytesPerSec }, entries.sumOf { it.upBytesPerSec }) }
            TrafficPanel("All devices", picture.totalDownBytesPerSec, picture.totalUpBytesPerSec, history)
            val banner = hwOffloadBanner ?: view.note
            if (banner != null) Note(banner)
            StackedShareBarCard(picture.devices)
            UsageListTitle(picture.devices.size)
            SfList {
                picture.devices.forEachIndexed { index, reading ->
                    val device = devices.firstOrNull { it.mac.equals(reading.mac, ignoreCase = true) }
                        ?: devices.singleOrNull { reading.mac == null && it.uniqueOnlineIp(devices) == reading.address }
                    if (index > 0) SfDivider()
                    val total = picture.totalDownBytesPerSec + picture.totalUpBytesPerSec
                    var showRemotes by remember(reading.id) { mutableStateOf(false) }
                    val openDevice = device?.let { d -> onDevice?.let { { it(d.mac) } } }
                    SfListRow(
                        reading.name,
                        subtitle = listOfNotNull(device?.connection?.label, reading.address).joinToString(" · "),
                        icon = SfIcons.Devices,
                        hue = SfHue.ORANGE,
                        trailing = { RatePair(reading.downBytesPerSec, reading.upBytesPerSec) },
                        onClick = openDevice ?: if (reading.top.isNotEmpty()) { { showRemotes = !showRemotes } } else null
                    )
                    ShareBar(reading.downBytesPerSec + reading.upBytesPerSec, total)
                    if (reading.top.isNotEmpty()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { showRemotes = !showRemotes }
                                .padding(start = 58.dp, end = 14.dp, bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (showRemotes) "Hide top destinations" else "Top destinations (${reading.top.size})",
                                color = c.accent,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (reading.connections == 1) "1 connection" else "${reading.connections} connections",
                                color = c.muted,
                                fontSize = 11.sp
                            )
                        }
                        if (showRemotes) {
                            Column(
                                Modifier.padding(start = 58.dp, end = 14.dp, bottom = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                RemoteEndpointsSection(reading.top)
                            }
                        }
                    }
                }
                if (picture.devices.isEmpty()) SfListRow("No devices have active internet connections.")
            }
        }
        combined != null -> {
            val total = combined.values.sum()
            SfCard(verticalSpacing = 10.dp) {
                SectionTitle("Combined speed")
                TrafficReading("Download and upload total", RealtimeStats.rateText(total), c.accent)
                Text("Separate download and upload readings are unavailable.", style = SfText.sub, color = c.muted)
            }
            val banner = hwOffloadBanner ?: view.note
            if (banner != null) Note(banner)
            UsageListTitle(combined.size)
            SfList {
                val rows = view.sections.firstOrNull()?.rows.orEmpty()
                combined.entries.sortedByDescending { it.value }.forEachIndexed { index, (ip, rate) ->
                    if (index > 0) SfDivider()
                    val device = devices.singleOrNull { it.uniqueOnlineIp(devices) == ip }
                    val mapped = rows.getOrNull(index)
                    SfListRow(device?.name ?: mapped?.label ?: ip, subtitle = listOfNotNull(device?.connection?.label, ip).joinToString(" · "),
                        icon = SfIcons.Devices, hue = SfHue.ORANGE,
                        trailing = { Text(RealtimeStats.rateText(rate), style = SfText.mono, color = c.ink) },
                        onClick = device?.let { d -> onDevice?.let { { it(d.mac) } } })
                    ShareBar(rate, total)
                }
                if (combined.isEmpty()) SfListRow("No devices have active internet connections.")
            }
        }
        view.note != null -> MissingTraffic(view.note)
        else -> SfCard { SectionTitle(view.summary) }
    }
}

internal fun resolveHardwareOffloadNote(view: FeatureView, flowOffloading: FlowOffloadingStatus?): String? {
    val defaultNote =
        "Hardware flow offloading (PPE) is active on this router. Offloaded connections bypass per-device byte counters, so live speeds may look lower than actual traffic."
    val noteHasOffload = view.note?.let {
        it.contains("offload", ignoreCase = true) || it.contains("PPE", ignoreCase = true)
    } == true
    val sectionHasHwOffload = view.sections.flatMap { it.rows }.any {
        it.label.contains("offload", ignoreCase = true) &&
            (it.value.contains("hardware", ignoreCase = true) || it.value.contains("PPE", ignoreCase = true))
    }
    return when {
        flowOffloading?.hardware == true -> if (noteHasOffload) view.note else defaultNote
        noteHasOffload -> view.note
        sectionHasHwOffload -> defaultNote
        else -> null
    }
}

@Composable
private fun MissingTraffic(message: String) {
    SfCard(verticalSpacing = 6.dp) {
        SectionTitle("Traffic data unavailable")
        Text(message, style = SfText.sub, color = SfTheme.colors.muted)
    }
}

@Composable
private fun UsageListTitle(size: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("Devices now")
        Pill(if (size == 1) "1 device" else "$size devices", PillTone.Off)
    }
}

@Composable
private fun TrafficPanel(title: String, down: Double, up: Double, history: List<TrafficSample>) {
    val c = SfTheme.colors
    var minutes by remember { mutableIntStateOf(1) }
    SfCard(verticalSpacing = 12.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle(title, Modifier.weight(1f))
            HistoryRange(minutes) { minutes = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) { TrafficReading("↓ Download", RealtimeStats.rateText(down), c.accent) }
            Column(Modifier.weight(1f)) { TrafficReading("↑ Upload", RealtimeStats.rateText(up), c.warn) }
        }
        if (history.size >= 2) {
            TrafficChart(history, minutes)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (minutes == 1) "60 sec ago" else "5 min ago", color = c.muted, style = SfText.mono)
                Text("Now", color = c.muted, style = SfText.mono)
            }
        } else Text("Collecting traffic history…", style = SfText.sub, color = c.muted)
    }
}

@Composable
private fun RatePair(down: Double, up: Double) {
    val c = SfTheme.colors
    Column(horizontalAlignment = Alignment.End) {
        Text("↓ ${RealtimeStats.rateText(down)}", style = SfText.sub, color = c.accent)
        Text("↑ ${RealtimeStats.rateText(up)}", style = SfText.sub, color = c.warn)
    }
}

@Composable
private fun ShareBar(rate: Double, total: Double) {
    val c = SfTheme.colors
    val fill = if (total > 0) (rate / total).toFloat().coerceIn(0f, 1f) else 0f
    Box(Modifier.fillMaxWidth().padding(start = 58.dp, end = 12.dp, bottom = 9.dp)
        .height(4.dp).clip(RoundedCornerShape(4.dp)).background(c.line)) {
        Box(Modifier.fillMaxWidth(fill).height(4.dp).background(c.accent))
    }
}
