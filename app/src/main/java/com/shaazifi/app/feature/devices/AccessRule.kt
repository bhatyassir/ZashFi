package com.shaazifi.app.feature.devices

import com.shaazifi.app.feature.router.ChangeLine
import com.shaazifi.app.feature.router.ChangePlan
import com.shaazifi.app.feature.router.FeatureChanges
import com.shaazifi.app.feature.router.PlanResult
import com.shaazifi.app.feature.router.UciConfig
import com.shaazifi.app.feature.router.UciOp
import com.shaazifi.app.feature.router.UciSection
import java.util.Locale

data class AccessRule(
    val id: String,
    val mac: String,
    val enabled: Boolean,
    val days: String = "",
    val from: String = "",
    val until: String = "",
    val family: FamilyTag? = null,
    val sourceZone: String = "lan"
) {
    val scheduled: Boolean get() = days.isNotBlank() || from.isNotBlank() || until.isNotBlank()

    companion object {
        private val macPattern = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")

        fun parse(firewall: UciConfig): List<AccessRule> = firewall.ofType("rule").mapNotNull(::parseRule)

        internal fun parseRule(section: UciSection): AccessRule? {
            val name = section.opt("name").orEmpty()
            val family = FamilyTag.parse(name)
            if (family == null && !name.startsWith("Parental:") && !name.startsWith("Block:")) return null
            val src = section.opt("src") ?: return null
            if (src == "wan" || !src.matches(Regex("[A-Za-z0-9_]{1,32}")) ||
                section.opt("dest") != "wan" ||
                section.opt("target") != "REJECT") return null
            val mac = section.list("src_mac").singleOrNull()?.takeIf(macPattern::matches) ?: return null
            val weekdays = section.list("weekdays").joinToString(" ")
            val rule = AccessRule(section.id, mac.uppercase(Locale.ROOT), section.flag("enabled", default = true),
                weekdays, section.opt("start_time").orEmpty(), section.opt("stop_time").orEmpty(), family, src)
            if (family != null && (family.isSchedule != rule.scheduled || section.opt("proto") != "all")) return null
            return rule
        }
    }
}

internal fun withSourceZone(plan: PlanResult, zone: String?): PlanResult {
    val validZone = zone?.takeIf { it != "wan" && it.matches(Regex("[A-Za-z0-9_]{1,32}")) } ?: return plan
    if (plan !is PlanResult.Ready || validZone == "lan") return plan
    val ops = plan.plan.ops.map { op ->
        if (op is UciOp.Add && op.config == "firewall" && op.type == "rule" && "src" in op.values) {
            op.copy(values = op.values + ("src" to validZone))
        } else op
    }
    return PlanResult.Ready(plan.plan.copy(ops = ops))
}

internal fun validateScheduleTimes(plan: PlanResult): PlanResult {
    if (plan !is PlanResult.Ready) return plan
    val values = (plan.plan.ops.singleOrNull() as? UciOp.Add)?.values ?: return plan
    return if (values["start_time"] == values["stop_time"]) {
        PlanResult.Invalid("Choose different start and end times.")
    } else plan
}

internal fun changeInternet(device: Device, rules: List<AccessRule>): PlanResult {
    val immediate = rules.filter { it.mac == device.mac && !it.scheduled && it.family == null }
    val active = immediate.filter { it.enabled }
    if (active.isEmpty() && immediate.isEmpty()) {
        return withSourceZone(
            FeatureChanges.parentalPause(device.mac, mapOf(device.mac to device.name)),
            device.network?.sourceZone
        )
    }
    val pause = active.isEmpty()
    val targets = if (pause) immediate.take(1) else active
    return PlanResult.Ready(ChangePlan(
        title = if (pause) "Pause internet for ${device.name}?" else "Resume internet for ${device.name}?",
        lines = listOf(ChangeLine(device.name, if (pause) "Allowed" else "Paused", if (pause) "Paused" else "Allowed")),
        ops = targets.map { UciOp.Set("firewall", it.id, mapOf("enabled" to if (pause) "1" else "0")) },
        reloadFirewall = true,
        note = if (!pause && rules.any { it.mac == device.mac && it.scheduled && it.enabled }) {
            "Enabled schedules still apply."
        } else null
    ))
}
