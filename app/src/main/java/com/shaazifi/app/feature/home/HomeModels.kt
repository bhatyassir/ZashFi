package com.shaazifi.app.feature.home

import com.shaazifi.app.feature.router.ThermalSensor

/** Router identity, from `system board`. */
data class BoardInfo(
    val model: String,
    val hostname: String,
    val firmware: String
)

/** One reading of the first line of /proc/stat. CPU use needs two of these. */
data class CpuSample(
    val busy: Long,
    val total: Long
)

/** State of the internet (WAN) connection, from `network.interface dump`. */
data class WanStatus(
    val up: Boolean,
    val proto: String,
    val device: String,
    val ipv4: String?,
    val gateway: String?,
    val uptimeSec: Long
)

/**
 * Connected devices, counted the way UnTangled counts them.
 * [wifiKnown] is false when the router's Wi-Fi did not answer, so the
 * Wi-Fi / wired split is unknown and must not be shown as a fact.
 */
data class ClientSummary(
    val total: Int,
    val wifi: Int,
    val wired: Int,
    val wifiKnown: Boolean
)

/** Byte counters of one network device since the router started. */
data class TrafficCounters(
    val rxBytes: Long,
    val txBytes: Long
)

/** Parts of Home that could not be read, without failing the whole screen. */
enum class HomeNotice {
    CLIENTS_FEATURE_MISSING,
    CLIENTS_NOT_PERMITTED
}

/** One raw reading of the router. Rates and CPU % are worked out from two readings. */
data class HomeReading(
    val board: BoardInfo,
    val uptimeSec: Long,
    val memUsedPct: Int,
    val cpu: CpuSample?,
    val wan: WanStatus,
    val clients: ClientSummary?,
    val wanCounters: TrafficCounters?,
    val notices: Set<HomeNotice> = emptySet(),
    /** Owner feedback: network links (lan, wan, VPN...) and whether each is up. */
    val interfaces: List<Pair<String, Boolean>> = emptyList(),
    val radios: List<RadioSummary> = emptyList(),
    val thermals: List<ThermalSensor> = emptyList()
)

/** One Wi-Fi network for the Home cards (like LuCI Mobile's dashboard). */
data class RadioSummary(
    val ssid: String,
    /** "2.4 GHz", "5 GHz", "6 GHz" or "". */
    val band: String,
    val channel: String,
    val up: Boolean,
    /** Devices on this network, or null when unknown. */
    val clients: Int?,
    val key: WifiNetworkKey? = null
)

data class WifiNetworkKey(val radio: String, val section: String?, val ifname: String?) {
    fun matches(other: WifiNetworkKey): Boolean =
        radio == other.radio && if (section != null && other.section != null) section == other.section
        else ifname != null && ifname == other.ifname

    companion object {
        fun from(radio: String, section: String?, ifname: String?): WifiNetworkKey? {
            val config = section?.takeIf { it.isNotBlank() }
            val runtime = ifname?.takeIf(HomeParsers::isSafeIfname)
            return if (config != null || runtime != null) WifiNetworkKey(radio, config, runtime) else null
        }
    }
}

/** Everything the Home screen shows. */
data class HomeDashboard(
    val routerName: String,
    val firmware: String,
    val internetUp: Boolean,
    val wanIp: String?,
    val uptimeText: String,
    val memUsedPct: Int,
    /** Null until two CPU samples exist. */
    val cpuUsedPct: Int?,
    val clients: ClientSummary?,
    /** Bits per second. Null on the first reading (a rate needs two readings). */
    val downloadBps: Double?,
    val uploadBps: Double?,
    val downloadHistoryBps: List<Double>,
    val uploadHistoryBps: List<Double> = emptyList(),
    /** When each chart point was measured (phone clock, ms), so the chart can scroll by time. */
    val historyTimesMs: List<Long> = emptyList(),
    val historySpanSec: Long,
    /** Download plus upload on the internet port since the router started. */
    val totalBytesSinceStart: Long?,
    val notices: Set<HomeNotice>,
    /** True when the last refresh failed and these are the last good values. */
    val stale: Boolean,
    val interfaces: List<Pair<String, Boolean>> = emptyList(),
    val radios: List<RadioSummary> = emptyList(),
    val thermals: List<ThermalSensor> = emptyList(),
    val primaryTempC: Double? = thermals.maxOfOrNull { it.tempCelsius }
)

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Loaded(val dashboard: HomeDashboard) : HomeUiState
    data class DemoData(val dashboard: HomeDashboard) : HomeUiState
    data object FeatureNotInstalled : HomeUiState
    data object NotPermitted : HomeUiState
    data object RouterUnreachable : HomeUiState
    data object SessionExpired : HomeUiState
}

/** Why a reading failed. */
sealed class HomeLoadException(message: String) : Exception(message) {
    class SessionExpired : HomeLoadException("Session expired")
    class NotPermitted : HomeLoadException("Not permitted")
    class FeatureNotInstalled : HomeLoadException("Feature not installed")
    class Unreachable : HomeLoadException("Router unreachable")
}
