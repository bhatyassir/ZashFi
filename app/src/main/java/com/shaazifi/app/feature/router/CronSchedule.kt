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
package com.shaazifi.app.feature.router

import java.util.Locale

/**
 * A nightly LED schedule, expressed only as four small integers. The app never accepts cron
 * text from the user: [CronSchedule.render] is the only way to turn this into a crontab entry,
 * and it can emit only the two fixed `/usr/bin/led-nightmode night|day` commands.
 */
data class LedSchedule(
    val offHour: Int,
    val offMinute: Int,
    val onHour: Int,
    val onMinute: Int
) {
    init {
        require(offHour in 0..23) { "The LEDs-off hour must be 0 to 23." }
        require(offMinute in 0..59) { "The LEDs-off minute must be 0 to 59." }
        require(onHour in 0..23) { "The LEDs-on hour must be 0 to 23." }
        require(onMinute in 0..59) { "The LEDs-on minute must be 0 to 59." }
    }
}

/**
 * Narrow scheduler for the owner's `/usr/bin/led-nightmode` script (the same script used by
 * the one-shot [Phase4.ledMode] action). No free-form cron string can enter here: every
 * generated line is built from validated integers and one of two literal commands.
 */
object CronSchedule {

    private const val BIN = "/usr/bin/led-nightmode"
    private const val CRONTAB_PATH = "/etc/crontabs/root"
    private const val MAX_CRON_CHARS = 4096

    private val COMMANDS = setOf("night", "day")
    private val FIELDS = Regex("\\s+")
    private val TWO_DIGITS = Regex("\\d{1,2}")

    /** `"mm HH * * * /usr/bin/led-nightmode <command>"` — [command] can only be `night` or `day`. */
    fun cronLine(atHour: Int, atMinute: Int, command: String): String {
        require(atHour in 0..23) { "Hour must be 0 to 23." }
        require(atMinute in 0..59) { "Minute must be 0 to 59." }
        require(command in COMMANDS) { "Only the night and day LED commands are supported." }
        return String.format(Locale.ROOT, "%02d %02d * * * %s %s", atMinute, atHour, BIN, command)
    }

    /** Exactly two newline-terminated lines: night (LEDs off) first, then day (LEDs on). */
    fun render(schedule: LedSchedule): String = buildString {
        append(cronLine(schedule.offHour, schedule.offMinute, "night")).append('\n')
        append(cronLine(schedule.onHour, schedule.onMinute, "day")).append('\n')
    }

    /**
     * Parses back only our own two-line format. A trailing blank line (from `split('\n')`) is
     * tolerated; anything else — extra entries, extra fields, a different command path or
     * argument, out-of-range values, embedded newlines — is rejected with `null`.
     */
    fun parse(lines: List<String>): LedSchedule? {
        val rows = lines.dropLastWhile { it.isBlank() }
        if (rows.size != 2) return null
        val night = parseRow(rows[0], "night") ?: return null
        val day = parseRow(rows[1], "day") ?: return null
        return LedSchedule(night.first, night.second, day.first, day.second)
    }

    private fun parseRow(line: String, expectedCommand: String): Pair<Int, Int>? {
        if (line.contains('\n') || line.contains('\r')) return null
        val fields = line.trim().split(FIELDS)
        if (fields.size != 7) return null
        if (fields[2] != "*" || fields[3] != "*" || fields[4] != "*") return null
        if (fields[5] != BIN || fields[6] != expectedCommand) return null
        if (!TWO_DIGITS.matches(fields[0]) || !TWO_DIGITS.matches(fields[1])) return null
        val minute = fields[0].toIntOrNull() ?: return null
        val hour = fields[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }

    /**
     * Drops every existing `led-nightmode` line and appends exactly the two freshly rendered
     * lines. All other crontab lines (including comments and environment assignments) are
     * kept byte-for-byte, so no other job is invented and no other command is generated.
     * Throws if [currentCron] is longer than 4096 characters.
     */
    fun merge(currentCron: String?, schedule: LedSchedule): String {
        if (currentCron != null && currentCron.length > MAX_CRON_CHARS) {
            throw IllegalArgumentException("The router's crontab is too large to rewrite from the app.")
        }
        val kept = currentCron?.lines().orEmpty().filterNot(::isLedLine).dropLastWhile { it.isBlank() }
        return buildString {
            kept.forEach { append(it).append('\n') }
            append(render(schedule))
        }
    }

    private fun isLedLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return false
        val fields = trimmed.split(FIELDS)
        // Standard cron ("mm HH dom mon dow command") and @reboot-style entries.
        return (fields.size >= 6 && fields[5] == BIN) ||
            (fields.size >= 2 && fields[0].startsWith("@") && fields[1] == BIN)
    }

    /** A confirm-and-run plan: one `file write` of the merged crontab, no UCI ops and no undo. */
    fun plan(schedule: LedSchedule, currentCron: String? = null): PlanResult {
        if ((currentCron?.length ?: 0) > MAX_CRON_CHARS) {
            return PlanResult.Invalid("The router's crontab is too large to rewrite from the app.")
        }
        val data = merge(currentCron, schedule)
        val off = String.format(Locale.ROOT, "%02d:%02d", schedule.offHour, schedule.offMinute)
        val on = String.format(Locale.ROOT, "%02d:%02d", schedule.onHour, schedule.onMinute)
        return PlanResult.Ready(
            ChangePlan(
                title = "Night LEDs: off at $off, on at $on?",
                lines = listOf(
                    ChangeLine("Night LEDs (off)", "Current schedule", "$off every day"),
                    ChangeLine("Night LEDs (on)", "Current schedule", "$on every day")
                ),
                ops = emptyList(),
                note = "Replaces only the two $BIN lines in $CRONTAB_PATH. Other cron jobs are " +
                    "preserved only if they are in the crontab the app read; no other command is " +
                    "generated. This runs once with no automatic undo.",
                ubusCall = UbusCall(
                    "file",
                    "write",
                    mapOf("path" to CRONTAB_PATH, "data" to data)
                )
            )
        )
    }

    /** e.g. `"LEDs off at 23:00, on at 07:00"`. */
    fun describe(schedule: LedSchedule): String = String.format(
        Locale.ROOT,
        "LEDs off at %02d:%02d, on at %02d:%02d",
        schedule.offHour, schedule.offMinute, schedule.onHour, schedule.onMinute
    )
}

// Top-level aliases so callers can also import the helpers directly.
fun cronLine(atHour: Int, atMinute: Int, command: String): String = CronSchedule.cronLine(atHour, atMinute, command)

fun render(schedule: LedSchedule): String = CronSchedule.render(schedule)

fun parse(lines: List<String>): LedSchedule? = CronSchedule.parse(lines)

fun merge(currentCron: String?, schedule: LedSchedule): String = CronSchedule.merge(currentCron, schedule)

fun plan(schedule: LedSchedule, currentCron: String? = null): PlanResult = CronSchedule.plan(schedule, currentCron)

fun describe(schedule: LedSchedule): String = CronSchedule.describe(schedule)
