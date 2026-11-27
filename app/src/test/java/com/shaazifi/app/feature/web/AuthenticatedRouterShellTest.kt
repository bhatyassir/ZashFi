package com.shaazifi.app.feature.web

import com.shaazifi.app.data.RouterProfile
import com.shaazifi.app.data.getAdGuardBaseUrl
import com.shaazifi.app.feature.demo.DemoMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatedRouterShellTest {

    private val testProfile = RouterProfile(
        id = "test-profile-1",
        name = "Home router",
        address = "https://100.64.0.7/untangled/",
        certificateFingerprint = "0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F:0F"
    )

    private val sessionToken = "e89547d7c6792fca0cb329c2ab87dc79"

    @Test
    fun testBottomBarTabsMatchSpecification() {
        val expectedLabels = listOf("Home", "Devices", "Wi-Fi", "AdGuard", "Parental", "More")
        assertEquals(expectedLabels, MainTab.entries.map { it.label })
        assertEquals(6, MainTab.entries.size)
        assertEquals(MainTab.Home, MainTab.entries[0])
        assertEquals(MainTab.Devices, MainTab.entries[1])
        assertEquals(MainTab.Wifi, MainTab.entries[2])
        assertEquals(MainTab.AdGuard, MainTab.entries[3])
        assertEquals(MainTab.Parental, MainTab.entries[4])
        assertEquals(MainTab.More, MainTab.entries[5])
    }

    @Test
    fun testAdvancedLuCIIsReachableUnderMore() {
        val luciPage = MorePage.RouterPages(RouterWebUi.Luci)
        val nav = ShellNavState().openMore(luciPage)

        assertEquals(MainTab.More, nav.tab)
        assertEquals(luciPage, nav.morePage)

        val routerOrigin = "https://100.64.0.7"
        val luciUrl = RouterWebPages.target(routerOrigin, RouterWebUi.Luci, null)
        assertEquals("https://100.64.0.7/cgi-bin/luci/", luciUrl)
        assertTrue(SecureWebNavigation.isInternalNavigation(luciUrl, routerOrigin))
    }

    @Test
    fun testShellKeepsSessionAndNavigatesAcrossAllSixTabs() {
        assertTrue("Session token must be valid 32-char hex string", SecureWebNavigation.isValidToken(sessionToken))

        var nav = ShellNavState()
        val luciRoute = MorePage.RouterPages(RouterWebUi.Luci, "wireless")
        nav = nav.openMore(luciRoute)
        assertEquals(MainTab.More, nav.tab)
        assertEquals(luciRoute, nav.morePage)

        val injectionScript = SecureWebNavigation.buildSessionInjectionScript(sessionToken)
        assertTrue(injectionScript.contains(sessionToken))
        assertTrue(injectionScript.contains("sessionStorage.setItem('untangled-session'"))

        // Switching across tabs preserves More sub-page and session token stays valid
        for (tab in listOf(MainTab.Home, MainTab.Devices, MainTab.Wifi, MainTab.AdGuard, MainTab.Parental)) {
            nav = nav.select(tab)
            assertEquals(tab, nav.tab)
        }

        nav = nav.select(MainTab.More)
        assertEquals(MainTab.More, nav.tab)
        assertEquals(luciRoute, nav.morePage)
        assertEquals(injectionScript, SecureWebNavigation.buildSessionInjectionScript(sessionToken))
    }

    @Test
    fun testMoreTabPagesAndDemoRestrictions() {
        val realOnlyPages = listOf(
            MorePage.RouterPages(RouterWebUi.Luci),
            MorePage.RouterPages(RouterWebUi.Untangled),
            MorePage.Settings,
            MorePage.AllDevices
        )
        realOnlyPages.forEach { page ->
            assertFalse("Page $page must be unavailable in demo mode", DemoMode.pageAvailable(page))
        }

        val demoAllowedPages = listOf(
            MorePage.Diagnostics,
            MorePage.About,
            MorePage.Activity,
            MorePage.Feature("wan")
        )
        demoAllowedPages.forEach { page ->
            assertTrue("Page $page should be available in demo mode", DemoMode.pageAvailable(page))
        }
    }

    @Test
    fun testProfileIdentityAndAdGuardEndpointResolution() {
        val vpnUpdated = testProfile.copy(
            vpnAddress = "https://100.64.0.99",
            vpnCertificateFingerprint = "AA:BB"
        )
        assertEquals(testProfile.id, vpnUpdated.id)
        assertEquals("http://100.64.0.7:3000", vpnUpdated.copy(address = "https://100.64.0.7").getAdGuardBaseUrl())
    }
}
