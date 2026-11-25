package com.shaazifi.app.feature.adguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdGuardParsersTest {

    @Test
    fun testParseStatus() {
        val json = """
            {
                "version": "v0.107.54",
                "language": "en",
                "dns_addresses": ["198.51.100.1"],
                "dns_port": 53,
                "http_port": 3000,
                "protection_enabled": true,
                "protection_disabled_duration": 600000,
                "dhcp_available": false,
                "running": true
            }
        """.trimIndent()

        val status = AdGuardParsers.parseStatus(json)
        assertEquals("v0.107.54", status.version)
        assertTrue(status.protectionEnabled)
        assertEquals(600000L, status.protectionDisabledDurationMs)
        assertEquals(53, status.dnsPort)
        assertEquals(3000, status.httpPort)
        assertTrue(status.running)
    }

    @Test
    fun testParseStatsFormulasAndUnits() {
        // In AdGuard openapi.yaml, avg_processing_time is float in SECONDS
        val json = """
            {
                "time_units": "hours",
                "num_dns_queries": 48213,
                "num_blocked_filtering": 6127,
                "num_replaced_safebrowsing": 12,
                "num_replaced_safesearch": 0,
                "num_replaced_parental": 0,
                "avg_processing_time": 0.00912,
                "dns_queries": [120, 150, 200, 310],
                "top_queried_domains": [
                    {"example-1.test": 1250},
                    {"example-2.test": 850}
                ],
                "top_blocked_domains": [
                    {"example-3.test": 1902},
                    {"example-4.test": 1266}
                ],
                "top_clients": [
                    {"198.51.100.20": 14880},
                    {"198.51.100.42": 11204}
                ]
            }
        """.trimIndent()

        val stats = AdGuardParsers.parseStats(json)
        assertEquals(48213L, stats.numDnsQueries)
        assertEquals(6127L, stats.numBlockedFiltering)
        assertEquals(12L, stats.numReplacedSafebrowsing)
        assertEquals(0.00912, stats.avgProcessingTimeSeconds, 0.00001)

        // Calculated values:
        // avgProcessingTimeMs = avg_processing_time * 1000 = ~9.12 ms
        assertEquals(9.12, stats.avgProcessingTimeMs, 0.01)

        // blockedPercentage = (6127 / 48213) * 100 = 12.708%
        assertEquals(12.71, stats.blockedPercentage, 0.01)

        // Top queried domains
        assertEquals(2, stats.topQueriedDomains.size)
        assertEquals("example-1.test", stats.topQueriedDomains[0].first)
        assertEquals(1250L, stats.topQueriedDomains[0].second)

        // Top blocked domains
        assertEquals(2, stats.topBlockedDomains.size)
        assertEquals("example-3.test", stats.topBlockedDomains[0].first)
        assertEquals(1902L, stats.topBlockedDomains[0].second)

        // Top clients
        assertEquals(2, stats.topClients.size)
        assertEquals("198.51.100.20", stats.topClients[0].first)
        assertEquals(14880L, stats.topClients[0].second)
    }

    @Test
    fun testParseStatsConfigPeriodLabel() {
        // 1 day = 24 hours -> "Last 24 hours"
        val config1Day = """
            {
                "enabled": true,
                "interval": 1,
                "ignored": []
            }
        """.trimIndent()
        val statsConfig1 = AdGuardParsers.parseStatsConfig(config1Day)
        assertEquals("Last 24 hours", statsConfig1.periodLabel)

        // 7 days -> "Last 7 days"
        val config7Days = """
            {
                "enabled": true,
                "interval": 7,
                "ignored": []
            }
        """.trimIndent()
        val statsConfig7 = AdGuardParsers.parseStatsConfig(config7Days)
        assertEquals("Last 7 days", statsConfig7.periodLabel)

        // 30 days -> "Last 30 days"
        val config30Days = """
            {
                "enabled": true,
                "interval": 30,
                "ignored": []
            }
        """.trimIndent()
        val statsConfig30 = AdGuardParsers.parseStatsConfig(config30Days)
        assertEquals("Last 30 days", statsConfig30.periodLabel)
    }

    @Test
    fun statsPeriodUsesTheServerIntervalInMilliseconds() {
        assertEquals("Last 7 days", AdGuardParsers.parseStatsConfig("""{"interval":604800000}""").periodLabel)
        assertEquals("Last 12 hours", AdGuardParsers.parseStatsConfig("""{"interval":43200000}""").periodLabel)
        assertEquals("Statistics period unavailable", AdGuardParsers.parseStatsConfig("{}").periodLabel)
    }

    @Test
    fun testParseQueryLogModernSchemaWithReason() {
        val modernJson = """
            {
                "oldest": "2026-11-16T10:00:00Z",
                "data": [
                    {
                        "time": "2026-11-16T15:45:30.123456789+05:30",
                        "question": {
                            "name": "example-1.test",
                            "type": "A",
                            "class": "IN"
                        },
                        "client": "198.51.100.20",
                        "client_id": "test-phone",
                        "client_info": {
                            "name": ""
                        },
                        "reason": "FilteredBlackList",
                        "status": "NXDOMAIN",
                        "elapsedMs": "4.2",
                        "rules": [
                            {"text": "||example-1.test^", "filter_list_id": 1}
                        ]
                    }
                ]
            }
        """.trimIndent()

        val log = AdGuardParsers.parseQueryLog(modernJson)
        assertEquals(1, log.items.size)
        val item = log.items[0]
        assertEquals("example-1.test", item.questionName)
        assertEquals("A", item.questionType)
        assertEquals("198.51.100.20", item.client)
        assertEquals("test-phone", item.clientId)
        assertEquals("2026-11-16T10:15:30.123456789Z", item.timestamp.toString())
        assertEquals("FilteredBlackList", item.reason)
        assertEquals("4.2", item.elapsedMs)
        assertTrue(item.isBlocked)
        assertEquals("Blocked", item.outcomeLabel)
        assertEquals("NXDOMAIN", item.responseStatus)
        assertEquals(listOf("||example-1.test^"), item.rules)
    }

    @Test
    fun testParseQueryLogLegacySchemaWithResponseStatus() {
        val legacyJson = """
            {
                "oldest": "2026-11-16T10:00:00Z",
                "data": [
                    {
                        "time": "2026-11-16T10:15:30Z",
                        "question": {
                            "name": "example-2.test",
                            "type": "AAAA",
                            "class": "IN"
                        },
                        "client": "198.51.100.31",
                        "response_status": "BLOCKED",
                        "elapsed_ms": "1.8",
                        "rules": []
                    }
                ]
            }
        """.trimIndent()

        val log = AdGuardParsers.parseQueryLog(legacyJson)
        assertEquals(1, log.items.size)
        val item = log.items[0]
        assertEquals("example-2.test", item.questionName)
        assertEquals("AAAA", item.questionType)
        assertEquals("BLOCKED", item.reason)
        assertEquals("1.8", item.elapsedMs)
        assertTrue(item.isBlocked)
        assertNull(item.responseStatus)
        assertNull(item.upstream)
        assertNull(item.cached)
        assertTrue(item.answers.isEmpty())
    }

    @Test
    fun inspectorReadsResponseDetailsWhenProvided() {
        val item = AdGuardParsers.parseQueryLog("""
            {"data":[{"reason":"Rewritten","status":"NOERROR",
              "upstream":"https://resolver.example.test/dns-query","cached":true,
              "answer":[{"type":"AAAA","value":"2001:db8::42","ttl":4294967295}, {"type":"A","value":null}]}]}
        """.trimIndent()).items.single()
        assertEquals("Rewritten", item.outcomeLabel)
        assertEquals("NOERROR", item.responseStatus)
        assertEquals("https://resolver.example.test/dns-query", item.upstream)
        assertEquals(true, item.cached)
        assertEquals(listOf(AdGuardDnsAnswer("AAAA", "2001:db8::42", 4294967295L)), item.answers)
    }

    @Test
    fun queryLogClientOptionsUseOnlyIdentifiersPresentInLogRecords() {
        val clients = AdGuardParsers.parseQueryLogClients("""
            {
              "clients": [
                {"name":"TV","ids":["192.0.2.20","00:11:22:33:44:55","192.0.2.0/24"]},
                {"name":"Phone","ids":["phone-1"]}
              ],
              "auto_clients": [{"name":"Discovered","ip":"2001:db8::2"},{"name":"Other TV name","ip":"192.0.2.20"}]
            }
        """.trimIndent())
        assertEquals(listOf("192.0.2.20", "phone-1", "2001:db8::2"), clients.map { it.identifier })
        assertEquals("TV", clients.first().label)
    }

    @Test
    fun testParseFilteringStatus() {
        val json = """
            {
                "enabled": true,
                "interval": 24,
                "filters": [
                    {"id": 1, "name": "AdGuard DNS filter", "rules_count": 45000, "enabled": true},
                    {"id": 2, "name": "AdAway Default Blocklist", "rules_count": 8200, "enabled": true},
                    {"id": 3, "name": "Malware Domain List", "rules_count": 1200, "enabled": true},
                    {"id": 4, "name": "Tracking Protection", "rules_count": 6400, "enabled": true}
                ],
                "whitelist_filters": [],
                "user_rules": [
                    "@@||example.com^",
                    "||bad-tracker.com^",
                    "||analytics.local^"
                ]
            }
        """.trimIndent()

        val filtering = AdGuardParsers.parseFilteringStatus(json)
        assertTrue(filtering.enabled)
        assertEquals(4, filtering.activeFilterCount)
        assertEquals(3, filtering.userRuleCount)
    }

    @Test
    fun testParseSafeSearchStatus() {
        val json = """
            {
                "enabled": true,
                "bing": true,
                "duckduckgo": true,
                "ecosia": true,
                "google": true,
                "pixabay": true,
                "yandex": true,
                "youtube": true
            }
        """.trimIndent()

        val safeSearch = AdGuardParsers.parseSafeSearchStatus(json)
        assertTrue(safeSearch.enabled)
        assertTrue(safeSearch.google)
        assertTrue(safeSearch.youtube)
    }

    @Test
    fun testTwoVersionCompatibilityDoesNotCrashOnMissingFields() {
        // Version 1: Modern schema with extra fields
        val modernStatus = """
            {
                "version": "v0.108.0-b.12",
                "protection_enabled": false,
                "protection_disabled_duration": 1800000,
                "some_future_field_2027": "unexpected"
            }
        """.trimIndent()
        val s1 = AdGuardParsers.parseStatus(modernStatus)
        assertEquals("v0.108.0-b.12", s1.version)
        assertFalse(s1.protectionEnabled)
        assertEquals(1800000L, s1.protectionDisabledDurationMs)

        // Version 2: Minimal older schema (no duration, no language)
        val olderStatus = """
            {
                "version": "v0.106.3",
                "protection_enabled": true
            }
        """.trimIndent()
        val s2 = AdGuardParsers.parseStatus(olderStatus)
        assertEquals("v0.106.3", s2.version)
        assertTrue(s2.protectionEnabled)
        assertEquals(0L, s2.protectionDisabledDurationMs)
    }

    /** Shape from AdGuard Home openapi.yaml (master 84f8fe124889): /safebrowsing/status and /parental/status. */
    @Test
    fun parseEnabledFlagReadsRealStatusAndReturnsNullWhenUnknown() {
        org.junit.Assert.assertEquals(false, AdGuardParsers.parseEnabledFlag("""{"enabled": false}"""))
        org.junit.Assert.assertEquals(true, AdGuardParsers.parseEnabledFlag("""{"enabled": true, "sensitivity": 13}"""))
        org.junit.Assert.assertNull(AdGuardParsers.parseEnabledFlag("""{}"""))
        org.junit.Assert.assertNull(AdGuardParsers.parseEnabledFlag("not json"))
    }
}
