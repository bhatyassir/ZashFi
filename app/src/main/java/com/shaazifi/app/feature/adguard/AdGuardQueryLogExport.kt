package com.shaazifi.app.feature.adguard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

internal enum class QueryLogExportFormat(val label: String, val extension: String, val mimeType: String) {
    CSV("CSV", "csv", "text/csv"),
    TEXT("Plain text", "txt", "text/plain")
}

internal data class QueryLogExport(val file: File, val count: Long)

internal class QueryLogExportException(message: String) : Exception(message)

private const val EXPORT_PREFIX = "zashfi-dns-"

internal suspend fun AdGuardQueryLogReader.export(
    directory: File,
    filter: QueryLogFilter,
    format: QueryLogExportFormat,
    onProgress: suspend (Long) -> Unit
): QueryLogExport {
    var partial: File? = null
    try {
        return withContext(Dispatchers.IO) {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Export directory unavailable")
            val now = Instant.now()
            val expiry = now.toEpochMilli() - TimeUnit.DAYS.toMillis(1)
            directory.listFiles()?.forEach { previous ->
                if (previous.name.startsWith(EXPORT_PREFIX) && previous.isFile && previous.lastModified() < expiry) {
                    previous.delete()
                }
            }
            val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
                .withZone(ZoneId.systemDefault()).format(now)
            val file = File.createTempFile("$EXPORT_PREFIX$stamp-", ".${format.extension}", directory)
                .also { partial = it }
            val context = currentCoroutineContext()
            var count = 0L
            file.bufferedWriter(Charsets.UTF_8).use { writer ->
                if (format == QueryLogExportFormat.CSV) writer.writeCsvRow(headers)
                else writer.write("ZashFi DNS query log\n\n")
                forEachRetainedPage(filter) { queries ->
                    for (query in queries) {
                        context.ensureActive()
                        writer.writeQuery(query, format)
                    }
                    count += queries.size
                    onProgress(count)
                }
            }
            if (count == 0L) throw QueryLogExportException("No retained queries match this export.")
            QueryLogExport(file, count)
        }
    } catch (failure: Exception) {
        withContext(NonCancellable + Dispatchers.IO) { partial?.delete() }
        throw failure
    }
}

private val headers = listOf(
    "Timestamp", "Domain", "Record type", "Device", "Client address", "Client ID",
    "Outcome", "Filtering reason", "DNS result", "Response time (ms)", "Upstream resolver",
    "Cached", "Matched rules", "DNS answers"
)

private fun Writer.writeQuery(query: AdGuardQueryLogItem, format: QueryLogExportFormat) {
    val fields = listOf(
        query.time, query.unicodeName ?: query.questionName, query.questionType, query.clientName.orEmpty(),
        query.client, query.clientId.orEmpty(), query.outcomeLabel, query.reason, query.responseStatus.orEmpty(),
        query.elapsedMs, query.upstream.orEmpty(), query.cached?.toString().orEmpty(), query.rules.joinToString("\n"),
        query.answers.joinToString("\n") { answer ->
            val ttl = answer.ttlSeconds?.let { " (TTL ${it}s)" }.orEmpty()
            "${answer.type} ${answer.value}$ttl"
        }
    )
    when (format) {
        QueryLogExportFormat.CSV -> writeCsvRow(fields)
        QueryLogExportFormat.TEXT -> {
            headers.zip(fields).forEach { (label, value) ->
                if (value.isNotBlank()) {
                    val lines = value.lineSequence().joinToString("\n    ")
                    write("$label: $lines\n")
                }
            }
            write("\n")
        }
    }
}

private fun Writer.writeCsvRow(fields: List<String>) {
    write(fields.joinToString(",") { value ->
        // Domains, labels and rule text are data. Prevent spreadsheet formula interpretation.
        val first = value.trimStart { it.isWhitespace() || it == '\uFEFF' }.firstOrNull()
        val escaped = value.replace("\"", "\"\"")
        val prefix = if (first != null && first in "=+-@＝＋－＠") "'" else ""
        "\"$prefix$escaped\""
    })
    write("\r\n")
}
