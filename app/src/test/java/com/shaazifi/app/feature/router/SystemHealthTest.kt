package com.shaazifi.app.feature.router

import com.shaazifi.app.ui.theme.PillTone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M10a. `system info` units (procd system.c, read 2026-11-17): memory in bytes, root/tmp in KiB,
 * load = raw Linux sysinfo loads (shown divided by 65536: UNVERIFIED, owner compares with LuCI).
 */
class SystemHealthTest {

    private val info = FeatureMappersTest.fixture("m7_system_info.json")

    private fun row(v: FeatureView, label: String) = v.sections.flatMap { it.rows }.first { it.label == label }

    @Test
    fun theRealReplyBecomesReadableNumbers() {
        val v = FeatureMappers.health(info, cpuPct = 12)
        assertEquals("12%", row(v, "Processor").value)
        assertTrue(row(v, "Memory").value.startsWith("86%"))
        assertEquals("372 MB of 432 MB in use", row(v, "Memory").sub)
        assertTrue(row(v, "Storage").value.startsWith("34%"))
        assertEquals("61 MB of 178 MB in use", row(v, "Storage").sub)
        assertTrue(row(v, "Temporary files").value.startsWith("3%"))
        assertTrue(row(v, "Swap").value.startsWith("4%"))
        assertEquals("19 h 50 min", row(v, "Running for").value)
        assertEquals("0.00 · 0.00 · 0.00", row(v, "Load (1, 5, 15 min)").value)
        assertEquals(PillTone.Ok, v.pill?.tone)
    }

    @Test
    fun cpuIsMeasuringUntilTwoSamplesExist() {
        assertEquals("Measuring...", row(FeatureMappers.health(info, cpuPct = null), "Processor").value)
    }

    @Test
    fun loadAndUptimeFormatting() {
        assertEquals("1.00 · 0.50 · 0.00", FeatureMappers.loadText(listOf(65536.0, 32768.0, 0.0)))
        assertEquals("2 d 7 h", FeatureMappers.uptimeText(200_000))
        assertEquals("5 min", FeatureMappers.uptimeText(300))
    }

    @Test
    fun lowMemoryOrStorageIsFlagged() {
        val tight = Json.parseToJsonElement(
            """{"uptime":10,"load":[0,0,0],"memory":{"total":1000,"free":10,"available":20},"root":{"total":100,"used":50,"avail":50}}"""
        ).jsonObject
        val v = FeatureMappers.health(tight, cpuPct = 5)
        assertEquals(PillTone.Warn, v.pill?.tone)
        assertEquals("Running low", v.pill?.text)
    }
}
