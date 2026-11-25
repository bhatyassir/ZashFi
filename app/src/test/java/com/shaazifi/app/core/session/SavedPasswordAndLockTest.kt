package com.shaazifi.app.core.session

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class SavedPasswordAndLockTest {

    /** Stand-in for the Keystore cipher: reversible, and refuses the wrong associated data like AES-GCM does. */
    private class FakeCipher : SecretCipher {
        override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray =
            aad.size.toByte().let { byteArrayOf(it) } + aad + plain.map { (it.toInt() xor 0x5A).toByte() }
        override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
            val n = blob[0].toInt()
            val storedAad = blob.copyOfRange(1, 1 + n)
            if (!storedAad.contentEquals(aad)) throw SecretUnavailableException.Tampered()
            return blob.copyOfRange(1 + n, blob.size).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    private class MemoryBox : SecretBox {
        val map = mutableMapOf<String, String>()
        override suspend fun get(key: String) = map[key]
        override suspend fun put(key: String, value: String) { map[key] = value }
        override suspend fun remove(key: String) { map.remove(key) }
    }

    @Test
    fun passwordsAreSavedEncryptedPerRouterAndKind() = runBlocking {
        val box = MemoryBox()
        val store = SecretStore(box, FakeCipher())
        store.save("r1", SecretKind.ROUTER, "router-pass-1")
        store.save("r1", SecretKind.ADGUARD, "adguard-pass-1")
        store.save("r2", SecretKind.ROUTER, "router-pass-2")

        assertEquals("router-pass-1", store.load("r1", SecretKind.ROUTER))
        assertEquals("adguard-pass-1", store.load("r1", SecretKind.ADGUARD))
        assertEquals("router-pass-2", store.load("r2", SecretKind.ROUTER))
        assertTrue(store.has("r1", SecretKind.ROUTER))
        assertFalse(store.has("r3", SecretKind.ROUTER))
        assertNull(store.load("r3", SecretKind.ROUTER))
        assertTrue("passwords never stored in plain text", box.map.values.none { it.contains("pass") })
    }

    @Test
    fun aSavedPasswordCannotBeMovedToAnotherEntry() = runBlocking {
        val box = MemoryBox()
        val store = SecretStore(box, FakeCipher())
        store.save("r1", SecretKind.ROUTER, "router-pass-1")
        // Copy r1's router blob into r2's AdGuard slot: decryption must refuse it.
        box.map[SecretStore.keyFor("r2", SecretKind.ADGUARD)] = box.map[SecretStore.keyFor("r1", SecretKind.ROUTER)]!!
        try {
            store.load("r2", SecretKind.ADGUARD)
            fail("a moved secret must not decrypt")
        } catch (_: SecretUnavailableException.Tampered) {
        }
    }

    @Test
    fun forgettingARouterRemovesBothItsPasswords() = runBlocking {
        val store = SecretStore(MemoryBox(), FakeCipher())
        store.save("r1", SecretKind.ROUTER, "a")
        store.save("r1", SecretKind.ADGUARD, "b")
        store.save("r2", SecretKind.ROUTER, "c")
        store.forget("r1")
        assertFalse(store.has("r1", SecretKind.ROUTER))
        assertFalse(store.has("r1", SecretKind.ADGUARD))
        assertTrue(store.has("r2", SecretKind.ROUTER))
    }

    @Test
    fun lockFollowsTheTimeoutScreenOffAndUnlock() {
        var now = 1_000L
        val lock = AppLock(now = { now })
        assertFalse("off until the owner turns it on", lock.locked.value)

        lock.configure(enabled = true, timeout = LockTimeout.ONE_MINUTE)
        assertTrue("turning the lock on locks at the next start", lock.locked.value)
        lock.unlocked()
        assertFalse(lock.locked.value)

        lock.onAppStopped(); now += 59_000; lock.onAppStarted()
        assertFalse("back within a minute: still open", lock.locked.value)
        lock.onAppStopped(); now += 60_000; lock.onAppStarted()
        assertTrue("away a minute or more: locked", lock.locked.value)

        lock.unlocked()
        lock.onScreenOff()
        assertTrue("screen off locks at once", lock.locked.value)

        lock.unlocked()
        lock.configure(enabled = true, timeout = LockTimeout.IMMEDIATELY)
        lock.onAppStopped(); now += 1; lock.onAppStarted()
        assertTrue(lock.locked.value)

        lock.configure(enabled = false, timeout = LockTimeout.IMMEDIATELY)
        assertFalse("turning the lock off opens the app", lock.locked.value)
        lock.onScreenOff()
        assertFalse(lock.locked.value)
    }

    @Test
    fun unlockMethodsFollowTheAndroidVersion() {
        // androidx.biometric PromptInfo.Builder#setAllowedAuthenticators: BIOMETRIC_STRONG | DEVICE_CREDENTIAL
        // is unsupported on API 28-29; DEVICE_CREDENTIAL alone is unsupported before API 30.
        val strong = UnlockRules.BIOMETRIC_STRONG
        val weak = UnlockRules.BIOMETRIC_WEAK
        val pin = UnlockRules.DEVICE_CREDENTIAL
        assertEquals(strong or pin, UnlockRules.allowedAuthenticators(26))
        assertEquals(strong or pin, UnlockRules.allowedAuthenticators(27))
        assertEquals(weak or pin, UnlockRules.allowedAuthenticators(28))
        assertEquals(weak or pin, UnlockRules.allowedAuthenticators(29))
        assertEquals(strong or pin, UnlockRules.allowedAuthenticators(30))
        assertEquals(strong or pin, UnlockRules.allowedAuthenticators(37))
    }

    @Test
    fun nothingLeavesThePhoneInBackupsOrTransfers() {
        val res = listOf(File("src/main/res/xml/data_extraction_rules.xml"), File("app/src/main/res/xml/data_extraction_rules.xml")).first { it.isFile }
        val xml = res.readText()
        val cloud = xml.substringAfter("<cloud-backup").substringBefore("</cloud-backup>")
        val transfer = xml.substringAfter("<device-transfer").substringBefore("</device-transfer>")
        for (domain in listOf("root", "file", "database", "sharedpref", "external")) {
            assertTrue("cloud backup must exclude $domain", cloud.contains("domain=\"$domain\""))
            assertTrue("device transfer must exclude $domain", transfer.contains("domain=\"$domain\""))
        }
        assertFalse(xml.contains("<include"))
        val manifest = File(res.parentFile.parentFile.parentFile, "AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
    }
}
