package com.shaazifi.app.feature.devices

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shaazifi.app.feature.router.ChangeController
import com.shaazifi.app.feature.router.FeatureChanges
import com.shaazifi.app.feature.router.FeatureForm
import com.shaazifi.app.feature.router.FoldForm
import com.shaazifi.app.feature.router.FormField
import com.shaazifi.app.feature.router.LoadHolder
import com.shaazifi.app.feature.router.LoadStateContent
import com.shaazifi.app.feature.router.submit
import com.shaazifi.app.ui.components.GhostButton
import com.shaazifi.app.ui.components.PageHeading
import com.shaazifi.app.ui.components.SectionTitle
import com.shaazifi.app.ui.components.Note
import com.shaazifi.app.ui.components.Pill
import com.shaazifi.app.ui.components.SfCard
import com.shaazifi.app.ui.components.SfDivider
import com.shaazifi.app.ui.components.SfList
import com.shaazifi.app.ui.components.SfListRow
import com.shaazifi.app.ui.icons.SfIcons
import com.shaazifi.app.ui.theme.SfHue
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme

@Composable
internal fun ParentalScreen(
    holder: LoadHolder<DeviceList>,
    selectedMac: String?,
    profileId: String?,
    drafts: List<FamilyDraft>,
    listScroll: ScrollState,
    changes: ChangeController,
    onSignInAgain: () -> Unit,
    onProfile: (String) -> Unit,
    onAssign: (String?) -> Unit,
    onSelect: (String) -> Unit,
    onCreate: (FamilyDraft) -> Unit,
    onDiscard: (String) -> Unit
) {
    LoadStateContent(holder, onSignInAgain) { list, _ ->
        Column(
            Modifier.fillMaxSize().verticalScroll(if (selectedMac == null && profileId == null) listScroll else rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (selectedMac == null) {
                FamilyProfilesScreen(list, drafts, profileId, changes, onProfile, onAssign, onSelect, onCreate, onDiscard)
            } else {
                val device = list.devices.firstOrNull { it.mac == selectedMac }
                if (device == null) Note("This device is unavailable.")
                else DeviceRules(device, list.accessRules, changes)
            }
        }
    }
}

@Composable
private fun DeviceRules(device: Device, rules: List<AccessRule>?, changes: ChangeController) {
    val c = SfTheme.colors
    val (status, tone) = devicePill(device)
    PageHeading(device.name, "Parental controls · ${device.connection.label}", status, tone)
    var scheduleOpen by remember(device.mac) { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        InternetAction(device, rules, changes)
        if (rules != null) OutlinedButton(onClick = { scheduleOpen = true }) { Text("Add a schedule") }
    }
    SectionTitle("Schedules")
    if (rules == null) {
        Note("Schedules are unavailable for this router login.")
        return
    }
    val own = rules.filter { it.mac == device.mac && it.scheduled && it.family == null }
    if (own.isEmpty()) SfList { SfListRow("No schedules for this device.") }
    var error by remember(device.mac, own) { mutableStateOf<String?>(null) }
    own.forEach { rule ->
        SfCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 8.dp) {
            Text(rule.days.ifBlank { "Every day" }, style = MaterialTheme.typography.titleSmall, color = c.ink)
            Text("${rule.from.ifBlank { "Any time" }} – ${rule.until.ifBlank { "Any time" }}",
                color = c.muted, style = SfText.sub)
            GhostButton(if (rule.enabled) "Disable schedule" else "Enable schedule",
                onClick = { error = changes.submit(FeatureChanges.parentalEnabled(rule.id, device.name, !rule.enabled)) })
            GhostButton("Remove schedule", onClick = { error = changes.submit(FeatureChanges.removeParental(rule.id, device.name)) })
        }
    }
    error?.let { Text(it, style = SfText.sub, color = c.bad) }
    val form = remember(device.mac, device.name) {
        FeatureForm(
            "Add a schedule",
            listOf(FormField("days", "Days (blank for every day)", hint = "Mon Tue Wed"),
                FormField("from", "Pause at", initial = "21:00"), FormField("to", "Resume at", initial = "07:00")),
            "Save schedule"
        ) { values ->
            FeatureChanges.parentalSchedule(device.mac, values["days"].orEmpty(), values["from"].orEmpty(),
                values["to"].orEmpty(), mapOf(device.mac to device.name))
        }
    }
    if (scheduleOpen) {
        AlertDialog(onDismissRequest = { scheduleOpen = false }, title = { Text("Add a schedule") }, text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(device.name, color = c.muted)
                FoldForm(form, submit = { plan ->
                    changes.submit(plan).also { if (it == null) scheduleOpen = false }
                }, showHeader = false)
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = { scheduleOpen = false }) { Text("Cancel") } })
    }
}
