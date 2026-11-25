package com.shaazifi.app.feature.demo

import com.shaazifi.app.feature.devices.AccessRule
import com.shaazifi.app.feature.devices.DeviceList
import com.shaazifi.app.feature.router.ChangeOutcome
import com.shaazifi.app.feature.router.ChangePlan
import com.shaazifi.app.feature.router.FeatureMappers
import com.shaazifi.app.feature.router.FeatureView
import com.shaazifi.app.feature.router.LoadHolder
import com.shaazifi.app.feature.router.Phase4
import com.shaazifi.app.feature.router.UciOp
import com.shaazifi.app.feature.router.UciSection

internal class DemoRouterChanges(
    private val devices: LoadHolder<DeviceList>,
    private val services: () -> LoadHolder<FeatureView>
) {
    private var ruleNumber = 0

    fun run(plan: ChangePlan): ChangeOutcome {
        val call = plan.ubusCall
        if (call?.obj == "rc" && call.method == "init") {
            val name = call.args["name"].orEmpty()
            val action = call.args["action"].orEmpty()
            if (!Phase4.isAllowedServiceAction(name, action)) return ChangeOutcome.Failed("Action unavailable.")
            services().updateDemo { view ->
                FeatureMappers.services(view.services.map { service ->
                    if (service.name != name) service else when (action) {
                        "start", "restart" -> service.copy(running = true)
                        "stop" -> service.copy(running = false)
                        "enable" -> service.copy(enabled = true)
                        "disable" -> service.copy(enabled = false)
                        else -> service
                    }
                })
            }
        } else if (plan.ops.isNotEmpty() && plan.ops.all { it.config == "firewall" }) {
            devices.updateDemo { list ->
                val rules = list.accessRules.orEmpty().toMutableList()
                plan.ops.forEach { op ->
                    when (op) {
                        is UciOp.Add -> {
                            val options = op.values.mapValues { (_, value) ->
                                if (value is List<*>) value.filterIsInstance<String>() else listOf(value.toString())
                            }
                            AccessRule.parseRule(UciSection("demo-rule-${++ruleNumber}", op.type, options))?.let { rules.add(it) }
                        }
                        is UciOp.Set -> rules.replaceAll {
                            if (it.id == op.section) it.copy(enabled = op.values["enabled"] == "1") else it
                        }
                        is UciOp.Delete -> rules.removeAll { it.id == op.section }
                        else -> Unit
                    }
                }
                list.copy(accessRules = rules, devices = list.devices.map { device ->
                    device.copy(blocked = rules.any { it.mac == device.mac && it.enabled && !it.scheduled })
                })
            }
        } else return ChangeOutcome.Failed("This change needs a real router.")
        return ChangeOutcome.Kept
    }
}
