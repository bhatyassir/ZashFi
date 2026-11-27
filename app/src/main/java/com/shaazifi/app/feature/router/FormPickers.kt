package com.shaazifi.app.feature.router

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shaazifi.app.feature.devices.Device
import com.shaazifi.app.feature.devices.DeviceKind
import com.shaazifi.app.feature.devices.hue
import com.shaazifi.app.feature.devices.icon
import com.shaazifi.app.ui.components.HueTile
import com.shaazifi.app.ui.icons.SfIcons
import com.shaazifi.app.ui.theme.SfTheme

/** The router's devices, for pickers in change forms (provided by the signed-in shell). */
val LocalKnownDevices = compositionLocalOf<List<Device>> { emptyList() }

/**
 * Owner feedback 2026-11-18: "show the list of devices here too to let the user choose from".
 * Tapping a device fills the field with its hardware address or IP address. Online devices first.
 */
@Composable
fun DevicePicker(kind: PickKind, value: String, onPick: (String) -> Unit) {
    val c = SfTheme.colors
    val devices = LocalKnownDevices.current
        .filter { if (kind == PickKind.DEVICE_IP) it.ip.isNotBlank() else it.mac.isNotBlank() }
        .sortedWith(compareByDescending<Device> { it.online }.thenBy { it.name.lowercase() })
    if (devices.isEmpty()) {
        Text("Open the Devices tab once to choose from a list, or type below.", color = c.muted, fontSize = 12.sp)
        return
    }
    var showAll by remember { mutableStateOf(false) }
    val shown = if (showAll) devices else devices.take(6)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, c.line, RoundedCornerShape(14.dp))
    ) {
        Text("Choose a device", color = c.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp))
        shown.forEach { d ->
            val pickValue = if (kind == PickKind.DEVICE_IP) d.ip else d.mac
            val selected = value.equals(pickValue, ignoreCase = true) || value.equals(d.name, ignoreCase = true)
            val dk = DeviceKind.guess(d.name, null)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (selected) c.accentSoft else c.surface)
                    .clickable(onClickLabel = "Choose ${d.name}") { onPick(pickValue) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                HueTile(dk.icon(), if (d.online) dk.hue() else com.shaazifi.app.ui.theme.SfHue.SLATE, size = 32.dp, round = true)
                Column(Modifier.weight(1f)) {
                    Text(d.name, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(d.ip, if (d.online) "online" else "offline").filter { it.isNotBlank() }.joinToString(" · "), color = c.muted, fontSize = 11.5.sp)
                }
                if (selected) Icon(SfIcons.Check, contentDescription = "Chosen", tint = c.accent, modifier = Modifier.size(20.dp))
            }
        }
        if (devices.size > 6) {
            Text(
                if (showAll) "Show fewer" else "Show all ${devices.size} devices",
                color = c.accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().clickable { showAll = !showAll }.padding(12.dp)
            )
        }
    }
}

/**
 * Toggle one address in a comma/space separated field. Pure so the selection logic can be tested
 * on the JVM; the picker below and the text field share the same string.
 */
internal fun togglePickerValue(value: String, item: String): String {
    val items = LinkedHashMap<String, String>()
    value.split(Regex("[\\s,]+")).map { it.trim() }.filter { it.isNotEmpty() }.forEach {
        items.putIfAbsent(it.lowercase(), it)
    }
    val key = item.lowercase()
    if (items.containsKey(key)) items.remove(key) else items[key] = item
    return items.values.joinToString(", ")
}

/**
 * Multi-select version of [DevicePicker]: tapping a device adds or removes its address from the
 * field instead of replacing it. Used by MAC filtering, where several devices are allowed.
 */
@Composable
fun MultiDevicePicker(kind: PickKind, value: String, onPick: (String) -> Unit) {
    val c = SfTheme.colors
    val devices = LocalKnownDevices.current
        .filter { if (kind == PickKind.DEVICE_IP) it.ip.isNotBlank() else it.mac.isNotBlank() }
        .sortedWith(compareByDescending<Device> { it.online }.thenBy { it.name.lowercase() })
    if (devices.isEmpty()) {
        Text("Open the Devices tab once to choose from a list, or type addresses below.", color = c.muted, fontSize = 12.sp)
        return
    }
    val chosen = value.split(Regex("[\\s,]+")).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    var showAll by remember { mutableStateOf(false) }
    val shown = if (showAll) devices else devices.take(6)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp))
    ) {
        Text(
            "Choose devices (tap to add or remove)",
            color = c.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp)
        )
        shown.forEach { d ->
            val pickValue = if (kind == PickKind.DEVICE_IP) d.ip else d.mac
            val selected = chosen.any { it.equals(pickValue, ignoreCase = true) }
            val dk = DeviceKind.guess(d.name, null)
            Row(
                Modifier.fillMaxWidth()
                    .background(if (selected) c.accentSoft else c.surface)
                    .clickable(onClickLabel = if (selected) "Remove ${d.name}" else "Add ${d.name}") {
                        onPick(togglePickerValue(value, pickValue))
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                HueTile(dk.icon(), if (d.online) dk.hue() else com.shaazifi.app.ui.theme.SfHue.SLATE, size = 32.dp, round = true)
                Column(Modifier.weight(1f)) {
                    Text(d.name, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(pickValue, color = c.muted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (selected) Icon(SfIcons.Check, contentDescription = "Selected", tint = c.accent, modifier = Modifier.size(20.dp))
            }
        }
        if (devices.size > 6) {
            Text(
                if (showAll) "Show fewer" else "Show all ${devices.size} devices",
                color = c.accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().clickable { showAll = !showAll }.padding(12.dp)
            )
        }
    }
}

/** Tappable answers for a field (single choice, or several joined by spaces). */
@Composable
fun ChoiceChips(choices: List<String>, multi: Boolean, value: String, onChange: (String) -> Unit) {
    val c = SfTheme.colors
    val chosen = value.split(Regex("[\\s,]+")).filter { it.isNotBlank() }.map { it.lowercase() }.toSet()
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        choices.forEach { ch ->
            val on = ch.lowercase() in chosen
            Text(
                ch,
                color = if (on) c.accentInk else c.ink,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (on) c.accent else c.sunk)
                    .clickable {
                        if (!multi) onChange(ch) else {
                            val next = if (on) choices.filter { it.lowercase() in chosen && it != ch } else choices.filter { it.lowercase() in chosen || it == ch }
                            onChange(next.joinToString(" "))
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}
