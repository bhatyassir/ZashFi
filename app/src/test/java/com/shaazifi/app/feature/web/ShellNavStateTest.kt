package com.shaazifi.app.feature.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.shaazifi.app.feature.home.WifiNetworkKey
import com.shaazifi.app.feature.adguard.AdGuardQueryEntry

class ShellNavStateTest {

    @Test
    fun tabsFollowTheApprovedMock() {
        // D-20261116-B570D322, changed by the owner 2026-11-18: AdGuard gets its own bottom tab.
        assertEquals(listOf("Home", "Devices", "Wi-Fi", "AdGuard", "Parental", "More"), MainTab.entries.map { it.label })
        assertEquals(MainTab.Home, ShellNavState().tab)
    }

    @Test
    fun networkAndDeviceDetailsReturnToTheirOrigin() {
        val key = WifiNetworkKey("radio0", "ap1", "wlan0")
        val home = ShellNavState()
        assertEquals(home, home.openWifiNetwork(key).back())
        val wifi = home.select(MainTab.Wifi)
        assertEquals(wifi, wifi.openWifiNetwork(key).back())
        val list = home.select(MainTab.Devices)
        val detail = list.openDevice("02:00:00:00:00:01")
        assertEquals(detail, detail.openParental(detail.deviceMac).back())
        assertEquals(list, detail.back())
    }

    @Test
    fun familyAssignmentReturnsToItsEntryPoint() {
        val dashboard = ShellNavState().openParental()
        val fromDashboard = dashboard.openFamilyAssignment(null)
        assertEquals(FamilyAssignment(null), fromDashboard.familyAssignment)
        assertEquals(dashboard, fromDashboard.back())

        val profile = dashboard.openFamilyProfile("kids")
        val fromProfile = profile.openFamilyAssignment("kids")
        assertEquals(FamilyAssignment("kids"), fromProfile.familyAssignment)
        assertEquals(MainTab.Parental, fromProfile.tab)
        assertNull(fromProfile.familyProfileId)
        assertEquals(profile, fromProfile.back())
        assertNull(fromProfile.select(MainTab.Parental).familyAssignment)
    }

    @Test
    fun adGuardAndItsQueryLogAreInTheAdGuardTab() {
        // Owner feedback 2026-11-17: back follows the path taken, so open it the way the app does.
        val log = ShellNavState().select(MainTab.AdGuard).openQueryLog()
        assertEquals(MainTab.AdGuard, log.tab)
        assertTrue(log.queryLogOpen)
        val back = log.back()
        assertFalse(back.queryLogOpen)
        assertEquals(MainTab.AdGuard, back.tab)
        assertEquals(MainTab.Home, back.back().tab)
    }

    @Test
    fun adGuardShortcutsCarryTheirOwnEntryAndReturnToTheDashboard() {
        val dashboard = ShellNavState().select(MainTab.AdGuard)
        AdGuardQueryEntry.entries.forEach { entry ->
            val log = dashboard.openQueryLog(entry)
            assertEquals(entry, log.queryLogEntry)
            assertEquals(dashboard, log.back())
            assertNull(log.select(MainTab.AdGuard).queryLogEntry)
        }
    }

    @Test
    fun moreStartsOnTheListAndBackReturnsToIt() {
        val more = ShellNavState().select(MainTab.More)
        assertNull(more.morePage)
        // Owner feedback 2026-11-17: back from the More list returns to the tab you came from.
        assertEquals(MainTab.Home, more.back().tab)
        val wan = more.openMore(MorePage.Feature("wan"))
        assertEquals(MorePage.Feature("wan"), wan.morePage)
        assertTrue(wan.canGoBack)
        assertNull(wan.back().morePage)
    }

    @Test
    fun tappingTheCurrentTabAgainReturnsToItsFirstScreen() {
        assertNull(ShellNavState().openMore(MorePage.Settings).select(MainTab.More).morePage)
        assertNull(ShellNavState().openWifiFeature("guest").select(MainTab.Wifi).wifiPage)
    }

    @Test
    fun leavingATabKeepsItsPage() {
        val s = ShellNavState().openMore(MorePage.Feature("log")).select(MainTab.Home).select(MainTab.More)
        assertEquals(MorePage.Feature("log"), s.morePage)
    }

    @Test
    fun routerPagesOpenAtTheChosenInterfaceAndBackRestoresTheFeature() {
        val guest = ShellNavState().openWifiFeature("guest")
        RouterWebUi.entries.forEach { webUi ->
            val page = MorePage.RouterPages(webUi, "guest")
            val opened = guest.openMore(page)
            assertEquals(MainTab.More, opened.tab)
            assertEquals(page, opened.morePage)
            assertEquals(guest, opened.back())
        }
    }

    @Test
    fun wifiSubPagesGoBackToTheWifiTab() {
        val s = ShellNavState().select(MainTab.Wifi).openWifiFeature("mesh")
        assertTrue(s.canGoBack)
        assertNull(s.back().wifiPage)
        assertEquals(MainTab.Wifi, s.back().tab)
    }

    @Test
    fun backDoesNothingAtTopLevel() {
        val home = ShellNavState()
        assertEquals(home, home.back())
    }
}
