package com.shaazifi.app.core.ubus

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UbusClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val successLoginResponse = """
        {
            "jsonrpc": "2.0",
            "id": 1,
            "result": [
                0,
                {
                    "ubus_rpc_session": "e89547d7c6792fca0cb329c2ab87dc79",
                    "timeout": 300,
                    "expires": 300,
                    "data": {
                        "username": "root"
                    }
                }
            ]
        }
    """.trimIndent()

    private val accessDeniedResponse = """
        {
            "jsonrpc": "2.0",
            "id": 1,
            "result": [6]
        }
    """.trimIndent()

    private val sessionDestroyResponse = """
        {
            "jsonrpc": "2.0",
            "id": 1,
            "result": [0]
        }
    """.trimIndent()

    @Test
    fun testLoginSuccessParsesSessionTokenAndTimeout() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody(successLoginResponse))
        server.start()

        try {
            val client = UbusClient(OkHttpClient())
            val baseUrl = server.url("/ubus").toString()

            val session = client.login(baseUrl, "root", "good_password")
            assertEquals("e89547d7c6792fca0cb329c2ab87dc79", session.token)
            assertEquals(300, session.timeoutSeconds)
            assertEquals("root", session.username)

            // Verify request body sent by UbusClient
            val recorded = server.takeRequest()
            val requestJson = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
            assertEquals("2.0", requestJson["jsonrpc"]?.jsonPrimitive?.content)
            assertEquals("call", requestJson["method"]?.jsonPrimitive?.content)

            val params = requestJson["params"]!!.jsonArray
            assertEquals("00000000000000000000000000000000", params[0].jsonPrimitive.content)
            assertEquals("session", params[1].jsonPrimitive.content)
            assertEquals("login", params[2].jsonPrimitive.content)

            val args = params[3].jsonObject
            assertEquals("root", args["username"]?.jsonPrimitive?.content)
            assertEquals("good_password", args["password"]?.jsonPrimitive?.content)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testLoginWrongPasswordThrowsAccessDeniedError() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody(accessDeniedResponse))
        server.start()

        try {
            val client = UbusClient(OkHttpClient())
            val baseUrl = server.url("/ubus").toString()

            try {
                client.login(baseUrl, "root", "wrong_password")
                fail("Expected UbusError.AccessDenied on code 6")
            } catch (e: UbusError.AccessDenied) {
                assertEquals(6, e.code)
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testSessionDestroyCallsDestroyWithActiveToken() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody(sessionDestroyResponse))
        server.start()

        try {
            val client = UbusClient(OkHttpClient())
            val baseUrl = server.url("/ubus").toString()

            val success = client.destroySession(baseUrl, "e89547d7c6792fca0cb329c2ab87dc79")
            assertTrue(success)

            val recorded = server.takeRequest()
            val requestJson = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
            val params = requestJson["params"]!!.jsonArray
            assertEquals("e89547d7c6792fca0cb329c2ab87dc79", params[0].jsonPrimitive.content)
            assertEquals("session", params[1].jsonPrimitive.content)
            assertEquals("destroy", params[2].jsonPrimitive.content)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testGenericCallParsesJsonResult() = runBlocking {
        val genericResponse = """
            {
                "jsonrpc": "2.0",
                "id": 1,
                "result": [
                    0,
                    {
                        "model": "GL.iNet GL-MT3000",
                        "release": {
                            "version": "SNAPSHOT"
                        }
                    }
                ]
            }
        """.trimIndent()

        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody(genericResponse))
        server.start()

        try {
            val client = UbusClient(OkHttpClient())
            val baseUrl = server.url("/ubus").toString()

            val result = client.call(
                endpointUrl = baseUrl,
                token = "valid_token",
                ubusObject = "system",
                method = "board",
                args = buildJsonObject {}
            )

            assertNotNull(result)
            assertEquals("GL.iNet GL-MT3000", result["model"]?.jsonPrimitive?.content)
            val releaseObj = result["release"]?.jsonObject
            assertEquals("SNAPSHOT", releaseObj?.get("version")?.jsonPrimitive?.content)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testNormalizeUbusUrlAlwaysTargetsUbusRoot() {
        val client = UbusClient(OkHttpClient())
        assertEquals("https://100.64.0.7/ubus", client.normalizeUbusUrl("https://100.64.0.7"))
        assertEquals("https://100.64.0.7/ubus", client.normalizeUbusUrl("https://100.64.0.7/"))
        assertEquals("https://100.64.0.7/ubus", client.normalizeUbusUrl("https://100.64.0.7/untangled"))
        assertEquals("https://100.64.0.7/ubus", client.normalizeUbusUrl("https://100.64.0.7/untangled/"))
        assertEquals("https://100.64.0.7/ubus", client.normalizeUbusUrl("https://100.64.0.7/ubus"))
        assertEquals("https://100.64.0.7/ubus", client.normalizeUbusUrl("https://100.64.0.7/ubus/"))
    }

    @Test
    fun testLoginWithSubpathSendsRequestToUbusRoot() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody(successLoginResponse))
        server.start()

        try {
            val client = UbusClient(OkHttpClient())
            val subpathUrl = server.url("/untangled/").toString()

            val session = client.login(subpathUrl, "root", "good_password")
            assertEquals("e89547d7c6792fca0cb329c2ab87dc79", session.token)

            val recorded = server.takeRequest()
            assertEquals("/ubus", recorded.path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testCallMalformedJsonThrowsInvalidJson() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>502 Bad Gateway</html>"))
        server.start()

        try {
            val client = UbusClient(OkHttpClient())
            client.call(server.url("/ubus").toString(), "token", "system", "info")
            fail("Expected UbusError.InvalidJson")
        } catch (e: UbusError.InvalidJson) {
            assertTrue(e.rawResponse.contains("502 Bad Gateway"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testCallMissingResultArrayThrowsInvalidJson() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"jsonrpc":"2.0","id":1}"""))
        server.start()

        try {
            val client = UbusClient(OkHttpClient())
            client.call(server.url("/ubus").toString(), "token", "system", "info")
            fail("Expected UbusError.InvalidJson")
        } catch (e: UbusError.InvalidJson) {
            assertTrue(e.message?.contains("missing 'result'") == true)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testCallEmptyResultArrayThrowsInvalidJson() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"jsonrpc":"2.0","id":1,"result":[]}"""))
        server.start()

        try {
            val client = UbusClient(OkHttpClient())
            client.call(server.url("/ubus").toString(), "token", "system", "info")
            fail("Expected UbusError.InvalidJson")
        } catch (e: UbusError.InvalidJson) {
            assertTrue(e.message?.contains("Empty 'result'") == true)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testCallSessionExpiredWithReloginRetriesOnceAndSucceeds() = runBlocking {
        val server = MockWebServer()
        // First request returns Session Expired [4]
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"jsonrpc":"2.0","id":1,"result":[4]}"""))
        // Second request with new token returns success [0, {"status": "ok"}]
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"jsonrpc":"2.0","id":2,"result":[0, {"status":"ok"}]}"""))
        server.start()

        try {
            val client = UbusClient(OkHttpClient())
            var reloginCount = 0

            val result = client.call(
                endpointUrl = server.url("/ubus").toString(),
                token = "expired_token",
                ubusObject = "system",
                method = "info",
                onSessionExpired = {
                    reloginCount++
                    "fresh_token_123"
                }
            )

            assertEquals(1, reloginCount)
            assertEquals("ok", result["status"]?.jsonPrimitive?.content)

            // Verify two requests were made
            val req1 = server.takeRequest()
            val req2 = server.takeRequest()
            val params1 = json.parseToJsonElement(req1.body.readUtf8()).jsonObject["params"]!!.jsonArray
            val params2 = json.parseToJsonElement(req2.body.readUtf8()).jsonObject["params"]!!.jsonArray
            assertEquals("expired_token", params1[0].jsonPrimitive.content)
            assertEquals("fresh_token_123", params2[0].jsonPrimitive.content)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testCallSessionExpiredWithReloginThrowsIfRetryAlsoFails() = runBlocking {
        val server = MockWebServer()
        // First request returns Session Expired [4]
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"jsonrpc":"2.0","id":1,"result":[4]}"""))
        // Second request also returns Session Expired [4]
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"jsonrpc":"2.0","id":2,"result":[4]}"""))
        server.start()

        try {
            val client = UbusClient(OkHttpClient())
            var reloginCount = 0

            try {
                client.call(
                    endpointUrl = server.url("/ubus").toString(),
                    token = "expired_token",
                    ubusObject = "system",
                    method = "info",
                    onSessionExpired = {
                        reloginCount++
                        "another_expired_token"
                    }
                )
                fail("Expected UbusError.SessionExpired after single retry")
            } catch (e: UbusError.SessionExpired) {
                assertEquals(4, e.code)
            }

            assertEquals(1, reloginCount) // Strictly one retry
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testCallAccessDeniedRpcErrorWithReloginRetriesOnceAndSucceeds() = runBlocking {
        val server = MockWebServer()
        // First request: router returns Access denied error (-32002) for expired session
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"jsonrpc":"2.0","id":1,"error":{"code":-32002,"message":"Access denied"}}"""
            )
        )
        // Second request with fresh token succeeds
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"jsonrpc":"2.0","id":2,"result":[0, {"status":"recovered"}]}"""
            )
        )
        server.start()

        try {
            val client = UbusClient(OkHttpClient())
            var reloginCount = 0

            val result = client.call(
                endpointUrl = server.url("/ubus").toString(),
                token = "expired_token_123",
                ubusObject = "system",
                method = "board",
                onSessionExpired = {
                    reloginCount++
                    "fresh_token_456"
                }
            )

            assertEquals(1, reloginCount)
            assertEquals("recovered", result["status"]?.jsonPrimitive?.content)

            val req1 = server.takeRequest()
            val req2 = server.takeRequest()
            val params1 = json.parseToJsonElement(req1.body.readUtf8()).jsonObject["params"]!!.jsonArray
            val params2 = json.parseToJsonElement(req2.body.readUtf8()).jsonObject["params"]!!.jsonArray
            assertEquals("expired_token_123", params1[0].jsonPrimitive.content)
            assertEquals("fresh_token_456", params2[0].jsonPrimitive.content)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testCallAccessDeniedStatusCode6WithReloginRetriesOnceAndSucceeds() = runBlocking {
        val server = MockWebServer()
        // First request: router returns ubus status [6] (permission denied)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"jsonrpc":"2.0","id":1,"result":[6]}"""
            )
        )
        // Second request with fresh token succeeds
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"jsonrpc":"2.0","id":2,"result":[0, {"status":"recovered_status6"}]}"""
            )
        )
        server.start()

        try {
            val client = UbusClient(OkHttpClient())
            var reloginCount = 0

            val result = client.call(
                endpointUrl = server.url("/ubus").toString(),
                token = "stale_token_abc",
                ubusObject = "network.interface",
                method = "dump",
                onSessionExpired = {
                    reloginCount++
                    "fresh_token_xyz"
                }
            )

            assertEquals(1, reloginCount)
            assertEquals("recovered_status6", result["status"]?.jsonPrimitive?.content)

            val req1 = server.takeRequest()
            val req2 = server.takeRequest()
            val params1 = json.parseToJsonElement(req1.body.readUtf8()).jsonObject["params"]!!.jsonArray
            val params2 = json.parseToJsonElement(req2.body.readUtf8()).jsonObject["params"]!!.jsonArray
            assertEquals("stale_token_abc", params1[0].jsonPrimitive.content)
            assertEquals("fresh_token_xyz", params2[0].jsonPrimitive.content)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testCallAccessDeniedWithReloginThrowsIfRetryAlsoDeniedNeverLoops() = runBlocking {
        val server = MockWebServer()
        // First request: Access denied (-32002)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"jsonrpc":"2.0","id":1,"error":{"code":-32002,"message":"Access denied"}}"""
            )
        )
        // Second request with refreshed token is STILL denied (-32002) because user lacks permission
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"jsonrpc":"2.0","id":2,"error":{"code":-32002,"message":"Access denied"}}"""
            )
        )
        server.start()

        try {
            val client = UbusClient(OkHttpClient())
            var reloginCount = 0

            try {
                client.call(
                    endpointUrl = server.url("/ubus").toString(),
                    token = "token_lacking_permission",
                    ubusObject = "restricted.object",
                    method = "restricted_method",
                    onSessionExpired = {
                        reloginCount++
                        "new_token_still_lacking_permission"
                    }
                )
                fail("Expected UbusError.AccessDenied when retry also lacks permission")
            } catch (e: UbusError.AccessDenied) {
                assertEquals(-32002, e.code)
            }

            // Must have attempted re-login at most ONCE, never looped!
            assertEquals(1, reloginCount)
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
