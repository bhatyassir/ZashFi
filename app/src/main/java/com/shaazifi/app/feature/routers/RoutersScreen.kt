package com.shaazifi.app.feature.routers

import androidx.compose.ui.res.stringResource
import com.shaazifi.app.R

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.clip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.shaazifi.app.ui.components.Eyebrow
import com.shaazifi.app.ui.components.GhostButton
import com.shaazifi.app.ui.components.Note
import com.shaazifi.app.ui.components.Pill
import com.shaazifi.app.ui.components.PrimaryButton
import com.shaazifi.app.ui.components.SfCard
import com.shaazifi.app.ui.components.SfDivider
import com.shaazifi.app.ui.components.SfList
import com.shaazifi.app.ui.components.SfListRow
import com.shaazifi.app.ui.icons.SfIcons
import com.shaazifi.app.ui.theme.PillTone
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.shaazifi.app.AppContainer
import com.shaazifi.app.data.RouterProfile
import com.shaazifi.app.data.normalizeRouterAddress
import com.shaazifi.app.debug.DebugRouterConfig
import com.shaazifi.app.feature.alerts.AlertScheduler
import kotlinx.coroutines.launch

@Composable
fun RoutersScreen(
    container: AppContainer,
    onSelectProfile: (RouterProfile) -> Unit,
    modifier: Modifier = Modifier,
    /** Test builds only: opens the connection self-test. Null hides the link. */
    onOpenSelfTest: (() -> Unit)? = null,
    /** Opens the offline demo (blueprint section 7: reachable from the first screen). */
    onTryDemo: (() -> Unit)? = null
) {
    val c = SfTheme.colors
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val profiles by container.profileRepository.getProfiles().collectAsState(initial = emptyList())

    val addRouterHolder = remember(container.sessionManager) { AddRouterStateHolder(container.sessionManager) }
    val addRouterUiState by addRouterHolder.state.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var profilePendingForget by remember { mutableStateOf<RouterProfile?>(null) }

    var overflowOpen by remember { mutableStateOf(false) }
    // Owner feedback: one-hand use. The list scrolls on top; the buttons sit at the bottom.
    Column(modifier = modifier.fillMaxSize().background(c.bg)) {
    LazyColumn(
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    com.shaazifi.app.ui.components.PageHero(
                        SfIcons.RouterLive, com.shaazifi.app.ui.theme.SfHue.GREEN,
                        "Your routers", stringResource(R.string.app_name), "Saved on this phone. Tap one to sign in."
                    )
                }
                // Test tools live in a menu, not on the main screen (owner feedback).
                if (onOpenSelfTest != null) {
                    Box {
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(SfIcons.MoreVertical, contentDescription = "More options", tint = c.muted)
                        }
                        DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Connection self-test (test builds)") },
                                onClick = { overflowOpen = false; onOpenSelfTest() }
                            )
                        }
                    }
                }
            }
        }

        if (profiles.isEmpty()) {
            item {
                SfCard(verticalSpacing = 10.dp) {
                    Text("No routers yet", style = MaterialTheme.typography.titleMedium, color = c.ink)
                    Text(
                        "Find the router on the Wi-Fi this phone is connected to, or add one by its address.",
                        style = SfText.sub,
                        color = c.muted
                    )
                    if (DebugRouterConfig.IS_DEBUG && DebugRouterConfig.ROUTER_HTTPS_URL.isNotBlank()) {
                        GhostButton(
                            text = "Add the test router (test builds only)",
                            onClick = {
                                coroutineScope.launch {
                                    val debugProfile = RouterProfile(
                                        name = "Tailscale OpenWrt",
                                        address = DebugRouterConfig.ROUTER_HTTPS_URL,
                                        certificateFingerprint = DebugRouterConfig.ROUTER_CERT_FINGERPRINT
                                    )
                                    container.profileRepository.saveProfile(debugProfile)
                                    container.trustStore.pin(debugProfile.address, debugProfile.certificateFingerprint)
                                }
                            }
                        )
                    }
                }
            }
        } else {
            item {
                SfList {
                    profiles.forEachIndexed { index, profile ->
                        if (index > 0) SfDivider()
                        RouterProfileRow(
                            profile = profile,
                            onClick = { onSelectProfile(profile) },
                            onForget = { profilePendingForget = profile }
                        )
                    }
                }
            }
        }

        item {
            Note("Each router is remembered by its address and its security certificate. The app only talks to private addresses: your home network or your VPN.")
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (onTryDemo != null) {
            TextButton(onClick = onTryDemo, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Try the demo (sample data, no router needed)")
            }
        }
        GhostButton(
            text = "Add a router by address",
            onClick = {
                val addr = if (DebugRouterConfig.IS_DEBUG) DebugRouterConfig.ROUTER_HTTPS_URL else ""
                val name = if (DebugRouterConfig.IS_DEBUG) "Test Router (Tailscale)" else "My Router"
                addRouterHolder.openDialog(initialAddress = addr, initialName = name)
                showAddDialog = true
            }
        )
        PrimaryButton(
            text = "Find the router on this Wi-Fi",
            onClick = {
                val gwCandidates = container.gatewayDiscovery.candidateEndpoints
                val addr = gwCandidates.firstOrNull() ?: "https://192.168.1.1"
                addRouterHolder.openDialog(initialAddress = addr, initialName = "Home Gateway")
                showAddDialog = true
                coroutineScope.launch { addRouterHolder.probeCandidates(gwCandidates) }
            }
        )
    }
    }

    // Forget-router confirmation (local data only; nothing is sent to the router)
    profilePendingForget?.let { target ->
        AlertDialog(
            onDismissRequest = { profilePendingForget = null },
            title = { Text("Forget ${target.name}?") },
            text = {
                Text(
                    "This removes the router and its saved certificate from this phone. Nothing on the router changes. You can add it again later.",
                    style = SfText.sub
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    profilePendingForget = null
                    coroutineScope.launch {
                        if (runCatching { container.alertSettings.profileId() }.getOrNull() == target.id) {
                            runCatching { container.alertSettings.disable() }
                            runCatching { AlertScheduler.stop(context) }
                        }
                        val kept = runCatching { container.savedSessions.last() }.getOrNull()
                        if (kept?.profileId == target.id) {
                            runCatching { container.savedSessions.clear(kept) }
                        }
                        container.routerSettingsHandler.forgetRouter(target.id, target.address)
                    }
                }) { Text("Forget", color = c.bad) }
            },
            dismissButton = {
                TextButton(onClick = { profilePendingForget = null }) { Text("Keep") }
            }
        )
    }

    // Add Router Dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!addRouterUiState.isProbing) {
                    addRouterHolder.dismissDialog()
                    showAddDialog = false
                }
            },
            title = { Text("Add a router") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "The app first checks the router's security certificate. Your password is not sent at this step.",
                        style = SfText.sub,
                        color = c.muted
                    )
                    OutlinedTextField(
                        value = addRouterUiState.name,
                        onValueChange = { addRouterHolder.onNameChanged(it) },
                        label = { Text("Name") },
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = addRouterUiState.address,
                        onValueChange = { addRouterHolder.onAddressChanged(it) },
                        label = { Text("Address, for example https://192.168.1.1") },
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    if (addRouterUiState.errorMessage != null) {
                        Text(text = addRouterUiState.errorMessage ?: "", color = c.bad, style = SfText.sub)
                    }

                    if (addRouterUiState.diagnostic != null) {
                        Text(
                            text = "${addRouterUiState.diagnostic?.exceptionClass ?: "Unknown"} · socket connected: " +
                                if (addRouterUiState.diagnostic?.socketConnected == true) "yes" else "no",
                            color = c.muted,
                            style = SfText.mono
                        )
                    }

                    if (addRouterUiState.isProbing) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = c.accent)
                            Text("Checking the router (no password sent)...", style = SfText.sub, color = c.muted)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { coroutineScope.launch { addRouterHolder.probe() } },
                    enabled = !addRouterUiState.isProbing && addRouterUiState.address.isNotBlank()
                ) { Text("Check router") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        addRouterHolder.dismissDialog()
                        showAddDialog = false
                    },
                    enabled = !addRouterUiState.isProbing
                ) { Text("Cancel") }
            }
        )
    }

    // Certificate check (trust on first use) or plain-HTTP notice
    if (addRouterUiState.showApprovalDialog && addRouterUiState.discoveredFingerprint != null) {
        val isHttp = addRouterUiState.isHttp || addRouterUiState.discoveredFingerprint == RouterDisplay.HTTP_NO_PIN
        AlertDialog(
            onDismissRequest = { addRouterHolder.dismissApproval() },
            title = { Text(if (isHttp) "This router uses plain HTTP" else "Check the router's certificate") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isHttp) Pill("HTTP", tone = PillTone.Warn) else Pill("HTTPS", tone = PillTone.Ok)
                        Text(RouterDisplay.hostLabel(addRouterUiState.address), style = SfText.mono, color = c.ink)
                    }
                    if (isHttp) {
                        Text(
                            "The password and router data travel unencrypted on your local network. There is no certificate to check. Only continue if this is your own router.",
                            style = SfText.sub,
                            color = c.muted
                        )
                    } else {
                        Text(
                            "This is the first time the app sees this router. Compare this fingerprint with the one in the router's own settings before you trust it. Your password has not been sent.",
                            style = SfText.sub,
                            color = c.muted
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .background(c.sunk)
                                .padding(10.dp)
                        ) {
                            Eyebrow("SHA-256 fingerprint")
                            RouterDisplay.fingerprintLines(addRouterUiState.discoveredFingerprint ?: "").forEach { line ->
                                Text(line, style = SfText.mono, color = c.ink)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val finalFp = addRouterUiState.discoveredFingerprint!!
                        val normalizedAddress = normalizeRouterAddress(addRouterUiState.address) ?: addRouterUiState.address.trim()
                        val profile = RouterProfile(
                            name = addRouterUiState.name.trim().ifBlank { "My Router" },
                            address = normalizedAddress,
                            certificateFingerprint = finalFp
                        )
                        coroutineScope.launch {
                            container.profileRepository.saveProfile(profile)
                            if (finalFp != RouterDisplay.HTTP_NO_PIN) {
                                container.trustStore.pin(profile.address, profile.certificateFingerprint)
                            }
                            addRouterHolder.dismissApproval()
                            showAddDialog = false
                            onSelectProfile(profile)
                        }
                    }
                ) { Text(if (isHttp) "Use HTTP and save" else "Trust and save") }
            },
            dismissButton = {
                TextButton(onClick = { addRouterHolder.dismissApproval() }) { Text("Don't trust") }
            }
        )
    }
}

/** One saved router, as in the mock's "Your routers" list. */
@Composable
fun RouterProfileRow(
    profile: RouterProfile,
    onClick: () -> Unit,
    onForget: () -> Unit
) {
    val c = SfTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    val http = RouterDisplay.isPlainHttp(profile)
    SfListRow(
        title = profile.name,
        subtitle = RouterDisplay.hostLabel(profile.address) + if (http) " · HTTP" else " · HTTPS",
        subtitleMono = true,
        icon = SfIcons.RouterSaved,
        onClick = onClick,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (http) Pill("HTTP", tone = PillTone.Warn) else Pill("Saved", tone = PillTone.Off)
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(SfIcons.MoreVertical, contentDescription = "Options for ${profile.name}", tint = c.muted, modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Forget this router", color = c.bad) },
                            onClick = {
                                menuOpen = false
                                onForget()
                            }
                        )
                    }
                }
            }
        }
    )
}
