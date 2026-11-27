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

import java.util.Locale

/**
 * Thermal trend logic for router sensors. Pure Kotlin: no UI, no network, no persistence API.
 *
 * [ThermalHistory] keeps a bounded in-memory deque (append-only, oldest dropped first, not
 * thread-safe); the free functions work on plain lists so a [Trend] can be computed from any
 * window the caller already has. Nothing here includes an address, a MAC or a router credential.
 *
 * The wire codec lives in [ThermalHistoryCodec]: this package already has a top-level
 * `decode(String?)` (DowntimeTimeline), so the uniquely named aliases at the bottom of this file
 * avoid a platform declaration clash while still exposing compact encode/decode helpers.
 */

/** One sensor reading. Invalid timestamps and non-finite temperatures are rejected at construction. */
data class ThermalSample(val atMs: Long, val sensorId: String, val tempC: Double) {
    init {
        require(atMs > 0L) { "atMs must be > 0" }
        require(tempC.isFinite()) { "tempC must be finite" }
    }
}

/** Bounded append-only history. Samples stay in insertion order; the oldest are dropped first. */
class ThermalHistory(private val maxSamples: Int = 288) {
    private val samples = ArrayDeque<ThermalSample>()

    init {
        require(maxSamples > 0) { "maxSamples must be > 0" }
    }

    fun add(sample: ThermalSample) {
        samples.addLast(sample)
        while (samples.size > maxSamples) samples.removeFirst()
    }

    /** Samples of [sensorId] in the inclusive [fromMs, toMs] window, ordered by time. */
    fun series(sensorId: String, fromMs: Long, toMs: Long): List<ThermalSample> =
        samples.filter { it.sensorId == sensorId && it.atMs >= fromMs && it.atMs <= toMs }
            .sortedBy { it.atMs }

    /** Newest reading for [sensorId], or null when that sensor has no samples. */
    fun latest(sensorId: String): ThermalSample? =
        samples.filter { it.sensorId == sensorId }.maxByOrNull { it.atMs }

    /** Every sensor id that currently has at least one sample. */
    fun sensorIds(): Set<String> = samples.mapTo(LinkedHashSet()) { it.sensorId }

    fun clear() {
        samples.clear()
    }
}

/** Temperature change of one sensor across a measured time span. */
data class Trend(
    val sensorId: String,
    val startC: Double,
    val endC: Double,
    val risePerHourC: Double,
    val minutes: Long
)

/**
 * Trend inside the last [windowMinutes] up to the newest sample. Callers pass one sensor's
 * samples (normally sorted already); sorting here is harmless and keeps the result deterministic.
 * Returns null unless two samples in the window are at least one minute apart.
 */
fun trend(samples: List<ThermalSample>, windowMinutes: Long = 30): Trend? {
    if (samples.size < 2 || windowMinutes <= 0L) return null
    val sorted = samples.sortedBy { it.atMs }
    val end = sorted.last()
    val windowMs = if (windowMinutes > Long.MAX_VALUE / 60_000L) {
        Long.MAX_VALUE
    } else {
        windowMinutes * 60_000L
    }
    val floor = if (windowMs >= end.atMs) Long.MIN_VALUE else end.atMs - windowMs
    val start = sorted.firstOrNull { it.atMs >= floor } ?: return null
    val spanMs = end.atMs - start.atMs
    if (spanMs < 60_000L) return null
    return Trend(
        sensorId = end.sensorId,
        startC = start.tempC,
        endC = end.tempC,
        risePerHourC = (end.tempC - start.tempC) / (spanMs / 3_600_000.0),
        minutes = spanMs / 60_000L
    )
}

/**
 * A trend is dangerous when the newest reading exceeds the sensor's absolute maximum, or when it
 * is climbing at least 20 °C/hour while already at or above 70 °C. No trend means no alert.
 */
fun isDangerous(trend: Trend?, absoluteMaxC: Double?): Boolean {
    if (trend == null) return false
    if (absoluteMaxC != null && trend.endC > absoluteMaxC) return true
    return trend.risePerHourC >= 20.0 && trend.endC >= 70.0
}

/** Short notification line: sensor id and temperatures only, never an address or a device MAC. */
fun alertText(trend: Trend): String {
    val start = String.format(Locale.US, "%.1f", trend.startC)
    val end = String.format(Locale.US, "%.1f", trend.endC)
    val rate = String.format(Locale.US, "%+.1f", trend.risePerHourC)
    return "${trend.sensorId}: $start°C → $end°C ($rate°C/h in ${trend.minutes}m)"
}

/** Compact `ms|id|temp` codec: at most 300 newest samples, corrupt lines are dropped. */
object ThermalHistoryCodec {
    const val MAX_SERIALIZED = 300

    fun encode(history: List<ThermalSample>): String =
        history.takeLast(MAX_SERIALIZED).joinToString("\n") { sample ->
            val id = sample.sensorId.replace('|', '_').replace('\n', '_').replace('\r', '_')
            "${sample.atMs}|$id|${sample.tempC}"
        }

    fun decode(value: String?): List<ThermalSample> {
        if (value.isNullOrBlank()) return emptyList()
        val parsed = ArrayList<ThermalSample>()
        for (raw in value.split('\n', '\r')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val parts = line.split('|')
            if (parts.size != 3) continue
            val atMs = parts[0].trim().toLongOrNull() ?: continue
            val id = parts[1].trim()
            val tempC = parts[2].trim().toDoubleOrNull() ?: continue
            if (atMs <= 0L || id.isEmpty() || !tempC.isFinite()) continue
            parsed.add(ThermalSample(atMs, id, tempC))
        }
        return parsed.takeLast(MAX_SERIALIZED)
    }
}

// `encode`/`decode` are already top-level in this package (DowntimeTimeline); use unique aliases.
fun encodeThermalHistory(history: List<ThermalSample>): String = ThermalHistoryCodec.encode(history)

fun decodeThermalHistory(value: String?): List<ThermalSample> = ThermalHistoryCodec.decode(value)
