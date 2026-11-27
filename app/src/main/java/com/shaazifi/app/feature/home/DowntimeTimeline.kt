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
 * Local internet downtime history. Pure logic only: no persistence, no network, no IP addresses.
 *
 * The timeline is a list of connectivity transitions. Consecutive events with the same state are
 * redundant and are ignored everywhere, so an unclosed "down" stretch is never reported as an
 * outage that is still ongoing.
 */
object DowntimeTimeline {

    private const val DAY_MS = 86_400_000L
    private const val WEEK_MS = 7L * DAY_MS
    private const val MAX_EVENTS = 500
    private const val MAX_DAYS = 90

    /** A connectivity transition at a strictly positive epoch-millisecond timestamp. */
    data class NetEvent(val atMs: Long, val up: Boolean) {
        init {
            require(atMs > 0) { "atMs must be > 0" }
        }
    }

    /** A closed down stretch. Unclosed stretches are deliberately not representable from mergeOutages. */
    data class Outage(val startMs: Long, val endMs: Long) {
        val durationMs: Long get() = endMs - startMs
    }

    /** UTC midnight at or before [ms]. Pure arithmetic, so it needs no java.time/desugaring. */
    private fun startOfUtcDay(ms: Long): Long = Math.floorDiv(ms, DAY_MS) * DAY_MS

    /** Sort, drop duplicate states, and keep the most recent [MAX_EVENTS] transitions. */
    private fun normalize(events: List<NetEvent>): List<NetEvent> {
        val sorted = events.sortedBy { it.atMs }
        val unique = ArrayList<NetEvent>(sorted.size)
        var previous: Boolean? = null
        for (event in sorted) {
            if (event.up == previous) continue
            previous = event.up
            unique.add(event)
        }
        return if (unique.size > MAX_EVENTS) unique.takeLast(MAX_EVENTS) else unique
    }

    /**
     * Pairs each down transition with the following up transition.
     * Duplicate states are ignored; a down stretch with no later up is omitted.
     */
    fun mergeOutages(events: List<NetEvent>): List<Outage> {
        val outages = ArrayList<Outage>()
        var downStart: Long? = null
        var state: Boolean? = null // before the first event the connection is assumed up
        for (event in events.sortedBy { it.atMs }) {
            if (event.up == state) continue
            state = event.up
            if (!event.up) {
                downStart = event.atMs
            } else {
                val start = downStart
                if (start != null) {
                    outages.add(Outage(start, event.atMs))
                    downStart = null
                }
            }
        }
        return outages
    }

    /**
     * (uptimeMs, downtimeMs) inside [fromMs, toMs]. The window is clipped; events at or before
     * [fromMs] only set the initial state, and events at or after [toMs] cannot add duration.
     */
    fun uptime(events: List<NetEvent>, fromMs: Long, toMs: Long): Pair<Long, Long> {
        require(toMs >= fromMs) { "toMs must be >= fromMs" }
        if (toMs == fromMs) return 0L to 0L

        val sorted = events.sortedBy { it.atMs }
        var index = 0
        var state = true
        while (index < sorted.size && sorted[index].atMs <= fromMs) {
            state = sorted[index].up
            index++
        }

        var cursor = fromMs
        var up = 0L
        var down = 0L
        while (index < sorted.size && sorted[index].atMs < toMs) {
            val event = sorted[index]
            val span = event.atMs - cursor
            if (span > 0) {
                if (state) up += span else down += span
                cursor = event.atMs
            }
            if (event.up != state) state = event.up // duplicate states ignored
            index++
        }
        val tail = toMs - cursor
        if (tail > 0) {
            if (state) up += tail else down += tail
        }
        return up to down
    }

    /** One (UTC midnight epochMs, uptime percent 0..100) per day, at most 90 days. */
    fun dailyUptime(events: List<NetEvent>, fromMs: Long, days: Int): List<Pair<Long, Double>> {
        if (days <= 0) return emptyList()
        val count = minOf(days, MAX_DAYS)
        val firstMidnight = startOfUtcDay(fromMs)
        val result = ArrayList<Pair<Long, Double>>(count)
        for (day in 0 until count) {
            val start = firstMidnight + day * DAY_MS
            val upMs = uptime(events, start, start + DAY_MS).first
            val percent = (upMs.toDouble() / DAY_MS.toDouble() * 100.0).coerceIn(0.0, 100.0)
            result.add(start to percent)
        }
        return result
    }

    /** Compact, one "ms:1" (up) or "ms:0" (down) transition per line. */
    fun encode(events: List<NetEvent>): String =
        normalize(events).joinToString("\n") { event -> "${event.atMs}:${if (event.up) '1' else '0'}" }

    /** Tolerates blank/garbage lines, bad flags, overflowing timestamps and out-of-order input. */
    fun decode(value: String?): List<NetEvent> {
        if (value.isNullOrBlank()) return emptyList()
        val parsed = ArrayList<NetEvent>()
        for (raw in value.split('\n', '\r')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val atMs = line.substring(0, separator).trim().toLongOrNull() ?: continue
            if (atMs <= 0L) continue
            val up = when (line.substring(separator + 1).trim().lowercase(Locale.ROOT)) {
                "1", "u", "up", "true", "t", "y", "yes" -> true
                "0", "d", "down", "false", "f", "n", "no" -> false
                else -> continue
            }
            parsed.add(NetEvent(atMs, up))
        }
        return normalize(parsed)
    }

    /**
     * Short human-readable history line. Only counts outages that ended within the last 7 days
     * (clipped to [nowMs]); no addresses or router details are ever included.
     */
    fun summary(outages: List<Outage>, nowMs: Long): String {
        val cutoff = nowMs - WEEK_MS
        val recent = ArrayList<Outage>()
        for (outage in outages) {
            val end = minOf(outage.endMs, nowMs)
            if (end > outage.startMs && end > cutoff && outage.startMs < nowMs) {
                recent.add(Outage(outage.startMs, end))
            }
        }
        if (recent.isEmpty()) return "No outages recorded"

        val todayStart = startOfUtcDay(nowMs)
        val prefix = if (recent.size == 1 && recent[0].startMs >= todayStart) {
            "1 outage today"
        } else {
            "${recent.size} ${if (recent.size == 1) "outage" else "outages"} in the last 7 days"
        }
        val totalMs = recent.sumOf { it.durationMs }
        val minutes = maxOf(0L, totalMs / 60_000L)
        val duration = if (minutes < 60L) "$minutes min" else "${minutes / 60L} h ${minutes % 60L} min"
        return "$prefix, $duration total"
    }
}

// Top-level aliases keep both `mergeOutages(...)` and `DowntimeTimeline.mergeOutages(...)` usable.
typealias NetEvent = DowntimeTimeline.NetEvent
typealias Outage = DowntimeTimeline.Outage

fun mergeOutages(events: List<NetEvent>): List<Outage> = DowntimeTimeline.mergeOutages(events)

fun uptime(events: List<NetEvent>, fromMs: Long, toMs: Long): Pair<Long, Long> =
    DowntimeTimeline.uptime(events, fromMs, toMs)

fun dailyUptime(events: List<NetEvent>, fromMs: Long, days: Int): List<Pair<Long, Double>> =
    DowntimeTimeline.dailyUptime(events, fromMs, days)

fun encode(events: List<NetEvent>): String = DowntimeTimeline.encode(events)

fun decode(value: String?): List<NetEvent> = DowntimeTimeline.decode(value)

fun summary(outages: List<Outage>, nowMs: Long): String = DowntimeTimeline.summary(outages, nowMs)
