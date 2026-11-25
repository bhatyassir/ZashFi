package com.shaazifi.app.feature.adguard

import com.shaazifi.app.feature.adguard.manage.AgPage
import com.shaazifi.app.data.AdGuardRepository
import com.shaazifi.app.ui.components.HueTile
import com.shaazifi.app.ui.components.PageHeading
import com.shaazifi.app.ui.components.PageLink
import kotlinx.coroutines.CancellationException
import com.shaazifi.app.ui.theme.SfHue
import com.shaazifi.app.ui.components.SfSwitch
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.ui.text.style.TextOverflow
import com.shaazifi.app.ui.components.BigNumber
import com.shaazifi.app.ui.components.Eyebrow
import com.shaazifi.app.ui.components.GhostButton
import com.shaazifi.app.ui.components.Note
import com.shaazifi.app.ui.components.Pill
import com.shaazifi.app.ui.components.PrimaryButton
import com.shaazifi.app.ui.components.SfCard
import com.shaazifi.app.ui.components.SfDivider
import com.shaazifi.app.ui.components.SfList
import com.shaazifi.app.ui.components.SfListRow
import com.shaazifi.app.ui.components.SwitchCard
import com.shaazifi.app.ui.components.SwitchRow
import com.shaazifi.app.ui.icons.SfIcons
import com.shaazifi.app.ui.theme.PillTone
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.Locale
import androidx.compose.material3.Checkbox
import com.shaazifi.app.core.session.SavedPassword
import com.shaazifi.app.core.session.SavedPasswordResult

@Composable
fun AdGuardScreen(
    stateHolder: AdGuardStateHolder,
    onOpenQueryLog: (AdGuardQueryEntry) -> Unit,
    modifier: Modifier = Modifier,
    showAddressForm: Boolean = false,
    initialHost: String = "",
    initialPort: String = "",
    initialProtocol: String = "http",
    /** Saves the typed address. Returns null on success or a message to show on failure. */
    onSaveAddress: suspend (host: String, port: String, protocol: String) -> String? = { _, _, _ -> null },
    /** Opens a management page ([com.shaazifi.app.feature.adguard.manage.AgPage] id). */
    onOpenPage: (String) -> Unit = {},
    /** M9: the AdGuard password saved behind fingerprint / PIN. Null in demo mode. */
    savedPassword: SavedPassword? = null
) {
    val coroutineScope = rememberCoroutineScope()
    var hostField by remember(initialHost) { mutableStateOf(initialHost) }
    var portField by remember(initialPort) { mutableStateOf(initialPort) }
    var protocolField by remember(initialProtocol) { mutableStateOf(initialProtocol) }
    var addressError by remember { mutableStateOf<String?>(null) }
    var savingAddress by remember { mutableStateOf(false) }
    val uiState by stateHolder.state.collectAsState()
    val pendingConfirmation by stateHolder.pendingConfirmation.collectAsState()

    var passwordInput by remember { mutableStateOf("") }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var rememberPassword by remember { mutableStateOf(false) }
    var hasSaved by remember { mutableStateOf(false) }
    var savedMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(savedPassword) { hasSaved = savedPassword?.let { runCatching { it.has() }.getOrDefault(false) } ?: false }
    val canSave = savedPassword?.usable == true

    LaunchedEffect(stateHolder) {
        stateHolder.pollWhileActive()
    }

    // Confirmation Dialog for state changes (Protection, Safe Browsing)
    if (pendingConfirmation != null) {
        val conf = pendingConfirmation!!
        AlertDialog(
            onDismissRequest = { stateHolder.dismissPendingAction() },
            title = {
                Text(
                    text = when (conf) {
                        is PendingConfirmation.ProtectionToggle -> conf.title
                        is PendingConfirmation.SafeBrowsingToggle -> conf.title
                    },
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = when (conf) {
                        is PendingConfirmation.ProtectionToggle -> conf.message
                        is PendingConfirmation.SafeBrowsingToggle -> conf.message
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            stateHolder.confirmPendingAction()
                        }
                    }
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { stateHolder.dismissPendingAction() }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Password input dialog when authentication fails (HTTP 401)
    // With a saved password, the card offers "Use saved password" first instead of opening this dialog.
    if (showPasswordDialog || (uiState is AdGuardUiState.WrongPassword && !(hasSaved && canSave))) {
        AlertDialog(
            onDismissRequest = { showPasswordDialog = false },
            title = { Text("AdGuard Home Sign In", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Enter administrator password for AdGuard Home:")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (canSave) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = rememberPassword, onCheckedChange = { rememberPassword = it })
                            Text("Remember on this phone (opens with fingerprint or PIN)", modifier = Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val typed = passwordInput
                        stateHolder.setPassword(typed)
                        passwordInput = ""
                        showPasswordDialog = false
                        coroutineScope.launch {
                            stateHolder.refresh()
                            // Save only a password AdGuard accepted.
                            if (rememberPassword && canSave && stateHolder.state.value !is AdGuardUiState.WrongPassword) {
                                savedMessage = savedPassword?.save(typed) ?: "AdGuard password saved."
                                hasSaved = savedPassword?.let { runCatching { it.has() }.getOrDefault(false) } ?: false
                            }
                        }
                    }
                ) {
                    Text("Sign In")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showPasswordDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    val c = SfTheme.colors
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(c.bg)
    ) {
        when (val state = uiState) {
            is AdGuardUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = c.accent)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Connecting to AdGuard Home...", style = SfText.sub, color = c.muted)
                    }
                }
            }
            is AdGuardUiState.FeatureNotInstalled -> {
                CenteredCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("AdGuard Home not found", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
                        Pill("Not found", tone = PillTone.Off)
                    }
                    Text(
                        if (showAddressForm) {
                            "AdGuard Home did not answer at this address. Enter the address and port you use " +
                                "to open AdGuard Home in a browser, then tap Save and check again."
                        } else {
                            "AdGuard Home is not installed on this router or is not listening on the configured port."
                        },
                        style = SfText.sub,
                        color = c.muted
                    )
                    if (state.diagnostic != null) {
                        Text(
                            text = "Tried ${state.diagnostic.targetHostPort} · ${state.diagnostic.httpCodeOrException} · " +
                                "socket connected: ${if (state.diagnostic.socketConnected) "yes" else "no"}",
                            style = SfText.mono,
                            color = c.muted
                        )
                    }
                    if (showAddressForm) {
                        OutlinedTextField(
                            value = hostField,
                            onValueChange = { hostField = it; addressError = null },
                            label = { Text("Address (for example 192.168.1.1)") },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = portField,
                            onValueChange = { portField = it.filter { ch -> ch.isDigit() }.take(5); addressError = null },
                            label = { Text("Port (for example 90)") },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Protocol", style = SfText.sub, color = c.muted)
                            listOf("http", "https").forEach { option ->
                                Pill(
                                    option,
                                    tone = if (protocolField == option) PillTone.Ok else PillTone.Off,
                                    onClick = { protocolField = option; addressError = null }
                                )
                            }
                        }
                        if (addressError != null) {
                            Text(text = addressError ?: "", color = c.bad, style = SfText.sub)
                        }
                        PrimaryButton(
                            text = if (savingAddress) "Saving..." else "Save and check again",
                            enabled = !savingAddress,
                            onClick = {
                                coroutineScope.launch {
                                    savingAddress = true
                                    addressError = null
                                    val error = onSaveAddress(hostField, portField, protocolField)
                                    savingAddress = false
                                    if (error != null) {
                                        addressError = error
                                    } else {
                                        stateHolder.refresh()
                                    }
                                }
                            }
                        )
                    }
                    GhostButton(text = "Check again", onClick = { coroutineScope.launch { stateHolder.refresh() } })
                }
            }
            is AdGuardUiState.RateLimited -> {
                CenteredCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Too many sign-in attempts", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
                        Pill("Locked", tone = PillTone.Bad)
                    }
                    Text(state.message, style = SfText.sub, color = c.muted)
                    GhostButton(text = "Try again", onClick = { coroutineScope.launch { stateHolder.refresh() } })
                }
            }
            is AdGuardUiState.WrongPassword -> {
                CenteredCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Sign in to AdGuard Home", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
                        Pill("Password", tone = PillTone.Warn)
                    }
                    Text(state.message, style = SfText.sub, color = c.muted)
                    if (savedMessage != null) Text(savedMessage ?: "", style = SfText.sub, color = c.muted)
                    if (hasSaved && canSave) {
                        PrimaryButton(text = "Use saved password", onClick = {
                            coroutineScope.launch {
                                when (val r = savedPassword?.load()) {
                                    is SavedPasswordResult.Value -> {
                                        stateHolder.setPassword(r.secret)
                                        stateHolder.refresh()
                                    }
                                    is SavedPasswordResult.Message -> {
                                        savedMessage = r.text
                                        hasSaved = runCatching { savedPassword?.has() == true }.getOrDefault(false)
                                    }
                                    else -> Unit
                                }
                            }
                        })
                        GhostButton(text = "Type the password", onClick = { showPasswordDialog = true })
                    } else {
                        PrimaryButton(text = "Enter password", onClick = { showPasswordDialog = true })
                    }
                }
            }
            is AdGuardUiState.RouterUnreachable -> {
                CenteredCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Can't reach AdGuard Home", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
                        Pill("Offline", tone = PillTone.Bad)
                    }
                    Text(state.message, style = SfText.sub, color = c.muted)
                    GhostButton(text = "Try again", onClick = { coroutineScope.launch { stateHolder.refresh() } })
                }
            }
            is AdGuardUiState.Loaded -> {
                AdGuardDashboardContent(
                    dashboard = state.dashboard,
                    repository = stateHolder.repository,
                    onProtectionToggle = { enabled -> stateHolder.requestProtectionToggle(enabled) },
                    onPauseTenMinutes = { stateHolder.requestProtectionToggle(enabled = false, durationMs = 600000L) },
                    onSafeBrowsingToggle = { enabled -> stateHolder.requestSafeBrowsingToggle(enabled) },
                    onOpenQueryLog = onOpenQueryLog,
                    onOpenPage = onOpenPage
                )
            }
            is AdGuardUiState.DemoData -> {
                AdGuardDashboardContent(
                    dashboard = state.dashboard,
                    repository = stateHolder.repository,
                    onProtectionToggle = { enabled -> stateHolder.requestProtectionToggle(enabled) },
                    onPauseTenMinutes = { stateHolder.requestProtectionToggle(enabled = false, durationMs = 600000L) },
                    onSafeBrowsingToggle = { enabled -> stateHolder.requestSafeBrowsingToggle(enabled) },
                    onOpenQueryLog = onOpenQueryLog,
                    onOpenPage = onOpenPage
                )
            }
        }
    }
}

/** A single card centred on the screen, for the non-dashboard states. */
@Composable
private fun CenteredCard(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        SfCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 12.dp, content = content)
    }
}

private fun agIcon(id: String) = when {
    id.contains("client") -> SfIcons.Devices
    id.contains("service") -> SfIcons.Ban
    id.contains("rewrite") -> SfIcons.Link
    id.contains("dns") || id.contains("upstream") -> SfIcons.Globe
    id.contains("access") -> SfIcons.Lock
    id.contains("log") || id.contains("stat") -> SfIcons.Chart
    id.contains("check") -> SfIcons.Check
    else -> SfIcons.Settings
}

private fun agHue(id: String) = when {
    id.contains("client") -> SfHue.VIOLET
    id.contains("service") -> SfHue.RED
    id.contains("rewrite") -> SfHue.TEAL
    id.contains("dns") || id.contains("upstream") -> SfHue.BLUE
    id.contains("access") -> SfHue.PINK
    id.contains("log") || id.contains("stat") -> SfHue.ORANGE
    else -> SfHue.SLATE
}

@Composable
private fun SectionTitle(text: String, hue: SfHue) {
    val (fg, _) = hue.colors()
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(width = 4.dp, height = 16.dp).clip(CircleShape).background(fg))
        Eyebrow(text)
    }
}

@Composable
private fun StatLine(hue: SfHue, label: String, value: String) {
    val (fg, _) = hue.colors()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(fg))
        Spacer(Modifier.size(8.dp))
        Text(label, color = SfTheme.colors.muted, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
        Text(value, color = SfTheme.colors.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable
private fun ControlTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    state: String,
    on: Boolean,
    hue: SfHue,
    modifier: Modifier,
    onClick: (() -> Unit)?
) {
    val c = SfTheme.colors
    val (fg, bg) = hue.colors()
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (on) bg else c.surface)
            .border(1.dp, if (on) bg else c.line, RoundedCornerShape(18.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = if (on) fg else c.muted, modifier = Modifier.size(24.dp))
        Text(title, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(state, color = if (on) fg else c.muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Ranked names with a coloured badge and a bar. */
@Composable
private fun RankList(items: List<Pair<String, Long>>, hue: SfHue) {
    val c = SfTheme.colors
    val (fg, bg) = hue.colors()
    val max = (items.maxOfOrNull { it.second } ?: 1L).coerceAtLeast(1L)
    SfList {
        items.forEachIndexed { idx, (name, count) ->
            if (idx > 0) SfDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                    Text("${idx + 1}", color = fg, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(name, style = SfText.mono, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(c.sunk)) {
                        Box(Modifier.fillMaxWidth(count.toFloat() / max.toFloat()).height(5.dp).background(fg))
                    }
                }
                Text(formatCount(count), style = SfText.mono, color = c.muted)
            }
        }
    }
}

private fun formatCount(n: Long): String = String.format(Locale.US, "%,d", n)

/** Muted value text at the end of a list row. */
@Composable
private fun RowValue(text: String) {
    Text(text, style = SfText.sub, color = SfTheme.colors.muted)
}

/** The mock's `.meter`: a thin track with an accent fill. */
@Composable
private fun Meter(fraction: Float) {
    val c = SfTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(99.dp))
            .background(c.sunk)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(c.accent)
        )
    }
}

/** AdGuard Home dashboard laid out as in the approved mock (screen 8). */
@Composable
private fun AdGuardDashboardContent(
    dashboard: AdGuardDashboard,
    repository: AdGuardRepository,
    onProtectionToggle: (Boolean) -> Unit,
    onPauseTenMinutes: () -> Unit,
    onSafeBrowsingToggle: (Boolean) -> Unit,
    onOpenQueryLog: (AdGuardQueryEntry) -> Unit,
    onOpenPage: (String) -> Unit = {}
) {
    val c = SfTheme.colors
    val status = dashboard.status
    val stats = dashboard.stats
    val paused = !status.protectionEnabled && status.protectionDisabledDurationMs > 0L

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PageHeading("AdGuard Home", "DNS protection and recent activity.",
            if (status.protectionEnabled) "Protection on" else "Protection off",
            if (status.protectionEnabled) PillTone.Ok else PillTone.Warn)
        SfCard(verticalSpacing = 10.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                com.shaazifi.app.ui.components.SectionTitle("Protection")
                OutlinedButton(onClick = { onProtectionToggle(!status.protectionEnabled) }) {
                    Text(if (status.protectionEnabled) "Pause protection" else "Enable protection")
                }
            }
            if (paused) Text("Paused for ${status.protectionDisabledDurationMs / 60000} more minutes", style = SfText.sub, color = c.warn)
            if (status.protectionEnabled) PageLink("Pause 10 min", onPauseTenMinutes)
        }
        val statsEnabled = dashboard.statsConfig.enabled
        val responseTime = stats.avgProcessingTimeMs.takeIf {
            statsEnabled && stats.numDnsQueries > 0 && it.isFinite() && it > 0
        }?.let { String.format(Locale.getDefault(), "%.1f ms", it) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (statsEnabled) dashboard.statsConfig.periodLabel else "Statistics off",
                style = SfText.sub,
                color = c.muted
            )
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QueryShortcut("Live queries", if (statsEnabled) formatCount(stats.numDnsQueries) else null,
                    "Open live log", SfIcons.Chart, SfHue.GREEN, Modifier.weight(1f).fillMaxHeight()) {
                    onOpenQueryLog(AdGuardQueryEntry.LIVE)
                }
                QueryShortcut("Blocked", if (statsEnabled) formatCount(stats.numBlockedFiltering) else null,
                    "View blocked history", SfIcons.Ban, SfHue.RED, Modifier.weight(1f).fillMaxHeight()) {
                    onOpenQueryLog(AdGuardQueryEntry.BLOCKED_HISTORY)
                }
            }
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QueryShortcut("Devices", null, "Choose a DNS device", SfIcons.Devices, SfHue.BLUE,
                    Modifier.weight(1f).fillMaxHeight()) {
                    onOpenQueryLog(AdGuardQueryEntry.DEVICES)
                }
                QueryShortcut("Average response", responseTime, "Open DNS settings", SfIcons.Bolt, SfHue.ORANGE,
                    Modifier.weight(1f).fillMaxHeight()) {
                    onOpenPage(AgPage.DNS.id)
                }
            }
        }
        var recent by remember(repository) { mutableStateOf<List<AdGuardQueryLogItem>?>(null) }
        var queriesUnavailable by remember(repository) { mutableStateOf(false) }
        LaunchedEffect(repository) {
            try {
                recent = repository.loadQueryLog(limit = 3).items.take(3)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                queriesUnavailable = true
            }
        }
        SfCard(verticalSpacing = 6.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                com.shaazifi.app.ui.components.SectionTitle("Recent queries", Modifier.weight(1f))
                PageLink("Query log", { onOpenQueryLog(AdGuardQueryEntry.LIVE) })
            }
            val queries = recent
            if (queries.isNullOrEmpty()) {
                Text(when {
                    queriesUnavailable -> "Recent queries unavailable."
                    queries == null -> "Reading recent queries..."
                    else -> "No recent queries."
                }, style = SfText.sub, color = c.muted)
            } else queries.forEach { query ->
                SfListRow(query.questionName, subtitle = query.clientName ?: query.client,
                    onClick = { onOpenQueryLog(AdGuardQueryEntry.LIVE) },
                    trailing = { Pill(query.outcomeLabel, if (query.isBlocked) PillTone.Warn else PillTone.Off) })
            }
        }
        com.shaazifi.app.ui.components.SectionTitle("AdGuard settings")
        AgPage.entries.chunked(2).forEach { pages ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pages.forEach { page ->
                    ControlTile(agIcon(page.id), page.title, page.subtitle, true, agHue(page.id), Modifier.weight(1f)) {
                        onOpenPage(page.id)
                    }
                }
                if (pages.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        val bars = stats.dnsQueries.takeLast(24)
        if (bars.isNotEmpty()) {
            val unit = if (stats.timeUnits.startsWith("day")) "days" else "hours"
            SfCard(verticalSpacing = 10.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (unit == "days") "Queries by day" else "Queries by hour", fontWeight = FontWeight.Bold, color = c.ink, modifier = Modifier.weight(1f))
                    Text("last ${bars.size} $unit", style = SfText.sub, color = c.muted)
                }
                val maxVal = (bars.maxOrNull() ?: 1).coerceAtLeast(1)
                val (barFg, _) = SfHue.BLUE.colors()
                Row(
                    modifier = Modifier.fillMaxWidth().height(90.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    bars.forEachIndexed { i, count ->
                        val ratio = (count.toFloat() / maxVal.toFloat()).coerceIn(0.04f, 1f)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(ratio)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(if (i == bars.lastIndex) barFg else barFg.copy(alpha = 0.55f))
                        )
                    }
                }
                Row {
                    Text("${bars.size} $unit ago", style = SfText.sub, color = c.muted, modifier = Modifier.weight(1f))
                    Text("now", style = SfText.sub, color = c.muted)
                }
            }
        }

        val safeBrowsing = dashboard.safeBrowsingEnabled
        SfList {
            SfListRow("Safe browsing", subtitle = when (safeBrowsing) { true -> "On"; false -> "Off"; null -> "Unavailable" },
                icon = SfIcons.Shield, hue = SfHue.GREEN,
                onClick = safeBrowsing?.let { { onSafeBrowsingToggle(!it) } })
            SfDivider()
            SfListRow("Parental filter", subtitle = when (dashboard.parentalEnabled) { true -> "On"; false -> "Off"; null -> "Unavailable" },
                icon = SfIcons.Lock, hue = SfHue.VIOLET)
        }

        if (stats.topBlockedDomains.isNotEmpty()) {
            SectionTitle("Most blocked", SfHue.RED)
            RankList(stats.topBlockedDomains.take(5), SfHue.RED)
        }
        if (stats.topQueriedDomains.isNotEmpty()) {
            SectionTitle("Most visited", SfHue.BLUE)
            RankList(stats.topQueriedDomains.take(5), SfHue.BLUE)
        }
        if (stats.topClients.isNotEmpty()) {
            SectionTitle("Busiest devices", SfHue.VIOLET)
            RankList(stats.topClients.take(5), SfHue.VIOLET)
        }

        if (dashboard.isCleartextHttp) {
            Note("AdGuard Home is reached over plain HTTP, so its password crosses your home network unencrypted. The app only does this on private addresses.")
        }

        Text("AdGuard Home ${status.version}", style = SfText.mono, color = c.muted, modifier = Modifier.padding(bottom = 8.dp))
    }
}

@Composable
private fun QueryShortcut(
    title: String,
    value: String?,
    action: String,
    icon: ImageVector,
    hue: SfHue,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val (accent, _) = hue.colors()
    SfCard(modifier = modifier, onClick = onClick, verticalSpacing = 6.dp) {
        HueTile(icon, hue, size = 28.dp)
        Text(title, style = MaterialTheme.typography.titleSmall, color = SfTheme.colors.ink)
        if (value != null) Text(value, style = MaterialTheme.typography.titleLarge, color = accent)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(action, style = SfText.sub, color = accent, modifier = Modifier.weight(1f))
            Icon(SfIcons.ChevronRight, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
        }
    }
}
