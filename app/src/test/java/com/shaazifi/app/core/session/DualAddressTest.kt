package com.shaazifi.app.core.session

import com.shaazifi.app.core.net.AddressNotAllowedException
import com.shaazifi.app.core.net.CertificateChangedException
import com.shaazifi.app.core.net.TrustStore
import com.shaazifi.app.core.ubus.UbusClient
import com.shaazifi.app.core.ubus.UbusError
import com.shaazifi.app.data.ProfileJsonAdapter
import com.shaazifi.app.data.InMemoryProfileRepository
import com.shaazifi.app.data.RouterProfile
import com.shaazifi.app.feature.settings.RouterSettingsHandler
import com.shaazifi.app.feature.settings.VpnAddressResult
import java.net.InetAddress
import com.shaazifi.app.data.endpoints
import com.shaazifi.app.data.normalizeRouterAddress
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ConnectException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

/** M9 item 2: one router, a home (LAN) address and a VPN address. 100.64.0.7 is a documentation stand-in. */
class DualAddressTest {

    private val lan = RouterProfile(id = "r1", name = "Home", address = "https://192.168.1.1", certificateFingerprint = "AA")

    @Test
    fun homeAddressComesFirstThenVpn() {
        assertEquals(listOf("https://192.168.1.1"), lan.endpoints().map { it.address })
        val both = lan.copy(vpnAddress = "https://100.64.0.7", vpnCertificateFingerprint = "BB")
        val e = both.endpoints()
        assertEquals(listOf("https://192.168.1.1", "https://100.64.0.7"), e.map { it.address })
        assertEquals(listOf("AA", "BB"), e.map { it.fingerprint })
        assertEquals(listOf(false, true), e.map { it.viaVpn })
        // A VPN address without an approved certificate is never used.
        assertEquals(1, lan.copy(vpnAddress = "https://100.64.0.7").endpoints().size)
    }

    @Test
    fun oldSavedRoutersStillLoad() {
        val old = """{"id":"r1","name":"Home","address":"https://192.168.1.1","certificateFingerprint":"AA"}"""
        val p = ProfileJsonAdapter.fromJson(old)!!
        assertNull(p.vpnAddress)
        assertNull(p.vpnCertificateFingerprint)
    }

    @Test
    fun typedVpnAddressesAreTidied() {
        assertEquals("https://100.64.0.7", normalizeRouterAddress(" 100.64.0.7 "))
        assertEquals("https://100.64.0.7", normalizeRouterAddress("https://100.64.0.7/"))
        assertEquals("https://router.tailnet.ts.net", normalizeRouterAddress("router.tailnet.ts.net"))
        assertEquals("http://100.64.0.7", normalizeRouterAddress("http://100.64.0.7"))
        assertNull(normalizeRouterAddress("   "))
    }

    @Test
    fun onlyAnUnreachableAddressMovesOnToTheVpn() {
        assertTrue(RouteChooser.tryNextAddress(UbusError.NetworkError(ConnectException("refused"))))
        assertTrue(RouteChooser.tryNextAddress(UbusError.NetworkError(SocketTimeoutException("timeout"))))
        assertTrue(RouteChooser.tryNextAddress(UbusError.NetworkError(java.net.NoRouteToHostException("no route"))))
        // Never on a security problem or a wrong password.
        assertFalse(RouteChooser.tryNextAddress(UbusError.AccessDenied()))
        assertFalse(RouteChooser.tryNextAddress(UbusError.NetworkError(SSLHandshakeException("bad cert"))))
        assertFalse(
            RouteChooser.tryNextAddress(
                UbusError.NetworkError(SSLHandshakeException("pin").apply { initCause(CertificateChangedException("h", "AA", "CC")) })
            )
        )
        assertFalse(RouteChooser.tryNextAddress(AddressNotAllowedException("8.8.8.8")))
    }

    private val loginReply = """{"jsonrpc":"2.0","id":1,"result":[0,{"ubus_rpc_session":"tok","timeout":300,"data":{"username":"root"}}]}"""
    private val deniedReply = """{"jsonrpc":"2.0","id":1,"result":[6]}"""

    private fun closedPortAddress(): String {
        val port = ServerSocket(0).use { it.localPort }
        return "http://127.0.0.1:$port"
    }

    @Test
    fun signInFallsBackToTheVpnWhenHomeIsUnreachable() = runBlocking {
        val vpn = MockWebServer().apply { enqueue(MockResponse().setBody(loginReply)); start() }
        try {
            val sm = SessionManager(UbusClient(OkHttpClient()), TrustStore(), OkHttpClient())
            val profile = lan.copy(
                address = closedPortAddress(),
                certificateFingerprint = "HTTP_NO_PIN",
                vpnAddress = vpn.url("/").toString().removeSuffix("/"),
                vpnCertificateFingerprint = "HTTP_NO_PIN"
            )
            sm.login(profile, "pw")
            val s = sm.sessionState.value as SessionState.LoggedIn
            assertEquals(profile.vpnAddress, s.endpoint)
            assertTrue(s.viaVpn)
            assertEquals("the stored profile is not changed", profile, s.profile)
        } finally {
            vpn.shutdown()
        }
    }

    @Test
    fun aWrongPasswordAtHomeIsNotRetriedOverTheVpn() = runBlocking {
        val home = MockWebServer().apply { enqueue(MockResponse().setBody(deniedReply)); start() }
        val vpn = MockWebServer().apply { start() }
        try {
            val sm = SessionManager(UbusClient(OkHttpClient()), TrustStore(), OkHttpClient())
            val profile = lan.copy(
                address = home.url("/").toString().removeSuffix("/"),
                certificateFingerprint = "HTTP_NO_PIN",
                vpnAddress = vpn.url("/").toString().removeSuffix("/"),
                vpnCertificateFingerprint = "HTTP_NO_PIN"
            )
            try {
                sm.login(profile, "wrong")
                fail("wrong password must fail")
            } catch (_: UbusError.AccessDenied) {
            }
            assertEquals("the VPN address must not receive the password", 0, vpn.requestCount)
        } finally {
            home.shutdown(); vpn.shutdown()
        }
    }

    @Test
    fun settingsAcceptOnlyPrivateVpnAddressesAndForgetClearsBothPins() = runBlocking {
        val repo = InMemoryProfileRepository(listOf(lan))
        val trust = TrustStore()
        val h = RouterSettingsHandler(repo, trust, clearWebStorage = {})
        val noDns = com.shaazifi.app.core.net.AddressGuardDns { InetAddress.getAllByName(it).toList() }

        assertEquals(VpnAddressResult.Checked("https://100.64.0.7"), h.checkVpnAddress(lan, "100.64.0.7", noDns))
        assertTrue(h.checkVpnAddress(lan, "8.8.8.8", noDns) is VpnAddressResult.Error)
        assertTrue(h.checkVpnAddress(lan, "https://192.168.1.1/", noDns) is VpnAddressResult.Error)
        assertTrue(h.checkVpnAddress(lan, " ", noDns) is VpnAddressResult.Error)

        val saved = h.saveVpnAddress("r1", "https://100.64.0.7", "BB")!!
        assertEquals("https://100.64.0.7", saved.vpnAddress)
        assertEquals("BB", trust.getPinned("https://100.64.0.7"))

        trust.pin(lan.address, lan.certificateFingerprint)
        h.forgetRouter("r1", lan.address)
        assertNull(trust.getPinned(lan.address))
        assertNull("the VPN pin goes too", trust.getPinned("https://100.64.0.7"))
    }

    @Test
    fun removingTheVpnAddressDropsItsPin() = runBlocking {
        val repo = InMemoryProfileRepository(listOf(lan))
        val trust = TrustStore()
        val h = RouterSettingsHandler(repo, trust, clearWebStorage = {})
        h.saveVpnAddress("r1", "https://100.64.0.7", "BB")
        val p = h.removeVpnAddress("r1")!!
        assertNull(p.vpnAddress)
        assertNull(trust.getPinned("https://100.64.0.7"))
        assertEquals(1, p.endpoints().size)
    }
}
