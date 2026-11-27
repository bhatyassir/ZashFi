package com.shaazifi.app.feature.router

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shaazifi.app.feature.devices.DeviceKind
import com.shaazifi.app.feature.devices.hue
import com.shaazifi.app.feature.devices.icon
import com.shaazifi.app.ui.components.Eyebrow
import com.shaazifi.app.ui.components.HueTile
import com.shaazifi.app.ui.theme.SfHue
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme

private val SHARE_HUES = listOf(SfHue.BLUE, SfHue.VIOLET, SfHue.PINK, SfHue.TEAL, SfHue.ORANGE, SfHue.GREEN)

/** Splits a rate into number and unit for big text ("12.4" + "Mbit/s"). */
private fun rateParts(bytesPerSec: Double): Pair<String, String> {
    val t = RealtimeStats.rateText(bytesPerSec)
    val i = t.lastIndexOf(' ')
    return if (i > 0) t.substring(0, i) to t.substring(i + 1) else t to ""
}

/**
 * Owner feedback 2026-11-18: "Who is using the internet" on steroids. Total download and upload
 * now, each device's share, and per device: live down and up, a small 1-minute graph and the
 * internet addresses it talks to most (tap a device).
 */
@Composable
fun BandwidthPictureView(p: BandwidthPicture) {
    val c = SfTheme.colors
    val active = p.devices.filter { it.downBytesPerSec + it.upBytesPerSec >= 1_000 }
    val quiet = p.devices - active.toSet()
    var showQuiet by remember { mutableStateOf(false) }

    // Totals.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TotalTile("Download", "↓", p.totalDownBytesPerSec, SfHue.GREEN, Modifier.weight(1f))
        TotalTile("Upload", "↑", p.totalUpBytesPerSec, SfHue.ORANGE, Modifier.weight(1f))
    }

    // Who takes how much of the download.
    StackedShareBarCard(p.devices)

    Eyebrow(if (active.isEmpty()) "No device is using the internet right now" else "Devices now", modifier = Modifier.padding(top = 6.dp))
    val most = active.maxOfOrNull { maxOf(it.downBytesPerSec, it.upBytesPerSec) }?.coerceAtLeast(1.0) ?: 1.0
    active.forEach { d -> DeviceUsageCard(d, p.history[d.id].orEmpty(), most) }

    if (quiet.isNotEmpty()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(c.sunk)
                .clickable { showQuiet = !showQuiet }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Quiet devices (${quiet.size})", color = c.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(if (showQuiet) "Hide" else "Show", color = c.accent, fontWeight = FontWeight.SemiBold)
        }
        if (showQuiet) quiet.forEach { d -> DeviceUsageCard(d, p.history[d.id].orEmpty(), most) }
    }
}

@Composable
private fun TotalTile(label: String, arrow: String, bytesPerSec: Double, hue: SfHue, modifier: Modifier) {
    val (fg, bg) = hue.colors()
    val (num, unit) = rateParts(bytesPerSec)
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .padding(14.dp)
    ) {
        Text("$arrow $label", color = fg, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(num, color = fg, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
            Text(unit, color = fg, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

@Composable
private fun DeviceUsageCard(d: BandwidthMeter.DeviceRate, history: List<Pair<Double, Double>>, most: Double) {
    val c = SfTheme.colors
    var open by remember(d.id) { mutableStateOf(false) }
    val kind = DeviceKind.guess(d.name, null)
    val down = c.accent
    val up = c.warn
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(16.dp))
            .clickable(onClickLabel = "Show where ${d.name} connects") { open = !open }
            .animateContentSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HueTile(kind.icon(), kind.hue(), size = 40.dp, round = true)
            Column(Modifier.weight(1f)) {
                Text(d.name, color = c.ink, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${d.address} · ${if (d.connections == 1) "1 connection" else "${d.connections} connections"}", color = c.muted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("↓ ${RealtimeStats.rateText(d.downBytesPerSec)}", color = down, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("↑ ${RealtimeStats.rateText(d.upBytesPerSec)}", color = up, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
        }
        RateBar(d.downBytesPerSec, most, down)
        RateBar(d.upBytesPerSec, most, up)
        if (history.size >= 2) MiniGraph(history, down, up, Modifier.fillMaxWidth().height(36.dp))
        if (open) {
            RemoteEndpointsSection(d.top, emptyText = "No traffic in the last moments.")
        }
    }
}

/** Stacked horizontal share bar and legend for the top active devices. */
@Composable
internal fun StackedShareBarCard(devices: List<BandwidthMeter.DeviceRate>, modifier: Modifier = Modifier) {
    val c = SfTheme.colors
    val active = devices.filter { it.downBytesPerSec + it.upBytesPerSec >= 1_000 }
    val total = active.sumOf { it.downBytesPerSec + it.upBytesPerSec }
    if (active.isEmpty() || total <= 0.0) return
    Eyebrow("Share of the internet now", modifier = modifier.padding(top = 6.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(CircleShape)
        ) {
            active.take(6).forEachIndexed { i, d ->
                val share = ((d.downBytesPerSec + d.upBytesPerSec) / total).toFloat()
                val w by animateFloatAsState(share.coerceAtLeast(0.01f), tween(600), label = "share")
                Box(Modifier.weight(w).fillMaxHeight().background(SHARE_HUES[i % SHARE_HUES.size].colors().first))
            }
            if (active.size > 6) {
                val rest = active.drop(6).sumOf { it.downBytesPerSec + it.upBytesPerSec } / total
                Box(Modifier.weight(rest.toFloat().coerceAtLeast(0.01f)).fillMaxHeight().background(c.muted))
            }
        }
        active.take(6).chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { d ->
                    val i = active.indexOf(d)
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(SHARE_HUES[i % SHARE_HUES.size].colors().first))
                        Spacer(Modifier.width(6.dp))
                        Text(d.name, color = c.ink, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${(100 * (d.downBytesPerSec + d.upBytesPerSec) / total).toInt()}%", color = c.muted, fontSize = 12.sp)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Remote endpoints a device talks to most, with per-direction byte rates. */
@Composable
internal fun RemoteEndpointsSection(top: List<BandwidthMeter.Remote>, emptyText: String? = null) {
    val c = SfTheme.colors
    if (top.isEmpty()) {
        if (emptyText != null) Text(emptyText, style = SfText.sub, color = c.muted)
        return
    }
    Text("Talks most to", color = c.muted, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
    top.forEach { r ->
        val endpoint = if (r.port.isNotBlank()) "${r.proto}/${r.port}" else r.proto
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${r.remote}  $endpoint", style = SfText.mono, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("↓ ${RealtimeStats.rateText(r.downBytesPerSec)} ↑ ${RealtimeStats.rateText(r.upBytesPerSec)}", color = c.muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun RateBar(value: Double, most: Double, color: Color) {
    val c = SfTheme.colors
    val f by animateFloatAsState((value / most).toFloat().coerceIn(0f, 1f), tween(700), label = "rate")
    Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(c.sunk)) {
        Box(Modifier.fillMaxWidth(f.coerceAtLeast(0.005f)).height(5.dp).background(color))
    }
}

/** The device's last minute: download filled, upload as a line. */
@Composable
private fun MiniGraph(history: List<Pair<Double, Double>>, down: Color, up: Color, modifier: Modifier) {
    Canvas(modifier) {
        val n = history.size
        val max = history.maxOf { maxOf(it.first, it.second) }.coerceAtLeast(1.0)
        fun x(i: Int) = size.width * i / (n - 1).toFloat()
        fun y(v: Double) = size.height - 1f - (size.height - 2f) * (v / max).toFloat()
        val area = Path().apply { moveTo(0f, size.height) }
        val line = Path()
        val upLine = Path()
        history.forEachIndexed { i, (d, u) ->
            area.lineTo(x(i), y(d))
            if (i == 0) { line.moveTo(x(i), y(d)); upLine.moveTo(x(i), y(u)) } else { line.lineTo(x(i), y(d)); upLine.lineTo(x(i), y(u)) }
        }
        area.lineTo(size.width, size.height)
        area.close()
        drawPath(area, down.copy(alpha = 0.18f))
        drawPath(line, down, style = Stroke(width = 3f, cap = StrokeCap.Round))
        drawPath(upLine, up, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
        drawCircle(down, 4f, Offset(x(n - 1), y(history.last().first)))
    }
}
