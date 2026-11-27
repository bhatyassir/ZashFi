package com.shaazifi.app.feature.adguard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

object AdGuardParsers {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private fun JsonElement?.asObj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.asArr(): JsonArray? = this as? JsonArray
    private fun JsonElement?.asPrim(): JsonPrimitive? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }
    private fun JsonElement?.asStr(): String? = asPrim()?.content
    private fun JsonElement?.asBool(): Boolean? = asPrim()?.booleanOrNull
    private fun JsonElement?.asInt(): Int? = asPrim()?.intOrNull
    private fun JsonElement?.asLong(): Long? = asPrim()?.longOrNull
    private fun JsonElement?.asFloat(): Float? = asPrim()?.floatOrNull
    private fun JsonElement?.asDouble(): Double? = asPrim()?.doubleOrNull

    fun parseStatus(jsonStr: String): AdGuardStatus {
        val root = json.parseToJsonElement(jsonStr).asObj() ?: JsonObject(emptyMap())
        val version = root["version"].asStr() ?: "unknown"
        val protectionEnabled = root["protection_enabled"].asBool() ?: false
        val duration = root["protection_disabled_duration"].asLong() ?: 0L
        val dnsPort = root["dns_port"].asInt() ?: 53
        val httpPort = root["http_port"].asInt() ?: 3000
        val running = root["running"].asBool() ?: true

        val dnsAddresses = root["dns_addresses"].asArr()?.mapNotNull {
            it.asStr()
        } ?: emptyList()

        return AdGuardStatus(
            version = version,
            protectionEnabled = protectionEnabled,
            protectionDisabledDurationMs = duration,
            dnsAddresses = dnsAddresses,
            dnsPort = dnsPort,
            httpPort = httpPort,
            running = running
        )
    }

    fun parseStats(jsonStr: String): AdGuardStats {
        val root = json.parseToJsonElement(jsonStr).asObj() ?: JsonObject(emptyMap())
        val timeUnits = root["time_units"].asStr() ?: "hours"
        val numQueries = root["num_dns_queries"].asLong() ?: 0L
        val numBlocked = root["num_blocked_filtering"].asLong() ?: 0L
        val numSafebrowsing = root["num_replaced_safebrowsing"].asLong() ?: 0L
        val numSafesearch = root["num_replaced_safesearch"].asLong() ?: 0L
        val numParental = root["num_replaced_parental"].asLong() ?: 0L
        val avgSec = root["avg_processing_time"].asDouble() ?: 0.0

        val queries = root["dns_queries"].asArr()?.mapNotNull {
            it.asInt()
        } ?: emptyList()

        val topQueried = parseTopArray(root["top_queried_domains"])
        val topBlocked = parseTopArray(root["top_blocked_domains"])
        val topClients = parseTopArray(root["top_clients"])

        return AdGuardStats(
            timeUnits = timeUnits,
            numDnsQueries = numQueries,
            numBlockedFiltering = numBlocked,
            numReplacedSafebrowsing = numSafebrowsing,
            numReplacedSafesearch = numSafesearch,
            numReplacedParental = numParental,
            avgProcessingTimeSeconds = avgSec,
            dnsQueries = queries,
            topQueriedDomains = topQueried,
            topBlockedDomains = topBlocked,
            topClients = topClients
        )
    }

    private fun parseTopArray(element: JsonElement?): List<Pair<String, Long>> {
        val array = element.asArr() ?: return emptyList()
        val list = mutableListOf<Pair<String, Long>>()
        for (item in array) {
            val obj = item.asObj() ?: continue
            for ((key, value) in obj) {
                val count = value.asLong() ?: 0L
                list.add(key to count)
            }
        }
        return list
    }

    fun parseStatsConfig(jsonStr: String): AdGuardStatsConfig {
        val root = json.parseToJsonElement(jsonStr).asObj() ?: JsonObject(emptyMap())
        val interval = root["interval"].asFloat()?.takeIf { it.isFinite() && it > 0 }
        // Current AdGuard reports milliseconds. Older replies used a count of days.
        val intervalDays = if (interval != null && interval >= 3_600_000) interval / 86_400_000 else interval
        val enabled = root["enabled"].asBool() ?: true
        return AdGuardStatsConfig(
            intervalDays = intervalDays ?: 1.0f,
            enabled = enabled,
            intervalKnown = interval != null
        )
    }

    fun parseQueryLog(jsonStr: String): AdGuardQueryLog {
        val root = json.parseToJsonElement(jsonStr).asObj() ?: error("AdGuard Home returned an invalid query log.")
        val oldest = root["oldest"].queryString().orEmpty()
        val dataArray = root["data"].asArr() ?: error("AdGuard Home returned an invalid query log.")

        val items = mutableListOf<AdGuardQueryLogItem>()
        for (item in dataArray) {
            val obj = item.asObj() ?: continue
            val time = obj["time"].queryString().orEmpty()
            val client = obj["client"].queryString().orEmpty()

            val question = obj["question"].asObj()
            val qName = question?.get("name").asStr() ?: ""
            val qType = question?.get("type").asStr() ?: "A"

            val clientInfo = obj["client_info"].asObj()
            val clientName = clientInfo?.get("name").queryString()

            // Unified reason handling: modern AdGuard uses 'reason', older releases used 'response_status'
            val reason = obj["reason"].queryString()
                ?: obj["response_status"].queryString()
                ?: "NotFilteredNotFound"

            val elapsedMs = obj["elapsedMs"].queryString() ?: obj["elapsed_ms"].queryString() ?: ""

            val rules = mutableListOf<String>()
            val rulesArray = obj["rules"].asArr()
            if (rulesArray != null) {
                for (r in rulesArray) {
                    val rObj = r.asObj()
                    val text = rObj?.get("text").queryString()
                    if (text != null) rules.add(text)
                }
            }

            items.add(
                AdGuardQueryLogItem(
                    time = time,
                    questionName = qName,
                    questionType = qType,
                    client = client,
                    clientName = clientName,
                    reason = reason,
                    elapsedMs = elapsedMs,
                    rules = rules,
                    clientId = obj["client_id"].queryString(),
                    unicodeName = question?.get("unicode_name").queryString(),
                    responseStatus = obj["status"].queryString(),
                    upstream = obj["upstream"].queryString(),
                    cached = obj["cached"].asBool(),
                    answers = obj["answer"].asArr().orEmpty().mapNotNull { answer ->
                        val record = answer.asObj() ?: return@mapNotNull null
                        val type = record["type"].queryString() ?: return@mapNotNull null
                        val value = record["value"].queryString() ?: return@mapNotNull null
                        AdGuardDnsAnswer(type, value, record["ttl"].asLong())
                    }
                )
            )
        }
        return AdGuardQueryLog(oldest = oldest, items = items.withPageOccurrences())
    }

    fun parseQueryLogSettings(jsonStr: String): AdGuardQueryLogSettings {
        val root = json.parseToJsonElement(jsonStr).asObj() ?: JsonObject(emptyMap())
        return AdGuardQueryLogSettings(
            enabled = root["enabled"].asBool(),
            anonymizeClientIp = root["anonymize_client_ip"].asBool()
        )
    }

    fun parseQueryLogClients(jsonStr: String): List<AdGuardLogDevice> {
        val root = json.parseToJsonElement(jsonStr).asObj() ?: JsonObject(emptyMap())
        val configured = root["clients"].asArr().orEmpty().flatMap { entry ->
            val client = entry.asObj()
            client?.get("ids").asArr().orEmpty().mapNotNull { id ->
                id.queryString()?.let { AdGuardLogDevice.fromIdentifier(it, client?.get("name").queryString()) }
            }
        }
        val discovered = root["auto_clients"].asArr().orEmpty().mapNotNull { entry ->
            val client = entry.asObj()
            client?.get("ip").queryString()?.let { AdGuardLogDevice.fromIdentifier(it, client?.get("name").queryString()) }
        }
        return (configured + discovered).distinctBy { it.identifier }
    }

    private fun JsonElement?.queryString(): String? = asPrim()?.content?.takeIf { it.isNotBlank() }

    fun parseFilteringStatus(jsonStr: String): AdGuardFilteringStatus {
        val root = json.parseToJsonElement(jsonStr).asObj() ?: JsonObject(emptyMap())
        val enabled = root["enabled"].asBool() ?: true
        val filters = root["filters"].asArr()
        val activeFilters = filters?.count {
            it.asObj()?.get("enabled").asBool() == true
        } ?: 0

        val userRules = root["user_rules"].asArr()
        val userRuleCount = userRules?.size ?: 0

        return AdGuardFilteringStatus(
            enabled = enabled,
            activeFilterCount = activeFilters,
            userRuleCount = userRuleCount
        )
    }

    /**
     * Reads `{"enabled": bool}` replies (GET /control/safebrowsing/status and
     * /control/parental/status; AdGuard Home openapi.yaml, master 84f8fe124889, read 2026-11-16).
     * Returns null when the value is missing or the reply is not JSON, so the screen can say "unknown".
     */
    fun parseEnabledFlag(jsonStr: String): Boolean? = try {
        json.parseToJsonElement(jsonStr).asObj()?.get("enabled").asBool()
    } catch (_: Exception) {
        null
    }

    fun parseSafeSearchStatus(jsonStr: String): AdGuardSafeSearchStatus {
        val root = json.parseToJsonElement(jsonStr).asObj() ?: JsonObject(emptyMap())
        val enabled = root["enabled"].asBool() ?: false
        val google = root["google"].asBool() ?: false
        val bing = root["bing"].asBool() ?: false
        val ddg = root["duckduckgo"].asBool() ?: false
        val ecosia = root["ecosia"].asBool() ?: false
        val pixabay = root["pixabay"].asBool() ?: false
        val yandex = root["yandex"].asBool() ?: false
        val yt = root["youtube"].asBool() ?: false

        return AdGuardSafeSearchStatus(
            enabled = enabled,
            google = google,
            bing = bing,
            duckduckgo = ddg,
            ecosia = ecosia,
            pixabay = pixabay,
            yandex = yandex,
            youtube = yt
        )
    }
}

