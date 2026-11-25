package com.shaazifi.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import com.shaazifi.app.feature.routers.RouterDisplay
import com.shaazifi.app.ui.components.Eyebrow
import com.shaazifi.app.ui.components.GhostButton
import com.shaazifi.app.ui.components.Note
import com.shaazifi.app.ui.components.Pill
import com.shaazifi.app.ui.components.PrimaryButton
import com.shaazifi.app.ui.components.SfCard
import com.shaazifi.app.ui.components.SfDivider
import com.shaazifi.app.ui.components.SfList
import com.shaazifi.app.ui.components.SfListRow
import com.shaazifi.app.ui.theme.PillTone
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shaazifi.app.data.RouterProfile
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    profile: RouterProfile,
    settingsHandler: RouterSettingsHandler,
    onForgetRouter: () -> Unit,
    onProfileUpdated: (RouterProfile) -> Unit = {},
    modifier: Modifier = Modifier,
    /** Checks a VPN address's certificate (M9). Null hides the VPN address row. */
    probeCertificate: (suspend (String) -> Result<String>)? = null,
    /** App lock and saved passwords (M9). */
    securitySection: @Composable () -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    var showForgetConfirmation by remember { mutableStateOf(false) }

    // AdGuard Home configuration state
    // Stored flag kept as is; nothing in the app reads it yet, so it is not shown as a switch.
    val adguardEnabled = profile.adguardEnabled
    var hostInput by remember(profile) { mutableStateOf(profile.adguardHost ?: "") }
    var portInput by remember(profile) { mutableStateOf(profile.adguardPort?.toString() ?: "") }
    var protocolInput by remember(profile) { mutableStateOf(profile.adguardProtocol ?: "http") }
    var usernameInput by remember(profile) { mutableStateOf(profile.adguardUsername) }
    var saveSuccessMessage by remember { mutableStateOf<String?>(null) }
    var saveErrorMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val c = SfTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(c.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        com.shaazifi.app.ui.components.PageHero(com.shaazifi.app.ui.icons.SfIcons.Settings, com.shaazifi.app.ui.theme.SfHue.SLATE, "Settings", profile.name, "This router's address and certificate, AdGuard Home, app security and alerts.")
        com.shaazifi.app.ui.components.SectionBar("This router", com.shaazifi.app.ui.theme.SfHue.GREEN)
        SfList {
            SfListRow(title = "Name", icon = com.shaazifi.app.ui.icons.SfIcons.Router, hue = com.shaazifi.app.ui.theme.SfHue.GREEN, trailing = { Text(profile.name, style = SfText.sub, color = c.muted) })
            SfDivider()
            SfListRow(title = "Home address", icon = com.shaazifi.app.ui.icons.SfIcons.Globe, hue = com.shaazifi.app.ui.theme.SfHue.BLUE, trailing = { Text(RouterDisplay.hostLabel(profile.address), style = SfText.mono, color = c.muted) })
            SfDivider()
            Column(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Certificate", fontWeight = FontWeight.SemiBold, color = c.ink, modifier = Modifier.weight(1f))
                    if (RouterDisplay.isPlainHttp(profile)) Pill("HTTP", tone = PillTone.Warn) else Pill("Pinned", tone = PillTone.Ok)
                }
                if (!RouterDisplay.isPlainHttp(profile)) {
                    RouterDisplay.fingerprintLines(profile.certificateFingerprint).forEach {
                        Text(it, style = SfText.mono, color = c.muted)
                    }
                }
            }
            if (probeCertificate != null) {
                SfDivider()
                VpnAddressRows(profile, settingsHandler, probeCertificate, onProfileUpdated)
            }
        }

        com.shaazifi.app.ui.components.SectionBar("AdGuard Home", com.shaazifi.app.ui.theme.SfHue.VIOLET)
        SfCard(verticalSpacing = 10.dp) {
            Text(
                "Where AdGuard Home runs. Leave the address empty to use the router's address. If the port is empty, the app tries 3000.",
                style = SfText.sub,
                color = c.muted
            )
            OutlinedTextField(
                value = hostInput,
                onValueChange = {
                    hostInput = it
                    saveSuccessMessage = null
                },
                label = { Text("Address (optional)") },
                placeholder = { Text("Router address") },
                shape = MaterialTheme.shapes.medium,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = portInput,
                onValueChange = {
                    portInput = it
                    saveSuccessMessage = null
                },
                label = { Text("Port (optional)") },
                placeholder = { Text("3000") },
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Protocol", style = SfText.sub, color = c.muted)
                listOf("http", "https").forEach { option ->
                    Pill(
                        option,
                        tone = if (protocolInput == option) PillTone.Ok else PillTone.Off,
                        onClick = {
                            protocolInput = option
                            saveSuccessMessage = null
                        }
                    )
                }
            }
            OutlinedTextField(
                value = usernameInput,
                onValueChange = {
                    usernameInput = it
                    saveSuccessMessage = null
                },
                label = { Text("AdGuard user name") },
                placeholder = { Text("admin") },
                shape = MaterialTheme.shapes.medium,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            PrimaryButton(
                text = if (isSaving) "Saving..." else "Save AdGuard settings",
                enabled = !isSaving,
                onClick = {
                    coroutineScope.launch {
                        isSaving = true
                        saveSuccessMessage = null
                        saveErrorMessage = null
                        val portInt = portInput.trim().toIntOrNull()
                        val updated = try {
                            settingsHandler.updateAdGuardConfig(
                                profileId = profile.id,
                                enabled = adguardEnabled,
                                host = hostInput,
                                port = portInt,
                                protocol = protocolInput,
                                username = usernameInput
                            )
                        } catch (e: Exception) {
                            saveErrorMessage = "Could not save: ${e::class.simpleName}."
                            null
                        }
                        isSaving = false
                        if (updated != null) {
                            onProfileUpdated(updated)
                            saveSuccessMessage = "AdGuard Home settings saved."
                        } else if (saveErrorMessage == null) {
                            saveErrorMessage = "Could not save: this router is not in the saved list."
                        }
                    }
                }
            )
            if (saveErrorMessage != null) {
                Text(text = saveErrorMessage ?: "", color = c.bad, style = SfText.sub, fontWeight = FontWeight.SemiBold)
            }
            if (saveSuccessMessage != null) {
                Text(text = "✓ $saveSuccessMessage", color = c.accent, style = SfText.sub, fontWeight = FontWeight.SemiBold)
            }
        }

        com.shaazifi.app.ui.components.SectionBar("Security", com.shaazifi.app.ui.theme.SfHue.BLUE)
        Note(
            "The app only talks to private addresses (your home network or VPN). It checks each router's certificate every time and never skips that check."
        )
        securitySection()

        com.shaazifi.app.ui.components.SectionBar("Forget", com.shaazifi.app.ui.theme.SfHue.RED)
        SfCard(verticalSpacing = 10.dp) {
            Text(
                "Removes this router, its approved certificate and the router pages' stored data from this phone. Nothing on the router changes.",
                style = SfText.sub,
                color = c.muted
            )
            GhostButton(text = "Forget this router", onClick = { showForgetConfirmation = true })
        }
        Box(Modifier.padding(bottom = 16.dp))
    }

    if (showForgetConfirmation) {
        AlertDialog(
            onDismissRequest = { showForgetConfirmation = false },
            title = { Text("Forget ${profile.name}?") },
            text = {
                Text(
                    "This removes the router, its approved certificate and the stored data of its web pages from this phone. " +
                        "To use it again you add it and approve its certificate again. Nothing on the router changes.",
                    style = SfText.sub
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showForgetConfirmation = false
                        coroutineScope.launch {
                            settingsHandler.forgetRouter(profile.id, profile.address)
                            onForgetRouter()
                        }
                    }
                ) { Text("Forget", color = c.bad) }
            },
            dismissButton = {
                TextButton(onClick = { showForgetConfirmation = false }) { Text("Keep") }
            }
        )
    }
}
