package com.shaazifi.app.feature.router

import com.shaazifi.app.feature.home.CpuSample
import com.shaazifi.app.feature.home.HomeParsers
import com.shaazifi.app.ui.theme.PillTone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Calendar
import java.util.Locale

/** Caches prior CPU sample and board metadata across live refreshes of System health. */
class HealthSampleCache {
    private var previousCpu: CpuSample? = null
    private var cachedBoard: JsonObject? = null

    @Synchronized
    fun nextCpuPct(current: CpuSample?): Int? {
        val prev = previousCpu
        if (current != null) previousCpu = current
        return HomeParsers.cpuUsedPct(prev, current)
    }

    @Synchronized
    fun board(): JsonObject? = cachedBoard

    @Synchronized
    fun setBoard(board: JsonObject) {
        cachedBoard = board
    }
}

/** What a feature loader may need besides the router. */
data class FeatureContext(
    val adGuardConfigured: Boolean,
    val routerIp: String = "",
    val liveUsageMeter: LiveUsageMeter = LiveUsageMeter(),
    val healthCache: HealthSampleCache = HealthSampleCache(),
    /** Phone-local history for uptime and thermal trend panels; null in tests and demo. */
    val localHistory: com.shaazifi.app.feature.home.LocalHistoryStore? = null
)

/**
 * One native, read-only feature screen. [untangledId] is UnTangled's own screen id
 * (src/lib/nav.ts), used to open that page for changes until native changes arrive.
 */
data class FeatureSpec(
    val id: String,
    val group: String,
    val title: String,
    val listSubtitle: String,
    val untangledId: String?,
    val liveRefreshMs: Long = 0L,
    val demo: FeatureView,
    val load: suspend (RouterReader, FeatureContext) -> FeatureView
)

private suspend fun RouterReader.wanDevice(): String? =
    optional("network.interface", "dump")?.let { HomeParsers.parseWan(it).device.ifBlank { null } }

private suspend fun RouterReader.namesByMac(): Map<String, String> {
    val leases = optional("luci-rpc", "getDHCPLeases") ?: return emptyMap()
    return (leases["dhcp_leases"] as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val mac = (o["macaddr"] as? JsonPrimitive)?.content?.uppercase(Locale.ROOT) ?: return@mapNotNull null
        val name = (o["hostname"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "*" } ?: return@mapNotNull null
        mac to name
    }.toMap()
}

/**
 * Which addresses belong to which device (host hints: hardware address -> name, IPv4 and IPv6
 * addresses; names from DHCP leases first), the router's own addresses and the home networks.
 */
private suspend fun RouterReader.hostIndex(): HostIndex {
    val leaseNames = namesByMac()
    val hints = optional("luci-rpc", "getHostHints")
    val hosts = hints?.mapNotNull { (mac, el) ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val addrs = listOf("ipaddrs", "ip6addrs").flatMap { k -> (o[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content } }.toSet()
        if (addrs.isEmpty()) return@mapNotNull null
        val name = leaseNames[mac.uppercase(Locale.ROOT)] ?: (o["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: addrs.firstOrNull { it.contains('.') } ?: addrs.first()
        HostIndex.Host(mac.uppercase(Locale.ROOT), name, addrs)
    }.orEmpty()
    val dump = optional("network.interface", "dump")
    val ifaces = (dump?.get("interface") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val own = mutableSetOf<String>()
    val nets = mutableListOf<String>()
    ifaces.forEach { i ->
        val name = (i["interface"] as? JsonPrimitive)?.content.orEmpty()
        listOf("ipv4-address", "ipv6-address").forEach { k ->
            (i[k] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { a ->
                val addr = (a["address"] as? JsonPrimitive)?.content ?: return@forEach
                own += addr.lowercase(Locale.ROOT)
                val mask = (a["mask"] as? JsonPrimitive)?.content
                if (k == "ipv4-address" && mask != null && !name.startsWith("wan") && name != "loopback") nets += "$addr/$mask"
            }
        }
    }
    return HostIndex(hosts, own, nets)
}

/** IPv4 address -> device name, from the DHCP leases. */
private suspend fun RouterReader.namesByIp(): Map<String, String> {
    val leases = optional("luci-rpc", "getDHCPLeases") ?: return emptyMap()
    return (leases["dhcp_leases"] as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val ip = (o["ipaddr"] as? JsonPrimitive)?.content ?: return@mapNotNull null
        val name = (o["hostname"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "*" } ?: return@mapNotNull null
        ip to name
    }.toMap()
}

private fun demo(summary: String, pill: ViewPill?, vararg sections: ViewSection) = FeatureView(summary, pill, sections.toList())
private fun row(label: String, value: String = "", pill: ViewPill? = null, mono: Boolean = false, sub: String? = null) = ViewRow(label, value, sub, mono, pill)
private val on = ViewPill("On", PillTone.Ok)
private val off = ViewPill("Off", PillTone.Off)

// rpcd file.c reads at most this many bytes when a /proc file reports no size.
private const val RPCD_PROC_READ_LIMIT = 4096

/** Every UnTangled feature, grouped like the mock's "All features" screen. Devices and Home have their own screens. */
object Features {

    const val INTERNET = "Internet and network"
    const val WIFI = "Wi-Fi"
    const val PROTECTION = "Protection"
    const val VPN = "VPN"
    const val SPEED = "Speed and usage"
    const val SYSTEM = "System"

    val all: List<FeatureSpec> = listOf(
        // ---- Wi-Fi
        FeatureSpec("wireless", WIFI, "Wireless", "Network names, security, channels", "wireless",
            demo = DemoWifiOverview
        ) { r, _ ->
            r.gate()
            val wifi = r.required("luci-rpc", "getWirelessDevices")
            // Channel and power choices per radio, as LuCI reads them (iwinfo freqlist / txpowerlist with the radio name).
            val radioIds = wifi.keys.filter { WifiRadio.isRadioName(it) }
            val dev = { id: String -> kotlinx.serialization.json.buildJsonObject { put("device", kotlinx.serialization.json.JsonPrimitive(id)) } }
            val freq = radioIds.associateWith { r.optional("iwinfo", "freqlist", dev(it)) }
            val power = radioIds.associateWith { r.optional("iwinfo", "txpowerlist", dev(it)) }
            val country = radioIds.associateWith { r.optional("iwinfo", "countrylist", dev(it)) }
            FeatureMappers.wireless(wifi, WifiRadio.radioInfos(wifi, freq, power, country))
        },

        FeatureSpec("guest", WIFI, "Guest network", "Internet for visitors", "guest",
            demo = demo("No guest network yet.", off)
        ) { r, _ -> r.gate(); FeatureMappers.guest(r.required("luci-rpc", "getWirelessDevices")) },

        FeatureSpec("mesh", WIFI, "Mesh Wi-Fi", "One Wi-Fi name across routers", null,
            demo = demo("One Wi-Fi name across several routers.", ViewPill("2 routers", PillTone.Ok),
                ViewSection("Routers in this mesh", listOf(row("Living room", "Main", mono = true), row("Upstairs", "Good signal", mono = true))))
        ) { r, _ ->
            r.gate()
            val info = r.optional("untangled.mesh", "info")
            val status = r.optional("untangled.mesh", "status")
            val nodes = r.optional("untangled.mesh", "nodes")
            // Mesh Wi-Fi comes from UnTangled's own package; on other LuCI routers the screen says it is not installed.
            if (info == null && status == null && nodes == null) throw FeatureLoadException.NotInstalled()
            if (info == null && status == null && nodes == null) throw FeatureLoadException.NotInstalled()
            FeatureMappers.mesh(info, status, nodes)
        },

        FeatureSpec("extend", WIFI, "Extend Wi-Fi", "Join another Wi-Fi network", "extend",
            demo = demo("This router is not connected to another Wi-Fi network.", off)
        ) { r, _ ->
            r.gate()
            FeatureMappers.extendWifi(r.required("luci-rpc", "getWirelessDevices"), r.uci("firewall", required = false), r.uci("network", required = false))
        },

        // ---- Internet and network
        FeatureSpec("interfaces", INTERNET, "Interfaces and ports", "Addresses, traffic, cables", null,
            demo = demo("3 of 3 interfaces up.", null,
                ViewSection("Interfaces", listOf(row("lan", "Static address", pill = on, sub = "br-lan · 192.168.1.1/24"), row("wan", "Automatic (DHCP)", pill = on, sub = "eth1"))),
                ViewSection("Ports", listOf(row("eth0", "1000 Mb/s full", pill = on), row("eth1", "Unplugged", pill = off)))).copy(
                network = NetworkPicture(
                    links = listOf(
                        NetLink("lan", "Static address", true, "br-lan", "192.168.1.1/24", 18_400_000_000, 2_100_000_000, 1_036_800),
                        NetLink("wan", "Automatic (DHCP)", true, "wan", "203.0.113.7/24", 2_100_000_000, 18_400_000_000, 950_400),
                        NetLink("vpn", "WireGuard VPN", false, "wg0", null, null, null, null)
                    ),
                    ports = listOf(
                        NetPort("wan", PortKind.INTERNET, true, 1000, "full", 18_400_000_000, 2_100_000_000),
                        NetPort("lan1", PortKind.LAN, true, 1000, "full", 6_200_000_000, 900_000_000),
                        NetPort("lan2", PortKind.LAN, false, null, null, 0, 0),
                        NetPort("lan3", PortKind.LAN, true, 100, "full", 120_000_000, 40_000_000),
                        NetPort("lan4", PortKind.LAN, false, null, null, 0, 0),
                        NetPort("phy0-ap0", PortKind.WIRELESS, true, null, null, 2_000_000_000, 9_000_000_000),
                        NetPort("phy1-ap0", PortKind.WIRELESS, true, null, null, 3_000_000_000, 7_000_000_000),
                        NetPort("br-lan", PortKind.BRIDGE, true, null, null, 2_100_000_000, 18_400_000_000)
                    )
                )
            )
        ) { r, _ ->
            r.gate()
            FeatureMappers.interfaces(r.required("luci-rpc", "getNetworkDevices"), r.optional("network.interface", "dump"))
        },

        FeatureSpec("live", SPEED, "Live traffic", "Speed right now on each port", null, liveRefreshMs = 3_000,
            demo = RealtimeStats.view(
                mapOf("wan" to Rates(1_550_000.0, 137_500.0, 960_000.0, 102_000.0, 1_550_000.0, 137_500.0,
                    (0..150).map { i -> TrafficSample(i * 2_000_000_000L,
                        950_000.0 + 300_000.0 * kotlin.math.sin(i/8.0), 100_000.0 + 25_000.0 * kotlin.math.sin(i/5.0)) }),
                    "br-lan" to Rates(125_000.0, 1_500_000.0, 110_000.0, 1_400_000.0, 150_000.0, 1_600_000.0)), "wan")
        ) { r, _ ->
            r.gate()
            // luci-bwc (LuCI's realtime graphs) measures per device; the internet port and the LAN bridge.
            val wan = r.wanDevice()
            val devices = listOfNotNull(wan, "br-lan").distinct()
            val replies = devices.associateWith { d ->
                r.optional("luci", "getRealtimeStats", kotlinx.serialization.json.buildJsonObject {
                    put("mode", kotlinx.serialization.json.JsonPrimitive("interface")); put("device", kotlinx.serialization.json.JsonPrimitive(d))
                })
            }
            if (replies.values.all { it == null || it.containsKey("error") }) throw FeatureLoadException.NotInstalled()
            RealtimeStats.view(replies.mapValues { (_, v) -> v?.let { RealtimeStats.rates(it) } }, wan)
        },

        FeatureSpec("routes", INTERNET, "Static routes", "Paths to other networks", null,
            demo = demo("Extra paths to other networks.", ViewPill("1 route", PillTone.Ok),
                ViewSection("Routes", listOf(row("10.8.0.0/24", "via 192.168.1.2 on lan", mono = true))))
        ) { r, _ -> r.gate(); FeatureMappers.routes(r.uci("network")!!) },

        FeatureSpec("wan", INTERNET, "Internet (WAN)", "Connection type and status", "wan",
            demo = demo("How the router connects to your internet provider.", ViewPill("Online", PillTone.Ok),
                ViewSection("Settings", listOf(row("Connection type", "PPPoE"), row("DNS servers", "From your internet provider"))))
        ) { r, _ -> r.gate(); FeatureMappers.wan(r.uci("network")!!, r.optional("network.interface", "dump")) },

        FeatureSpec("lan", INTERNET, "LAN and DHCP", "Router address and address pool", "lan",
            demo = demo("Your home network address.", ViewPill("192.168.1.1", PillTone.Off),
                ViewSection("Handing out addresses (DHCP)", listOf(row("DHCP", pill = on), row("First address", ".100", mono = true), row("How many", "150"), row("Lease time", "12h", mono = true))))
        ) { r, _ -> r.gate(); FeatureMappers.lan(r.uci("network")!!, r.uci("dhcp", required = false)) },

        FeatureSpec("ipv6", INTERNET, "IPv6", "Newer internet addresses", "ipv6",
            demo = demo("The newer internet addresses.", on, ViewSection("IPv6", listOf(row("From your provider", pill = on), row("Prefix", "2001:db8:12::/56", mono = true))))
        ) { r, _ -> r.gate(); FeatureMappers.ipv6(r.uci("network")!!, r.uci("dhcp", required = false), r.optional("network.interface", "dump")) },

        FeatureSpec("dualwan", INTERNET, "Dual WAN", "Backup internet connection", "dualwan",
            demo = demo("Two internet connections.", ViewPill("Active", PillTone.Ok), ViewSection("Connections", listOf(row("wan", "PPPoE", on), row("wwan", "USB 4G", on))))
        ) { r, _ -> r.gate(); FeatureMappers.dualWan(r.uci("mwan3")!!, r.uci("network", required = false)) },

        FeatureSpec("ddns", INTERNET, "Dynamic DNS", "A name for your home address", "ddns",
            demo = demo("A name that keeps pointing at your home.", on, ViewSection("Names", listOf(row("home.example.net", "Custom", on, mono = true))))
        ) { r, _ -> r.gate(); FeatureMappers.ddns(r.uci("ddns")!!) },

        // ---- Protection
        FeatureSpec("protection", PROTECTION, "Network protection", "Ad and threat blocking packages", "aiprotect",
            demo = demo("Safety switches for everyone on your network.", null, ViewSection("Protection", listOf(row("Block ads and trackers (adblock)", pill = on), row("Block dangerous sites (banIP)", pill = on))))
        ) { r, ctx -> r.gate(); FeatureMappers.protection(r.uci("adblock", required = false), r.uci("banip", required = false), ctx.adGuardConfigured) },

        FeatureSpec("parental", PROTECTION, "Parental controls", "Internet schedules for devices", "parental",
            demo = demo("Internet paused or limited for chosen devices.", ViewPill("1 active", PillTone.Ok),
                ViewSection("Schedules", listOf(row("Kid's tablet", "Mon Tue Wed Thu Fri · 21:30–06:30", ViewPill("Scheduled", PillTone.Ok)))))
        ) { r, _ -> r.gate(); FeatureMappers.parental(r.uci("firewall")!!, r.namesByMac()) },

        FeatureSpec("firewall", PROTECTION, "Firewall", "Zones and allowed paths", "firewall",
            demo = demo("Which parts of your network may talk to each other.", ViewPill("3 zones", PillTone.Off),
                ViewSection("Allowed paths", listOf(row("lan → wan", pill = on))))
        ) { r, _ -> r.gate(); FeatureMappers.firewall(r.uci("firewall")!!) },

        FeatureSpec("portfwd", PROTECTION, "Port forwarding", "Ports open to the internet", "portfwd",
            demo = demo("Lets something on the internet reach one device.", ViewPill("2 on", PillTone.Warn),
                ViewSection("Open ports", listOf(row("Camera", "tcp 8443 → 192.168.1.70", on, mono = true), row("Game server", "tcp 25565 → 192.168.1.12", on, mono = true))))
        ) { r, ctx -> r.gate(); FeatureMappers.portForwarding(r.uci("firewall")!!, ctx.routerIp) },

        FeatureSpec("dmz", PROTECTION, "DMZ", "Expose one device fully", "dmz",
            demo = demo("Sends everything from the internet to one device.", off, ViewSection("DMZ", listOf(row("Exposed device", "None"))))
        ) { r, ctx -> r.gate(); FeatureMappers.dmz(r.uci("firewall")!!, ctx.routerIp) },

        FeatureSpec("upnp", PROTECTION, "UPnP", "Apps that open ports themselves", "upnp",
            demo = demo("Lets games and apps open ports on their own.", on, ViewSection("UPnP", listOf(row("UPnP", pill = on), row("Secure mode", "Yes"))))
        ) { r, _ -> r.gate(); FeatureMappers.upnp(r.uci("upnpd")!!) },

        // ---- VPN
        FeatureSpec("vpn", VPN, "WireGuard and OpenVPN", "Private tunnels", "vpn",
            demo = demo("Private tunnels into your home network.", ViewPill("1 set up", PillTone.Ok),
                ViewSection("WireGuard · wg0", listOf(row("Status", pill = on), row("Listens on port", "51820", mono = true), row("Private key", "Hidden"))))
        ) { r, _ ->
            r.gate()
            FeatureMappers.vpn(r.uci("network")!!, r.uci("openvpn", required = false), r.optional("luci.wireguard", "getWgInstances"), System.currentTimeMillis() / 1000)
        },

        FeatureSpec("tailscale", VPN, "Tailscale", "Reach home from anywhere", "tailscale",
            demo = demo("A private network that reaches your home from anywhere.", ViewPill("Connected", PillTone.Ok),
                ViewSection("This router", listOf(row("Tailscale address", "100.64.0.7", mono = true))))
        ) { r, _ ->
            r.gate()
            val candidates = listOf(
                "/usr/sbin/tailscale" to listOf("status", "--json"),
                "/usr/bin/tailscale" to listOf("status", "--json")
            )
            var found: ExecResult? = null
            for ((cmd, params) in candidates) {
                val res = r.exec(cmd, params) ?: continue
                if (res.code == 127) continue
                found = res
                if (res.code == 0 || res.stdout.isNotBlank()) break
            }
            val res = found ?: throw FeatureLoadException.NotInstalled()
            FeatureMappers.tailscale(res.stdout, exitCode = res.code, stderr = res.stderr)
        },

        // ---- Speed and usage
        FeatureSpec("qos", SPEED, "Smart Queue (QoS)", "Smooth calls and games", "qos",
            demo = demo("Keeps calls and games smooth.", on, ViewSection("Smart Queue · wan", listOf(row("Status", pill = on), row("Download limit", "95 Mbps"), row("Upload limit", "19 Mbps"))))
        ) { r, _ -> r.gate(); FeatureMappers.qos(r.uci("sqm")!!) },

        FeatureSpec("traffic", SPEED, "Traffic monitor", "Data per network port", "traffic", liveRefreshMs = 5_000L,
            demo = demo("Data through each network port.", ViewPill("24.3 GB", PillTone.Off),
                ViewSection("Interfaces", listOf(row("wan", "24.3 GB", ViewPill("Internet", PillTone.Ok), true), row("br-lan", "24.1 GB", mono = true))))
        ) { r, _ -> r.gate(); FeatureMappers.traffic(r.required("network.device", "status"), r.wanDevice()) },

        // Owner feedback: the Home speed card opens this. Credit: LuCI's Realtime > Connections page (luci-mod-status).
        FeatureSpec("bandwidth", SPEED, "Who is using the internet", "Live download and upload of each device", null, liveRefreshMs = 2_000,
            demo = FeatureMappers.liveUsage(BandwidthMeter.Usage(
                listOf(
                    BandwidthMeter.DeviceRate("Smart TV", null, "192.168.1.20", 2_275_000.0, 41_000.0, 4, listOf(BandwidthMeter.Remote("203.0.113.80", "443", "tcp", 2_270_000.0, 40_000.0))),
                    BandwidthMeter.DeviceRate("Office laptop", null, "192.168.1.31", 310_000.0, 115_000.0, 27, listOf(BandwidthMeter.Remote("198.51.100.20", "443", "tcp", 250_000.0, 100_000.0))),
                    BandwidthMeter.DeviceRate("Pixel 8", null, "192.168.1.42", 27_500.0, 6_000.0, 9, emptyList()),
                    BandwidthMeter.DeviceRate("Printer", null, "192.168.1.60", 0.0, 0.0, 1, emptyList())
                ), 2_612_500.0, 162_000.0
            ), emptyMap())
        ) bandwidth@{ r, context ->
            r.gate()
            val flows = r.readFile("/proc/net/nf_conntrack")
                ?.takeUnless { it.length == RPCD_PROC_READ_LIMIT }
                ?.let(Conntrack::parse)
            val meter = context.liveUsageMeter
            if (flows != null) {
                val now = System.nanoTime()
                val (usage, history, timedHistory) = meter.sample(now, flows, r.hostIndex())
                    ?: return@bandwidth FeatureView("Measuring live traffic…")
                FeatureMappers.liveUsage(usage, history, timedHistory)
            } else {
                meter.clear()
                val first = r.optional("luci", "getConntrackList") ?: return@bandwidth FeatureMappers.bandwidthUnavailable()
                val t0 = System.nanoTime()
                kotlinx.coroutines.delay(2_000)
                val second = r.optional("luci", "getConntrackList") ?: return@bandwidth FeatureMappers.bandwidthUnavailable()
                val seconds = (System.nanoTime() - t0) / 1e9
                FeatureMappers.bandwidth(first, second, seconds, r.namesByIp())
            }
        },

        FeatureSpec("usage", SPEED, "Usage history", "Data used per day and device", "usage",
            demo = demo("How much data your internet and devices have used.", ViewPill("Today 18.4 GB", PillTone.Off),
                ViewSection("Internet usage", listOf(row("Today", "18.4 GB", mono = true), row("This month", "412 GB", mono = true))),
                ViewSection("By device", listOf(row("Smart TV", "188 GB", mono = true), row("Office laptop", "96 GB", mono = true)))).copy(
                deviceTotals = com.shaazifi.app.feature.devices.DemoDevices.devices.mapIndexed { index, device ->
                    device.mac to (if (index == 5) 0L else (index + 1) * 12_600_000L)
                }.toMap()
            )
        ) { r, _ ->
            r.gate()
            val vn = r.exec("/usr/bin/vnstat", listOf("--json"))
            val nl = r.exec("/usr/sbin/nlbw", listOf("-c", "json", "-g", "ip,mac", "-o", "rx_bytes"))
            val cal = Calendar.getInstance()
            val todayDate = Triple(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
            FeatureMappers.usage(
                vnstatOut = vn?.stdout,
                nlbwOut = nl?.stdout,
                wanDevice = r.wanDevice(),
                nameByMac = r.namesByMac(),
                vnstatResult = vn,
                nlbwResult = nl,
                todayDate = todayDate
            )
        },

        // ---- System
        FeatureSpec("admin", SYSTEM, "Administration", "Router name, time zone", "admin",
            demo = demo("The router's name and clock.", null, ViewSection("Router", listOf(row("Name", "Home router"), row("Time zone", "Asia/Kolkata"))))
        ) { r, _ ->
            r.gate()
            val zones = r.optional("luci", "getTimezones")?.mapNotNull { (k, v) ->
                ((v as? kotlinx.serialization.json.JsonObject)?.get("tzstring") as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { k to it }
            }?.toMap()
            FeatureMappers.admin(r.uci("system")!!, r.uci("uhttpd", required = false), zones)
        },

        FeatureSpec("health", SYSTEM, "System health", "Processor, memory, storage", null, liveRefreshMs = 5_000,
            demo = demo("How hard the router is working.", ViewPill("Healthy", PillTone.Ok),
                ViewSection("Now", listOf(row("Processor", "8%"), row("Memory", "42%", sub = "182 MB of 432 MB in use"), row("Storage", "34%", sub = "61 MB of 178 MB in use"), row("Running for", "3 d 4 h"))))
        ) { r, ctx ->
            val board = ctx.healthCache.board()?.also { r.gate() }
                ?: r.required("system", "board", sessionGate = true).also { ctx.healthCache.setBoard(it) }
            val info = r.required("system", "info")
            val cpuSample = r.readFile("/proc/stat")?.let { HomeParsers.parseCpuSample(it) }
            val cpuPct = ctx.healthCache.nextCpuPct(cpuSample)
            val thermals = r.readThermals()
            val offloading = r.readFlowOffloading()
            FeatureMappers.health(info, cpuPct, thermals = thermals, board = board, offloading = offloading)
        },

        FeatureSpec("services", SYSTEM, "Services", "Programs on the router", null, liveRefreshMs = 5_000,
            demo = FeatureMappers.services(listOf(
                RouterService("dnsmasq", 19, true, true), RouterService("firewall", 19, true, true),
                RouterService("uhttpd", 50, true, true), RouterService("odhcpd", 35, true, true),
                RouterService("vnstat", 95, false, false)
            ))
        ) { r, _ -> r.gate(); FeatureMappers.services(r.required("rc", "list")) },

        FeatureSpec("kernellog", SYSTEM, "Kernel log", "Messages from drivers and hardware", null,
            demo = demo("Messages from the router's system core.", ViewPill("No errors", PillTone.Ok),
                ViewSection("Messages", listOf(row("ath11k: wifi started", sub = "12.5 s after start", mono = true))))
        ) { r, _ ->
            r.gate()
            FeatureMappers.kernelLog(r.exec("/bin/dmesg", listOf("-r"))?.stdout ?: throw FeatureLoadException.NotPermitted())
        },

        FeatureSpec("log", SYSTEM, "System log", "Recent router messages", "log",
            demo = demo("The router's recent messages.", ViewPill("No errors", PillTone.Ok), ViewSection("Messages", listOf(row("daemon.info dnsmasq: started", sub = "16 Nov 09:00:01", mono = true))))
        ) { r, _ -> r.gate(); FeatureMappers.log(r.readLog() ?: throw FeatureLoadException.NotPermitted()) },

        FeatureSpec("doctor", SYSTEM, "Network doctor", "Quick health check", "doctor",
            demo = demo("A quick health check of the router.", ViewPill("Healthy", PillTone.Ok), ViewSection("Checks", listOf(row("Internet", "Connected.", ViewPill("OK", PillTone.Ok)))))
        ) { r, _ ->
            r.gate()
            FeatureMappers.doctor(
                r.optional("network.interface", "dump"),
                r.optional("system", "info"),
                r.optional("luci-rpc", "getWirelessDevices"),
                r.readLog()
            )
        },

        FeatureSpec("usb", SYSTEM, "USB storage", "Drives and sharing", "usb",
            demo = demo("Drives plugged into the router.", ViewPill("0 drives", PillTone.Off), ViewSection("Drives", emptyList(), "No USB drive found."))
        ) { r, _ -> r.gate(); FeatureMappers.usb(r.readFile("/proc/mounts"), r.uci("minidlna", required = false), r.uci("ksmbd", required = false), r.uci("samba4", required = false)) },

        FeatureSpec("software", SYSTEM, "Software", "Installed packages", "software",
            demo = demo("Programs installed on the router.", ViewPill("312 packages", PillTone.Off), ViewSection("Useful extras", listOf(row("vnstat", pill = ViewPill("Installed", PillTone.Ok)))))
        ) { r, _ ->
            r.gate()
            FeatureMappers.software(
                r.execFirst(
                    "/usr/bin/apk" to listOf("info"),
                    "/usr/bin/apk" to listOf("list", "-I"),
                    "/bin/opkg" to listOf("list-installed")
                )?.stdout
            )
        },

        // ---- Pages that complete the LuCI menus (read-only unless stated).
        FeatureSpec("inspector", SYSTEM, "Router inspector", "Processes, routes, firewall and channels", null,
            demo = demo("Live router state.", null, ViewSection("Processes", listOf(row("1", "/sbin/procd", mono = true))))
        ) { r, _ -> r.gate(); RouterTools.inspector(r) },

        FeatureSpec("configtasks", SYSTEM, "Configuration & tasks", "Cron, DNS, bandwidth monitor and LEDs", null,
            demo = demo("Router configuration.", null, ViewSection("Scheduled tasks", listOf(row("Job 1", "0 23 * * * led-nightmode night", mono = true))))
        ) { r, _ -> r.gate(); RouterTools.configuration(r) },

        FeatureSpec("firewallx", PROTECTION, "Firewall extras", "NAT rules, IP sets and custom rules", null,
            demo = demo("Extra firewall views.", null, ViewSection("NAT Rules", listOf(row("No NAT rules", ""))))
        ) { r, _ -> r.gate(); ExtraPanels.firewallExtras(r) },

        FeatureSpec("realtime", SPEED, "Realtime & channels", "Live graphs and Wi-Fi channel survey", null,
            demo = demo("Live router graphs.", null, ViewSection("Channel analysis", listOf(row("phy0", "channel 1", mono = true))))
        ) { r, _ -> r.gate(); RealtimePanels.realtimeAndChannels(r) },

        FeatureSpec("tailscale-peers", VPN, "Tailscale peers", "Devices on your private network", null,
            demo = demo("Tailscale peers.", ViewPill("Connected", PillTone.Ok), ViewSection("Tailscale", listOf(row("This router", "100.64.0.7", mono = true))))
        ) { r, _ -> r.gate(); ExtraPanels.tailscaleDetails(r) },

        FeatureSpec("adguardpkg", PROTECTION, "AdGuard package", "The router's AdGuard Home settings", null,
            demo = demo("AdGuard Home on the router.", ViewPill("On", PillTone.Ok), ViewSection("Package", listOf(row("Enabled", "On"))))
        ) { r, _ -> r.gate(); AdGuardPackagePanel.load(r) },

        FeatureSpec("wifiextras", WIFI, "Wireless tools", "WPS, MAC filtering, isolation and VLANs", null,
            demo = demo("Extra Wi-Fi settings.", null, ViewSection("Client isolation", listOf(row("Off", ""))))
        ) { r, _ ->
            r.gate()
            val wifi = r.uci("wireless")!!
            val sections = wifi.ofType("wifi-iface")
            WifiExtras.panel(wifi).copy(forms = listOf(
                FeatureForm("Wi-Fi protection", listOf(
                    FormField("section", "Network section", sections.firstOrNull()?.id.orEmpty(),
                        choices = sections.map { it.id }),
                    FormField("mode", "What to change", "isolate",
                        choices = listOf("isolate", "wps", "wds")),
                    FormField("on", "On or off", "0", choices = listOf("0", "1"))
                ), "Apply") { v ->
                    val on = v["on"] == "1"
                    when (v["mode"]) {
                        "wps" -> WifiExtras.setWps(v["section"].orEmpty(), on)
                        "wds" -> WifiExtras.setWds(v["section"].orEmpty(), on)
                        else -> WifiExtras.setIsolate(v["section"].orEmpty(), on)
                    }
                },
                FeatureForm("MAC filtering", listOf(
                    FormField("section", "Network section", sections.firstOrNull()?.id.orEmpty(),
                        choices = sections.map { it.id }),
                    FormField("mode", "Mode", "disabled", choices = listOf("disabled", "allow", "deny")),
                    FormField("macs", "Allowed or blocked MACs", "",
                        hint = "Pick devices above, or type MACs separated by commas",
                        pick = com.shaazifi.app.feature.router.PickKind.DEVICE_MAC, multi = true)
                ), "Apply") { v ->
                    WifiExtras.setMacFilter(v["section"].orEmpty(), v["mode"].orEmpty(), v["macs"].orEmpty())
                },
                FeatureForm("Guest VLAN", listOf(
                    FormField("section", "Guest section", sections.firstOrNull { it.list("network").any { n -> n.contains("guest") } }?.id.orEmpty(),
                        choices = sections.filter { it.list("network").any { n -> n.contains("guest") } }.map { it.id }),
                    FormField("vlan", "VLAN ID (1–4094)")
                ), "Apply") { v -> WifiExtras.setGuestVlan(v["section"].orEmpty(), v["vlan"].orEmpty()) }
            ))
        },

        FeatureSpec("downtimeline", INTERNET, "Internet uptime", "Outages and daily availability", null,
            demo = demo("Internet availability.", ViewPill("Healthy", PillTone.Ok), ViewSection("Outages", listOf(row("Today", "No outages"))))
        ) { _, ctx ->
            val events = ctx.localHistory?.readNetEvents().orEmpty()
            val outages = com.shaazifi.app.feature.home.mergeOutages(events)
            val now = System.currentTimeMillis()
            FeatureView("Internet availability recorded on this phone.", null, listOf(
                ViewSection("Outages", listOf(ViewRow("Summary", com.shaazifi.app.feature.home.summary(outages, now))),
                    emptyText = "No outages recorded yet."),
                ViewSection("Last 7 days", com.shaazifi.app.feature.home.dailyUptime(events, now - 7L * 86_400_000L, 7)
                    .map { (day, pct) -> ViewRow(java.time.Instant.ofEpochMilli(day).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString(), "$pct% up") },
                    emptyText = "No uptime history yet.")
            ))
        },

        FeatureSpec("thermaltrend", SYSTEM, "Temperature trends", "How hot the router is getting", null,
            demo = demo("Temperature history.", ViewPill("Stable", PillTone.Ok), ViewSection("Sensors", listOf(row("CPU", "49 °C"))))
        ) { r, ctx ->
            r.gate()
            val sensors = r.readThermals()
            val samples = ctx.localHistory?.readThermalSamples().orEmpty()
            val now = System.currentTimeMillis()
            val trendRows = sensors.mapNotNull { sensor ->
                val t = com.shaazifi.app.feature.home.trend(samples.filter { it.sensorId == sensor.id }, 30)
                if (t == null) null else ViewRow(
                    sensor.label,
                    com.shaazifi.app.feature.home.alertText(t),
                    pill = if (com.shaazifi.app.feature.home.isDangerous(t, sensor.maxCelsius)) ViewPill("Rising", PillTone.Bad) else null
                )
            }
            FeatureView("Temperature history is kept on this phone.", null, listOf(
                ViewSection("Now", sensors.map { ViewRow(it.label, "%.1f °C".format(Locale.US, it.tempCelsius), mono = true) },
                    emptyText = "No temperature sensors were reported."),
                ViewSection("30-minute trend", trendRows, emptyText = "Not enough history yet; trends appear after a few readings.")
            ))
        }
    )

    val groups: List<String> = listOf(INTERNET, WIFI, PROTECTION, VPN, SPEED, SYSTEM)

    fun byId(id: String): FeatureSpec? = all.firstOrNull { it.id == id }
}
