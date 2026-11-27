package com.shaazifi.app.feature.adguard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.shaazifi.app.core.adguard.AdGuardCaller
import com.shaazifi.app.core.adguard.AdGuardError
import com.shaazifi.app.data.AdGuardRepository
import com.shaazifi.app.feature.adguard.manage.AgAction
import com.shaazifi.app.feature.adguard.manage.AgChanges
import com.shaazifi.app.feature.adguard.manage.AgQueryFilter
import com.shaazifi.app.ui.components.HueTile
import com.shaazifi.app.ui.components.Note
import com.shaazifi.app.ui.components.PageHeading
import com.shaazifi.app.ui.components.PageLink
import com.shaazifi.app.ui.components.Pill
import com.shaazifi.app.ui.components.SfCard
import com.shaazifi.app.ui.components.SfListRow
import com.shaazifi.app.ui.icons.SfIcons
import com.shaazifi.app.ui.theme.PillTone
import com.shaazifi.app.ui.theme.SfHue
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class QueryLogMode { Live, History }

private data class QueryLogRowUi(
    val item: AdGuardQueryLogItem,
    val displayTime: String,
    val filterDevice: AdGuardLogDevice?,
    val clientLabel: String,
    val canChangeRule: Boolean
)

@Composable
fun AdGuardQueryLogScreen(
    repository: AdGuardRepository,
    modifier: Modifier = Modifier,
    caller: AdGuardCaller? = null,
    demo: Boolean = false,
    entry: AdGuardQueryEntry = AdGuardQueryEntry.LIVE,
    onBack: () -> Unit,
    onSignIn: () -> Unit = onBack
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val reader = remember(repository) { AdGuardQueryLogReader(repository) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val c = SfTheme.colors

    var mode by rememberSaveable(entry) {
        mutableStateOf(if (entry == AdGuardQueryEntry.BLOCKED_HISTORY) QueryLogMode.History else QueryLogMode.Live)
    }
    var paused by remember(reader) { mutableStateOf(false) }
    var choosingDevice by rememberSaveable(entry) { mutableStateOf(entry == AdGuardQueryEntry.DEVICES) }
    var pickerFromDashboard by rememberSaveable(entry) { mutableStateOf(entry == AdGuardQueryEntry.DEVICES) }
    var selectedDeviceId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedDeviceName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedDevice = remember(selectedDeviceId, selectedDeviceName) {
        selectedDeviceId?.let { AdGuardLogDevice.fromIdentifier(it, selectedDeviceName) }
    }
    var resultFilter by rememberSaveable(entry) {
        mutableStateOf(if (entry == AdGuardQueryEntry.BLOCKED_HISTORY) AgQueryFilter.BLOCKED else AgQueryFilter.ALL)
    }
    var searchInput by rememberSaveable { mutableStateOf("") }
    var historySearch by remember(reader) { mutableStateOf(searchInput.trim()) }
    var refresh by remember(reader) { mutableIntStateOf(0) }

    var settings by remember(reader) { mutableStateOf(AdGuardQueryLogSettings()) }
    var settingsRead by remember(reader) { mutableStateOf(false) }
    var live by remember(reader) { mutableStateOf(LiveQueryLog()) }
    var knownDevices by remember(reader) { mutableStateOf(emptyList<AdGuardLogDevice>()) }
    var deviceReadFailed by remember(reader) { mutableStateOf(false) }
    var deviceNeedsSignIn by remember(reader) { mutableStateOf(false) }
    var loadingDevices by remember(reader) { mutableStateOf(true) }
    var exportOpen by rememberSaveable { mutableStateOf(false) }
    var inspectedQuery by remember(reader) { mutableStateOf<AdGuardQueryLogItem?>(null) }
    var pendingAction by remember(reader) { mutableStateOf<AgAction?>(null) }
    var applyingRule by remember(reader) { mutableStateOf(false) }
    var actionMessage by remember(reader) { mutableStateOf<String?>(null) }
    var actionIsError by remember(reader) { mutableStateOf(false) }
    var actionNeedsSignIn by remember(reader) { mutableStateOf(false) }

    val allowAddress = settings.anonymizeClientIp != true
    val loggingDisabled = settings.enabled == false
    val device = selectedDevice?.takeUnless { it.isAddress && !allowAddress }
    val historyFilter = remember(device, historySearch, resultFilter) {
        QueryLogFilter(device, historySearch, resultFilter)
    }
    var cursors by remember(reader, historyFilter) { mutableStateOf(listOf<String?>(null)) }
    val cursor = cursors.last()
    var history by remember(reader, historyFilter, cursor, allowAddress) { mutableStateOf<QueryHistoryPage?>(null) }
    var historyError by remember(reader, historyFilter, cursor) { mutableStateOf<String?>(null) }
    var historyNeedsSignIn by remember(reader, historyFilter, cursor) { mutableStateOf(false) }
    var historyLoading by remember(reader, historyFilter, cursor) { mutableStateOf(true) }

    LaunchedEffect(reader, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            readQueryLog { repository.loadQueryLogSettings() }.getOrNull()?.let { updated ->
                if (updated.anonymizeClientIp == true && settings.anonymizeClientIp != true) {
                    live = LiveQueryLog()
                    knownDevices = knownDevices.filterNot { it.isAddress }
                    if (selectedDevice?.isAddress == true) {
                        selectedDeviceId = null
                        selectedDeviceName = null
                        if (exportOpen) exportOpen = false
                    }
                    inspectedQuery = null
                }
                settings = updated
            }
            settingsRead = true
        }
    }
    LaunchedEffect(reader, searchInput) {
        delay(250)
        historySearch = searchInput.trim()
    }
    LaunchedEffect(reader, lifecycle, mode, paused, choosingDevice, refresh, loggingDisabled, allowAddress, exportOpen) {
        if (mode != QueryLogMode.Live || paused || choosingDevice || loggingDisabled || exportOpen) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            live = live.copy(status = LiveLogStatus.CONNECTING, error = null, needsSignIn = false)
            reader.live(live).collect { incoming ->
                if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 &&
                    live.items.firstOrNull()?.key != incoming.items.firstOrNull()?.key) {
                    listState.requestScrollToItem(0)
                }
                live = incoming
            }
        }
    }
    LaunchedEffect(reader, lifecycle, mode, choosingDevice, historyFilter, cursor, refresh, allowAddress, exportOpen) {
        if (mode != QueryLogMode.History || choosingDevice || exportOpen) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            historyLoading = true
            historyError = null
            historyNeedsSignIn = false
            readQueryLog { reader.history(historyFilter, cursor) }
                .onSuccess { history = it }
                .onFailure {
                    historyError = (it as? Exception)?.queryLogMessage() ?: "Couldn't read the query log."
                    historyNeedsSignIn = it is AdGuardError.Unauthorized
                }
            historyLoading = false
        }
    }
    LaunchedEffect(reader, mode, device, resultFilter, historySearch, cursor, choosingDevice) {
        if (!choosingDevice) listState.requestScrollToItem(0)
    }
    LaunchedEffect(reader, lifecycle, choosingDevice) {
        if (!choosingDevice) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            deviceReadFailed = false
            deviceNeedsSignIn = false
            loadingDevices = true
            readQueryLog { repository.loadQueryLogClients() }
                .onSuccess { knownDevices = it }
                .onFailure {
                    deviceReadFailed = true
                    deviceNeedsSignIn = it is AdGuardError.Unauthorized
                }
            loadingDevices = false
        }
    }

    val currentFilter = remember(device, searchInput, resultFilter) {
        QueryLogFilter(device, searchInput.trim(), resultFilter)
    }
    if (exportOpen) {
        AdGuardQueryLogExportDialog(reader, currentFilter, onClose = { exportOpen = false })
    }

    val closePicker = { if (pickerFromDashboard) onBack() else choosingDevice = false }
    BackHandler(enabled = choosingDevice, onBack = closePicker)
    if (choosingDevice) {
        val devices = remember(knownDevices, live.items, history?.items, settings) {
            val seen = (live.items + history?.items.orEmpty()).mapNotNull { it.logDevice(allowAddress) }
            val directory = knownDevices.filter { !it.isAddress || settings.anonymizeClientIp == false }
            (seen.filter { it.name != null } + directory + seen)
                .filter { allowAddress || !it.isAddress }
                .distinctBy { it.identifier }
                .sortedWith(compareBy({ it.name == null }, { it.label.lowercase() }, { it.identifier }))
        }
        QueryLogDevicePicker(
            devices = if (settingsRead) devices else emptyList(),
            selected = device,
            allowAddress = allowAddress,
            loading = !settingsRead || loadingDevices,
            allowManualEntry = settingsRead,
            loadFailed = deviceReadFailed,
            needsSignIn = deviceNeedsSignIn,
            backLabel = if (pickerFromDashboard) "Back to AdGuard" else "Back to query log",
            onBack = closePicker,
            onSignIn = onSignIn
        ) {
            selectedDeviceId = it?.identifier
            selectedDeviceName = it?.name
            pickerFromDashboard = false
            choosingDevice = false
        }
        return
    }

    val onRuleChange: ((AdGuardQueryLogItem) -> Unit)? = remember(caller, applyingRule) {
        if (caller == null || applyingRule) null else { item ->
            AgChanges.domainOrNull(item.questionName)?.let { domain ->
                pendingAction = if (item.isBlocked) AgChanges.allowDomain(domain) else AgChanges.blockDomain(domain)
            }
        }
    }
    val onSelectDevice = remember {
        { chosen: AdGuardLogDevice ->
            selectedDeviceId = chosen.identifier
            selectedDeviceName = chosen.name
        }
    }
    val onInspect = remember {
        { item: AdGuardQueryLogItem -> inspectedQuery = item }
    }
    if (pendingAction == null) {
        inspectedQuery?.let { item ->
            AdGuardQueryInspector(item, onClose = { inspectedQuery = null }, onRuleChange = onRuleChange)
        }
    }

    pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(action.title, fontWeight = FontWeight.Bold) },
            text = { Text(action.message) },
            confirmButton = {
                TextButton(onClick = {
                    if (applyingRule) return@TextButton
                    val service = caller ?: return@TextButton
                    applyingRule = true
                    pendingAction = null
                    inspectedQuery = null
                    coroutineScope.launch {
                        try {
                            val result = readQueryLog { action.perform(service) }
                            result.onSuccess { msg ->
                                actionMessage = msg ?: "Done."
                                actionIsError = false
                                actionNeedsSignIn = false
                            }.onFailure { err ->
                                actionMessage = (err as? Exception)?.queryLogMessage()
                                    ?: "AdGuard Home couldn't apply this rule."
                                actionIsError = true
                                actionNeedsSignIn = err is AdGuardError.Unauthorized
                            }
                        } finally {
                            applyingRule = false
                        }
                    }
                }) { Text(action.label) }
            },
            dismissButton = { TextButton(onClick = { pendingAction = null }) { Text("Cancel") } }
        )
    }

    val isLive = mode == QueryLogMode.Live
    val rows = if (isLive) remember(live.items, currentFilter) {
        live.items.filter { currentFilter.matches(it) && resultFilter.matchesLive(it) }
    } else history?.items.orEmpty()
    val timeFormat = remember(mode) {
        DateTimeFormatter.ofPattern(if (isLive) "HH:mm:ss" else "MMM d, HH:mm:ss").withZone(ZoneId.systemDefault())
    }
    val rowModels = remember(rows, allowAddress, device?.identifier, timeFormat) {
        rows.map { item ->
            val identifier = item.clientId ?: item.client
            QueryLogRowUi(
                item = item,
                displayTime = item.timestamp?.let(timeFormat::format) ?: item.time,
                filterDevice = item.logDevice(allowAddress)?.takeUnless { it.identifier == device?.identifier },
                clientLabel = item.clientName?.let { "$it · $identifier" } ?: identifier,
                canChangeRule = AgChanges.domainOrNull(item.questionName) != null
            )
        }
    }
    Column(
        modifier.fillMaxSize().background(c.bg).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            PageLink("Back to AdGuard", onBack, back = true)
            TextButton(onClick = { exportOpen = true }) { Text("Export") }
        }
        PageHeading("DNS query log", "", if (demo) "Demo" else null)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QueryLogMode.entries.forEach { option ->
                Pill(option.name, if (mode == option) PillTone.Ok else PillTone.Off, onClick = { mode = option })
            }
            OutlinedButton(onClick = { choosingDevice = true }, modifier = Modifier.weight(1f)) {
                Text(device?.label ?: "All devices", maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(" ▾")
            }
        }
        OutlinedTextField(
            value = searchInput,
            onValueChange = { searchInput = it },
            label = { Text(if (device == null) "Find a domain or device" else "Find a domain") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AgQueryFilter.entries.forEach { result ->
                Pill(result.label, if (result == resultFilter) PillTone.Ok else PillTone.Off, onClick = { resultFilter = result })
            }
        }
        if (actionMessage != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    actionMessage.orEmpty(),
                    style = SfText.sub,
                    color = if (actionIsError) c.bad else c.ink,
                    modifier = Modifier.weight(1f)
                )
                if (actionNeedsSignIn) {
                    TextButton(onClick = onSignIn) { Text("Sign in") }
                }
                TextButton(onClick = {
                    actionMessage = null
                    actionIsError = false
                    actionNeedsSignIn = false
                }) { Text("Dismiss") }
            }
        }
        if (isLive) {
            val status = when {
                settings.enabled == false -> "Query logging is off in AdGuard Home."
                paused -> "Live updates paused"
                live.status == LiveLogStatus.CONNECTING -> "Connecting…"
                live.status == LiveLogStatus.RETRYING -> "Reconnecting · ${live.error.orEmpty()}"
                live.status == LiveLogStatus.UNAVAILABLE -> live.error.orEmpty()
                else -> "Updating live"
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(status, style = SfText.sub, color = if (live.status == LiveLogStatus.UNAVAILABLE) c.bad else c.muted, modifier = Modifier.weight(1f))
                if (settings.enabled != false) {
                    if (live.status == LiveLogStatus.UNAVAILABLE && !paused) {
                        if (live.needsSignIn) {
                            TextButton(onClick = onSignIn) { Text("Sign in") }
                        } else {
                            TextButton(onClick = { refresh++ }) { Text("Retry") }
                        }
                    } else {
                        TextButton(onClick = { paused = !paused }) { Text(if (paused) "Resume" else "Pause") }
                    }
                }
            }
            if (live.hasGap) Note("Some live queries may have been missed. Check History for retained records.")
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Page ${cursors.size}", style = SfText.sub, color = c.muted, modifier = Modifier.weight(1f))
                TextButton(onClick = { cursors = cursors.dropLast(1) }, enabled = cursors.size > 1) { Text("Newer") }
                val next = history?.olderThan
                TextButton(onClick = { if (next != null) cursors = cursors + next },
                    enabled = next != null && !historyLoading) { Text("Older") }
            }
            if (historyLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            historyError?.let { error ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(error, style = SfText.sub, color = c.bad, modifier = Modifier.weight(1f))
                    if (historyNeedsSignIn) {
                        TextButton(onClick = onSignIn) { Text("Sign in") }
                    } else {
                        TextButton(onClick = { refresh++ }) { Text("Retry") }
                    }
                }
            }
        }
        if (rows.isEmpty()) {
            val empty = when {
                isLive && settings.enabled == false -> "Open History to view queries AdGuard Home still retains."
                isLive && (live.status == LiveLogStatus.CONNECTING || live.status == LiveLogStatus.RETRYING) -> null
                isLive && live.status == LiveLogStatus.UNAVAILABLE -> null
                isLive && paused -> "Resume to see new queries."
                isLive -> "Waiting for matching DNS queries."
                historyLoading || historyError != null -> null
                history?.olderThan != null -> "No matching queries on this page. Tap Older to keep looking."
                else -> "No matching queries in the retained log."
            }
            if (empty != null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Note(empty) }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(rowModels, key = { it.item.key }, contentType = { "query_row" }) { row ->
                    QueryLogItemCard(
                        row = row,
                        onSelectDevice = onSelectDevice,
                        onInspect = onInspect,
                        onRuleChange = onRuleChange
                    )
                }
            }
        }
    }
}

@Composable
private fun QueryLogDevicePicker(
    devices: List<AdGuardLogDevice>,
    selected: AdGuardLogDevice?,
    allowAddress: Boolean,
    loading: Boolean,
    allowManualEntry: Boolean,
    loadFailed: Boolean,
    needsSignIn: Boolean,
    backLabel: String,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onChoose: (AdGuardLogDevice?) -> Unit
) {
    val c = SfTheme.colors
    var search by remember { mutableStateOf("") }
    val term = search.trim()
    val visible = remember(devices, term) {
        devices.filter { it.label.contains(term, true) || it.identifier.contains(term, true) }
    }
    val typed = remember(term, allowManualEntry, allowAddress, devices, visible) {
        AdGuardLogDevice.fromIdentifier(term).takeIf { allowManualEntry }
            ?.takeIf { (allowAddress || !it.isAddress) && devices.none { known -> known.identifier == it.identifier } }
            ?.takeIf { visible.isEmpty() }
    }
    Column(Modifier.fillMaxSize().background(c.bg).imePadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PageLink(backLabel, onBack, back = true)
        PageHeading("Choose a DNS device")
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text(if (allowAddress) "Find by name, IP or Client ID" else "Find by name or Client ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (!allowAddress) Note("IP anonymization is on. Choose a device with a Client ID.")
        if (loadFailed) {
            if (needsSignIn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sign in to AdGuard Home again to load saved devices.", style = SfText.sub, color = c.bad, modifier = Modifier.weight(1f))
                    TextButton(onClick = onSignIn) { Text("Sign in") }
                }
            } else {
                Note("Couldn't load the device list. Recent DNS devices are still available.")
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                SfListRow("All devices",
                    modifier = Modifier.semantics { this.selected = selected == null },
                    icon = SfIcons.Globe, hue = SfHue.GREEN, onClick = { onChoose(null) },
                    trailing = { if (selected == null) Pill("Selected") })
            }
            items(visible, key = { it.identifier }) { device ->
                val isSelected = selected?.identifier == device.identifier
                SfListRow(device.label,
                    modifier = Modifier.semantics { this.selected = isSelected },
                    subtitle = device.name?.let { device.identifier }, icon = SfIcons.Devices, hue = SfHue.BLUE,
                    onClick = { onChoose(device) },
                    trailing = { if (isSelected) Pill("Selected") else Text("›", color = c.muted) })
            }
            if (typed != null) {
                item {
                    SfListRow("Use ${typed.identifier}", subtitle = if (typed.isAddress) "IP address" else "Client ID",
                        onClick = { onChoose(typed) })
                }
            } else if (visible.isEmpty() && !loading) {
                item { Note(if (term.isEmpty()) "Recent devices will appear after AdGuard Home sees their queries." else "No matching devices.") }
            }
        }
    }
}

@Composable
private fun QueryLogItemCard(
    row: QueryLogRowUi,
    onSelectDevice: (AdGuardLogDevice) -> Unit,
    onInspect: (AdGuardQueryLogItem) -> Unit,
    onRuleChange: ((AdGuardQueryLogItem) -> Unit)?
) {
    val c = SfTheme.colors
    val item = row.item
    SfCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 6.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HueTile(if (item.isBlocked) SfIcons.Ban else SfIcons.Check, if (item.isBlocked) SfHue.RED else SfHue.GREEN,
                size = 36.dp, round = true)
            Column(Modifier.weight(1f)) {
                Text(item.unicodeName ?: item.questionName, style = SfText.mono.copy(fontWeight = FontWeight.SemiBold),
                    color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(row.displayTime, style = SfText.mono, color = c.muted)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Pill(item.questionType, tone = PillTone.Off)
                Text(item.outcomeLabel, style = SfText.sub, color = if (item.isBlocked) c.bad else c.muted)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val deviceTarget = row.filterDevice
            if (deviceTarget == null) {
                Text(row.clientLabel, style = SfText.sub, color = c.muted, modifier = Modifier.weight(1f))
            } else {
                PageLink(row.clientLabel, { onSelectDevice(deviceTarget) }, Modifier.weight(1f))
            }
            if (item.elapsedMs.isNotBlank()) Text("${item.elapsedMs} ms", style = SfText.mono, color = c.muted)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (onRuleChange != null && row.canChangeRule) {
                val label = if (item.isBlocked) "Allow this site" else "Block this site"
                Pill(label, if (item.isBlocked) PillTone.Ok else PillTone.Bad, onClick = { onRuleChange(item) })
            }
            Box(Modifier.weight(1f))
            PageLink("Inspect", { onInspect(item) })
        }
    }
}

private suspend fun <T> readQueryLog(read: suspend () -> T): Result<T> = try {
    Result.success(read())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
