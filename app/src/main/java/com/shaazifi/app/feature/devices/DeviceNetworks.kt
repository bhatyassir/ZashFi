package com.shaazifi.app.feature.devices

import com.shaazifi.app.feature.router.UciConfig
import java.net.InetAddress
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

data class DeviceNetwork(val name: String, val subnet: String, val sourceZone: String?) {
    val key: String get() = "$name:$subnet"
    val label: String get() = if (name == "lan") "LAN" else name
}

private fun literalAddress(text: String): ByteArray? {
    if (text.contains(':')) {
        if (!text.matches(Regex("[0-9a-fA-F:.]+"))) return null
        return runCatching { InetAddress.getByName(text).address }.getOrNull()
    }
    val parts = text.split('.')
    if (parts.size != 4) return null
    return parts.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 }?.toByte() ?: return null }.toByteArray()
}

internal fun addressSortKey(ip: String): String = literalAddress(ip)?.let { bytes ->
    "${if (bytes.size == 4) 0 else 1}${bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }}"
} ?: "2"

internal fun DeviceList.withNetworks(dump: JsonObject?, firewall: UciConfig?): DeviceList {
    val sections = firewall?.ofType("zone").orEmpty()
    val modeAvailable = sections.any { it.opt("name") == "wan" }
    val zones = sections.flatMap { s -> s.list("network").flatMap { it.split(' ') }.mapNotNull { network ->
        val zone = s.opt("name")?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,32}")) }
        zone?.let { network to it }
    } }.groupBy({ it.first }, { it.second })

    data class Prefix(val network: DeviceNetwork, val bytes: ByteArray, val length: Int) {
        fun contains(address: ByteArray): Boolean = address.size == bytes.size && bytes.indices.all { i ->
            val bits = (length - i * 8).coerceIn(0, 8)
            val mask = (255 shl (8 - bits)) and 255
            (address[i].toInt() and mask) == (bytes[i].toInt() and mask)
        }
    }

    val prefixes = (dump?.get("interface") as? JsonArray).orEmpty().flatMap { entry ->
        val iface = entry as? JsonObject ?: return@flatMap emptyList()
        val name = (iface["interface"] as? JsonPrimitive)?.content ?: return@flatMap emptyList()
        val zone = zones[name]?.distinct()?.singleOrNull()
        if (name == "loopback" || name.startsWith("wan") || zone == "wan") return@flatMap emptyList()
        listOf("ipv4-address", "ipv6-address").flatMap { family ->
            (iface[family] as? JsonArray).orEmpty().mapNotNull { raw ->
                val addr = raw as? JsonObject ?: return@mapNotNull null
                val bytes = literalAddress((addr["address"] as? JsonPrimitive)?.content.orEmpty()) ?: return@mapNotNull null
                val bits = (addr["mask"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..(bytes.size * 8) } ?: return@mapNotNull null
                val masked = ByteArray(bytes.size) { i ->
                    val mask = (255 shl (8 - (bits - i * 8).coerceIn(0, 8))) and 255
                    (bytes[i].toInt() and mask).toByte()
                }
                val network = DeviceNetwork(name, "${InetAddress.getByAddress(masked).hostAddress}/$bits",
                    zone.takeIf { modeAvailable })
                Prefix(network, masked, bits)
            }
        }
    }.sortedByDescending { it.length }

    return copy(devices = devices.map { device ->
        val addr = literalAddress(device.ip)
        val matches = if (addr != null) prefixes.filter { it.contains(addr) } else emptyList()
        val longest = matches.firstOrNull()?.length
        val match = matches.takeWhile { it.length == longest }.map { it.network }.distinct().singleOrNull()
        device.copy(network = match)
    })
}
