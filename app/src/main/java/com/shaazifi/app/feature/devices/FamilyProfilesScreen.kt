package com.shaazifi.app.feature.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shaazifi.app.feature.router.ChangeController
import com.shaazifi.app.feature.router.FeatureForm
import com.shaazifi.app.feature.router.FoldForm
import com.shaazifi.app.feature.router.FormField
import com.shaazifi.app.feature.router.PlanResult
import com.shaazifi.app.feature.router.submit
import com.shaazifi.app.ui.components.GhostButton
import com.shaazifi.app.ui.components.HueTile
import com.shaazifi.app.ui.components.Note
import com.shaazifi.app.ui.components.PageHeading
import com.shaazifi.app.ui.components.Pill
import com.shaazifi.app.ui.components.SectionTitle
import com.shaazifi.app.ui.components.SfCard
import com.shaazifi.app.ui.components.SfDivider
import com.shaazifi.app.ui.components.SfList
import com.shaazifi.app.ui.components.SfListRow
import com.shaazifi.app.ui.icons.SfIcons
import com.shaazifi.app.ui.theme.PillTone
import com.shaazifi.app.ui.theme.SfHue
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme

@Composable
internal fun FamilyProfilesScreen(
    list: DeviceList,
    drafts: List<FamilyDraft>,
    profileId: String?,
    changes: ChangeController,
    onProfile: (String) -> Unit,
    onAssign: (String?) -> Unit,
    onDevice: (String) -> Unit,
    onCreate: (FamilyDraft) -> Unit,
    onDiscard: (String) -> Unit
) {
    val c = SfTheme.colors
    val access = list.accessRules
    val rules = access.orEmpty()
    val profiles = familyProfiles(rules, drafts)
    val assigned = profiles.flatMap { it.macs }.toSet()
    val unassigned = list.devices.filterNot { it.mac in assigned }
    val groupPause = FamilyChanges.overrideActive(rules)
    var create by remember { mutableStateOf(false) }
    var error by remember(profileId) { mutableStateOf<String?>(null) }
    val request: (PlanResult) -> Unit = { error = changes.submit(it) }

    if (profileId == null) {
        PageHeading("Parental controls", "Manage family profiles, schedules and internet access.")
        SfCard(verticalSpacing = 9.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                HueTile(SfIcons.Users, if (groupPause) SfHue.ORANGE else SfHue.GREEN, size = 38.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Pause family internet", style = MaterialTheme.typography.titleSmall, color = c.ink)
                    val active = profiles.count { it.members.isNotEmpty() }
                    Text(when {
                        access == null -> "Firewall rules unavailable"
                        groupPause -> "Family pause is on"
                        active == 0 -> "Assign devices to a profile first"
                        active == 1 -> "Applies to 1 profile"
                        else -> "Applies to $active profiles"
                    }, style = SfText.sub, color = if (groupPause) c.warn else c.muted)
                }
                Switch(checked = groupPause, enabled = access != null && (assigned.isNotEmpty() || groupPause),
                    onCheckedChange = { request(FamilyChanges.familyPause(profiles, rules)) })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Family profiles")
            Pill("${profiles.size}", PillTone.Off)
        }
        if (profiles.isEmpty()) Note("Create a profile to share one pause or schedule across its devices.")
        profiles.forEach { profile ->
            val pausedByFamily = profile.rules.any { it.family?.kind == "override" && it.enabled }
            val status = when {
                access == null -> "Status unavailable"
                pausedByFamily -> "Family pause"
                profile.paused -> "Paused"
                profile.members.isEmpty() -> "No devices assigned"
                else -> scheduleSummary(profile) ?: "No active schedules"
            }
            val tone = if (pausedByFamily || profile.paused) PillTone.Warn else PillTone.Off
            SfCard(verticalSpacing = 8.dp) {
                SfListRow(
                    profile.label, subtitle = if (profile.macs.size == 1) "1 device" else "${profile.macs.size} devices",
                    icon = SfIcons.Users, hue = SfHue.VIOLET, onClick = { onProfile(profile.id) },
                    trailing = { androidx.compose.material3.Icon(SfIcons.ChevronRight, null, tint = c.muted) }
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Pill(status, tone, modifier = Modifier.align(Alignment.CenterVertically))
                    if (access != null && profile.members.isNotEmpty()) {
                        TextButton(onClick = { request(FamilyChanges.profilePause(profile)) }) {
                            Text(if (profile.paused) "Resume profile" else "Pause profile")
                        }
                    }
                }
            }
        }
        GhostButton("Create new profile", onClick = { create = true })
        SfList {
            SfListRow("Assign devices", subtitle = "${unassigned.size} unassigned", icon = SfIcons.Devices, hue = SfHue.BLUE,
                onClick = if (access != null && profiles.isNotEmpty() && unassigned.isNotEmpty()) {
                    { onAssign(profiles.singleOrNull()?.id) }
                } else null,
                trailing = { androidx.compose.material3.Icon(SfIcons.ChevronRight, null, tint = c.muted) })
        }
    } else {
        val profile = profiles.firstOrNull { it.id == profileId }
        if (profile == null) {
            Note(if (access == null) "Profile rules are unavailable for this router login." else "This profile has been removed.")
        } else {
            FamilyProfileDetail(profile, list, access != null, unassigned.isNotEmpty(), changes,
                onAssign = { onAssign(profile.id) }, onDevice = onDevice, request = request,
                removeDraft = { onDiscard(profile.id) })
        }
    }
    error?.let { Text(it, style = SfText.sub, color = c.bad) }
    if (create) CreateFamilyDialog(onClose = { create = false }, onCreate = { draft ->
        create = false
        onCreate(draft)
    })
}

private fun scheduleSummary(profile: FamilyProfile): String? {
    val enabled = profile.schedules.values.mapNotNull { group -> group.firstOrNull { it.enabled } }
    return when (enabled.size) {
        0 -> null
        1 -> "${enabled.single().from}–${enabled.single().until}"
        else -> "${enabled.size} schedules enabled"
    }
}

@Composable
private fun CreateFamilyDialog(onClose: () -> Unit, onCreate: (FamilyDraft) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose, title = { Text("Create family profile") },
        text = {
            OutlinedTextField(name, { name = it.take(40) }, label = { Text("Profile name") }, singleLine = true)
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { newFamilyDraft(name)?.let(onCreate) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
    )
}

@Composable
private fun FamilyProfileDetail(
    profile: FamilyProfile,
    list: DeviceList,
    canChange: Boolean,
    hasUnassigned: Boolean,
    changes: ChangeController,
    onAssign: () -> Unit,
    onDevice: (String) -> Unit,
    request: (PlanResult) -> Unit,
    removeDraft: () -> Unit
) {
    val pausedByFamily = profile.rules.any { it.family?.kind == "override" && it.enabled }
    PageHeading(profile.label, "Family profile · ${profile.macs.size} devices",
        when { pausedByFamily -> "Family pause"; profile.paused -> "Paused"; else -> null }, PillTone.Warn)
    var scheduleOpen by remember(profile.id) { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (canChange && profile.members.isNotEmpty()) {
            OutlinedButton(onClick = { request(FamilyChanges.profilePause(profile)) }) {
                Text(if (profile.paused) "Resume profile" else "Pause profile")
            }
            OutlinedButton(onClick = { scheduleOpen = true }) { Text("Add schedule") }
        }
    }
    SectionTitle("Devices")
    SfList {
        if (profile.members.isEmpty()) SfListRow("No devices assigned")
        profile.members.forEachIndexed { index, rule ->
            if (index > 0) SfDivider()
            val device = list.devices.firstOrNull { it.mac == rule.mac }
            val label = device?.name ?: rule.mac
            SfListRow(label, subtitle = device?.network?.label, icon = SfIcons.Devices, hue = SfHue.BLUE,
                onClick = device?.let { { onDevice(it.mac) } },
                trailing = { if (canChange) TextButton(onClick = { request(FamilyChanges.removeMember(profile, rule.mac, label)) }) {
                    Text("Remove")
                } })
        }
    }
    GhostButton("Assign devices", onClick = onAssign, enabled = canChange && hasUnassigned)
    SectionTitle("Schedules")
    if (profile.schedules.isEmpty()) SfList { SfListRow("No schedules for this profile") }
    profile.schedules.forEach { (key, rows) ->
        val preview = rows.first()
        val enabled = rows.any { it.enabled }
        SfCard(verticalSpacing = 4.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${preview.from}–${preview.until}", style = MaterialTheme.typography.titleSmall)
                    Text(preview.days.ifBlank { "Every day" }, style = SfText.sub, color = SfTheme.colors.muted)
                }
                Switch(checked = enabled, enabled = canChange, onCheckedChange = { request(FamilyChanges.schedule(profile, key, it)) })
            }
            if (canChange) TextButton(onClick = { request(FamilyChanges.schedule(profile, key, null)) }) { Text("Remove schedule") }
        }
    }
    TextButton(enabled = canChange || profile.rules.isEmpty(), onClick = {
        if (profile.rules.isEmpty()) removeDraft() else request(FamilyChanges.deleteProfile(profile))
    }) { Text("Remove profile") }

    if (scheduleOpen) {
        val form = remember(profile) {
            FeatureForm(
                "Add schedule",
                listOf(FormField("days", "Days (blank for every day)", hint = "Mon Tue Wed"),
                    FormField("from", "Pause at", initial = "21:00"), FormField("until", "Resume at", initial = "07:00")),
                "Review schedule"
            ) { values ->
                FamilyChanges.addSchedule(profile, values["days"].orEmpty(), values["from"].orEmpty(), values["until"].orEmpty())
            }
        }
        AlertDialog(onDismissRequest = { scheduleOpen = false }, title = { Text("Add schedule") },
            text = { Column(Modifier.heightIn(max = 425.dp).verticalScroll(rememberScrollState())) {
                FoldForm(form, submit = { result ->
                    changes.submit(result).also { if (it == null) scheduleOpen = false }
                }, showHeader = false)
            } }, confirmButton = {},
            dismissButton = { TextButton(onClick = { scheduleOpen = false }) { Text("Cancel") } })
    }
}
