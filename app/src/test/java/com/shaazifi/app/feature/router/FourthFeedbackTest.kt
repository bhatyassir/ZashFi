package com.shaazifi.app.feature.router

import com.shaazifi.app.feature.home.UbusCaller
import com.shaazifi.app.ui.theme.PillTone
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner feedback 2026-11-18 (Notes PDF): "Who is using the internet" shows live download and
 * upload per device; device forms (parental controls and others) offer a device list to choose from.
 * Source (read 2026-11-18): openwrt/luci luci-base ucode sys.uc conntrack_list reads
 * /proc/net/nf_conntrack and adds both directions' `bytes` together, so the app reads the file
 * itself to keep them apart (first tuple = original direction, second = reply).
 */
class FourthFeedbackTest {

    private fun line(proto: String, oSrc: String, oDst: String, oSport: Int, oDport: Int, oBytes: Long, rSrc: String, rDst: String, rBytes: Long) =
        "ipv4     2 $proto      6 431999 ESTABLISHED src=$oSrc dst=$oDst sport=$oSport dport=$oDport packets=10 bytes=$oBytes " +
            "src=$rSrc dst=$rDst sport=$oDport dport=$oSport packets=20 bytes=$rBytes [ASSURED] mark=0 zone=0 use=2"

    @Test
    fun conntrackLinesKeepBothDirections() {
        val flows = requireNotNull(Conntrack.parse(
            line("tcp", "192.168.1.5", "203.0.113.1", 50000, 443, 1_000, "203.0.113.1", "198.51.100.2", 9_000) + "\n" +
                "ipv6     10 udp      17 30 src=fd00::5 dst=2001:db8::1 sport=5000 dport=53 packets=1 bytes=80 src=2001:db8::1 dst=fd00::5 sport=53 dport=5000 packets=1 bytes=200 mark=0 use=2\n" +
                "garbage line\n"
        ))
        assertEquals(2, flows.size)
        assertEquals("192.168.1.5", flows[0].origSrc)
        assertEquals(1_000L, flows[0].origBytes)
        assertEquals(9_000L, flows[0].replyBytes)
        assertEquals("203.0.113.1", flows[0].replySrc)
        assertEquals("udp", flows[1].proto)
    }

    private val hosts = HostIndex(
        devices = listOf(
            HostIndex.Host("AA:AA:AA:AA:AA:01", "Laptop", setOf("192.168.1.5", "fd00::5")),
            HostIndex.Host("AA:AA:AA:AA:AA:02", "TV", setOf("192.168.1.9"))
        ),
        routerAddresses = setOf("192.168.1.1", "198.51.100.2"),
        homeNets = listOf("192.168.1.0/24")
    )

    @Test
    fun liveUsageSplitsDownloadAndUploadPerDevice() {
        val before = requireNotNull(Conntrack.parse(
            line("tcp", "192.168.1.5", "203.0.113.1", 50000, 443, 1_000, "203.0.113.1", "198.51.100.2", 9_000) + "\n" +
                line("tcp", "203.0.113.50", "198.51.100.2", 40000, 8443, 500, "192.168.1.9", "203.0.113.50", 100)
        ))
        val after = requireNotNull(Conntrack.parse(
            line("tcp", "192.168.1.5", "203.0.113.1", 50000, 443, 3_000, "203.0.113.1", "198.51.100.2", 1_009_000) + "\n" +
                // Port forward from outside to the TV: the TV uploads the reply direction.
                line("tcp", "203.0.113.50", "198.51.100.2", 40000, 8443, 1_500, "192.168.1.9", "203.0.113.50", 200_100) + "\n" +
                "ipv6     10 tcp      6 300 ESTABLISHED src=fd00::5 dst=2001:db8::1 sport=6000 dport=443 packets=1 bytes=4000 src=2001:db8::1 dst=fd00::5 sport=443 dport=6000 packets=1 bytes=96000 mark=0 use=2\n" +
                // The router's own traffic and home-to-home traffic are not internet use of a device.
                line("udp", "198.51.100.2", "203.0.113.9", 123, 123, 999, "203.0.113.9", "198.51.100.2", 999) + "\n" +
                line("tcp", "192.168.1.5", "192.168.1.9", 50100, 8009, 77_777, "192.168.1.9", "192.168.1.5", 77_777)
        ))
        val usage = BandwidthMeter.usage(before, after, seconds = 2.0, hosts = hosts)
        val laptop = usage.devices.first { it.name == "Laptop" }
        // Laptop: IPv4 (2,000 up, 1,000,000 down) + new IPv6 (4,000 up, 96,000 down), over 2 s.
        assertEquals(3_000.0, laptop.upBytesPerSec, 0.1)
        assertEquals(548_000.0, laptop.downBytesPerSec, 0.1)
        assertEquals(2, laptop.connections)
        val tv = usage.devices.first { it.name == "TV" }
        assertEquals(500.0, tv.downBytesPerSec, 0.1)      // 1,000 from outside / 2 s
        assertEquals(100_000.0, tv.upBytesPerSec, 0.1)    // 200,000 sent / 2 s
        assertEquals("busiest first", "Laptop", usage.devices.first().name)
        assertFalse("router itself is not a device", usage.devices.any { it.address == "198.51.100.2" })
        assertEquals(laptop.downBytesPerSec + tv.downBytesPerSec, usage.totalDownBytesPerSec, 0.1)
        assertEquals("203.0.113.1", laptop.top.first().remote) // largest destination first
    }

    @Test
    fun theUsageScreenShowsLiveRatesAsAPicture() {
        val usage = BandwidthMeter.Usage(
            devices = listOf(BandwidthMeter.DeviceRate("Laptop", "AA:AA:AA:AA:AA:01", "192.168.1.5", 125_000.0, 12_500.0, 3, emptyList())),
            totalDownBytesPerSec = 125_000.0, totalUpBytesPerSec = 12_500.0
        )
        val v = FeatureMappers.liveUsage(usage, history = emptyMap())
        assertEquals("1.0 Mbit/s", RealtimeStats.rateText(v.bandwidth!!.devices.first().downBytesPerSec))
        assertEquals(PillTone.Ok, v.pill!!.tone)
        assertTrue(v.summary.isNotBlank())
    }

    @Test
    fun liveUsageShowsMissingCountersAndStartsFreshWhenTheyReturnOrTheRouterChanges() = runTest {
        fun reading(bytes: Long) = line("tcp", "192.168.1.5", "203.0.113.1", 50000, 443, bytes, "203.0.113.1", "198.51.100.2", bytes)
        var text = reading(0)
        var combined = JsonObject(emptyMap())
        val reader = RouterReader(object : UbusCaller {
            override suspend fun call(ubusObject: String, method: String, args: JsonObject): JsonObject =
                when (ubusObject to method) {
                    "file" to "read" -> JsonObject(mapOf("data" to JsonPrimitive(text)))
                    "luci" to "getConntrackList" -> combined
                    "luci-rpc" to "getHostHints" ->
                        Json.parseToJsonElement("""{"AA:AA:AA:AA:AA:01":{"ipaddrs":["192.168.1.5"],"name":"Laptop"}}""").jsonObject
                    else -> JsonObject(emptyMap())
                }
        })
        val load = requireNotNull(Features.byId("bandwidth")).load
        val firstRouter = FeatureContext(false)
        assertEquals("Measuring live traffic…", load(reader, firstRouter).summary)
        val zero = requireNotNull(load(reader, firstRouter).bandwidth)
        assertEquals(0.0, zero.totalDownBytesPerSec, 0.0)
        assertEquals(1, zero.history.values.single().size)

        text = reading(20).replaceFirst(" bytes=20", "")
        val unavailable = load(reader, firstRouter)
        assertEquals("Traffic data unavailable", unavailable.summary)
        assertNull(unavailable.bandwidth)
        assertNull(unavailable.pill)

        text = reading(100)
        assertEquals("Measuring live traffic…", load(reader, firstRouter).summary)
        text = reading(200)
        assertEquals(1, requireNotNull(load(reader, firstRouter).bandwidth).history.values.single().size)

        text = reading(200).padEnd(4096)
        combined = Json.parseToJsonElement("""{"result":[{"src":"192.168.1.5","bytes":400}]}""").jsonObject
        val capped = load(reader, firstRouter)
        assertNull(capped.bandwidth)
        assertEquals("0 busy", capped.pill?.text)

        text = reading(300)
        assertEquals("Measuring live traffic…", load(reader, firstRouter).summary)
        text = reading(400)
        assertEquals(1, requireNotNull(load(reader, firstRouter).bandwidth).history.values.single().size)

        val secondRouter = FeatureContext(false)
        assertEquals("Measuring live traffic…", load(reader, secondRouter).summary)
        text = reading(500)
        assertEquals(1, requireNotNull(load(reader, secondRouter).bandwidth).history.values.single().size)
    }

    @Test
    fun combinedUsageRequiresEvidenceOfCountersButAllowsMeasuredZeroRates() {
        fun reading(bytes: String) =
            Json.parseToJsonElement("""{"result":[{"src":"192.168.1.5","dst":"203.0.113.1"$bytes}]}""").jsonObject
        val noCounter = reading("")
        val guessedZero = reading(""","bytes":0""")
        val measured = reading(""","bytes":100""")

        assertEquals("Traffic data unavailable", FeatureMappers.bandwidth(noCounter, measured, 2.0, emptyMap()).summary)
        assertEquals("Traffic data unavailable", FeatureMappers.bandwidth(guessedZero, guessedZero, 2.0, emptyMap()).summary)
        val idle = FeatureMappers.bandwidth(measured, measured, 2.0, emptyMap())
        assertEquals("0 busy", idle.pill?.text)
        assertTrue(idle.summary.contains("Separate download and upload readings are unavailable"))
    }

    @Test
    fun deviceFormsOfferAListToChooseFrom() {
        val view = FeatureMappers.parental(FeatureMappersTest.uci("{}"), emptyMap())
        val pause = view.forms.first { it.title == "Pause a device now" }
        assertEquals(PickKind.DEVICE_MAC, pause.fields.first { it.key == "device" }.pick)
        val schedule = view.forms.first { it.title == "Block on a schedule" }
        assertEquals(PickKind.DEVICE_MAC, schedule.fields.first { it.key == "device" }.pick)
        assertEquals(listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"), schedule.fields.first { it.key == "days" }.choices)
        val ports = FeatureMappers.portForwarding(FeatureMappersTest.uci("{}"))
        assertEquals(PickKind.DEVICE_IP, ports.forms.first().fields.first { it.key == "ip" }.pick)
        assertNull(ports.forms.first().fields.first { it.key == "name" }.pick)
    }
}
