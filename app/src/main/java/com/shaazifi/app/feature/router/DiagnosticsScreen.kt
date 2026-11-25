package com.shaazifi.app.feature.router

import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shaazifi.app.ui.components.GhostButton
import com.shaazifi.app.ui.components.Note
import com.shaazifi.app.ui.components.Pill
import com.shaazifi.app.ui.components.SfCard
import com.shaazifi.app.ui.theme.PillTone
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme
import kotlinx.coroutines.launch

/** What a diagnostics run returned. */
sealed class DiagResult {
    data class Output(val text: String, val ok: Boolean) : DiagResult()
    data object NotPermitted : DiagResult()
    data class Failed(val message: String) : DiagResult()
}

/** Runs a diagnostic on the router with the read-only allowlist (or sample output in demo). */
suspend fun runDiagnostic(reader: RouterReader?, tool: Diagnostics.Tool, host: String): DiagResult {
    if (reader == null) return DiagResult.Output(Diagnostics.demoOutput(tool, host), ok = true)
    val commands = Diagnostics.commandsFor(tool, host)
        ?: return DiagResult.Failed("Type a host name like openwrt.org or an address like 1.1.1.1.")
    return try {
        val r = reader.execFirst(*commands.toTypedArray()) ?: return DiagResult.NotPermitted
        DiagResult.Output((r.stdout + if (r.stderr.isNotBlank()) "\n" + r.stderr else "").trim().ifBlank { "(no output)" }, ok = r.code == 0)
    } catch (e: FeatureLoadException) {
        DiagResult.Failed("Can't reach the router. Check that this phone is on its Wi-Fi or VPN.")
    } catch (e: Exception) {
        DiagResult.Failed("Could not run it (${e::class.simpleName}).")
    }
}

/** M10b: ping, trace route and DNS lookup from the router itself. Read-only: nothing is changed. */
@Composable
fun DiagnosticsScreen(run: suspend (Diagnostics.Tool, String) -> DiagResult) {
    val c = SfTheme.colors
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("openwrt.org") }
    var busy by remember { mutableStateOf<Diagnostics.Tool?>(null) }
    var result by remember { mutableStateOf<Pair<Diagnostics.Tool, DiagResult>?>(null) }
    val valid = Diagnostics.isSafeHost(host.trim())

    Column(
        Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        com.shaazifi.app.ui.components.PageHero(com.shaazifi.app.ui.icons.SfIcons.Tools, com.shaazifi.app.ui.theme.SfHue.ORANGE, "Diagnostics", "Tools", "Tests run on the router itself, so they show what the router sees.")
        SfCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 10.dp) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Host name or address") },
                singleLine = true,
                isError = host.isNotBlank() && !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )
            if (host.isNotBlank() && !valid) Text("Letters, numbers, dots and hyphens only.", style = SfText.sub, color = c.bad)
            // Owner feedback 2026-11-18: one coloured tile per test.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Diagnostics.Tool.entries.forEach { t ->
                    val (icon, hue) = when (t) {
                        Diagnostics.Tool.PING -> com.shaazifi.app.ui.icons.SfIcons.Bolt to com.shaazifi.app.ui.theme.SfHue.GREEN
                        Diagnostics.Tool.TRACEROUTE -> com.shaazifi.app.ui.icons.SfIcons.Link to com.shaazifi.app.ui.theme.SfHue.BLUE
                        Diagnostics.Tool.LOOKUP -> com.shaazifi.app.ui.icons.SfIcons.Globe to com.shaazifi.app.ui.theme.SfHue.VIOLET
                    }
                    val (fg, bg) = hue.colors()
                    val enabled = busy == null && valid
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                            .background(if (enabled) bg else c.sunk)
                            .clickable(enabled = enabled) {
                                busy = t
                                scope.launch {
                                    result = t to run(t, host.trim())
                                    busy = null
                                }
                            }
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(icon, contentDescription = null, tint = if (enabled) fg else c.muted, modifier = Modifier.size(24.dp))
                        Text(if (busy == t) "Running..." else t.label, color = if (enabled) fg else c.muted, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
        if (busy == Diagnostics.Tool.TRACEROUTE) Note("Trace route can take up to 20 seconds.")
        result?.let { (tool, r) ->
            SfCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 8.dp) {
                Row {
                    Text(tool.label, style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
                    when (r) {
                        is DiagResult.Output -> Pill(if (r.ok) "Done" else "Problem", tone = if (r.ok) PillTone.Ok else PillTone.Warn)
                        DiagResult.NotPermitted -> Pill("Not permitted", tone = PillTone.Warn)
                        is DiagResult.Failed -> Pill("Failed", tone = PillTone.Bad)
                    }
                }
                when (r) {
                    is DiagResult.Output -> Text(r.text, style = SfText.mono, color = c.ink, modifier = Modifier.horizontalScroll(rememberScrollState()))
                    DiagResult.NotPermitted -> Text(
                        "The router's access rules do not allow this test, or the tool is missing. Installing the app's router permission file (docs/router) allows it.",
                        style = SfText.sub, color = c.muted
                    )
                    is DiagResult.Failed -> Text(r.message, style = SfText.sub, color = c.muted)
                }
            }
        }
        Box(Modifier.padding(bottom = 12.dp))
    }
}
