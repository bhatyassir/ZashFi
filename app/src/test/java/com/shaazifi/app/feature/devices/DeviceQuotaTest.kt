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
package com.shaazifi.app.feature.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceQuotaTest {
    private val dayMs = 86_400_000L
    private val midnight = 1_704_067_200_000L // 2024-01-01T00:00:00Z
    private val mac = "AA:BB:CC:DD:EE:FF"

    private fun quota(name: String = "Kid's tablet", limitMb: Int = 1_000, start: Long = midnight) =
        Quota(mac, name, limitMb, start)

    private fun rejects(block: () -> Unit) {
        val threw = try {
            block()
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue("Expected IllegalArgumentException", threw)
    }

    @Test
    fun quotaValidationRejectsBadMacLimitAndNegativeWindow() {
        rejects { Quota("not-a-mac", "Phone", 100) }
        rejects { Quota("AA:BB:CC:DD:EE", "Phone", 100) }
        rejects { Quota("AA:BB:CC:DD:EE:GG", "Phone", 100) }
        rejects { Quota(mac, "Phone", 0) }
        rejects { Quota(mac, "Phone", 1_000_001) }
        rejects { Quota(mac, "Phone", 100, -1L) }
        val valid = Quota("aa:bb:cc:dd:ee:ff", "Phone", 1_000_000, 0L)
        assertEquals(0L, valid.windowStartMs)
        assertEquals(1_000_000, valid.limitMb)
    }

    @Test
    fun windowIsComparedOnUtcEpochDays() {
        assertTrue(isCurrentWindow(quota(start = midnight + 1L), midnight + dayMs - 1L))
        assertTrue(isCurrentWindow(quota(start = midnight + 12 * 3_600_000L), midnight + 23 * 3_600_000L))
        assertFalse(isCurrentWindow(quota(start = midnight - dayMs), midnight))
        assertFalse(isCurrentWindow(quota(start = midnight + dayMs), midnight))
    }

    @Test
    fun staleWindowForgetsUsage() {
        val stale = evaluate(quota(start = midnight - dayMs), 900_000_000L, midnight + 10_000L)
        assertEquals(0, stale.usedMb)
        assertEquals(0, stale.pct)
        assertFalse(stale.over)
        assertFalse(stale.warning)

        val fresh = evaluate(quota(start = midnight + 1_000L), 900_000_000L, midnight + 10_000L)
        assertEquals(900, fresh.usedMb)
        assertEquals(90, fresh.pct)
        assertTrue(fresh.warning)
    }

    @Test
    fun usageIsFlooredAndThresholdsFollowPercent() {
        val base = quota(limitMb = 1_000, start = midnight + 1L)
        assertEquals(0, evaluate(base, 999_999L, midnight).usedMb)
        assertEquals(1, evaluate(base, 1_000_000L, midnight).usedMb)
        assertEquals(1, evaluate(base, 1_999_999L, midnight).usedMb)

        val at79 = evaluate(base, 799_999_999L, midnight)
        assertEquals(79, at79.pct)
        assertFalse(at79.warning)
        assertFalse(at79.over)

        val at80 = evaluate(base, 800_000_000L, midnight)
        assertEquals(80, at80.pct)
        assertTrue(at80.warning)
        assertFalse(at80.over)

        val at99 = evaluate(base, 999_999_999L, midnight)
        assertEquals(99, at99.pct)
        assertTrue(at99.warning)
        assertFalse(at99.over)

        val at100 = evaluate(base, 1_000_000_000L, midnight)
        assertEquals(100, at100.pct)
        assertFalse(at100.warning)
        assertTrue(at100.over)

        val huge = evaluate(base, 20_000_000_000L, midnight)
        assertEquals(999, huge.pct)
        assertTrue(huge.over)
    }

    @Test
    fun encodeDecodeRoundTripsAndEscapesNames() {
        val quirky = Quota(mac, "Kid's | tablet; 100%", 1_000, midnight)
        val phone = Quota("11:22:33:44:55:66", "Phone", 500, midnight + 1L)
        assertEquals(listOf(quirky, phone), decode(encode(listOf(quirky, phone))))
        assertEquals(emptyList<Quota>(), decode(null))
        assertEquals(emptyList<Quota>(), decode(""))
    }

    @Test
    fun decodeSkipsCorruptEntriesAndKeepsLastDuplicate() {
        val good = quota(name = "Tablet")
        val other = Quota("11:22:33:44:55:66", "Phone", 500, midnight)
        val encoded = listOf(
            encode(listOf(good, other)),
            "GG:BB:CC:DD:EE:FF|Bad mac|100|$midnight",
            "$mac|Bad limit|0|$midnight",
            "$mac|Big limit|1000001|$midnight",
            "$mac|Bad window|100|-1",
            "truncated|entry",
            "$mac|Updated|200|$midnight"
        ).joinToString(";")
        val decoded = decode(encoded)
        assertEquals(2, decoded.size)
        assertEquals(other, decoded[0])
        assertEquals(Quota(mac, "Updated", 200, midnight), decoded[1])
    }

    @Test
    fun decodeCapsAtOneHundredEntries() {
        val many = (0 until 105).map { Quota("02:00:00:00:00:%02X".format(it), "D$it", 100, midnight) }
        val decoded = decode(encode(many))
        assertEquals(100, decoded.size)
        assertEquals(many.last(), decoded.last())
    }

    @Test
    fun alertTextNamesDeviceAndUsageWithoutAddresses() {
        val text = alertText(evaluate(quota(), 850_000_000L, midnight + 1L))
        assertTrue(text.contains("Kid's tablet"))
        assertTrue(text.contains("850 MB"))
        assertTrue(text.contains("1000 MB"))
        assertTrue(text.contains("85%"))
        assertFalse(text.contains(mac))
        assertFalse(text.contains("192.168"))

        val overText = alertText(evaluate(quota(), 1_200_000_000L, midnight + 1L))
        assertTrue(overText.contains("Kid's tablet"))
        assertTrue(overText.contains("1200 MB"))
        assertFalse(overText.contains("11:22:33:44:55:66"))
    }

    @Test
    fun dailyResetPointsToNextUtcMidnight() {
        assertEquals(midnight, dailyResetAtMs(midnight - 1L))
        assertEquals(midnight + dayMs, dailyResetAtMs(midnight))
        assertEquals(midnight + 2L * dayMs, dailyResetAtMs(midnight + dayMs + 1L))
    }
}
