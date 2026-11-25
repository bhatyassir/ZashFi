package com.shaazifi.app.feature.adguard.manage

import com.shaazifi.app.core.adguard.AdGuardCaller
import com.shaazifi.app.core.adguard.AdGuardEndpoints
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AdGuard Home management pages. Calls and bodies follow AdGuard Home's openapi/openapi.yaml
 * (master, read 2026-11-17); the set of features follows JGeek00/adguard-home-manager
 * (Apache-2.0, used as a reference list only, no code copied).
 */
class AdGuardManageTest {

    private fun j(s: String): JsonElement = Json.parseToJsonElement(s)

    /** Answers GETs from [replies]; records every write. */
    private class FakeCaller(private val replies: Map<String, String>) : AdGuardCaller {
        val sent = mutableListOf<Triple<String, String, String?>>()
        val reads = mutableListOf<String>()
        override suspend fun get(path: String, query: Map<String, String>): JsonElement {
            reads += path
            return Json.parseToJsonElement(replies[path] ?: error("no reply for $path"))
        }
        override suspend fun send(method: String, path: String, body: JsonElement?): JsonElement? {
            sent += Triple(method, path, body?.toString())
            return null
        }
    }

    private val filtering = """{"enabled":true,"interval":24,
        "filters":[{"id":1,"name":"AdGuard DNS filter","url":"https://example.org/a.txt","enabled":true,"rules_count":50000,"last_updated":"2026-11-16T10:00:00Z"},
                   {"id":2,"name":"Old list","url":"https://example.org/b.txt","enabled":false,"rules_count":10}],
        "whitelist_filters":[],
        "user_rules":["||ads.example.com^","@@||good.example.com^"]}"""

    @Test
    fun onlyKnownAdGuardCallsAreAllowed() {
        listOf("/filtering/status", "/blocked_services/all", "/blocked_services/get", "/clients", "/access/list", "/rewrite/list",
            "/rewrite/settings", "/dns_info", "/querylog/config", "/stats/config", "/safesearch/status", "/filtering/check_host")
            .forEach { assertTrue(it, AdGuardEndpoints.isAllowedRead(it)) }
        assertTrue(AdGuardEndpoints.isAllowedWrite("PUT", "/blocked_services/update"))
        assertTrue(AdGuardEndpoints.isAllowedWrite("POST", "/filtering/set_rules"))
        // Never: software update, DHCP, encryption settings, DNS server config, logout.
        listOf("POST" to "/update", "POST" to "/dhcp/set_config", "POST" to "/tls/configure", "POST" to "/dns_config", "POST" to "/logout",
            "PUT" to "/filtering/set_rules", "POST" to "/blocked_services/set")
            .forEach { (m, p) -> assertFalse("$m $p", AdGuardEndpoints.isAllowedWrite(m, p)) }
        assertFalse(AdGuardEndpoints.isAllowedRead("/dhcp/status"))
    }

    @Test
    fun filterListsShowAndChangeOneListAtATime() = runBlocking {
        val v = AgPages.filters(j(filtering).jsonObject)
        val rows = v.sections.first().rows
        assertEquals(listOf("AdGuard DNS filter", "Old list"), rows.map { it.label })
        assertEquals(true, rows[0].toggle)
        val caller = FakeCaller(emptyMap())
        rows[0].onToggle!!.perform(caller)
        assertEquals(Triple("POST", "/filtering/set_url",
            """{"url":"https://example.org/a.txt","whitelist":false,"data":{"name":"AdGuard DNS filter","url":"https://example.org/a.txt","enabled":false}}"""),
            caller.sent.single())
        caller.sent.clear()
        rows[1].extra!!.perform(caller)
        assertEquals(Triple("POST", "/filtering/remove_url", """{"url":"https://example.org/b.txt","whitelist":false}"""), caller.sent.single())
    }

    @Test
    fun addingAListChecksTheAddress() = runBlocking {
        val form = AgPages.filters(j(filtering).jsonObject).forms.first { it.title.contains("blocklist", true) }
        assertTrue(form.build(mapOf("name" to "x", "url" to "ftp://x")) is AgBuild.Invalid)
        assertTrue(form.build(mapOf("name" to "", "url" to "https://example.org/c.txt")) is AgBuild.Invalid)
        val ready = form.build(mapOf("name" to "My list", "url" to "https://example.org/c.txt")) as AgBuild.Ready
        val caller = FakeCaller(emptyMap())
        ready.action.perform(caller)
        assertEquals(Triple("POST", "/filtering/add_url", """{"name":"My list","url":"https://example.org/c.txt","whitelist":false}"""), caller.sent.single())
    }

    @Test
    fun customRulesAreReplacedAsAWholeList() = runBlocking {
        val caller = FakeCaller(mapOf("/filtering/status" to filtering))
        AgChanges.blockDomain("tracker.example.net").perform(caller)
        assertEquals(Triple("POST", "/filtering/set_rules",
            """{"rules":["||ads.example.com^","@@||good.example.com^","||tracker.example.net^"]}"""), caller.sent.single())
        caller.sent.clear()
        AgChanges.allowDomain("ads.example.com").perform(caller)
        assertEquals("""{"rules":["@@||good.example.com^","@@||ads.example.com^"]}""", caller.sent.single().third)
        assertNull("an unsafe domain is refused before anything is sent", AgChanges.domainOrNull("bad domain;"))
        val rules = AgPages.rules(j(filtering).jsonObject).sections.first().rows
        caller.sent.clear()
        rules[0].extra!!.perform(caller)
        assertEquals("""{"rules":["@@||good.example.com^"]}""", caller.sent.single().third)
    }

    @Test
    fun blockedServicesKeepTheSchedule() = runBlocking {
        val all = j("""{"blocked_services":[{"id":"tiktok","name":"TikTok","icon_svg":"x","rules":[]},{"id":"youtube","name":"YouTube","icon_svg":"x","rules":[]}],"groups":[]}""").jsonObject
        val get = j("""{"ids":["tiktok"],"schedule":{"time_zone":"Local"}}""").jsonObject
        val rows = AgPages.services(all, get).sections.flatMap { it.rows }
        assertEquals(true, rows.first { it.label == "TikTok" }.toggle)
        val caller = FakeCaller(emptyMap())
        rows.first { it.label == "YouTube" }.onToggle!!.perform(caller)
        assertEquals(Triple("PUT", "/blocked_services/update", """{"ids":["tiktok","youtube"],"schedule":{"time_zone":"Local"}}"""), caller.sent.single())
    }

    @Test
    fun rewritesAccessListAndSafeSearch() = runBlocking {
        val caller = FakeCaller(emptyMap())
        val rw = AgPages.rewrites(j("""[{"domain":"nas.lan","answer":"192.168.1.20","enabled":true}]""").let { it as kotlinx.serialization.json.JsonArray }, j("""{"enabled":true}""").jsonObject)
        rw.sections.flatMap { it.rows }.first { it.label == "nas.lan" }.extra!!.perform(caller)
        assertEquals(Triple("POST", "/rewrite/delete", """{"domain":"nas.lan","answer":"192.168.1.20","enabled":true}"""), caller.sent.single())
        val add = rw.forms.first().build(mapOf("domain" to "printer.lan", "answer" to "192.168.1.30")) as AgBuild.Ready
        caller.sent.clear(); add.action.perform(caller)
        assertEquals(Triple("POST", "/rewrite/add", """{"domain":"printer.lan","answer":"192.168.1.30"}"""), caller.sent.single())

        val access = AgPages.access(j("""{"allowed_clients":[],"disallowed_clients":["10.0.0.9"],"blocked_hosts":["version.bind"]}""").jsonObject)
        caller.sent.clear()
        access.sections.flatMap { it.rows }.first { it.label == "10.0.0.9" }.extra!!.perform(caller)
        assertEquals(Triple("POST", "/access/set", """{"allowed_clients":[],"disallowed_clients":[],"blocked_hosts":["version.bind"]}"""), caller.sent.single())

        val ss = AgPages.safeSearch(j("""{"enabled":true,"bing":true,"duckduckgo":true,"ecosia":true,"google":true,"pixabay":true,"yandex":true,"youtube":false}""").jsonObject)
        caller.sent.clear()
        ss.sections.flatMap { it.rows }.first { it.label == "YouTube" }.onToggle!!.perform(caller)
        assertEquals(Triple("PUT", "/safesearch/settings",
            """{"enabled":true,"bing":true,"duckduckgo":true,"ecosia":true,"google":true,"pixabay":true,"yandex":true,"youtube":true}"""), caller.sent.single())
    }

    @Test
    fun logAndStatisticsSettingsKeepOtherFields() = runBlocking {
        val q = j("""{"enabled":true,"interval":7776000000,"anonymize_client_ip":false,"ignored":["a.lan"],"ignored_enabled":true}""").jsonObject
        val s = j("""{"enabled":true,"interval":86400000,"ignored":[],"ignored_enabled":false}""").jsonObject
        val v = AgPages.logsStats(q, s)
        val caller = FakeCaller(emptyMap())
        v.sections.flatMap { it.rows }.first { it.label == "Hide client addresses" }.onToggle!!.perform(caller)
        assertEquals(Triple("PUT", "/querylog/config/update",
            """{"enabled":true,"interval":7776000000,"anonymize_client_ip":true,"ignored":["a.lan"],"ignored_enabled":true}"""), caller.sent.single())
        assertTrue(v.sections.flatMap { it.rows }.any { it.label == "Keep query log" && it.value == "90 days" })
    }

    @Test
    fun clientsShowSavedAndSeen() = runBlocking {
        val clients = j("""{"clients":[{"name":"Laptop","ids":["192.168.1.5"],"use_global_settings":true}],
            "auto_clients":[{"ip":"192.168.1.9","name":"phone.lan","source":"rDNS"}],"supported_tags":[]}""").jsonObject
        val v = AgPages.clients(clients)
        val caller = FakeCaller(emptyMap())
        v.sections.flatMap { it.rows }.first { it.label == "Laptop" }.extra!!.perform(caller)
        assertEquals(Triple("POST", "/clients/delete", """{"name":"Laptop"}"""), caller.sent.single())
        assertTrue(v.sections.flatMap { it.rows }.any { it.label == "phone.lan" })
        val add = v.forms.first().build(mapOf("name" to "TV", "ids" to "192.168.1.40")) as AgBuild.Ready
        caller.sent.clear(); add.action.perform(caller)
        val body = Json.parseToJsonElement(caller.sent.single().third!!).jsonObject
        assertEquals("/clients/add", caller.sent.single().second)
        assertEquals("\"TV\"", body["name"].toString())
        assertEquals("""["192.168.1.40"]""", body["ids"].toString())
    }

    @Test
    fun nothingIsSentBeforeTheOwnerConfirms() = runBlocking {
        val caller = FakeCaller(mapOf("/filtering/status" to filtering))
        val holder = AgPageHolder(AgPage.FILTERS, caller)
        holder.refresh()
        val action = (holder.state.value as AgState.Loaded).view.sections.first().rows[0].onToggle!!
        holder.request(action)
        assertEquals(action, holder.pending.value)
        assertTrue("no write before confirm", caller.sent.isEmpty())
        holder.dismiss()
        assertTrue(caller.sent.isEmpty())
        holder.request(action)
        holder.confirm()
        assertEquals(1, caller.sent.size)
        assertNull(holder.pending.value)
    }

    @Test
    fun queryLogStatusFilterUsesTheServersValues() {
        // AdGuard Home internal/querylog/deprecated.go (master): accepted response_status values.
        assertEquals(listOf("all", "filtered", "blocked", "whitelisted", "rewritten", "processed"), AgQueryFilter.entries.map { it.value })
    }
}
