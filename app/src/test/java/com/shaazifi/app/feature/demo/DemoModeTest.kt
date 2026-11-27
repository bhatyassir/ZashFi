package com.shaazifi.app.feature.demo

import com.shaazifi.app.data.RouterProfile
import com.shaazifi.app.feature.home.DemoHomeRepository
import com.shaazifi.app.feature.home.HomeStateHolder
import com.shaazifi.app.feature.home.HomeUiState
import com.shaazifi.app.feature.web.MorePage
import com.shaazifi.app.feature.web.RouterWebUi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoModeTest {

    @Test
    fun theDemoRouterIsRecognisedAndRealRoutersAreNot() {
        assertTrue(DemoMode.isDemo(DemoMode.profile))
        assertEquals(DemoMode.profile, DemoMode.session.profile)
        // Older demo profiles named "Demo ..." keep working.
        assertTrue(DemoMode.isDemo(RouterProfile(name = "Demo lab", address = "https://192.168.1.1", certificateFingerprint = "")))
        assertFalse(DemoMode.isDemo(RouterProfile(name = "Home Gateway", address = "https://192.168.1.1", certificateFingerprint = "AA")))
    }

    @Test
    fun pagesThatNeedARealRouterAreClosedInDemo() {
        RouterWebUi.entries.forEach { webUi ->
            assertFalse(DemoMode.pageAvailable(MorePage.RouterPages(webUi)))
            assertFalse(DemoMode.pageAvailable(MorePage.RouterPages(webUi, "devices")))
        }
        assertFalse(DemoMode.pageAvailable(MorePage.Settings))
        assertFalse(DemoMode.pageAvailable(MorePage.AllDevices))
        // AdGuard is a bottom tab since 2026-11-18 (owner); it works in the demo (DemoModeTest above).
        assertTrue(DemoMode.pageAvailable(MorePage.Diagnostics))
        assertTrue(DemoMode.pageAvailable(MorePage.Feature("wan")))
    }

    @Test
    fun demoHomeShowsSampleDataWithoutTheRouter() = runBlocking {
        val holder = HomeStateHolder(DemoHomeRepository(), demo = true)
        holder.refresh()
        assertTrue(holder.state.value is HomeUiState.DemoData)
    }
}
