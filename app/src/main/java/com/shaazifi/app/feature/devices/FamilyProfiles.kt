package com.shaazifi.app.feature.devices

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.shaazifi.app.feature.router.ChangeLine
import com.shaazifi.app.feature.router.ChangePlan
import com.shaazifi.app.feature.router.FeatureChanges
import com.shaazifi.app.feature.router.PlanResult
import com.shaazifi.app.feature.router.UciOp
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class FamilyTag(val profileId: String, val kind: String, val label: String) {
    val isSchedule: Boolean get() = kind.startsWith("schedule-")
    fun ruleName() = "ShaaziFi family $profileId $kind $label"

    companion object {
        private val format = Regex("^ShaaziFi family ([0-9a-f]{16}) (member|override|schedule-[0-9a-f]{16}) (.{1,40})$")
        fun parse(name: String): FamilyTag? = format.matchEntire(name)?.destructured?.let { (id, kind, label) ->
            FamilyTag(id, kind, label)
        }
    }
}

@Serializable
internal data class FamilyDraft(val id: String, val label: String)

internal fun newFamilyDraft(raw: String): FamilyDraft? {
    val label = raw.trim().takeIf { it.isNotBlank() && it.length <= 40 && it.none(Char::isISOControl) } ?: return null
    return FamilyDraft(newFamilyId(), label)
}

private fun newFamilyId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

internal class FamilyDraftStore(private val data: DataStore<Preferences>) {
    private fun key(router: String) = stringPreferencesKey("family_drafts_$router")

    fun profiles(router: String): Flow<List<FamilyDraft>> = data.data.map { unpack(it[key(router)]) }

    suspend fun add(router: String, draft: FamilyDraft) {
        val key = key(router)
        data.edit { it[key] = Json.encodeToString(unpack(it[key]) + draft) }
    }

    suspend fun remove(router: String, ids: Set<String>) {
        val key = key(router)
        data.edit { prefs ->
            val remaining = unpack(prefs[key]).filterNot { it.id in ids }
            if (remaining.isEmpty()) prefs.remove(key) else prefs[key] = Json.encodeToString(remaining)
        }
    }

    private fun unpack(raw: String?): List<FamilyDraft> =
        raw?.let { runCatching { Json.decodeFromString<List<FamilyDraft>>(it) }.getOrNull() }.orEmpty()
}

internal data class FamilyProfile(val id: String, val label: String, val rules: List<AccessRule>) {
    val members: List<AccessRule> get() = rules.filter { it.family?.kind == "member" }.distinctBy { it.mac }
    val macs: Set<String> get() = members.mapTo(mutableSetOf()) { it.mac }
    val paused: Boolean get() = members.isNotEmpty() && members.all { it.enabled }
    val schedules: Map<String, List<AccessRule>>
        get() = rules.filter { it.mac in macs && it.family?.isSchedule == true }.groupBy { it.family!!.kind }
}

internal fun familyProfiles(rules: List<AccessRule>, drafts: List<FamilyDraft>): List<FamilyProfile> {
    val owned = rules.mapNotNull { rule -> rule.family?.let { it.profileId to rule } }
        .groupBy({ it.first }, { it.second })
    val configured = owned.map { (id, scoped) ->
        val first = scoped.firstOrNull { it.family?.kind == "member" } ?: scoped.first()
        FamilyProfile(id, first.family!!.label, scoped)
    }
    return (configured + drafts.filterNot { it.id in owned }.map { FamilyProfile(it.id, it.label, emptyList()) })
        .sortedBy { it.label.lowercase() }
}

internal object FamilyChanges {
    private val macPattern = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

    fun overrideActive(rules: List<AccessRule>): Boolean = rules.any { it.family?.kind == "override" && it.enabled }

    fun assign(profile: FamilyProfile, chosen: List<Device>, rules: List<AccessRule>): PlanResult {
        if (chosen.isEmpty()) return PlanResult.Invalid("Choose at least one device.")
        val assigned = rules.filter { it.family?.kind == "member" }.map { it.mac }.toSet()
        if (chosen.any { it.mac in assigned || !macPattern.matches(it.mac) || it.network?.sourceZone == null }) {
            return PlanResult.Invalid("Each device needs a known firewall zone and can belong to one profile.")
        }
        val override = overrideActive(rules)
        val ops = chosen.distinctBy { it.mac }.flatMap { device ->
            val zone = device.network!!.sourceZone!!
            buildList {
                add(addRule(profile, "member", device.mac, zone, profile.paused))
                if (override) add(addRule(profile, "override", device.mac, zone, true))
                profile.schedules.forEach { (id, group) ->
                    val rule = group.first()
                    add(addRule(profile, id, device.mac, zone, rule.enabled, rule))
                }
            }
        }
        return plan("Assign devices to ${profile.label}?",
            chosen.map { ChangeLine(it.name, "Unassigned", profile.label) }, ops,
            "This profile’s restrictions apply to the selected devices.")
    }

    fun profilePause(profile: FamilyProfile): PlanResult {
        val pause = !profile.paused
        return plan("${if (pause) "Pause" else "Resume"} ${profile.label}?",
            listOf(ChangeLine(profile.label, "", if (pause) "Paused" else "Profile pause lifted")),
            profile.rules.filter { it.family?.kind == "member" }.map { enabled(it, pause) },
            "Family-wide pause, device pauses and schedules are independent.")
    }

    fun familyPause(profiles: List<FamilyProfile>, rules: List<AccessRule>): PlanResult {
        val overrides = rules.filter { it.family?.kind == "override" }
        if (overrideActive(rules)) {
            return plan("Resume family internet?", profiles.filter { p -> overrides.any { it.family?.profileId == p.id && it.enabled } }
                .map { ChangeLine(it.label, "Family pause", "Family pause lifted") },
                overrides.map { UciOp.Delete("firewall", it.id) },
                "Individual pauses and schedules still apply.")
        }
        val included = profiles.filter { it.members.isNotEmpty() }
        val ops = overrides.map { UciOp.Delete("firewall", it.id) } + included.flatMap { profile ->
            profile.members.map { addRule(profile, "override", it.mac, it.sourceZone, true) }
        }
        if (included.isEmpty()) return PlanResult.Invalid("Assign devices to a family profile first.")
        return plan("Pause family internet?", included.map { ChangeLine(it.label, "", "${it.macs.size} devices paused") }, ops,
            "Only devices assigned to these profiles are affected. Resume family internet to lift this pause.")
    }

    fun addSchedule(profile: FamilyProfile, days: String, from: String, until: String): PlanResult {
        val first = profile.members.firstOrNull() ?: return PlanResult.Invalid("Assign a device before adding a schedule.")
        val validated = validateScheduleTimes(
            FeatureChanges.parentalSchedule(first.mac, days, from, until, mapOf(first.mac to profile.label))
        )
        if (validated !is PlanResult.Ready) return validated
        val values = (validated.plan.ops.single() as UciOp.Add).values
        val template = first.copy(days = values["weekdays"] as? String ?: "",
            from = values.getValue("start_time").toString(), until = values.getValue("stop_time").toString())
        val id = "schedule-${newFamilyId()}"
        return plan("Schedule ${profile.label}?",
            listOf(ChangeLine(profile.label, "", "${template.days.ifBlank { "Every day" }} · ${template.from}–${template.until}")),
            profile.members.map { addRule(profile, id, it.mac, it.sourceZone, true, template) },
            "The schedule follows the router’s clock.")
    }

    fun schedule(profile: FamilyProfile, key: String, enable: Boolean?): PlanResult {
        val action = when (enable) { null -> "Remove"; true -> "Enable"; false -> "Disable" }
        val ops = profile.schedules[key].orEmpty().map { rule ->
            if (enable == null) UciOp.Delete("firewall", rule.id) else enabled(rule, enable)
        }
        return plan("$action schedule for ${profile.label}?", listOf(ChangeLine("Schedule", "", action)), ops)
    }

    fun removeMember(profile: FamilyProfile, mac: String, label: String): PlanResult {
        val lastMember = profile.macs.size == 1 && mac in profile.macs
        val ops = if (lastMember) profile.rules.map { UciOp.Delete("firewall", it.id) }
        else profile.rules.filter { it.mac == mac }.map { UciOp.Delete("firewall", it.id) }
        return plan("Remove $label from ${profile.label}?", listOf(ChangeLine(label, profile.label, "Unassigned")),
            ops,
            if (lastMember) "Removing the last device also removes this profile. Individual device rules still apply."
            else "Individual device rules still apply.")
    }

    fun deleteProfile(profile: FamilyProfile): PlanResult =
        plan("Remove ${profile.label}?", listOf(ChangeLine(profile.label, "Family profile", "Removed")),
            profile.rules.map { UciOp.Delete("firewall", it.id) }, "Individual device rules still apply.")

    private fun enabled(rule: AccessRule, enable: Boolean) =
        UciOp.Set("firewall", rule.id, mapOf("enabled" to if (enable) "1" else "0"))

    private fun addRule(profile: FamilyProfile, kind: String, mac: String, zone: String, enabled: Boolean,
                        schedule: AccessRule? = null): UciOp.Add {
        require(macPattern.matches(mac) && zone.matches(Regex("[A-Za-z0-9_]{1,32}")) && zone != "wan")
        val tag = FamilyTag(profile.id, kind, profile.label).ruleName()
        val values = mutableMapOf<String, Any>(
            "name" to tag, "src" to zone, "dest" to "wan", "src_mac" to mac,
            "proto" to "all", "target" to "REJECT", "enabled" to if (enabled) "1" else "0"
        )
        if (schedule != null) {
            if (schedule.days.isNotBlank()) values["weekdays"] = schedule.days
            values["start_time"] = schedule.from
            values["stop_time"] = schedule.until
        }
        return UciOp.Add("firewall", "rule", values)
    }

    private fun plan(title: String, lines: List<ChangeLine>, ops: List<UciOp>, note: String? = null): PlanResult =
        if (ops.isEmpty()) PlanResult.Invalid("There are no profile rules to change.")
        else PlanResult.Ready(ChangePlan(title, lines, ops.distinct(), reloadFirewall = true, note = note))
}
