package com.shaazifi.app.feature.adguard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shaazifi.app.feature.adguard.manage.AgChanges
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme

@Composable
internal fun AdGuardQueryInspector(
    query: AdGuardQueryLogItem,
    onClose: () -> Unit,
    onRuleChange: ((AdGuardQueryLogItem) -> Unit)?
) {
    val canChangeRule = onRuleChange != null && AgChanges.domainOrNull(query.questionName) != null
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("DNS query details") },
        text = {
            SelectionContainer {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(query.unicodeName ?: query.questionName, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, color = SfTheme.colors.ink)
                    QueryDetail("Outcome", query.outcomeLabel)
                    QueryDetail("Record type", query.questionType)
                    QueryDetail("Time reported by AdGuard", query.time)
                    QueryDetail("Device", query.clientName)
                    QueryDetail("Client ID", query.clientId)
                    QueryDetail("Address reported by AdGuard", query.client)
                    QueryDetail("DNS result", query.responseStatus)
                    QueryDetail("DNS response time", query.elapsedMs.takeIf { it.isNotBlank() }?.let { "$it ms" })
                    QueryDetail("Upstream resolver", query.upstream)
                    if (query.cached == true) QueryDetail("Answer source", "Cache")
                    QueryDetail("Filtering reason", query.reason)
                    if (query.rules.isNotEmpty()) {
                        QueryDetail("Matched rules", query.rules.joinToString("\n"))
                    }
                    if (query.answers.isNotEmpty()) {
                        Text("DNS answers", fontWeight = FontWeight.SemiBold, color = SfTheme.colors.ink)
                        query.answers.forEach { answer ->
                            val ttl = answer.ttlSeconds?.let { " · TTL ${it}s" }.orEmpty()
                            Text("${answer.type}  ${answer.value}$ttl", style = SfText.mono, color = SfTheme.colors.ink)
                        }
                    }
                }
            }
        },
        dismissButton = {
            if (canChangeRule) {
                TextButton(onClick = { onRuleChange(query) }) {
                    Text(if (query.isBlocked) "Allow this site" else "Block this site")
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

@Composable
private fun QueryDetail(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = SfText.sub, color = SfTheme.colors.muted)
        Text(value, style = SfText.mono, color = SfTheme.colors.ink)
    }
}
