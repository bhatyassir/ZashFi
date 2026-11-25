package com.shaazifi.app.feature.home

/**
 * Made-up readings for demo mode. Makes no network calls at all.
 * Counters grow on every reading so speed and CPU look alive.
 */
class DemoHomeRepository : HomeRepository {
    private var reads = 0L
    private var extraRx = 0L
    private var extraTx = 0L
    private var ticks = 0L

    /** Demo counters move every second too, so the chart looks the same as with a router. */
    override suspend fun loadCounters(): TrafficCounters {
        ticks++
        val wave = longArrayOf(4, 6, 5, 8, 7, 9, 6, 5, 3, 7)[(ticks % 10).toInt()]
        extraRx += wave * 660_000L
        extraTx += wave * 96_000L
        val n = reads - 1
        val w = longArrayOf(4, 6, 5, 8, 7, 9, 6, 5)[((n.coerceAtLeast(0)) % 8).toInt()]
        return TrafficCounters(18_400_000_000L + n * w * 3_300_000L + extraRx, 2_100_000_000L + n * w * 480_000L + extraTx)
    }

    override suspend fun load(): HomeReading {
        val n = reads++
        // A gentle wave so the speed graph is not a flat line.
        val wave = longArrayOf(4, 6, 5, 8, 7, 9, 6, 5)[(n % 8).toInt()]
        return HomeReading(
            board = BoardInfo(model = "Demo router", hostname = "demo", firmware = "OpenWrt (demo)"),
            uptimeSec = 12L * 86400 + 3 * 3600 + n * 5,
            memUsedPct = 41,
            cpu = CpuSample(busy = 1_000 + n * 60, total = 10_000 + n * 2_000),
            wan = WanStatus(
                up = true,
                proto = "dhcp",
                device = "wan",
                ipv4 = "203.0.113.7",
                gateway = "203.0.113.1",
                uptimeSec = 11L * 86400 + n * 5
            ),
            clients = ClientSummary(total = 9, wifi = 7, wired = 2, wifiKnown = true),
            wanCounters = TrafficCounters(
                rxBytes = 18_400_000_000L + n * wave * 3_300_000L + extraRx,
                txBytes = 2_100_000_000L + n * wave * 480_000L + extraTx
            ),
            notices = emptySet(),
            interfaces = listOf("lan" to true, "wan" to true, "wan6" to true, "vpn" to false),
            radios = listOf(
                RadioSummary("Demo Home", "2.4 GHz", "6", true, 1, WifiNetworkKey("radio0", "home", "wlan0")),
                RadioSummary("Demo Home 5G", "5 GHz", "36", true, 2, WifiNetworkKey("radio1", "home5g", "wlan1"))
            )
        )
    }
}
