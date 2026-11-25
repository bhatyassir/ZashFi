package com.shaazifi.app.feature.alerts

/** What one background check saw. [devices] maps MAC address to name. */
data class AlertSnapshot(val internetUp: Boolean, val devices: Map<String, String>)

/** What the app remembers between checks (kept on the phone, in app storage). */
data class AlertMemory(val knownMacs: Set<String>, val internetUp: Boolean) {
    fun encode(): String = (if (internetUp) "1" else "0") + "|" + knownMacs.sorted().joinToString(",")

    companion object {
        fun decode(s: String?): AlertMemory? {
            if (s == null) return null
            val parts = s.split("|", limit = 2)
            if (parts.size != 2 || parts[0] !in setOf("0", "1")) return null
            val macs = parts[1].split(",").filter { it.isNotBlank() }.toSet()
            return AlertMemory(macs, parts[0] == "1")
        }
    }
}

sealed class Alert {
    data class NewDevice(val mac: String, val name: String) : Alert()
    data class NewDevices(val count: Int) : Alert()
    data object InternetDown : Alert()
    data object InternetBack : Alert()
}

/**
 * M10c alert rules (blueprint v3: new device, internet down; "update ready" dropped with the
 * firmware options, D-20261117-004C63FB). The first check only learns; later checks alert on changes.
 */
object AlertRules {
    const val MAX_SINGLE_DEVICE_ALERTS = 3

    fun evaluate(previous: AlertMemory?, now: AlertSnapshot): Pair<List<Alert>, AlertMemory> {
        val memory = AlertMemory(
            knownMacs = (previous?.knownMacs.orEmpty()) + now.devices.keys,
            internetUp = now.internetUp
        )
        if (previous == null) return emptyList<Alert>() to memory
        val alerts = mutableListOf<Alert>()
        if (previous.internetUp && !now.internetUp) alerts += Alert.InternetDown
        if (!previous.internetUp && now.internetUp) alerts += Alert.InternetBack
        val fresh = now.devices.filterKeys { it !in previous.knownMacs }
        if (fresh.size > MAX_SINGLE_DEVICE_ALERTS) {
            alerts += Alert.NewDevices(fresh.size)
        } else {
            fresh.forEach { (mac, name) -> alerts += Alert.NewDevice(mac, name) }
        }
        return alerts to memory
    }

    /** Notification title and text. No addresses, passwords or tokens. */
    fun text(alert: Alert, routerName: String): Pair<String, String> = when (alert) {
        is Alert.NewDevice -> "New device on $routerName" to "${alert.name.ifBlank { "An unnamed device" }} joined your network."
        is Alert.NewDevices -> "New devices on $routerName" to "${alert.count} new devices joined your network."
        Alert.InternetDown -> "Internet is down" to "$routerName lost its internet connection."
        Alert.InternetBack -> "Internet is back" to "$routerName is online again."
    }
}
