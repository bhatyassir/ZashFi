/*
 * Copyright (C) 2026 ZashFi contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.shaazifi.app.feature.adguard.manage

import com.shaazifi.app.core.adguard.AdGuardCaller
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Bulk import/export of AdGuard Home DNS rewrites. Every call still goes through
 * [AdGuardCaller], which enforces the endpoint allowlist in AdGuardEndpoints.
 */

private const val MAX_ANSWER_LENGTH = 255
private const val HOST_LABEL = "[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"
/** A dotted hostname, optionally with a `*.` label prefix; single labels are not DNS rewrites. */
private val DOMAIN_PATTERN = Regex("""(?:\*\.)?$HOST_LABEL(?:\.$HOST_LABEL)+""")

/** One DNS rewrite: a plain hostname (optionally `*.`-wildcard) and its answer. */
data class RewriteRule(val domain: String, val answer: String) {
    /** Null when this rule is safe to send to AdGuard Home. */
    fun validationError(): String? = when {
        !DOMAIN_PATTERN.matches(domain) -> "invalid domain '$domain'"
        answer.isBlank() -> "empty answer for '$domain'"
        answer.length > MAX_ANSWER_LENGTH -> "answer too long for '$domain' (max $MAX_ANSWER_LENGTH)"
        else -> null
    }
}

data class ParseResult(val rules: List<RewriteRule>, val errors: List<String>)

data class BulkChange(val add: List<RewriteRule>, val remove: List<RewriteRule>)

/** Parses `domain answer` lines. Blank lines and `#` comments are ignored; bad lines are reported, not thrown. */
fun parse(lines: String): ParseResult {
    val rules = mutableListOf<RewriteRule>()
    val errors = mutableListOf<String>()
    lines.lineSequence().forEachIndexed { index, raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) {
            // A comment or a blank line: nothing to import.
        } else {
            val split = line.indexOfFirst { it.isWhitespace() }
            if (split < 0) {
                errors += "Line ${index + 1}: missing answer for '$line'"
            } else {
                val domain = line.substring(0, split).trim()
                val answer = line.substring(split).trim()
                val rule = RewriteRule(domain, answer)
                val error = rule.validationError()
                if (error == null) rules += rule else errors += "Line ${index + 1}: $error"
            }
        }
    }
    return ParseResult(rules, errors)
}

/** One `domain answer` per line, in the given order. */
fun serialise(rules: List<RewriteRule>): String =
    rules.joinToString("\n") { "${it.domain} ${it.answer}" }

/** Rules whose full (domain, answer) pair differs between the two lists. */
fun diff(current: List<RewriteRule>, desired: List<RewriteRule>): BulkChange {
    val currentList = current.distinct()
    val desiredList = desired.distinct()
    val currentSet = currentList.toSet()
    val desiredSet = desiredList.toSet()
    return BulkChange(
        add = desiredList.filter { it !in currentSet },
        remove = currentList.filter { it !in desiredSet }
    )
}

private fun JsonElement?.stringValue(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

private fun ruleBody(rule: RewriteRule) = buildJsonObject {
    put("domain", rule.domain)
    put("answer", rule.answer)
}

/**
 * Applies removals before additions so a changed answer cannot clash with the old one.
 * Stops at the first failed call and returns how many calls succeeded. Cancellation is
 * never swallowed.
 */
suspend fun AdGuardCaller.applyBulk(change: BulkChange): Int {
    var applied = 0
    try {
        for (rule in change.remove) {
            send("POST", "/rewrite/delete", ruleBody(rule))
            applied++
        }
        for (rule in change.add) {
            send("POST", "/rewrite/add", ruleBody(rule))
            applied++
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The remaining changes are left untouched; the caller learns what was applied.
    }
    return applied
}

/** Exports the current rewrites as importable text, skipping malformed entries. */
suspend fun AdGuardCaller.exportRewrites(): String {
    val list = get("/rewrite/list") as? JsonArray ?: return ""
    val rules = mutableListOf<RewriteRule>()
    for (element in list) {
        val domain = (element as? JsonObject)?.get("domain").stringValue()
        val answer = (element as? JsonObject)?.get("answer").stringValue()
        if (domain != null && answer != null) {
            val rule = RewriteRule(domain, answer)
            if (rule.validationError() == null) rules += rule
        }
    }
    return serialise(rules)
}
