package com.shaazifi.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shaazifi.app.ui.icons.SfIcons
import com.shaazifi.app.ui.theme.PillTone
import com.shaazifi.app.ui.theme.SfDisplayFont
import com.shaazifi.app.ui.theme.SfTheme
import com.shaazifi.app.ui.theme.SfText

/** One action in the top bar's overflow menu. */
data class TopBarAction(val label: String, val destructive: Boolean = false, val onClick: () -> Unit)

/**
 * The mock's `.top` bar: status dot, router name, a "Switch" pill, and an overflow
 * menu for less frequent actions (Reconnect, Sign out).
 */
@Composable
fun SfTopBar(
    title: String,
    modifier: Modifier = Modifier,
    online: Boolean = true,
    onSwitch: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    menu: List<TopBarAction> = emptyList()
) {
    val c = SfTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(c.bg)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 4.dp)
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (onBack != null) {
            Pill("‹ Back", tone = PillTone.Off, onClick = onBack)
        } else {
            StatusDot(on = online)
        }
        Text(
            title,
            fontFamily = SfDisplayFont,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = c.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (onSwitch != null) Pill("Switch", tone = PillTone.Off, onClick = onSwitch)
        if (menu.isNotEmpty()) {
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.semantics { contentDescription = "More actions" }) {
                    Icon(SfIcons.MoreVertical, contentDescription = null, tint = c.muted, modifier = Modifier.size(22.dp))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    menu.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(action.label, color = if (action.destructive) c.bad else c.ink) },
                            onClick = {
                                menuOpen = false
                                action.onClick()
                            }
                        )
                    }
                }
            }
        }
    }
}

data class BottomBarItem(val label: String, val icon: ImageVector, val accessibleLabel: String? = null)

@Composable
internal fun ReviewRouterBar(
    name: String,
    demo: Boolean,
    dark: Boolean,
    onSwitch: () -> Unit,
    onTheme: () -> Unit,
    menu: List<TopBarAction>,
    modifier: Modifier = Modifier
) {
    val c = SfTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.fillMaxWidth().background(c.bg)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 55.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.weight(1f).clip(shape).clickable(onClickLabel = "Switch router", onClick = onSwitch)
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Box(Modifier.size(35.dp).clip(shape).background(c.surface).border(1.dp, c.line, shape), contentAlignment = Alignment.Center) {
                    Icon(SfIcons.Router, null, tint = c.accent, modifier = Modifier.size(21.dp))
                }
                Column(Modifier.weight(1f, fill = false)) {
                    Text(name, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (demo) "Online · OpenWrt" else "OpenWrt", color = c.muted, fontSize = 11.sp)
                }
                Icon(SfIcons.ChevronRight, null, tint = c.muted, modifier = Modifier.size(13.dp))
            }
            if (demo) Pill("Demo", tone = PillTone.Off)
            IconButton(
                onClick = onTheme,
                modifier = Modifier.size(42.dp).border(1.dp, c.line, shape)
            ) {
                Icon(if (dark) SfIcons.Sun else SfIcons.Moon, if (dark) "Use light theme" else "Use dark theme",
                    tint = c.muted, modifier = Modifier.size(20.dp))
            }
            if (menu.isNotEmpty()) {
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                        Icon(SfIcons.MoreVertical, "More actions", tint = c.muted, modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        menu.forEach { action ->
                            DropdownMenuItem(
                                text = { Text(action.label, style = SfText.sub, color = if (action.destructive) c.bad else c.ink) },
                                onClick = { menuOpen = false; action.onClick() }
                            )
                        }
                    }
                }
            }
        }
        SfDivider()
    }
}

/** The mock's `.nav`: surface bar with a top hairline, line icons, accent for the current tab. */
@Composable
fun SfBottomBar(
    items: List<BottomBarItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = SfTheme.colors
    Column(modifier.fillMaxWidth().background(c.surface)) {
        SfDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 8.dp)
        ) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                val tint = if (selected) c.accent else c.muted
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) c.accentSoft else c.surface)
                        .clickable(role = Role.Tab) { onSelect(index) }
                        .semantics {
                            this.selected = selected
                            item.accessibleLabel?.let { contentDescription = it }
                        }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                    Text(item.label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
    }
}
