package com.shaazifi.app.feature.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.shaazifi.app.feature.devices.DeviceKind
import com.shaazifi.app.feature.devices.Makers
import com.shaazifi.app.feature.devices.hue
import com.shaazifi.app.feature.devices.icon
import com.shaazifi.app.feature.devices.rememberMakers
import com.shaazifi.app.ui.components.GhostButton
import com.shaazifi.app.ui.components.HueTile
import com.shaazifi.app.ui.components.Note
import com.shaazifi.app.ui.components.PageHero
import com.shaazifi.app.ui.components.Pill
import com.shaazifi.app.ui.components.SectionBar
import com.shaazifi.app.ui.components.SfCard
import com.shaazifi.app.ui.components.SfDivider
import com.shaazifi.app.ui.components.SfList
import com.shaazifi.app.ui.components.SfListRow
import com.shaazifi.app.ui.icons.SfIcons
import com.shaazifi.app.ui.theme.PillTone
import com.shaazifi.app.ui.theme.SfHue
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme
import com.shaazifi.app.ui.theme.ThemeChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Remembers the theme choice (phase 5). */
class ThemeStore(private val dataStore: DataStore<Preferences>) {
    private val key = stringPreferencesKey("theme_choice")
    val choice: Flow<ThemeChoice> = dataStore.data.map { ThemeChoice.parse(it[key]) }
    suspend fun set(c: ThemeChoice) { dataStore.edit { it[key] = c.name } }
}

private fun timeText(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

@Composable
fun ActivityScreen(store: ActivityStore) {
    val c = SfTheme.colors
    val scope = rememberCoroutineScope()
    val entries by store.entries.collectAsState(initial = emptyList())
    var confirmClear by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "hero") {
            PageHero(
                SfIcons.Chart,
                SfHue.BLUE,
                "Activity",
                "This phone",
                "Changes made from this app, newest first. Kept only on this phone."
            )
        }
        if (entries.isEmpty()) {
            item(key = "empty") {
                Note("No changes made from the app yet.")
            }
        } else {
            itemsIndexed(entries, key = { index, e -> "${e.time}:${e.router}:${e.title}:$index" }) { _, e ->
                val ok = e.result == "Saved"
                val failed = e.result.startsWith("Failed")
                val icon = when {
                    ok -> SfIcons.Check
                    failed -> SfIcons.Ban
                    else -> SfIcons.Arrows
                }
                val hue = when {
                    ok -> SfHue.GREEN
                    failed -> SfHue.RED
                    else -> SfHue.ORANGE
                }
                val tone = when {
                    ok -> PillTone.Ok
                    failed -> PillTone.Bad
                    else -> PillTone.Warn
                }
                val badge = when {
                    ok -> "Saved"
                    failed -> "Failed"
                    else -> "Undone"
                }
                SfCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        HueTile(icon, hue, size = 34.dp, round = true)
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(e.title, color = c.ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Pill(badge, tone = tone)
                            }
                            Text("${e.router} · ${timeText(e.time)}", style = SfText.sub, color = c.muted)
                            e.lines.forEach { Text(it, style = SfText.mono, color = c.muted) }
                            if (!ok) Text(e.result, style = SfText.sub, color = c.muted)
                        }
                    }
                }
            }
            item(key = "clear") {
                GhostButton("Clear the activity log", onClick = { confirmClear = true })
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear activity log?") },
            text = { Text("This deletes the record of app changes saved on this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { store.clear() }
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun AllDevicesScreen(store: DeviceCacheStore) {
    val c = SfTheme.colors
    val cache by store.cache.collectAsState(initial = emptyMap())
    var q by remember { mutableStateOf("") }
    val makers = rememberMakers()
    val found = remember(cache, q, makers) {
        DeviceCache.search(cache, q) { Makers.label(makers, it) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "hero") {
            PageHero(
                SfIcons.Devices,
                SfHue.VIOLET,
                "Devices on all routers",
                "Every saved router",
                "The last device list seen on each saved router. Open a router's Devices tab to update it."
            )
        }
        item(key = "search") {
            OutlinedTextField(
                value = q,
                onValueChange = { q = it },
                label = { Text("Search name, maker, IP or MAC") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )
        }
        items(cache.entries.toList(), key = { "summary:${it.key}" }) { (_, r) ->
            Text("${r.routerName}: ${r.devices.size} devices · ${timeText(r.time)}", style = SfText.sub, color = c.muted)
        }
        if (found.isEmpty()) {
            item(key = "empty") {
                Note(if (cache.isEmpty()) "No device lists yet." else "Nothing matches.")
            }
        } else {
            itemsIndexed(found, key = { index, (router, d) -> "$router:${d.mac}:$index" }) { _, (router, d) ->
                val maker = Makers.label(makers, d.mac)
                val randomized = Makers.isRandomized(d.mac)
                val dk = DeviceKind.guess(d.name, maker)
                SfCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                    SfListRow(
                        title = d.name.ifBlank { d.mac },
                        subtitle = listOfNotNull(router, d.ip.takeIf { it.isNotBlank() }, d.mac, maker).joinToString(" · "),
                        icon = dk.icon(),
                        hue = dk.hue(),
                        trailing = {
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Pill(if (d.online) "Online" else "Offline", tone = if (d.online) PillTone.Ok else PillTone.Off)
                                if (randomized) Pill("Private MAC", PillTone.Off)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun AppearanceSection(store: ThemeStore) {
    val c = SfTheme.colors
    val scope = rememberCoroutineScope()
    val choice by store.choice.collectAsState(initial = ThemeChoice.SYSTEM)
    SectionBar("Appearance", SfHue.PINK)
    SfList {
        ThemeChoice.entries.forEachIndexed { i, t ->
            if (i > 0) SfDivider()
            SfListRow(title = t.label, onClick = { scope.launch { store.set(t) } }, icon = SfIcons.Bulb, hue = if (t == choice) SfHue.PINK else SfHue.SLATE,
                trailing = { if (t == choice) Text("Selected", style = SfText.sub, color = c.accent) })
        }
    }
}
