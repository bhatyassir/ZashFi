package com.shaazifi.app.feature.web

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.shaazifi.app.AppContainer
import com.shaazifi.app.R
import com.shaazifi.app.core.adguard.AdGuardCaller
import com.shaazifi.app.core.adguard.HttpAdGuardCaller
import com.shaazifi.app.core.session.BiometricGate
import com.shaazifi.app.core.session.SavedPassword
import com.shaazifi.app.core.session.SecretKind
import com.shaazifi.app.core.session.SessionState
import com.shaazifi.app.data.HttpAdGuardRepository
import com.shaazifi.app.data.RouterProfile
import com.shaazifi.app.data.getAdGuardBaseUrl
import com.shaazifi.app.feature.about.AboutScreen
import com.shaazifi.app.feature.activity.ActivityEntry
import com.shaazifi.app.feature.activity.ActivityLog
import com.shaazifi.app.feature.activity.ActivityScreen
import com.shaazifi.app.feature.activity.AllDevicesScreen
import com.shaazifi.app.feature.activity.AppearanceSection
import com.shaazifi.app.feature.activity.CachedDevice
import com.shaazifi.app.feature.adguard.AdGuardScreen
import com.shaazifi.app.feature.adguard.AdGuardQueryLogScreen
import com.shaazifi.app.feature.adguard.AdGuardStateHolder
import com.shaazifi.app.feature.adguard.AdGuardUiState
import com.shaazifi.app.feature.adguard.DemoAdGuardRepository
import com.shaazifi.app.feature.adguard.manage.AgPage
import com.shaazifi.app.feature.adguard.manage.AgPageHolder
import com.shaazifi.app.feature.adguard.manage.AgPageScreen
import com.shaazifi.app.feature.adguard.manage.DemoAdGuardCaller
import com.shaazifi.app.feature.alerts.AlertsSection
import com.shaazifi.app.feature.demo.DemoMode
import com.shaazifi.app.feature.demo.DemoRouterChanges
import com.shaazifi.app.feature.devices.DemoDevices
import com.shaazifi.app.feature.devices.DeviceDetailScreen
import com.shaazifi.app.feature.devices.DeviceList
import com.shaazifi.app.feature.devices.DeviceSort
import com.shaazifi.app.feature.devices.DevicesScreen
import com.shaazifi.app.feature.devices.FamilyAssignmentScreen
import com.shaazifi.app.feature.devices.FamilyDraft
import com.shaazifi.app.feature.devices.FamilyDraftStore
import com.shaazifi.app.feature.devices.ParentalScreen
import com.shaazifi.app.feature.devices.loadDevices
import com.shaazifi.app.feature.guestqr.GuestQr
import com.shaazifi.app.feature.guestqr.GuestExpiryCard
import com.shaazifi.app.feature.guestqr.GuestQrCard
import com.shaazifi.app.feature.home.DemoHomeRepository
import com.shaazifi.app.feature.home.HomeScreen
import com.shaazifi.app.feature.home.HomeStateHolder
import com.shaazifi.app.feature.home.HomeUiState
import com.shaazifi.app.feature.home.UbusClientCaller
import com.shaazifi.app.feature.home.UbusHomeRepository
import com.shaazifi.app.feature.lock.LockSettingsSection
import com.shaazifi.app.feature.quick.QuickAction
import com.shaazifi.app.feature.reconnect.ReconnectScreen
import com.shaazifi.app.feature.router.AllFeaturesList
import com.shaazifi.app.feature.router.Capabilities
import com.shaazifi.app.feature.router.ChangeController
import com.shaazifi.app.feature.router.ChangeHost
import com.shaazifi.app.feature.router.ConfigBackupCard
import com.shaazifi.app.feature.router.DiagnosticsScreen
import com.shaazifi.app.feature.router.ExtraEntry
import com.shaazifi.app.feature.router.FeatureContext
import com.shaazifi.app.feature.router.FeatureScreen
import com.shaazifi.app.feature.router.FeatureSpec
import com.shaazifi.app.feature.router.FeatureView
import com.shaazifi.app.feature.router.Features
import com.shaazifi.app.feature.router.FrequentFeatures
import com.shaazifi.app.feature.router.LiveUsageMeter
import com.shaazifi.app.feature.router.LoadHolder
import com.shaazifi.app.feature.router.LoadState
import com.shaazifi.app.feature.router.LoadStateContent
import com.shaazifi.app.feature.router.LocalKnownDevices
import com.shaazifi.app.feature.router.PlanResult
import com.shaazifi.app.feature.router.PollVisible
import com.shaazifi.app.feature.router.Phase4
import com.shaazifi.app.feature.router.RouterReader
import com.shaazifi.app.feature.router.RouterWriter
import com.shaazifi.app.feature.router.SafeChangeRunner
import com.shaazifi.app.feature.router.ServicesScreen
import com.shaazifi.app.feature.router.WifiNetworkScreen
import com.shaazifi.app.feature.router.WifiOverview
import com.shaazifi.app.feature.router.WifiScanCard
import com.shaazifi.app.feature.router.runDiagnostic
import com.shaazifi.app.feature.routers.RouterDisplay
import com.shaazifi.app.feature.settings.AdGuardAddressSaveResult
import com.shaazifi.app.feature.settings.SettingsScreen
import com.shaazifi.app.ui.components.BottomBarItem
import com.shaazifi.app.ui.components.Eyebrow
import com.shaazifi.app.ui.components.GhostButton
import com.shaazifi.app.ui.components.Note
import com.shaazifi.app.ui.components.PageLink
import com.shaazifi.app.ui.components.Pill
import com.shaazifi.app.ui.components.PrimaryButton
import com.shaazifi.app.ui.components.ReviewRouterBar
import com.shaazifi.app.ui.components.SectionBar
import com.shaazifi.app.ui.components.SectionTitle
import com.shaazifi.app.ui.components.SfBottomBar
import com.shaazifi.app.ui.components.SfCard
import com.shaazifi.app.ui.components.SfDivider
import com.shaazifi.app.ui.components.SfList
import com.shaazifi.app.ui.components.SfListRow
import com.shaazifi.app.ui.components.TopBarAction
import com.shaazifi.app.ui.icons.SfIcons
import com.shaazifi.app.ui.theme.PillTone
import com.shaazifi.app.ui.theme.SfHue
import com.shaazifi.app.ui.theme.SfText
import com.shaazifi.app.ui.theme.SfTheme
import com.shaazifi.app.ui.theme.ThemeChoice
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
fun AuthenticatedRouterShell(
    container: AppContainer,
    profile: RouterProfile,
    session: SessionState.LoggedIn,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    /** M9: a tile shortcut to open once signed in. */
    quickAction: QuickAction? = null,
    onQuickActionUsed: () -> Unit = {},
    /** Returns to the router list without destroying the background ubus session. */
    onSwitchRouter: () -> Unit = onSignOut
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var nav by remember(profile.id, session.token) { mutableStateOf(ShellNavState()) }
    var isReconnecting by remember(profile.id, session.token) { mutableStateOf(false) }
    var currentProfile by remember(profile) { mutableStateOf(profile) }
    var activeEndpoint by remember(profile.id, session.token) { mutableStateOf(session.endpoint) }

    if (isReconnecting) {
        ReconnectScreen(
            // Wait on the address this session uses (the VPN one, with its own certificate, when signed in over VPN).
            profile = if (session.viaVpn) {
                currentProfile.copy(
                    address = activeEndpoint,
                    certificateFingerprint = currentProfile.vpnCertificateFingerprint ?: currentProfile.certificateFingerprint
                )
            } else {
                currentProfile.copy(address = activeEndpoint)
            },
            onReconnected = { verifiedAddress ->
                if (verifiedAddress.isNotBlank()) {
                    activeEndpoint = verifiedAddress
                    if (!session.viaVpn && verifiedAddress != currentProfile.address) {
                        currentProfile = currentProfile.copy(address = verifiedAddress)
                    }
                }
                isReconnecting = false
            },
            onCancel = {
                isReconnecting = false
            }
        )
        return
    }

    // M9: the address this session reached (home, or VPN when home was unreachable).
    val routerAddress = activeEndpoint
    // Dynamic router origin derived from the session's address
    val parsedUrl = remember(routerAddress) { routerAddress.toHttpUrlOrNull() }
    val routerOrigin = remember(parsedUrl) {
        if (parsedUrl != null) {
            val portPart = if ((parsedUrl.scheme == "https" && parsedUrl.port != 443) ||
                (parsedUrl.scheme == "http" && parsedUrl.port != 80)
            ) ":${parsedUrl.port}" else ""
            "${parsedUrl.scheme}://${parsedUrl.host}$portPart"
        } else {
            routerAddress
        }
    }

    // Offer UnTangled alongside LuCI when the active router serves it.
    var hasUntangled by remember(routerOrigin) { mutableStateOf(false) }
    LaunchedEffect(routerOrigin) {
        if (!DemoMode.isDemo(currentProfile)) hasUntangled = RouterWebPages.detectUntangled(container.okHttpClient, routerOrigin)
    }
    // The router's web pages are checked against the certificate approved for the address in use (VPN or home).
    val webPin = if (session.viaVpn) {
        currentProfile.vpnCertificateFingerprint ?: currentProfile.certificateFingerprint
    } else {
        currentProfile.certificateFingerprint
    }

    // Home keeps its readings while the user visits other tabs.
    val homeStateHolder = remember(currentProfile.id, routerAddress, session.token) {
        if (DemoMode.isDemo(currentProfile)) {
            HomeStateHolder(DemoHomeRepository(), demo = true)
        } else {
            HomeStateHolder(
                UbusHomeRepository(UbusClientCaller(container.ubusClient, routerAddress, session.token))
            )
        }
    }

    // AdGuard Home state holder and repository (v2).
    // Preserves the in-memory password across VPN/AdGuard profile edits for the same router session.
    val adGuardPasswordCache = remember(currentProfile.id, routerAddress) {
        object {
            var lastHolder: AdGuardStateHolder? = null
        }
    }
    val adGuardBaseUrl = remember(currentProfile, routerAddress) {
        currentProfile.copy(address = routerAddress).getAdGuardBaseUrl()
    }
    val adGuardStateHolder = remember(currentProfile.id, routerAddress, adGuardBaseUrl, currentProfile.adguardUsername) {
        val holder = if (DemoMode.isDemo(currentProfile)) {
            AdGuardStateHolder(DemoAdGuardRepository(), isDemo = true)
        } else {
            var stateHolderInstance: AdGuardStateHolder? = null
            val repo = HttpAdGuardRepository(
                adGuardClient = container.adGuardClient,
                baseUrl = adGuardBaseUrl,
                username = currentProfile.adguardUsername
            ) {
                stateHolderInstance?.getPassword()
            }
            AdGuardStateHolder(repo, isDemo = false).also { instance ->
                stateHolderInstance = instance
                adGuardPasswordCache.lastHolder?.getPassword()?.let { instance.setPassword(it) }
            }
        }
        adGuardPasswordCache.lastHolder = holder
        holder
    }

    // AdGuard Home management pages: the same address, user and in-memory password as the dashboard.
    val adGuardCaller: AdGuardCaller = remember(adGuardStateHolder) {
        val repo = adGuardStateHolder.repository as? HttpAdGuardRepository
        if (DemoMode.isDemo(currentProfile) || repo == null) DemoAdGuardCaller()
        else HttpAdGuardCaller(container.okHttpClient, repo.baseUrl, repo.username) { adGuardStateHolder.getPassword() }
    }

    // M9: saved AdGuard password behind fingerprint / PIN.
    val shellContext = LocalContext.current
    val adGuardSavedPassword = remember(currentProfile.id) {
        SavedPassword(
            store = container.secretStore,
            lock = container.appLock,
            activity = shellContext as? FragmentActivity,
            profileId = currentProfile.id,
            kind = SecretKind.ADGUARD,
            usable = BiometricGate.canUnlock(shellContext)
        )
    }

    val isDemoProfile = DemoMode.isDemo(currentProfile)

    // Local-only history: record internet up/down and thermal samples for uptime and trend panels.
    val localHistory = remember(container.dataStore) { com.shaazifi.app.feature.home.LocalHistoryStore(container.dataStore) }
    LaunchedEffect(localHistory, isDemoProfile) {
        if (isDemoProfile) return@LaunchedEffect
        homeStateHolder.state.collect { state ->
            val d = (state as? HomeUiState.Loaded)?.dashboard ?: return@collect
            val now = System.currentTimeMillis()
            runCatching {
                localHistory.recordNetEvent(now, d.internetUp)
                val samples = d.thermals.map { t ->
                    com.shaazifi.app.feature.home.ThermalSample(now, t.id, t.tempCelsius)
                }
                localHistory.recordThermalSamples(samples)
            }
        }
    }

    // M10e: feed the home-screen widget without recomposing the shell on every poll tick.
    LaunchedEffect(homeStateHolder, isDemoProfile, currentProfile.name) {
        if (isDemoProfile) return@LaunchedEffect
        homeStateHolder.state.collect { state ->
            val d = (state as? HomeUiState.Loaded)?.dashboard ?: return@collect
            runCatching {
                container.widgetStore.update(context, currentProfile.id, currentProfile.name, internetUp = d.internetUp, devicesOnline = d.clients?.total)
            }
        }
    }

    // M9 tiles: open the shortcut's screen. "Pause AdGuard" also asks the usual confirmation
    // (nothing is sent until the owner confirms); guest Wi-Fi opens its screen with the switch.
    var pendingPause by remember { mutableStateOf(false) }
    var pendingLed by remember { mutableStateOf(false) }
    LaunchedEffect(quickAction) {
        val q = quickAction ?: return@LaunchedEffect
        nav = q.startNav()
        pendingPause = q == QuickAction.ADGUARD_PAUSE
        pendingLed = q == QuickAction.LED_NIGHT
        onQuickActionUsed()
    }
    LaunchedEffect(adGuardStateHolder, isDemoProfile, currentProfile.name) {
        if (isDemoProfile) return@LaunchedEffect
        adGuardStateHolder.state.collect { state ->
            val loaded = state as? AdGuardUiState.Loaded ?: return@collect
            runCatching {
                container.widgetStore.update(context, currentProfile.id, currentProfile.name, adguardOn = loaded.dashboard.status.protectionEnabled)
            }
        }
    }
    LaunchedEffect(pendingPause, adGuardStateHolder) {
        if (!pendingPause) return@LaunchedEffect
        adGuardStateHolder.state.collect { state ->
            val loaded = state as? AdGuardUiState.Loaded ?: return@collect
            pendingPause = false
            if (loaded.dashboard.status.protectionEnabled) {
                adGuardStateHolder.requestProtectionToggle(enabled = false, durationMs = QuickAction.PAUSE_MS)
            }
        }
    }

    val signOut: () -> Unit = {
        if (DemoMode.isDemo(currentProfile)) {
            // Demo: nothing to sign out of on any router.
            onSignOut()
        } else {
            coroutineScope.launch {
                container.sessionManager.logout()
                WebStorageManager.clearAll()
                onSignOut()
            }
        }
    }
    val switchRouter: () -> Unit = {
        if (isDemoProfile) signOut() else onSwitchRouter()
    }

    BackHandler(enabled = nav.canGoBack) { nav = nav.back() }

    val reader = remember(currentProfile.id, routerAddress, session.token) {
        RouterReader(UbusClientCaller(container.ubusClient, routerAddress, session.token))
    }

    val liveUsageMeter = remember(reader) { LiveUsageMeter() }
    val featureContext = FeatureContext(
        adGuardConfigured = currentProfile.adguardHost != null || currentProfile.adguardPort != null,
        routerIp = parsedUrl?.host ?: "",
        liveUsageMeter = liveUsageMeter,
        localHistory = localHistory
    )
    val featureHolders = remember(reader, isDemoProfile) { mutableMapOf<String, LoadHolder<FeatureView>>() }
    fun holderFor(spec: FeatureSpec): LoadHolder<FeatureView> = featureHolders.getOrPut(spec.id) {
        LoadHolder(
            load = { spec.load(reader, featureContext) },
            demo = if (isDemoProfile) spec.demo else null,
            refreshMs = spec.liveRefreshMs
        )
    }
    val devicesHolder = remember(reader, isDemoProfile, currentProfile.id, currentProfile.name) {
        LoadHolder(load = {
            loadDevices(reader).also { list ->
                if (!isDemoProfile) {
                    // Phase 5: remember this router's device list for "Devices on all routers".
                    runCatching {
                        container.deviceCache.put(
                            currentProfile.id,
                            currentProfile.name,
                            list.devices.map { CachedDevice(it.name, it.ip, it.mac, it.online) }
                        )
                    }
                }
            }
        }, demo = if (isDemoProfile) DemoDevices else null)
    }
    LaunchedEffect(devicesHolder) {
        if (devicesHolder.state.value is LoadState.Loading) runCatching { devicesHolder.refresh() }
    }

    // N1: the only path that changes router settings (confirmation, rollback apply, confirm after reconnect).
    val deviceChanges = remember(reader, isDemoProfile) {
        if (isDemoProfile) null else ChangeController(
            SafeChangeRunner(RouterWriter(UbusClientCaller(container.ubusClient, routerAddress, session.token))),
            onKept = {
                devicesHolder.refresh()
                // Re-read the screens that were opened, so a kept change shows at once.
                featureHolders.filterKeys { it != "bandwidth" }.values.forEach { it.refresh() }
            },
            onDone = { plan, outcome ->
                container.activityStore.add(ActivityLog.entry(plan, outcome, currentProfile.name, System.currentTimeMillis()))
            }
        )
    }
    val changes = deviceChanges ?: remember(devicesHolder, featureHolders) {
        val demo = DemoRouterChanges(devicesHolder) { holderFor(Features.byId("services")!!) }
        ChangeController(runPlan = { demo.run(it) })
    }

    // A Quick Settings tile may start the LED change: show the same confirmation as any other change.
    LaunchedEffect(pendingLed, changes) {
        if (!pendingLed) return@LaunchedEffect
        pendingLed = false
        if (!isDemoProfile && Phase4.ledMode("night") is PlanResult.Ready) {
            changes.request((Phase4.ledMode("night") as PlanResult.Ready).plan)
        }
    }

    // Phase 3: what this login may do (rpcd ACL), read once. No answer = hide nothing.
    var capabilities by remember(reader) { mutableStateOf(Capabilities.UNKNOWN) }
    LaunchedEffect(reader) {
        if (!isDemoProfile) capabilities = reader.optional("session", "access")?.let { Capabilities.parse(it) } ?: Capabilities.UNKNOWN
    }
    // Phase 3: change forms must follow what this login may actually write. Demo mode keeps them.
    fun mayChange(featureId: String): Boolean = isDemoProfile || capabilities.canChangeFeature(featureId)
    val mayChangeDevices = isDemoProfile || (capabilities.uciWrite("dhcp") && capabilities.uciWrite("firewall"))

    // M9 guest QR: shown under the guest network screen. Demo uses sample details, never the router.
    val footerFor: (String) -> (@Composable ColumnScope.() -> Unit)? = { id ->
        when (id) {
            "guest" -> { {
                GuestQrCard(load = { if (isDemoProfile) GuestQr.demo else GuestQr.load(reader) })
                GuestExpiryCard(load = { if (isDemoProfile) GuestQr.demo else GuestQr.load(reader) }, changes = deviceChanges)
            } }
            // Phase 2: scan and join another Wi-Fi network; the change goes through the confirm-and-undo core.
            "admin" -> { {
                ConfigBackupCard(if (isDemoProfile) null else reader, !isDemoProfile && capabilities.uciRead("system"), isDemoProfile)
            } }
            "extend" -> { {
                WifiScanCard(
                    reader = if (isDemoProfile) null else reader,
                    onPlan = { result ->
                        when (result) {
                            is PlanResult.Ready -> if (mayChange("extend")) {
                                deviceChanges?.request(result.plan)
                                null
                            } else "This login may not change the router."
                            is PlanResult.Invalid -> result.message
                        }
                    }
                )
            } }
            else -> null
        }
    }
    val routerPageAction: (@Composable (String, String) -> Unit)? = if (isDemoProfile) null else { label, featureId ->
        RouterPageButton(label, hasUntangled) { webUi ->
            nav = nav.openMore(MorePage.RouterPages(webUi, featureId))
        }
    }

    // Owner feedback 2026-11-18: lists reopen where they were left, and More shows the most used features.
    val moreScroll = rememberScrollState()
    val wifiScroll = rememberScrollState()
    val parentalScroll = rememberScrollState()
    val devicesListState = androidx.compose.foundation.lazy.rememberLazyListState()
    var deviceFilter by remember(reader) { mutableStateOf("") }
    var deviceKind by remember(reader) { mutableStateOf("All devices") }
    var deviceSort by remember(reader) { mutableStateOf(DeviceSort.TRAFFIC) }
    var collapsedNetworks by remember(reader) { mutableStateOf(emptySet<String>()) }
    var moreFilter by remember(reader) { mutableStateOf("") }
    LaunchedEffect(nav.morePage) {
        (nav.morePage as? MorePage.Feature)?.let { runCatching { container.featureUsage.record(it.id) } }
    }

    val c = SfTheme.colors
    val dark = c.bg.luminance() < 0.3f
    val bottomItems = MainTab.entries.map { tab ->
        BottomBarItem(
            label = tab.label,
            icon = when (tab) {
                MainTab.Home -> SfIcons.Home
                MainTab.Devices -> SfIcons.Devices
                MainTab.Wifi -> SfIcons.Wifi
                MainTab.AdGuard -> SfIcons.Shield
                MainTab.Parental -> SfIcons.Users
                MainTab.More -> SfIcons.More
            },
            accessibleLabel = if (tab == MainTab.Parental) "Parental controls" else null
        )
    }

    val subTitle: String? = when {
        nav.deviceMac != null -> "Device details"
        nav.wifiNetwork != null -> "Network details"
        nav.parentalMac != null -> "Parental controls"
        nav.familyProfileId != null -> "Family profile"
        nav.familyAssignment != null -> "Assign devices"
        nav.tab == MainTab.More -> when (val page = nav.morePage) {
            is MorePage.Feature -> Features.byId(page.id)?.title
            is MorePage.RouterPages -> page.webUi.label
            MorePage.Diagnostics -> "Diagnostics"
            MorePage.About -> "About"
            MorePage.Activity -> "Activity"
            MorePage.AllDevices -> "All routers"
            MorePage.Settings -> "Settings"
            null -> null
        }
        nav.tab == MainTab.Wifi -> nav.wifiPage?.let { Features.byId(it)?.title }
        nav.tab == MainTab.AdGuard -> AgPage.byId(nav.adGuardPage)?.title
        else -> null
    }

    val trafficHolder = holderFor(Features.byId("bandwidth")!!)
    val openFeature: (String) -> Unit = { id ->
        nav = when (id) {
            "parental" -> nav.openParental()
            "wireless" -> nav.select(MainTab.Wifi)
            "guest", "mesh", "extend" -> nav.openWifiFeature(id)
            else -> nav.openMore(MorePage.Feature(id))
        }
        if (nav.tab != MainTab.More) coroutineScope.launch { runCatching { container.featureUsage.record(id) } }
    }

    @Composable
    fun AdGuardTabContent() {
        val agPage = AgPage.byId(nav.adGuardPage)
        val logEntry = nav.queryLogEntry
        if (agPage != null) {
            val devices by devicesHolder.state.collectAsState()
            val holder = remember(agPage, adGuardCaller) {
                AgPageHolder(agPage, adGuardCaller, demo = isDemoProfile) { a, result ->
                    if (!isDemoProfile) {
                        container.activityStore.add(
                            ActivityEntry(System.currentTimeMillis(), currentProfile.name, "AdGuard: ${a.title}", result, emptyList())
                        )
                    }
                }
            }
            CompositionLocalProvider(LocalKnownDevices provides devices.deviceListOrNull()?.devices.orEmpty()) {
                AgPageScreen(holder, agPage, onSignIn = { nav = nav.back() })
            }
        } else if (logEntry != null) {
            AdGuardQueryLogScreen(
                repository = adGuardStateHolder.repository,
                caller = adGuardCaller,
                demo = isDemoProfile,
                entry = logEntry,
                onBack = { nav = nav.back() }
            )
        } else {
            val adGuardBase = remember(currentProfile, routerAddress) {
                currentProfile.copy(address = routerAddress).getAdGuardBaseUrl().toHttpUrlOrNull()
            }
            AdGuardScreen(
                stateHolder = adGuardStateHolder,
                savedPassword = if (isDemoProfile) null else adGuardSavedPassword,
                onOpenQueryLog = { entry -> nav = nav.openQueryLog(entry) },
                onOpenPage = { id -> nav = nav.openAdGuardPage(id) },
                showAddressForm = !isDemoProfile,
                initialHost = adGuardBase?.host ?: "",
                initialPort = adGuardBase?.port?.toString() ?: "",
                initialProtocol = adGuardBase?.scheme ?: "http",
                onSaveAddress = { host, port, protocol ->
                    when (val result = container.routerSettingsHandler.saveAdGuardAddress(
                        currentProfile.id, host, port, protocol
                    )) {
                        is AdGuardAddressSaveResult.Saved -> {
                            currentProfile = result.profile
                            null
                        }
                        is AdGuardAddressSaveResult.Error -> result.message
                    }
                }
            )
        }
    }

    @Composable
    fun MorePageContent(page: MorePage) {
        when (page) {
            is MorePage.Feature -> {
                val spec = Features.byId(page.id)
                if (spec?.id == "services") ServicesScreen(holderFor(spec), if (mayChange("services")) changes else null, signOut)
                else if (spec != null) {
                    val devices by devicesHolder.state.collectAsState()
                    val loaded = homeStateHolder.state.value
                    val model = when (loaded) {
                        is HomeUiState.Loaded -> loaded.dashboard.routerName
                        is HomeUiState.DemoData -> loaded.dashboard.routerName
                        else -> currentProfile.name
                    }
                    CompositionLocalProvider(LocalKnownDevices provides devices.deviceListOrNull()?.devices.orEmpty()) {
                        FeatureScreen(
                            spec, holderFor(spec), signOut, routerPageAction,
                            if (mayChange(spec.id)) deviceChanges else null,
                            onDevice = { nav = nav.openDevice(it) },
                            routerModel = model,
                            routerAddress = parsedUrl?.host.orEmpty(),
                            onLiveTraffic = { nav = nav.openMore(MorePage.Feature("live")) },
                            footer = footerFor(spec.id)
                        )
                    }
                }
            }
            is MorePage.RouterPages -> {
                var certMismatchFingerprint by remember(page, routerOrigin, webPin) { mutableStateOf<String?>(null) }
                val mismatch = certMismatchFingerprint
                if (mismatch != null) {
                    CertMismatchHardStopCard(
                        expectedFingerprint = webPin,
                        presentedFingerprint = mismatch,
                        onBack = {
                            certMismatchFingerprint = null
                            nav = nav.back()
                        },
                        onSignOut = signOut
                    )
                } else {
                    RouterWebView(
                        routerOrigin = routerOrigin,
                        pinnedFingerprint = webPin,
                        targetUrl = RouterWebPages.target(routerOrigin, page.webUi, page.featureId),
                        sessionToken = session.token,
                        onCertMismatch = { presented -> certMismatchFingerprint = presented },
                        onConnectionError = { isReconnecting = true }
                    )
                }
            }
            MorePage.About -> AboutScreen()
            MorePage.Activity -> ActivityScreen(container.activityStore)
            MorePage.AllDevices -> {
                if (isDemoProfile) DemoUnavailable(onLeaveDemo = signOut)
                else AllDevicesScreen(container.deviceCache)
            }
            MorePage.Diagnostics -> DiagnosticsScreen(run = { tool, host ->
                runDiagnostic(if (isDemoProfile) null else reader, tool, host)
            })
            MorePage.Settings -> SettingsScreen(
                profile = currentProfile,
                settingsHandler = container.routerSettingsHandler,
                onForgetRouter = {
                    coroutineScope.launch {
                        container.sessionManager.logout()
                        onSignOut()
                    }
                },
                onProfileUpdated = { updatedProfile ->
                    currentProfile = updatedProfile
                },
                probeCertificate = { container.sessionManager.probeCertificate(it) },
                securitySection = {
                    AppearanceSection(container.themeStore)
                    LockSettingsSection(
                        lock = container.appLock,
                        settings = container.lockSettings,
                        secrets = container.secretStore,
                        profileId = currentProfile.id
                    )
                    SectionBar("Alerts", SfHue.ORANGE)
                    AlertsSection(
                        container = container,
                        profile = currentProfile,
                        savedPassword = SavedPassword(
                            store = container.secretStore,
                            lock = container.appLock,
                            activity = shellContext as? FragmentActivity,
                            profileId = currentProfile.id,
                            kind = SecretKind.ROUTER,
                            usable = BiometricGate.canUnlock(shellContext)
                        )
                    )
                }
            )
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = c.bg,
        topBar = {
            ReviewRouterBar(
                name = currentProfile.name,
                modifier = Modifier.statusBarsPadding(),
                demo = isDemoProfile,
                dark = dark,
                onSwitch = switchRouter,
                onTheme = { coroutineScope.launch { container.themeStore.set(if (dark) ThemeChoice.LIGHT else ThemeChoice.DARK) } },
                menu = if (isDemoProfile) {
                    listOf(TopBarAction("Leave the demo", destructive = true, onClick = signOut))
                } else {
                    listOf(
                        TopBarAction("Switch router", onClick = switchRouter),
                        TopBarAction("Reconnect") { isReconnecting = true },
                        TopBarAction("Sign out", destructive = true, onClick = signOut)
                    )
                }
            )
        },
        bottomBar = {
            SfBottomBar(
                items = bottomItems,
                selectedIndex = nav.tab.ordinal,
                onSelect = { index -> nav = nav.select(MainTab.entries[index]) }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val route = nav
            if (subTitle != null) {
                val previous = route.history.lastOrNull()
                val origin = when {
                    previous?.wifiNetwork != null -> "network"
                    previous?.deviceMac != null -> "device"
                    previous?.familyProfileId != null -> "profile"
                    previous?.queryLogOpen == true -> "Query log"
                    else -> (previous?.tab ?: route.tab).label
                }
                PageLink("Back to $origin", { nav = nav.back() }, Modifier.padding(start = 16.dp, top = 4.dp), back = true)
            }
            if (route.deviceMac != null) {
                LoadStateContent(devicesHolder, signOut) { list, _ ->
                    val selected = list.devices.firstOrNull { it.mac == route.deviceMac }
                    if (selected == null) Note("This device is unavailable.", Modifier.padding(16.dp))
                    else {
                        val ifname = list.wifiClients.entries.singleOrNull { selected.mac in it.value }?.key
                        val wireless = holderFor(Features.byId("wireless")!!)
                        val wirelessState by wireless.state.collectAsState()
                        if (ifname != null && wirelessState is LoadState.Loading) PollVisible(wireless)
                        val wifi = when (val state = wirelessState) {
                            is LoadState.Loaded -> state.value
                            is LoadState.DemoData -> state.value
                            else -> null
                        }
                        val network = ifname?.let { name -> wifi?.wifiNetworks?.singleOrNull { it.key.ifname == name } }
                        DeviceDetailScreen(
                            selected, list, trafficHolder, holderFor(Features.byId("usage")!!),
                            if (mayChangeDevices) changes else null, isDemoProfile, parsedUrl?.host.orEmpty(),
                            onParental = { nav = nav.openParental(selected.mac) },
                            routerPageAction = { routerPageAction?.invoke("Open in the router pages", "devices") },
                            networkName = network?.name, onNetwork = network?.let { { nav = nav.openWifiNetwork(it.key) } },
                            quotaStore = if (isDemoProfile) null else com.shaazifi.app.feature.devices.QuotaStore(container.dataStore)
                        )
                    }
                }
            } else if (route.wifiNetwork != null) {
                val devices by devicesHolder.state.collectAsState()
                WifiNetworkScreen(
                    holderFor(Features.byId("wireless")!!),
                    route.wifiNetwork,
                    devices.deviceListOrNull(),
                    signOut,
                    if (mayChange("wireless")) deviceChanges else null,
                    onDevice = { nav = nav.openDevice(it) }
                )
            } else when (nav.tab) {
                MainTab.Home -> {
                    PollVisible(trafficHolder)
                    val devices by devicesHolder.state.collectAsState()
                    val trafficState by trafficHolder.state.collectAsState()
                    HomeScreen(
                        stateHolder = homeStateHolder,
                        routerHost = parsedUrl?.host ?: routerOrigin,
                        onOpenRouterTab = { nav = nav.select(MainTab.Devices) },
                        onSignInAgain = signOut,
                        onReconnect = { isReconnecting = true },
                        onOpenFeature = openFeature,
                        onOpenWifi = { key -> nav = if (key == null) nav.select(MainTab.Wifi) else nav.openWifiNetwork(key) },
                        onAllWifi = { nav = nav.select(MainTab.Wifi) },
                        connectedDevices = devices.deviceListOrNull()?.devices.orEmpty(),
                        liveTraffic = trafficState.featureViewOrNull(),
                        onDevice = { nav = nav.openDevice(it) }
                    )
                }
                MainTab.Devices -> {
                    PollVisible(trafficHolder)
                    val trafficState by trafficHolder.state.collectAsState()
                    DevicesScreen(
                        holder = devicesHolder,
                        onSignInAgain = signOut,
                        onOpenDevice = { nav = nav.openDevice(it.mac) },
                        filter = deviceFilter,
                        onFilterChange = { deviceFilter = it },
                        kind = deviceKind, onKindChange = { deviceKind = it },
                        sort = deviceSort, onSortChange = { deviceSort = it },
                        collapsedNetworks = collapsedNetworks, onToggleNetwork = { key ->
                            collapsedNetworks = if (key in collapsedNetworks) collapsedNetworks - key else collapsedNetworks + key
                        },
                        liveTraffic = trafficState.featureViewOrNull(),
                        listState = devicesListState
                    )
                }
                MainTab.Wifi -> {
                    val devices by devicesHolder.state.collectAsState()
                    val sub = nav.wifiPage?.let { Features.byId(it) }
                    if (sub != null) {
                        CompositionLocalProvider(LocalKnownDevices provides devices.deviceListOrNull()?.devices.orEmpty()) {
                            FeatureScreen(
                                sub, holderFor(sub), signOut, routerPageAction,
                                if (mayChange(sub.id)) deviceChanges else null,
                                footer = footerFor(sub.id)
                            )
                        }
                    } else {
                        val wireless = Features.byId("wireless")!!
                        WifiOverview(
                            wireless, holderFor(wireless), wifiScroll, devices.deviceListOrNull(), signOut,
                            onOpen = { nav = nav.openWifiNetwork(it) },
                            changes = if (mayChange("wireless")) deviceChanges else null
                        ) {
                            SectionTitle("Wireless tools")
                            SfList {
                                Features.all.filter { it.group == Features.WIFI && it.id != "wireless" }.forEachIndexed { i, spec ->
                                    if (i > 0) SfDivider()
                                    SfListRow(
                                        title = spec.title,
                                        subtitle = spec.listSubtitle,
                                        onClick = { nav = nav.openWifiFeature(spec.id) },
                                        icon = SfIcons.Wifi,
                                        hue = SfHue.TEAL,
                                        trailing = { Icon(SfIcons.ChevronRight, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp)) }
                                    )
                                }
                            }
                        }
                    }
                }
                MainTab.AdGuard -> AdGuardTabContent()
                MainTab.Parental -> ParentalTabContent(
                    container = container,
                    familyRouterId = currentProfile.id,
                    isDemoProfile = isDemoProfile,
                    devicesHolder = devicesHolder,
                    route = route,
                    nav = nav,
                    onNavChange = { nav = it },
                    parentalScroll = parentalScroll,
                    changes = changes,
                    signOut = signOut
                )
                MainTab.More -> when (val page = nav.morePage) {
                    is MorePage -> if (isDemoProfile && !DemoMode.pageAvailable(page)) {
                        DemoUnavailable(onLeaveDemo = signOut)
                    } else {
                        MorePageContent(page)
                    }
                    null -> {
                        val usageCounts by container.featureUsage.counts.collectAsState(initial = emptyMap())
                        val allExtras = listOfNotNull(
                            if (!isDemoProfile && hasUntangled) {
                                MorePage.RouterPages(RouterWebUi.Untangled) to ExtraEntry("Router pages", "UnTangled pages", "The router's UnTangled web interface") {
                                    nav = nav.openMore(MorePage.RouterPages(RouterWebUi.Untangled))
                                }
                            } else null,
                            (MorePage.RouterPages(RouterWebUi.Luci) to ExtraEntry("Router pages", "Advanced settings (LuCI)", "Every OpenWrt setting") {
                                nav = nav.openMore(MorePage.RouterPages(RouterWebUi.Luci))
                            }),
                            (MorePage.Diagnostics to ExtraEntry("Tools", "Diagnostics", "Ping, trace route and DNS lookup from the router") {
                                nav = nav.openMore(MorePage.Diagnostics)
                            }),
                            (MorePage.Settings to ExtraEntry("App", "Settings", "AdGuard address, security, forget this router") {
                                nav = nav.openMore(MorePage.Settings)
                            }),
                            (MorePage.Activity to ExtraEntry("App", "Activity", "Changes made from this app") {
                                nav = nav.openMore(MorePage.Activity)
                            }),
                            (MorePage.AllDevices to ExtraEntry("App", "Devices on all routers", "Search every saved router's devices") {
                                nav = nav.openMore(MorePage.AllDevices)
                            }),
                            (MorePage.About to ExtraEntry(
                                "App",
                                stringResource(R.string.app_about, stringResource(R.string.app_name)),
                                "Licence (GPL-3.0) and credits"
                            ) {
                                nav = nav.openMore(MorePage.About)
                            })
                        )
                        val visibleExtras = allExtras
                            .filter { (morePage, _) -> !isDemoProfile || DemoMode.pageAvailable(morePage) }
                            .map { (_, entry) -> entry }

                        AllFeaturesList(
                            onOpenFeature = openFeature,
                            isAllowed = { id -> capabilities.feature(id) },
                            frequent = FrequentFeatures.top(usageCounts, { id -> Features.byId(id) != null && capabilities.feature(id) }, 4),
                            scrollState = moreScroll,
                            filter = moreFilter,
                            onFilterChange = { moreFilter = it },
                            extras = visibleExtras
                        )
                    }
                }
            }
        }
    }
    ChangeHost(changes, demo = isDemoProfile)
}

@Composable
private fun ParentalTabContent(
    container: AppContainer,
    familyRouterId: String,
    isDemoProfile: Boolean,
    devicesHolder: LoadHolder<DeviceList>,
    route: ShellNavState,
    nav: ShellNavState,
    onNavChange: (ShellNavState) -> Unit,
    parentalScroll: androidx.compose.foundation.ScrollState,
    changes: ChangeController,
    signOut: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val familyStore = remember(container.dataStore) { FamilyDraftStore(container.dataStore) }
    val familyFlow = remember(familyStore, familyRouterId) { familyStore.profiles(familyRouterId) }
    val savedDrafts by familyFlow.collectAsState(initial = emptyList())
    var demoDrafts by remember(familyRouterId) { mutableStateOf(emptyList<FamilyDraft>()) }
    val drafts = if (isDemoProfile) demoDrafts else savedDrafts

    val devicesState by devicesHolder.state.collectAsState()
    val populatedProfiles = devicesState.deviceListOrNull()?.accessRules.orEmpty()
        .mapNotNull { rule -> rule.family?.takeIf { it.kind == "member" }?.profileId }
        .toSet()
    LaunchedEffect(familyRouterId, populatedProfiles, drafts) {
        val promoted = drafts.map { it.id }.toSet().intersect(populatedProfiles)
        if (promoted.isNotEmpty()) {
            if (isDemoProfile) demoDrafts = demoDrafts.filterNot { it.id in promoted }
            else familyStore.remove(familyRouterId, promoted)
        }
    }

    if (route.familyAssignment != null) {
        FamilyAssignmentScreen(
            devicesHolder, drafts, route.familyAssignment.profileId, changes, signOut,
            onAssigned = { if (nav == route) onNavChange(nav.back()) }
        )
    } else {
        ParentalScreen(
            devicesHolder, nav.parentalMac, nav.familyProfileId, drafts, parentalScroll, changes, signOut,
            onProfile = { onNavChange(nav.openFamilyProfile(it)) },
            onAssign = { onNavChange(nav.openFamilyAssignment(it)) },
            onSelect = { onNavChange(nav.openParental(it)) },
            onCreate = { draft ->
                coroutineScope.launch {
                    if (isDemoProfile) demoDrafts += draft else familyStore.add(familyRouterId, draft)
                    onNavChange(nav.openFamilyProfile(draft.id))
                }
            },
            onDiscard = { id ->
                coroutineScope.launch {
                    if (isDemoProfile) demoDrafts = demoDrafts.filterNot { it.id == id }
                    else familyStore.remove(familyRouterId, setOf(id))
                    onNavChange(nav.back())
                }
            }
        )
    }
}

private fun LoadState<DeviceList>.deviceListOrNull(): DeviceList? = when (this) {
    is LoadState.Loaded -> value
    is LoadState.DemoData -> value
    else -> null
}

private fun LoadState<FeatureView>.featureViewOrNull(): FeatureView? = when (this) {
    is LoadState.Loaded -> value
    is LoadState.DemoData -> value
    else -> null
}

/** Hard stop when the router WebView detects an unpinned/changed TLS certificate. */
@Composable
private fun CertMismatchHardStopCard(
    expectedFingerprint: String,
    presentedFingerprint: String,
    onBack: () -> Unit,
    onSignOut: () -> Unit
) {
    val c = SfTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SfCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 10.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Security certificate changed",
                    style = MaterialTheme.typography.titleMedium,
                    color = c.ink,
                    modifier = Modifier.weight(1f)
                )
                Pill("Stopped", tone = PillTone.Bad)
            }
            Text(
                "The router's web page showed a different certificate than the one you approved. " +
                    "Loading was stopped to protect your session.",
                style = SfText.sub,
                color = c.muted
            )
            Eyebrow("Approved certificate")
            RouterDisplay.fingerprintLines(expectedFingerprint).forEach {
                Text(it, style = SfText.mono, color = c.muted)
            }
            Eyebrow("Certificate shown now")
            RouterDisplay.fingerprintLines(presentedFingerprint).forEach {
                Text(it, style = SfText.mono, color = c.bad)
            }
            PrimaryButton("Back", onClick = onBack)
            GhostButton("Sign out", onClick = onSignOut)
        }
    }
}

/** Shown for pages that need a real router while in demo mode. */
@Composable
private fun DemoUnavailable(onLeaveDemo: () -> Unit) {
    val c = SfTheme.colors
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        SfCard(modifier = Modifier.fillMaxWidth(), verticalSpacing = 12.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Not in the demo", style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
                Pill("Demo", tone = PillTone.Off)
            }
            Text(
                "This page needs a real router. Leave the demo and add your router to use it.",
                style = SfText.sub,
                color = c.muted
            )
            GhostButton("Leave the demo", onClick = onLeaveDemo)
        }
    }
}
