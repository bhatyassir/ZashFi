package com.shaazifi.app.feature.demo

import com.shaazifi.app.core.session.SessionState
import com.shaazifi.app.data.RouterProfile
import com.shaazifi.app.feature.web.MorePage

/**
 * Offline demo (blueprint section 7: "Demo mode reachable from the first screen, for reviewers").
 * Every screen shows built-in sample data; nothing is sent to any network. Pages that only make
 * sense on a real router (its own web pages, LuCI, settings) say so instead.
 */
object DemoMode {

    const val PROFILE_ID = "shaazifi-demo"

    /** A pretend router. Its address is never contacted in demo mode. */
    val profile = RouterProfile(
        id = PROFILE_ID,
        name = "Demo router",
        address = "https://192.168.1.1/",
        certificateFingerprint = "DEMO"
    )

    /** A pretend session so the signed-in screens can open. The token is never sent anywhere. */
    val session = SessionState.LoggedIn(profile = profile, token = "demo", timeoutSeconds = 0)

    /** The demo router, or an older profile whose name contains "Demo". */
    fun isDemo(p: RouterProfile): Boolean = p.id == PROFILE_ID || p.name.contains("Demo", ignoreCase = true)

    /** Pages under More that need a real router are closed in demo mode. */
    fun pageAvailable(page: MorePage): Boolean = when (page) {
        is MorePage.Feature, MorePage.Diagnostics, MorePage.About, MorePage.Activity, MorePage.AllDevices -> true
        is MorePage.RouterPages, MorePage.Settings -> false
    }
}
