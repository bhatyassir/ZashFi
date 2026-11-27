package com.shaazifi.app.feature.router

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.shaazifi.app.ui.components.Pill
import com.shaazifi.app.ui.theme.PillTone
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme
import kotlinx.coroutines.launch

/**
 * Shows a [ChangeController]'s plan for confirmation, the progress, and the result.
 * Nothing is sent to the router before the user taps "Apply".
 */
@Composable
fun ChangeHost(controller: ChangeController, demo: Boolean = false) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val c = SfTheme.colors
    when (val s = state) {
        ChangeUiState.Idle -> Unit
        is ChangeUiState.Confirming -> {
            var applying by remember(s.plan) { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { if (!applying) controller.cancel() },
                title = { Text(s.plan.title) },
                text = {
                    Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        s.plan.lines.forEach { line ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(line.label, fontWeight = FontWeight.SemiBold, color = c.ink, modifier = Modifier.weight(1f))
                                if (line.before.isNotBlank()) {
                                    Text(line.before, style = SfText.mono, color = c.muted)
                                    Text("→", color = c.muted)
                                }
                                Text(line.after, style = SfText.mono, color = c.ink)
                            }
                        }
                        s.plan.note?.let { Text(it, style = SfText.sub, color = c.muted) }
                        if (!demo && s.plan.checkInternet) {
                            Text(
                                "The app also checks that the internet comes back. If it doesn't, the old settings return by themselves within 2 minutes.",
                                style = SfText.sub,
                                color = c.muted
                            )
                        }
                        if (!demo && s.plan.command == null && s.plan.ubusCall == null) {
                            Text(
                                "Safety: if the app cannot reach the router after this change, the router puts the old setting back by itself within 2 minutes.",
                                style = SfText.sub,
                                color = c.muted
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(enabled = !applying, onClick = {
                        applying = true
                        scope.launch { controller.confirm() }
                    }) { Text("Apply") }
                },
                dismissButton = { TextButton(enabled = !applying, onClick = { controller.cancel() }) { Text("Cancel") } }
            )
        }
        is ChangeUiState.Running -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("Applying...") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = c.accent)
                    Text(when {
                        demo -> "Updating demo data."
                        s.plan.ubusCall != null || s.plan.command != null -> "Sending the action to the router."
                        else -> "Making the change, then checking the app can still reach the router before keeping it."
                    }, style = SfText.sub, color = c.muted)
                }
            },
            confirmButton = {}
        )
        is ChangeUiState.Done -> {
            val (title, body, pill) = when (val o = s.outcome) {
                ChangeOutcome.Kept -> Triple("Done", if (demo) "The change was applied to demo data." else "The router accepted the change.", ViewPill("Applied", PillTone.Ok))
                ChangeOutcome.InternetNotBack -> Triple(
                    "Internet did not come back",
                    "The router answered, but the internet connection did not come back up, so the app did not keep the change. The router will put the old settings back by itself within 2 minutes.",
                    ViewPill("Undoing", PillTone.Warn)
                )
                ChangeOutcome.LostConnection -> Triple(
                    "Lost the router",
                    "The app could not reach the router after the change, so it did not keep it. The router will put the old setting back by itself within 2 minutes.",
                    ViewPill("Undoing", PillTone.Warn)
                )
                is ChangeOutcome.Failed -> Triple("Couldn’t complete the change", o.message, ViewPill("Check status", PillTone.Bad))
            }
            AlertDialog(
                onDismissRequest = { controller.dismiss() },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title, modifier = Modifier.weight(1f))
                        Pill(pill.text, tone = pill.tone)
                    }
                },
                text = { Text(body, style = SfText.sub, color = c.muted) },
                confirmButton = { TextButton(onClick = { controller.dismiss() }) { Text("OK") } }
            )
        }
    }
}
