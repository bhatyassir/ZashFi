package com.shaazifi.app.feature.router

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shaazifi.app.feature.devices.DeviceList
import com.shaazifi.app.feature.home.WifiNetworkKey
import com.shaazifi.app.ui.components.Eyebrow
import com.shaazifi.app.ui.components.HueTile
import com.shaazifi.app.ui.components.MetricTile
import com.shaazifi.app.ui.components.PageHeading
import com.shaazifi.app.ui.components.SectionTitle
import com.shaazifi.app.ui.components.SfCard
import com.shaazifi.app.ui.components.WifiNetworkCard
import com.shaazifi.app.ui.components.GhostButton
import com.shaazifi.app.ui.components.Note
import com.shaazifi.app.ui.components.Pill
import com.shaazifi.app.ui.components.PrimaryButton
import com.shaazifi.app.ui.components.SfDivider
import com.shaazifi.app.ui.components.SfList
import com.shaazifi.app.ui.components.SfListRow
import com.shaazifi.app.ui.icons.SfIcons
import com.shaazifi.app.ui.theme.PillTone
import com.shaazifi.app.ui.theme.SfHue
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme

internal fun ChangeController.submit(result: PlanResult): String? = when (result) {
    is PlanResult.Ready -> { request(result.plan); null }
    is PlanResult.Invalid -> result.message
}

internal val DemoWifiOverview = FeatureView(
    "Networks and radio settings",
    ViewPill("2 of 2 radios on", PillTone.Ok),
    wifiNetworks = listOf(
        WifiNetworkDetail(WifiNetworkKey("radio0", "home", "wlan0"), "Demo Home", "2.4 GHz",
            FeatureView("2.4 GHz · WPA2", ViewPill("On", PillTone.Ok),
                listOf(ViewSection("Network settings", listOf(ViewRow("Channel", "6"), ViewRow("Channel width", "HE20"))))),
            channel = "6", security = "WPA2", enabled = true),
        WifiNetworkDetail(WifiNetworkKey("radio1", "home5g", "wlan1"), "Demo Home 5G", "5 GHz",
            FeatureView("5 GHz · WPA2/WPA3", ViewPill("On", PillTone.Ok),
                listOf(ViewSection("Network settings", listOf(ViewRow("Channel", "36"), ViewRow("Channel width", "HE80"))))),
            channel = "36", security = "WPA2/WPA3", enabled = true)
    )
)

@Composable
internal fun WifiOverview(
    spec: FeatureSpec,
    holder: LoadHolder<FeatureView>,
    scrollState: ScrollState,
    devices: DeviceList?,
    onSignInAgain: () -> Unit,
    onOpen: (WifiNetworkKey) -> Unit,
    changes: ChangeController?,
    footer: @Composable ColumnScope.() -> Unit
) {
    LoadStateContent(holder, onSignInAgain) { view, demo ->
        if (view.wifiNetworks.isEmpty()) {
            FeatureViewContent(spec, view, null, if (demo) null else changes, footer = footer)
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PageHeading("Wi-Fi", "Manage your networks and wireless radios.", view.pill?.text, view.pill?.tone ?: PillTone.Off)
                view.wifiNetworks.forEach { network ->
                    val clients = network.key.ifname?.let { devices?.wifiClients?.get(it)?.size }
                    WifiNetworkCard(network.name, network.band, network.channel, network.view.pill?.tone == PillTone.Ok,
                        clients, Modifier.fillMaxWidth()) { onOpen(network.key) }
                }
                footer()
            }
        }
    }
}

@Composable
internal fun WifiNetworkScreen(
    holder: LoadHolder<FeatureView>,
    key: WifiNetworkKey,
    devices: DeviceList?,
    onSignInAgain: () -> Unit,
    changes: ChangeController?,
    onDevice: (String) -> Unit
) {
    LoadStateContent(holder, onSignInAgain) { view, _ ->
        val network = view.wifiNetworks.singleOrNull { it.key.matches(key) }
        if (network == null) {
            Note("This Wi-Fi network is unavailable.", modifier = Modifier.padding(16.dp))
        } else {
            val clients = network.key.ifname?.let { devices?.wifiClients?.get(it) }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val status = network.view.pill
                val settings = network.view.sections.firstOrNull()
                val hue = if (network.band == "5 GHz") SfHue.BLUE else SfHue.GREEN
                PageHeading(network.name, "Wi-Fi network · ${network.band}", status?.text, status?.tone ?: PillTone.Off)
                SfCard(verticalSpacing = 9.dp) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("Network at a glance", Modifier.weight(1f))
                        HueTile(SfIcons.Wifi, hue, size = 35.dp)
                    }
                    val metrics = listOf(ViewRow("Band", network.band), ViewRow("Security", network.security),
                        ViewRow("Connected", clients?.size?.let { "$it devices" } ?: "Unavailable")) + settings?.rows.orEmpty()
                    metrics.filter { it.value.isNotBlank() }.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { MetricTile(it.label, it.value, Modifier.weight(1f)) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Connected devices")
                    clients?.let { Pill("${it.size} devices", PillTone.Off) }
                }
                SfList {
                    if (clients.isNullOrEmpty()) {
                        SfListRow(if (clients == null) "Connected devices unavailable" else "No devices connected.")
                    } else clients.sorted().forEachIndexed { i, mac ->
                        val device = devices?.devices?.firstOrNull { it.mac == mac }
                        if (i > 0) SfDivider()
                        SfListRow(title = device?.name ?: mac, subtitle = device?.ip.orEmpty(),
                            icon = SfIcons.Devices, hue = hue, onClick = device?.let { { onDevice(mac) } },
                            trailing = { Pill("Online") })
                    }
                }
                SectionTitle("Network settings")
                SfList {
                    val section = network.key.section
                    val enabled = network.enabled
                    if (settings?.form != null && changes != null) {
                        FoldForm(settings.form.copy(title = "Network name"), changes::submit)
                        SfDivider()
                    } else SfListRow("Network name", subtitle = network.name, icon = SfIcons.Pencil, hue = hue)
                    settings?.moreForms.orEmpty().forEach { form ->
                        if (changes != null) {
                            SfDivider()
                            FoldForm(form, changes::submit)
                        }
                    }
                    if (enabled != null) {
                        SfDivider()
                        SfListRow("Network enabled", icon = SfIcons.Wifi, hue = hue, trailing = {
                            Switch(checked = enabled, enabled = changes != null && section != null,
                                onCheckedChange = { on ->
                                    if (section != null) changes?.submit(FeatureChanges.wifiEnabled(section, network.name, on))
                                })
                        })
                    }
                }
            }
        }
    }
}

private fun RouterService.status(): ViewPill = when (running) {
    true -> ViewPill("Running", PillTone.Ok)
    false -> ViewPill("Stopped", if (enabled) PillTone.Warn else PillTone.Off)
    null -> ViewPill("Status unavailable", PillTone.Off)
}

@Composable
internal fun ServicesScreen(holder: LoadHolder<FeatureView>, changes: ChangeController, onSignInAgain: () -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    LoadStateContent(holder, onSignInAgain) { view, demo ->
        val c = SfTheme.colors
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PageHeading("Services", "View and control router services.")
            if (view.services.isEmpty()) Note("The router reported no services.")
            else SfList {
                view.services.forEachIndexed { i, service ->
                    if (i > 0) SfDivider()
                    SfListRow(title = service.name, subtitle = if (service.enabled) "Starts at boot" else "Does not start at boot",
                        icon = SfIcons.Port, hue = SfHue.GREEN, onClick = { selected = service.name },
                        trailing = { service.status().let { Pill(it.text, it.tone) } })
                }
            }
            if (!demo) view.forms.forEach { form -> FoldForm(form, changes::submit) }
        }
        view.services.firstOrNull { it.name == selected }?.let { service ->
            ServiceDialog(service, onClose = { selected = null }, onAction = { action ->
                changes.submit(Phase4.serviceAction(service.name, action))
                selected = null
            })
        }
    }
}

@Composable
private fun ServiceDialog(service: RouterService, onClose: () -> Unit, onAction: (String) -> Unit) {
    val mayStop = Phase4.isAllowedServiceAction(service.name, "stop")
    val mayStart = Phase4.isAllowedServiceAction(service.name, "start")
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(service.name, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(SfIcons.Close, contentDescription = "Close") }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                service.status().let { Pill(it.text, it.tone) }
                if (service.running == true && !mayStop) Text("This service is required by the app or the network.")
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (service.running == true) {
                    GhostButton("Stop", onClick = { onAction("stop") }, modifier = Modifier.weight(1f), enabled = mayStop)
                }
                if (service.running == false) PrimaryButton("Start", onClick = { onAction("start") }, modifier = Modifier.weight(1f), enabled = mayStart)
                else PrimaryButton("Restart", onClick = { onAction("restart") }, modifier = Modifier.weight(1f), enabled = mayStart)
            }
        }
    )
}
