package com.shaazifi.app.feature.web

import com.shaazifi.app.feature.adguard.AdGuardQueryEntry
import com.shaazifi.app.feature.home.WifiNetworkKey

/**
 * Navigation state of the signed-in shell, kept as plain data so it can be unit-tested.
 * Tabs follow the approved mock and decision D-20261116-B570D322: Home, Devices, Wi-Fi, More.
 * Owner, 2026-11-18: AdGuard Home moved from More to its own tab. Every other feature lives
 * under More ("All features").
 */
enum class MainTab(val label: String) {
    Home("Home"),
    Devices("Devices"),
    Wifi("Wi-Fi"),
    AdGuard("AdGuard"),
    Parental("Parental"),
    More("More")
}

/** Pages reached from the More list. */
sealed interface MorePage {
    /** A native read-only feature screen ([com.shaazifi.app.feature.router.Features] id). */
    data class Feature(val id: String) : MorePage
    /** The chosen router interface, optionally opened at a feature. */
    data class RouterPages(val webUi: RouterWebUi, val featureId: String? = null) : MorePage
    /** M10b: ping, trace route, DNS lookup run on the router. */
    data object Diagnostics : MorePage
    /** Licence and credits. */
    data object About : MorePage
    /** Phase 5: changes made from the app; devices of all saved routers. */
    data object Activity : MorePage
    data object AllDevices : MorePage
    data object Settings : MorePage
}

data class FamilyAssignment(val profileId: String?)

data class ShellNavState(
    val tab: MainTab = MainTab.Home,
    val morePage: MorePage? = null,
    val queryLogEntry: AdGuardQueryEntry? = null,
    /** An AdGuard Home management page ([com.shaazifi.app.feature.adguard.manage.AgPage] id). */
    val adGuardPage: String? = null,
    /** A Wi-Fi feature opened from the Wi-Fi tab (guest, mesh, extend). */
    val wifiPage: String? = null,
    val wifiNetwork: WifiNetworkKey? = null,
    val deviceMac: String? = null,
    val parentalMac: String? = null,
    val familyProfileId: String? = null,
    val familyAssignment: FamilyAssignment? = null,
    /**
     * Owner feedback 2026-11-17: back returns to the page you came from. Each move remembers the
     * screen before it (newest last, at most [MAX_HISTORY]).
     */
    val history: List<ShellNavState> = emptyList()
) {
    val queryLogOpen: Boolean get() = queryLogEntry != null

    companion object {
        const val MAX_HISTORY = 40
    }

    /** The screen itself, without its history. */
    private fun screen() = copy(history = emptyList())

    /** Moves to [next], remembering this screen so back can return to it. */
    private fun go(next: ShellNavState): ShellNavState {
        if (next.screen() == screen()) return this
        return next.copy(history = (history + screen()).takeLast(MAX_HISTORY))
    }

    /** Selecting a tab. Tapping the current tab again returns to its first screen. Tab switches do not push back history. */
    private fun clearDetails() = copy(wifiNetwork = null, deviceMac = null, parentalMac = null,
        familyProfileId = null, familyAssignment = null)

    fun select(target: MainTab): ShellNavState =
        when {
            target == tab && target == MainTab.More -> clearDetails().copy(morePage = null, history = emptyList())
            target == tab && target == MainTab.Wifi -> clearDetails().copy(wifiPage = null, history = emptyList())
            target == tab && target == MainTab.AdGuard -> clearDetails().copy(queryLogEntry = null, adGuardPage = null, history = emptyList())
            else -> clearDetails().copy(tab = target, history = emptyList())
        }

    fun openMore(page: MorePage): ShellNavState = go(clearDetails().copy(tab = MainTab.More, morePage = page))

    fun openAdGuardPage(id: String): ShellNavState = go(clearDetails().copy(tab = MainTab.AdGuard, queryLogEntry = null, adGuardPage = id))

    fun openQueryLog(entry: AdGuardQueryEntry = AdGuardQueryEntry.LIVE): ShellNavState =
        go(clearDetails().copy(tab = MainTab.AdGuard, queryLogEntry = entry, adGuardPage = null))

    fun openWifiFeature(id: String): ShellNavState = go(clearDetails().copy(tab = MainTab.Wifi, wifiPage = id))

    fun openWifiNetwork(key: WifiNetworkKey): ShellNavState = go(clearDetails().copy(
        tab = if (tab == MainTab.Home) MainTab.Home else MainTab.Wifi, wifiPage = null, wifiNetwork = key
    ))

    fun openDevice(mac: String): ShellNavState = go(clearDetails().copy(deviceMac = mac))

    fun openParental(mac: String? = null): ShellNavState = go(clearDetails().copy(tab = MainTab.Parental, parentalMac = mac))

    fun openFamilyProfile(id: String): ShellNavState = go(clearDetails().copy(tab = MainTab.Parental, familyProfileId = id))

    fun openFamilyAssignment(profileId: String?): ShellNavState =
        go(clearDetails().copy(tab = MainTab.Parental, familyAssignment = FamilyAssignment(profileId)))

    private val canGoUp: Boolean
        get() = wifiNetwork != null || deviceMac != null || parentalMac != null || familyProfileId != null ||
            familyAssignment != null ||
            (tab == MainTab.More && morePage != null) || (tab == MainTab.Wifi && wifiPage != null) ||
            (tab == MainTab.AdGuard && (adGuardPage != null || queryLogOpen))

    /** True when the system back gesture should go back inside the shell. */
    val canGoBack: Boolean
        get() = history.isNotEmpty() || canGoUp

    /** The screen you came from; with no history (a shortcut opened this screen), one level up. */
    fun back(): ShellNavState {
        if (history.isNotEmpty()) return history.last().copy(history = history.dropLast(1))
        return when {
            wifiNetwork != null || deviceMac != null || parentalMac != null || familyProfileId != null ||
                familyAssignment != null -> clearDetails()
            tab == MainTab.AdGuard && adGuardPage != null -> copy(adGuardPage = null)
            tab == MainTab.AdGuard && queryLogOpen -> copy(queryLogEntry = null)
            tab == MainTab.More && morePage != null -> copy(morePage = null)
            tab == MainTab.Wifi && wifiPage != null -> copy(wifiPage = null)
            else -> this
        }
    }
}
