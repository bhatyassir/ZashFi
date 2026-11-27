/*
 * Copyright (C) 2026 ZashFi contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.shaazifi.app.feature.adguard.manage

import com.shaazifi.app.core.adguard.AdGuardCaller
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AdGuardRewriteBulkTest {

    private fun j(s: String): JsonElement = Json.parseToJsonElement(s)

    private fun r(domain: String, answer: String) = RewriteRule(domain, answer)

    /** Records every call in order and can fail on a chosen send. */
    private class FakeCaller(
        private val getReplies: Map<String, JsonElement> = emptyMap(),
        private val failOnSend: (Int) -> Boolean = { false }
    ) : AdGuardCaller {
        data class Call(val method: String, val path: String, val body: JsonElement?)

        val calls = mutableListOf<Call>()
        val reads = mutableListOf<String>()

        override suspend fun get(path: String, query: Map<String, String>): JsonElement {
            reads += path
            return getReplies[path] ?: throw AssertionError("no reply for $path")
        }

        override suspend fun send(method: String, path: String, body: JsonElement?): JsonElement? {
            calls += Call(method, path, body)
            if (failOnSend(calls.size)) throw IOException("router refused the change")
            return null
        }
    }

    @Test
    fun parseIgnoresCommentsAndCollectsErrors() {
        val text = listOf(
            "# a comment",
            "",
            "nas.lan 192.168.1.20",
            "http://bad.example 1.2.3.4",
            "bad host 1.2.3.4",
            "noanswer.lan",
            "wild.lan\t1.2.3.5"
        ).joinToString("\n")
        val result = parse(text)
        assertEquals(listOf(r("nas.lan", "192.168.1.20"), r("wild.lan", "1.2.3.5")), result.rules)
        assertEquals(3, result.errors.size)
        assertTrue(result.errors[0].contains("Line 4"))
        assertTrue(result.errors[1].contains("Line 5"))
        assertTrue(result.errors[2].contains("Line 6"))

        val longAnswer = "x".repeat(256)
        val tooLong = parse("long.lan $longAnswer")
        assertTrue(tooLong.rules.isEmpty())
        assertTrue(tooLong.errors.single().contains("too long"))
    }

    @Test
    fun parseAndSerialiseRoundTrip() {
        val text = listOf(
            "nas.lan 192.168.1.20",
            "*.wild.lan router.lan",
            "# ignored",
            "",
            "mail.example.test 10.0.0.9"
        ).joinToString("\n")
        val result = parse(text)
        assertEquals(emptyList<String>(), result.errors)
        assertEquals(
            listOf(
                r("nas.lan", "192.168.1.20"),
                r("*.wild.lan", "router.lan"),
                r("mail.example.test", "10.0.0.9")
            ),
            result.rules
        )
        assertEquals(
            "nas.lan 192.168.1.20\n*.wild.lan router.lan\nmail.example.test 10.0.0.9",
            serialise(result.rules)
        )
        assertEquals("", serialise(emptyList()))
    }

    @Test
    fun diffMatchesOnBothDomainAndAnswer() {
        val current = listOf(r("a.lan", "1.1.1.1"), r("b.lan", "2.2.2.2"), r("keep.lan", "9.9.9.9"))
        val desired = listOf(r("keep.lan", "9.9.9.9"), r("a.lan", "3.3.3.3"), r("c.lan", "4.4.4.4"))
        val change = diff(current, desired)
        assertEquals(listOf(r("a.lan", "3.3.3.3"), r("c.lan", "4.4.4.4")), change.add)
        assertEquals(listOf(r("a.lan", "1.1.1.1"), r("b.lan", "2.2.2.2")), change.remove)
        assertEquals(BulkChange(emptyList(), emptyList()), diff(current, current))
    }

    @Test
    fun applyBulkDeletesBeforeAddsWithSingleRuleBodies(): Unit = runTest {
        val caller = FakeCaller()
        val change = BulkChange(
            remove = listOf(r("old.lan", "1.2.3.4")),
            add = listOf(r("new.lan", "5.6.7.8"), r("*.wild.lan", "router.lan"))
        )
        assertEquals(3, caller.applyBulk(change))
        assertEquals(
            listOf(
                Triple("POST", "/rewrite/delete", """{"domain":"old.lan","answer":"1.2.3.4"}"""),
                Triple("POST", "/rewrite/add", """{"domain":"new.lan","answer":"5.6.7.8"}"""),
                Triple("POST", "/rewrite/add", """{"domain":"*.wild.lan","answer":"router.lan"}""")
            ),
            caller.calls.map { Triple(it.method, it.path, it.body?.toString()) }
        )
    }

    @Test
    fun applyBulkStopsAtFirstFailureAndReportsCount(): Unit = runTest {
        val caller = FakeCaller(failOnSend = { it == 2 })
        val change = BulkChange(
            remove = listOf(r("a.lan", "1.1.1.1"), r("b.lan", "2.2.2.2")),
            add = listOf(r("c.lan", "3.3.3.3"))
        )
        assertEquals(1, caller.applyBulk(change))
        assertEquals(2, caller.calls.size)
        assertEquals("/rewrite/delete", caller.calls.last().path)
    }

    @Test
    fun exportRewritesSkipsMalformedItems(): Unit = runTest {
        val list = j(
            """[
                {"domain":"nas.lan","answer":"192.168.1.20"},
                {"domain":"*.wild.lan","answer":"router.lan","disabled":true},
                {"domain":"http://bad","answer":"1.2.3.4"},
                {"domain":"noanswer.lan"},
                {"domain":"blank.lan","answer":"   "},
                "junk"
            ]"""
        ) as JsonArray
        val caller = FakeCaller(mapOf("/rewrite/list" to list))
        assertEquals(
            "nas.lan 192.168.1.20\n*.wild.lan router.lan",
            caller.exportRewrites()
        )
        assertEquals(listOf("/rewrite/list"), caller.reads)
        assertTrue(caller.calls.isEmpty())
    }
}
