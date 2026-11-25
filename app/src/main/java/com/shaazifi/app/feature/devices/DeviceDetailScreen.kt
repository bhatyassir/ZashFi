package com.shaazifi.app.feature.devices

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.shaazifi.app.feature.home.HomeParsers
import com.shaazifi.app.feature.router.ChangeController
import com.shaazifi.app.feature.router.FeatureView
import com.shaazifi.app.feature.router.LoadHolder
import com.shaazifi.app.feature.router.LoadState
import com.shaazifi.app.feature.router.PollVisible
import com.shaazifi.app.feature.router.RealtimeStats
import com.shaazifi.app.feature.router.TrafficSample
import com.shaazifi.app.feature.router.submit
import com.shaazifi.app.ui.components.Note
import com.shaazifi.app.ui.components.PageHeading
import com.shaazifi.app.ui.components.SectionTitle
import com.shaazifi.app.ui.components.HistoryRange
import com.shaazifi.app.ui.components.TrafficReading
import com.shaazifi.app.ui.components.SfCard
import com.shaazifi.app.ui.components.SfDivider
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme
import kotlin.math.sin

@Composable
internal fun DeviceDetailScreen(
    device: Device,
    devices: DeviceList,
    traffic: LoadHolder<FeatureView>,
    usage: LoadHolder<FeatureView>,
    changes: ChangeController,
    demo: Boolean,
    routerIp: String,
    onParental: () -> Unit,
    routerPageAction: @Composable () -> Unit,
    networkName: String?,
    onNetwork: (() -> Unit)?
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        val (status, tone) = devicePill(device)
        PageHeading(device.name, listOf(DeviceKind.guess(device.name, null).label, device.ip)
            .filter { it.isNotBlank() }.joinToString(" · "), status, tone) {
            InternetAction(device, devices.accessRules, changes)
        }
        DeviceTraffic(device, devices.devices, traffic, usage, demo)
        DeviceDetailsAndActions(
            device, routerPageAction,
            onRequest = if (demo) null else { result ->
                when (result) {
                    is DeviceChangeResult.Ready -> { changes.request(result.plan); null }
                    is DeviceChangeResult.Invalid -> result.message
                }
            },
            routerIp = routerIp, networkName = networkName, onNetwork = onNetwork, onParental = onParental
        )
    }
}

@Composable
internal fun InternetAction(device: Device, rules: List<AccessRule>?, changes: ChangeController) {
    if (rules == null) {
        Note("Internet controls are unavailable for this router login.")
    } else {
        val manual = rules.any { it.mac == device.mac && it.enabled && !it.scheduled && it.family == null }
        val family = rules.any { it.mac == device.mac && it.enabled && !it.scheduled && it.family != null }
        if (family && !manual) {
            Note("Paused by a family profile.")
            return
        }
        var error by remember(device.mac, rules) { mutableStateOf<String?>(null) }
        Button(onClick = { error = changes.submit(changeInternet(device, rules)) }, shape = RoundedCornerShape(9.dp)) {
            Text(if (manual) "Resume internet" else "Pause internet")
        }
        error?.let { Text(it, color = SfTheme.colors.bad, style = SfText.sub) }
    }
}

@Composable
private fun DeviceTraffic(
    device: Device,
    devices: List<Device>,
    traffic: LoadHolder<FeatureView>,
    usage: LoadHolder<FeatureView>,
    demo: Boolean
) {
    val c = SfTheme.colors
    val usageState by usage.state.collectAsState()
    val trafficState by traffic.state.collectAsState()
    PollVisible(usage)
    if (!demo) PollVisible(traffic)
    val usageView = usageState.featureValue()
    val trafficView = trafficState.featureValue()
    val picture = trafficView?.bandwidth
    val uniqueIp = device.uniqueOnlineIp(devices)
    val current = device.splitTraffic(devices, trafficView)
    val combined = uniqueIp?.let { trafficView?.combinedTraffic?.get(it) }
    val samples = if (demo) remember(device.mac) { demoSamples(device) }
        else current?.id?.let { picture?.timedHistory?.get(it) }.orEmpty()
    val last = samples.lastOrNull()
    val download = if (demo) last?.downBytesPerSec else current?.downBytesPerSec
    val upload = if (demo) last?.upBytesPerSec else current?.upBytesPerSec
    var minutes by remember(device.mac) { mutableIntStateOf(1) }

    SfCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 12.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Device traffic", Modifier.weight(1f))
            if (download != null && upload != null) HistoryRange(minutes) { minutes = it }
        }
        if (download != null && upload != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) { TrafficReading("↓ Download", RealtimeStats.rateText(download), c.accent) }
                Column(Modifier.weight(1f)) { TrafficReading("↑ Upload", RealtimeStats.rateText(upload), c.warn) }
            }
            if (samples.size >= 2) {
                TrafficChart(samples, minutes)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (minutes == 1) "60 sec ago" else "5 min ago", style = SfText.mono, color = c.muted)
                    Text("Now", style = SfText.mono, color = c.muted)
                }
            } else Note("Collecting traffic history…")
        } else if (combined != null) {
            TrafficReading("Combined speed", RealtimeStats.rateText(combined), c.accent)
            Text("Separate download and upload readings are unavailable.", style = SfText.sub, color = c.muted)
        } else {
            val message = when {
                trafficState is LoadState.Loading || trafficView?.summary == "Measuring live traffic…" -> "Measuring live traffic…"
                picture != null || trafficView?.combinedTraffic != null -> "No measured traffic for this device."
                else -> "Traffic data unavailable"
            }
            Note(message)
        }
        SfDivider()
        val total = usageView?.deviceTotals?.get(device.mac)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Total data used", style = MaterialTheme.typography.titleSmall, color = c.ink)
                Text(when {
                    total != null && demo -> "Since router start"
                    total != null -> "Current accounting period"
                    usageState is LoadState.Loading -> "Reading usage…"
                    else -> "Usage data unavailable"
                }, style = SfText.sub, color = c.muted)
            }
            if (total != null) {
                Text(HomeParsers.formatBytes(total), style = MaterialTheme.typography.titleLarge, color = c.ink)
            }
        }
    }
}

private fun LoadState<FeatureView>.featureValue(): FeatureView? = when (this) {
    is LoadState.Loaded -> value
    is LoadState.DemoData -> value
    else -> null
}

@Composable
internal fun TrafficChart(samples: List<TrafficSample>, minutes: Int, modifier: Modifier = Modifier) {
    val c = SfTheme.colors
    val period = minutes * 60_000_000_000L
    val right = samples.last().timeNs
    val visible = samples.filter { right - it.timeNs <= period }
    val peak = visible.maxOf { maxOf(it.downBytesPerSec, it.upBytesPerSec) }.coerceAtLeast(1.0)
    Canvas(modifier.fillMaxWidth().height(106.dp)) {
        for (i in 0..4) {
            val y = i / 4f * size.height
            drawLine(c.muted.copy(alpha = 0.2f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
        fun point(sample: TrafficSample, rate: Double) = Offset(
            ((period - (right - sample.timeNs)).toDouble() / period).toFloat() * size.width,
            size.height * (1 - (rate / peak).toFloat() * 0.87f)
        )
        visible.zipWithNext().forEach { (first, next) ->
            if (next.timeNs - first.timeNs <= 10_000_000_000L) {
                drawLine(c.accent, point(first, first.downBytesPerSec), point(next, next.downBytesPerSec), 2.dp.toPx())
                drawLine(c.warn, point(first, first.upBytesPerSec), point(next, next.upBytesPerSec), 2.dp.toPx())
            }
        }
    }
}

private fun demoSamples(device: Device): List<TrafficSample> {
    val scale = device.mac.takeLast(2).toInt(16) * 2_000.0
    return (0..150).map { i ->
        val down = if (device.online) scale * (1.0 + 0.24 * sin(i / 3.5) + 0.22 * sin(i / 9.0)) else 0.0
        TrafficSample(i * 2_000_000_000L, down, down * 0.24)
    }
}
