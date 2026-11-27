package com.shaazifi.app.feature.adguard.manage

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shaazifi.app.core.adguard.AdGuardCaller
import com.shaazifi.app.ui.components.Eyebrow
import com.shaazifi.app.ui.components.GhostButton
import com.shaazifi.app.ui.components.Note
import com.shaazifi.app.ui.components.Pill
import com.shaazifi.app.ui.components.PrimaryButton
import com.shaazifi.app.ui.components.SfCard
import com.shaazifi.app.ui.components.SfDivider
import com.shaazifi.app.ui.components.SfList
import com.shaazifi.app.ui.components.SfSwitch
import com.shaazifi.app.ui.icons.SfIcons
import com.shaazifi.app.ui.theme.PillTone
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme
import kotlinx.coroutines.launch

/** Bulk DNS rewrites: paste a `domain answer` list, or copy the current list to the clipboard. */
@Composable
fun RewriteBulkCard(holder: AgPageHolder) {
    val c = SfTheme.colors
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var confirmImport by remember { mutableStateOf(false) }
    if (confirmImport) AlertDialog(
        onDismissRequest = { confirmImport = false },
        title = { Text("Import DNS rewrites?") },
        text = { Text("Add the new rows and remove the ones you deleted from the list. AdGuard Home applies the change at once.") },
        confirmButton = {
            TextButton(onClick = {
                confirmImport = false
                scope.launch { status = holder.importRewrites(text) }
            }) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = { confirmImport = false }) { Text("Cancel") } }
    )
    SfCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 8.dp) {
        Text("Import or export rewrites", style = MaterialTheme.typography.titleMedium, color = c.ink)
        Text("One “domain answer” per line. Lines starting with # are ignored.", style = SfText.sub, color = c.muted)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Rewrites") },
            minLines = 3,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Import", enabled = text.isNotBlank(), onClick = { confirmImport = true })
            GhostButton("Export here", onClick = {
                scope.launch {
                    status = try {
                        text = holder.exportRewrites()
                        "Loaded ${text.lineSequence().count { it.isNotBlank() }} rewrite(s)."
                    } catch (e: Exception) {
                        "Could not export rewrites (${e::class.simpleName})."
                    }
                }
            })
        }
        status?.let { Text(it, style = SfText.sub, color = c.muted) }
    }
}

/** One AdGuard Home management page: rows, folded forms, actions, and the confirmation dialog. */
@Composable
fun AgPageScreen(holder: AgPageHolder, page: AgPage, onSignIn: () -> Unit) {
    val c = SfTheme.colors
    val scope = rememberCoroutineScope()
    val state by holder.state.collectAsState()
    val pending by holder.pending.collectAsState()
    val message by holder.message.collectAsState()
    val busy by holder.busy.collectAsState()
    var serviceSearch by rememberSaveable(page) { mutableStateOf("") }
    LaunchedEffect(holder) { holder.refresh() }
    val request = remember(holder, scope, busy) {
        { a: AgAction -> if (!busy) scope.launch { holder.request(a) } }
    }

    pending?.let { a ->
        AlertDialog(
            onDismissRequest = { if (!busy) holder.dismiss() },
            title = { Text(a.title, fontWeight = FontWeight.Bold) },
            text = { if (a.message.isNotBlank()) Text(a.message) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = { scope.launch { holder.confirm() } }
                ) {
                    Text(
                        if (a.destructive) "Yes, ${a.label.lowercase()}" else a.label,
                        color = if (a.destructive) c.bad else c.accent
                    )
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { holder.dismiss() }) { Text("Cancel") }
            }
        )
    }

    val (heroFg, heroBg) = com.shaazifi.app.ui.theme.SfHue.VIOLET.colors()
    val (barFg, _) = com.shaazifi.app.ui.theme.SfHue.VIOLET.colors()
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(c.bg),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "header") {
            // Owner feedback 2026-11-18: same coloured header as every other page.
            Column(
                Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).background(heroBg).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(46.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c.surface), contentAlignment = Alignment.Center) {
                        Icon(SfIcons.Shield, contentDescription = null, tint = heroFg, modifier = Modifier.size(24.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(page.title, style = MaterialTheme.typography.titleLarge, color = c.ink, fontWeight = FontWeight.Bold)
                        Text("AdGuard Home", color = heroFg, fontWeight = FontWeight.SemiBold, style = SfText.sub)
                    }
                    TextButton(
                        enabled = !busy,
                        onClick = { scope.launch { holder.refresh() } }
                    ) {
                        Text("Refresh", color = if (busy) c.muted else heroFg)
                    }
                }
                (state as? AgState.Loaded)?.view?.summary?.let { Text(it, style = SfText.sub, color = c.ink) }
                val pill = (state as? AgState.Loaded)?.view?.pill
                if (holder.demo || pill != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pill?.let { Pill(it.text, tone = it.tone) }
                    if (holder.demo) Pill("Demo", tone = PillTone.Off)
                }
            }
        }
        if (page == AgPage.REWRITES && !holder.demo) {
            item(key = "rewrite-bulk") {
                RewriteBulkCard(holder)
            }
        }
        if (busy) {
            item(key = "busy") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.accent)
                    Text("Working...", style = SfText.sub, color = c.muted)
                }
            }
        }
        message?.let { m ->
            item(key = "message") {
                SfCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 6.dp) {
                    Text(m, style = SfText.mono, color = c.ink)
                    TextButton(onClick = { holder.clearMessage() }) { Text("OK") }
                }
            }
        }
        when (val s = state) {
            AgState.Loading -> {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = c.accent)
                    }
                }
            }
            is AgState.Failed -> {
                item(key = "failed") {
                    SfCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 10.dp) {
                        Text(s.message, style = SfText.sub, color = c.muted)
                        if (s.needsPassword) PrimaryButton("Go to AdGuard sign in", onClick = onSignIn)
                        GhostButton("Try again", enabled = !busy, onClick = { scope.launch { holder.refresh() } })
                    }
                }
            }
            is AgState.Loaded -> {
                val view = s.view
                if (page == AgPage.SERVICES) {
                    item(key = "service_search") {
                        OutlinedTextField(
                            value = serviceSearch,
                            onValueChange = { serviceSearch = it },
                            label = { Text("Find a service") },
                            singleLine = true,
                            enabled = !busy,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                val term = if (page == AgPage.SERVICES) serviceSearch.trim() else ""
                view.sections.forEachIndexed { secIdx, section ->
                    val rows = if (term.isEmpty()) {
                        section.rows
                    } else {
                        section.rows.filter {
                            it.label.contains(term, ignoreCase = true) ||
                                it.sub?.contains(term, ignoreCase = true) == true
                        }
                    }
                    item(key = "sec_${secIdx}_header") {
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(width = 4.dp, height = 16.dp).clip(androidx.compose.foundation.shape.CircleShape).background(barFg))
                            Eyebrow(section.heading)
                        }
                    }
                    if (rows.isEmpty()) {
                        item(key = "sec_${secIdx}_empty") {
                            Note(
                                if (term.isNotEmpty()) "No matching services."
                                else section.emptyText ?: "Nothing here."
                            )
                        }
                    } else {
                        itemsIndexed(
                            items = rows,
                            key = { rowIdx, r -> "sec_${secIdx}_row_${rowIdx}_${r.label}" },
                            contentType = { _, _ -> "ag_row" }
                        ) { _, r ->
                            SfCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                                AgRowView(r, enabled = !busy, request = request)
                            }
                        }
                    }
                }
                if (view.forms.isNotEmpty() || view.actions.isNotEmpty()) {
                    item(key = "changes_header") {
                        Eyebrow("Changes", modifier = Modifier.padding(top = 8.dp))
                    }
                    item(key = "changes_list") {
                        SfList {
                            var first = true
                            view.forms.forEach { f ->
                                if (!first) SfDivider(); first = false
                                AgFormView(f, enabled = !busy, request = request)
                            }
                            view.actions.forEach { a ->
                                if (!first) SfDivider(); first = false
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable(enabled = !busy) { request(a) }
                                        .padding(horizontal = 14.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    com.shaazifi.app.ui.components.HueTile(
                                        if (a.destructive) SfIcons.Ban else SfIcons.Bolt,
                                        if (a.destructive) com.shaazifi.app.ui.theme.SfHue.RED else com.shaazifi.app.ui.theme.SfHue.ORANGE,
                                        size = 34.dp
                                    )
                                    Text(
                                        a.label,
                                        color = if (!busy) (if (a.destructive) c.bad else c.ink) else c.muted,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
                view.note?.let { note ->
                    item(key = "view_note") { Note(note) }
                }
                item(key = "confirm_note") {
                    Note("Every change is shown to you first. AdGuard Home applies it at once; there is no automatic undo.")
                }
            }
        }
        item(key = "bottom_spacer") { Spacer(Modifier.padding(bottom = 12.dp)) }
    }
}

@Composable
private fun AgRowView(r: AgRow, enabled: Boolean, request: (AgAction) -> Unit) {
    val c = SfTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .then(if (r.onToggle != null) Modifier.clickable(enabled = enabled) { request(r.onToggle) } else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val state = r.pill?.tone ?: r.toggle?.let { if (it) PillTone.Ok else PillTone.Off }
        if (state != null) {
            val (icon, hue) = when (state) {
                PillTone.Ok -> SfIcons.Check to com.shaazifi.app.ui.theme.SfHue.GREEN
                PillTone.Warn -> SfIcons.Bolt to com.shaazifi.app.ui.theme.SfHue.ORANGE
                PillTone.Bad -> SfIcons.Ban to com.shaazifi.app.ui.theme.SfHue.RED
                PillTone.Off -> SfIcons.More to com.shaazifi.app.ui.theme.SfHue.SLATE
            }
            com.shaazifi.app.ui.components.HueTile(icon, hue, size = 34.dp, round = true)
        }
        Column(Modifier.weight(1f)) {
            Text(r.label, color = c.ink, fontWeight = FontWeight.SemiBold, style = if (r.mono) SfText.mono else MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            r.sub?.let { Text(it, style = SfText.sub, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            if (r.value.isNotEmpty() && r.value.contains('\n')) Text(r.value, style = SfText.mono, color = c.muted)
        }
        if (r.value.isNotEmpty() && !r.value.contains('\n')) {
            Text(r.value, style = if (r.mono) SfText.mono else SfText.sub, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        r.pill?.let { Pill(it.text, tone = it.tone) }
        r.extra?.let { e -> Pill(e.label, tone = PillTone.Bad, onClick = if (enabled) ({ request(e) }) else null) }
        if (r.toggle != null) SfSwitch(checked = r.toggle, enabled = enabled && r.onToggle != null)
    }
}

@Composable
private fun AgFormView(form: AgForm, enabled: Boolean, request: (AgAction) -> Unit) {
    val c = SfTheme.colors
    var open by remember(form.title) { mutableStateOf(false) }
    var error by remember(form.title) { mutableStateOf<String?>(null) }
    val values = remember(form.title) { mutableStateMapOf<String, String>().apply { form.fields.forEach { put(it.key, it.initial) } } }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { open = !open; error = null }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        com.shaazifi.app.ui.components.HueTile(SfIcons.Pencil, com.shaazifi.app.ui.theme.SfHue.BLUE, size = 34.dp)
        Spacer(Modifier.size(12.dp))
        Text(form.title, color = c.ink, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Icon(SfIcons.ChevronRight, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp).rotate(if (open) 90f else 0f))
    }
    if (!open) return
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        form.fields.forEach { f ->
            f.pick?.let { kind -> com.shaazifi.app.feature.router.DevicePicker(kind, values[f.key].orEmpty()) { if (enabled) { values[f.key] = it; error = null } } }
            if (f.choices.isNotEmpty()) com.shaazifi.app.feature.router.ChoiceChips(f.choices, f.multi, values[f.key].orEmpty()) { if (enabled) { values[f.key] = it; error = null } }
            OutlinedTextField(
                value = values[f.key].orEmpty(),
                onValueChange = { values[f.key] = it; error = null },
                label = { Text(f.label) },
                placeholder = f.hint?.let { h -> { Text(h) } },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = if (f.numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )
        }
        error?.let { Text(it, style = SfText.sub, color = c.bad) }
        PrimaryButton(form.submitLabel, enabled = enabled, onClick = {
            when (val b = form.build(values.toMap())) {
                is AgBuild.Invalid -> error = b.message
                is AgBuild.Ready -> {
                    error = null
                    form.fields.forEach { f -> values[f.key] = f.initial }
                    open = false
                    request(b.action)
                }
            }
        })
    }
}

/** Offline demo answers for every page. Writes are not sent anywhere. */
class DemoAdGuardCaller : AdGuardCaller {
    private val replies = mapOf(
        "/filtering/status" to """{"enabled":true,"interval":24,"filters":[{"id":1,"name":"AdGuard DNS filter","url":"https://adguardteam.github.io/HostlistsRegistry/assets/filter_1.txt","enabled":true,"rules_count":61234,"last_updated":"2026-11-17T08:00:00Z"},{"id":2,"name":"AdAway Default Blocklist","url":"https://adaway.org/hosts.txt","enabled":false,"rules_count":6540}],"whitelist_filters":[],"user_rules":["||ads.example.com^","@@||bank.example.com^"]}""",
        "/blocked_services/all" to """{"blocked_services":[{"id":"tiktok","name":"TikTok","group_id":"social_network"},{"id":"youtube","name":"YouTube","group_id":"video"},{"id":"steam","name":"Steam","group_id":"gaming"}],"groups":[]}""",
        "/blocked_services/get" to """{"ids":["tiktok"],"schedule":{"time_zone":"Local"}}""",
        "/clients" to """{"clients":[{"name":"Living room TV","ids":["192.168.1.40"],"use_global_settings":true}],"auto_clients":[{"ip":"192.168.1.23","name":"phone.lan","source":"rDNS"}],"supported_tags":[]}""",
        "/access/list" to """{"allowed_clients":[],"disallowed_clients":[],"blocked_hosts":["version.bind","id.server","hostname.bind"]}""",
        "/rewrite/list" to """[{"domain":"nas.lan","answer":"192.168.1.20","enabled":true}]""",
        "/rewrite/settings" to """{"enabled":true}""",
        "/dns_info" to """{"upstream_dns":["https://dns10.quad9.net/dns-query"],"bootstrap_dns":["9.9.9.10"],"fallback_dns":[],"upstream_mode":"load_balance","blocking_mode":"default","ratelimit":20,"cache_enabled":true,"cache_size":4194304,"dnssec_enabled":false,"edns_cs_enabled":false,"disable_ipv6":false}""",
        "/querylog/config" to """{"enabled":true,"interval":7776000000,"anonymize_client_ip":false,"ignored":[],"ignored_enabled":false}""",
        "/stats/config" to """{"enabled":true,"interval":86400000,"ignored":[],"ignored_enabled":false}""",
        "/safesearch/status" to """{"enabled":false,"bing":true,"duckduckgo":true,"ecosia":true,"google":true,"pixabay":true,"yandex":true,"youtube":true}""",
        "/filtering/check_host" to """{"reason":"NotFilteredNotFound","rules":[]}"""
    )
    override suspend fun get(path: String, query: Map<String, String>) =
        kotlinx.serialization.json.Json.parseToJsonElement(replies[path] ?: "{}")
    override suspend fun send(method: String, path: String, body: kotlinx.serialization.json.JsonElement?): kotlinx.serialization.json.JsonElement? = null
}
