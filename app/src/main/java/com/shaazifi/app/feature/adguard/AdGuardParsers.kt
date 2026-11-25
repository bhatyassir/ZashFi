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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

object AdGuardParsers {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun parseStatus(jsonStr: String): AdGuardStatus {
        val root = json.parseToJsonElement(jsonStr).jsonObject
        val version = root["version"]?.jsonPrimitive?.content ?: "unknown"
        val protectionEnabled = root["protection_enabled"]?.jsonPrimitive?.booleanOrNull ?: false
        val duration = root["protection_disabled_duration"]?.jsonPrimitive?.longOrNull ?: 0L
        val dnsPort = root["dns_port"]?.jsonPrimitive?.intOrNull ?: 53
        val httpPort = root["http_port"]?.jsonPrimitive?.intOrNull ?: 3000
        val running = root["running"]?.jsonPrimitive?.booleanOrNull ?: true

        val dnsAddresses = root["dns_addresses"]?.jsonArray?.mapNotNull {
            it.jsonPrimitive.content
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
        val root = json.parseToJsonElement(jsonStr).jsonObject
        val timeUnits = root["time_units"]?.jsonPrimitive?.content ?: "hours"
        val numQueries = root["num_dns_queries"]?.jsonPrimitive?.longOrNull ?: 0L
        val numBlocked = root["num_blocked_filtering"]?.jsonPrimitive?.longOrNull ?: 0L
        val numSafebrowsing = root["num_replaced_safebrowsing"]?.jsonPrimitive?.longOrNull ?: 0L
        val numSafesearch = root["num_replaced_safesearch"]?.jsonPrimitive?.longOrNull ?: 0L
        val numParental = root["num_replaced_parental"]?.jsonPrimitive?.longOrNull ?: 0L
        val avgSec = root["avg_processing_time"]?.jsonPrimitive?.doubleOrNull ?: 0.0

        val queries = root["dns_queries"]?.jsonArray?.mapNotNull {
            it.jsonPrimitive.intOrNull
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
        val array = element as? JsonArray ?: return emptyList()
        val list = mutableListOf<Pair<String, Long>>()
        for (item in array) {
            val obj = item as? JsonObject ?: continue
            for ((key, value) in obj) {
                val count = value.jsonPrimitive.longOrNull ?: 0L
                list.add(key to count)
            }
        }
        return list
    }

    fun parseStatsConfig(jsonStr: String): AdGuardStatsConfig {
        val root = json.parseToJsonElement(jsonStr).jsonObject
        val interval = root["interval"]?.jsonPrimitive?.floatOrNull?.takeIf { it.isFinite() && it > 0 }
        // Current AdGuard reports milliseconds. Older replies used a count of days.
        val intervalDays = if (interval != null && interval >= 3_600_000) interval / 86_400_000 else interval
        val enabled = root["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        return AdGuardStatsConfig(
            intervalDays = intervalDays ?: 1.0f,
            enabled = enabled,
            intervalKnown = interval != null
        )
    }

    fun parseQueryLog(jsonStr: String): AdGuardQueryLog {
        val root = json.parseToJsonElement(jsonStr).jsonObject
        val oldest = root["oldest"].queryString().orEmpty()
        val dataArray = root["data"] as? JsonArray ?: error("AdGuard Home returned an invalid query log.")

        val items = mutableListOf<AdGuardQueryLogItem>()
        for (item in dataArray) {
            val obj = item as? JsonObject ?: continue
            val time = obj["time"].queryString().orEmpty()
            val client = obj["client"].queryString().orEmpty()

            val question = obj["question"] as? JsonObject
            val qName = question?.get("name")?.jsonPrimitive?.content ?: ""
            val qType = question?.get("type")?.jsonPrimitive?.content ?: "A"

            val clientInfo = obj["client_info"] as? JsonObject
            val clientName = clientInfo?.get("name").queryString()

            // Unified reason handling: modern AdGuard uses 'reason', older releases used 'response_status'
            val reason = obj["reason"].queryString()
                ?: obj["response_status"].queryString()
                ?: ""

            val elapsedMs = obj["elapsedMs"].queryString() ?: obj["elapsed_ms"].queryString() ?: ""

            val rules = mutableListOf<String>()
            val rulesArray = obj["rules"] as? JsonArray
            if (rulesArray != null) {
                for (r in rulesArray) {
                    val rObj = r as? JsonObject
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
                    cached = (obj["cached"] as? JsonPrimitive)?.booleanOrNull,
                    answers = (obj["answer"] as? JsonArray).orEmpty().mapNotNull { answer ->
                        val record = answer as? JsonObject ?: return@mapNotNull null
                        val type = record["type"].queryString() ?: return@mapNotNull null
                        val value = record["value"].queryString() ?: return@mapNotNull null
                        AdGuardDnsAnswer(type, value, (record["ttl"] as? JsonPrimitive)?.longOrNull)
                    }
                )
            )
        }
        return AdGuardQueryLog(oldest = oldest, items = items)
    }

    fun parseQueryLogSettings(jsonStr: String): AdGuardQueryLogSettings {
        val root = json.parseToJsonElement(jsonStr).jsonObject
        return AdGuardQueryLogSettings(
            enabled = (root["enabled"] as? JsonPrimitive)?.booleanOrNull,
            anonymizeClientIp = (root["anonymize_client_ip"] as? JsonPrimitive)?.booleanOrNull
        )
    }

    fun parseQueryLogClients(jsonStr: String): List<AdGuardLogDevice> {
        val root = json.parseToJsonElement(jsonStr).jsonObject
        val configured = (root["clients"] as? JsonArray).orEmpty().flatMap { entry ->
            val client = entry as? JsonObject
            (client?.get("ids") as? JsonArray).orEmpty().mapNotNull { id ->
                id.queryString()?.let { AdGuardLogDevice.fromIdentifier(it, client?.get("name").queryString()) }
            }
        }
        val discovered = (root["auto_clients"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val client = entry as? JsonObject
            client?.get("ip").queryString()?.let { AdGuardLogDevice.fromIdentifier(it, client?.get("name").queryString()) }
        }
        return (configured + discovered).distinctBy { it.identifier }
    }

    private fun JsonElement?.queryString(): String? = (this as? JsonPrimitive)
        ?.takeUnless { it is JsonNull }?.content?.takeIf { it.isNotBlank() }

    fun parseFilteringStatus(jsonStr: String): AdGuardFilteringStatus {
        val root = json.parseToJsonElement(jsonStr).jsonObject
        val enabled = root["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        val filters = root["filters"] as? JsonArray
        val activeFilters = filters?.count {
            (it as? JsonObject)?.get("enabled")?.jsonPrimitive?.booleanOrNull == true
        } ?: 0

        val userRules = root["user_rules"] as? JsonArray
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
        (json.parseToJsonElement(jsonStr) as? JsonObject)?.get("enabled")?.jsonPrimitive?.booleanOrNull
    } catch (_: Exception) {
        null
    }

    fun parseSafeSearchStatus(jsonStr: String): AdGuardSafeSearchStatus {
        val root = json.parseToJsonElement(jsonStr).jsonObject
        val enabled = root["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
        val google = root["google"]?.jsonPrimitive?.booleanOrNull ?: false
        val bing = root["bing"]?.jsonPrimitive?.booleanOrNull ?: false
        val ddg = root["duckduckgo"]?.jsonPrimitive?.booleanOrNull ?: false
        val ecosia = root["ecosia"]?.jsonPrimitive?.booleanOrNull ?: false
        val pixabay = root["pixabay"]?.jsonPrimitive?.booleanOrNull ?: false
        val yandex = root["yandex"]?.jsonPrimitive?.booleanOrNull ?: false
        val yt = root["youtube"]?.jsonPrimitive?.booleanOrNull ?: false

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

