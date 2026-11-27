package com.shaazifi.app.feature.routers

import com.shaazifi.app.core.net.HttpFactory
import com.shaazifi.app.core.net.TrustStore
import com.shaazifi.app.core.session.ProbeDiagnostic
import com.shaazifi.app.core.session.SessionManager
import com.shaazifi.app.core.ubus.UbusClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddRouterStateHolderTest {

    @Test
    fun testAddressChangeResetsErrorMessageAndDiagnostic() = runBlocking {
        val trustStore = TrustStore()
        val client = HttpFactory.createClient(trustStore)
        val ubusClient = UbusClient(client)
        val sessionManager = SessionManager(ubusClient, trustStore, client)

        val holder = AddRouterStateHolder(sessionManager)
        holder.openDialog(initialAddress = "http://127.0.0.1:1", initialName = "Failing Router")

        // Trigger probe to force an error
        holder.probe()

        // Verify error state is populated
        val errorState = holder.state.value
        assertFalse(errorState.isProbing)
        assertNotNull(errorState.errorMessage)
        assertNotNull(errorState.diagnostic)
        assertFalse(errorState.diagnostic!!.socketConnected)

        // Now change address text: MUST clear error state immediately!
        holder.onAddressChanged("http://192.168.1.1")

        val resetState = holder.state.value
        assertEquals("http://192.168.1.1", resetState.address)
        assertNull("Error message must reset when address changes", resetState.errorMessage)
        assertNull("Diagnostic must reset when address changes", resetState.diagnostic)
    }

    @Test
    fun testNameChangeResetsErrorMessageAndDiagnostic() = runBlocking {
        val trustStore = TrustStore()
        val client = HttpFactory.createClient(trustStore)
        val ubusClient = UbusClient(client)
        val sessionManager = SessionManager(ubusClient, trustStore, client)

        val holder = AddRouterStateHolder(sessionManager)
        holder.openDialog(initialAddress = "http://127.0.0.1:1", initialName = "Failing Router")

        holder.probe()
        assertNotNull(holder.state.value.errorMessage)

        // Changing name also clears previous probe error
        holder.onNameChanged("New Router Name")

        val resetState = holder.state.value
        assertEquals("New Router Name", resetState.name)
        assertNull(resetState.errorMessage)
        assertNull(resetState.diagnostic)
    }

    @Test
    fun testProbeNormalizesAddressOnSuccess() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        server.start()
        try {
            val trustStore = TrustStore()
            val client = HttpFactory.createClient(trustStore)
            val sessionManager = SessionManager(UbusClient(client), trustStore, client)
            val holder = AddRouterStateHolder(sessionManager)

            val rawAddress = "  http://127.0.0.1:${server.port}/  "
            holder.openDialog(initialAddress = rawAddress, initialName = "My Router")
            holder.probe()

            val state = holder.state.value
            assertFalse(state.isProbing)
            assertTrue(state.showApprovalDialog)
            assertTrue(state.isHttp)
            assertEquals("http://127.0.0.1:${server.port}", state.address)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testProbeCandidatesFallsBackFromUnreachableHttpsToHttp() = runBlocking {
        val httpServer = MockWebServer()
        httpServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        httpServer.start()
        try {
            val trustStore = TrustStore()
            val client = HttpFactory.createClient(trustStore)
            val sessionManager = SessionManager(UbusClient(client), trustStore, client)
            val holder = AddRouterStateHolder(sessionManager)

            val unreachableHttps = "https://127.0.0.1:1"
            val workingHttp = "http://127.0.0.1:${httpServer.port}"
            holder.openDialog(initialAddress = unreachableHttps, initialName = "Home Gateway")
            holder.probeCandidates(listOf(unreachableHttps, workingHttp))

            val state = holder.state.value
            assertFalse(state.isProbing)
            assertTrue(state.showApprovalDialog)
            assertTrue(state.isHttp)
            assertEquals(workingHttp, state.address)
            assertEquals(RouterDisplay.HTTP_NO_PIN, state.discoveredFingerprint)
            assertNull(state.errorMessage)
        } finally {
            httpServer.shutdown()
        }
    }
}
