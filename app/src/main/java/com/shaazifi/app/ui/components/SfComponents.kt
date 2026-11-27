package com.shaazifi.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shaazifi.app.ui.theme.PillTone
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme
import com.shaazifi.app.ui.theme.pillColors

/*
 * Shared building blocks matching the approved mock's CSS classes
 * (.card, .list, .ico, .pill, .eyebrow, .big, .sw, .btn, .btn.ghost, .note, .dot).
 */

private val CardShape = RoundedCornerShape(16.dp)
private val ButtonShape = RoundedCornerShape(9.dp)

/** `.card`: surface, 1 dp line border, 16 dp corners, 14 dp padding. */
@Composable
fun SfCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    verticalSpacing: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = SfTheme.colors
    Column(
        modifier = modifier
            .clip(CardShape)
            .background(c.surface)
            .border(1.dp, c.line, CardShape)
            .then(
                if (onClick != null) Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick)
                else Modifier
            )
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        content = content
    )
}

/** `.list`: one bordered surface holding rows separated by hairlines. Use [SfListRow] inside. */
@Composable
fun SfList(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = SfTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(c.surface)
            .border(1.dp, c.line, CardShape),
        content = content
    )
}

/** Hairline between list rows. */
@Composable
fun SfDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(SfTheme.colors.line)
    )
}

/** `.list > *`: a row with optional icon tile, title, subtitle and trailing content. */
@Composable
fun SfListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleMono: Boolean = false,
    icon: ImageVector? = null,
    iconLive: Boolean = false,
    onClick: (() -> Unit)? = null,
    /** A coloured icon tile instead of the plain one. */
    hue: com.shaazifi.app.ui.theme.SfHue? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val c = SfTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (icon != null) {
            if (hue != null) HueTile(icon = icon, hue = hue) else IconTile(icon = icon, live = iconLive)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = if (subtitleMono) SfText.mono else SfText.sub,
                    color = c.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (trailing != null) trailing()
    }
}

/** `.ico` / `.ico.live`: 38 dp tile, 11 dp corners, 20 dp line icon. */
@Composable
fun IconTile(icon: ImageVector, live: Boolean = false, modifier: Modifier = Modifier) {
    val c = SfTheme.colors
    Box(
        modifier = modifier
            .size(38.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (live) c.accentSoft else c.sunk),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = if (live) c.accent else c.ink, modifier = Modifier.size(20.dp))
    }
}

/** `.pill`: small rounded label. Clickable when [onClick] is set (for example the mock's "Switch"). */
@Composable
fun Pill(text: String, tone: PillTone = PillTone.Ok, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val (bg, fg) = pillColors(tone, SfTheme.colors)
    if (onClick != null) {
        Box(
            modifier = modifier
                .heightIn(min = 48.dp)
                .widthIn(min = 48.dp)
                .clip(RoundedCornerShape(7.dp))
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(bg)
                    .padding(horizontal = 9.dp, vertical = 3.dp)
            ) {
                Text(text, color = fg, style = SfText.eyebrow.copy(letterSpacing = SfText.sub.letterSpacing), maxLines = 1)
            }
        }
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(7.dp))
                .background(bg)
                .padding(horizontal = 9.dp, vertical = 3.dp)
        ) {
            Text(text, color = fg, style = SfText.eyebrow.copy(letterSpacing = SfText.sub.letterSpacing), maxLines = 1)
        }
    }
}

/** `.eyebrow`: small uppercase label above a value or section. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = SfText.eyebrow, color = SfTheme.colors.muted, modifier = modifier)
}

/** `.big` with an optional `.sub` unit: "42.6 Mbps". */
@Composable
fun BigNumber(
    value: String,
    unit: String? = null,
    modifier: Modifier = Modifier,
    color: Color = SfTheme.colors.ink,
    valueStyle: TextStyle = SfText.big,
    unitStyle: TextStyle = SfText.sub
) {
    val c = SfTheme.colors
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        Text(value, style = valueStyle, color = color)
        if (!unit.isNullOrBlank()) {
            Spacer(Modifier.width(4.dp))
            Text(unit, style = unitStyle, color = c.muted, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

/** `.sw`: the mock's 44 x 26 switch. Display only; put the toggle on the surrounding row. */
@Composable
fun SfSwitch(checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = SfTheme.colors
    val track by animateColorAsState(if (checked) c.accent else c.line, label = "track")
    val knobX by animateDpAsState(if (checked) 21.dp else 3.dp, label = "knob")
    Box(
        modifier = modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(CircleShape)
            .background(if (enabled) track else track.copy(alpha = 0.5f))
    ) {
        Box(
            Modifier
                .offset(x = knobX, y = 3.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(c.surface)
        )
    }
}

/** `button.card.row[role=switch]`: a card that toggles, with title, subtitle and switch. */
@Composable
fun SwitchCard(
    title: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true
) {
    val c = SfTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(CardShape)
            .background(c.surface)
            .border(1.dp, c.line, CardShape)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onToggle)
            .semantics { stateDescription = if (checked) "On" else "Off" }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, style = SfText.sub, color = c.muted)
        }
        SfSwitch(checked = checked, enabled = enabled)
    }
}

/** A switch row for use inside [SfList]. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true
) {
    val c = SfTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onToggle)
            .semantics { stateDescription = if (checked) "On" else "Off" }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, style = SfText.sub, color = c.muted)
        }
        SfSwitch(checked = checked, enabled = enabled)
    }
}

/** `.btn`: full-width accent button. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = SfTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = c.accent, contentColor = c.accentInk,
            disabledContainerColor = c.line, disabledContentColor = c.muted
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 13.dp),
        modifier = modifier.fillMaxWidth()
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

/** `.btn.ghost`: full-width surface button with a line border. */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = SfTheme.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = ButtonShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, c.line),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = c.surface, contentColor = c.ink, disabledContentColor = c.muted
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 13.dp),
        modifier = modifier.fillMaxWidth()
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

/** `.note`: small muted text in a dashed box. */
@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    val c = SfTheme.colors
    Text(
        text,
        style = SfText.sub.copy(fontSize = SfText.mono.fontSize),
        color = c.muted,
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = c.line,
                    cornerRadius = CornerRadius(10.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
                )
            }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    )
}

/** `.dot`: a small status dot (accent when on, muted when off). */
@Composable
fun StatusDot(on: Boolean = true, modifier: Modifier = Modifier) {
    val c = SfTheme.colors
    Box(
        modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(if (on) c.accent else c.muted)
            .semantics { contentDescription = if (on) "Online" else "Offline" }
    )
}

/** A coloured rounded tile with a line icon (owner feedback: a livelier look). */
@Composable
fun HueTile(icon: ImageVector, hue: com.shaazifi.app.ui.theme.SfHue, modifier: Modifier = Modifier, size: Dp = 38.dp, round: Boolean = false) {
    val (fg, bg) = hue.colors()
    Box(
        modifier = modifier
            .size(size)
            .clip(if (round) CircleShape else RoundedCornerShape(size * 0.3f))
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(size * 0.53f))
    }
}

/** Owner feedback 2026-11-18: the coloured page header used on every screen. */
@Composable
fun PageHero(icon: ImageVector, hue: com.shaazifi.app.ui.theme.SfHue, title: String, kicker: String, summary: String? = null, pills: (@Composable () -> Unit)? = null) {
    val c = SfTheme.colors
    val (fg, bg) = hue.colors()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(46.dp).clip(CircleShape).background(c.surface), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = c.ink, fontWeight = FontWeight.Bold)
                Text(kicker, color = fg, style = SfText.sub, fontWeight = FontWeight.SemiBold)
            }
        }
        summary?.let { Text(it, style = SfText.sub, color = c.ink) }
        pills?.let { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { it() } }
    }
}

/** Section heading: either a plain title or, when [hue] is given, an eyebrow with a coloured bar. */
@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    hue: com.shaazifi.app.ui.theme.SfHue? = null
) {
    if (hue != null) {
        val (fg, _) = hue.colors()
        Row(
            modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(Modifier.size(width = 4.dp, height = 16.dp).clip(CircleShape).background(fg))
            Eyebrow(title)
        }
    } else {
        Text(title, modifier = modifier, style = MaterialTheme.typography.titleLarge, color = SfTheme.colors.ink)
    }
}

/** A section title with a small coloured bar. */
@Composable
fun SectionBar(text: String, hue: com.shaazifi.app.ui.theme.SfHue, modifier: Modifier = Modifier) {
    SectionTitle(title = text, modifier = modifier, hue = hue)
}
