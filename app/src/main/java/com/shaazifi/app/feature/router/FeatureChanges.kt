package com.shaazifi.app.feature.router

import java.util.Locale

sealed interface PlanResult {
    data class Ready(val plan: ChangePlan) : PlanResult
    data class Invalid(val message: String) : PlanResult
}

/**
 * Change plans for the feature screens. Pure and unit-tested (FeatureChangesTest); nothing
 * here talks to the router. Settings and follow-up service commands are the ones UnTangled
 * uses for the same actions (src/lib/firewall.ts, parental.ts, upnp.ts, ddns.ts, qos.ts,
 * guest.ts; read as facts). Every plan runs through [SafeChangeRunner].
 */
object FeatureChanges {

    private fun ready(p: ChangePlan) = PlanResult.Ready(p)
    private fun invalid(m: String) = PlanResult.Invalid(m)
    private fun onOff(b: Boolean) = if (b) "On" else "Off"

    private fun ipv4(text: String): List<Int>? {
        val parts = text.trim().split('.')
        if (parts.size != 4) return null
        return parts.map { p -> p.toIntOrNull()?.takeIf { it in 0..255 && p == it.toString() } ?: return null }
    }

    /** A device address for forwarding: private IPv4, not the router, not .0/.255. Returns an error or null. */
    private fun checkDeviceIp(ip: String, routerIp: String): String? {
        val n = ipv4(ip) ?: return "Enter the device's address, like 192.168.1.50."
        val private = n[0] == 10 || (n[0] == 172 && n[1] in 16..31) || (n[0] == 192 && n[1] == 168)
        if (!private) return "Use a device address on your home network, like 192.168.1.50."
        if (ip.trim() == routerIp) return "That is the router's own address."
        if (n[3] == 0 || n[3] == 255) return "Addresses ending in .0 or .255 can't be used for a device."
        return null
    }

    /** "8443" or "6000-6010", each 1..65535, range low to high. Returns the cleaned text or null. */
    private fun port(text: String): String? {
        val t = text.trim()
        val bits = t.split('-')
        if (bits.size !in 1..2) return null
        val nums = bits.map { it.trim().toIntOrNull()?.takeIf { p -> p in 1..65535 } ?: return null }
        if (nums.size == 2 && nums[0] > nums[1]) return null
        return nums.joinToString("-")
    }

    // ---------------------------------------------------------------- Guest Wi-Fi

    fun guestEnabled(section: String, ssid: String, on: Boolean): PlanResult = ready(ChangePlan(
        title = if (on) "Turn on the guest network?" else "Turn off the guest network?",
        lines = listOf(ChangeLine(ssid.ifBlank { "Guest network" }, onOff(!on), onOff(on))),
        ops = listOf(UciOp.Set("wireless", section, mapOf("disabled" to if (on) "0" else "1"))),
        note = "The router restarts Wi-Fi on that radio, so Wi-Fi may drop for a few seconds. The app waits for it before keeping the change."
    ))

    fun wifiEnabled(section: String, ssid: String, on: Boolean): PlanResult = ready(ChangePlan(
        title = "Turn ${onOff(on).lowercase()} $ssid?",
        lines = listOf(ChangeLine(ssid, onOff(!on), onOff(on))),
        ops = listOf(UciOp.Set("wireless", section, mapOf("disabled" to if (on) "0" else "1"))),
        note = "The radio may reconnect before the app can confirm the change."
    ))

    // ------------------------------------------------------------ Port forwarding

    fun addPortForward(name: String, externalPort: String, deviceIp: String, internalPort: String, proto: String, routerIp: String): PlanResult {
        val cleanName = name.trim().take(40)
        if (cleanName.isBlank()) return invalid("Give the rule a name, like Camera.")
        val ext = port(externalPort) ?: return invalid("The outside port must be a number from 1 to 65535 (or a range like 6000-6010).")
        val int = if (internalPort.isBlank()) ext else port(internalPort) ?: return invalid("The device port must be a number from 1 to 65535.")
        checkDeviceIp(deviceIp, routerIp)?.let { return invalid(it) }
        val protoValue: Any = when (proto.trim().lowercase(Locale.ROOT)) {
            "tcp" -> "tcp"
            "udp" -> "udp"
            "both", "tcp udp", "tcp/udp", "" -> listOf("tcp", "udp")
            else -> return invalid("Choose tcp, udp or both.")
        }
        val protoText = (protoValue as? List<*>)?.joinToString("/") ?: protoValue.toString()
        return ready(ChangePlan(
            title = "Open port $ext to ${deviceIp.trim()}?",
            lines = listOf(ChangeLine("$cleanName ($protoText)", "Closed", "$ext → ${deviceIp.trim()}:$int")),
            ops = listOf(UciOp.Add("firewall", "redirect", mapOf(
                "name" to cleanName,
                "src" to "wan",
                "dest" to "lan",
                "target" to "DNAT",
                "proto" to protoValue,
                "src_dport" to ext,
                "dest_ip" to deviceIp.trim(),
                "dest_port" to int
            ))),
            reloadFirewall = true,
            note = "Anyone on the internet can then reach this device on this port. Only do this for something you trust."
        ))
    }

    fun redirectEnabled(section: String, name: String, on: Boolean): PlanResult = ready(ChangePlan(
        title = if (on) "Open $name again?" else "Close $name?",
        lines = listOf(ChangeLine(name, onOff(!on), onOff(on))),
        ops = listOf(UciOp.Set("firewall", section, mapOf("enabled" to if (on) "1" else "0"))),
        reloadFirewall = true
    ))

    fun removeRedirect(section: String, name: String): PlanResult = ready(ChangePlan(
        title = "Remove $name?",
        lines = listOf(ChangeLine(name, "Open", "Removed")),
        ops = listOf(UciOp.Delete("firewall", section)),
        reloadFirewall = true
    ))

    // ------------------------------------------------------------------------ DMZ

    fun setDmz(existingSection: String?, deviceIp: String, routerIp: String): PlanResult {
        checkDeviceIp(deviceIp, routerIp)?.let { return invalid(it) }
        val ip = deviceIp.trim()
        val op = if (existingSection != null) {
            UciOp.Set("firewall", existingSection, mapOf("dest_ip" to ip, "enabled" to "1"))
        } else {
            UciOp.Add("firewall", "redirect", mapOf(
                "name" to "DMZ", "src" to "wan", "dest" to "lan", "proto" to "all", "target" to "DNAT", "dest_ip" to ip
            ))
        }
        return ready(ChangePlan(
            title = "Expose $ip to the whole internet?",
            lines = listOf(ChangeLine("DMZ device", "None", ip)),
            ops = listOf(op),
            reloadFirewall = true,
            note = "Everything from the internet that no other rule handles goes to this device. Use it only for a device built for that, like a game console."
        ))
    }

    fun dmzOff(section: String?): PlanResult {
        val id = section ?: return invalid("DMZ is already off.")
        return ready(ChangePlan(
            title = "Turn off DMZ?",
            lines = listOf(ChangeLine("DMZ", "On", "Off")),
            ops = listOf(UciOp.Delete("firewall", id)),
            reloadFirewall = true
        ))
    }

    // ------------------------------------------------------------ Parental controls

    private val MAC = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
    private val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val TIME = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")

    /** Finds a device by hardware address or by its name in [nameByMac]. */
    private fun findDevice(input: String, nameByMac: Map<String, String>): Pair<String, String>? {
        val t = input.trim()
        if (MAC.matches(t)) {
            val mac = t.uppercase(Locale.ROOT)
            return mac to (nameByMac[mac] ?: mac)
        }
        val hit = nameByMac.entries.firstOrNull { it.value.equals(t, ignoreCase = true) } ?: return null
        return hit.key to hit.value
    }

    private fun parentalBase(mac: String, name: String) = mapOf(
        "name" to "Parental: $name", "src" to "lan", "dest" to "wan", "src_mac" to mac,
        "proto" to "all", "target" to "REJECT", "enabled" to "1"
    )

    fun parentalPause(device: String, nameByMac: Map<String, String>): PlanResult {
        val (mac, name) = findDevice(device, nameByMac)
            ?: return invalid("No device by that name. Type its name as shown in Devices, or its hardware address.")
        return ready(ChangePlan(
            title = "Pause the internet for $name?",
            lines = listOf(ChangeLine(name, "Allowed", "Paused")),
            ops = listOf(UciOp.Add("firewall", "rule", parentalBase(mac, name))),
            reloadFirewall = true,
            note = "Stays paused until you turn it off here."
        ))
    }

    fun parentalSchedule(device: String, days: String, from: String, to: String, nameByMac: Map<String, String>): PlanResult {
        val (mac, name) = findDevice(device, nameByMac)
            ?: return invalid("No device by that name. Type its name as shown in Devices, or its hardware address.")
        val dayList = days.split(' ', ',').filter { it.isNotBlank() }.map { d ->
            DAYS.firstOrNull { it.equals(d.take(3), ignoreCase = true) && d.length <= 9 }
                ?: return invalid("Days must be like: Mon Tue Wed (or leave empty for every day).")
        }.distinct()
        if (!TIME.matches(from.trim()) || !TIME.matches(to.trim())) return invalid("Enter the time as hours and minutes, like 21:30.")
        val values = parentalBase(mac, name).toMutableMap<String, Any>()
        values["start_time"] = from.trim().padStart(5, '0')
        values["stop_time"] = to.trim().padStart(5, '0')
        if (dayList.isNotEmpty() && dayList.size < 7) values["weekdays"] = dayList.sortedBy { DAYS.indexOf(it) }.joinToString(" ")
        return ready(ChangePlan(
            title = "Block the internet for $name on a schedule?",
            lines = listOf(ChangeLine(name, "Always allowed", "${(values["weekdays"] as? String) ?: "Every day"} ${values["start_time"]}–${values["stop_time"]}")),
            ops = listOf(UciOp.Add("firewall", "rule", values)),
            reloadFirewall = true,
            note = "Uses the router's clock. Times that cross midnight (like 21:30 to 06:30) are handled by the router's firewall. UNVERIFIED on this router: check at home."
        ))
    }

    fun parentalEnabled(section: String, name: String, on: Boolean): PlanResult = ready(ChangePlan(
        title = if (on) "Turn $name's rule on?" else "Turn $name's rule off?",
        lines = listOf(ChangeLine(name, onOff(!on), onOff(on))),
        ops = listOf(UciOp.Set("firewall", section, mapOf("enabled" to if (on) "1" else "0"))),
        reloadFirewall = true
    ))

    fun removeParental(section: String, name: String): PlanResult = ready(ChangePlan(
        title = "Remove $name's rule?",
        lines = listOf(ChangeLine(name, "Rule", "Removed")),
        ops = listOf(UciOp.Delete("firewall", section)),
        reloadFirewall = true
    ))

    // ----------------------------------------------------------------- UPnP, DDNS, QoS

    fun upnpEnabled(section: String, on: Boolean): PlanResult {
        val v = if (on) "1" else "0"
        return ready(ChangePlan(
            title = if (on) "Turn UPnP on?" else "Turn UPnP off?",
            lines = listOf(ChangeLine("UPnP", onOff(!on), onOff(on))),
            ops = listOf(UciOp.Set("upnpd", section, mapOf("enabled" to v, "enable_upnp" to v, "enable_natpmp" to v))),
            extraServices = if (on) {
                listOf("/etc/init.d/miniupnpd" to listOf("restart"), "/etc/init.d/miniupnpd" to listOf("enable"))
            } else {
                listOf("/etc/init.d/miniupnpd" to listOf("stop"))
            },
            note = if (on) "Apps and games on your network can then open ports by themselves." else null
        ))
    }

    fun ddnsEnabled(section: String, host: String, on: Boolean): PlanResult = ready(ChangePlan(
        title = if (on) "Turn on $host?" else "Turn off $host?",
        lines = listOf(ChangeLine(host, onOff(!on), onOff(on))),
        ops = listOf(UciOp.Set("ddns", section, mapOf("enabled" to if (on) "1" else "0"))),
        extraServices = listOf("/etc/init.d/ddns" to listOf("reload"))
    ))

    fun qosEnabled(section: String, on: Boolean): PlanResult = ready(ChangePlan(
        title = if (on) "Turn Smart Queue on?" else "Turn Smart Queue off?",
        lines = listOf(ChangeLine("Smart Queue", onOff(!on), onOff(on))),
        ops = listOf(UciOp.Set("sqm", section, mapOf("enabled" to if (on) "1" else "0"))),
        extraServices = listOf("/etc/init.d/sqm" to listOf("restart"))
    ))

    /** Limits in Mbps (decimals allowed); stored in kbit/s as sqm expects. */
    fun qos(section: String, on: Boolean, downMbps: String, upMbps: String): PlanResult {
        val down = downMbps.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it <= 100_000 }
        val up = upMbps.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it <= 100_000 }
        if (down == null || up == null) return invalid("Enter each speed in Mbps, more than 0 (for example 95 and 19).")
        val downK = (down * 1000).toLong().toString()
        val upK = (up * 1000).toLong().toString()
        return ready(ChangePlan(
            title = "Change Smart Queue limits?",
            lines = listOf(
                ChangeLine("Download limit", "", "${downMbps.trim()} Mbps"),
                ChangeLine("Upload limit", "", "${upMbps.trim()} Mbps"),
                ChangeLine("Smart Queue", "", onOff(on))
            ),
            ops = listOf(UciOp.Set("sqm", section, mapOf("enabled" to if (on) "1" else "0", "download" to downK, "upload" to upK))),
            extraServices = listOf("/etc/init.d/sqm" to listOf("restart")),
            note = "Set limits a little below your real line speed (about 90%) for the best effect."
        ))
    }

    // ---------------------------------------------------------------- Batch 2

    private val HOST = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")

    fun hostname(section: String, current: String, input: String): PlanResult {
        val name = input.trim()
        if (!HOST.matches(name)) return invalid("Use letters, numbers and hyphens (not at the start or end), up to 63 characters.")
        if (name == current) return invalid("That is already the router's name.")
        return ready(ChangePlan(
            title = "Rename the router?",
            lines = listOf(ChangeLine("Router name", current, name)),
            ops = listOf(UciOp.Set("system", section, mapOf("hostname" to name)))
        ))
    }

    private val LEASE = Regex("^(\\d+)([mhd])$")

    /** Address pool: first address (.start), how many (limit) and lease time like 30m, 12h or 2d (at least 2 minutes). */
    fun dhcpPool(section: String, start: String, limit: String, leasetime: String): PlanResult {
        val st = start.trim().removePrefix(".").toIntOrNull()?.takeIf { it in 1..253 }
            ?: return invalid("First address must be a number from 1 to 253.")
        val lim = limit.trim().toIntOrNull()?.takeIf { it >= 1 } ?: return invalid("How many must be at least 1.")
        if (st + lim - 1 > 254) return invalid("The pool would run past .254. Lower the first address or how many.")
        val m = LEASE.matchEntire(leasetime.trim().lowercase()) ?: return invalid("Write the lease time like 30m, 12h or 2d.")
        val minutes = m.groupValues[1].toLong() * when (m.groupValues[2]) { "m" -> 1L; "h" -> 60L; else -> 1440L }
        if (minutes < 2) return invalid("The lease time must be at least 2m.")
        return ready(ChangePlan(
            title = "Change the address pool?",
            lines = listOf(
                ChangeLine("First address", "", ".$st"),
                ChangeLine("How many", "", "$lim"),
                ChangeLine("Lease time", "", leasetime.trim().lowercase())
            ),
            ops = listOf(UciOp.Set("dhcp", section, mapOf("start" to "$st", "limit" to "$lim", "leasetime" to leasetime.trim().lowercase()))),
            note = "Devices keep their current address until their lease runs out."
        ))
    }

    fun dlnaEnabled(section: String, on: Boolean): PlanResult = ready(ChangePlan(
        title = if (on) "Turn on media sharing (DLNA)?" else "Turn off media sharing (DLNA)?",
        lines = listOf(ChangeLine("Media for TVs", onOff(!on), onOff(on))),
        ops = listOf(UciOp.Set("minidlna", section, mapOf("enabled" to if (on) "1" else "0"))),
        extraServices = if (on) listOf("/etc/init.d/minidlna" to listOf("restart"), "/etc/init.d/minidlna" to listOf("enable"))
        else listOf("/etc/init.d/minidlna" to listOf("stop"))
    ))

    fun adblockEnabled(section: String, on: Boolean): PlanResult = ready(ChangePlan(
        title = if (on) "Turn on ad blocking (adblock)?" else "Turn off ad blocking (adblock)?",
        lines = listOf(ChangeLine("Block ads and trackers", onOff(!on), onOff(on))),
        ops = listOf(UciOp.Set("adblock", section, mapOf("adb_enabled" to if (on) "1" else "0")))
    ))

    fun banipEnabled(section: String, on: Boolean): PlanResult = ready(ChangePlan(
        title = if (on) "Turn on threat blocking (banIP)?" else "Turn off threat blocking (banIP)?",
        lines = listOf(ChangeLine("Block dangerous sites", onOff(!on), onOff(on))),
        ops = listOf(UciOp.Set("banip", section, mapOf("ban_enabled" to if (on) "1" else "0")))
    ))

    fun openvpnEnabled(section: String, name: String, on: Boolean): PlanResult = ready(ChangePlan(
        title = if (on) "Turn on OpenVPN $name?" else "Turn off OpenVPN $name?",
        lines = listOf(ChangeLine(name, onOff(!on), onOff(on))),
        ops = listOf(UciOp.Set("openvpn", section, mapOf("enabled" to if (on) "1" else "0"))),
        note = if (!on) "If you reach the router through this VPN right now, the app will lose it and the router will undo this within 2 minutes." else null
    ))

    /**
     * Main Wi-Fi name and/or password. The new password is typed by the owner, sent only in the
     * change, and never shown back. Encryption is not changed. WPA keys: 8 to 63 characters, or
     * 64 hex digits.
     */
    fun wifiNetwork(section: String, currentSsid: String, newSsid: String, newKey: String, encryption: String): PlanResult {
        val ssid = newSsid.trim()
        if (ssid.isEmpty()) return invalid("Enter a network name.")
        if (ssid.toByteArray(Charsets.UTF_8).size > 32) return invalid("The network name can be at most 32 characters.")
        val values = mutableMapOf<String, Any>()
        val lines = mutableListOf<ChangeLine>()
        if (ssid != currentSsid) {
            values["ssid"] = ssid
            lines += ChangeLine("Network name", currentSsid, ssid)
        }
        if (newKey.isNotEmpty()) {
            if (encryption.startsWith("Open") || encryption.startsWith("Enhanced")) {
                return invalid("This network is open (no password). Set its security in the router pages first.")
            }
            val hex64 = newKey.length == 64 && newKey.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            val printable = newKey.length in 8..63 && newKey.all { it.code in 32..126 }
            if (!hex64 && !printable) return invalid("The password needs 8 to 63 letters, numbers or symbols.")
            values["key"] = newKey
            lines += ChangeLine("Password", "Current", "New password (hidden)")
        }
        if (values.isEmpty()) return invalid("Nothing changed: type a new name or a new password.")
        return ready(ChangePlan(
            title = "Change the Wi-Fi network?",
            lines = lines,
            ops = listOf(UciOp.Set("wireless", section, values)),
            longWait = true,
            note = "This phone will drop off Wi-Fi. Join \"$ssid\" with ${if (values.containsKey("key")) "the new password" else "the same password"} within about 90 seconds, then come back to the app. " +
                "If the app cannot reach the router by then, the router puts the old name and password back by itself. Every other device must rejoin too."
        ))
    }

    // ---------------------------------------------------------------- Batch 3: WAN, IPv6, WireGuard

    private fun maskBits(mask: List<Int>): Int? {
        val v = mask.fold(0L) { acc, b -> (acc shl 8) or b.toLong() }
        val bits = java.lang.Long.bitCount(v)
        val expected = if (bits == 0) 0L else (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
        return if (v == expected && bits in 8..30) bits else null
    }

    /**
     * Internet (WAN) settings: automatic (DHCP), static, or PPPoE. Standard OpenWrt `network.wan`
     * options proto, ipaddr, netmask, gateway, dns, peerdns, username, password. The PPPoE
     * password is typed by the owner and never shown; empty keeps the current one when already on PPPoE.
     */
    fun wan(currentProto: String, proto: String, ip: String, mask: String, gateway: String, dns: String, username: String, password: String): PlanResult {
        val values = mutableMapOf<String, Any>()
        val lines = mutableListOf(ChangeLine("Connection type", FeatureMappers.protoLabel(currentProto), FeatureMappers.protoLabel(proto.trim().lowercase())))
        val dnsList = dns.split(' ', ',').map { it.trim() }.filter { it.isNotEmpty() }
        if (dnsList.any { ipv4(it) == null }) return invalid("DNS servers must be addresses like 1.1.1.1, separated by spaces.")
        when (proto.trim().lowercase()) {
            "dhcp" -> values["proto"] = "dhcp"
            "static" -> {
                val a = ipv4(ip) ?: return invalid("Enter the internet address the provider gave you, like 192.168.31.50.")
                val m = ipv4(mask) ?: return invalid("Enter the subnet mask, like 255.255.255.0.")
                val bits = maskBits(m) ?: return invalid("That subnet mask is not valid. Use one like 255.255.255.0.")
                val g = ipv4(gateway) ?: return invalid("Enter the gateway address the provider gave you.")
                val net = { x: List<Int> -> (x.fold(0L) { acc, b -> (acc shl 8) or b.toLong() }) shr (32 - bits) }
                if (net(a) != net(g)) return invalid("The gateway must be on the same network as the address (check the mask).")
                if (a == g) return invalid("The address and the gateway can't be the same.")
                if (dnsList.isEmpty()) return invalid("With a static address, enter at least one DNS server, like 1.1.1.1.")
                values += mapOf("proto" to "static", "ipaddr" to ip.trim(), "netmask" to mask.trim(), "gateway" to gateway.trim())
                lines += ChangeLine("Address", "", "${ip.trim()} / ${mask.trim()}")
                lines += ChangeLine("Gateway", "", gateway.trim())
            }
            "pppoe" -> {
                if (username.isBlank()) return invalid("Enter the PPPoE user name from your provider.")
                if (password.isEmpty() && currentProto != "pppoe") return invalid("Enter the PPPoE password from your provider.")
                values["proto"] = "pppoe"
                values["username"] = username.trim()
                if (password.isNotEmpty()) values["password"] = password
                lines += ChangeLine("User name", "", "Set")
                lines += ChangeLine("Password", "", if (password.isNotEmpty()) "New (hidden)" else "Unchanged")
            }
            else -> return invalid("Choose dhcp, static or pppoe.")
        }
        if (dnsList.isNotEmpty()) {
            values["dns"] = dnsList
            values["peerdns"] = "0"
            lines += ChangeLine("DNS servers", "", dnsList.joinToString(", "))
        } else {
            values["peerdns"] = "1"
        }
        return ready(ChangePlan(
            title = "Change the internet connection?",
            lines = lines,
            ops = listOf(UciOp.Set("network", "wan", values)),
            checkInternet = true,
            note = "The internet drops for a moment. If you are using the app over the internet (VPN), you may lose it; the router then undoes the change by itself."
        ))
    }

    fun ipv6Enabled(on: Boolean): PlanResult = ready(ChangePlan(
        title = if (on) "Turn on IPv6 from your provider?" else "Turn off IPv6 from your provider?",
        lines = listOf(ChangeLine("IPv6 (wan6)", onOff(!on), onOff(on))),
        ops = listOf(UciOp.Set("network", "wan6", mapOf("disabled" to if (on) "0" else "1"))),
        checkInternet = true
    ))

    fun wireguardEnabled(section: String, on: Boolean): PlanResult {
        if (section in setOf("lan", "wan", "wan6", "loopback")) return invalid("That is not a WireGuard tunnel.")
        return ready(ChangePlan(
            title = if (on) "Turn on WireGuard $section?" else "Turn off WireGuard $section?",
            lines = listOf(ChangeLine("WireGuard $section", onOff(!on), onOff(on))),
            ops = listOf(UciOp.Set("network", section, mapOf("disabled" to if (on) "0" else "1"))),
            note = "If you reach the router through this VPN right now, the app will lose it and the router will undo this within 2 minutes."
        ))
    }
}
