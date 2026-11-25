package com.shaazifi.app.feature.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.shaazifi.app.feature.router.ChangeController
import com.shaazifi.app.feature.router.ChangeOutcome
import com.shaazifi.app.feature.router.ChangeUiState
import com.shaazifi.app.feature.router.LoadHolder
import com.shaazifi.app.feature.router.LoadStateContent
import com.shaazifi.app.feature.router.submit
import com.shaazifi.app.ui.components.Note
import com.shaazifi.app.ui.components.PageHeading
import com.shaazifi.app.ui.components.PrimaryButton
import com.shaazifi.app.ui.components.SfDivider
import com.shaazifi.app.ui.components.SfListRow
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme
import kotlinx.coroutines.flow.first

@Composable
internal fun FamilyAssignmentScreen(
    holder: LoadHolder<DeviceList>,
    drafts: List<FamilyDraft>,
    profileId: String?,
    changes: ChangeController,
    onSignInAgain: () -> Unit,
    onAssigned: () -> Unit
) {
    var search by remember(profileId) { mutableStateOf("") }
    var chosenProfile by remember(profileId) { mutableStateOf(profileId) }
    var picked by remember(profileId) { mutableStateOf(emptySet<String>()) }
    var submitting by remember(profileId) { mutableStateOf(false) }
    var problem by remember(profileId) { mutableStateOf<String?>(null) }
    var profilesOpen by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val c = SfTheme.colors

    LaunchedEffect(submitting) {
        if (submitting) {
            val result = changes.state.first { it is ChangeUiState.Done || it == ChangeUiState.Idle }
            submitting = false
            if (result is ChangeUiState.Done && result.outcome == ChangeOutcome.Kept) onAssigned()
        }
    }

    LoadStateContent(holder, onSignInAgain) { list, _ ->
        val rules = list.accessRules
        val profiles = familyProfiles(rules.orEmpty(), drafts)
        val profile = profiles.firstOrNull { it.id == (chosenProfile ?: profiles.singleOrNull()?.id) }
        val assigned = profiles.flatMapTo(mutableSetOf()) { it.macs }
        val unassigned = list.devices.filterNot { it.mac in assigned }
        val chosen = unassigned.filter { it.mac in picked && it.network?.sourceZone != null }
        val query = search.trim()
        val shown = unassigned.filter { d -> listOf(d.name, d.ip, d.mac).any { it.contains(query, true) } }

        Column(Modifier.fillMaxSize().imePadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PageHeading("Assign devices", if (profileId != null || profiles.size == 1) profile?.label.orEmpty() else "")
                if (profileId == null && profiles.size > 1) {
                    Box {
                        OutlinedButton(onClick = { profilesOpen = true }) {
                            Text(profile?.let { "${it.label} ▾" } ?: "Choose a profile ▾")
                        }
                        DropdownMenu(expanded = profilesOpen, onDismissRequest = { profilesOpen = false }) {
                            profiles.forEach { option ->
                                DropdownMenuItem(text = { Text(option.label) }, onClick = {
                                    chosenProfile = option.id
                                    problem = null
                                    profilesOpen = false
                                })
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Find a device") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            LazyColumn(
                Modifier.weight(1f).padding(horizontal = 16.dp).clip(RoundedCornerShape(14.dp)),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                when {
                    rules == null -> item { Note("Assignment is unavailable for this router login.") }
                    profileId != null && profile == null -> item { Note("This family profile is unavailable.") }
                    unassigned.isEmpty() -> item { Note("All devices are assigned.") }
                    shown.isEmpty() -> item { Note("No devices match your search.") }
                    else -> itemsIndexed(shown, key = { _, device -> device.mac }) { index, device ->
                        val eligible = device.network?.sourceZone != null
                        val selected = eligible && device.mac in picked
                        val kind = DeviceKind.guess(device.name, null)
                        if (index != 0) SfDivider()
                        SfListRow(
                            title = device.name,
                            modifier = Modifier.background(if (selected) c.accentSoft else c.surface)
                                .toggleable(value = selected, role = Role.Checkbox, enabled = eligible && !submitting,
                                    onValueChange = { picked = if (it) picked + device.mac else picked - device.mac; problem = null }),
                            subtitle = if (eligible) listOfNotNull(
                                device.ip.takeIf { it.isNotBlank() && it != device.name }, device.network.label
                            ).joinToString(" · ") else "Firewall zone unavailable",
                            icon = kind.icon(),
                            hue = kind.hue(),
                            trailing = { Checkbox(checked = selected, onCheckedChange = null, enabled = eligible) }
                        )
                    }
                }
            }

            Column(Modifier.background(c.bg), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                SfDivider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    problem?.let { Text(it, style = SfText.sub, color = c.bad) }
                    PrimaryButton(
                        text = if (chosen.isEmpty()) "Review assignment" else "Review assignment (${chosen.size})",
                        enabled = rules != null && profile != null && chosen.isNotEmpty() && !submitting,
                        onClick = {
                            if (rules != null && profile != null && changes.state.value == ChangeUiState.Idle) {
                                focus.clearFocus()
                                problem = changes.submit(FamilyChanges.assign(profile, chosen, rules))
                                submitting = problem == null
                            }
                        }
                    )
                }
            }
        }
    }
}
