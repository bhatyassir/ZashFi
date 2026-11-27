package com.shaazifi.app.feature.alerts

import com.shaazifi.app.data.RouterProfile
import com.shaazifi.app.feature.routers.RouterDisplay
import java.util.Locale

/** What one background check saw. [devices] maps MAC address to name. */
data class AlertSnapshot(
    val internetUp: Boolean,
    val devices: Map<String, String>,
    val maxTempCelsius: Double? = null,
    val hottestSensorLabel: String? = null
)

/** What the app remembers between checks (kept on the phone, in app storage). */
data class AlertMemory(
    val knownMacs: Set<String>,
    val internetUp: Boolean,
    val highTempActive: Boolean = false,
    val missedCounts: Map<String, Int> = emptyMap()
) {
    fun encode(): String {
        val base = (if (internetUp) "1" else "0") + "|" + knownMacs.sorted().joinToString(",")
        if (!highTempActive && missedCounts.isEmpty()) return base
        val misses = missedCounts.entries
            .filter { it.key in knownMacs && it.value > 0 }
            .sortedBy { it.key }
            .joinToString(",") { "${it.key}=${it.value}" }
        return "$base|${if (highTempActive) "1" else "0"}|$misses"
    }

    companion object {
        fun decode(s: String?): AlertMemory? {
            if (s == null) return null
            val parts = s.split("|")
            if (parts.size !in 2..4 || parts[0] !in setOf("0", "1")) return null
            val macs = parts[1].split(",").filter { it.isNotBlank() }.toSet()
            val highTemp = if (parts.size >= 3) {
                when (parts[2]) {
                    "1" -> true
                    "0", "" -> false
                    else -> return null
                }
            } else false
            val misses = if (parts.size == 4 && parts[3].isNotBlank()) {
                parts[3].split(",").mapNotNull { entry ->
                    val eq = entry.indexOf('=')
                    if (eq <= 0) null
                    else {
                        val mac = entry.substring(0, eq)
                        val count = entry.substring(eq + 1).toIntOrNull()
                        if (mac in macs && count != null && count > 0) mac to count else null
                    }
                }.toMap()
            } else emptyMap()
            return AlertMemory(macs, parts[0] == "1", highTemp, misses)
        }
    }
}

sealed class Alert {
    data class NewDevice(val mac: String, val name: String) : Alert()
    data class NewDevices(val count: Int) : Alert()
    data object InternetDown : Alert()
    data object InternetBack : Alert()
    data class HighTemperature(
        val tempCelsius: Double,
        val thresholdCelsius: Double,
        val sensorLabel: String? = null
    ) : Alert()
}

/** Security gate for background polling: credentials must never be sent over plain HTTP. */
object AlertSecurity {
    fun canPollInBackground(profile: RouterProfile): Boolean =
        !RouterDisplay.isPlainHttp(profile) &&
            profile.certificateFingerprint.isNotBlank() &&
            profile.address.trim().startsWith("https://", ignoreCase = true)
}

/**
 * M10c alert rules (blueprint v3: new device, internet down; "update ready" dropped with the
 * firmware options, D-20261117-004C63FB; optional high-temperature sensor alert).
 * The first check only learns devices and internet state; later checks alert on changes.
 */
object AlertRules {
    const val MAX_SINGLE_DEVICE_ALERTS = 3
    const val DEFAULT_TEMP_THRESHOLD_C = 80.0
    const val MIN_TEMP_THRESHOLD_C = 40.0
    const val MAX_TEMP_THRESHOLD_C = 110.0
    const val TEMP_HYSTERESIS_C = 3.0
    const val DEFAULT_STALE_MISSES = 96
    const val MAX_KNOWN_MACS = 500

    fun evaluate(
        previous: AlertMemory?,
        now: AlertSnapshot,
        highTempEnabled: Boolean = false,
        tempThresholdC: Double = DEFAULT_TEMP_THRESHOLD_C,
        staleAfterMisses: Int = DEFAULT_STALE_MISSES
    ): Pair<List<Alert>, AlertMemory> {
        val nextMissed = mutableMapOf<String, Int>()
        val retained = mutableSetOf<String>()
        for (mac in previous?.knownMacs.orEmpty()) {
            if (mac in now.devices) {
                retained += mac
            } else {
                val misses = (previous?.missedCounts?.get(mac) ?: 0) + 1
                if (misses < staleAfterMisses) {
                    retained += mac
                    nextMissed[mac] = misses
                }
            }
        }
        retained += now.devices.keys
        if (retained.size > MAX_KNOWN_MACS) {
            val overflow = retained.size - MAX_KNOWN_MACS
            val toDrop = (retained - now.devices.keys)
                .sortedByDescending { nextMissed[it] ?: 0 }
                .take(overflow)
            retained -= toDrop.toSet()
            toDrop.forEach { nextMissed.remove(it) }
        }

        val wasHot = previous?.highTempActive ?: false
        val nextHot: Boolean
        var highTempAlert: Alert.HighTemperature? = null
        if (!highTempEnabled || now.maxTempCelsius == null) {
            nextHot = if (!highTempEnabled) false else wasHot
        } else if (!wasHot && now.maxTempCelsius >= tempThresholdC) {
            nextHot = true
            highTempAlert = Alert.HighTemperature(now.maxTempCelsius, tempThresholdC, now.hottestSensorLabel)
        } else if (wasHot && now.maxTempCelsius < tempThresholdC - TEMP_HYSTERESIS_C) {
            nextHot = false
        } else {
            nextHot = wasHot
        }

        val memory = AlertMemory(
            knownMacs = retained,
            internetUp = now.internetUp,
            highTempActive = nextHot,
            missedCounts = nextMissed
        )
        if (previous == null) return listOfNotNull(highTempAlert) to memory

        val alerts = mutableListOf<Alert>()
        if (previous.internetUp && !now.internetUp) alerts += Alert.InternetDown
        if (!previous.internetUp && now.internetUp) alerts += Alert.InternetBack
        val fresh = now.devices.filterKeys { it !in previous.knownMacs }
        if (fresh.size > MAX_SINGLE_DEVICE_ALERTS) {
            alerts += Alert.NewDevices(fresh.size)
        } else {
            fresh.forEach { (mac, name) -> alerts += Alert.NewDevice(mac, name) }
        }
        highTempAlert?.let { alerts += it }
        return alerts to memory
    }

    /** Notification title and text. No addresses, passwords or tokens. */
    fun text(alert: Alert, routerName: String): Pair<String, String> = when (alert) {
        is Alert.NewDevice -> "New device on $routerName" to "${alert.name.ifBlank { "An unnamed device" }} joined your network."
        is Alert.NewDevices -> "New devices on $routerName" to "${alert.count} new devices joined your network."
        Alert.InternetDown -> "Internet is down" to "$routerName lost its internet connection."
        Alert.InternetBack -> "Internet is back" to "$routerName is online again."
        is Alert.HighTemperature -> {
            val sensor = alert.sensorLabel?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            val temp = String.format(Locale.US, "%.0f°C", alert.tempCelsius)
            val limit = String.format(Locale.US, "%.0f°C", alert.thresholdCelsius)
            "Router running hot" to "$routerName$sensor reached $temp (threshold $limit)."
        }
    }
}
