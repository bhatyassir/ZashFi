package com.shaazifi.app.feature.web

import com.shaazifi.app.feature.adguard.AdGuardQueryEntry
import com.shaazifi.app.feature.home.WifiNetworkKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        val adGuard = ShellNavState().select(MainTab.AdGuard)
        val log = adGuard.openQueryLog()
        assertEquals(MainTab.AdGuard, log.tab)
        assertTrue(log.queryLogOpen)
        assertTrue(log.canGoBack)

        val back = log.back()
        assertFalse(back.queryLogOpen)
        assertEquals(MainTab.AdGuard, back.tab)
        assertFalse(back.canGoBack)
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
    fun moreStartsOnTheListAndBackPopsIntraTabPages() {
        val more = ShellNavState().select(MainTab.More)
        assertNull(more.morePage)
        assertFalse(more.canGoBack)

        val wan = more.openMore(MorePage.Feature("wan"))
        assertEquals(MorePage.Feature("wan"), wan.morePage)
        assertTrue(wan.canGoBack)
        assertNull(wan.back().morePage)
        assertEquals(MainTab.More, wan.back().tab)
        assertFalse(wan.back().canGoBack)
    }

    @Test
    fun tappingTheCurrentTabAgainReturnsToItsFirstScreen() {
        assertNull(ShellNavState().openMore(MorePage.Settings).select(MainTab.More).morePage)
        assertNull(ShellNavState().openWifiFeature("guest").select(MainTab.Wifi).wifiPage)
        assertNull(ShellNavState().select(MainTab.AdGuard).openQueryLog().select(MainTab.AdGuard).queryLogEntry)
    }

    @Test
    fun leavingATabKeepsItsPageAcrossMoreWifiAndAdGuard() {
        val moreKept = ShellNavState().openMore(MorePage.Feature("log")).select(MainTab.Home).select(MainTab.More)
        assertEquals(MorePage.Feature("log"), moreKept.morePage)

        val wifiKept = ShellNavState().openWifiFeature("guest").select(MainTab.Home).select(MainTab.Wifi)
        assertEquals("guest", wifiKept.wifiPage)
        // Intra-tab back pops the Wi-Fi sub-page back to the Wi-Fi root without jumping to Home.
        assertTrue(wifiKept.canGoBack)
        assertEquals(MainTab.Wifi, wifiKept.back().tab)
        assertNull(wifiKept.back().wifiPage)
        assertFalse(wifiKept.back().canGoBack)

        val adGuardKept = ShellNavState().openAdGuardPage("dns").select(MainTab.Home).select(MainTab.AdGuard)
        assertEquals("dns", adGuardKept.adGuardPage)
    }

    @Test
    fun switchingTabsRepeatedlyDoesNotBuildCrossTabBackHistory() {
        var nav = ShellNavState()
        val tabs = MainTab.entries
        repeat(45) { step ->
            nav = nav.select(tabs[step % tabs.size])
            assertFalse("Cross-tab switch #$step must not enable shell Back", nav.canGoBack)
        }
        assertEquals(nav, nav.back())
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
        assertFalse(home.canGoBack)
        assertEquals(home, home.back())
    }
}
