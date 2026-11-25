package com.shaazifi.app.feature.router

import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import com.shaazifi.app.ui.components.PageHeading
import com.shaazifi.app.ui.components.SectionTitle
import com.shaazifi.app.ui.components.MetricTile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.shaazifi.app.ui.components.SfSwitch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shaazifi.app.ui.components.Eyebrow
import com.shaazifi.app.ui.components.GhostButton
import com.shaazifi.app.ui.components.Note
import com.shaazifi.app.ui.components.Pill
import com.shaazifi.app.ui.components.PrimaryButton
import com.shaazifi.app.ui.components.SfCard
import com.shaazifi.app.ui.components.SfDivider
import com.shaazifi.app.ui.components.SfList
import com.shaazifi.app.ui.components.SfListRow
import com.shaazifi.app.ui.icons.SfIcons
import com.shaazifi.app.ui.theme.PillTone
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme
import kotlinx.coroutines.launch

/**
 * Shared frame for every native screen: shows the six states and hands the data to [content].
 * [content] gets the value and whether it is demo data.
 */
@Composable
fun <T> LoadStateContent(
    holder: LoadHolder<T>,
    onSignInAgain: () -> Unit,
    content: @Composable (value: T, isDemo: Boolean) -> Unit
) {
    val state by holder.state.collectAsState()
    val scope = rememberCoroutineScope()
    PollVisible(holder)
    when (val s = state) {
        LoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = SfTheme.colors.accent)
        }
        is LoadState.Loaded -> content(s.value, false)
        is LoadState.DemoData -> content(s.value, true)
        LoadState.NotInstalled -> StateCard(
            "Not on this router",
            "This feature needs a package or setting the router does not have.",
            ViewPill("Not installed", PillTone.Off)
        ) { GhostButton("Check again", onClick = { scope.launch { holder.refresh() } }) }
        LoadState.NotPermitted -> StateCard(
            "The router does not allow this",
            "The router's access rules do not let the app read this. A permission file for the router comes with the next step.",
            ViewPill("Not permitted", PillTone.Warn)
        ) { GhostButton("Check again", onClick = { scope.launch { holder.refresh() } }) }
        LoadState.Unreachable -> StateCard(
            "Can't reach the router",
            "Check that this phone is on the router's Wi-Fi or VPN.",
            ViewPill("Offline", PillTone.Bad)
        ) { GhostButton("Try again", onClick = { scope.launch { holder.refresh() } }) }
        LoadState.SessionExpired -> StateCard(
            "Signed out",
            "The router ended the session. Sign in again to continue.",
            ViewPill("Signed out", PillTone.Warn)
        ) { PrimaryButton("Sign in again", onClick = onSignInAgain) }
    }
}

@Composable
internal fun PollVisible(holder: LoadHolder<*>) {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(holder, lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { holder.pollWhileActive() }
    }
}

@Composable
private fun StateCard(title: String, body: String, pill: ViewPill, actions: @Composable ColumnScope.() -> Unit) {
    val c = SfTheme.colors
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        SfCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 12.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
                Pill(pill.text, tone = pill.tone)
            }
            Text(body, style = SfText.sub, color = c.muted)
            actions()
        }
    }
}

/** One native feature screen in the mock's "feature detail" style. Read-only. */
@Composable
fun FeatureScreen(
    spec: FeatureSpec,
    holder: LoadHolder<FeatureView>,
    onSignInAgain: () -> Unit,
    routerPageAction: (@Composable (label: String, featureId: String) -> Unit)?,
    changes: ChangeController? = null,
    onDevice: ((String) -> Unit)? = null,
    routerModel: String = "",
    routerAddress: String = "",
    onLiveTraffic: (() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null
) {
    LoadStateContent(holder, onSignInAgain) { view, isDemo ->
        FeatureViewContent(spec, view, routerPageAction, if (isDemo) null else changes,
            onDevice, routerModel, routerAddress, onLiveTraffic, footer)
    }
}

@Composable
fun FeatureViewContent(
    spec: FeatureSpec,
    view: FeatureView,
    routerPageAction: (@Composable (label: String, featureId: String) -> Unit)?,
    changes: ChangeController? = null,
    onDevice: ((String) -> Unit)? = null,
    routerModel: String = "",
    routerAddress: String = "",
    onLiveTraffic: (() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null
) {
    val c = SfTheme.colors
    var actionError by remember(view) { mutableStateOf<String?>(null) }
    val request: (PlanResult) -> Unit = { result ->
        when (result) {
            is PlanResult.Ready -> { actionError = null; changes?.request(result.plan) }
            is PlanResult.Invalid -> actionError = result.message
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        val custom = spec.id in setOf("health", "bandwidth", "live")
        if (!custom) PageHeading(spec.title, when (spec.id) {
            "guest" -> "Separate Wi-Fi for visitors."
            "interfaces" -> "See wired links and network addresses."
            else -> view.summary
        }, view.pill?.text, view.pill?.tone ?: PillTone.Off)
        actionError?.let { Text(it, style = SfText.sub, color = c.bad) }

        val picture = view.network
        when {
            spec.id == "health" -> SystemHealth(view, routerModel, routerAddress)
            spec.id == "live" -> LiveTrafficPage(view)
            spec.id == "bandwidth" -> BandwidthPage(view, LocalKnownDevices.current, onDevice)
            picture != null -> NetworkPictureView(picture, onLiveTraffic)
            else -> view.sections.forEach { section ->
                SectionTitle(section.heading)
                val formsVisible = changes != null && (section.form != null || section.moreForms.isNotEmpty())
                if (spec.id !in setOf("traffic", "usage", "guest", "mesh") && !formsVisible && SectionLook.asTiles(section)) {
                    section.rows.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { row -> MetricTile(row.label, row.value, Modifier.weight(1f), note = row.sub) }
                            if (pair.size == 1) Box(Modifier.weight(1f))
                        }
                    }
                    return@forEach
                }
                val mainRows = section.rows.filterNot { it.detail }
                val detailRows = section.rows.filter { it.detail }
                val form = if (changes != null) section.form else null
                if (section.rows.isEmpty() && form == null) {
                    Note(section.emptyText ?: "Nothing here.")
                } else {
                    // One card per group: its rows, its folded details, and its own change form.
                    SfList {
                        var first = true
                        if (section.rows.isEmpty()) {
                            FeatureRow(ViewRow(section.emptyText ?: "Nothing here."), null); first = false
                        }
                        mainRows.forEach { r ->
                            if (!first) SfDivider(); first = false
                            FeatureRow(r, if (changes != null) request else null)
                        }
                        if (detailRows.isNotEmpty()) {
                            if (!first) SfDivider(); first = false
                            FoldRow("Details", detailRows.joinToString(" · ") { it.value.ifBlank { it.label } }) {
                                detailRows.forEach { r -> SfDivider(); FeatureRow(r, if (changes != null) request else null) }
                            }
                        }
                        if (form != null) {
                            if (!first) SfDivider(); first = false
                            FoldForm(form, submit = { result -> changes?.let { submitForm(result, it) } ?: "Changes are off here." })
                        }
                        if (changes != null) section.moreForms.forEach { f ->
                            if (!first) SfDivider(); first = false
                            FoldForm(f, submit = { result -> submitForm(result, changes) })
                        }
                    }
                }
            }
        }

        if (spec.id != "bandwidth") view.note?.let { Note(it) }

        if (changes != null && view.forms.isNotEmpty()) {
            SectionTitle("Changes")
            SfList {
                view.forms.forEachIndexed { i, form ->
                    if (i > 0) SfDivider()
                    FoldForm(form, submit = { result -> submitForm(result, changes) })
                }
            }
        }
        footer?.invoke(this)

        if (routerPageAction != null && spec.untangledId != null) {
            val hasActions = changes != null && (view.forms.isNotEmpty() || view.sections.any { s -> s.rows.any { it.onToggle != null || it.extra != null } })
            routerPageAction(if (hasActions) "More in the router pages" else "Change in the router pages", spec.untangledId)
        }
        Box(Modifier.padding(bottom = 12.dp))
    }
}

/**
 * Owner feedback 2026-11-18: "use more GUI elements in every page". Which groups of rows are
 * plain short facts (shown as a grid of tiles) and which need the list (switches, buttons,
 * long text, details).
 */
object SectionLook {
    fun asTiles(section: ViewSection): Boolean =
        section.rows.size in 2..8 && section.rows.all { r ->
            r.toggle == null && r.onToggle == null && r.extra == null && !r.detail &&
                r.value.isNotBlank() && r.value.length <= 18 && r.label.length <= 24 &&
                (r.sub == null || r.sub.length <= 44)
        }
}


@Composable
private fun FeatureRow(r: ViewRow, request: ((PlanResult) -> Unit)?) {
    val c = SfTheme.colors
    val toggleAction = if (request != null) r.onToggle else null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (toggleAction != null) Modifier.clickable { request?.invoke(toggleAction()) } else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Owner feedback 2026-11-18: a status picture in front of each row that has a state.
        val state = r.pill?.tone ?: r.toggle?.let { if (it) PillTone.Ok else PillTone.Off }
        if (state != null) {
            val (icon, hue) = when (state) {
                PillTone.Ok -> SfIcons.Check to com.shaazifi.app.ui.theme.SfHue.GREEN
                PillTone.Warn -> SfIcons.Bolt to com.shaazifi.app.ui.theme.SfHue.ORANGE
                PillTone.Bad -> SfIcons.Ban to com.shaazifi.app.ui.theme.SfHue.RED
                PillTone.Off -> SfIcons.More to com.shaazifi.app.ui.theme.SfHue.SLATE
            }
            com.shaazifi.app.ui.components.HueTile(icon, hue, size = 34.dp, round = true)
        }
        Column(Modifier.weight(1f)) {
            Text(r.label, color = c.ink, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (r.value.isNotEmpty() && r.toggle != null) {
                Text(r.value, style = if (r.mono) SfText.mono else SfText.sub, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            r.sub?.let { Text(it, style = if (r.mono) SfText.mono else SfText.sub, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            if (request != null && r.extra != null) {
                Text(
                    r.extra.label,
                    color = c.bad,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.5.sp,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .border(1.dp, c.bad, androidx.compose.foundation.shape.CircleShape)
                        .clickable { request(r.extra.build()) }
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
        if (r.value.isNotEmpty() && r.toggle == null) {
            Text(
                r.value,
                style = if (r.mono) SfText.mono else SfText.sub,
                color = c.ink,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        if (r.toggle == null) r.pill?.let { Pill(it.text, tone = it.tone) }
        if (r.toggle != null) {
            // Shows the router's current value; tapping asks for confirmation, so nothing changes until Apply.
            SfSwitch(checked = r.toggle, enabled = toggleAction != null)
        }
    }
}

/** Sends a ready plan for confirmation, or returns the reason it is not valid (shown inside the form). */
private fun submitForm(result: PlanResult, changes: ChangeController): String? = when (result) {
    is PlanResult.Ready -> { changes.request(result.plan); null }
    is PlanResult.Invalid -> result.message
}

/** A row that opens to show more rows underneath, inside the same card. */
@Composable
private fun FoldRow(title: String, summary: String?, content: @Composable () -> Unit) {
    val c = SfTheme.colors
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = !open }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontWeight = FontWeight.SemiBold)
            if (!open && !summary.isNullOrBlank()) {
                Text(summary, style = SfText.mono, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(SfIcons.ChevronRight, contentDescription = if (open) "Close" else "Open", tint = c.muted, modifier = Modifier.size(18.dp).rotate(if (open) 90f else 0f))
    }
    if (open) content()
}

/**
 * A change form folded into one row of its card. Opening shows the fields; values are checked when
 * the plan is built, and the plan is shown for confirmation before anything is sent.
 */
@Composable
internal fun FoldForm(form: FeatureForm, submit: (PlanResult) -> String?, showHeader: Boolean = true) {
    val c = SfTheme.colors
    var open by remember(form.title, showHeader) { mutableStateOf(!showHeader) }
    var error by remember(form.title) { mutableStateOf<String?>(null) }
    val values = remember(form) { mutableStateMapOf<String, String>().apply { form.fields.forEach { put(it.key, it.initial) } } }
    if (showHeader) Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = !open; error = null }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        com.shaazifi.app.ui.components.HueTile(SfIcons.Pencil, com.shaazifi.app.ui.theme.SfHue.BLUE, size = 34.dp)
        Text(form.title, color = c.ink, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Icon(SfIcons.ChevronRight, contentDescription = if (open) "Close" else "Open", tint = c.muted, modifier = Modifier.size(18.dp).rotate(if (open) 90f else 0f))
    }
    if (!open) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .background(c.sunk)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        form.fields.forEach { f ->
            f.pick?.let { kind -> DevicePicker(kind, values[f.key].orEmpty()) { values[f.key] = it; error = null } }
            if (f.choices.isNotEmpty()) ChoiceChips(f.choices, f.multi, values[f.key].orEmpty()) { values[f.key] = it; error = null }
            OutlinedTextField(
                value = values[f.key].orEmpty(),
                onValueChange = { values[f.key] = it; error = null },
                label = { Text(f.label) },
                placeholder = f.hint?.let { h -> { Text(h) } },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = when {
                    f.secret -> KeyboardOptions(keyboardType = KeyboardType.Password)
                    f.numeric -> KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    else -> KeyboardOptions.Default
                },
                visualTransformation = if (f.secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth()
            )
        }
        error?.let { Text(it, style = SfText.sub, color = c.bad) }
        PrimaryButton(form.submitLabel, onClick = {
            error = submit(form.build(values.toMap()))
            if (error == null) {
                open = false
                // Typed passwords are not kept once the change has been handed over.
                form.fields.filter { it.secret }.forEach { values[it.key] = "" }
            }
        })
    }
}

/** One entry of the "All features" list that is not a [FeatureSpec] (AdGuard, router pages, LuCI, tools, settings). */
data class ExtraEntry(val group: String, val title: String, val subtitle: String, val onClick: () -> Unit)

/** The mock's "All features" screen: every feature grouped, plus app-level entries. */
@Composable
fun AllFeaturesList(
    onOpenFeature: (String) -> Unit,
    extras: List<ExtraEntry>,
    /** Phase 3: hides features the login's permissions cannot use (unknown permissions hide nothing). */
    isAllowed: (String) -> Boolean = { true },
    /** Owner feedback 2026-11-18: the most opened features, shown first. */
    frequent: List<String> = emptyList(),
    /** Kept by the caller so the list reopens where it was left (owner feedback 2026-11-18). */
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
    filter: String = "",
    onFilterChange: (String) -> Unit = {}
) {
    val c = SfTheme.colors
    val query = filter.trim()
    val specsShown = Features.all.filter { isAllowed(it.id) && listOf(it.title, it.listSubtitle, it.group).any { label -> label.contains(query, true) } }
    val extrasShown = extras.filter { listOf(it.title, it.subtitle, it.group).any { label -> label.contains(query, true) } }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PageHeading("All features", status = "${specsShown.size + extrasShown.size} features", tone = PillTone.Off)
        OutlinedTextField(
            value = filter, onValueChange = onFilterChange, label = { Text("Find a feature") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        if (specsShown.isEmpty() && extrasShown.isEmpty()) Note("No features match your search.")
        val quick = if (query.isBlank()) frequent.mapNotNull { Features.byId(it) } else emptyList()
        if (quick.isNotEmpty()) {
            SectionTitle("Frequently used", modifier = Modifier.padding(top = 6.dp))
            quick.chunked(2).forEach { pair ->
                Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { spec ->
                        val (groupIcon, hue) = groupLook(spec.group)
                        QuickTile(spec.title, spec.listSubtitle, featureIcon(spec.id) ?: groupIcon, hue,
                            Modifier.weight(1f).fillMaxHeight()) { onOpenFeature(spec.id) }
                    }
                    if (pair.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
        val groups = (Features.groups + extrasShown.map { it.group }).distinct()
        groups.forEach { group ->
            val specs = specsShown.filter { it.group == group }
            val extra = extrasShown.filter { it.group == group }
            if (specs.isEmpty() && extra.isEmpty()) return@forEach
            SectionTitle(group, modifier = Modifier.padding(top = 6.dp))
            SfList {
                var first = true
                val (groupIcon, hue) = groupLook(group)
                extra.filter { it.group == Features.PROTECTION }.forEach { e ->
                    if (!first) SfDivider(); first = false
                    EntryRow(e.title, e.subtitle, groupIcon, hue, e.onClick)
                }
                specs.forEach { spec ->
                    if (!first) SfDivider(); first = false
                    EntryRow(spec.title, spec.listSubtitle, featureIcon(spec.id) ?: groupIcon, hue) { onOpenFeature(spec.id) }
                }
                extra.filter { it.group != Features.PROTECTION }.forEach { e ->
                    if (!first) SfDivider(); first = false
                    EntryRow(e.title, e.subtitle, extraIcon(e.title) ?: groupIcon, hue, e.onClick)
                }
            }
        }
        val hidden = Features.all.count { !isAllowed(it.id) }
        if (hidden > 0) Note("$hidden features are hidden because this router login is not allowed to use them. Signing in as root, or installing the app's router permission file (docs/router), shows them.")
        Box(Modifier.padding(bottom = 12.dp))
    }
}

/** Owner feedback (a livelier look): each group has its own colour, each entry an icon. */
private fun groupLook(group: String): Pair<androidx.compose.ui.graphics.vector.ImageVector, com.shaazifi.app.ui.theme.SfHue> = when (group) {
    Features.INTERNET -> SfIcons.Globe to com.shaazifi.app.ui.theme.SfHue.BLUE
    Features.WIFI -> SfIcons.Wifi to com.shaazifi.app.ui.theme.SfHue.TEAL
    Features.PROTECTION -> SfIcons.Shield to com.shaazifi.app.ui.theme.SfHue.VIOLET
    Features.VPN -> SfIcons.Lock to com.shaazifi.app.ui.theme.SfHue.PINK
    Features.SPEED -> SfIcons.Chart to com.shaazifi.app.ui.theme.SfHue.ORANGE
    Features.SYSTEM -> SfIcons.Chip to com.shaazifi.app.ui.theme.SfHue.SLATE
    "Router pages" -> SfIcons.Router to com.shaazifi.app.ui.theme.SfHue.GREEN
    "Tools" -> SfIcons.Tools to com.shaazifi.app.ui.theme.SfHue.ORANGE
    else -> SfIcons.Settings to com.shaazifi.app.ui.theme.SfHue.SLATE
}

private fun featureIcon(id: String): androidx.compose.ui.graphics.vector.ImageVector? = when (id) {
    "interfaces", "lan" -> SfIcons.Port
    "routes", "dualwan" -> SfIcons.Link
    "firewall", "portfwd", "dmz", "upnp", "parental" -> SfIcons.Shield
    "bandwidth", "live", "traffic" -> SfIcons.Arrows
    "usage", "qos" -> SfIcons.Chart
    "admin", "services" -> SfIcons.Settings
    "kernellog", "log", "doctor" -> SfIcons.Tools
    "guest", "mesh", "extend", "wireless" -> SfIcons.Wifi
    else -> null
}

private fun extraIcon(title: String): androidx.compose.ui.graphics.vector.ImageVector? = when {
    title.startsWith("Activity") -> SfIcons.Chart
    title.startsWith("Devices") -> SfIcons.Devices
    title.startsWith("About") -> SfIcons.Bulb
    title.startsWith("Settings") -> SfIcons.Settings
    else -> null
}

/** A big coloured shortcut tile for the "Frequently used" group. */
@Composable
private fun QuickTile(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, hue: com.shaazifi.app.ui.theme.SfHue, modifier: Modifier, onClick: () -> Unit) {
    val c = SfTheme.colors
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(13.dp)
    Column(
        modifier
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.line, shape)
            .clickable(onClickLabel = "Open $title", onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            com.shaazifi.app.ui.components.HueTile(icon, hue, size = 35.dp)
            Icon(SfIcons.ChevronRight, null, tint = c.muted, modifier = Modifier.size(15.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = c.ink, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = c.muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EntryRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, hue: com.shaazifi.app.ui.theme.SfHue, onClick: () -> Unit) {
    SfListRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        icon = icon,
        hue = hue,
        trailing = { Icon(SfIcons.ChevronRight, contentDescription = null, tint = SfTheme.colors.muted, modifier = Modifier.size(18.dp)) }
    )
}
