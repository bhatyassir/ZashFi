package com.shaazifi.app.feature.routers

import com.shaazifi.app.core.net.HttpFactory
import com.shaazifi.app.core.net.TrustStore
import com.shaazifi.app.core.session.ProbeDiagnostic
import com.shaazifi.app.core.session.SessionManager
import com.shaazifi.app.core.ubus.UbusClient
import kotlinx.coroutines.runBlocking
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
}
