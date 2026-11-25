package com.shaazifi.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shaazifi.app.ui.icons.SfIcons
import com.shaazifi.app.ui.theme.PillTone
import com.shaazifi.app.ui.theme.SfDisplayFont
import com.shaazifi.app.ui.theme.SfHue
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme

@Composable
internal fun PageHeading(
    title: String,
    subtitle: String = "",
    status: String? = null,
    tone: PillTone = PillTone.Ok,
    actions: (@Composable () -> Unit)? = null
) {
    val c = SfTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.displayLarge, color = c.ink)
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = c.muted)
        if (status != null || actions != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (status != null) Pill(status, tone, modifier = Modifier.align(Alignment.CenterVertically))
                actions?.invoke()
            }
        }
    }
}

@Composable
internal fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier = modifier, style = MaterialTheme.typography.titleLarge, color = SfTheme.colors.ink)
}

@Composable
internal fun PageLink(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, back: Boolean = false) {
    val c = SfTheme.colors
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 42.dp).padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (back) Icon(SfIcons.ChevronLeft, null, tint = c.muted, modifier = Modifier.size(15.dp))
        Text(label, style = SfText.sub, fontWeight = FontWeight.SemiBold, color = if (back) c.muted else c.accent)
        if (!back) Icon(SfIcons.ChevronRight, null, tint = c.accent, modifier = Modifier.size(15.dp))
    }
}

@Composable
internal fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    percent: Int? = null,
    blue: Boolean = false
) {
    val c = SfTheme.colors
    val shape = RoundedCornerShape(11.dp)
    Column(
        modifier.clip(shape).background(c.sunk).border(1.dp, c.line, shape).padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, color = c.muted, style = MaterialTheme.typography.bodySmall)
        Text(value, color = c.ink, style = MaterialTheme.typography.titleLarge)
        if (!note.isNullOrBlank()) Text(note, color = c.muted, style = MaterialTheme.typography.bodySmall)
        if (percent != null) {
            val fill = if (blue) SfHue.BLUE.colors().first else c.accent
            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(c.line)) {
                Box(Modifier.fillMaxWidth(percent.coerceIn(0, 100) / 100f).height(5.dp).background(fill))
            }
        }
    }
}

@Composable
internal fun HistoryRange(minutes: Int, onRange: (Int) -> Unit) {
    val c = SfTheme.colors
    val shape = RoundedCornerShape(9.dp)
    Row(Modifier.clip(shape).border(1.dp, c.line, shape).background(c.sunk).padding(3.dp)) {
        listOf(1, 5).forEach { range ->
            val selected = minutes == range
            Box(
                Modifier.clip(RoundedCornerShape(6.dp)).background(if (selected) c.line else c.sunk)
                    .clickable(role = Role.Tab) { onRange(range) }.semantics { this.selected = selected }
                    .heightIn(min = 35.dp).padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("$range min", color = if (selected) c.ink else c.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
internal fun TrafficReading(label: String, reading: String, color: Color) {
    val c = SfTheme.colors
    val value = reading.substringBefore(' ')
    val unit = when (val suffix = reading.substringAfter(' ', "")) {
        "Gbit/s" -> "Gbps"
        "Mbit/s" -> "Mbps"
        "kbit/s" -> "Kbps"
        "bit/s" -> "bps"
        else -> suffix
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.muted)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(value, fontFamily = SfDisplayFont, fontWeight = FontWeight.Bold, fontSize = 34.sp, color = color)
            if (unit.isNotBlank()) Text(unit, style = MaterialTheme.typography.bodySmall, color = c.muted,
                modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

@Composable
internal fun WifiNetworkCard(
    name: String,
    band: String,
    channel: String,
    enabled: Boolean,
    clients: Int?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val c = SfTheme.colors
    val hue = if (band == "5 GHz") SfHue.BLUE else SfHue.GREEN
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
            Text(listOf(band, channel.takeIf { it.isNotBlank() }?.let { "Ch $it" }.orEmpty())
                .filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = c.muted, modifier = Modifier.weight(1f))
            if (clients != null) Text(if (clients == 1) "1 device" else "$clients devices",
                style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        Text("View network ›", style = MaterialTheme.typography.bodySmall, color = c.accent, fontWeight = FontWeight.SemiBold)
    }
}
