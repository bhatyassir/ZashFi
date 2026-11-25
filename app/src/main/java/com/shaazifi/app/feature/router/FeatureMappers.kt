package com.shaazifi.app.feature.router

import com.shaazifi.app.feature.home.HomeParsers
import com.shaazifi.app.ui.theme.PillTone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Locale

/**
 * Pure functions that turn router replies into [FeatureView]s. READ-ONLY and secret-free:
 * Wi-Fi keys, VPN private keys and ISP/DDNS passwords are never put into a view.
 *
 * Option names are OpenWrt's standard UCI options (openwrt.org/docs/guide-user/... pages for
 * network, dhcp, wireless, firewall, ddns, mwan3, upnpd, sqm, openvpn, system, uhttpd) and the
 * ones UnTangled reads (reference/untangled/src/lib, commit 6acc3c2). Shapes not yet seen on the
 * owner's router are UNVERIFIED and parsed leniently: a missing field shows as "Not set".
 */
object FeatureMappers {

    private val ON = ViewPill("On", PillTone.Ok)
    private val OFF = ViewPill("Off", PillTone.Off)
    private fun onOff(b: Boolean) = if (b) ON else OFF
    private fun yesNo(b: Boolean) = if (b) "Yes" else "No"
    private fun orNotSet(s: String?) = s?.takeIf { it.isNotBlank() } ?: "Not set"

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
    private fun JsonElement?.num(): Double = (this as? JsonPrimitive)?.doubleOrNull ?: 0.0
    private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.let { it.booleanOrNull ?: it.content.let { c -> c == "1" || c == "true" } }
    private fun JsonElement?.strings(): List<String> = when (this) {
        is JsonArray -> mapNotNull { it.str() }
        is JsonPrimitive -> listOf(content)
        else -> emptyList()
    }

    fun protoLabel(proto: String?): String = when (proto?.lowercase()) {
        "dhcp" -> "Automatic (DHCP)"
        "static" -> "Static IP"
        "pppoe" -> "PPPoE"
        "dhcpv6" -> "Automatic (DHCPv6)"
        "wireguard" -> "WireGuard"
        "none", null, "" -> "Not used"
        else -> proto
    }

    fun encryptionLabel(enc: String?): String {
        val base = enc?.lowercase()?.substringBefore('+') ?: return "Not set"
        return when (base) {
            "none" -> "Open (no password)"
            "owe" -> "Enhanced open"
            "psk" -> "WPA"
            "psk2" -> "WPA2"
            "psk-mixed" -> "WPA/WPA2"
            "sae" -> "WPA3"
            "sae-mixed" -> "WPA2/WPA3"
            "wpa", "wpa2", "wpa3", "wpa-mixed", "wpa2-mixed" -> "Enterprise (${base.uppercase()})"
            else -> if (base.startsWith("wep")) "WEP (insecure)" else base
        }
    }

    fun bandLabel(band: String?): String = when (band?.lowercase()) {
        "2g" -> "2.4 GHz"
        "5g" -> "5 GHz"
        "6g" -> "6 GHz"
        "60g" -> "60 GHz"
        else -> "Wi-Fi"
    }

    private fun bytes(n: Long): String = HomeParsers.formatBytes(n)

    // ------------------------------------------------------------------ Wi-Fi

    /** A Wi-Fi network (access point or client link) from `luci-rpc getWirelessDevices`. The key is never read. */
    data class WifiIface(
        val radio: String,
        val band: String,
        val ssid: String,
        val mode: String,
        val encryption: String,
        val hidden: Boolean,
        val disabled: Boolean,
        val isolate: Boolean,
        val networks: List<String>,
        val ifname: String?,
        val stations: Int?,
        /** UCI section name of this network (for example "default_radio0"), used for on/off changes. */
        val section: String? = null
    )

    data class WifiRadio(
        val id: String,
        val band: String,
        val up: Boolean,
        val disabled: Boolean,
        val channel: String,
        val htmode: String,
        val country: String,
        val txpower: String?
    )

    /** Shape seen on the owner's router (docs/fixtures/m7_luci_rpc_wirelessdevices.json, M7). */
    fun parseWireless(reply: JsonObject): Pair<List<WifiRadio>, List<WifiIface>> {
        val radios = mutableListOf<WifiRadio>()
        val ifaces = mutableListOf<WifiIface>()
        for ((id, element) in reply) {
            val radio = element.obj() ?: continue
            val cfg = radio["config"].obj()
            val band = bandLabel(cfg?.get("band").str())
            val firstIwinfo = radio["interfaces"].arr()?.firstOrNull().obj()?.get("iwinfo").obj()
            radios += WifiRadio(
                id = id,
                band = band,
                up = radio["up"].bool() == true,
                disabled = radio["disabled"].bool() == true || cfg?.get("disabled").bool() == true,
                channel = firstIwinfo?.get("channel").str() ?: cfg?.get("channel").str() ?: "auto",
                htmode = cfg?.get("htmode").str() ?: "",
                country = cfg?.get("country").str() ?: "",
                txpower = firstIwinfo?.get("txpower").str()
            )
            for (ie in radio["interfaces"].arr() ?: JsonArray(emptyList())) {
                val iface = ie.obj() ?: continue
                val ic = iface["config"].obj() ?: continue
                ifaces += WifiIface(
                    radio = id,
                    band = band,
                    ssid = ic["ssid"].str() ?: "",
                    mode = ic["mode"].str() ?: "ap",
                    encryption = encryptionLabel(ic["encryption"].str()),
                    hidden = ic["hidden"].bool() == true,
                    disabled = ic["disabled"].bool() == true,
                    isolate = ic["isolate"].bool() == true,
                    networks = ic["network"].strings(),
                    ifname = iface["ifname"].str(),
                    stations = iface["stations"].arr()?.size,
                    section = iface["section"].str()
                )
            }
        }
        return radios to ifaces
    }

    fun isGuest(i: WifiIface): Boolean =
        i.networks.any { it.startsWith("guest") } || i.ssid.contains("guest", ignoreCase = true)

    fun wireless(reply: JsonObject): FeatureView = wireless(reply, emptyList())

    /** [radios]: channel / width / power choices per radio (phase 2), from iwinfo freqlist and txpowerlist. */
    fun wireless(reply: JsonObject, radioInfos: List<RadioInfo>): FeatureView {
        val (radios, ifaces) = parseWireless(reply)
        if (radios.isEmpty()) return FeatureView("This router has no Wi-Fi radios.", ViewPill("Wired only", PillTone.Off))
        val main = ifaces.filter { it.mode == "ap" && !isGuest(it) }
        val sections = radios.map { r ->
            val nets = main.filter { it.radio == r.id }
            val radioOn = !r.disabled && r.up
            // One editable network per band gets its change form inside the band's card.
            val editable = nets.singleOrNull { it.section != null }
            ViewSection(
                heading = r.band,
                rows = buildList {
                    if (nets.isEmpty()) add(ViewRow("No network on this band", pill = onOff(radioOn)))
                    nets.forEach { n ->
                        add(ViewRow(
                            n.ssid.ifBlank { "Unnamed network" },
                            sub = listOfNotNull(n.encryption.ifBlank { null }, if (n.hidden) "Hidden name" else null).joinToString(" · "),
                            pill = onOff(radioOn && !n.disabled)
                        ))
                    }
                    add(ViewRow("Channel", r.channel, mono = true, detail = true))
                    if (r.htmode.isNotBlank()) add(ViewRow("Channel width", r.htmode, mono = true, detail = true))
                    if (r.country.isNotBlank()) add(ViewRow("Country", r.country, mono = true, detail = true))
                    r.txpower?.let { add(ViewRow("Transmit power", "$it dBm", mono = true, detail = true)) }
                },
                form = editable?.let { n -> wifiForm(n) },
                moreForms = listOfNotNull(radioInfos.firstOrNull { it.id == r.id }?.let { radioForm(it) })
            )
        }
        val on = radios.count { !it.disabled && it.up }
        val networks = ifaces.filter { it.mode == "ap" }.mapNotNull { net ->
            val key = com.shaazifi.app.feature.home.WifiNetworkKey.from(net.radio, net.section, net.ifname) ?: return@mapNotNull null
            val radio = radios.first { it.id == net.radio }
            val name = net.ssid.ifBlank { "Unnamed network" }
            WifiNetworkDetail(key, name, net.band, FeatureView(
                summary = listOf(net.band, net.encryption).filter { it.isNotBlank() }.joinToString(" · "),
                pill = onOff(!net.disabled && !radio.disabled && radio.up),
                sections = listOf(ViewSection(
                    heading = "Network settings",
                    rows = listOfNotNull(
                        ViewRow("Channel", radio.channel, mono = true),
                        radio.htmode.takeIf { it.isNotBlank() }?.let { ViewRow("Channel width", it, mono = true) },
                        radio.country.takeIf { it.isNotBlank() }?.let { ViewRow("Country", it, mono = true) },
                        radio.txpower?.let { ViewRow("Transmit power", "$it dBm", mono = true) }
                    ),
                    form = net.section?.let { wifiForm(net) },
                    moreForms = listOfNotNull(radioInfos.firstOrNull { it.id == net.radio }?.let { radioForm(it) })
                ))
            ), channel = radio.channel, security = net.encryption, enabled = !net.disabled)
        }
        // A band with several networks (rare) keeps their forms in the folded "Changes" list.
        val leftover = main.filter { n -> n.section != null && main.count { it.radio == n.radio && it.section != null } > 1 }
        return FeatureView(
            summary = main.map { it.ssid }.distinct().joinToString(", ").ifBlank { "No Wi-Fi network set up" },
            pill = ViewPill("$on of ${radios.size} radios on", if (on > 0) PillTone.Ok else PillTone.Off),
            sections = sections,
            note = "Wi-Fi passwords are never shown or kept by the app.",
            // Bands with several networks keep their forms in the folded "Changes" list.
            forms = leftover.map { wifiForm(it, "Change ${it.ssid} (${it.band})") },
            wifiNetworks = networks
        )
    }

    private fun radioForm(r: RadioInfo) = FeatureForm(
        title = "Channel, width and power",
        fields = listOf(
            FormField("channel", "Channel", initial = r.channel, hint = (listOf("auto") + r.channels.map { it.toString() }).joinToString(", ")),
            FormField("width", "Width", initial = r.htmode, hint = com.shaazifi.app.feature.router.WifiRadio.widthChoices(r).joinToString(", ")),
            FormField("country", "Country (two letters)", initial = r.country),
            FormField("power", "Transmit power (dBm)", initial = r.txpower ?: "default", hint = (listOf("default") + r.powers.map { it.toString() }).joinToString(", "))
        ),
        submitLabel = "Save radio"
    ) { v -> com.shaazifi.app.feature.router.WifiRadio.radioSettings(r, v["channel"].orEmpty(), v["width"].orEmpty(), v["country"].orEmpty(), v["power"].orEmpty()) }

    private fun wifiForm(n: WifiIface, title: String = "Change name or password") = FeatureForm(
        title = title,
        fields = listOf(
            FormField("ssid", "Network name", initial = n.ssid),
            FormField("key", "New password (leave empty to keep)", secret = true)
        ),
        submitLabel = "Save Wi-Fi"
    ) { v -> FeatureChanges.wifiNetwork(n.section!!, n.ssid, v["ssid"].orEmpty(), v["key"].orEmpty(), n.encryption) }

    fun guest(reply: JsonObject): FeatureView {
        val guests = parseWireless(reply).second.filter { it.mode == "ap" && isGuest(it) }
        if (guests.isEmpty()) {
            return FeatureView(
                "No guest network yet. Visitors can get internet without your main password once one is created.",
                OFF
            )
        }
        val anyOn = guests.any { !it.disabled }
        return FeatureView(
            summary = "Internet for visitors, kept apart from your devices.",
            pill = onOff(anyOn),
            sections = guests.map { g ->
                ViewSection(g.band, listOf(
                    ViewRow(
                        "Network name", g.ssid,
                        pill = if (g.section == null) onOff(!g.disabled) else null,
                        toggle = if (g.section != null) !g.disabled else null,
                        onToggle = g.section?.let { sec -> { FeatureChanges.guestEnabled(sec, g.ssid, on = g.disabled) } }
                    ),
                    ViewRow("Security", g.encryption),
                    ViewRow("Devices kept apart", yesNo(g.isolate))
                ))
            }
        )
    }

    fun extendWifi(reply: JsonObject): FeatureView = extendWifi(reply, null, null)

    /** With [firewall] and [network] read, each client link gets a "Disconnect" action (phase 2). */
    fun extendWifi(reply: JsonObject, firewall: UciConfig?, network: UciConfig?): FeatureView {
        val sta = parseWireless(reply).second.filter { it.mode == "sta" }
        if (sta.isEmpty()) {
            return FeatureView("This router is not connected to another Wi-Fi network.", OFF)
        }
        return FeatureView(
            summary = "This router takes its internet from another Wi-Fi network.",
            pill = ON,
            sections = listOf(ViewSection("Connected to", sta.map {
                ViewRow(
                    it.ssid.ifBlank { "Unknown network" }, it.band, sub = it.encryption, pill = onOff(!it.disabled),
                    extra = if (firewall != null && network != null && it.section != null) {
                        RowAction("Disconnect") { com.shaazifi.app.feature.router.WifiRadio.disconnect(it.section, it.ssid, firewall, network) }
                    } else null
                )
            }))
        )
    }

    /** `untangled.mesh` comes from UnTangled's own package; its reply shape is UNVERIFIED, so values are shown as they come. */
    fun mesh(info: JsonObject?, status: JsonObject?, nodes: JsonObject?): FeatureView {
        fun flat(o: JsonObject?): List<ViewRow> = o.orEmpty().mapNotNull { (k, v) ->
            if (k !in setOf("dawn", "umdns", "wpad_mesh", "version", "role", "backhaul", "mesh_id", "roaming")) return@mapNotNull null
            val s = (v as? JsonPrimitive)?.content ?: return@mapNotNull null
            ViewRow(k.replace('_', ' ').replaceFirstChar { it.uppercase() }, s, mono = true)
        }
        val nodeList = nodes?.values?.firstOrNull { it is JsonArray }.arr()
        val nodeRows = nodeList?.mapNotNull { n ->
            val o = n.obj() ?: return@mapNotNull null
            ViewRow(o["name"].str() ?: o["hostname"].str() ?: "Router", o["role"].str() ?: "", sub = o["ip"].str() ?: o["addr"].str(), mono = true)
        }.orEmpty()
        return FeatureView(
            summary = "One Wi-Fi name across several routers, with devices moving between them.",
            pill = if (nodeRows.isNotEmpty()) ViewPill("${nodeRows.size} routers", PillTone.Ok) else null,
            sections = listOf(
                ViewSection("This router", flat(info) + flat(status), emptyText = "No mesh details reported."),
                ViewSection("Routers in this mesh", nodeRows, emptyText = "Only this router.")
            )
        )
    }

    // ------------------------------------------------------------- Internet and network

    fun wan(network: UciConfig, dump: JsonObject?): FeatureView {
        val wan = network.byId("wan")
        val live = dump?.let { HomeParsers.parseWan(it) }
        val proto = wan?.opt("proto")
        val rows = buildList {
            add(ViewRow("Connection type", protoLabel(proto)))
            if (proto == "static") {
                add(ViewRow("IP address", orNotSet(wan.opt("ipaddr")), mono = true))
                add(ViewRow("Subnet mask", orNotSet(wan.opt("netmask")), mono = true))
                add(ViewRow("Gateway", orNotSet(wan.opt("gateway")), mono = true))
            }
            if (proto == "pppoe") add(ViewRow("User name", if (wan.opt("username").isNullOrBlank()) "Not set" else "Set", sub = "Password never shown"))
            val customDns = wan?.list("dns").orEmpty()
            add(ViewRow("DNS servers", if (wan?.flag("peerdns", default = true) != false && customDns.isEmpty()) "From your internet provider" else customDns.joinToString(", "), mono = customDns.isNotEmpty()))
        }
        val status = live?.let {
            ViewSection("Right now", listOfNotNull(
                ViewRow("Status", pill = if (it.up) ViewPill("Online", PillTone.Ok) else ViewPill("Offline", PillTone.Bad)),
                it.ipv4?.let { ip -> ViewRow("Address", ip, mono = true) },
                it.gateway?.let { gw -> ViewRow("Gateway", gw, mono = true) },
                if (it.uptimeSec > 0) ViewRow("Connected for", HomeParsers.formatUptime(it.uptimeSec)) else null
            ))
        }
        val current = proto ?: "dhcp"
        return FeatureView(
            summary = "How the router connects to your internet provider.",
            pill = live?.let { if (it.up) ViewPill("Online", PillTone.Ok) else ViewPill("Offline", PillTone.Bad) },
            sections = listOfNotNull(status, ViewSection("Settings", rows)),
            forms = if (wan != null) listOf(FeatureForm(
                title = "Change the internet connection",
                fields = listOf(
                    FormField("proto", "Type: dhcp, static or pppoe", initial = current),
                    FormField("ip", "Address (static only)", initial = wan.opt("ipaddr").orEmpty()),
                    FormField("mask", "Subnet mask (static only)", initial = wan.opt("netmask") ?: "255.255.255.0"),
                    FormField("gw", "Gateway (static only)", initial = wan.opt("gateway").orEmpty()),
                    FormField("dns", "DNS servers (empty = from provider)", initial = wan.list("dns").joinToString(" ")),
                    FormField("user", "PPPoE user name (pppoe only)", initial = wan.opt("username").orEmpty()),
                    FormField("pass", "PPPoE password (pppoe only; empty keeps it)", secret = true)
                ),
                submitLabel = "Save internet settings"
            ) { v ->
                FeatureChanges.wan(current, v["proto"].orEmpty(), v["ip"].orEmpty(), v["mask"].orEmpty(), v["gw"].orEmpty(), v["dns"].orEmpty(), v["user"].orEmpty(), v["pass"].orEmpty())
            }) else emptyList()
        )
    }

    fun lan(network: UciConfig, dhcp: UciConfig?): FeatureView {
        val lan = network.byId("lan")
        val raw = lan?.opt("ipaddr") ?: ""
        val ip = raw.substringBefore('/')
        val prefix = raw.substringAfter('/', "").toIntOrNull()
        val mask = prefix?.let { prefixToMask(it) } ?: lan?.opt("netmask") ?: "255.255.255.0"
        val pool = dhcp?.sections?.firstOrNull { it.type == "dhcp" && (it.opt("interface") == "lan" || it.id == "lan") }
        val dhcpOn = pool != null && !pool.flag("ignore")
        val start = pool?.opt("start") ?: "100"
        val limit = pool?.opt("limit") ?: "150"
        return FeatureView(
            summary = "Your home network address and the addresses handed out to devices.",
            pill = ViewPill(ip.ifBlank { "Not set" }, PillTone.Off),
            sections = listOf(
                ViewSection("Router on your network", listOf(
                    ViewRow("Router address", orNotSet(ip), mono = true),
                    ViewRow("Subnet mask", mask, mono = true)
                )),
                ViewSection("Handing out addresses (DHCP)", buildList {
                    add(ViewRow("DHCP", pill = onOff(dhcpOn)))
                    if (dhcpOn) {
                        add(ViewRow("First address", ".$start", mono = true))
                        add(ViewRow("How many", limit))
                        add(ViewRow("Lease time", pool?.opt("leasetime") ?: "12h", mono = true))
                    }
                    val fixed = dhcp?.ofType("host")?.size ?: 0
                    add(ViewRow("Devices with a fixed address", "$fixed"))
                })
            ),
            forms = if (pool != null && dhcpOn) listOf(FeatureForm(
                title = "Address pool",
                fields = listOf(
                    FormField("start", "First address (.n)", initial = start, numeric = true),
                    FormField("limit", "How many", initial = limit, numeric = true),
                    FormField("lease", "Lease time (30m, 12h, 2d)", initial = pool.opt("leasetime") ?: "12h")
                ),
                submitLabel = "Save pool"
            ) { v -> FeatureChanges.dhcpPool(pool.id, v["start"].orEmpty(), v["limit"].orEmpty(), v["lease"].orEmpty()) }) else emptyList()
        )
    }

    fun prefixToMask(prefix: Int): String {
        val p = prefix.coerceIn(0, 32)
        val bits = if (p == 0) 0L else (0xFFFFFFFFL shl (32 - p)) and 0xFFFFFFFFL
        return listOf(24, 16, 8, 0).joinToString(".") { ((bits shr it) and 0xFF).toString() }
    }

    fun ipv6(network: UciConfig, dhcp: UciConfig?, dump: JsonObject?): FeatureView {
        val wan6 = network.byId("wan6")
        val wanOn = wan6 != null && !wan6.flag("disabled") && wan6.opt("proto") != "none"
        val pool = dhcp?.sections?.firstOrNull { it.type == "dhcp" && (it.opt("interface") == "lan" || it.id == "lan") }
        val mode = pool?.opt("ra") ?: pool?.opt("dhcpv6") ?: "server"
        val prefix = dump?.get("interface").arr()?.mapNotNull { it.obj() }?.firstOrNull { it["interface"].str() == "wan6" }
            ?.get("ipv6-prefix").arr()?.firstOrNull().obj()?.let { "${it["address"].str()}/${it["mask"].str()}" }
        return FeatureView(
            summary = "The newer internet addresses, if your provider offers them.",
            pill = onOff(wanOn),
            sections = listOf(ViewSection("IPv6", listOfNotNull(
                if (wan6 != null) ViewRow("From your provider", toggle = wanOn, onToggle = { FeatureChanges.ipv6Enabled(!wanOn) }) else ViewRow("From your provider", pill = onOff(wanOn)),
                prefix?.let { ViewRow("Prefix", it, mono = true) },
                ViewRow("For your devices", when (mode) { "server" -> "Router hands out addresses"; "relay" -> "Passed through from provider"; "disabled" -> "Off"; else -> mode }),
                ViewRow("Share size", lan6Assign(network), mono = true)
            )))
        )
    }

    private fun lan6Assign(network: UciConfig): String = network.byId("lan")?.opt("ip6assign")?.let { "/$it" } ?: "Not set"

    fun dualWan(mwan3: UciConfig, network: UciConfig?): FeatureView {
        val ifaces = mwan3.ofType("interface")
        val enabled = ifaces.filter { it.flag("enabled") }
        val members = mwan3.ofType("member")
        val policies = mwan3.ofType("policy")
        return FeatureView(
            summary = "Two internet connections: one takes over when the other fails, or both share the load.",
            pill = if (enabled.size >= 2) ViewPill("Active", PillTone.Ok) else ViewPill("Not set up", PillTone.Off),
            sections = listOf(
                ViewSection("Connections", ifaces.map { s ->
                    ViewRow(s.id, protoLabel(network?.byId(s.id)?.opt("proto")), pill = onOff(s.flag("enabled")))
                }, emptyText = "No connections set up."),
                ViewSection("Rules", members.map { m ->
                    ViewRow(m.id, "${m.opt("interface") ?: "?"} · priority ${m.opt("metric") ?: "1"} · weight ${m.opt("weight") ?: "1"}", mono = true)
                } + policies.map { p -> ViewRow("Policy ${p.id}", p.list("use_member").joinToString(", "), mono = true) },
                    emptyText = "No rules.")
            )
        )
    }

    fun ddns(ddns: UciConfig): FeatureView {
        val services = ddns.ofType("service")
        return FeatureView(
            summary = "A name that keeps pointing at your home even when your internet address changes.",
            pill = if (services.any { it.flag("enabled") }) ON else OFF,
            sections = listOf(ViewSection("Names", services.map { s ->
                val host = orNotSet(s.opt("lookup_host") ?: s.opt("domain"))
                val enabled = s.flag("enabled")
                ViewRow(
                    host,
                    s.opt("service_name") ?: "Custom",
                    sub = "Password never shown",
                    mono = true,
                    toggle = enabled,
                    onToggle = { FeatureChanges.ddnsEnabled(s.id, host, !enabled) }
                )
            }, emptyText = "No dynamic DNS name set up."))
        )
    }

    // ------------------------------------------------------------------ Protection

    fun firewall(fw: UciConfig): FeatureView {
        val zones = fw.ofType("zone")
        val forwards = fw.ofType("forwarding")
        val rules = fw.ofType("rule")
        return FeatureView(
            summary = "Which parts of your network may talk to each other and to the internet.",
            pill = ViewPill("${zones.size} zones", PillTone.Off),
            sections = listOf(
                ViewSection("Zones", zones.map { z ->
                    val name = z.opt("name") ?: z.id
                    ViewRow(
                        name,
                        "in ${(z.opt("input") ?: "ACCEPT").lowercase()} · out ${(z.opt("output") ?: "ACCEPT").lowercase()} · forward ${(z.opt("forward") ?: "REJECT").lowercase()}",
                        sub = z.list("network").joinToString(", ").ifBlank { null },
                        pill = if (z.flag("masq")) ViewPill("NAT", PillTone.Off) else null
                    )
                }),
                ViewSection("Allowed paths", forwards.map { f -> ViewRow("${f.opt("src") ?: "?"} → ${f.opt("dest") ?: "?"}", pill = ON) }, emptyText = "None."),
                ViewSection("Traffic rules", rules.map { r ->
                    val name = r.opt("name") ?: "Unnamed rule"
                    val enabled = r.flag("enabled", default = true)
                    ViewRow(
                        name,
                        "${r.opt("src") ?: "router"} → ${r.opt("dest") ?: "router"}: ${(r.opt("target") ?: "?").lowercase()}",
                        sub = listOfNotNull(r.list("proto").joinToString("/").ifBlank { null }, r.opt("dest_port")?.let { "port $it" }).joinToString(" · ").ifBlank { null },
                        toggle = enabled,
                        onToggle = { Phase4.ruleEnabled(r.id, name, !enabled) },
                        extra = RowAction("Remove") { Phase4.removeRule(r.id, name) }
                    )
                }, emptyText = "No traffic rules.")
            ).let { secs ->
                // Each zone's policies can be changed in the Zones card (phase 4, with automatic undo).
                val zoneForms = zones.map { z ->
                    FeatureForm("Change zone ${z.opt("name") ?: z.id}", listOf(
                        FormField("input", "Into the router (ACCEPT, REJECT, DROP)", initial = z.opt("input") ?: "ACCEPT"),
                        FormField("output", "From the router", initial = z.opt("output") ?: "ACCEPT"),
                        FormField("forward", "Through the router", initial = z.opt("forward") ?: "REJECT"),
                        FormField("masq", "Masquerading (yes or no)", initial = if (z.flag("masq")) "yes" else "no")
                    ), "Save zone") { v -> Phase4.zoneEdit(z, v["input"].orEmpty(), v["output"].orEmpty(), v["forward"].orEmpty(), v["masq"].orEmpty()) }
                }
                listOf(secs[0].copy(moreForms = zoneForms)) + secs.drop(1)
            },
            forms = listOf(FeatureForm("Add a traffic rule", listOf(
                FormField("name", "Name"),
                FormField("src", "From zone", hint = zones.mapNotNull { it.opt("name") }.joinToString(", ")),
                FormField("dest", "To zone (empty = the router itself)"),
                FormField("proto", "Protocol", initial = "tcp udp", hint = "tcp, udp, icmp, all"),
                FormField("dest_port", "Port (optional)", hint = "22 or 8000-8100"),
                FormField("target", "Action", initial = "REJECT", hint = "ACCEPT, REJECT, DROP")
            ), "Add rule") { v -> Phase4.addRule(v, zones.mapNotNull { it.opt("name") }.toSet()) })
        )
    }

    /** Static IPv4 routes (network `route` sections, as LuCI's routes page). Phase 4. */
    fun routes(network: UciConfig): FeatureView {
        val routes = network.ofType("route")
        val ifaces = network.ofType("interface").map { it.id }.filter { it != "loopback" }.toSet()
        return FeatureView(
            summary = "Extra paths to networks reached through another router or VPN.",
            pill = ViewPill("${routes.size} routes", if (routes.isEmpty()) PillTone.Off else PillTone.Ok),
            sections = listOf(ViewSection("Routes", routes.map { r ->
                val target = r.opt("target") ?: "?"
                val iface = r.opt("interface") ?: "?"
                val on = !r.flag("disabled")
                ViewRow(
                    target, "via ${r.opt("gateway") ?: "interface"} on $iface", mono = true,
                    sub = r.opt("metric")?.let { "metric $it" },
                    toggle = on,
                    onToggle = { Phase4.routeEnabled(r.id, target, !on) },
                    extra = RowAction("Remove") { Phase4.removeRoute(iface, target) }
                )
            }, emptyText = "No static routes.")),
            forms = listOf(FeatureForm("Add a route", listOf(
                FormField("interface", "Interface", hint = ifaces.joinToString(", ")),
                FormField("target", "Network (address/bits)", hint = "10.8.0.0/24"),
                FormField("gateway", "Gateway (optional)", hint = "192.168.1.2"),
                FormField("metric", "Metric (optional)", numeric = true)
            ), "Add route") { v -> Phase4.addRoute(v["interface"].orEmpty(), v["target"].orEmpty(), v["gateway"].orEmpty(), v["metric"].orEmpty(), ifaces) })
        )
    }

    private fun isDmzLike(s: UciSection): Boolean {
        val name = s.opt("name")
        return name == "DMZ" || name == "dmz" || s.id == "dmz" || (s.opt("proto") == "all" && s.opt("src_dport") == null)
    }

    fun portForwarding(fw: UciConfig, routerIp: String = ""): FeatureView {
        val rules = fw.ofType("redirect").filter {
            (it.opt("target") ?: "DNAT").equals("DNAT", ignoreCase = true) && !isDmzLike(it) && it.opt("src_dport") != null
        }
        return FeatureView(
            summary = "Lets something on the internet reach one device in your home on one port.",
            pill = ViewPill("${rules.count { it.flag("enabled", default = true) }} on", if (rules.isEmpty()) PillTone.Off else PillTone.Warn),
            sections = listOf(ViewSection("Open ports", rules.map { r ->
                val name = r.opt("name") ?: "Unnamed"
                val enabled = r.flag("enabled", default = true)
                ViewRow(
                    name,
                    "${(r.list("proto").joinToString("/").ifBlank { "tcp/udp" })} ${r.opt("src_dport")} → ${r.opt("dest_ip") ?: "?"}${r.opt("dest_port")?.let { ":$it" } ?: ""}",
                    mono = true,
                    toggle = enabled,
                    onToggle = { FeatureChanges.redirectEnabled(r.id, name, !enabled) },
                    extra = RowAction("Remove") { FeatureChanges.removeRedirect(r.id, name) }
                )
            }, emptyText = "No ports are open to the internet.")),
            forms = listOf(FeatureForm(
                title = "Open a port",
                fields = listOf(
                    FormField("name", "Name", hint = "Camera"),
                    FormField("port", "Outside port", hint = "8443 or 6000-6010"),
                    FormField("ip", "Device address", hint = "192.168.1.70", pick = PickKind.DEVICE_IP),
                    FormField("dport", "Port on the device (optional)", hint = "Same as outside port"),
                    FormField("proto", "Protocol: tcp, udp or both", initial = "both", choices = listOf("tcp", "udp", "both"))
                ),
                submitLabel = "Open port"
            ) { v -> FeatureChanges.addPortForward(v["name"].orEmpty(), v["port"].orEmpty(), v["ip"].orEmpty(), v["dport"].orEmpty(), v["proto"].orEmpty(), routerIp) })
        )
    }

    fun dmz(fw: UciConfig, routerIp: String = ""): FeatureView {
        val rule = fw.ofType("redirect").firstOrNull { it.opt("name") == "DMZ" || it.opt("name") == "dmz" || it.id == "dmz" }
        val on = rule != null && rule.flag("enabled", default = true)
        return FeatureView(
            summary = "Sends everything from the internet to one device. Risky: that device is fully exposed.",
            pill = if (on) ViewPill("On", PillTone.Bad) else OFF,
            sections = listOf(ViewSection("DMZ", listOfNotNull(
                ViewRow(
                    "Exposed device", if (on) orNotSet(rule?.opt("dest_ip")) else "None", mono = on,
                    extra = if (rule != null) RowAction("Turn off") { FeatureChanges.dmzOff(rule.id) } else null
                )
            ))),
            forms = listOf(FeatureForm(
                title = if (on) "Expose a different device" else "Expose one device",
                fields = listOf(FormField("ip", "Device address", initial = rule?.opt("dest_ip").orEmpty(), hint = "192.168.1.9", pick = PickKind.DEVICE_IP)),
                submitLabel = "Expose device"
            ) { v -> FeatureChanges.setDmz(rule?.id, v["ip"].orEmpty(), routerIp) })
        )
    }

    fun upnp(upnpd: UciConfig): FeatureView {
        val g = upnpd.ofType("upnpd").firstOrNull()
        val on = g != null && (g.flag("enabled") || g.flag("enable_upnp"))
        return FeatureView(
            summary = "Lets games and apps open ports on their own.",
            pill = onOff(on),
            sections = listOf(ViewSection("UPnP", listOf(
                if (g != null) ViewRow("UPnP", toggle = on, onToggle = { FeatureChanges.upnpEnabled(g.id, !on) }) else ViewRow("UPnP", "Not set up", pill = OFF),
                ViewRow("Secure mode", yesNo(g?.flag("secure_mode", default = true) ?: true), sub = "Apps may only open ports to themselves")
            )))
        )
    }

    fun parental(fw: UciConfig, nameByMac: Map<String, String>): FeatureView {
        val rules = fw.ofType("rule").filter { (it.opt("name") ?: "").startsWith("Parental") }
        return FeatureView(
            summary = "Internet paused or limited to set hours for chosen devices.",
            pill = ViewPill("${rules.count { it.flag("enabled", default = true) }} active", if (rules.isEmpty()) PillTone.Off else PillTone.Ok),
            sections = listOf(ViewSection("Schedules", rules.map { r ->
                val mac = (r.opt("src_mac") ?: "").uppercase(Locale.ROOT)
                val scheduled = r.opt("start_time") != null || r.opt("stop_time") != null || r.list("weekdays").isNotEmpty()
                val mode = when {
                    !r.flag("enabled", default = true) -> ViewPill("Off", PillTone.Off)
                    scheduled -> ViewPill("Scheduled", PillTone.Ok)
                    else -> ViewPill("Paused", PillTone.Warn)
                }
                val label = nameByMac[mac] ?: mac.ifBlank { "Device" }
                val enabled = r.flag("enabled", default = true)
                ViewRow(
                    label,
                    if (scheduled) "${r.list("weekdays").joinToString(" ").ifBlank { "Every day" }} · ${r.opt("start_time") ?: "?"}–${r.opt("stop_time") ?: "?"}" else "No internet",
                    sub = listOf(mode.text, mac).filter { it.isNotBlank() }.joinToString(" · "),
                    toggle = enabled,
                    onToggle = { FeatureChanges.parentalEnabled(r.id, label, !enabled) },
                    extra = RowAction("Remove") { FeatureChanges.removeParental(r.id, label) }
                )
            }, emptyText = "No device has a schedule.")),
            forms = listOf(
                FeatureForm(
                    title = "Pause a device now",
                    fields = listOf(FormField("device", "Device name or hardware address", hint = "As shown in Devices", pick = PickKind.DEVICE_MAC)),
                    submitLabel = "Pause internet"
                ) { v -> FeatureChanges.parentalPause(v["device"].orEmpty(), nameByMac) },
                FeatureForm(
                    title = "Block on a schedule",
                    fields = listOf(
                        FormField("device", "Device name or hardware address", hint = "As shown in Devices", pick = PickKind.DEVICE_MAC),
                        FormField("days", "Days (empty = every day)", hint = "Mon Tue Wed Thu Fri", choices = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"), multi = true),
                        FormField("from", "From", initial = "21:30"),
                        FormField("to", "To", initial = "06:30")
                    ),
                    submitLabel = "Save schedule"
                ) { v -> FeatureChanges.parentalSchedule(v["device"].orEmpty(), v["days"].orEmpty(), v["from"].orEmpty(), v["to"].orEmpty(), nameByMac) }
            )
        )
    }

    /** adblock / banip packages: installed when their config can be read (UnTangled src/lib/protection.ts). */
    fun protection(adblock: UciConfig?, banip: UciConfig?, adGuardConfigured: Boolean): FeatureView {
        fun row(label: String, cfg: UciConfig?, type: String, option: String, plan: (String, Boolean) -> PlanResult): ViewRow {
            if (cfg == null) return ViewRow(label, "Not installed", pill = ViewPill("Not installed", PillTone.Off))
            val g = cfg.ofType(type).firstOrNull() ?: return ViewRow(label, "Not set up", pill = OFF)
            val enabled = g.flag(option)
            return ViewRow(label, toggle = enabled, onToggle = { plan(g.id, !enabled) })
        }
        return FeatureView(
            summary = "Safety switches for everyone on your network.",
            sections = listOf(ViewSection("Protection", listOf(
                row("Block ads and trackers (adblock)", adblock, "adblock", "adb_enabled", FeatureChanges::adblockEnabled),
                row("Block dangerous sites (banIP)", banip, "banip", "ban_enabled", FeatureChanges::banipEnabled),
                ViewRow("AdGuard Home", if (adGuardConfigured) "Open it under More" else "Set up under More", pill = ViewPill("Separate", PillTone.Off))
            )))
        )
    }

    // ------------------------------------------------------------------------ VPN

    fun vpn(network: UciConfig, openvpn: UciConfig?): FeatureView = vpn(network, openvpn, null, 0)

    /** With [wg] (`luci.wireguard getWgInstances`) the screen also lists live peers (phase 3). */
    fun vpn(network: UciConfig, openvpn: UciConfig?, wg: JsonObject?, nowSeconds: Long): FeatureView {
        val base = vpnBase(network, openvpn)
        val peers = wg?.let { WireGuardPeers.rows(it, nowSeconds) }.orEmpty()
        return if (peers.isEmpty()) base else base.copy(sections = base.sections + ViewSection("WireGuard peers now", peers))
    }

    private fun vpnBase(network: UciConfig, openvpn: UciConfig?): FeatureView {
        val wg = network.ofType("interface").filter { it.opt("proto") == "wireguard" }
        val wgSections = wg.map { w ->
            val peers = network.ofType("wireguard_${w.id}")
            ViewSection("WireGuard · ${w.id}", buildList {
                val on = !w.flag("disabled")
                add(ViewRow("Status", toggle = on, onToggle = { FeatureChanges.wireguardEnabled(w.id, !on) }))
                add(ViewRow("Addresses", w.list("addresses").joinToString(", ").ifBlank { "Not set" }, mono = true))
                w.opt("listen_port")?.let { add(ViewRow("Listens on port", it, mono = true)) }
                add(ViewRow("Private key", "Hidden", sub = "Keys are never shown"))
                peers.forEach { p ->
                    add(ViewRow(
                        p.opt("description") ?: "Peer",
                        p.opt("endpoint_host")?.let { h -> "$h:${p.opt("endpoint_port") ?: "51820"}" } ?: "Connects to this router",
                        sub = p.list("allowed_ips").joinToString(", ").ifBlank { null },
                        mono = true
                    ))
                }
            })
        }
        val ovpn = openvpn?.sections?.filter { it.type == "openvpn" }.orEmpty()
        val ovpnSection = ViewSection("OpenVPN", ovpn.map { o ->
            val role = when {
                o.flag("client") || o.opt("config") != null -> "Client"
                o.opt("server") != null || o.opt("server_bridge") != null -> "Server"
                else -> "Unknown"
            }
            val enabled = o.flag("enabled", default = true)
            ViewRow(o.opt("name") ?: o.id, role, toggle = enabled, onToggle = { FeatureChanges.openvpnEnabled(o.id, o.opt("name") ?: o.id, !enabled) })
        }, emptyText = if (openvpn == null) "OpenVPN is not installed." else "No OpenVPN profiles.")
        return FeatureView(
            summary = "Private tunnels into your home network, or out to another network.",
            pill = ViewPill("${wg.size + ovpn.size} set up", if (wg.isEmpty() && ovpn.isEmpty()) PillTone.Off else PillTone.Ok),
            sections = (if (wgSections.isEmpty()) listOf(ViewSection("WireGuard", emptyList(), emptyText = "No WireGuard tunnel.")) else wgSections) + ovpnSection
        )
    }

    /** `tailscale status --json` fields as read by UnTangled (src/lib/tailscale.ts): BackendState, Self, Peer, CurrentTailnet. */
    fun tailscale(stdout: String?): FeatureView {
        val json = stdout?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }.obj()
            ?: return FeatureView("Tailscale did not report a status.", ViewPill("Unknown", PillTone.Off))
        val state = json["BackendState"].str() ?: "Unknown"
        val self = json["Self"].obj()
        val peers = json["Peer"].obj()?.values?.mapNotNull { it.obj() }.orEmpty()
        val connected = state.equals("Running", ignoreCase = true)
        return FeatureView(
            summary = "A private network that reaches your home from anywhere.",
            pill = if (connected) ViewPill("Connected", PillTone.Ok) else ViewPill(state, PillTone.Warn),
            sections = listOf(
                ViewSection("This router", listOfNotNull(
                    ViewRow("Name", self?.get("HostName").str() ?: "Unknown"),
                    ViewRow("Tailscale address", self?.get("TailscaleIPs").strings().firstOrNull() ?: "None", mono = true),
                    json["CurrentTailnet"].obj()?.get("Name").str()?.let { ViewRow("Network", it) }
                )),
                ViewSection("Machines", peers.map { p ->
                    ViewRow(
                        p["HostName"].str() ?: "Machine",
                        p["TailscaleIPs"].strings().firstOrNull() ?: "",
                        sub = p["OS"].str(),
                        mono = true,
                        pill = if (p["Online"].bool() == true) ViewPill("Online", PillTone.Ok) else ViewPill("Offline", PillTone.Off)
                    )
                }, emptyText = "No other machines.")
            )
        )
    }

    // ------------------------------------------------------------ Speed and usage

    fun qos(sqm: UciConfig): FeatureView {
        val queues = sqm.ofType("queue")
        fun mbps(kbit: String?) = kbit?.toDoubleOrNull()?.let { String.format(Locale.US, "%.0f Mbps", it / 1000.0) } ?: "Not set"
        return FeatureView(
            summary = "Keeps calls and games smooth when the line is busy.",
            pill = onOff(queues.any { it.flag("enabled") }),
            sections = queues.map { q ->
                val enabled = q.flag("enabled")
                ViewSection("Smart Queue · ${q.opt("interface") ?: q.id}", listOf(
                    ViewRow("Status", toggle = enabled, onToggle = { FeatureChanges.qosEnabled(q.id, !enabled) }),
                    ViewRow("Download limit", mbps(q.opt("download"))),
                    ViewRow("Upload limit", mbps(q.opt("upload"))),
                    ViewRow("Method", q.opt("script") ?: "piece_of_cake.qos", mono = true)
                ))
            }.ifEmpty { listOf(ViewSection("Smart Queue", emptyList(), emptyText = "No queue set up.")) },
            forms = queues.firstOrNull()?.let { q ->
                fun mbpsText(k: String?) = k?.toDoubleOrNull()?.let { String.format(Locale.US, "%.0f", it / 1000.0) } ?: ""
                listOf(FeatureForm(
                    title = "Speed limits",
                    fields = listOf(
                        FormField("down", "Download limit (Mbps)", initial = mbpsText(q.opt("download")), numeric = true),
                        FormField("up", "Upload limit (Mbps)", initial = mbpsText(q.opt("upload")), numeric = true)
                    ),
                    submitLabel = "Save limits"
                ) { v -> FeatureChanges.qos(q.id, true, v["down"].orEmpty(), v["up"].orEmpty()) })
            }.orEmpty()
        )
    }

    /** Totals since boot per network device (`network.device status` → statistics.rx_bytes / tx_bytes). */
    fun traffic(deviceStatus: JsonObject, wanDevice: String?): FeatureView {
        data class Dev(val name: String, val rx: Long, val tx: Long)
        val devs = deviceStatus.mapNotNull { (name, v) ->
            val st = v.obj()?.get("statistics").obj() ?: return@mapNotNull null
            Dev(name, st["rx_bytes"].num().toLong(), st["tx_bytes"].num().toLong())
        }.filter { it.rx + it.tx > 0 }.sortedByDescending { it.rx + it.tx }
        val wan = devs.firstOrNull { it.name == wanDevice }
        return FeatureView(
            summary = "Data through each network port since the router started.",
            pill = wan?.let { ViewPill(bytes(it.rx + it.tx), PillTone.Off) },
            sections = listOf(ViewSection("Interfaces", devs.map { d ->
                ViewRow(d.name, bytes(d.rx + d.tx), sub = "↓ ${bytes(d.rx)} · ↑ ${bytes(d.tx)}", mono = true,
                    pill = if (d.name == wanDevice) ViewPill("Internet", PillTone.Ok) else null)
            }, emptyText = "No traffic counters reported.")),
            note = "Live speed is on Home."
        )
    }

    /**
     * vnstat 2 JSON (`vnstat --json`): interfaces[].traffic.day[] / month[] with date and rx/tx bytes,
     * and nlbwmon (`nlbw -c json -g ip,mac -o rx_bytes`): columns + data rows. Fields as read by
     * UnTangled (src/lib/history.ts). Either may be missing: that part says "not installed".
     */
    /** A home-network IPv4 address (RFC 1918) or an IPv6 unique-local / link-local one. */
    private fun isHomeAddress(ip: String): Boolean {
        val p = ip.split('.')
        if (p.size == 4) {
            val a = p[0].toIntOrNull() ?: return false
            val b = p[1].toIntOrNull() ?: return false
            return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
        }
        val l = ip.lowercase(Locale.ROOT)
        return l.startsWith("fd") || l.startsWith("fc") || l.startsWith("fe80:")
    }

    /**
     * Owner feedback: "show which client is using how much of the bandwidth". Two readings of the
     * router's open connections ([first], then [second] taken [seconds] later), each with its byte
     * count for both directions (openwrt/luci luci-base ucode sys.uc conntrack_list, the data behind
     * LuCI's Realtime > Connections page). The growth of each connection, summed per home device
     * that opened it, is that device's speed. A connection first seen in [second] counts in full.
     * Limits: traffic of connections that ended between the readings is missed, and the router's
     * hardware offload, if on, can keep counts from growing.
     */
    fun bandwidth(first: JsonObject, second: JsonObject, seconds: Double, names: Map<String, String>): FeatureView {
        fun conns(o: JsonObject): Map<String, Pair<String, Long>>? {
            val entries = o["result"].arr() ?: return null
            return entries.associate { entry ->
                val c = entry.obj() ?: return null
                val src = c["src"].str() ?: return null
                val bytes = (c["bytes"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 } ?: return null
                val key = listOf(c["layer3"].str(), c["layer4"].str(), src, c["sport"].str(), c["dst"].str(), c["dport"].str()).joinToString("|")
                key to (src to bytes)
            }
        }
        val before = conns(first) ?: return bandwidthUnavailable()
        val after = conns(second) ?: return bandwidthUnavailable()
        // LuCI starts at zero even when the router has no byte counters.
        if (before.values.none { it.second > 0 } && after.values.none { it.second > 0 }) return bandwidthUnavailable()
        val perDevice = mutableMapOf<String, Long>()
        val connCount = mutableMapOf<String, Int>()
        for ((key, v) in after) {
            val (src, bytes) = v
            if (!isHomeAddress(src)) continue
            val grown = bytes - (before[key]?.second ?: 0L)
            connCount[src] = (connCount[src] ?: 0) + 1
            if (grown > 0) perDevice[src] = (perDevice[src] ?: 0L) + grown
        }
        val secs = seconds.coerceAtLeast(0.1)
        val rows = connCount.keys
            .map { ip -> Triple(ip, (perDevice[ip] ?: 0L) / secs, connCount[ip] ?: 0) }
            .sortedByDescending { it.second }
            .map { (ip, rate, n) ->
                val name = names[ip]
                ViewRow(name ?: ip, RealtimeStats.rateText(rate), sub = listOfNotNull(if (name != null) ip else null, if (n == 1) "1 connection" else "$n connections").joinToString(" · "), mono = true)
            }
        val busy = rows.count { it.value != RealtimeStats.rateText(0.0) }
        return FeatureView(
            summary = "Combined speed of each device. Separate download and upload readings are unavailable.",
            pill = ViewPill(if (busy == 1) "1 busy" else "$busy busy", if (busy > 0) PillTone.Ok else PillTone.Off),
            sections = listOf(ViewSection("Devices now", rows, emptyText = "No device has an open internet connection right now.")),
            combinedTraffic = connCount.keys.associateWith { ip -> (perDevice[ip] ?: 0L) / secs }
        )
    }

    fun bandwidthUnavailable(): FeatureView = FeatureView(
        summary = "Traffic data unavailable",
        note = "The router isn’t providing usable traffic counters. The app will keep checking."
    )

    /** Owner feedback 2026-11-18: live download and upload per device (see [BandwidthMeter]). */
    fun liveUsage(
        usage: BandwidthMeter.Usage,
        history: Map<String, List<Pair<Double, Double>>>,
        timedHistory: Map<String, List<TrafficSample>> = emptyMap()
    ): FeatureView {
        val busy = usage.devices.count { it.downBytesPerSec + it.upBytesPerSec >= 1_000 }
        return FeatureView(
            summary = "Live download and upload of every device, from the router's open connections. Updates every 2 seconds.",
            pill = ViewPill(if (busy == 1) "1 busy" else "$busy busy", if (busy > 0) PillTone.Ok else PillTone.Off),
            sections = listOf(ViewSection("Devices now", usage.devices.map { d ->
                ViewRow(d.name, "↓ ${RealtimeStats.rateText(d.downBytesPerSec)}  ↑ ${RealtimeStats.rateText(d.upBytesPerSec)}", sub = d.address, mono = true)
            }, emptyText = "No device has an open internet connection right now.")),
            bandwidth = BandwidthPicture(usage.devices, usage.totalDownBytesPerSec, usage.totalUpBytesPerSec, history, timedHistory)
        )
    }

    fun usage(vnstatOut: String?, nlbwOut: String?, wanDevice: String?, nameByMac: Map<String, String>): FeatureView {
        val sections = mutableListOf<ViewSection>()
        var pill: ViewPill? = null
        val vn = vnstatOut?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }.obj()
        if (vn == null) {
            sections += ViewSection("Internet usage", emptyList(), emptyText = "Needs the vnstat package on the router.")
        } else {
            val ifaces = vn["interfaces"].arr()?.mapNotNull { it.obj() }.orEmpty()
            val pick = ifaces.firstOrNull { it["name"].str() == wanDevice } ?: ifaces.firstOrNull { it["name"].str() != "lo" }
            val t = pick?.get("traffic").obj()
            val days = (t?.get("day") ?: t?.get("days")).arr()?.mapNotNull { it.obj() }.orEmpty()
            val months = (t?.get("month") ?: t?.get("months")).arr()?.mapNotNull { it.obj() }.orEmpty()
            fun label(o: JsonObject, withDay: Boolean): String {
                val d = o["date"].obj() ?: o
                val m = d["month"].num().toInt()
                return if (withDay) String.format(Locale.US, "%02d/%02d", d["day"].num().toInt(), m) else "${d["year"].num().toInt()}-${String.format(Locale.US, "%02d", m)}"
            }
            fun total(o: JsonObject) = o["rx"].num().toLong() + o["tx"].num().toLong()
            val today = days.lastOrNull()
            val month = months.lastOrNull()
            pill = today?.let { ViewPill("Today ${bytes(total(it))}", PillTone.Off) }
            sections += ViewSection("Internet usage · ${pick?.get("name").str() ?: "?"}", listOfNotNull(
                today?.let { ViewRow("Today", bytes(total(it)), sub = "↓ ${bytes(it["rx"].num().toLong())} · ↑ ${bytes(it["tx"].num().toLong())}", mono = true) },
                month?.let { ViewRow("This month", bytes(total(it)), sub = "↓ ${bytes(it["rx"].num().toLong())} · ↑ ${bytes(it["tx"].num().toLong())}", mono = true) }
            ))
            sections += ViewSection("Last days", days.takeLast(7).reversed().map { ViewRow(label(it, true), bytes(total(it)), mono = true) }, emptyText = "No daily history yet.")
        }
        val data = parseDeviceUsage(nlbwOut)
        if (data == null) {
            sections += ViewSection("By device", emptyList(), emptyText = "Needs the nlbwmon package on the router.")
        } else {
            val rows = data.filter { it.total > 0 }.sortedByDescending { it.total }
            sections += ViewSection("By device", rows.take(15).map { row ->
                val name = nameByMac[row.mac] ?: row.ip.ifBlank { row.mac }
                ViewRow(name, bytes(row.total), sub = "${row.ip} · ↓ ${bytes(row.rx)} · ↑ ${bytes(row.tx)}", mono = true)
            }, emptyText = "No per-device data yet.")
        }
        val totals = data.orEmpty().filter { deviceMac.matches(it.mac) }.groupBy { it.mac }.mapValues { (_, rows) ->
            rows.fold(0L) { sum, row -> sum + row.total.coerceAtMost(Long.MAX_VALUE - sum) }
        }
        return FeatureView("How much data your internet and your devices have used.", pill, sections, deviceTotals = totals)
    }

    private val deviceMac = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")
    private data class DeviceUsage(val mac: String, val ip: String, val rx: Long, val tx: Long) {
        val total: Long get() = rx + tx.coerceAtMost(Long.MAX_VALUE - rx)
    }

    private fun parseDeviceUsage(output: String?): List<DeviceUsage>? {
        val report = output?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }.obj() ?: return null
        val columns = report["columns"].strings()
        val rx = columns.indexOf("rx_bytes")
        val tx = columns.indexOf("tx_bytes")
        if (rx < 0 || tx < 0) return null
        val mac = columns.indexOf("mac")
        val ip = columns.indexOf("ip")
        return report["data"].arr()?.mapNotNull { entry ->
            val row = entry.arr() ?: return@mapNotNull null
            val received = (row.getOrNull(rx) as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 } ?: return@mapNotNull null
            val sent = (row.getOrNull(tx) as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 } ?: return@mapNotNull null
            DeviceUsage(row.getOrNull(mac).str().orEmpty().uppercase(Locale.ROOT), row.getOrNull(ip).str().orEmpty(), received, sent)
        }
    }

    // --------------------------------------------------------------------- System

    fun admin(system: UciConfig, uhttpd: UciConfig?): FeatureView = admin(system, uhttpd, null)

    /** [zones]: `luci getTimezones` (zone name to time-zone string) for the time zone form (phase 4). */
    fun admin(system: UciConfig, uhttpd: UciConfig?, zones: Map<String, String>?): FeatureView {
        val s = system.ofType("system").firstOrNull()
        val main = uhttpd?.byId("main")
        return FeatureView(
            summary = "The router's name, clock and how its web pages are served.",
            sections = listOf(
                ViewSection("Router", listOf(
                    ViewRow("Name", s?.opt("hostname") ?: "OpenWrt"),
                    ViewRow("Time zone", s?.opt("zonename") ?: s?.opt("timezone") ?: "UTC"),
                    ViewRow("Admin password", "Hidden", sub = "Never shown")
                )),
                ViewSection("Web pages", listOfNotNull(
                    main?.let { ViewRow("HTTP", it.list("listen_http").joinToString(", ").ifBlank { "Off" }, mono = true) },
                    main?.let { ViewRow("HTTPS", it.list("listen_https").joinToString(", ").ifBlank { "Off" }, mono = true) },
                    main?.let { ViewRow("Send HTTP to HTTPS", yesNo(it.flag("redirect_https"))) }
                ), emptyText = "Web server settings not readable.")
            ),
            forms = (if (s != null) listOf(FeatureForm(
                title = "Rename the router",
                fields = listOf(FormField("name", "Router name", initial = s.opt("hostname") ?: "OpenWrt")),
                submitLabel = "Save name"
            ) { v -> FeatureChanges.hostname(s.id, s.opt("hostname") ?: "OpenWrt", v["name"].orEmpty()) }) +
                listOfNotNull(zones?.takeIf { it.isNotEmpty() }?.let { z ->
                    FeatureForm("Change the time zone", listOf(FormField("zone", "Time zone", initial = s.opt("zonename") ?: "UTC", hint = "Region/City, for example Asia/Kolkata")), "Save time zone") { v ->
                        Phase4.timeZone(s.id, s.opt("zonename"), v["zone"].orEmpty(), z)
                    }
                }) else emptyList()) + listOf(
                FeatureForm("Change the router password", listOf(
                    FormField("new", "New password", secret = true),
                    FormField("repeat", "New password again", secret = true),
                    FormField("confirm", "Type CHANGE to confirm")
                ), "Change password") { v -> Phase4.password(v["new"].orEmpty(), v["repeat"].orEmpty(), v["confirm"].orEmpty()) },
                FeatureForm("Restart the router", listOf(FormField("confirm", "Type RESTART to confirm")), "Restart") { v -> Phase4.reboot(v["confirm"].orEmpty()) }
            )
        )
    }

    private val NOTABLE = listOf(
        "luci", "untangled", "untangled-full", "adguardhome", "tailscale", "vnstat", "nlbwmon", "sqm-scripts", "mwan3",
        "ddns-scripts", "miniupnpd", "etherwake", "wireguard-tools", "openvpn-openssl", "adblock", "banip", "minidlna", "ksmbd-server"
    )

    /** Package names from `apk list -I` ("name-1.2-r3 arch {origin} (lic) [installed]") or `opkg list-installed` ("name - 1.2"). */
    fun parsePackages(output: String): List<String> = output.lines().mapNotNull { line ->
        val l = line.trim()
        if (l.isEmpty()) return@mapNotNull null
        if (" - " in l) return@mapNotNull l.substringBefore(" - ").trim()
        val token = l.substringBefore(' ')
        Regex("^(.+?)-\\d").find(token)?.groupValues?.get(1) ?: token
    }.distinct()

    fun software(output: String?): FeatureView {
        if (output == null) return FeatureView("The router did not list its packages.", ViewPill("Unknown", PillTone.Off))
        val names = parsePackages(output).toSet()
        return FeatureView(
            summary = "Programs installed on the router.",
            pill = ViewPill("${names.size} packages", PillTone.Off),
            sections = listOf(ViewSection("Useful extras", NOTABLE.map { n ->
                ViewRow(n, pill = if (n in names) ViewPill("Installed", PillTone.Ok) else ViewPill("Not installed", PillTone.Off))
            }))
        )
    }

    private val LEVELS = listOf("emerg", "alert", "crit", "err", "warning", "notice", "info", "debug")

    /** `log read {lines, stream:false}` → `{"log":[{time, priority, source, msg}]}` (UnTangled src/lib/net.ts getLog). */
    // ---------------------------------------------------------------- M10a: system health

    /**
     * `system info` (procd system.c): memory in bytes, root/tmp in KiB, load = raw Linux sysinfo
     * loads, shown divided by 65536 (SI_LOAD_SHIFT 16: UNVERIFIED here; compare with LuCI's status page).
     */
    fun loadText(loads: List<Double>): String =
        loads.take(3).joinToString(" · ") { String.format(java.util.Locale.ROOT, "%.2f", it / 65536.0) }

    fun uptimeText(seconds: Long): String {
        val d = seconds / 86_400
        val h = (seconds % 86_400) / 3_600
        val m = (seconds % 3_600) / 60
        return when {
            d > 0 -> "$d d $h h"
            h > 0 -> "$h h $m min"
            else -> "$m min"
        }
    }

    private fun pct(used: Double, total: Double): Int = if (total <= 0.0) 0 else Math.round(used / total * 100.0).toInt()
    private fun mb(bytes: Double): Long = Math.round(bytes / 1_048_576.0)

    fun health(info: JsonObject, cpuPct: Int?): FeatureView {
        val mem = info["memory"].obj()
        val memTotal = mem?.get("total").num()
        val memAvail = mem?.get("available")?.let { (it as? JsonPrimitive)?.doubleOrNull } ?: mem?.get("free").num()
        val memUsed = (memTotal - memAvail).coerceAtLeast(0.0)
        val memPct = pct(memUsed, memTotal)

        fun storage(key: String): Pair<Int, String>? {
            val o = info[key].obj() ?: return null
            val total = o["total"].num()
            if (total <= 0.0) return null
            val used = o["used"].num()
            return pct(used, total) to "${mb(used * 1024)} MB of ${mb(total * 1024)} MB in use"
        }
        val root = storage("root")
        val tmp = storage("tmp")
        val swap = info["swap"].obj()?.let { s ->
            val total = s["total"].num()
            if (total <= 0.0) null else pct(total - s["free"].num(), total)
        }
        fun bar(p: Int) = when {
            p >= 90 -> ViewPill("High", PillTone.Bad)
            p >= 75 -> ViewPill("Busy", PillTone.Warn)
            else -> null
        }

        val rows = buildList {
            add(ViewRow("Processor", cpuPct?.let { "$it%" } ?: "Measuring...", sub = "Share of time the processor is busy", pill = cpuPct?.let { bar(it) }))
            add(ViewRow("Memory", "$memPct%", sub = "${mb(memUsed)} MB of ${mb(memTotal)} MB in use", pill = bar(memPct)))
            root?.let { (p, s) -> add(ViewRow("Storage", "$p%", sub = s, pill = bar(p))) }
            add(ViewRow("Running for", uptimeText(info["uptime"].num().toLong())))
        }
        val details = buildList {
            info["load"].arr()?.let { l -> add(ViewRow("Load (1, 5, 15 min)", loadText(l.map { it.num() }), mono = true, detail = true)) }
            tmp?.let { (p, s) -> add(ViewRow("Temporary files", "$p%", sub = s, detail = true)) }
            swap?.let { add(ViewRow("Swap", "$it%", detail = true)) }
        }
        val low = memPct >= 90 || (root?.first ?: 0) >= 90
        return FeatureView(
            summary = "How hard the router is working. Updates every few seconds while open.",
            pill = if (low) ViewPill("Running low", PillTone.Warn) else ViewPill("Healthy", PillTone.Ok),
            sections = listOf(ViewSection("Now", rows + details)),
            note = if (low) "Memory or storage is nearly full. Restarting the router or removing unused packages frees space." else null
        )
    }

    // ---------------------------------------------------------------- M10b: services

    /** `rc list` (rpcd rc.c): start/stop order, enabled always, running only for procd scripts. */
    fun services(reply: JsonObject): FeatureView {
        val list = reply.mapNotNull { (name, el) ->
            val o = el.obj() ?: return@mapNotNull null
            RouterService(name, o["start"]?.let { it.num().toInt() } ?: 999, o["enabled"].bool() == true, o["running"].bool())
        }.sortedWith(compareBy({ it.start }, { it.name }))
        return services(list)
    }

    fun services(list: List<RouterService>): FeatureView {
        val stopped = list.count { it.enabled && it.running == false }
        val rows = list.map { s ->
            ViewRow(
                s.name,
                // Phase 4: start/stop (switch), restart (button); both confirmed, one-shot.
                toggle = s.running,
                onToggle = s.running?.let { on -> { Phase4.serviceAction(s.name, if (on) "stop" else "start") } },
                extra = RowAction("Restart") { Phase4.serviceAction(s.name, "restart") },
                sub = if (s.enabled) "Starts at boot" else "Does not start at boot",
                mono = false,
                pill = when (s.running) {
                    true -> ViewPill("Running", PillTone.Ok)
                    false -> if (s.enabled) ViewPill("Stopped", PillTone.Bad) else ViewPill("Stopped", PillTone.Off)
                    null -> null
                }
            )
        }
        return FeatureView(
            summary = "${list.size} services" + if (stopped > 0) ", $stopped stopped that should run." else ", all that should run are running.",
            pill = if (stopped > 0) ViewPill("$stopped stopped", PillTone.Warn) else ViewPill("All running", PillTone.Ok),
            sections = listOf(ViewSection("Services", rows, emptyText = "The router reported no services.")),
            note = "Services the app or your network depend on (web server, RPC, network, DNS/DHCP, firewall) can be restarted but not stopped here. Services without a switch do not report whether they run.",
            forms = listOf(FeatureForm("Start at boot", listOf(FormField("name", "Service name"), FormField("boot", "yes or no", initial = "yes")), "Save") { v ->
                when (v["boot"].orEmpty().trim().lowercase()) {
                    "yes" -> Phase4.serviceAction(v["name"].orEmpty().trim(), "enable")
                    "no" -> Phase4.serviceAction(v["name"].orEmpty().trim(), "disable")
                    else -> PlanResult.Invalid("Type yes or no.")
                }
            }),
            services = list
        )
    }

    // ---------------------------------------------------------------- Phase 3: interfaces, kernel log

    /** `luci-rpc getNetworkDevices` (stats, flags, link) + `network.interface dump`. Loopback hidden. */
    fun interfaces(devices: JsonObject, dump: JsonObject?): FeatureView {
        val list = dump?.get("interface").arr()?.mapNotNull { it.obj() }.orEmpty()
            .filter { it["interface"].str() != "loopback" }
        val logical = list.map { i ->
            val name = i["interface"].str().orEmpty()
            val dev = i["l3_device"].str() ?: i["device"].str()
            val addr = i["ipv4-address"].arr()?.mapNotNull { it.obj() }?.firstOrNull()?.let { "${it["address"].str()}/${it["mask"].str()}" }
            val st = dev?.let { devices[it].obj()?.get("stats").obj() }
            ViewRow(
                label = name,
                value = protoLabel(i["proto"].str().orEmpty()),
                sub = listOfNotNull(dev, addr, st?.let { "↓ ${bytesText(it["rx_bytes"].num())} ↑ ${bytesText(it["tx_bytes"].num())}" }).joinToString(" · "),
                mono = false,
                pill = onOff(i["up"].bool() == true)
            )
        }
        val ports = devices.mapNotNull { (name, el) ->
            val d = el.obj() ?: return@mapNotNull null
            val link = d["link"].obj() ?: return@mapNotNull null
            if (d["flags"].obj()?.get("loopback").bool() == true) return@mapNotNull null
            val carrier = link["carrier"].bool() == true
            val st = d["stats"].obj()
            ViewRow(
                label = d["name"].str() ?: name,
                value = if (carrier) "${link["speed"].num().toLong()} Mb/s ${link["duplex"].str().orEmpty()}".trim() else "Unplugged",
                sub = st?.let { "↓ ${bytesText(it["rx_bytes"].num())} ↑ ${bytesText(it["tx_bytes"].num())}" },
                pill = if (carrier) ON else OFF
            )
        }.sortedBy { it.label }
        // Owner feedback: a picture of the ports and links (same data as the rows above).
        val wanDevices = list.filter { it["interface"].str().orEmpty().startsWith("wan") }
            .flatMap { listOfNotNull(it["device"].str(), it["l3_device"].str()) }.toSet()
        val wireless = Regex("^(phy\\d+-|wlan\\d|wl\\d|ath\\d|ra\\d|rai\\d|apcli)")
        val virtual = Regex("^(tailscale|wg|tun|tap|ppp|gre|ip6|sit|veth|ifb|docker|bond|vlan)")
        val picPorts = devices.mapNotNull { (key, el) ->
            val d = el.obj() ?: return@mapNotNull null
            if (d["flags"].obj()?.get("loopback").bool() == true) return@mapNotNull null
            val name = d["name"].str() ?: key
            if (name == "lo") return@mapNotNull null
            val link = d["link"].obj()
            val st = d["stats"].obj()
            val kind = when {
                name in wanDevices -> PortKind.INTERNET
                name.startsWith("br-") -> PortKind.BRIDGE
                wireless.containsMatchIn(name) -> PortKind.WIRELESS
                virtual.containsMatchIn(name) || name.contains('.') -> PortKind.OTHER
                else -> PortKind.LAN
            }
            val speed = link?.get("speed").num().toInt().takeIf { it > 0 && (kind == PortKind.INTERNET || kind == PortKind.LAN) }
            NetPort(
                name = name,
                kind = kind,
                connected = link?.get("carrier").bool() == true,
                speedMbps = speed,
                duplex = link?.get("duplex").str()?.takeIf { speed != null },
                rxBytes = st?.get("rx_bytes").num().toLong(),
                txBytes = st?.get("tx_bytes").num().toLong()
            )
        }.sortedWith(compareBy<NetPort>({ it.kind.ordinal }, { it.name }))
        val picLinks = list.map { i ->
            val dev = i["l3_device"].str() ?: i["device"].str()
            val st = dev?.let { devices[it].obj()?.get("stats").obj() }
            NetLink(
                name = i["interface"].str().orEmpty(),
                protocol = protoLabel(i["proto"].str().orEmpty()),
                up = i["up"].bool() == true,
                device = dev,
                address = i["ipv4-address"].arr()?.mapNotNull { it.obj() }?.firstOrNull()?.let { "${it["address"].str()}/${it["mask"].str()}" },
                rxBytes = st?.get("rx_bytes")?.num()?.toLong(),
                txBytes = st?.get("tx_bytes")?.num()?.toLong(),
                uptimeSec = i["uptime"]?.num()?.toLong()?.takeIf { it > 0 }
            )
        }
        return FeatureView(
            summary = "${logical.count { it.pill == ON }} of ${logical.size} interfaces up.",
            sections = listOf(
                ViewSection("Interfaces", logical, "No interfaces reported."),
                ViewSection("Ports", ports, "No ports reported.")
            ),
            network = NetworkPicture(picLinks, picPorts)
        )
    }

    /** `dmesg -r` lines: <priority>[seconds] text. Newest first, last 200. */
    fun kernelLog(out: String): FeatureView {
        val re = Regex("^<(\\d+)>\\[\\s*([0-9.]+)\\]\\s?(.*)$")
        val entries = out.lines().mapNotNull { re.find(it.trimEnd()) }.map { m ->
            Triple(m.groupValues[1].toInt() and 7, m.groupValues[2].toDoubleOrNull() ?: 0.0, m.groupValues[3])
        }.takeLast(200).reversed()
        val errors = entries.count { it.first <= 3 }
        fun since(s: Double) = when {
            s < 60 -> String.format(java.util.Locale.ROOT, "%.1f s after start", s)
            s < 3600 -> "${(s / 60).toLong()} min after start"
            else -> "${(s / 3600).toLong()} h after start"
        }
        return FeatureView(
            summary = "Messages from the router's system core (drivers, Wi-Fi chips, storage).",
            pill = if (errors > 0) ViewPill("$errors errors", PillTone.Warn) else ViewPill("No errors", PillTone.Ok),
            sections = listOf(ViewSection("Messages", entries.map { (lvl, t, msg) ->
                ViewRow(msg, sub = since(t), mono = true, pill = when {
                    lvl <= 3 -> ViewPill("Error", PillTone.Bad)
                    lvl == 4 -> ViewPill("Warning", PillTone.Warn)
                    else -> null
                })
            }, "No messages."))
        )
    }

    fun log(reply: JsonObject?): FeatureView {
        val entries = reply?.get("log").arr()?.mapNotNull { it.obj() }.orEmpty()
        val errors = entries.count { it["priority"].num().toInt() <= 3 }
        return FeatureView(
            summary = "The router's recent messages, newest first.",
            pill = if (errors > 0) ViewPill("$errors errors", PillTone.Bad) else ViewPill("No errors", PillTone.Ok),
            sections = listOf(ViewSection("Messages", entries.takeLast(100).reversed().map { e ->
                val p = e["priority"].num().toInt()
                val ms = e["time"].num().let { if (it > 1e11) it.toLong() else (it * 1000).toLong() }
                ViewRow(
                    e["msg"].str() ?: "",
                    "",
                    sub = java.text.SimpleDateFormat("dd MMM HH:mm:ss", Locale.US).format(java.util.Date(ms)),
                    mono = true,
                    pill = when {
                        p <= 3 -> ViewPill(LEVELS.getOrElse(p) { "err" }, PillTone.Bad)
                        p == 4 -> ViewPill("warning", PillTone.Warn)
                        else -> null
                    }
                )
            }, emptyText = "No messages."))
        )
    }

    /** Health checks in plain words, from data the other screens already read. */
    fun doctor(dump: JsonObject?, info: JsonObject?, wifi: JsonObject?, logReply: JsonObject?): FeatureView {
        val rows = mutableListOf<ViewRow>()
        val wan = dump?.let { HomeParsers.parseWan(it) }
        rows += when {
            wan == null -> ViewRow("Internet", "Could not read the internet status.", pill = ViewPill("Check", PillTone.Warn))
            !wan.up -> ViewRow("Internet", "The internet connection is down.", pill = ViewPill("Problem", PillTone.Bad))
            wan.ipv4 == null -> ViewRow("Internet", "Connected but no address yet.", pill = ViewPill("Problem", PillTone.Bad))
            else -> ViewRow("Internet", "Connected.", pill = ViewPill("OK", PillTone.Ok))
        }
        val mem = info?.let { HomeParsers.parseMemUsedPct(it) }
        rows += when {
            mem == null -> ViewRow("Memory", "Could not read memory use.", pill = ViewPill("Check", PillTone.Warn))
            mem >= 90 -> ViewRow("Memory", "$mem% used. Services may restart when busy.", pill = ViewPill("High", PillTone.Warn))
            else -> ViewRow("Memory", "$mem% used.", pill = ViewPill("OK", PillTone.Ok))
        }
        rows += if (wifi == null) {
            ViewRow("Wi-Fi", "Could not read the Wi-Fi status.", pill = ViewPill("Check", PillTone.Warn))
        } else {
            val radios = parseWireless(wifi).first
            if (radios.isEmpty()) ViewRow("Wi-Fi", "No Wi-Fi radios (wired only).", pill = ViewPill("OK", PillTone.Ok))
            else ViewRow("Wi-Fi", "${radios.count { it.up }} of ${radios.size} radios answering.", pill = ViewPill(if (radios.all { it.up || it.disabled }) "OK" else "Check", if (radios.all { it.up || it.disabled }) PillTone.Ok else PillTone.Warn))
        }
        val errors = logReply?.get("log").arr()?.count { (it.obj()?.get("priority").num().toInt()) <= 3 }
        rows += when {
            errors == null -> ViewRow("System log", "Could not read the log.", pill = ViewPill("Check", PillTone.Warn))
            errors > 0 -> ViewRow("System log", "$errors error messages recently.", pill = ViewPill("Look", PillTone.Warn))
            else -> ViewRow("System log", "No error messages recently.", pill = ViewPill("OK", PillTone.Ok))
        }
        val problems = rows.count { it.pill?.tone == PillTone.Bad }
        return FeatureView(
            summary = "A quick health check of the router.",
            pill = if (problems > 0) ViewPill("$problems problems", PillTone.Bad) else ViewPill("Healthy", PillTone.Ok),
            sections = listOf(ViewSection("Checks", rows))
        )
    }

    fun usb(mounts: String?, minidlna: UciConfig?, ksmbd: UciConfig?, samba4: UciConfig?): FeatureView {
        val drives = mounts.orEmpty().lines().mapNotNull { l ->
            val parts = l.split(' ')
            if (parts.size < 3) return@mapNotNull null
            val (dev, mp, fs) = Triple(parts[0], parts[1], parts[2])
            if (!dev.startsWith("/dev/sd") && !dev.startsWith("/dev/mmcblk") && !mp.startsWith("/mnt")) return@mapNotNull null
            if (mp == "/" || mp.startsWith("/rom") || mp.startsWith("/overlay")) return@mapNotNull null
            ViewRow(mp, fs, sub = dev, mono = true)
        }
        val dlna = minidlna?.ofType("minidlna")?.firstOrNull()
        val smb = (ksmbd ?: samba4)?.sections?.firstOrNull { it.type == "share" }
        return FeatureView(
            summary = "Drives plugged into the router, and sharing them on your network.",
            pill = ViewPill("${drives.size} drives", if (drives.isEmpty()) PillTone.Off else PillTone.Ok),
            sections = listOf(
                ViewSection("Drives", drives, emptyText = "No USB drive found."),
                ViewSection("Sharing", listOf(
                    ViewRow("File sharing", smb?.opt("path") ?: if (ksmbd == null && samba4 == null) "Not installed" else "Off",
                        pill = if (smb != null) ON else OFF, mono = smb != null),
                    if (dlna != null) {
                        val enabled = dlna.flag("enabled")
                        ViewRow("Media for TVs (DLNA)", dlna.list("media_dir").firstOrNull() ?: "No folder set", mono = true,
                            toggle = enabled, onToggle = { FeatureChanges.dlnaEnabled(dlna.id, !enabled) })
                    } else {
                        ViewRow("Media for TVs (DLNA)", if (minidlna == null) "Not installed" else "Off", pill = OFF)
                    }
                ))
            )
        )
    }
}
