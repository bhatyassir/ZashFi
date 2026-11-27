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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure, offline tests: no router is contacted and no crontab is written. */
class CronScheduleTest {

    private val schedule = LedSchedule(23, 0, 7, 0)

    private val nightLine = "00 23 * * * /usr/bin/led-nightmode night"
    private val dayLine = "00 07 * * * /usr/bin/led-nightmode day"

    // --- validation ---------------------------------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOffHourAbove23() {
        LedSchedule(24, 0, 7, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeOffMinute() {
        LedSchedule(23, -1, 7, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOnHourAbove23() {
        LedSchedule(23, 0, 24, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOnMinuteAbove59() {
        LedSchedule(23, 0, 7, 60)
    }

    @Test
    fun acceptsBoundaryTimes() {
        val edge = LedSchedule(0, 0, 23, 59)
        assertEquals(0, edge.offHour)
        assertEquals(0, edge.offMinute)
        assertEquals(23, edge.onHour)
        assertEquals(59, edge.onMinute)
    }

    // --- cronLine / render --------------------------------------------------------

    @Test
    fun cronLineUsesOnlyTheFixedCommandAndZeroPaddedFields() {
        assertEquals("05 07 * * * /usr/bin/led-nightmode day", cronLine(7, 5, "day"))
        assertEquals("00 23 * * * /usr/bin/led-nightmode night", cronLine(23, 0, "night"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun cronLineRejectsArbitraryCommand() {
        cronLine(7, 5, "day; rm -rf /")
    }

    @Test(expected = IllegalArgumentException::class)
    fun cronLineRejectsOutOfRangeTime() {
        cronLine(24, 0, "night")
    }

    @Test
    fun renderProducesExactlyTwoNewlineTerminatedLines() {
        assertEquals("$nightLine\n$dayLine\n", render(schedule))
    }

    // --- parse --------------------------------------------------------------------

    @Test
    fun parseRoundTripsOurOwnFormat() {
        assertEquals(schedule, parse(render(schedule).lines()))
        assertEquals(schedule, parse(render(schedule).split("\n")))
    }

    @Test
    fun parseRejectsADifferentCommand() {
        assertNull(parse(listOf(nightLine, "07 00 * * * /usr/bin/led-nightmode reboot")))
        assertNull(parse(listOf(nightLine, "07 00 * * * /bin/sh day")))
    }

    @Test
    fun parseRejectsSwappedCommands() {
        assertNull(parse(listOf(dayLine, nightLine)))
    }

    @Test
    fun parseRejectsExtraFieldsAndExtraEntries() {
        assertNull(parse(listOf("$nightLine extra", dayLine)))
        assertNull(parse(listOf("00 23 * * 1 /usr/bin/led-nightmode night", dayLine)))
        assertNull(parse(render(schedule).lines() + "0 4 * * * /usr/bin/backup"))
    }

    @Test
    fun parseRejectsOutOfRangeTimes() {
        assertNull(parse(listOf("00 24 * * * /usr/bin/led-nightmode night", dayLine)))
        assertNull(parse(listOf("60 23 * * * /usr/bin/led-nightmode night", dayLine)))
        assertNull(parse(listOf(nightLine, "07 60 * * * /usr/bin/led-nightmode day")))
    }

    @Test
    fun parseRejectsInjectedTextAndEmbeddedNewlines() {
        assertNull(parse(listOf("00 23 * * * /bin/sh -c night", dayLine)))
        assertNull(parse(listOf("00 23 * * * /usr/bin/led-nightmode night\n$dayLine", dayLine)))
        assertNull(parse(listOf("00 23 * * * /usr/bin/led-nightmode night $(id)", dayLine)))
    }

    // --- merge --------------------------------------------------------------------

    @Test
    fun mergeWithoutCrontabIsJustTheTwoLines() {
        assertEquals(render(schedule), merge(null, schedule))
        assertEquals(render(schedule), merge("$nightLine\n$dayLine\n", schedule))
    }

    @Test
    fun mergeDropsOldLedLinesAndPreservesEveryOtherJob() {
        val current = listOf(
            "MAILTO=root",
            "15 4 * * * /usr/bin/backup",
            nightLine,
            "30 6 * * 1 /usr/bin/led-nightmode day",
            "0 5 * * * /usr/bin/report",
            "# keep /usr/bin/led-nightmode documentation"
        ).joinToString("\n", postfix = "\n")

        val merged = merge(current, schedule)

        assertEquals(
            listOf(
                "MAILTO=root",
                "15 4 * * * /usr/bin/backup",
                "0 5 * * * /usr/bin/report",
                "# keep /usr/bin/led-nightmode documentation",
                nightLine,
                dayLine
            ),
            merged.trimEnd('\n').lines()
        )
        assertTrue(merged.contains("15 4 * * * /usr/bin/backup"))
        assertFalse(merged.contains("30 6 * * 1 /usr/bin/led-nightmode"))
        assertTrue(merged.endsWith(render(schedule)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun mergeRejectsOversizedInput() {
        merge("x".repeat(4097), schedule)
    }

    // --- plan ---------------------------------------------------------------------

    @Test
    fun planWritesTheMergedCrontabThroughOneNarrowUbusFileWrite() {
        val current = "0 4 * * * /usr/bin/backup\n00 22 * * * /usr/bin/led-nightmode night\n"
        val result = plan(schedule, current)

        assertTrue(result is PlanResult.Ready)
        val p = (result as PlanResult.Ready).plan
        assertEquals(
            UbusCall(
                "file",
                "write",
                mapOf("path" to "/etc/crontabs/root", "data" to merge(current, schedule))
            ),
            p.ubusCall
        )
        val data = p.ubusCall!!.args.getValue("data")
        assertTrue(data.contains("0 4 * * * /usr/bin/backup"))
        assertFalse(data.contains("00 22 * * * /usr/bin/led-nightmode"))
        assertTrue(data.endsWith(render(schedule)))
        assertTrue(p.ops.isEmpty())
        assertNull(p.command)
    }

    @Test
    fun planTitleLinesAndNoteDescribeTheNightLedChange() {
        val result = plan(schedule, null)
        assertTrue(result is PlanResult.Ready)
        val p = (result as PlanResult.Ready).plan

        assertTrue(p.title.contains("Night LEDs"))
        assertTrue(p.title.contains("23:00"))
        assertTrue(p.title.contains("07:00"))
        assertTrue(p.lines.any { it.label.contains("Night LEDs") })
        assertTrue(p.lines.any { it.after.contains("23:00") })
        assertTrue(p.lines.any { it.after.contains("07:00") })
        assertTrue(p.note!!.contains("only the two"))
        assertTrue(p.note!!.contains("preserved only if"))
    }

    @Test
    fun planRejectsOversizedCrontab() {
        assertTrue(plan(schedule, "x".repeat(4097)) is PlanResult.Invalid)
    }

    // --- describe / facade --------------------------------------------------------

    @Test
    fun describeFormatsBothTimes() {
        assertEquals("LEDs off at 23:00, on at 07:00", describe(schedule))
        assertEquals("LEDs off at 00:05, on at 23:59", describe(LedSchedule(0, 5, 23, 59)))
        assertEquals(describe(schedule), CronSchedule.describe(schedule))
    }

    @Test
    fun objectFacadeMatchesTopLevelHelpers() {
        assertEquals(CronSchedule.cronLine(7, 5, "day"), cronLine(7, 5, "day"))
        assertEquals(CronSchedule.render(schedule), render(schedule))
        assertEquals(CronSchedule.parse(render(schedule).lines()), parse(render(schedule).lines()))
        assertEquals(CronSchedule.merge(null, schedule), merge(null, schedule))
    }
}
