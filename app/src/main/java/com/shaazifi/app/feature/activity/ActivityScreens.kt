package com.shaazifi.app.feature.activity

import com.shaazifi.app.feature.devices.hue
import com.shaazifi.app.feature.devices.icon
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.shaazifi.app.ui.components.Eyebrow
import com.shaazifi.app.ui.components.GhostButton
import com.shaazifi.app.ui.components.Note
import com.shaazifi.app.ui.components.Pill
import com.shaazifi.app.ui.components.SfDivider
import com.shaazifi.app.ui.components.SfList
import com.shaazifi.app.ui.components.SfListRow
import com.shaazifi.app.ui.theme.PillTone
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
    Column(
        Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        com.shaazifi.app.ui.components.PageHero(com.shaazifi.app.ui.icons.SfIcons.Chart, com.shaazifi.app.ui.theme.SfHue.BLUE, "Activity", "This phone", "Changes made from this app, newest first. Kept only on this phone.")
        if (entries.isEmpty()) Note("No changes made from the app yet.") else SfList {
            entries.forEachIndexed { i, e ->
                if (i > 0) SfDivider()
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val ok = e.result == "Saved"
                    val failed = e.result.startsWith("Failed")
                    com.shaazifi.app.ui.components.HueTile(if (ok) com.shaazifi.app.ui.icons.SfIcons.Check else if (failed) com.shaazifi.app.ui.icons.SfIcons.Ban else com.shaazifi.app.ui.icons.SfIcons.Arrows, if (ok) com.shaazifi.app.ui.theme.SfHue.GREEN else if (failed) com.shaazifi.app.ui.theme.SfHue.RED else com.shaazifi.app.ui.theme.SfHue.ORANGE, size = 34.dp, round = true)
                }
                Column(Modifier.fillMaxWidth().padding(start = 58.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.title, color = c.ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Pill(if (e.result == "Saved") "Saved" else if (e.result.startsWith("Failed")) "Failed" else "Undone",
                            tone = if (e.result == "Saved") PillTone.Ok else if (e.result.startsWith("Failed")) PillTone.Bad else PillTone.Warn)
                    }
                    Text("${e.router} · ${timeText(e.time)}", style = SfText.sub, color = c.muted)
                    e.lines.forEach { Text(it, style = SfText.mono, color = c.muted) }
                    if (e.result != "Saved") Text(e.result, style = SfText.sub, color = c.muted)
                }
            }
        }
        if (entries.isNotEmpty()) GhostButton("Clear the activity log", onClick = { scope.launch { store.clear() } })
        Box(Modifier.padding(bottom = 12.dp))
    }
}

@Composable
fun AllDevicesScreen(store: DeviceCacheStore) {
    val c = SfTheme.colors
    val cache by store.cache.collectAsState(initial = emptyMap())
    var q by remember { mutableStateOf("") }
    val makers = com.shaazifi.app.feature.devices.rememberMakers()
    // Search also matches the maker (for example "Apple").
    val found = DeviceCache.search(cache, "").filter { (_, d) ->
        q.isBlank() || DeviceCache.search(mapOf("x" to CachedRouterDevices("", 0, listOf(d))), q).isNotEmpty() ||
            com.shaazifi.app.feature.devices.Makers.label(makers, d.mac)?.contains(q.trim(), true) == true
    }
    Column(
        Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        com.shaazifi.app.ui.components.PageHero(com.shaazifi.app.ui.icons.SfIcons.Devices, com.shaazifi.app.ui.theme.SfHue.VIOLET, "Devices on all routers", "Every saved router", "The last device list seen on each saved router. Open a router's Devices tab to update it.")
        OutlinedTextField(value = q, onValueChange = { q = it }, label = { Text("Search name, address or hardware address") },
            singleLine = true, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth())
        cache.values.forEach { r -> Text("${r.routerName}: ${r.devices.size} devices · ${timeText(r.time)}", style = SfText.sub, color = c.muted) }
        if (found.isEmpty()) Note(if (cache.isEmpty()) "No device lists yet." else "Nothing matches.") else SfList {
            found.forEachIndexed { i, (router, d) ->
                if (i > 0) SfDivider()
                val dk = com.shaazifi.app.feature.devices.DeviceKind.guess(d.name, com.shaazifi.app.feature.devices.Makers.label(makers, d.mac))
                SfListRow(title = d.name.ifBlank { d.mac }, subtitle = listOfNotNull(router, d.ip, d.mac, com.shaazifi.app.feature.devices.Makers.label(makers, d.mac)).joinToString(" · "),
                    icon = dk.icon(), hue = dk.hue(),
                    trailing = { Pill(if (d.online) "Online" else "Offline", tone = if (d.online) PillTone.Ok else PillTone.Off) })
            }
        }
        Box(Modifier.padding(bottom = 12.dp))
    }
}

@Composable
fun AppearanceSection(store: ThemeStore) {
    val c = SfTheme.colors
    val scope = rememberCoroutineScope()
    val choice by store.choice.collectAsState(initial = ThemeChoice.SYSTEM)
    com.shaazifi.app.ui.components.SectionBar("Appearance", com.shaazifi.app.ui.theme.SfHue.PINK)
    SfList {
        ThemeChoice.entries.forEachIndexed { i, t ->
            if (i > 0) SfDivider()
            SfListRow(title = t.label, onClick = { scope.launch { store.set(t) } }, icon = com.shaazifi.app.ui.icons.SfIcons.Bulb, hue = if (t == choice) com.shaazifi.app.ui.theme.SfHue.PINK else com.shaazifi.app.ui.theme.SfHue.SLATE,
                trailing = { if (t == choice) Text("Selected", style = SfText.sub, color = c.accent) })
        }
    }
}
