package com.shaazifi.app.feature.router

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.shaazifi.app.ui.components.GhostButton
import com.shaazifi.app.ui.components.Pill
import com.shaazifi.app.ui.components.PrimaryButton
import com.shaazifi.app.ui.components.SfCard
import com.shaazifi.app.ui.components.SfDivider
import com.shaazifi.app.ui.components.SfList
import com.shaazifi.app.ui.theme.PillTone
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Extend Wi-Fi (phase 2): scan for nearby networks with one radio (`iwinfo scan`, as LuCI) and
 * join one as a client. The join is a [ChangePlan] shown for confirmation and run with the
 * router's automatic undo. [reader] null = demo (sample networks, nothing sent).
 */
@Composable
fun WifiScanCard(reader: RouterReader?, onPlan: ((PlanResult) -> String?)?) {
    val c = SfTheme.colors
    val scope = rememberCoroutineScope()
    var radios by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) } // id to band label
    var radio by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<ScanEntry>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var chosen by remember { mutableStateOf<ScanEntry?>(null) }
    var password by remember { mutableStateOf("") }

    LaunchedEffect(reader) {
        radios = if (reader == null) listOf("radio0" to "2.4 GHz", "radio1" to "5 GHz") else runCatching {
            val wifi = reader.required("luci-rpc", "getWirelessDevices")
            WifiRadio.radioInfos(wifi, emptyMap(), emptyMap()).map { it.id to WifiRadio.bandLabel(it.band) }
        }.getOrDefault(emptyList())
        radio = radios.firstOrNull()?.first
    }

    SfCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 10.dp) {
        Text("Find a network to join", style = MaterialTheme.typography.titleMedium, color = c.ink)
        Text("The router scans with one of its radios. Joining uses that radio, so its own Wi-Fi moves to the other network's channel.", style = SfText.sub, color = c.muted)
        if (radios.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            radios.forEach { (id, label) -> Pill(label, tone = if (id == radio) PillTone.Ok else PillTone.Off, onClick = { radio = id; results = null; chosen = null }) }
        }
        PrimaryButton(if (busy) "Scanning..." else "Scan", enabled = !busy && radio != null, onClick = {
            val r = radio ?: return@PrimaryButton
            busy = true; message = null; chosen = null
            scope.launch {
                results = try {
                    if (reader == null) WifiRadio.parseScan(kotlinx.serialization.json.Json.parseToJsonElement(DEMO_SCAN) as kotlinx.serialization.json.JsonObject)
                    else WifiRadio.parseScan(reader.required("iwinfo", "scan", buildJsonObject { put("device", JsonPrimitive(r)) }))
                } catch (e: FeatureLoadException) {
                    message = when (e) {
                        is FeatureLoadException.NotPermitted -> "The router does not allow scanning (needs the rpcd iwinfo module and permission)."
                        is FeatureLoadException.Unreachable -> "Lost the router during the scan. Try again."
                        else -> "Scanning is not available on this router."
                    }; null
                } catch (e: Exception) {
                    message = "Scan failed (${e::class.simpleName})."; null
                }
                busy = false
            }
        })
        message?.let { Text(it, style = SfText.sub, color = c.bad) }
        results?.let { list ->
            if (list.isEmpty()) Text("No networks found.", style = SfText.sub, color = c.muted)
            else SfList {
                list.forEachIndexed { i, b ->
                    if (i > 0) SfDivider()
                    Row(
                        Modifier.fillMaxWidth().clickable { chosen = b; password = ""; message = null }.padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(b.ssid.ifBlank { "(hidden network)" }, color = c.ink, fontWeight = FontWeight.SemiBold)
                            Text("${b.security} · channel ${b.channel ?: "?"}", style = SfText.sub, color = c.muted)
                        }
                        Text(b.signal?.let { "$it dBm" } ?: "", style = SfText.mono, color = c.muted)
                    }
                }
            }
        }
        chosen?.let { b ->
            Text("Join \"${b.ssid}\"", style = MaterialTheme.typography.titleSmall, color = c.ink)
            if (b.encryption != "none") OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password of \"${b.ssid}\"") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )
            PrimaryButton("Join", enabled = onPlan != null, onClick = {
                val r = radio ?: return@PrimaryButton
                val submit = onPlan ?: return@PrimaryButton
                scope.launch {
                    message = try {
                        if (reader == null) "Demo: nothing is sent." else {
                            val wifi = reader.required("luci-rpc", "getWirelessDevices")
                            val fw = reader.uci("firewall") ?: UciConfig(emptyList())
                            val net = reader.uci("network") ?: UciConfig(emptyList())
                            submit(WifiRadio.join(r, b, password, wifi, fw, net)).also { if (it == null) { password = ""; chosen = null } }
                        }
                    } catch (e: Exception) {
                        "Could not prepare the change (${e::class.simpleName})."
                    }
                }
            })
            GhostButton("Cancel", onClick = { chosen = null; password = "" })
        }
    }
}

private const val DEMO_SCAN = """{"results":[{"ssid":"Cafe Wi-Fi","bssid":"02:00:00:00:00:01","channel":6,"signal":-58,"encryption":{"enabled":true,"wpa":[2],"authentication":["psk"]}},{"ssid":"Library","bssid":"02:00:00:00:00:02","channel":11,"signal":-74,"encryption":{"enabled":false}}]}"""
