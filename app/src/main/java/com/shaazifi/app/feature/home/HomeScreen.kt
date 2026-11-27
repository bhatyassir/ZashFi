package com.shaazifi.app.feature.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.shaazifi.app.feature.devices.Device
import com.shaazifi.app.feature.devices.matchDeviceRate
import com.shaazifi.app.feature.router.FeatureView
import com.shaazifi.app.feature.router.RealtimeStats
import com.shaazifi.app.ui.components.HistoryRange
import com.shaazifi.app.ui.components.HueTile
import com.shaazifi.app.ui.components.MetricTile
import com.shaazifi.app.ui.components.PageHeading
import com.shaazifi.app.ui.components.PageLink
import com.shaazifi.app.ui.components.Pill
import com.shaazifi.app.ui.components.SectionTitle
import com.shaazifi.app.ui.components.SfCard
import com.shaazifi.app.ui.components.SfDivider
import com.shaazifi.app.ui.components.SfListRow
import com.shaazifi.app.ui.components.TrafficReading
import com.shaazifi.app.ui.icons.SfIcons
import com.shaazifi.app.ui.theme.PillTone
import com.shaazifi.app.ui.theme.SfColors
import com.shaazifi.app.ui.theme.SfHue
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme
import java.util.Locale

/** Colours taken from docs/design/router-app-mock.html. */
private enum class NodeIcon { INTERNET, ROUTER, DEVICES }

/**
 * Native Home dashboard (blueprint M7). Read-only.
 * Refreshes only while this screen is on screen and the app is in the foreground.
 */
@Composable
fun HomeScreen(
    stateHolder: HomeStateHolder,
    routerHost: String,
    onOpenRouterTab: () -> Unit,
    onSignInAgain: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
    /** Owner feedback: Home cards open the matching page (bandwidth, health, interfaces...). */
    onOpenFeature: (String) -> Unit = {},
    onOpenWifi: (WifiNetworkKey?) -> Unit = {},
    onAllWifi: () -> Unit = {},
    connectedDevices: List<Device> = emptyList(),
    liveTraffic: FeatureView? = null,
    onDevice: (String) -> Unit = {}
) {
    val palette = SfTheme.colors
    val actions = HomeActions(onOpenRouterTab, onOpenFeature, onOpenWifi, onAllWifi, onDevice)
    val state by stateHolder.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(stateHolder, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            stateHolder.pollWhileActive()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.bg)
    ) {
        when (val s = state) {
            HomeUiState.Loading -> LoadingView(palette)
            is HomeUiState.Loaded -> DashboardView(s.dashboard, routerHost, palette, actions, connectedDevices, liveTraffic)
            is HomeUiState.DemoData -> DashboardView(s.dashboard, routerHost, palette, actions, connectedDevices, liveTraffic)
            HomeUiState.SessionExpired -> MessageView(
                palette = palette,
                title = "Signed out",
                body = "Your session on the router has ended. Sign in again to continue.",
                action = "Sign in again",
                onAction = onSignInAgain
            )
            HomeUiState.RouterUnreachable -> MessageView(
                palette = palette,
                title = "Can't reach the router",
                body = "Check that this phone is on the router's Wi-Fi or your VPN is connected.",
                action = "Reconnect",
                onAction = onReconnect
            )
            HomeUiState.NotPermitted -> MessageView(
                palette = palette,
                title = "Not allowed",
                body = "This account is not allowed to read the router's status. The Router tab still works.",
                action = "Open Router tab",
                onAction = onOpenRouterTab
            )
            HomeUiState.FeatureNotInstalled -> MessageView(
                palette = palette,
                title = "Status not available",
                body = "This router does not offer the status information Home needs. The Router tab still works.",
                action = "Open Router tab",
                onAction = onOpenRouterTab
            )
        }
    }
}

@Composable
private fun LoadingView(palette: SfColors) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = palette.accent)
        Spacer(modifier = Modifier.height(12.dp))
        Text("Reading router status", color = palette.muted, fontSize = 14.sp)
    }
}

@Composable
private fun MessageView(
    palette: SfColors,
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, color = palette.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(8.dp))
        Text(body, color = palette.muted, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = onAction,
            shape = RoundedCornerShape(13.dp),
            colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.accentInk),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(action, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Where each Home card leads. */
private class HomeActions(
    val openDevices: () -> Unit,
    val openFeature: (String) -> Unit,
    val openWifi: (WifiNetworkKey?) -> Unit,
    val openAllWifi: () -> Unit,
    val openDevice: (String) -> Unit
)

/**
 * Owner feedback 2026-11-17: denser and interactive, like LuCI Mobile's dashboard. One status line,
 * a large traffic graph that opens "who is using the internet", health tiles, Wi-Fi cards and link chips.
 */
@Composable
private fun DashboardView(
    d: HomeDashboard,
    routerHost: String,
    palette: SfColors,
    actions: HomeActions,
    connectedDevices: List<Device>,
    liveTraffic: FeatureView?
) {
    var minutes by remember { mutableIntStateOf(1) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PageHeading(
            "Network overview",
            "${d.routerName} · $routerHost",
            if (d.stale) "Last known status" else if (d.internetUp) "Internet online" else "Internet offline",
            if (d.stale) PillTone.Warn else if (d.internetUp) PillTone.Ok else PillTone.Off
        )
        if (d.stale) Banner("Showing the last values. The router did not answer.", palette.warnSoft, palette.warn)

        SfCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 12.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Internet traffic", Modifier.weight(1f))
                HistoryRange(minutes) { minutes = it }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.weight(1f)) { TrafficReading("↓ Download", graphRate(d.downloadBps), palette.accent) }
                Box(Modifier.weight(1f)) { TrafficReading("↑ Upload", graphRate(d.uploadBps), palette.warn) }
            }
            if (d.downloadHistoryBps.size >= 2 && d.downloadBps != null && d.uploadBps != null) {
                TrafficGraph(
                    d.historyTimesMs,
                    d.downloadHistoryBps,
                    d.uploadHistoryBps,
                    palette,
                    windowMs = minutes * 60_000L,
                    modifier = Modifier.fillMaxWidth().height(133.dp)
                        .semantics { contentDescription = "Download and upload history over $minutes minutes" }
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (minutes == 1) "60 sec ago" else "5 min ago", style = SfText.mono, color = palette.muted)
                    Text("Now", style = SfText.mono, color = palette.muted)
                }
            } else {
                Text(
                    if (d.downloadBps == null && d.downloadHistoryBps.isNotEmpty()) "Traffic data unavailable" else "Collecting traffic history…",
                    color = palette.muted,
                    style = SfText.sub
                )
            }
            SfDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    d.totalBytesSinceStart?.let { "${HomeParsers.formatBytes(it)} since router start" }.orEmpty(),
                    color = palette.muted,
                    style = SfText.sub,
                    modifier = Modifier.weight(1f)
                )
                PageLink("Who is using it?", { actions.openFeature("bandwidth") })
            }
        }

        if (d.radios.isNotEmpty()) SfCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 8.dp) {
            SectionTitle("Wi-Fi networks")
            d.radios.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { r ->
                        HomeWifiCard(r.ssid, r.band, r.channel, r.up, r.clients, Modifier.weight(1f)) { actions.openWifi(r.key) }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            PageLink("All Wi-Fi settings", actions.openAllWifi)
        }

        SfCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 8.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                SectionTitle("System health")
                PageLink("Details", { actions.openFeature("health") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile("CPU", d.cpuUsedPct?.let { "$it%" } ?: "—", Modifier.weight(1f), percent = d.cpuUsedPct)
                MetricTile("Memory", "${d.memUsedPct}%", Modifier.weight(1f), percent = d.memUsedPct, blue = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TempMetricTile(d.primaryTempC, palette, Modifier.weight(1f))
                MetricTile("Uptime", d.uptimeText, Modifier.weight(1f))
            }
        }

        SfCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 4.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Top connected devices", Modifier.weight(1f))
                PageLink(d.clients?.let { "${it.total} devices" } ?: "Devices", actions.openDevices)
            }
            val ranked = connectedDevices.filter { it.online }
                .map { it to matchDeviceRate(it, connectedDevices, liveTraffic) }
                .sortedWith(compareByDescending<Pair<Device, Double?>> { it.second ?: -1.0 }.thenBy { it.first.name.lowercase() })
                .take(3)
            if (ranked.isEmpty()) Text("No connected devices to show.", style = SfText.sub, color = palette.muted)
            ranked.forEach { (device, rate) ->
                SfListRow(
                    device.name,
                    subtitle = device.connection.label,
                    icon = SfIcons.Devices,
                    trailing = { if (rate != null) Text(RealtimeStats.rateText(rate), style = SfText.mono, color = palette.muted) },
                    onClick = { actions.openDevice(device.mac) }
                )
            }
        }
    }
}

private fun graphRate(bitsPerSecond: Double?): String =
    bitsPerSecond?.let(HomeParsers::formatRate)?.let { "${it.first} ${it.second}" } ?: "—"

/** The chart shows the line this far behind "now", so a new point is always ready before it shows. */
private const val GRAPH_DELAY_MS = 2_000L
private const val GRAPH_FRAME_STEP_MS = 33L

private data class TempTileStyle(val bg: Color, val border: Color, val valueColor: Color, val note: String?)

@Composable
private fun TempMetricTile(tempCelsius: Double?, palette: SfColors, modifier: Modifier = Modifier) {
    val style = when {
        tempCelsius == null -> TempTileStyle(palette.sunk, palette.line, palette.ink, null)
        tempCelsius >= 85.0 -> TempTileStyle(palette.badSoft, palette.bad, palette.bad, "Hot")
        tempCelsius >= 75.0 -> TempTileStyle(palette.warnSoft, palette.warn, palette.warn, "Warm")
        else -> TempTileStyle(palette.sunk, palette.line, palette.ink, null)
    }
    val valueText = tempCelsius?.let { String.format(Locale.US, "%.1f°C", it) } ?: "—"
    val shape = RoundedCornerShape(11.dp)
    Column(
        modifier.clip(shape).background(style.bg).border(1.dp, style.border, shape).padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Temp", color = palette.muted, style = MaterialTheme.typography.bodySmall)
        Text(valueText, color = style.valueColor, style = MaterialTheme.typography.titleLarge)
        if (style.note != null) {
            Text(style.note, color = style.valueColor, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun HomeWifiCard(
    name: String,
    band: String,
    channel: String,
    enabled: Boolean,
    clients: Int?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val c = SfTheme.colors
    val hue = when (band) {
        "6 GHz", "60 GHz" -> SfHue.VIOLET
        "5 GHz" -> SfHue.BLUE
        else -> SfHue.GREEN
    }
    val shape = RoundedCornerShape(13.dp)
    Column(
        modifier.clip(shape).background(c.sunk).border(1.dp, c.line, shape)
            .clickable(onClickLabel = "View $name", onClick = onClick).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            HueTile(SfIcons.Wifi, hue, size = 35.dp)
            Pill(if (enabled) "On" else "Off", if (enabled) PillTone.Ok else PillTone.Off)
        }
        Text(name, style = MaterialTheme.typography.titleMedium, color = c.ink)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                listOf(band, channel.takeIf { it.isNotBlank() }?.let { "Ch $it" }.orEmpty())
                    .filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = c.muted,
                modifier = Modifier.weight(1f)
            )
            if (clients != null) {
                Text(
                    if (clients == 1) "1 device" else "$clients devices",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.muted
                )
            }
        }
        Text("View network ›", style = MaterialTheme.typography.bodySmall, color = c.accent, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Download (filled) and upload (line) on one scale. Owner feedback: smooth, real time, and no
 * jump back each second. Points sit at the time they were measured; the chart scrolls smoothly
 * in the draw phase at ~30 fps with cached [Path] instances so no per-frame allocations occur.
 */
@Composable
private fun TrafficGraph(times: List<Long>, down: List<Double>, up: List<Double>, palette: SfColors, windowMs: Long, modifier: Modifier = Modifier) {
    val clock = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        var lastFrameMs = 0L
        while (true) {
            withFrameMillis { frameMs ->
                if (frameMs - lastFrameMs >= GRAPH_FRAME_STEP_MS) {
                    lastFrameMs = frameMs
                    clock.longValue = System.currentTimeMillis()
                }
            }
        }
    }
    val target = remember(times, down, up, windowMs) {
        val cutoff = (times.lastOrNull() ?: 0L) - windowMs
        val first = times.indexOfFirst { it >= cutoff }.coerceAtLeast(0)
        var peak = 1.0
        for (i in first until down.size) if (down[i] > peak) peak = down[i]
        for (i in first until up.size) if (up[i] > peak) peak = up[i]
        peak.toFloat() * 1.15f
    }
    val max by animateFloatAsState(target, animationSpec = tween(800), label = "graph-scale")
    val downLine = remember { Path() }
    val downArea = remember { Path() }
    val upLine = remember { Path() }
    Canvas(modifier = modifier.clipToBounds()) {
        val n = minOf(times.size, down.size)
        if (n < 2) return@Canvas
        val w = size.width
        val h = size.height
        val shownNow = clock.longValue - GRAPH_DELAY_MS
        fun x(t: Long): Float = w - (shownNow - t).toFloat() / windowMs * w
        fun y(v: Double): Float = h - 2f - (h - 6f) * (v.toFloat() / max).coerceIn(0f, 1f)

        drawLine(palette.line, Offset(0f, h / 3f), Offset(w, h / 3f), 1f)
        drawLine(palette.line, Offset(0f, h * 2f / 3f), Offset(w, h * 2f / 3f), 1f)

        fun populateSmooth(path: Path, values: List<Double>, count: Int) {
            path.reset()
            for (i in 0 until count) {
                val px = x(times[i])
                val py = y(values[i])
                if (i == 0) {
                    path.moveTo(px, py)
                } else {
                    val qx = x(times[i - 1])
                    val qy = y(values[i - 1])
                    val mx = (qx + px) / 2f
                    path.cubicTo(mx, qy, mx, py, px, py)
                }
            }
        }

        populateSmooth(downLine, down, n)
        downArea.reset()
        downArea.addPath(downLine)
        downArea.lineTo(x(times[n - 1]), h)
        downArea.lineTo(x(times[0]), h)
        downArea.close()

        drawPath(downArea, palette.accentSoft)
        drawPath(downLine, palette.accent, style = Stroke(width = 4f, cap = StrokeCap.Round))
        val upCount = minOf(times.size, up.size)
        if (upCount >= 2) {
            populateSmooth(upLine, up, upCount)
            drawPath(upLine, palette.warn, style = Stroke(width = 3f, cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun Banner(text: String, background: Color, foreground: Color) {
    Text(
        text = text,
        color = foreground,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}
