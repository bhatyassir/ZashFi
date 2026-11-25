package com.shaazifi.app.feature.adguard.manage

import com.shaazifi.app.core.adguard.AdGuardCaller
import com.shaazifi.app.core.adguard.AdGuardError
import com.shaazifi.app.feature.router.FormField
import com.shaazifi.app.feature.router.ViewPill
import com.shaazifi.app.ui.theme.PillTone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/*
 * AdGuard Home management (full rebuild of the AdGuard feature). Feature list from
 * JGeek00/adguard-home-manager (Apache-2.0; reference only, no code copied). Calls, methods and
 * bodies from AdGuard Home openapi/openapi.yaml (master, read 2026-11-17). Every change is an
 * [AgAction] that is only performed after the owner confirms it.
 */

enum class AgPage(val id: String, val title: String, val subtitle: String) {
    FILTERS("filters", "Blocklists", "Lists of blocked and allowed sites"),
    RULES("rules", "Custom rules", "Your own block and allow rules; check a site"),
    SERVICES("services", "Blocked services", "Block apps and sites by name"),
    CLIENTS("clients", "Clients", "Devices AdGuard knows"),
    ACCESS("access", "Access", "Devices and names refused by AdGuard"),
    REWRITES("rewrites", "DNS rewrites", "Your own names for addresses"),
    DNS("dns", "DNS settings", "Upstream servers, cache, test"),
    LOGS("logs", "Log and statistics", "What is kept and for how long"),
    SAFESEARCH("safesearch", "Safe search", "Per search engine");

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/** A change (or a check) that runs only after the owner confirms. Returns a message to show, or null. */
data class AgAction(
    val label: String,
    val title: String,
    val message: String,
    val destructive: Boolean = false,
    /** False for checks that change nothing (check a site, test upstreams). */
    val needsConfirm: Boolean = true,
    val perform: suspend (AdGuardCaller) -> String?
)

data class AgRow(
    val label: String,
    val value: String = "",
    val sub: String? = null,
    val mono: Boolean = false,
    val pill: ViewPill? = null,
    val toggle: Boolean? = null,
    val onToggle: AgAction? = null,
    val extra: AgAction? = null
)

data class AgSection(val heading: String, val rows: List<AgRow>, val emptyText: String? = null)

sealed class AgBuild {
    data class Ready(val action: AgAction) : AgBuild()
    data class Invalid(val message: String) : AgBuild()
}

data class AgForm(val title: String, val fields: List<FormField>, val submitLabel: String, val build: (Map<String, String>) -> AgBuild)

data class AgView(
    val summary: String,
    val pill: ViewPill? = null,
    val sections: List<AgSection> = emptyList(),
    val forms: List<AgForm> = emptyList(),
    val actions: List<AgAction> = emptyList(),
    val note: String? = null
)

/** Query log filter, values accepted by AdGuard Home's `response_status` (internal/querylog/deprecated.go). */
enum class AgQueryFilter(val value: String, val label: String) {
    ALL("all", "All"),
    FILTERED("filtered", "Filtered"),
    BLOCKED("blocked", "Blocked"),
    WHITELISTED("whitelisted", "Allowed"),
    REWRITTEN("rewritten", "Rewritten"),
    PROCESSED("processed", "Not filtered")
}

// ------------------------------------------------------------------------------- JSON helpers

private fun JsonElement?.obj() = this as? JsonObject
private fun JsonElement?.arr() = this as? JsonArray
private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull ?: (this as? JsonPrimitive)?.doubleOrNull?.toLong()
private fun JsonElement?.strings(): List<String> = arr()?.mapNotNull { it.str() }.orEmpty()
private fun strArray(list: List<String>) = buildJsonArray { list.forEach { add(JsonPrimitive(it)) } }

/** The same object with one field replaced, keeping the order AdGuard sent. */
private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(LinkedHashMap(this).apply { put(key, value) })

private val ON = ViewPill("On", PillTone.Ok)
private val OFF = ViewPill("Off", PillTone.Off)

private fun onOff(b: Boolean?) = when (b) { true -> ON; false -> OFF; null -> null }

private val DOMAIN = Regex("^[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?$")

private fun days(ms: Long?): String {
    if (ms == null) return "Unknown"
    val h = ms / 3_600_000
    return if (h < 24) "$h hours" else "${h / 24} days"
}

// ------------------------------------------------------------------------------- changes

object AgChanges {

    /** A plain domain like ads.example.com (no spaces, scheme or path). Null when unsafe. */
    fun domainOrNull(raw: String): String? =
        raw.trim().lowercase().removeSuffix(".").takeIf { it.isNotEmpty() && DOMAIN.matches(it) && !it.contains("..") }

    private suspend fun currentRules(caller: AdGuardCaller): List<String> =
        caller.get("/filtering/status").obj()?.get("user_rules").strings()

    private suspend fun setRules(caller: AdGuardCaller, rules: List<String>) {
        caller.send("POST", "/filtering/set_rules", buildJsonObject { put("rules", strArray(rules)) })
    }

    private fun replaceDomainRule(rules: List<String>, domain: String, newRule: String): List<String> =
        rules.filterNot { it == "||$domain^" || it == "@@||$domain^" } + newRule

    fun blockDomain(domain: String) = AgAction(
        "Block", "Block $domain?", "Adds the rule ||$domain^ to your custom rules. Every device stops reaching it."
    ) { c -> setRules(c, replaceDomainRule(currentRules(c), domain, "||$domain^")); "Blocked $domain." }

    fun allowDomain(domain: String) = AgAction(
        "Allow", "Allow $domain?", "Adds the rule @@||$domain^ to your custom rules, so no blocklist blocks it."
    ) { c -> setRules(c, replaceDomainRule(currentRules(c), domain, "@@||$domain^")); "Allowed $domain." }

    fun addRule(rule: String) = AgAction("Add", "Add this rule?", rule) { c ->
        val now = currentRules(c)
        if (rule in now) "That rule is already there." else { setRules(c, now + rule); "Rule added." }
    }

    fun removeRule(rule: String) = AgAction("Remove", "Remove this rule?", rule, destructive = true) { c ->
        setRules(c, currentRules(c).filterNot { it == rule }); "Rule removed."
    }
}

// ------------------------------------------------------------------------------- pages

object AgPages {

    suspend fun load(page: AgPage, c: AdGuardCaller): AgView = when (page) {
        AgPage.FILTERS -> filters(c.get("/filtering/status").obj() ?: JsonObject(emptyMap()))
        AgPage.RULES -> rules(c.get("/filtering/status").obj() ?: JsonObject(emptyMap()))
        AgPage.SERVICES -> services(c.get("/blocked_services/all").obj() ?: JsonObject(emptyMap()), c.get("/blocked_services/get").obj() ?: JsonObject(emptyMap()))
        AgPage.CLIENTS -> clients(c.get("/clients").obj() ?: JsonObject(emptyMap()))
        AgPage.ACCESS -> access(c.get("/access/list").obj() ?: JsonObject(emptyMap()))
        AgPage.REWRITES -> rewrites(c.get("/rewrite/list").arr() ?: JsonArray(emptyList()), runCatching { c.get("/rewrite/settings").obj() }.getOrNull())
        AgPage.DNS -> dns(c.get("/dns_info").obj() ?: JsonObject(emptyMap()))
        AgPage.LOGS -> logsStats(c.get("/querylog/config").obj() ?: JsonObject(emptyMap()), c.get("/stats/config").obj() ?: JsonObject(emptyMap()))
        AgPage.SAFESEARCH -> safeSearch(c.get("/safesearch/status").obj() ?: JsonObject(emptyMap()))
    }

    // ---- Blocklists (/filtering/status; set_url, remove_url, add_url, refresh, config)

    fun filters(status: JsonObject): AgView {
        fun listRows(key: String, whitelist: Boolean) = status[key].arr()?.mapNotNull { it.obj() }.orEmpty().map { f ->
            val name = f["name"].str().orEmpty().ifBlank { f["url"].str().orEmpty() }
            val url = f["url"].str().orEmpty()
            val enabled = f["enabled"].bool() == true
            AgRow(
                label = name,
                sub = "${f["rules_count"].long() ?: 0} rules" + (f["last_updated"].str()?.take(10)?.let { " · updated $it" } ?: ""),
                toggle = enabled,
                onToggle = AgAction(
                    if (enabled) "Turn off" else "Turn on",
                    "${if (enabled) "Turn off" else "Turn on"} \"$name\"?",
                    if (whitelist) "An allowlist: sites on it are never blocked." else "A blocklist: sites on it are blocked."
                ) { c ->
                    c.send("POST", "/filtering/set_url", buildJsonObject {
                        put("url", url)
                        put("whitelist", whitelist)
                        put("data", buildJsonObject { put("name", name); put("url", url); put("enabled", !enabled) })
                    }); null
                },
                extra = AgAction("Remove", "Remove \"$name\"?", "The list is removed from AdGuard Home. You can add it again later.", destructive = true) { c ->
                    c.send("POST", "/filtering/remove_url", buildJsonObject { put("url", url); put("whitelist", whitelist) }); null
                }
            )
        }
        val on = status["enabled"].bool() != false
        fun addForm(whitelist: Boolean) = AgForm(
            if (whitelist) "Add an allowlist" else "Add a blocklist",
            listOf(FormField("name", "Name"), FormField("url", "Address (https://...)", hint = "https://example.org/list.txt")),
            "Add list"
        ) { v ->
            val name = v["name"].orEmpty().trim()
            val url = v["url"].orEmpty().trim()
            when {
                name.isEmpty() -> AgBuild.Invalid("Give the list a name.")
                !(url.startsWith("https://") || url.startsWith("http://")) || url.any { it.isWhitespace() } ->
                    AgBuild.Invalid("The address must start with https:// (or http://).")
                else -> AgBuild.Ready(AgAction("Add", "Add \"$name\"?", url) { c ->
                    c.send("POST", "/filtering/add_url", buildJsonObject { put("name", name); put("url", url); put("whitelist", whitelist) }); "List added."
                })
            }
        }
        val blocklists = listRows("filters", whitelist = false)
        return AgView(
            summary = "${blocklists.count { it.toggle == true }} of ${blocklists.size} blocklists on. Lists update every ${status["interval"].long() ?: 24} hours.",
            pill = if (on) ON else ViewPill("Filtering off", PillTone.Warn),
            sections = listOf(
                AgSection("Blocklists", blocklists, "No blocklists."),
                AgSection("Allowlists", listRows("whitelist_filters", whitelist = true), "No allowlists.")
            ),
            forms = listOf(addForm(false), addForm(true)),
            actions = listOf(
                AgAction("Update blocklists now", "Update all blocklists now?", "AdGuard Home downloads the newest version of every blocklist.", needsConfirm = true) { c ->
                    val r = c.send("POST", "/filtering/refresh", buildJsonObject { put("whitelist", false) })
                    "Updated ${r.obj()?.get("updated").long() ?: 0} lists."
                },
                AgAction(
                    if (on) "Turn filtering off" else "Turn filtering on",
                    if (on) "Turn all filtering off?" else "Turn filtering on?",
                    if (on) "No blocklist or custom rule is applied until you turn it on again." else "Blocklists and custom rules apply again.",
                    destructive = on
                ) { c ->
                    c.send("POST", "/filtering/config", buildJsonObject { put("enabled", !on); put("interval", status["interval"].long() ?: 24) }); null
                }
            )
        )
    }

    // ---- Custom rules (/filtering/status user_rules; set_rules; check_host)

    fun rules(status: JsonObject): AgView {
        val rules = status["user_rules"].strings().filter { it.isNotBlank() }
        return AgView(
            summary = "${rules.size} custom rules. ||site^ blocks a site; @@||site^ always allows it.",
            sections = listOf(AgSection("Rules", rules.map { r ->
                AgRow(r, mono = true, pill = if (r.startsWith("@@")) ViewPill("Allow", PillTone.Ok) else if (r.startsWith("!") || r.startsWith("#")) ViewPill("Note", PillTone.Off) else ViewPill("Block", PillTone.Bad),
                    extra = AgChanges.removeRule(r))
            }, "No custom rules.")),
            forms = listOf(
                AgForm("Block or allow a site", listOf(FormField("domain", "Site", hint = "ads.example.com"), FormField("mode", "block or allow", initial = "block")), "Save") { v ->
                    val d = AgChanges.domainOrNull(v["domain"].orEmpty()) ?: return@AgForm AgBuild.Invalid("Type a site name like ads.example.com.")
                    when (v["mode"].orEmpty().trim().lowercase()) {
                        "block" -> AgBuild.Ready(AgChanges.blockDomain(d))
                        "allow" -> AgBuild.Ready(AgChanges.allowDomain(d))
                        else -> AgBuild.Invalid("Type block or allow.")
                    }
                },
                AgForm("Add a rule (advanced)", listOf(FormField("rule", "Rule", hint = "||example.com^\$important")), "Add rule") { v ->
                    val r = v["rule"].orEmpty().trim()
                    if (r.isEmpty() || r.length > 1024 || r.contains('\n')) AgBuild.Invalid("Type one rule on one line.") else AgBuild.Ready(AgChanges.addRule(r))
                },
                AgForm("Check a site", listOf(FormField("name", "Site", hint = "example.com")), "Check") { v ->
                    val d = AgChanges.domainOrNull(v["name"].orEmpty()) ?: return@AgForm AgBuild.Invalid("Type a site name like example.com.")
                    AgBuild.Ready(AgAction("Check", "Check $d", "", needsConfirm = false) { c ->
                        val r = c.get("/filtering/check_host", mapOf("name" to d)).obj()
                        val reason = r?.get("reason").str().orEmpty()
                        val rule = r?.get("rules").arr()?.mapNotNull { it.obj()?.get("text").str() }?.firstOrNull()
                        "$d: ${checkReason(reason)}" + (rule?.let { "\nRule: $it" } ?: "")
                    })
                }
            )
        )
    }

    fun checkReason(reason: String): String = when {
        reason.startsWith("NotFiltered") -> "not blocked"
        reason == "FilteredBlackList" || reason == "FilteredBlockedService" -> "blocked"
        reason.startsWith("FilteredSafeBrowsing") -> "blocked as unsafe"
        reason.startsWith("FilteredParental") -> "blocked by parental control"
        reason.startsWith("Rewrite") -> "rewritten"
        reason.startsWith("FilteredSafeSearch") -> "safe search applied"
        reason.isBlank() -> "no answer"
        else -> reason
    }

    // ---- Blocked services (/blocked_services/all, /get; PUT /update)

    fun services(all: JsonObject, current: JsonObject): AgView {
        val blocked = current["ids"].strings()
        val schedule = current["schedule"] ?: JsonObject(emptyMap())
        val list = all["blocked_services"].arr()?.mapNotNull { it.obj() }.orEmpty()
        fun update(ids: List<String>) = buildJsonObject { put("ids", strArray(ids)); put("schedule", schedule) }
        val rows = list.map { s ->
            val id = s["id"].str().orEmpty()
            val name = s["name"].str().orEmpty().ifBlank { id }
            val on = id in blocked
            AgRow(name, toggle = on, sub = s["group_id"].str(),
                onToggle = AgAction(if (on) "Unblock" else "Block", "${if (on) "Unblock" else "Block"} $name?",
                    if (on) "$name works again on every device." else "Every device stops reaching $name.") { c ->
                    c.send("PUT", "/blocked_services/update", update(if (on) blocked - id else blocked + id)); null
                })
        }.sortedWith(compareByDescending<AgRow> { it.toggle == true }.thenBy { it.label.lowercase() })
        return AgView(
            summary = "${blocked.size} services blocked.",
            pill = if (blocked.isEmpty()) OFF else ViewPill("${blocked.size} blocked", PillTone.Warn),
            sections = listOf(AgSection("Services", rows, "AdGuard Home did not list any services.")),
            note = "A schedule set in AdGuard Home's own page is kept unchanged."
        )
    }

    // ---- Clients (/clients; add, delete)

    fun clients(reply: JsonObject): AgView {
        val saved = reply["clients"].arr()?.mapNotNull { it.obj() }.orEmpty().map { cl ->
            val name = cl["name"].str().orEmpty()
            AgRow(name, sub = cl["ids"].strings().joinToString(", "), mono = false,
                pill = if (cl["use_global_settings"].bool() == false) ViewPill("Own settings", PillTone.Off) else null,
                extra = AgAction("Remove", "Remove client \"$name\"?", "AdGuard Home forgets this client's name and settings. The device itself is not blocked.", destructive = true) { c ->
                    c.send("POST", "/clients/delete", buildJsonObject { put("name", name) }); null
                })
        }
        val seen = reply["auto_clients"].arr()?.mapNotNull { it.obj() }.orEmpty().map { a ->
            AgRow(a["name"].str().orEmpty().ifBlank { a["ip"].str().orEmpty() }, sub = listOfNotNull(a["ip"].str(), a["source"].str()).joinToString(" · "))
        }
        return AgView(
            summary = "${saved.size} saved clients, ${seen.size} seen automatically.",
            sections = listOf(AgSection("Saved clients", saved, "No saved clients."), AgSection("Seen automatically", seen, "None seen yet.")),
            forms = listOf(AgForm("Save a client", listOf(FormField("name", "Name"), FormField("ids", "Addresses (IP, MAC, CIDR; separate with commas)", pick = com.shaazifi.app.feature.router.PickKind.DEVICE_IP)), "Save client") { v ->
                val name = v["name"].orEmpty().trim()
                val ids = v["ids"].orEmpty().split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
                when {
                    name.isEmpty() -> AgBuild.Invalid("Give the client a name.")
                    ids.isEmpty() || ids.any { id -> id.any { it.isWhitespace() || it in ";\"'" } } -> AgBuild.Invalid("Type at least one address.")
                    else -> AgBuild.Ready(AgAction("Save", "Save client \"$name\"?", ids.joinToString(", ")) { c ->
                        c.send("POST", "/clients/add", buildJsonObject {
                            put("name", name)
                            put("ids", strArray(ids))
                            put("use_global_settings", true)
                            put("use_global_blocked_services", true)
                            put("filtering_enabled", true)
                            put("parental_enabled", false)
                            put("safebrowsing_enabled", false)
                            put("tags", JsonArray(emptyList()))
                            put("upstreams", JsonArray(emptyList()))
                        }); "Client saved."
                    })
                }
            })
        )
    }

    // ---- Access (/access/list; /access/set with the whole object)

    fun access(list: JsonObject): AgView {
        val allowed = list["allowed_clients"].strings()
        val disallowed = list["disallowed_clients"].strings()
        val hosts = list["blocked_hosts"].strings()
        fun set(a: List<String>, d: List<String>, h: List<String>) = buildJsonObject {
            put("allowed_clients", strArray(a)); put("disallowed_clients", strArray(d)); put("blocked_hosts", strArray(h))
        }
        fun rows(items: List<String>, kind: String, remove: (String) -> JsonObject) = items.map { x ->
            AgRow(x, mono = true, extra = AgAction("Remove", "Remove $x from $kind?", "", destructive = true) { c -> c.send("POST", "/access/set", remove(x)); null })
        }
        return AgView(
            summary = if (allowed.isNotEmpty()) "Only the allowed clients may use AdGuard Home." else "${disallowed.size} clients refused, ${hosts.size} names refused.",
            sections = listOf(
                AgSection("Allowed clients (only these may ask)", rows(allowed, "allowed clients") { set(allowed - it, disallowed, hosts) }, "Not used: every client may ask."),
                AgSection("Refused clients", rows(disallowed, "refused clients") { set(allowed, disallowed - it, hosts) }, "None."),
                AgSection("Refused names", rows(hosts, "refused names") { set(allowed, disallowed, hosts - it) }, "None.")
            ),
            forms = listOf(
                AgForm("Refuse a client", listOf(FormField("id", "IP, CIDR or ClientID")), "Refuse") { v ->
                    val id = v["id"].orEmpty().trim()
                    if (id.isEmpty() || id.any { it.isWhitespace() }) AgBuild.Invalid("Type one address.")
                    else AgBuild.Ready(AgAction("Refuse", "Refuse $id?", "AdGuard Home stops answering this client. If it is this phone or the router, DNS stops working for it.", destructive = true) { c ->
                        c.send("POST", "/access/set", set(allowed, disallowed + id, hosts)); null
                    })
                },
                AgForm("Refuse a name", listOf(FormField("host", "Name", hint = "example.org")), "Refuse") { v ->
                    val h = v["host"].orEmpty().trim()
                    if (h.isEmpty() || h.any { it.isWhitespace() }) AgBuild.Invalid("Type one name.")
                    else AgBuild.Ready(AgAction("Refuse", "Refuse questions for $h?", "") { c -> c.send("POST", "/access/set", set(allowed, disallowed, hosts + h)); null })
                }
            ),
            note = "Allowed clients are shown but not added here: one wrong entry stops DNS for every other device. Use AdGuard Home's own page for that."
        )
    }

    // ---- DNS rewrites (/rewrite/list, /rewrite/settings; add, delete, settings/update)

    fun rewrites(list: JsonArray, settings: JsonObject?): AgView {
        val enabled = settings?.get("enabled").bool()
        val rows = list.mapNotNull { it.obj() }.map { e ->
            val domain = e["domain"].str().orEmpty()
            val answer = e["answer"].str().orEmpty()
            AgRow(domain, answer, mono = true, pill = if (e["enabled"].bool() == false) OFF else null,
                extra = AgAction("Remove", "Remove $domain?", "$domain will no longer answer $answer.", destructive = true) { c ->
                    c.send("POST", "/rewrite/delete", e); null
                })
        }
        return AgView(
            summary = "${rows.size} rewrites.",
            pill = onOff(enabled),
            sections = listOf(AgSection("Rewrites", rows, "No rewrites.")),
            forms = listOf(AgForm("Add a rewrite", listOf(FormField("domain", "Name", hint = "nas.lan"), FormField("answer", "Answer (address or name)", hint = "192.168.1.20")), "Add") { v ->
                val d = v["domain"].orEmpty().trim()
                val a = v["answer"].orEmpty().trim()
                if (d.isEmpty() || a.isEmpty() || (d + a).any { it.isWhitespace() }) AgBuild.Invalid("Type a name and an answer.")
                else AgBuild.Ready(AgAction("Add", "Add $d → $a?", "Devices asking for $d get $a.") { c ->
                    c.send("POST", "/rewrite/add", buildJsonObject { put("domain", d); put("answer", a) }); null
                })
            }),
            actions = listOfNotNull(enabled?.let { on ->
                AgAction(if (on) "Turn rewrites off" else "Turn rewrites on", if (on) "Turn all rewrites off?" else "Turn rewrites on?", "") { c ->
                    c.send("PUT", "/rewrite/settings/update", buildJsonObject { put("enabled", !on) }); null
                }
            })
        )
    }

    // ---- DNS settings (/dns_info read only; cache_clear; test_upstream_dns)

    fun dns(info: JsonObject): AgView {
        fun list(k: String) = info[k].strings().joinToString("\n").ifBlank { "None" }
        val rows = listOf(
            AgRow("Upstream servers", list("upstream_dns"), mono = true),
            AgRow("Bootstrap servers", list("bootstrap_dns"), mono = true),
            AgRow("Fallback servers", list("fallback_dns"), mono = true),
            AgRow("Upstream mode", info["upstream_mode"].str().orEmpty().ifBlank { "load_balance" }),
            AgRow("Blocking mode", info["blocking_mode"].str().orEmpty()),
            AgRow("Rate limit", "${info["ratelimit"].long() ?: 0} per second"),
            AgRow("Cache", if (info["cache_enabled"].bool() == false) "Off" else "${(info["cache_size"].long() ?: 0) / 1024} KB"),
            AgRow("DNSSEC", "", pill = onOff(info["dnssec_enabled"].bool())),
            AgRow("Client subnet (EDNS)", "", pill = onOff(info["edns_cs_enabled"].bool())),
            AgRow("IPv6 answers", "", pill = onOff(info["disable_ipv6"].bool()?.not()))
        )
        return AgView(
            summary = "How AdGuard Home looks up names. Changing servers is done in AdGuard Home's own page.",
            sections = listOf(AgSection("Settings", rows)),
            actions = listOf(
                AgAction("Test upstream servers", "Test upstream servers", "", needsConfirm = false) { c ->
                    val r = c.send("POST", "/test_upstream_dns", buildJsonObject {
                        put("upstream_dns", info["upstream_dns"] ?: JsonArray(emptyList()))
                        put("bootstrap_dns", info["bootstrap_dns"] ?: JsonArray(emptyList()))
                        put("fallback_dns", info["fallback_dns"] ?: JsonArray(emptyList()))
                        put("private_upstream", info["local_ptr_upstreams"] ?: JsonArray(emptyList()))
                    }).obj()
                    if (r == null) "No answer from the test." else r.entries.joinToString("\n") { (k, v) -> "$k: ${v.str()}" }
                },
                AgAction("Clear DNS cache", "Clear the DNS cache?", "AdGuard Home forgets saved answers; the next look-ups are slightly slower.") { c ->
                    c.send("POST", "/cache_clear"); "Cache cleared."
                }
            )
        )
    }

    // ---- Log and statistics (/querylog/config, /stats/config; PUT updates; clear and reset)

    fun logsStats(q: JsonObject, s: JsonObject): AgView {
        fun toggleQ(key: String, label: String) = q[key].bool()?.let { on ->
            AgAction(if (on) "Turn off" else "Turn on", "${if (on) "Turn off" else "Turn on"}: $label?", "") { c ->
                c.send("PUT", "/querylog/config/update", q.with(key, JsonPrimitive(!on))); null
            }
        }
        fun toggleS(key: String, label: String) = s[key].bool()?.let { on ->
            AgAction(if (on) "Turn off" else "Turn on", "${if (on) "Turn off" else "Turn on"}: $label?", "") { c ->
                c.send("PUT", "/stats/config/update", s.with(key, JsonPrimitive(!on))); null
            }
        }
        val rows = listOf(
            AgRow("Query log", toggle = q["enabled"].bool(), onToggle = toggleQ("enabled", "query log")),
            AgRow("Keep query log", days(q["interval"].long())),
            AgRow("Hide client addresses", sub = "Anonymise client IP addresses in the log", toggle = q["anonymize_client_ip"].bool(), onToggle = toggleQ("anonymize_client_ip", "hide client addresses")),
            AgRow("Statistics", toggle = s["enabled"].bool(), onToggle = toggleS("enabled", "statistics")),
            AgRow("Keep statistics", days(s["interval"].long()))
        )
        fun retention(title: String, path: String, base: JsonObject, allowedDays: List<Int>) =
            AgForm(title, listOf(FormField("days", "Days (${allowedDays.joinToString(", ")})", numeric = true)), "Save") { v ->
                val d = v["days"].orEmpty().trim().toIntOrNull()
                if (d == null || d !in allowedDays) AgBuild.Invalid("Choose one of ${allowedDays.joinToString(", ")} days.")
                else AgBuild.Ready(AgAction("Save", "$title: $d days?", "Older entries are removed.") { c ->
                    c.send("PUT", path, base.with("interval", JsonPrimitive(d * 86_400_000L))); null
                })
            }
        return AgView(
            summary = "What AdGuard Home keeps about DNS questions.",
            sections = listOf(AgSection("Settings", rows)),
            forms = listOf(
                retention("Keep query log", "/querylog/config/update", q, listOf(1, 7, 30, 90)),
                retention("Keep statistics", "/stats/config/update", s, listOf(1, 7, 30, 90))
            ),
            actions = listOf(
                AgAction("Clear query log", "Clear the whole query log?", "Every logged question is deleted. This cannot be undone.", destructive = true) { c ->
                    c.send("POST", "/querylog_clear"); "Query log cleared."
                },
                AgAction("Reset statistics", "Reset all statistics?", "All counts start from zero. This cannot be undone.", destructive = true) { c ->
                    c.send("POST", "/stats_reset"); "Statistics reset."
                }
            )
        )
    }

    // ---- Safe search (/safesearch/status; PUT /safesearch/settings with the whole object)

    fun safeSearch(status: JsonObject): AgView {
        val names = linkedMapOf("enabled" to "Safe search", "google" to "Google", "bing" to "Bing", "duckduckgo" to "DuckDuckGo",
            "ecosia" to "Ecosia", "yandex" to "Yandex", "youtube" to "YouTube", "pixabay" to "Pixabay")
        val rows = names.mapNotNull { (k, label) ->
            val on = status[k].bool() ?: return@mapNotNull null
            AgRow(label, toggle = on, onToggle = AgAction(if (on) "Turn off" else "Turn on", "${if (on) "Turn off" else "Turn on"} $label safe search?", "") { c ->
                c.send("PUT", "/safesearch/settings", status.with(k, JsonPrimitive(!on))); null
            })
        }
        return AgView(
            summary = "Forces safe results on search engines for every device.",
            pill = onOff(status["enabled"].bool()),
            sections = listOf(AgSection("Search engines", rows))
        )
    }
}

// ------------------------------------------------------------------------------- state

sealed class AgState {
    data object Loading : AgState()
    data class Loaded(val view: AgView) : AgState()
    data class Failed(val message: String, val needsPassword: Boolean = false) : AgState()
}

/** One management page. Nothing is sent before [confirm] (or for checks, which change nothing). */
class AgPageHolder(
    private val page: AgPage,
    private val caller: AdGuardCaller,
    val demo: Boolean = false,
    /** Phase 5: every confirmed AdGuard change and its result (for the activity log). */
    private val onDone: suspend (AgAction, String) -> Unit = { _, _ -> }
) {
    private val _state = MutableStateFlow<AgState>(AgState.Loading)
    val state: StateFlow<AgState> = _state.asStateFlow()
    private val _pending = MutableStateFlow<AgAction?>(null)
    val pending: StateFlow<AgAction?> = _pending.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    suspend fun refresh() {
        _state.value = try {
            AgState.Loaded(AgPages.load(page, caller))
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdGuardError.Unauthorized) {
            AgState.Failed("Sign in to AdGuard Home first (on the AdGuard screen).", needsPassword = true)
        } catch (e: AdGuardError) {
            AgState.Failed(e.message ?: "AdGuard Home did not answer.")
        } catch (e: Exception) {
            AgState.Failed("Could not read this page (${e::class.simpleName}).")
        }
    }

    suspend fun request(action: AgAction) {
        _message.value = null
        if (action.needsConfirm) _pending.value = action else run(action)
    }

    fun dismiss() { _pending.value = null }

    fun clearMessage() { _message.value = null }

    suspend fun confirm() {
        val a = _pending.value ?: return
        _pending.value = null
        run(a)
    }

    private suspend fun run(a: AgAction) {
        _busy.value = true
        try {
            val msg = a.perform(caller)
            _message.value = if (demo) "Demo: nothing was sent." else msg ?: "Done."
            if (a.needsConfirm && !demo) runCatching { onDone(a, "Saved") }
            if (a.needsConfirm) refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdGuardError) {
            _message.value = "AdGuard Home refused: ${e.message}"
            if (a.needsConfirm && !demo) runCatching { onDone(a, "Failed: ${e.message}") }
        } catch (e: Exception) {
            _message.value = "Could not do it (${e::class.simpleName})."
        } finally {
            _busy.value = false
        }
    }
}
