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
package com.shaazifi.app.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic coverage for the local downtime timeline. All timestamps are made up. */
class DowntimeTimelineTest {

    private val day = 86_400_000L
    private val minute = 60_000L
    // 2024-01-01T00:00:00Z
    private val midnight = 1_704_067_200_000L

    @Test
    fun mergeOutagesIgnoresDuplicatesAndSortsInput() {
        val events = listOf(
            NetEvent(midnight + 80 * minute, true), // duplicate up
            NetEvent(midnight + 30 * minute, true),
            NetEvent(midnight + 75 * minute, true),
            NetEvent(midnight + 20 * minute, false),
            NetEvent(midnight + 45 * minute, false),
            NetEvent(midnight + 60 * minute, false), // duplicate down
            NetEvent(midnight + 10 * minute, true)   // duplicate initial up
        )
        assertEquals(
            listOf(
                Outage(midnight + 20 * minute, midnight + 30 * minute),
                Outage(midnight + 45 * minute, midnight + 75 * minute)
            ),
            mergeOutages(events)
        )
        assertEquals(10 * minute, mergeOutages(events)[0].durationMs)
        assertEquals(30 * minute, mergeOutages(events)[1].durationMs)
        assertTrue(mergeOutages(emptyList()).isEmpty())
        assertTrue(mergeOutages(listOf(NetEvent(midnight, true))).isEmpty())
    }

    @Test
    fun mergeOutagesOmitsUnclosedOutage() {
        val events = listOf(
            NetEvent(midnight + 1_000, false),
            NetEvent(midnight + 2_000, false), // still down
            NetEvent(midnight + 3_000, true),
            NetEvent(midnight + 4_000, false)  // never closes: must not be reported
        )
        assertEquals(listOf(Outage(midnight + 1_000, midnight + 3_000)), mergeOutages(events))
        assertTrue(mergeOutages(listOf(NetEvent(midnight, false))).isEmpty())
    }

    @Test
    fun netEventRequiresPositiveTimestamp() {
        assertThrows(IllegalArgumentException::class.java) { NetEvent(0L, true) }
        assertThrows(IllegalArgumentException::class.java) { NetEvent(-1L, false) }
        assertEquals(1L, NetEvent(1L, true).atMs)
    }

    @Test
    fun uptimeClipsWindowAndUsesEarlierEventsForInitialState() {
        val events = listOf(
            NetEvent(1_000L, false),
            NetEvent(2_000L, true),  // up just before/at the window start
            NetEvent(3_000L, false),
            NetEvent(5_000L, true),
            NetEvent(6_000L, false),
            NetEvent(9_000L, true)   // after the window: ignored
        )
        // 2-3s up, 3-5s down, 5-6s up, 6-7s down.
        assertEquals(2_000L to 3_000L, uptime(events, 2_000L, 7_000L))
        // Full range: up 0-1, 2-3, 5-6, 9-10; down 1-2, 3-5, 6-9.
        assertEquals(4_000L to 6_000L, uptime(events, 0L, 10_000L))
        assertEquals(5_000L to 0L, uptime(emptyList(), 0L, 5_000L))
        assertEquals(0L to 0L, uptime(events, 5_000L, 5_000L))
        assertThrows(IllegalArgumentException::class.java) { uptime(events, 10L, 5L) }
    }

    @Test
    fun dailyUptimeReturnsUtcMidnightsAndPercentages() {
        val events = listOf(
            NetEvent(midnight + 6 * 3_600_000L, false),
            NetEvent(midnight + 12 * 3_600_000L, true) // 6 h down on day one
        )
        // fromMs is midday, but the first bucket still starts at UTC midnight.
        val days = dailyUptime(events, midnight + 12 * 3_600_000L, 3)
        assertEquals(3, days.size)
        assertEquals(midnight, days[0].first)
        assertEquals(midnight + day, days[1].first)
        assertEquals(midnight + 2 * day, days[2].first)
        assertEquals(75.0, days[0].second, 1e-9)
        assertEquals(100.0, days[1].second, 1e-9)
        assertEquals(100.0, days[2].second, 1e-9)
        assertTrue(dailyUptime(events, midnight, 0).isEmpty())
        assertTrue(dailyUptime(events, midnight, -3).isEmpty())
        assertEquals(90, dailyUptime(events, midnight, 200).size)
    }

    @Test
    fun encodeDecodeRoundTripAndDropCorruption() {
        val events = listOf(
            NetEvent(2_000L, true),
            NetEvent(1_000L, false), // out of order
            NetEvent(3_000L, false),
            NetEvent(4_000L, false)  // duplicate
        )
        val normalized = listOf(NetEvent(1_000L, false), NetEvent(2_000L, true), NetEvent(3_000L, false))
        assertEquals("1000:0\n2000:1\n3000:0", encode(events))
        assertEquals(normalized, decode(encode(events)))

        val messy = listOf(
            "oops", "0:1", "-5:0", "500:maybe", "2000:1", "1000:0", "1000:0",
            "1500:DOWN", "999999999999999999999999:1", "3000:d", ""
        ).joinToString("\r\n")
        assertEquals(normalized, decode(messy))
        assertTrue(decode(null).isEmpty())
        assertTrue(decode("").isEmpty())
    }

    @Test
    fun encodeDecodeCapAtFiveHundredMostRecentEvents() {
        val many = (1..600).map { NetEvent(it * 1_000L, it % 2 == 0) }
        val encoded = encode(many)
        assertEquals(500, encoded.lines().size)
        val decoded = decode(encoded)
        assertEquals(500, decoded.size)
        assertEquals(101_000L, decoded.first().atMs)
        assertEquals(false, decoded.first().up)
    }

    @Test
    fun summaryUsesTodayAndSevenDayWording() {
        val now = midnight + 12 * 3_600_000L
        assertEquals("No outages recorded", summary(emptyList(), now))

        val today = Outage(midnight + 8 * 3_600_000L, midnight + 8 * 3_600_000L + 12 * minute)
        assertEquals("1 outage today, 12 min total", summary(listOf(today), now))

        val twoDaysAgo = midnight - 2 * day
        val recent = listOf(
            Outage(twoDaysAgo + 3_600_000L, twoDaysAgo + 2 * 3_600_000L),
            Outage(midnight + 3_600_000L, midnight + 3_600_000L + 65 * minute)
        )
        assertEquals("2 outages in the last 7 days, 2 h 5 min total", summary(recent, now))

        val yesterday = midnight - day
        assertEquals(
            "1 outage in the last 7 days, 10 min total",
            summary(listOf(Outage(yesterday, yesterday + 10 * minute)), now)
        )
        assertEquals(
            "No outages recorded",
            summary(listOf(Outage(now - 8 * day, now - 8 * day + 30 * minute)), now)
        )
        assertEquals("No outages recorded", summary(listOf(Outage(now + 1_000L, now + 2_000L)), now))
    }
}
