package com.shaazifi.app.feature.router

/*
 * Phase 4 (T-20261117-C0594015): services, time zone, router password, reboot, firewall zones,
 * traffic rules, static routes. Calls and options from (read 2026-11-17): openwrt/rpcd rc.c;
 * openwrt/luci (Apache-2.0) luci-base rpcd ucode `luci` (getTimezones, setPassword),
 * luci-mod-system system.js (zonename + timezone), luci-mod-network routes.js,
 * luci-app-firewall zones.js and rules.js. Credited in CREDITS.md.
 *
 * Settings changes use the router's automatic undo. Services, password and reboot are one-shot
 * actions (no undo exists): password and reboot need a word typed, and services the app depends
 * on cannot be stopped from the app.
 */

/** A one-shot ubus action run once after confirmation (no apply, no undo). */
data class UbusCall(val obj: String, val method: String, val args: Map<String, String>)

object Phase4 {

    private val SERVICE_NAME = Regex("^[A-Za-z0-9_.-]{1,64}$")
    val SERVICE_ACTIONS = setOf("start", "stop", "restart", "reload", "enable", "disable")

    /** Stopping or disabling these would cut the app off (web server, RPC, network) or the home network (DNS/DHCP, firewall). */
    val PROTECTED_SERVICES = setOf("uhttpd", "rpcd", "network", "dnsmasq", "firewall", "ubus", "procd")

    fun isAllowedServiceAction(name: String, action: String): Boolean =
        SERVICE_NAME.matches(name) && action in SERVICE_ACTIONS && !(name in PROTECTED_SERVICES && action in setOf("stop", "disable"))

    fun serviceAction(name: String, action: String): PlanResult {
        if (!SERVICE_NAME.matches(name)) return PlanResult.Invalid("That is not a service name.")
        if (action !in SERVICE_ACTIONS) return PlanResult.Invalid("Choose start, stop, restart, reload, enable or disable.")
        if (!isAllowedServiceAction(name, action)) {
            return PlanResult.Invalid("$name cannot be stopped or disabled from the app: the app or your home network needs it. Restart is allowed.")
        }
        val (title, note) = when (action) {
            "start" -> "Start $name?" to "The service starts now."
            "stop" -> "Stop $name?" to "The service stops until it is started again or the router restarts (if it starts at boot)."
            "restart" -> "Restart $name?" to "The service stops and starts again; anything using it pauses for a moment."
            "reload" -> "Reload $name?" to "The service reads its settings again."
            "enable" -> "Start $name at boot?" to "The service will start when the router starts."
            else -> "Do not start $name at boot?" to "The service will not start when the router starts."
        }
        return PlanResult.Ready(ChangePlan(title, listOf(ChangeLine("Service", name, action)), emptyList(),
            note = "$note This runs once; there is no automatic undo.", ubusCall = UbusCall("rc", "init", mapOf("name" to name, "action" to action))))
    }

    fun reboot(typed: String): PlanResult {
        if (typed.trim() != "RESTART") return PlanResult.Invalid("Type RESTART to confirm.")
        return PlanResult.Ready(ChangePlan(
            "Restart the router now?", listOf(ChangeLine("Router", "Running", "Restarting")), emptyList(),
            note = "Internet and Wi-Fi stop for about one to three minutes while the router restarts. The app reconnects afterwards.",
            ubusCall = UbusCall("system", "reboot", emptyMap())
        ))
    }

    fun password(new: String, repeat: String, typed: String): PlanResult {
        if (typed.trim() != "CHANGE") return PlanResult.Invalid("Type CHANGE to confirm.")
        if (new != repeat) return PlanResult.Invalid("The two passwords do not match.")
        if (new.length < 8) return PlanResult.Invalid("Use at least 8 characters.")
        if (new.any { it.code < 32 || it.code > 126 }) return PlanResult.Invalid("Use letters, numbers and symbols only.")
        return PlanResult.Ready(ChangePlan(
            "Change the router password?", listOf(ChangeLine("Router password (root)", "Current", "New (hidden)")), emptyList(),
            note = "The router's root password changes at once; there is no automatic undo. This session stays signed in. " +
                "Your saved password and the alerts' copy still hold the old one: sign in again with the new password, tick Remember, and turn alerts off and on.",
            ubusCall = UbusCall("luci", "setPassword", mapOf("username" to "root", "password" to new))
        ))
    }

    fun timeZone(systemSection: String, current: String?, zone: String, zones: Map<String, String>): PlanResult {
        val z = zone.trim()
        val tz = zones[z] ?: return PlanResult.Invalid("Choose a time zone from the list, for example Asia/Kolkata.")
        if (z == current) return PlanResult.Invalid("Nothing changed.")
        return PlanResult.Ready(ChangePlan(
            "Change the router's time zone?", listOf(ChangeLine("Time zone", current ?: "UTC", z)),
            listOf(UciOp.Set("system", systemSection, mapOf("zonename" to z, "timezone" to tz))),
            note = "Schedules (parental rules, Wi-Fi times) follow the new time zone."
        ))
    }

    private val POLICIES = setOf("ACCEPT", "REJECT", "DROP")

    fun zoneEdit(zone: UciSection, input: String, output: String, forward: String, masq: String): PlanResult {
        val i = input.trim().uppercase(); val o = output.trim().uppercase(); val f = forward.trim().uppercase()
        if (listOf(i, o, f).any { it !in POLICIES }) return PlanResult.Invalid("Policies are ACCEPT, REJECT or DROP.")
        val m = when (masq.trim().lowercase()) { "yes", "on", "1", "true" -> true; "no", "off", "0", "false" -> false; else -> return PlanResult.Invalid("Masquerading: yes or no.") }
        val name = zone.opt("name") ?: zone.id
        val values = linkedMapOf<String, Any>()
        val lines = mutableListOf<ChangeLine>()
        fun cmp(key: String, label: String, now: String?, def: String, new: String) {
            if ((now ?: def).uppercase() != new) { values[key] = new; lines += ChangeLine(label, now ?: def, new) }
        }
        cmp("input", "Into the router", zone.opt("input"), "ACCEPT", i)
        cmp("output", "From the router", zone.opt("output"), "ACCEPT", o)
        cmp("forward", "Through the router", zone.opt("forward"), "REJECT", f)
        if (zone.flag("masq") != m) { values["masq"] = if (m) "1" else "0"; lines += ChangeLine("Masquerading (NAT)", if (zone.flag("masq")) "On" else "Off", if (m) "On" else "Off") }
        if (values.isEmpty()) return PlanResult.Invalid("Nothing changed.")
        val risky = "lan" in zone.list("network") && i != "ACCEPT"
        return PlanResult.Ready(ChangePlan(
            "Change the $name zone?", lines, listOf(UciOp.Set("firewall", zone.id, values)), reloadFirewall = true,
            note = (if (risky) "Not accepting traffic into the router from the home network can cut this phone off. " else "") +
                "If the app cannot reach the router afterwards, the router undoes this within 2 minutes."
        ))
    }

    private val PORTS = Regex("^[0-9]{1,5}(-[0-9]{1,5})?$")

    fun addRule(v: Map<String, String>, zones: Set<String>): PlanResult {
        val name = v["name"].orEmpty().trim()
        val src = v["src"].orEmpty().trim()
        val dest = v["dest"].orEmpty().trim()
        val proto = v["proto"].orEmpty().trim().lowercase().ifEmpty { "tcp udp" }
        val port = v["dest_port"].orEmpty().trim()
        val target = v["target"].orEmpty().trim().uppercase()
        if (name.isEmpty() || name.length > 64) return PlanResult.Invalid("Give the rule a short name.")
        if (src.isNotEmpty() && src != "*" && src !in zones) return PlanResult.Invalid("Source zone must be one of: ${zones.joinToString(", ")}.")
        if (dest.isNotEmpty() && dest != "*" && dest !in zones) return PlanResult.Invalid("Destination zone must be one of: ${zones.joinToString(", ")}.")
        if (proto.split(' ').any { it !in setOf("tcp", "udp", "icmp", "all") }) return PlanResult.Invalid("Protocol: tcp, udp, icmp or all.")
        if (port.isNotEmpty() && (!PORTS.matches(port) || port.split('-').any { (it.toIntOrNull() ?: 0) !in 1..65535 })) return PlanResult.Invalid("Port must be 1 to 65535, or a range like 8000-8100.")
        if (target !in POLICIES) return PlanResult.Invalid("Action: ACCEPT, REJECT or DROP.")
        val values = linkedMapOf<String, Any>("name" to name)
        if (src.isNotEmpty()) values["src"] = src
        if (dest.isNotEmpty()) values["dest"] = dest
        values["proto"] = proto
        if (port.isNotEmpty()) values["dest_port"] = port
        values["target"] = target
        return PlanResult.Ready(ChangePlan(
            "Add the rule \"$name\"?",
            listOf(ChangeLine("Rule", "", "${src.ifEmpty { "router" }} → ${dest.ifEmpty { "router" }} $proto${if (port.isNotEmpty()) " port $port" else ""}: $target")),
            listOf(UciOp.Add("firewall", "rule", values)), reloadFirewall = true,
            note = "If the app cannot reach the router afterwards, the router undoes this within 2 minutes."
        ))
    }

    fun ruleEnabled(id: String, name: String, enable: Boolean) = PlanResult.Ready(ChangePlan(
        if (enable) "Turn on the rule \"$name\"?" else "Turn off the rule \"$name\"?",
        listOf(ChangeLine(name, if (enable) "Off" else "On", if (enable) "On" else "Off")),
        listOf(UciOp.Set("firewall", id, mapOf("enabled" to if (enable) "1" else "0"))), reloadFirewall = true
    ))

    fun removeRule(id: String, name: String) = PlanResult.Ready(ChangePlan(
        "Remove the rule \"$name\"?", listOf(ChangeLine(name, "Present", "Removed")),
        listOf(UciOp.Delete("firewall", id)), reloadFirewall = true,
        note = "If the app cannot reach the router afterwards, the router undoes this within 2 minutes."
    ))

    private val CIDR = Regex("^([0-9]{1,3}\\.){3}[0-9]{1,3}/([0-9]{1,2})$")
    private val IPV4 = Regex("^([0-9]{1,3}\\.){3}[0-9]{1,3}$")
    private fun octetsOk(s: String) = s.substringBefore('/').split('.').all { (it.toIntOrNull() ?: 999) in 0..255 }

    fun addRoute(iface: String, target: String, gateway: String, metric: String, interfaces: Set<String>): PlanResult {
        val i = iface.trim(); val t = target.trim(); val g = gateway.trim(); val m = metric.trim()
        if (i !in interfaces) return PlanResult.Invalid("Choose an interface: ${interfaces.joinToString(", ")}.")
        val bits = CIDR.find(t)?.groupValues?.get(2)?.toIntOrNull()
        if (bits == null || bits > 32 || !octetsOk(t)) return PlanResult.Invalid("Target must be a network address like 10.8.0.0/24.")
        if (g.isNotEmpty() && (!IPV4.matches(g) || !octetsOk(g))) return PlanResult.Invalid("Gateway must be an address like 192.168.1.2.")
        if (m.isNotEmpty() && (m.toIntOrNull() ?: -1) !in 0..9999) return PlanResult.Invalid("Metric must be a number from 0 to 9999.")
        val values = linkedMapOf<String, Any>("interface" to i, "target" to t)
        if (g.isNotEmpty()) values["gateway"] = g
        if (m.isNotEmpty()) values["metric"] = m
        return PlanResult.Ready(ChangePlan(
            "Add a route to $t?", listOf(ChangeLine("Route", "", "$t via ${g.ifEmpty { "the interface" }} on $i")),
            listOf(UciOp.Add("network", "route", values)),
            note = "A wrong route can cut off parts of your network. If the app cannot reach the router afterwards, the router undoes this within 2 minutes."
        ))
    }

    fun removeRoute(iface: String, target: String) = PlanResult.Ready(ChangePlan(
        "Remove the route to $target?", listOf(ChangeLine("Route", "$target on $iface", "Removed")),
        listOf(UciOp.DeleteWhere("network", "route", mapOf("interface" to iface, "target" to target))),
        note = "If the app cannot reach the router afterwards, the router undoes this within 2 minutes."
    ))

    fun routeEnabled(id: String, label: String, enable: Boolean) = PlanResult.Ready(ChangePlan(
        if (enable) "Turn on the route to $label?" else "Turn off the route to $label?",
        listOf(ChangeLine(label, if (enable) "Off" else "On", if (enable) "On" else "Off")),
        listOf(UciOp.Set("network", id, mapOf("disabled" to if (enable) "0" else "1")))
    ))
}
