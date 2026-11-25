package com.shaazifi.app.feature.web

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.shaazifi.app.ui.components.GhostButton

@Composable
internal fun RouterPageButton(label: String, hasUntangled: Boolean, onOpen: (RouterWebUi) -> Unit) {
    if (!hasUntangled) {
        GhostButton(label, onClick = { onOpen(RouterWebUi.Luci) })
        return
    }

    var expanded by remember { mutableStateOf(false) }
    Box {
        GhostButton(label, onClick = { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RouterWebUi.entries.forEach { webUi ->
                DropdownMenuItem(
                    text = { Text("Open in ${webUi.label}") },
                    onClick = {
                        expanded = false
                        onOpen(webUi)
                    }
                )
            }
        }
    }
}
