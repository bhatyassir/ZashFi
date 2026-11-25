package com.shaazifi.app.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shaazifi.app.data.RouterProfile
import com.shaazifi.app.feature.routers.RouterDisplay
import com.shaazifi.app.ui.components.GhostButton
import com.shaazifi.app.ui.components.Pill
import com.shaazifi.app.ui.components.PrimaryButton
import com.shaazifi.app.ui.theme.PillTone
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme
import kotlinx.coroutines.launch

/**
 * M9 item 2: the router's VPN address (for example Tailscale), inside the "This router" card.
 * The app uses it only when the home address cannot be reached. Its certificate is shown to the
 * owner and approved before it is saved, like a new router. Nothing is sent to the router here
 * except the certificate check (no password).
 */
@Composable
fun VpnAddressRows(
    profile: RouterProfile,
    handler: RouterSettingsHandler,
    probeCertificate: suspend (String) -> Result<String>,
    onProfileUpdated: (RouterProfile) -> Unit
) {
    val c = SfTheme.colors
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var input by remember(profile.vpnAddress) { mutableStateOf(profile.vpnAddress.orEmpty()) }
    var checked by remember { mutableStateOf<Pair<String, String>?>(null) } // address to fingerprint
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Row(
        modifier = Modifier.fillMaxWidth().clickable { open = !open; message = null }.padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text("VPN address", fontWeight = FontWeight.SemiBold, color = c.ink)
            Text(
                profile.vpnAddress?.let { RouterDisplay.hostLabel(it) } ?: "Used when you are away from home. Tap to add.",
                style = if (profile.vpnAddress != null) SfText.mono else SfText.sub,
                color = c.muted
            )
        }
        if (profile.vpnAddress != null) Pill("Approved", tone = PillTone.Ok) else Text("Add", color = c.accent, fontWeight = FontWeight.SemiBold)
    }
    if (!open) return

    Column(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val pending = checked
        if (pending == null) {
            Text(
                "The router's address on your VPN, for example its Tailscale address. The app tries the home address first and uses this one only when home cannot be reached.",
                style = SfText.sub,
                color = c.muted
            )
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; message = null },
                label = { Text("VPN address") },
                placeholder = { Text("100.64.0.7") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            )
            message?.let { Text(it, style = SfText.sub, color = c.bad) }
            PrimaryButton(if (busy) "Checking..." else "Check the certificate", enabled = !busy && input.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    when (val r = handler.checkVpnAddress(profile, input)) {
                        is VpnAddressResult.Error -> message = r.message
                        is VpnAddressResult.Checked -> {
                            val fp = probeCertificate(r.address)
                            fp.onSuccess { checked = r.address to it }
                                .onFailure { message = "Could not reach ${RouterDisplay.hostLabel(r.address)}. Is the VPN on?" }
                        }
                    }
                    busy = false
                }
            })
            if (profile.vpnAddress != null) {
                GhostButton("Remove the VPN address", onClick = {
                    scope.launch {
                        handler.removeVpnAddress(profile.id)?.let(onProfileUpdated)
                        input = ""
                        open = false
                    }
                })
            }
        } else {
            val (address, fp) = pending
            val http = fp == RouterDisplay.HTTP_NO_PIN || address.startsWith("http://", ignoreCase = true)
            Text("Approve this certificate?", fontWeight = FontWeight.SemiBold, color = c.ink)
            Text(RouterDisplay.hostLabel(address), style = SfText.mono, color = c.muted)
            if (http) {
                Text("This address uses plain HTTP: there is no certificate to check.", style = SfText.sub, color = c.warn)
            } else {
                RouterDisplay.fingerprintLines(fp).forEach { Text(it, style = SfText.mono, color = c.muted) }
                Text(
                    if (fp == profile.certificateFingerprint) "This is the same certificate as the home address."
                    else "This is a different certificate from the home address. Approve only if you trust it.",
                    style = SfText.sub,
                    color = if (fp == profile.certificateFingerprint) c.muted else c.warn
                )
            }
            PrimaryButton("Approve and save", onClick = {
                scope.launch {
                    handler.saveVpnAddress(profile.id, address, fp)?.let(onProfileUpdated)
                    checked = null
                    open = false
                }
            })
            GhostButton("Cancel", onClick = { checked = null })
        }
    }
}
