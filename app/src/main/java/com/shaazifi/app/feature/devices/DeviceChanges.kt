package com.shaazifi.app.feature.devices

import com.shaazifi.app.feature.router.ChangeLine
import com.shaazifi.app.feature.router.ChangePlan
import com.shaazifi.app.feature.router.UciOp

sealed interface DeviceChangeResult {
    data class Ready(val plan: ChangePlan) : DeviceChangeResult
    data class Invalid(val message: String) : DeviceChangeResult
}

/**
 * Builds the change plans for the Devices screen. Pure and unit-tested; nothing here talks to
 * the router. The settings used are the ones UnTangled uses for the same actions
 * (src/lib/devices.ts, read as facts): a firewall `rule` named "Block: <name>" with src lan,
 * dest wan, src_mac, proto all, target REJECT; a dhcp `host` section with mac, ip and name;
 * and `etherwake -i br-lan <mac>` (needs the etherwake package) to wake a device.
 */
object DeviceChanges {

    private fun ready(plan: ChangePlan) = DeviceChangeResult.Ready(plan)
    private fun invalid(message: String) = DeviceChangeResult.Invalid(message)

    /** Letters, digits and hyphens only, as hostnames allow. */
    fun hostname(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9-]+"), "-").trim('-').take(63)

    fun block(d: Device): DeviceChangeResult {
        if (d.blocked) return invalid("${d.name} is already blocked.")
        val zone = d.network?.sourceZone?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,32}")) && it != "wan" } ?: "lan"
        val values = mapOf(
            "name" to "Block: ${hostname(d.name).ifBlank { d.mac }}",
            "src" to zone,
            "dest" to "wan",
            "src_mac" to d.mac,
            "proto" to "all",
            "target" to "REJECT",
            "enabled" to "1"
        )
        val op = if (d.blockId != null) UciOp.Set("firewall", d.blockId, values) else UciOp.Add("firewall", "rule", values)
        return ready(ChangePlan(
            title = "Block internet for ${d.name}?",
            lines = listOf(ChangeLine("Internet", "Allowed", "Blocked")),
            ops = listOf(op),
            reloadFirewall = true,
            note = "The device stays on your network and can still reach other devices, but not the internet."
        ))
    }

    fun unblock(d: Device): DeviceChangeResult {
        val id = d.blockId ?: return invalid("${d.name} is not blocked.")
        return ready(ChangePlan(
            title = "Allow internet for ${d.name}?",
            lines = listOf(ChangeLine("Internet", "Blocked", "Allowed")),
            ops = listOf(UciOp.Delete("firewall", id)),
            reloadFirewall = true
        ))
    }

    private fun ipv4(text: String): List<Int>? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val nums = parts.map { p -> p.toIntOrNull()?.takeIf { it in 0..255 && p == it.toString() } ?: return null }
        return nums
    }

    /**
     * A fixed address must be a private address on the network the device is on now (same first
     * three numbers, the usual /24 home network), not the router itself, and not .0 or .255.
     */
    fun setFixedIp(d: Device, input: String, routerIp: String): DeviceChangeResult {
        val ip = input.trim()
        val n = ipv4(ip) ?: return invalid("Enter an address like 192.168.1.50.")
        val private = n[0] == 10 || (n[0] == 172 && n[1] in 16..31) || (n[0] == 192 && n[1] == 168)
        if (!private) return invalid("Use an address on your home network, like 192.168.1.50.")
        if (ip == routerIp) return invalid("That is the router's own address.")
        if (n[3] == 0 || n[3] == 255) return invalid("Addresses ending in .0 or .255 can't be used for a device.")
        // Stay on the network the device is on now (some homes have more than one); else the router's.
        val r = ipv4(d.ip) ?: ipv4(routerIp)
        if (r != null && (r[0] != n[0] || r[1] != n[1] || r[2] != n[2])) {
            return invalid("Use an address starting with ${r[0]}.${r[1]}.${r[2]}., the network this device is on.")
        }
        if (ip == d.fixedIp) return invalid("${d.name} already has this address.")
        val op = if (d.reserveId != null) {
            UciOp.Set("dhcp", d.reserveId, mapOf("ip" to ip, "mac" to d.mac))
        } else {
            val name = hostname(d.name)
            UciOp.Add("dhcp", "host", buildMap {
                put("mac", d.mac)
                put("ip", ip)
                if (name.isNotBlank() && !name.equals(d.mac.replace(':', '-'), ignoreCase = true) && name != d.ip.replace('.', '-')) put("name", name)
            })
        }
        return ready(ChangePlan(
            title = "Always give ${d.name} this address?",
            lines = listOf(ChangeLine("Fixed address", d.fixedIp ?: "None", ip)),
            ops = listOf(op),
            note = "The device gets the new address the next time it reconnects."
        ))
    }

    fun removeFixedIp(d: Device): DeviceChangeResult {
        val id = d.reserveId?.takeIf { d.fixedIp != null } ?: return invalid("${d.name} has no fixed address.")
        val keepName = !d.customName.isNullOrBlank()
        return ready(ChangePlan(
            title = "Stop giving ${d.name} a fixed address?",
            lines = listOf(ChangeLine("Fixed address", d.fixedIp ?: "Set", "None")),
            ops = listOf(if (keepName) UciOp.DeleteOption("dhcp", id, "ip") else UciOp.Delete("dhcp", id)),
            note = if (keepName) "Its custom name is kept." else null
        ))
    }

    fun rename(d: Device, input: String): DeviceChangeResult {
        val name = hostname(input)
        if (name.isBlank()) return invalid("Use letters, numbers or hyphens.")
        val op = if (d.reserveId != null) UciOp.Set("dhcp", d.reserveId, mapOf("name" to name))
        else UciOp.Add("dhcp", "host", mapOf("mac" to d.mac, "name" to name))
        return ready(ChangePlan(
            title = "Rename ${d.name}?",
            lines = listOf(ChangeLine("Name", d.name, name)),
            ops = listOf(op),
            note = "Names may only use letters, numbers and hyphens, so spaces become hyphens."
        ))
    }

    fun wake(d: Device): DeviceChangeResult = ready(ChangePlan(
        title = "Wake ${d.name}?",
        lines = listOf(ChangeLine("Action", "", "Send a wake-up signal")),
        ops = emptyList(),
        note = "Works only for wired devices set up to wake on LAN, and needs the etherwake package on the router.",
        command = "/usr/bin/etherwake" to listOf("-i", "br-lan", d.mac)
    ))
}
