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
package com.shaazifi.app.feature.guestqr

import com.shaazifi.app.feature.router.ChangeLine
import com.shaazifi.app.feature.router.ChangePlan
import com.shaazifi.app.feature.router.PlanResult
import com.shaazifi.app.feature.router.UciOp
import java.util.Locale

/**
 * Time-limited guest access. A pass is only a pointer to an existing guest `wifi-iface`: it holds
 * no password and is safe to keep as a short line in local preferences or in a link/QR payload.
 *
 * Expiry is advisory — the router itself has no timer. When the time is up the app offers a
 * narrowly scoped plan that disables that one guest section ([buildDisablePlan]).
 */
data class GuestPass(
    val sectionId: String,
    val ssid: String,
    val expiresAtMs: Long,
    val createdAtMs: Long
) {
    /** True once the clock has reached the expiry instant (no grace period). */
    fun isExpired(nowMs: Long): Boolean = nowMs >= expiresAtMs

    /** Whole milliseconds left, never negative. */
    fun remainingMs(nowMs: Long): Long = (expiresAtMs - nowMs).coerceAtLeast(0L)
}

/** Fixed choices only: no free-form amount, so a value can never smuggle a command or string. */
private val DURATIONS_MS: Map<String, Long> = mapOf(
    "30m" to 30L * 60_000L,
    "1h" to 60L * 60_000L,
    "2h" to 2L * 60 * 60_000L,
    "6h" to 6L * 60 * 60_000L,
    "12h" to 12L * 60 * 60_000L,
    "1d" to 24L * 60 * 60_000L,
    "7d" to 7L * 24 * 60 * 60_000L
)

/** The only duration strings the guest form may produce, case-insensitive. Anything else is null. */
fun parseDuration(raw: String): Long? = DURATIONS_MS[raw.trim().lowercase(Locale.ROOT)]

private val SECTION_NAME = Regex("^[A-Za-z0-9_]{1,32}$")

/** Exactly the one allowlisted write needed to end guest access: no firewall, no main Wi-Fi. */
fun buildDisablePlan(sectionId: String, ssid: String): PlanResult {
    val section = sectionId.trim()
    if (!SECTION_NAME.matches(section)) return PlanResult.Invalid("That is not a valid guest network section.")
    val name = ssid.trim().ifBlank { "Guest network" }
    return PlanResult.Ready(ChangePlan(
        title = "End guest access for \"$name\" now?",
        lines = listOf(
            ChangeLine(name, "On", "Off"),
            ChangeLine("Guests", "Can join", "Disconnected")
        ),
        ops = listOf(UciOp.Set("wireless", section, mapOf("disabled" to "1"))),
        note = "The router restarts Wi-Fi on that radio, so Wi-Fi drops for a few seconds and every guest is disconnected. Guests cannot rejoin until the guest network is turned back on."
    ))
}

/** Protect the separator and the escape marker before splitting on '|'. */
private fun escapeField(s: String): String = s.replace("%", "%25").replace("|", "%7C")

private fun unescapeField(s: String): String = s.replace("%7C", "|").replace("%25", "%")

private fun positiveLong(s: String): Long? =
    s.takeIf { it.isNotEmpty() && it.all { ch -> ch in '0'..'9' } }
        ?.toLongOrNull()
        ?.takeIf { it > 0L }

/**
 * Compact, password-free encoding: `sectionId|ssid|expiresAtMs|createdAtMs`.
 * Throws [IllegalArgumentException] rather than writing a pass that [decode] would reject.
 */
fun encode(pass: GuestPass): String {
    require(SECTION_NAME.matches(pass.sectionId)) { "Invalid guest section name." }
    require(pass.ssid.isNotBlank()) { "The guest network needs a name." }
    require(pass.createdAtMs > 0L && pass.expiresAtMs > pass.createdAtMs) { "Invalid guest access times." }
    return listOf(
        pass.sectionId,
        escapeField(pass.ssid),
        pass.expiresAtMs.toString(),
        pass.createdAtMs.toString()
    ).joinToString("|")
}

/** Parses [encode]'s format, returning null for every corrupt, truncated or tampered value. */
fun decode(value: String?): GuestPass? {
    if (value.isNullOrEmpty()) return null
    val parts = value.split('|')
    if (parts.size != 4) return null
    val (section, rawSsid, rawExpires, rawCreated) = parts
    if (!SECTION_NAME.matches(section)) return null
    val ssid = unescapeField(rawSsid)
    if (ssid.isBlank()) return null
    val expiresAtMs = positiveLong(rawExpires) ?: return null
    val createdAtMs = positiveLong(rawCreated) ?: return null
    if (expiresAtMs <= createdAtMs) return null
    return GuestPass(section, ssid, expiresAtMs, createdAtMs)
}

/** A short status line for the guest QR card. Whole minutes, floor-rounded. */
fun summarise(pass: GuestPass?, nowMs: Long): String {
    if (pass == null) return "No guest pass is active"
    if (pass.isExpired(nowMs)) return "Guest access has ended"
    val minutes = pass.remainingMs(nowMs) / 60_000L
    val hours = minutes / 60L
    val mins = minutes % 60L
    return when {
        hours > 0L && mins > 0L -> "Guest access ends in $hours h $mins min"
        hours > 0L -> "Guest access ends in $hours h"
        else -> "Guest access ends in $mins min"
    }
}
