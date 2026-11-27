package com.shaazifi.app.feature.localnet

import com.shaazifi.app.feature.localnet.LocalNetworkAccess.Decision
import com.shaazifi.app.test.InMemoryPreferencesDataStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M9 item 5. developer.android.com/privacy-and-security/local-network-permission (read 2026-11-17):
 * ACCESS_LOCAL_NETWORK is needed only when the app targets SDK 37+; at 36 or lower "don't add
 * ACCESS_LOCAL_NETWORK to your manifest or request it at runtime".
 */
class LocalNetworkAccessTest {

    @Test
    fun onlyAppliesWhenTheAppAndThePhoneAreOnAndroid17() {
        assertFalse(LocalNetworkAccess.applies(targetSdk = 36, deviceSdk = 37))
        assertFalse(LocalNetworkAccess.applies(targetSdk = 37, deviceSdk = 36))
        assertTrue(LocalNetworkAccess.applies(targetSdk = 37, deviceSdk = 37))
        assertEquals("android.permission.ACCESS_LOCAL_NETWORK", LocalNetworkAccess.PERMISSION)
    }

    @Test
    fun explainFirstAndSendToSettingsOnlyAfterARealRefusal() {
        assertEquals(Decision.NotNeeded, LocalNetworkAccess.decide(applies = false, granted = false, askedBefore = false, showRationale = false))
        assertEquals(Decision.Granted, LocalNetworkAccess.decide(applies = true, granted = true, askedBefore = true, showRationale = false))
        assertEquals(Decision.Explain, LocalNetworkAccess.decide(applies = true, granted = false, askedBefore = false, showRationale = false))
        assertEquals(Decision.Explain, LocalNetworkAccess.decide(applies = true, granted = false, askedBefore = true, showRationale = true))
        assertEquals(Decision.OpenSettings, LocalNetworkAccess.decide(applies = true, granted = false, askedBefore = true, showRationale = false))
    }

    @Test
    fun theManifestMatchesTheTargetSdk() {
        val app = listOf(File("."), File("app")).first { File(it, "build.gradle.kts").isFile && File(it, "src/main/AndroidManifest.xml").isFile }
        val target = Regex("""targetSdk\s*=\s*(\d+)""").find(File(app, "build.gradle.kts").readText())!!.groupValues[1].toInt()
        val declared = File(app, "src/main/AndroidManifest.xml").readText().contains("android.permission.ACCESS_LOCAL_NETWORK")
        if (target < LocalNetworkAccess.FIRST_SDK) {
            assertFalse("targetSdk $target: must not declare ACCESS_LOCAL_NETWORK (Google's guidance)", declared)
        } else {
            assertTrue("targetSdk $target: must declare ACCESS_LOCAL_NETWORK, or the router cannot be reached", declared)
        }
    }

    @Test
    fun skippedAndAskedStatePersistInPrefs() = runTest {
        val ds = InMemoryPreferencesDataStore()
        val prefs = LocalNetworkPrefs(ds)
        assertFalse(prefs.askedBefore())
        assertFalse(prefs.skipped())

        prefs.markAsked()
        prefs.markSkipped()
        assertTrue(prefs.askedBefore())
        assertTrue(prefs.skipped())

        prefs.clearSkipped()
        assertFalse(prefs.skipped())
    }
}
