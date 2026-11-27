package com.shaazifi.app.feature.router

import com.shaazifi.app.ui.theme.PillTone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A small label with a colour, as the mock's `.pill`. */
data class ViewPill(val text: String, val tone: PillTone)

/** One line of a feature screen, as the mock's feature-detail rows: label, value, optional pill. */
data class ViewRow(
    val label: String,
    val value: String = "",
    val sub: String? = null,
    val mono: Boolean = false,
    val pill: ViewPill? = null,
    /** When set, the row shows a switch in this position; tapping it asks for [onToggle]'s plan. */
    val toggle: Boolean? = null,
    val onToggle: (() -> PlanResult)? = null,
    /** A second action on the row, such as "Remove". */
    val extra: RowAction? = null,
    /** Technical detail: folded under "Details" in its card so the main rows stay short. */
    val detail: Boolean = false
)

/** A button-like action that produces a change plan (shown for confirmation before anything is sent). */
data class RowAction(val label: String, val build: () -> PlanResult)

data class FormField(
    val key: String,
    val label: String,
    val initial: String = "",
    val numeric: Boolean = false,
    val hint: String? = null,
    /** Masked while typing (a password). Never pre-filled from the router. */
    val secret: Boolean = false,
    /** Owner feedback 2026-11-18: offer the router's device list to fill this field. */
    val pick: PickKind? = null,
    /** Tappable answers; with [multi], several can be chosen (joined by spaces). */
    val choices: List<String> = emptyList(),
    val multi: Boolean = false
)

/** What a device picker fills in: the device's hardware address or its IP address. */
enum class PickKind { DEVICE_MAC, DEVICE_IP }

/** A short form on a feature screen (for example "Open a port"). [build] gets the typed values by key. */
data class FeatureForm(
    val title: String,
    val fields: List<FormField>,
    val submitLabel: String,
    val build: (Map<String, String>) -> PlanResult
)

/**
 * A titled group of rows (the mock's eyebrow heading and `.list`). [form] is the change for
 * exactly this group: it opens inside the same card instead of as a separate card below.
 */
data class ViewSection(
    val heading: String,
    val rows: List<ViewRow>,
    val emptyText: String? = null,
    val form: FeatureForm? = null,
    /** More change forms for this same card (for example the radio's channel and width). */
    val moreForms: List<FeatureForm> = emptyList()
)

/** Everything one native feature screen shows. Built by pure functions in [FeatureMappers]. */
data class FeatureView(
    val summary: String,
    val pill: ViewPill? = null,
    val sections: List<ViewSection> = emptyList(),
    val note: String? = null,
    /** Changes not tied to one section (for example "Open a port"). Shown folded in one "Changes" list. Hidden in demo mode. */
    val forms: List<FeatureForm> = emptyList(),
    /** Owner feedback: the interfaces page draws its ports and links as a picture instead of [sections]. */
    val network: NetworkPicture? = null,
    /** Owner feedback 2026-11-18: live download and upload per device, drawn instead of [sections]. */
    val bandwidth: BandwidthPicture? = null,
    val wifiNetworks: List<WifiNetworkDetail> = emptyList(),
    val services: List<RouterService> = emptyList(),
    val combinedTraffic: Map<String, Double>? = null,
    val deviceTotals: Map<String, Long> = emptyMap(),
    val portTraffic: Map<String, Rates?> = emptyMap(),
    val internetPort: String? = null
)

data class WifiNetworkDetail(
    val key: com.shaazifi.app.feature.home.WifiNetworkKey,
    val name: String,
    val band: String,
    val view: FeatureView,
    val channel: String = "",
    val security: String = "",
    val enabled: Boolean? = null
)

data class RouterService(val name: String, val start: Int, val enabled: Boolean, val running: Boolean?)

/** What a network port is for. */
enum class PortKind { INTERNET, LAN, WIRELESS, BRIDGE, OTHER }

/** One port or network device of the router (`luci-rpc getNetworkDevices`). */
data class NetPort(
    val name: String,
    val kind: PortKind,
    /** Cable plugged in / link up (`link.carrier`). */
    val connected: Boolean,
    /** Link speed in Mb/s; null when the port has none (virtual ports) or it is unknown. */
    val speedMbps: Int?,
    val duplex: String?,
    val rxBytes: Long,
    val txBytes: Long
)

/** One logical network (`network.interface dump`): lan, wan, a VPN... */
data class NetLink(
    val name: String,
    val protocol: String,
    val up: Boolean,
    val device: String?,
    val address: String?,
    val rxBytes: Long?,
    val txBytes: Long?,
    val uptimeSec: Long?
)

data class NetworkPicture(val links: List<NetLink>, val ports: List<NetPort>)

/** The six screen states the blueprint requires (plus session expired), for any kind of screen data. */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Loaded<T>(val value: T) : LoadState<T>
    data class DemoData<T>(val value: T) : LoadState<T>
    data object NotInstalled : LoadState<Nothing>
    data object NotPermitted : LoadState<Nothing>
    data object Unreachable : LoadState<Nothing>
    data object SessionExpired : LoadState<Nothing>
}

/**
 * Holds one native screen's data. Reads only while the screen is visible
 * ([pollWhileActive] is cancelled when it leaves). In demo mode it never touches the network.
 */
class LoadHolder<T>(
    private val load: suspend () -> T,
    private var demo: T? = null,
    private val refreshMs: Long = 0L
) {
    companion object {
        /** Failed live refreshes in a row before a loaded screen is replaced by Unreachable. */
        const val MAX_STALE_REFRESHES = 3
    }

    private val _state = MutableStateFlow<LoadState<T>>(LoadState.Loading)
    val state: StateFlow<LoadState<T>> = _state.asStateFlow()

    private val readLock = Mutex()
    private var staleRefreshes = 0

    suspend fun refresh() = readLock.withLock {
        demo?.let {
            _state.value = LoadState.DemoData(it)
            return@withLock
        }
        val current = _state.value
        _state.value = try {
            val loaded = load()
            staleRefreshes = 0
            LoadState.Loaded(loaded)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FeatureLoadException.SessionExpired) {
            staleRefreshes = 0
            LoadState.SessionExpired
        } catch (e: FeatureLoadException.NotPermitted) {
            staleRefreshes = 0
            LoadState.NotPermitted
        } catch (e: FeatureLoadException.NotInstalled) {
            staleRefreshes = 0
            LoadState.NotInstalled
        } catch (e: FeatureLoadException.Unreachable) {
            if (current is LoadState.Loaded) {
                staleRefreshes++
                if (staleRefreshes < MAX_STALE_REFRESHES) current else LoadState.Unreachable
            } else {
                LoadState.Unreachable
            }
        }
    }

    /** One read, then (for live screens) a read every [refreshMs] while the screen stays visible. */
    suspend fun pollWhileActive() {
        refresh()
        if (refreshMs <= 0L || demo != null) return
        while (_state.value is LoadState.Loaded || _state.value is LoadState.Unreachable) {
            delay(refreshMs)
            refresh()
        }
    }

    fun updateDemo(update: (T) -> T) {
        demo?.let { old ->
            val value = update(old)
            demo = value
            _state.value = LoadState.DemoData(value)
        }
    }
}
