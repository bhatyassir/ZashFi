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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Pure-logic tests for thermal history, trend, alert text and the compact wire codec. */
class ThermalHistoryTest {

    private fun sample(atMs: Long, id: String = "cpu", tempC: Double = 50.0) =
        ThermalSample(atMs, id, tempC)

    private fun expectInvalid(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun sampleRejectsInvalidTimestampAndTemperature() {
        val ok = sample(1L, "cpu_thermal", 42.5)
        assertEquals(1L, ok.atMs)
        assertEquals("cpu_thermal", ok.sensorId)
        assertEquals(42.5, ok.tempC, 0.0)

        expectInvalid { sample(0L) }
        expectInvalid { sample(-1L) }
        expectInvalid { sample(1L, tempC = Double.NaN) }
        expectInvalid { sample(1L, tempC = Double.POSITIVE_INFINITY) }
        expectInvalid { sample(1L, tempC = Double.NEGATIVE_INFINITY) }
        expectInvalid { ThermalHistory(0) }
    }

    @Test
    fun historyDropsOldestSamplesBeyondBound() {
        val history = ThermalHistory(3)
        for (i in 1..5) history.add(sample(i * 1_000L, tempC = i.toDouble()))
        val kept = history.series("cpu", 0L, Long.MAX_VALUE)
        assertEquals(listOf(3_000L, 4_000L, 5_000L), kept.map { it.atMs })
        assertEquals(listOf(3.0, 4.0, 5.0), kept.map { it.tempC })

        val defaultBound = ThermalHistory()
        repeat(290) { i -> defaultBound.add(sample((i + 1).toLong())) }
        val all = defaultBound.series("cpu", 0L, Long.MAX_VALUE)
        assertEquals(288, all.size)
        assertEquals(3L, all.first().atMs)
        assertEquals(290L, all.last().atMs)
    }

    @Test
    fun seriesFiltersBySensorAndInclusiveTimeWindow() {
        val history = ThermalHistory()
        history.add(sample(1_000L, "cpu", 40.0))
        history.add(sample(2_000L, "wifi", 50.0))
        history.add(sample(3_000L, "cpu", 60.0))
        history.add(sample(4_000L, "cpu", 70.0))

        assertEquals(listOf(3_000L), history.series("cpu", 2_000L, 3_000L).map { it.atMs })
        assertEquals(
            listOf(1_000L, 3_000L, 4_000L),
            history.series("cpu", 1_000L, 4_000L).map { it.atMs }
        )
        assertTrue(history.series("gpu", 0L, Long.MAX_VALUE).isEmpty())
        assertTrue(history.series("cpu", 4_000L, 1_000L).isEmpty())
    }

    @Test
    fun latestSensorIdsAndClear() {
        val history = ThermalHistory()
        history.add(sample(3_000L, "cpu", 60.0))
        history.add(sample(1_000L, "wifi", 45.0))
        history.add(sample(2_000L, "cpu", 50.0))

        assertEquals(3_000L, history.latest("cpu")!!.atMs)
        assertEquals(1_000L, history.latest("wifi")!!.atMs)
        assertNull(history.latest("gpu"))
        assertEquals(setOf("cpu", "wifi"), history.sensorIds())

        history.clear()
        assertTrue(history.sensorIds().isEmpty())
        assertNull(history.latest("cpu"))
        assertTrue(history.series("cpu", 0L, Long.MAX_VALUE).isEmpty())
    }

    @Test
    fun trendNeedsTwoSamplesAtLeastOneMinuteApart() {
        assertNull(trend(emptyList()))
        assertNull(trend(listOf(sample(60_000L, tempC = 50.0))))
        assertNull(
            trend(
                listOf(
                    sample(60_000L, tempC = 50.0),
                    sample(119_000L, tempC = 60.0)
                )
            )
        )
        val oneMinute = trend(
            listOf(
                sample(60_000L, "cpu", 50.0),
                sample(120_000L, "cpu", 60.0)
            )
        )!!
        assertEquals(1L, oneMinute.minutes)
        assertEquals(50.0, oneMinute.startC, 0.0)
        assertEquals(60.0, oneMinute.endC, 0.0)
        assertEquals(600.0, oneMinute.risePerHourC, 1e-9)
    }

    @Test
    fun trendUsesWindowAndComputesRisePerHour() {
        val base = 1_000L
        val samples = listOf(
            sample(base, "cpu", 50.0),
            sample(base + 10 * 60_000L, "cpu", 60.0),
            sample(base + 40 * 60_000L, "cpu", 70.0)
        )

        val halfHour = trend(samples, windowMinutes = 30)!!
        assertEquals("cpu", halfHour.sensorId)
        assertEquals(60.0, halfHour.startC, 0.0)
        assertEquals(70.0, halfHour.endC, 0.0)
        assertEquals(30L, halfHour.minutes)
        assertEquals(20.0, halfHour.risePerHourC, 1e-9)

        val fullHourWindow = trend(samples, windowMinutes = 60)!!
        assertEquals(50.0, fullHourWindow.startC, 0.0)
        assertEquals(70.0, fullHourWindow.endC, 0.0)
        assertEquals(40L, fullHourWindow.minutes)
        assertEquals(30.0, fullHourWindow.risePerHourC, 1e-9)

        // Only the newest reading falls inside a 5-minute window: no trend can be measured.
        assertNull(trend(samples, windowMinutes = 5))
        assertNull(trend(samples, windowMinutes = 0))
    }

    @Test
    fun trendHandlesFallingTemperaturesAndDuplicateTimes() {
        val falling = trend(
            listOf(
                sample(1_000L, "cpu", 90.0),
                sample(601_000L, "cpu", 75.0)
            )
        )!!
        assertEquals(-90.0, falling.risePerHourC, 1e-9)
        assertFalse(isDangerous(falling, 100.0))
        assertNull(
            trend(
                listOf(
                    sample(1_000L, "cpu", 50.0),
                    sample(1_000L, "cpu", 60.0)
                )
            )
        )
    }

    @Test
    fun isDangerousHonoursAbsoluteMaxAndRiseThreshold() {
        assertFalse(isDangerous(null, 100.0))

        val flatHot = Trend("cpu", 121.0, 121.0, 0.0, 10)
        assertTrue(isDangerous(flatHot, 120.0))
        assertFalse(isDangerous(flatHot, null))

        val rising = Trend("cpu", 65.0, 75.0, 20.0, 30)
        assertTrue(isDangerous(rising, null))
        assertTrue(isDangerous(rising, 120.0))
        assertTrue(isDangerous(Trend("cpu", 50.0, 75.0, 25.0, 60), 120.0))
        assertTrue(isDangerous(Trend("cpu", 69.0, 70.0, 20.0, 30), null))

        assertFalse(isDangerous(Trend("cpu", 64.0, 69.9, 30.0, 30), null))
        assertFalse(isDangerous(Trend("cpu", 65.0, 75.0, 19.9, 30), null))
    }

    @Test
    fun alertTextContainsSensorAndTemperaturesWithoutAddresses() {
        val text = alertText(Trend("hwmon0/cpu_thermal", 65.0, 85.0, 40.0, 30))
        assertTrue(text.contains("hwmon0/cpu_thermal"))
        assertTrue(text.contains("65.0"))
        assertTrue(text.contains("85.0"))
        assertTrue(text.contains("+40.0"))
        assertFalse(text.contains("192.168"))
        assertFalse(text.contains("aa:bb:cc"))
    }

    @Test
    fun encodeCapsAt300AndKeepsNewest() {
        val all = (1..305).map { i -> sample(i.toLong(), id = "s$i", tempC = i.toDouble()) }
        val encoded = encodeThermalHistory(all)
        val lines = encoded.split('\n')
        assertEquals(300, lines.size)
        assertEquals("6|s6|6.0", lines.first())
        assertEquals("305|s305|305.0", lines.last())
        assertEquals(encoded, ThermalHistoryCodec.encode(all))
    }

    @Test
    fun decodeDropsCorruptLines() {
        val raw = listOf(
            "",
            "   ",
            "garbage",
            "0|cpu|50.0",
            "-1|cpu|50.0",
            "10||50.0",
            "11|cpu|NaN",
            "12|cpu|Infinity",
            "13|cpu|oops",
            "14|cpu|50.0|extra",
            "15|cpu|55.5",
            "16|wifi|70.25"
        ).joinToString("\r\n")

        assertEquals(
            listOf(ThermalSample(15L, "cpu", 55.5), ThermalSample(16L, "wifi", 70.25)),
            decodeThermalHistory(raw)
        )
        assertTrue(decodeThermalHistory(null).isEmpty())
        assertTrue(decodeThermalHistory("  \n  ").isEmpty())
    }

    @Test
    fun decodeCapsAt300AndRoundTripsEncodedHistory() {
        val samples = (1..305).map { i -> sample(i.toLong(), "cpu", i.toDouble()) }
        val decoded = decodeThermalHistory(encodeThermalHistory(samples))
        assertEquals(300, decoded.size)
        assertEquals(samples.takeLast(300), decoded)
        assertEquals(6L, decoded.first().atMs)
        assertEquals(305L, decoded.last().atMs)
    }

    @Test
    fun historySeriesFeedsTrendEndToEnd() {
        val history = ThermalHistory()
        val base = 1_000L
        history.add(sample(base, "cpu", 50.0))
        history.add(sample(base + 10 * 60_000L, "cpu", 60.0))
        history.add(sample(base + 20 * 60_000L, "wifi", 45.0))
        history.add(sample(base + 40 * 60_000L, "cpu", 70.0))

        val series = history.series("cpu", base, base + 60 * 60_000L)
        assertEquals(3, series.size)
        val computed = trend(series)!!
        assertEquals("cpu", computed.sensorId)
        assertEquals(30L, computed.minutes)
        assertEquals(20.0, computed.risePerHourC, 1e-9)
        assertTrue(isDangerous(computed, 120.0))
    }
}
