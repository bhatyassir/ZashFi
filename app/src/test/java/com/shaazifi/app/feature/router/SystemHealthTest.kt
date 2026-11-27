package com.shaazifi.app.feature.router

import com.shaazifi.app.data.DemoData
import com.shaazifi.app.ui.theme.PillTone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun conntrackSkipsMalformedLinesAndAvoidsIcmpKeyCollisions() {
        val text = buildString {
            appendLine("ipv4     2 tcp      6 431999 ESTABLISHED src=192.168.1.5 dst=203.0.113.1 sport=50000 dport=443 packets=10 bytes=1000 src=203.0.113.1 dst=198.51.100.2 sport=443 dport=50000 packets=20 bytes=9000 [ASSURED]")
            appendLine("ipv4     2 tcp      6 100 SYN_SENT src=192.168.1.5 dst=203.0.113.2 sport=50001 dport=443 packets=1 src=203.0.113.2 dst=198.51.100.2")
            appendLine("ipv4     2 icmp     1 29 src=192.168.1.5 dst=1.1.1.1 type=8 code=0 id=101 packets=1 bytes=84 src=1.1.1.1 dst=198.51.100.2 type=0 code=0 id=101 packets=1 bytes=84")
            appendLine("ipv4     2 icmp     1 29 src=192.168.1.5 dst=1.1.1.1 type=8 code=0 id=202 packets=1 bytes=168 src=1.1.1.1 dst=198.51.100.2 type=0 code=0 id=202 packets=1 bytes=168")
        }
        val flows = requireNotNull(Conntrack.parse(text))
        assertEquals(3, flows.size)
        assertEquals(3, flows.map { it.key }.distinct().size)

        val hosts = HostIndex(
            devices = listOf(HostIndex.Host("AA:AA:AA:AA:AA:01", "Laptop", setOf("192.168.1.5"))),
            routerAddresses = setOf("192.168.1.1", "198.51.100.2"),
            homeNets = listOf("192.168.1.0/24")
        )
        val afterText = text
            .replace("id=101 packets=1 bytes=84 src=1.1.1.1 dst=198.51.100.2 type=0 code=0 id=101 packets=1 bytes=84",
                "id=101 packets=2 bytes=184 src=1.1.1.1 dst=198.51.100.2 type=0 code=0 id=101 packets=2 bytes=284")
            .replace("id=202 packets=1 bytes=168 src=1.1.1.1 dst=198.51.100.2 type=0 code=0 id=202 packets=1 bytes=168",
                "id=202 packets=2 bytes=368 src=1.1.1.1 dst=198.51.100.2 type=0 code=0 id=202 packets=2 bytes=568")
        val after = requireNotNull(Conntrack.parse(afterText))
        val usage = BandwidthMeter.usage(flows, after, seconds = 1.0, hosts = hosts)
        val laptop = usage.devices.single()
        assertEquals(300.0, laptop.upBytesPerSec, 0.1)
        assertEquals(600.0, laptop.downBytesPerSec, 0.1)
    }

    @Test
    fun hardwareFlowOffloadingNoteResolvesCleanly() {
        val plain = FeatureView("Live")
        assertNull(resolveHardwareOffloadNote(plain, FlowOffloadingStatus(software = true, hardware = false)))
        val hw = resolveHardwareOffloadNote(plain, FlowOffloadingStatus(software = true, hardware = true))
        assertNotNull(hw)
        assertTrue(hw!!.contains("PPE"))
    }

    @Test
    fun demoDataProvidesRealisticThermalFixtures() {
        assertEquals(listOf("CPU / SoC", "2.4 GHz Wi-Fi", "5 GHz Wi-Fi"), DemoData.thermals.map { it.label })
        assertEquals(listOf(49.4, 51.0, 53.0), DemoData.thermals.map { it.tempCelsius })
        val thermalRows = DemoData.healthView.sections.first { it.heading == "Thermals" }.rows
        assertEquals(3, thermalRows.size)
        assertEquals("49.4°C", thermalRows[0].value)
    }
}
