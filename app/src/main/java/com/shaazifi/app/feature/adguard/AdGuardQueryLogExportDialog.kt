package com.shaazifi.app.feature.adguard

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.shaazifi.app.feature.adguard.manage.AgQueryFilter
import com.shaazifi.app.ui.theme.SfTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class AdGuardLogFileProvider : FileProvider()

private enum class ExportScope { ALL, DEVICE, FILTERS }

@Composable
internal fun AdGuardQueryLogExportDialog(
    reader: AdGuardQueryLogReader,
    currentFilter: QueryLogFilter,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val canFilter = currentFilter.search.isNotBlank() || currentFilter.result != AgQueryFilter.ALL
    val device = currentFilter.device
    var scope by remember {
        mutableStateOf(when {
            canFilter -> ExportScope.FILTERS
            device != null -> ExportScope.DEVICE
            else -> ExportScope.ALL
        })
    }
    var format by remember { mutableStateOf(QueryLogExportFormat.CSV) }
    var exporting by remember { mutableStateOf(false) }
    var count by remember { mutableLongStateOf(0L) }
    var completed by remember { mutableStateOf<QueryLogExport?>(null) }
    var shareStarted by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { if (!shareStarted) completed?.file?.delete() }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Export DNS logs") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when {
                    exporting -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Preparing export · $count queries written")
                    }
                    completed != null -> {
                        val total = completed?.count ?: 0L
                        Text(if (total == 1L) "1 query ready to share." else "$total queries ready to share.")
                    }
                    else -> {
                        Column(Modifier.selectableGroup()) {
                            Text("Scope", style = MaterialTheme.typography.titleSmall)
                            ExportChoice("All retained queries", scope == ExportScope.ALL) { scope = ExportScope.ALL }
                            if (device != null) {
                                ExportChoice("Selected device · ${device.label}", scope == ExportScope.DEVICE) {
                                    scope = ExportScope.DEVICE
                                }
                            }
                            if (canFilter) {
                                ExportChoice("Current filters", scope == ExportScope.FILTERS) {
                                    scope = ExportScope.FILTERS
                                }
                            }
                        }
                        Column(Modifier.selectableGroup()) {
                            Text("File format", style = MaterialTheme.typography.titleSmall)
                            QueryLogExportFormat.entries.forEach { option ->
                                ExportChoice(option.label, format == option) { format = option }
                            }
                        }
                    }
                }
                error?.let { Text(it, color = SfTheme.colors.bad) }
            }
        },
        confirmButton = {
            if (!exporting) {
                val export = completed
                if (export == null) {
                    TextButton(onClick = {
                        if (exporting || completed != null) return@TextButton
                        val filter = when (scope) {
                            ExportScope.ALL -> QueryLogFilter(null, "", AgQueryFilter.ALL)
                            ExportScope.DEVICE -> QueryLogFilter(device, "", AgQueryFilter.ALL)
                            ExportScope.FILTERS -> currentFilter
                        }
                        exporting = true
                        error = null
                        count = 0L
                        coroutineScope.launch {
                            try {
                                completed = reader.export(File(context.cacheDir, "adguard-exports"), filter, format) {
                                    withContext(Dispatchers.Main.immediate) { count = it }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = when (failure) {
                                    is QueryLogExportException -> failure.message
                                    is IOException -> "Couldn't write the export file."
                                    else -> failure.queryLogMessage()
                                }
                            } finally {
                                exporting = false
                            }
                        }
                    }) { Text("Create export") }
                } else {
                    TextButton(onClick = {
                        try {
                            val uri = FileProvider.getUriForFile(
                                context, "${context.packageName}.adguard-exports", export.file
                            )
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = format.mimeType
                                putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = ClipData.newUri(context.contentResolver, export.file.name, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, "Share DNS logs"))
                            shareStarted = true
                            onClose()
                        } catch (_: RuntimeException) {
                            error = "Couldn't open sharing for this file."
                        }
                    }) { Text("Share file") }
                }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(if (completed == null) "Cancel" else "Close") } }
    )
}

@Composable
private fun ExportChoice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
