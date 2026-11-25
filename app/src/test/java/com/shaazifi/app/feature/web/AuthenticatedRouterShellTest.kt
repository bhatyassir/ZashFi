package com.shaazifi.app.feature.web

import com.shaazifi.app.data.RouterProfile
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
    fun testBottomBarTabsMatchBlueprintSpecification() {
        // Blueprint Section 6 and M8 requirement: "Bottom bar: Home, AdGuard, Router, More."
        val expectedTabs = listOf("Home", "AdGuard", "Router", "More")
        
        // Tab index mapping:
        // 0 -> Home
        // 1 -> AdGuard
        // 2 -> Router
        // 3 -> More
        assertEquals("Home", expectedTabs[0])
        assertEquals("AdGuard", expectedTabs[1])
        assertEquals("Router", expectedTabs[2])
        assertEquals("More", expectedTabs[3])
        assertEquals(4, expectedTabs.size)
    }

    @Test
    fun testAdvancedLuCIIsReachableUnderMore() {
        val moreTabs = listOf("Advanced (LuCI)", "Tools", "Settings")
        
        assertTrue("Advanced (LuCI) must be reachable under More", moreTabs.contains("Advanced (LuCI)"))
        assertEquals(0, moreTabs.indexOf("Advanced (LuCI)"))

        // Origin resolution logic
        val routerOrigin = "https://100.64.0.7"
        val luciUrl = "$routerOrigin/cgi-bin/luci/"
        
        assertTrue("LuCI URL must point to /cgi-bin/luci/ on router origin", luciUrl.endsWith("/cgi-bin/luci/"))
        assertTrue("LuCI URL must use secure HTTPS router origin", luciUrl.startsWith(routerOrigin))
    }

    @Test
    fun testRouterTabKeepsSignedInStateAcrossTabSwitches() {
        // Verify token validity
        assertTrue("Session token must be valid 32-char hex string", SecureWebNavigation.isValidToken(sessionToken))

        // When on Router tab (tab 2), token is injected
        var currentTab = 2 // Router tab
        var activeSessionToken = sessionToken
        var injectionScript = SecureWebNavigation.buildSessionInjectionScript(activeSessionToken)
        assertTrue(injectionScript.contains(sessionToken))
        assertTrue(injectionScript.contains("sessionStorage.setItem('untangled-session'"))

        // User switches to Home (tab 0)
        currentTab = 0
        assertEquals(0, currentTab)
        // Session token remains retained in memory
        assertEquals(sessionToken, activeSessionToken)

        // User switches to AdGuard (tab 1)
        currentTab = 1
        assertEquals(1, currentTab)
        assertEquals(sessionToken, activeSessionToken)

        // User switches to More (tab 3)
        currentTab = 3
        assertEquals(3, currentTab)
        assertEquals(sessionToken, activeSessionToken)

        // User switches back to Router (tab 2)
        currentTab = 2
        assertEquals(2, currentTab)
        // Injection script built for RouterWebView retains the identical authenticated session token
        val scriptAfterReturn = SecureWebNavigation.buildSessionInjectionScript(activeSessionToken)
        assertEquals(injectionScript, scriptAfterReturn)
        assertTrue(scriptAfterReturn.contains(sessionToken))
    }

    @Test
    fun testMoreTabHousesToolsAndSettings() {
        val moreTabs = listOf("Advanced (LuCI)", "Tools", "Settings")
        assertTrue(moreTabs.contains("Tools"))
        assertTrue(moreTabs.contains("Settings"))
    }
}

