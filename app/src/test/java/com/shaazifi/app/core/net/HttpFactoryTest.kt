package com.shaazifi.app.core.net

import okhttp3.Dns
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress

class HttpFactoryTest {

    @Test
    fun testHttpFactoryBlocksPublicAddress() {
        val client = HttpFactory.createClient()
        val request = Request.Builder().url("https://8.8.8.8/").build()
        try {
            client.newCall(request).execute()
            fail("Expected AddressNotAllowedException for 8.8.8.8")
        } catch (e: AddressNotAllowedException) {
            assertEquals("8.8.8.8", e.host)
        }
    }

    @Test
    fun testHttpFactoryBlocksRedirectToPublicAddress() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "http://8.8.8.8/evil")
        )
        server.start()

        try {
            val client = HttpFactory.createClient()
            val request = Request.Builder()
                .url(server.url("/start").newBuilder().host("127.0.0.1").build())
                .build()

            try {
                client.newCall(request).execute()
                fail("Expected AddressNotAllowedException on redirect to 8.8.8.8")
            } catch (e: Exception) {
                var found = false
                fun check(t: Throwable?) {
                    if (t == null || found) return
                    if (t is AddressNotAllowedException) {
                        found = true
                        assertEquals("8.8.8.8", t.host)
                        return
                    }
                    check(t.cause)
                    for (s in t.suppressed) check(s)
                }
                check(e)
                assertTrue("Expected AddressNotAllowedException on redirect, got $e", found)
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testHttpFactoryBlocksDnsResolvingToPublicAddress() {
        val mockDns = object : AddressGuardDns {
            override fun lookup(hostname: String): List<InetAddress> {
                return if (hostname.equals("router.lan", ignoreCase = true)) {
                    listOf(InetAddress.getByName("8.8.8.8"))
                } else {
                    Dns.SYSTEM.lookup(hostname)
                }
            }
        }

        val client = HttpFactory.createClient(dns = mockDns)
        val request = Request.Builder().url("http://router.lan/").build()
        try {
            client.newCall(request).execute()
            fail("Expected AddressNotAllowedException for router.lan resolving to public 8.8.8.8")
        } catch (e: AddressNotAllowedException) {
            assertEquals("router.lan", e.host)
        }
    }
}

