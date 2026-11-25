package com.shaazifi.app.feature.about

import com.shaazifi.app.feature.router.Features
import com.shaazifi.app.feature.web.RouterWebPages
import com.shaazifi.app.feature.web.RouterWebUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Owner, 2026-11-17: ShaaziFi becomes GPL-3.0, credits everyone, and works on any OpenWrt router with LuCI. */
class LicenceAndGenericLuciTest {

    private val repo = listOf(File(".."), File(".")).first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun theAppIsGplV3() {
        val licence = File(repo, "LICENSE").readText()
        assertTrue(licence.contains("GNU GENERAL PUBLIC LICENSE"))
        assertTrue(licence.contains("Version 3, 29 June 2007"))
    }

    @Test
    fun everyCreditIsInCreditsMdWithACompatibleLicence() {
        val md = File(repo, "CREDITS.md").readText()
        val compatible = setOf("GPL-3.0", "GPL-2.0-or-later", "Apache-2.0", "OFL-1.1", "MIT", "BSD-3-Clause")
        Credits.entries.forEach { c ->
            assertTrue("CREDITS.md must name ${c.name}", md.contains(c.name))
            assertTrue("CREDITS.md must link ${c.url}", md.contains(c.url))
            assertTrue("${c.name}: ${c.licence} must be GPL-3.0 compatible", c.licence in compatible)
        }
        listOf("LuCI Mobile", "UnTangled", "LuCI", "AdGuard Home Manager", "AdGuard Home", "OpenWrt", "OpenWrtManager", "ZXing", "OkHttp")
            .forEach { n -> assertTrue("missing credit: $n", Credits.entries.any { it.name == n }) }
    }

    @Test
    fun routerPageLinksHonorTheChosenInterface() {
        assertEquals("/cgi-bin/luci/admin/network/wireless", RouterWebPages.luciPath("wireless"))
        assertEquals("/cgi-bin/luci/admin/network/firewall/forwards", RouterWebPages.luciPath("portfwd"))
        assertEquals("/cgi-bin/luci/admin/status/logs", RouterWebPages.luciPath("log"))
        assertEquals("/cgi-bin/luci/", RouterWebPages.luciPath("not-a-feature"))
        assertEquals("https://r/cgi-bin/luci/admin/services/ddns", RouterWebPages.target("https://r", RouterWebUi.Luci, "ddns"))
        assertEquals("https://r/untangled/#ddns", RouterWebPages.target("https://r", RouterWebUi.Untangled, "ddns"))
        assertEquals("https://r/cgi-bin/luci/", RouterWebPages.target("https://r", RouterWebUi.Luci, null))
        assertEquals("https://r/untangled/", RouterWebPages.target("https://r", RouterWebUi.Untangled, null))
        // Every feature that offers "change in the router pages" has a LuCI page too.
        Features.all.mapNotNull { it.untangledId }.filterNot { it == "mesh" }.forEach { id ->
            assertTrue("no LuCI page for $id", RouterWebPages.luciPath(id) != "/cgi-bin/luci/")
        }
    }
}
