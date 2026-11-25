package com.shaazifi.app.feature.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shaazifi.app.R
import com.shaazifi.app.ui.components.Eyebrow
import com.shaazifi.app.ui.components.Note
import com.shaazifi.app.ui.components.SfDivider
import com.shaazifi.app.ui.components.SfList
import com.shaazifi.app.ui.components.SfListRow
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme

/** About ShaaziFi: licence and credits (GPL-3.0 since 2026-11-17). Works in demo; no router needed. */
@Composable
fun AboutScreen() {
    val c = SfTheme.colors
    val appName = stringResource(R.string.app_name)
    val context = LocalContext.current
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    Column(
        Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        com.shaazifi.app.ui.components.PageHero(
            com.shaazifi.app.ui.icons.SfIcons.RouterLive, com.shaazifi.app.ui.theme.SfHue.GREEN, appName, "Free software · GPL-3.0",
            "A free app for managing OpenWrt routers with LuCI, and AdGuard Home. Free software under the GNU General Public " +
                "License version 3: you may use, study, share and change it under the same licence."
        )
        SfList {
            SfListRow(title = "Licence", subtitle = "GNU GPL version 3", icon = com.shaazifi.app.ui.icons.SfIcons.Lock, hue = com.shaazifi.app.ui.theme.SfHue.BLUE, onClick = { open("https://www.gnu.org/licenses/gpl-3.0.html") })
        }
        com.shaazifi.app.ui.components.SectionBar("Credits", com.shaazifi.app.ui.theme.SfHue.VIOLET)
        val creditHues = listOf(com.shaazifi.app.ui.theme.SfHue.BLUE, com.shaazifi.app.ui.theme.SfHue.VIOLET, com.shaazifi.app.ui.theme.SfHue.PINK, com.shaazifi.app.ui.theme.SfHue.TEAL, com.shaazifi.app.ui.theme.SfHue.ORANGE, com.shaazifi.app.ui.theme.SfHue.GREEN)
        SfList {
            Credits.entries.forEachIndexed { i, cr ->
                if (i > 0) SfDivider()
                SfListRow(title = cr.name, subtitle = "${cr.author} · ${cr.licence}\n${cr.how}", icon = com.shaazifi.app.ui.icons.SfIcons.Bulb, hue = creditHues[i % creditHues.size], onClick = { open(cr.url) })
            }
        }
        Note("Names of other projects belong to their owners and only say what $appName works with. $appName is not affiliated with them.")
        Box(Modifier.padding(bottom = 12.dp))
    }
}
