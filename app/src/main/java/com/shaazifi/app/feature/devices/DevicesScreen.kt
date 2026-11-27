package com.shaazifi.app.feature.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.shaazifi.app.ui.theme.SfHue
import com.shaazifi.app.ui.icons.SfIcons
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shaazifi.app.feature.router.LoadHolder
import com.shaazifi.app.feature.router.LoadStateContent
import com.shaazifi.app.feature.router.FeatureView
import com.shaazifi.app.feature.router.RealtimeStats
import com.shaazifi.app.ui.components.PageHeading
import com.shaazifi.app.ui.components.SectionTitle
import com.shaazifi.app.ui.components.Note
import com.shaazifi.app.ui.components.Pill
import com.shaazifi.app.ui.components.SfDivider
import com.shaazifi.app.ui.components.SfList
import com.shaazifi.app.ui.components.SfListRow
import com.shaazifi.app.ui.theme.PillTone
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme

/** Pill text and tone for a device, as in the mock (Online / Asleep / Blocked). */
fun devicePill(d: Device): Pair<String, PillTone> = when {
    d.blocked -> "Paused" to PillTone.Bad
    d.online -> "Online" to PillTone.Ok
    else -> "Offline" to PillTone.Off
}

/** The device list. The router shell owns selection and search state. */
@Composable
internal fun DevicesScreen(
    holder: LoadHolder<DeviceList>,
    onSignInAgain: () -> Unit,
    onOpenDevice: (Device) -> Unit,
    filter: String,
    onFilterChange: (String) -> Unit,
    kind: String,
    onKindChange: (String) -> Unit,
    sort: DeviceSort,
    onSortChange: (DeviceSort) -> Unit,
    collapsedNetworks: Set<String>,
    onToggleNetwork: (String) -> Unit,
    liveTraffic: FeatureView?,
    /** Kept by the caller so the list reopens where it was left. */
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
) {
    var sortOpen by remember { mutableStateOf(false) }
    val makers = rememberMakers()
    LoadStateContent(holder, onSignInAgain) { list, _ ->
        val c = SfTheme.colors
        val base = list.devices
        val matching = base.filter { device ->
            val maker = Makers.label(makers, device.mac).orEmpty()
            (when (kind) {
                "Online" -> device.online
                "Wi-Fi" -> device.connection.isWifi
                "Wired" -> device.connection == Connection.WIRED
                "All devices" -> true
                else -> device.network?.key == kind || base.none { it.network?.key == kind }
            }) && listOf(device.name, device.ip, device.mac, device.connection.label, device.network?.label.orEmpty(), maker)
                .any { it.contains(filter.trim(), true) }
        }
        val shown = when (sort) {
            DeviceSort.TRAFFIC -> matching.sortedWith(compareByDescending<Device> { matchDeviceRate(it, base, liveTraffic) ?: -1.0 }
                .thenBy { it.name.lowercase() })
            DeviceSort.NAME -> matching.sortedBy { it.name.lowercase() }
            DeviceSort.IP -> matching.sortedBy { addressSortKey(it.ip) }
        }
        val groups = shown.groupBy { it.network }
        val networks = base.mapNotNull { it.network }.distinctBy { it.key }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(c.bg),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                PageHeading("Devices", "Connected devices, networks and live traffic.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Pill("${list.online} online")
                    val picture = liveTraffic?.bandwidth
                    val combined = liveTraffic?.combinedTraffic
                    when {
                        picture != null -> {
                            Pill("↓ ${RealtimeStats.rateText(picture.totalDownBytesPerSec)}", PillTone.Off)
                            Pill("↑ ${RealtimeStats.rateText(picture.totalUpBytesPerSec)}", PillTone.Off)
                        }
                        combined != null -> Pill("Combined ${RealtimeStats.rateText(combined.values.sum())}", PillTone.Off)
                        else -> Pill("Traffic data unavailable", PillTone.Off)
                    }
                }
            }
            if (list.devices.isNotEmpty()) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = filter, onValueChange = onFilterChange, label = { Text("Name, IP or MAC") },
                            singleLine = true, shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f))
                        Box {
                            TextButton(onClick = { sortOpen = true }) { Text("${sort.label} ▾") }
                            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                                DeviceSort.entries.forEach { option ->
                                    DropdownMenuItem(text = { Text(option.label) }, onClick = { onSortChange(option); sortOpen = false })
                                }
                            }
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val options = listOf("All devices" to "All (${base.size})", "Online" to "Online") +
                            networks.map { it.key to it.label } + listOf("Wi-Fi" to "Wi-Fi", "Wired" to "Wired")
                        options.forEach { (id, label) ->
                            FilterChip(selected = kind == id, onClick = { onKindChange(id) }, label = { Text(label) })
                        }
                    }
                }
            }
            if (shown.isEmpty()) item {
                Note(if (list.devices.isEmpty()) "No devices found." else "No device matches \"$filter\".")
            }
            groups.forEach { (network, devices) ->
                val key = network?.key ?: "unknown"
                val collapsed = filter.isBlank() && key in collapsedNetworks
                item(key = "network:$key") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val header = listOfNotNull(network?.label, network?.subnet)
                            .joinToString(" · ").ifBlank { "Network not reported" }
                        SfListRow(header, onClick = { onToggleNetwork(key) }, trailing = {
                            Text("${devices.size} ${if (collapsed) "▸" else "▾"}", style = SfText.mono, color = c.muted)
                        })
                        if (!collapsed) SfList {
                            devices.forEachIndexed { i, d ->
                                if (i > 0) SfDivider()
                                val maker = Makers.label(makers, d.mac)
                                val randomized = Makers.isRandomized(d.mac)
                                val deviceKind = DeviceKind.guess(d.name, maker)
                                SfListRow(
                                    title = d.name,
                                    subtitle = listOfNotNull(
                                        d.ip.takeIf { it.isNotBlank() && it != d.name },
                                        d.connection.label,
                                        maker?.takeUnless { randomized }
                                    ).joinToString(" · "),
                                    icon = deviceKind.icon(),
                                    hue = deviceKind.hue(),
                                    onClick = { onOpenDevice(d) },
                                    trailing = {
                                        Column(
                                            horizontalAlignment = Alignment.End,
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            DeviceRate(d, base, liveTraffic)
                                            if (randomized) Pill("Private MAC", PillTone.Off)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
            if (!list.wifiKnown) {
                item { Note("The router did not say which devices are on Wi-Fi, so the connection type shows as unknown.") }
            }
        }
    }

}

internal enum class DeviceSort(val label: String) { TRAFFIC("Traffic"), NAME("Name"), IP("IP address") }

@Composable
private fun DeviceRate(device: Device, devices: List<Device>, view: FeatureView?) {
    val c = SfTheme.colors
    val split = device.splitTraffic(devices, view)
    val combined = device.uniqueOnlineIp(devices)?.let { view?.combinedTraffic?.get(it) }
    Column(horizontalAlignment = Alignment.End) {
        when {
            !device.online -> Text("Offline", style = SfText.sub, color = c.muted)
            split != null -> {
                Text("↓ ${RealtimeStats.rateText(split.downBytesPerSec)}", style = SfText.mono, color = c.accent)
                Text("↑ ${RealtimeStats.rateText(split.upBytesPerSec)}", style = SfText.mono, color = c.muted)
            }
            combined != null -> {
                Text(RealtimeStats.rateText(combined), style = SfText.mono, color = c.accent)
                Text("Combined", style = SfText.sub, color = c.muted)
            }
            else -> Text("No reading", style = SfText.sub, color = c.muted)
        }
    }
}

private val Connection.isWifi: Boolean
    get() = this == Connection.WIFI || this == Connection.WIFI_24 || this == Connection.WIFI_5 || this == Connection.WIFI_6

/** Picture and colour for each kind of device. */
fun DeviceKind.icon(): ImageVector = when (this) {
    DeviceKind.PHONE -> SfIcons.Phone
    DeviceKind.COMPUTER -> SfIcons.Laptop
    DeviceKind.TV -> SfIcons.Tv
    DeviceKind.CONSOLE -> SfIcons.Gamepad
    DeviceKind.PRINTER -> SfIcons.Printer
    DeviceKind.SPEAKER -> SfIcons.Speaker
    DeviceKind.CAMERA -> SfIcons.Camera
    DeviceKind.SMART_HOME -> SfIcons.Bulb
    DeviceKind.NETWORK -> SfIcons.Router
    DeviceKind.OTHER -> SfIcons.Devices
}

fun DeviceKind.hue(): SfHue = when (this) {
    DeviceKind.PHONE -> SfHue.BLUE
    DeviceKind.COMPUTER -> SfHue.VIOLET
    DeviceKind.TV -> SfHue.PINK
    DeviceKind.CONSOLE -> SfHue.ORANGE
    DeviceKind.PRINTER -> SfHue.SLATE
    DeviceKind.SPEAKER -> SfHue.TEAL
    DeviceKind.CAMERA -> SfHue.RED
    DeviceKind.SMART_HOME -> SfHue.GREEN
    DeviceKind.NETWORK -> SfHue.TEAL
    DeviceKind.OTHER -> SfHue.SLATE
}

private enum class DeviceEdit { NAME, ADDRESS }

@Composable
internal fun DeviceDetailsAndActions(
    d: Device,
    routerPageAction: @Composable () -> Unit,
    /** Shows the plan for confirmation; returns an error message when the input is not valid. */
    onRequest: ((DeviceChangeResult) -> String?)?,
    routerIp: String,
    networkName: String?,
    onNetwork: (() -> Unit)?,
    onParental: () -> Unit
) {
    val c = SfTheme.colors
    val makers = rememberMakers()
    val maker = Makers.label(makers, d.mac)
    val randomized = Makers.isRandomized(d.mac)
    var editing by remember(d.mac) { mutableStateOf<DeviceEdit?>(null) }
    var error by remember(d.mac) { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionTitle("Connection")
        SfList {
            SfListRow(title = "Network", subtitle = networkName ?: d.connection.label,
                icon = if (d.connection.isWifi) SfIcons.Wifi else SfIcons.Port, hue = SfHue.BLUE, onClick = onNetwork)
            SfDivider()
            SfListRow(title = "IP address", icon = SfIcons.Globe, hue = SfHue.BLUE,
                trailing = { Text(d.ip.ifBlank { "Unavailable" }, style = SfText.mono, color = c.muted) })
            SfDivider()
            SfListRow(
                title = "MAC address",
                subtitle = if (randomized) "Randomized (private) address" else maker,
                icon = SfIcons.Devices,
                hue = SfHue.VIOLET,
                trailing = {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(d.mac, style = SfText.mono, color = c.muted, softWrap = true)
                        if (randomized) Pill("Private MAC", PillTone.Warn)
                    }
                }
            )
            d.fixedIp?.let {
                SfDivider()
                SfListRow(title = "Fixed address", subtitle = it, icon = SfIcons.Pin, hue = SfHue.VIOLET)
            }
        }
        SectionTitle("Device controls")
        SfList {
            if (onRequest != null) {
                SfListRow("Rename device", icon = SfIcons.Pencil, hue = SfHue.GREEN,
                    onClick = { editing = DeviceEdit.NAME; error = null })
                SfDivider()
            }
            SfListRow("Parental controls", icon = SfIcons.Shield, hue = SfHue.VIOLET, onClick = onParental)
            if (onRequest != null) {
                SfDivider()
                SfListRow("Fix address", icon = SfIcons.Pin, hue = SfHue.VIOLET,
                    onClick = { editing = DeviceEdit.ADDRESS; error = null })
                SfDivider()
                SfListRow("Wake device", icon = SfIcons.Bolt, hue = SfHue.ORANGE,
                    onClick = { error = onRequest(DeviceChanges.wake(d)) })
            }
        }
        if (editing == null) error?.let { Text(it, style = SfText.sub, color = c.bad) }
        routerPageAction()
    }

    if (onRequest != null) when (editing) {
        DeviceEdit.NAME -> EditDialog(
            title = "Rename device", label = "Name", initial = d.name, error = error,
            onDismiss = { editing = null; error = null },
            onSave = { v -> error = onRequest(DeviceChanges.rename(d, v)); if (error == null) editing = null }
        )
        DeviceEdit.ADDRESS -> EditDialog(
            title = "Always use this IP address", label = "Fixed address", initial = d.fixedIp ?: d.ip, error = error,
            onDismiss = { editing = null; error = null },
            onSave = { v -> error = onRequest(DeviceChanges.setFixedIp(d, v, routerIp)); if (error == null) editing = null },
            onRemove = if (d.fixedIp != null) ({ error = onRequest(DeviceChanges.removeFixedIp(d)); if (error == null) editing = null }) else null
        )
        null -> Unit
    }
}

@Composable
private fun EditDialog(
    title: String,
    label: String,
    initial: String,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onRemove: (() -> Unit)? = null
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value, onValueChange = { value = it },
                    label = { Text(label) }, singleLine = true,
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it, style = SfText.sub, color = SfTheme.colors.bad) }
                if (onRemove != null) TextButton(onClick = onRemove) { Text("Remove the fixed address") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
