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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shaazifi.app.feature.home.HomeParsers
import com.shaazifi.app.ui.components.Eyebrow
import com.shaazifi.app.ui.components.HueTile
import com.shaazifi.app.ui.icons.SfIcons
import com.shaazifi.app.ui.theme.SfHue
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme

/**
 * Owner feedback 2026-11-17: "give the interfaces and ports pages a better GUI makeover".
 * The cable ports are drawn like the back of the router (lit when a cable is in), Wi-Fi and
 * virtual ports as chips, and each network as a card with its address and traffic.
 */
@Composable
fun NetworkPictureView(p: NetworkPicture, onLiveTraffic: (() -> Unit)? = null) {
    val c = SfTheme.colors
    val cablePorts = p.ports.filter { it.kind == PortKind.INTERNET || it.kind == PortKind.LAN }
    val wireless = p.ports.filter { it.kind == PortKind.WIRELESS }
    val virtual = p.ports.filter { it.kind == PortKind.BRIDGE || it.kind == PortKind.OTHER }
    var selected by remember(p) { mutableStateOf(cablePorts.firstOrNull { it.kind == PortKind.INTERNET }?.name) }

    if (cablePorts.isNotEmpty()) {
        com.shaazifi.app.ui.components.SectionTitle("Router ports")
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(c.surface)
                .border(1.dp, c.line, RoundedCornerShape(18.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // The "back panel": up to five ports per row.
            cablePorts.chunked(5).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { port ->
                        PortTile(port, selected == port.name, Modifier.weight(1f)) {
                            selected = if (selected == port.name) null else port.name
                        }
                    }
                    repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Legend(SfHue.BLUE, "Internet")
                Legend(SfHue.GREEN, "Home devices")
                Legend(SfHue.SLATE, "Unplugged")
            }
            cablePorts.firstOrNull { it.name == selected }?.let { PortDetail(it) }
        }
    }

    if (p.links.isNotEmpty()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            com.shaazifi.app.ui.components.SectionTitle("Networks")
            onLiveTraffic?.let { com.shaazifi.app.ui.components.PageLink("Live traffic", it) }
        }
        val most = p.links.maxOfOrNull { maxOf(it.rxBytes ?: 0, it.txBytes ?: 0) }?.coerceAtLeast(1) ?: 1
        p.links.forEach { LinkCard(it, most) }
    }

    if (wireless.isNotEmpty()) {
        com.shaazifi.app.ui.components.SectionTitle("Wireless interfaces")
        wireless.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { w -> SmallPortCard(w, SfIcons.Wifi, SfHue.TEAL, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }

    if (virtual.isNotEmpty()) {
        Eyebrow("Bridges and virtual ports", modifier = Modifier.padding(top = 6.dp))
        virtual.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { v -> SmallPortCard(v, SfIcons.Link, SfHue.VIOLET, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun speedText(mbps: Int?): String = when {
    mbps == null -> "—"
    mbps >= 1000 && mbps % 1000 == 0 -> "${mbps / 1000}G"
    mbps >= 1000 -> String.format(java.util.Locale.ROOT, "%.1fG", mbps / 1000.0)
    else -> "${mbps}M"
}

@Composable
private fun PortTile(port: NetPort, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = SfTheme.colors
    val hue = when {
        !port.connected -> SfHue.SLATE
        port.kind == PortKind.INTERNET -> SfHue.BLUE
        else -> SfHue.GREEN
    }
    val (fg, bg) = hue.colors()
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (port.connected) bg else c.sunk)
            .border(if (selected) 2.dp else 0.dp, if (selected) fg else bg, RoundedCornerShape(12.dp))
            .clickable(onClickLabel = "Show port ${port.name}", onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(SfIcons.Port, contentDescription = null, tint = if (port.connected) fg else c.muted, modifier = Modifier.size(22.dp))
        Text(port.name, color = if (port.connected) fg else c.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (port.connected) speedText(port.speedMbps) else "off", color = c.muted, fontSize = 10.5.sp)
    }
}

@Composable
private fun Legend(hue: SfHue, label: String) {
    val (fg, _) = hue.colors()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(fg))
        Spacer(Modifier.width(5.dp))
        Text(label, color = SfTheme.colors.muted, fontSize = 11.5.sp)
    }
}

@Composable
private fun PortDetail(port: NetPort) {
    val c = SfTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.sunk)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            "${port.name} · " + if (port.kind == PortKind.INTERNET) "internet port" else "home port",
            color = c.ink, fontWeight = FontWeight.SemiBold
        )
        Text(
            if (port.connected) listOfNotNull(port.speedMbps?.let { "$it Mb/s" } ?: "Speed unknown", port.duplex?.let { "$it duplex" }).joinToString(" · ")
            else "No cable plugged in",
            style = SfText.sub, color = c.muted
        )
        Text("↓ ${HomeParsers.formatBytes(port.rxBytes)}  ↑ ${HomeParsers.formatBytes(port.txBytes)} since the router started", style = SfText.sub, color = c.muted)
    }
}

@Composable
private fun SmallPortCard(port: NetPort, icon: ImageVector, hue: SfHue, modifier: Modifier) {
    val c = SfTheme.colors
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(14.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        HueTile(icon, if (port.connected) hue else SfHue.SLATE, size = 34.dp)
        Column(Modifier.weight(1f)) {
            Text(port.name, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("↓ ${HomeParsers.formatBytes(port.rxBytes)} ↑ ${HomeParsers.formatBytes(port.txBytes)}", color = c.muted, fontSize = 11.5.sp, maxLines = 1)
        }
    }
}

private fun linkLook(name: String, protocol: String): Pair<ImageVector, SfHue> {
    val n = name.lowercase(java.util.Locale.ROOT)
    val pr = protocol.lowercase(java.util.Locale.ROOT)
    return when {
        n.startsWith("wan") -> SfIcons.Globe to SfHue.BLUE
        n.startsWith("lan") -> SfIcons.Home to SfHue.GREEN
        n.contains("guest") -> SfIcons.Wifi to SfHue.TEAL
        n.contains("vpn") || n.contains("wg") || n.contains("tailscale") || pr.contains("wireguard") || pr.contains("vpn") -> SfIcons.Lock to SfHue.PINK
        else -> SfIcons.Link to SfHue.VIOLET
    }
}

@Composable
private fun LinkCard(l: NetLink, most: Long) {
    val c = SfTheme.colors
    val (icon, hue) = linkLook(l.name, l.protocol)
    val (fg, _) = hue.colors()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HueTile(icon, if (l.up) hue else SfHue.SLATE, size = 40.dp)
            Column(Modifier.weight(1f)) {
                Text(l.name.uppercase(java.util.Locale.ROOT), color = c.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(l.protocol, color = c.muted, fontSize = 12.5.sp)
            }
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(if (l.up) c.accentSoft else c.sunk)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (l.up) c.accent else c.muted))
                Spacer(Modifier.width(5.dp))
                Text(if (l.up) "Up" else "Down", color = if (l.up) c.accent else c.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        val facts = listOfNotNull(l.address, l.device, l.uptimeSec?.let { "up ${HomeParsers.formatUptime(it)}" })
        if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = SfText.mono, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (l.rxBytes != null && l.txBytes != null) {
            TrafficBar("↓", l.rxBytes, most, fg)
            TrafficBar("↑", l.txBytes, most, c.warn)
        }
    }
}

@Composable
private fun TrafficBar(arrow: String, bytes: Long, most: Long, color: androidx.compose.ui.graphics.Color) {
    val c = SfTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(arrow, color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Box(
            Modifier
                .weight(1f)
                .height(6.dp)
                .clip(CircleShape)
                .background(c.sunk)
        ) {
            Box(
                Modifier
                    .fillMaxWidth((bytes.toFloat() / most).coerceIn(0.02f, 1f))
                    .height(6.dp)
                    .background(color)
            )
        }
        Text(HomeParsers.formatBytes(bytes), color = c.ink, fontSize = 12.sp, modifier = Modifier.width(72.dp), maxLines = 1)
    }
}
