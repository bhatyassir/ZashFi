package com.shaazifi.app.feature.web

import androidx.compose.ui.res.stringResource
import com.shaazifi.app.R

import com.shaazifi.app.feature.router.LoadState
import androidx.compose.runtime.CompositionLocalProvider
import com.shaazifi.app.feature.router.FrequentFeatures
import com.shaazifi.app.feature.activity.ActivityLog
import com.shaazifi.app.feature.activity.ActivityEntry
import com.shaazifi.app.feature.activity.CachedDevice
import com.shaazifi.app.feature.activity.ActivityScreen
import com.shaazifi.app.feature.activity.AllDevicesScreen
import com.shaazifi.app.feature.activity.AppearanceSection
import com.shaazifi.app.feature.router.Capabilities
import com.shaazifi.app.feature.router.PlanResult
import com.shaazifi.app.feature.router.WifiScanCard
import com.shaazifi.app.feature.about.AboutScreen
import com.shaazifi.app.core.adguard.HttpAdGuardCaller
import com.shaazifi.app.core.adguard.AdGuardCaller
import com.shaazifi.app.feature.adguard.manage.DemoAdGuardCaller
import com.shaazifi.app.feature.adguard.manage.AgPageScreen
import com.shaazifi.app.feature.adguard.manage.AgPageHolder
import com.shaazifi.app.feature.adguard.manage.AgPage
import com.shaazifi.app.feature.home.HomeUiState
import com.shaazifi.app.feature.alerts.AlertsSection
import com.shaazifi.app.feature.router.runDiagnostic
import com.shaazifi.app.feature.router.DiagnosticsScreen
import com.shaazifi.app.feature.guestqr.GuestQr
import com.shaazifi.app.feature.guestqr.GuestQrCard
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.isSystemInDarkTheme
import com.shaazifi.app.feature.quick.QuickAction
import com.shaazifi.app.feature.adguard.AdGuardUiState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.fragment.app.FragmentActivity
import com.shaazifi.app.core.session.BiometricGate
import com.shaazifi.app.core.session.SavedPassword
import com.shaazifi.app.core.session.SecretKind
import com.shaazifi.app.feature.lock.LockSettingsSection
import com.shaazifi.app.feature.demo.DemoMode
import com.shaazifi.app.feature.demo.DemoRouterChanges
import com.shaazifi.app.feature.home.DemoHomeRepository
import com.shaazifi.app.feature.devices.DemoDevices
import com.shaazifi.app.feature.devices.DeviceDetailScreen
import com.shaazifi.app.feature.devices.DevicesScreen
import com.shaazifi.app.feature.devices.DeviceSort
import com.shaazifi.app.feature.devices.FamilyDraft
import com.shaazifi.app.feature.devices.FamilyDraftStore
import com.shaazifi.app.feature.devices.FamilyAssignmentScreen
import com.shaazifi.app.feature.devices.ParentalScreen
import com.shaazifi.app.feature.devices.loadDevices
import com.shaazifi.app.feature.router.AllFeaturesList
import com.shaazifi.app.feature.router.ChangeController
import com.shaazifi.app.feature.router.ChangeHost
import com.shaazifi.app.feature.router.RouterWriter
import com.shaazifi.app.feature.router.SafeChangeRunner
import com.shaazifi.app.feature.router.ExtraEntry
import com.shaazifi.app.feature.router.FeatureContext
import com.shaazifi.app.feature.router.FeatureScreen
import com.shaazifi.app.feature.router.FeatureSpec
import com.shaazifi.app.feature.router.FeatureView
import com.shaazifi.app.feature.router.Features
import com.shaazifi.app.feature.router.LiveUsageMeter
import com.shaazifi.app.feature.router.LoadHolder
import com.shaazifi.app.feature.router.LoadStateContent
import com.shaazifi.app.feature.router.PollVisible
import com.shaazifi.app.feature.router.RouterReader
import com.shaazifi.app.feature.router.ServicesScreen
import com.shaazifi.app.feature.router.WifiNetworkScreen
import com.shaazifi.app.feature.router.WifiOverview
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shaazifi.app.AppContainer
import com.shaazifi.app.core.session.SessionState
import com.shaazifi.app.data.HttpAdGuardRepository
import com.shaazifi.app.data.RouterProfile
import com.shaazifi.app.data.getAdGuardBaseUrl
import com.shaazifi.app.feature.settings.AdGuardAddressSaveResult
import com.shaazifi.app.feature.adguard.AdGuardQueryLogScreen
import com.shaazifi.app.feature.adguard.AdGuardScreen
import com.shaazifi.app.feature.adguard.AdGuardStateHolder
import com.shaazifi.app.feature.adguard.DemoAdGuardRepository
import com.shaazifi.app.feature.home.HomeScreen
import com.shaazifi.app.feature.home.HomeStateHolder
import com.shaazifi.app.feature.home.UbusClientCaller
import com.shaazifi.app.feature.home.UbusHomeRepository
import com.shaazifi.app.feature.reconnect.ReconnectScreen
import com.shaazifi.app.feature.settings.SettingsScreen
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.statusBarsPadding
import com.shaazifi.app.ui.components.BottomBarItem
import com.shaazifi.app.ui.components.Eyebrow
import com.shaazifi.app.ui.components.GhostButton
import com.shaazifi.app.ui.components.Note
import com.shaazifi.app.ui.components.Pill
import com.shaazifi.app.ui.components.PrimaryButton
import com.shaazifi.app.ui.components.SfBottomBar
import com.shaazifi.app.ui.components.SfCard
import com.shaazifi.app.ui.components.SfDivider
import com.shaazifi.app.ui.components.SfList
import com.shaazifi.app.ui.components.SfListRow
import com.shaazifi.app.ui.components.PageLink
import com.shaazifi.app.ui.components.ReviewRouterBar
import com.shaazifi.app.ui.components.TopBarAction
import com.shaazifi.app.ui.icons.SfIcons
import com.shaazifi.app.ui.theme.PillTone
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
    onQuickActionUsed: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var nav by remember(profile.id, session.token) { mutableStateOf(ShellNavState()) }
    var isReconnecting by remember(profile.id, session.token) { mutableStateOf(false) }
    var currentProfile by remember(profile) { mutableStateOf(profile) }

    if (isReconnecting) {
        ReconnectScreen(
            // Wait on the address this session uses (the VPN one, with its own certificate, when signed in over VPN).
            profile = if (session.viaVpn) {
                currentProfile.copy(
                    address = session.endpoint,
                    certificateFingerprint = currentProfile.vpnCertificateFingerprint ?: currentProfile.certificateFingerprint
                )
            } else {
                currentProfile
            },
            onReconnected = { _ ->
                isReconnecting = false
            },
            onCancel = {
                isReconnecting = false
            }
        )
        return
    }

    // M9: the address this session reached (home, or VPN when home was unreachable).
    val routerAddress = session.endpoint
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
    val webPin = if (session.viaVpn) currentProfile.vpnCertificateFingerprint ?: profile.certificateFingerprint else profile.certificateFingerprint

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

    // AdGuard Home state holder and repository (v2)
    val adGuardStateHolder = remember(currentProfile, routerAddress) {
        if (DemoMode.isDemo(currentProfile)) {
            AdGuardStateHolder(DemoAdGuardRepository(), isDemo = true)
        } else {
            var stateHolderInstance: AdGuardStateHolder? = null
            val baseUrl = currentProfile.copy(address = routerAddress).getAdGuardBaseUrl()
            val repo = HttpAdGuardRepository(
                adGuardClient = container.adGuardClient,
                baseUrl = baseUrl,
                username = currentProfile.adguardUsername
            ) {
                stateHolderInstance?.getPassword()
            }
            val holder = AdGuardStateHolder(repo, isDemo = false)
            stateHolderInstance = holder
            holder
        }
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

    // M10e: feed the home-screen widget with what the app sees (never in demo; no secrets).
    val homeUiForWidget by homeStateHolder.state.collectAsState()
    LaunchedEffect(homeUiForWidget) {
        val d = (homeUiForWidget as? HomeUiState.Loaded)?.dashboard ?: return@LaunchedEffect
        if (DemoMode.isDemo(currentProfile)) return@LaunchedEffect
        runCatching {
            container.widgetStore.update(context, currentProfile.id, currentProfile.name, internetUp = d.internetUp, devicesOnline = d.clients?.total)
        }
    }

    // M9 tiles: open the shortcut's screen. "Pause AdGuard" also asks the usual confirmation
    // (nothing is sent until the owner confirms); guest Wi-Fi opens its screen with the switch.
    var pendingPause by remember { mutableStateOf(false) }
    LaunchedEffect(quickAction) {
        val q = quickAction ?: return@LaunchedEffect
        nav = q.startNav()
        pendingPause = q == QuickAction.ADGUARD_PAUSE
        onQuickActionUsed()
    }
    val adGuardUiState by adGuardStateHolder.state.collectAsState()
    LaunchedEffect(adGuardUiState) {
        val loaded = adGuardUiState as? AdGuardUiState.Loaded ?: return@LaunchedEffect
        if (DemoMode.isDemo(currentProfile)) return@LaunchedEffect
        runCatching {
            container.widgetStore.update(context, currentProfile.id, currentProfile.name, adguardOn = loaded.dashboard.status.protectionEnabled)
        }
    }
    LaunchedEffect(pendingPause, adGuardUiState) {
        val loaded = adGuardUiState as? AdGuardUiState.Loaded
        if (pendingPause && loaded != null) {
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

    BackHandler(enabled = nav.canGoBack) { nav = nav.back() }

    val isDemoProfile = DemoMode.isDemo(currentProfile)
    val familyRouterId = currentProfile.id
    val familyStore = remember(container.dataStore) { FamilyDraftStore(container.dataStore) }
    val familyFlow = remember(familyStore, familyRouterId) { familyStore.profiles(familyRouterId) }
    val savedDrafts by familyFlow.collectAsState(initial = emptyList())
    var demoDrafts by remember(familyRouterId) { mutableStateOf(emptyList<FamilyDraft>()) }
    val drafts = if (isDemoProfile) demoDrafts else savedDrafts
    val reader = remember(currentProfile.id, routerAddress, session.token) {
        RouterReader(UbusClientCaller(container.ubusClient, routerAddress, session.token))
    }

    val liveUsageMeter = remember(reader) { LiveUsageMeter() }
    val featureContext = FeatureContext(
        adGuardConfigured = currentProfile.adguardHost != null || currentProfile.adguardPort != null,
        routerIp = parsedUrl?.host ?: "",
        liveUsageMeter = liveUsageMeter
    )
    val featureHolders = remember(reader, isDemoProfile) { mutableMapOf<String, LoadHolder<FeatureView>>() }
    fun holderFor(spec: FeatureSpec): LoadHolder<FeatureView> = featureHolders.getOrPut(spec.id) {
        LoadHolder(
            load = { spec.load(reader, featureContext) },
            demo = if (isDemoProfile) spec.demo else null,
            refreshMs = spec.liveRefreshMs
        )
    }
    val devicesHolder = remember(reader, isDemoProfile) {
        LoadHolder(load = {
            loadDevices(reader).also { list ->
                // Phase 5: remember this router's device list for "Devices on all routers".
                runCatching { container.deviceCache.put(currentProfile.id, currentProfile.name, list.devices.map { CachedDevice(it.name, it.ip, it.mac, it.online) }) }
            }
        }, demo = if (isDemoProfile) DemoDevices else null)
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
            onDone = { plan, outcome -> container.activityStore.add(ActivityLog.entry(plan, outcome, currentProfile.name, System.currentTimeMillis())) }
        )
    }
    val changes = deviceChanges ?: remember(devicesHolder, featureHolders) {
        val demo = DemoRouterChanges(devicesHolder) { holderFor(Features.byId("services")!!) }
        ChangeController(runPlan = { demo.run(it) })
    }

    // Phase 3: what this login may do (rpcd ACL), read once. No answer = hide nothing.
    var capabilities by remember(reader) { mutableStateOf(Capabilities.UNKNOWN) }
    LaunchedEffect(reader) {
        if (!isDemoProfile) capabilities = reader.optional("session", "access")?.let { Capabilities.parse(it) } ?: Capabilities.UNKNOWN
    }

    // M9 guest QR: shown under the guest network screen. Demo uses sample details, never the router.
    val footerFor: (String) -> (@Composable ColumnScope.() -> Unit)? = { id ->
        when (id) {
            "guest" -> { { GuestQrCard(load = { if (isDemoProfile) GuestQr.demo else GuestQr.load(reader) }) } }
            // Phase 2: scan and join another Wi-Fi network; the change goes through the confirm-and-undo core.
            "extend" -> { {
                WifiScanCard(
                    reader = if (isDemoProfile) null else reader,
                    onPlan = { result ->
                        when (result) {
                            is PlanResult.Ready -> { deviceChanges?.request(result.plan); null }
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
    val usageCounts by container.featureUsage.counts.collectAsState(initial = emptyMap())
    LaunchedEffect(nav.morePage) {
        (nav.morePage as? MorePage.Feature)?.let { runCatching { container.featureUsage.record(it.id) } }
    }

    val c = SfTheme.colors
    val themeChoice by container.themeStore.choice.collectAsState(initial = ThemeChoice.SYSTEM)
    val dark = themeChoice.isDark(isSystemInDarkTheme())
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

    @Composable
    fun AdGuardTabContent() {
                            val agPage = AgPage.byId(nav.adGuardPage)
                            val logEntry = nav.queryLogEntry
                            if (agPage != null) {
                                val holder = remember(agPage, adGuardCaller) {
                                    AgPageHolder(agPage, adGuardCaller, demo = isDemoProfile) { a, result ->
                                        container.activityStore.add(ActivityEntry(System.currentTimeMillis(), currentProfile.name, "AdGuard: ${a.title}", result, emptyList()))
                                    }
                                }
                                AgPageScreen(holder, agPage, onSignIn = { nav = nav.back() })
                            } else if (logEntry != null) {
                                AdGuardQueryLogScreen(
                                    repository = adGuardStateHolder.repository,
                                    caller = adGuardCaller,
                                    demo = isDemoProfile,
                                    entry = logEntry,
                                    onBack = { nav = nav.back() }
                                )
                            } else {
                                val adGuardBase = remember(currentProfile) { currentProfile.copy(address = routerAddress).getAdGuardBaseUrl().toHttpUrlOrNull() }
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
                            if (spec?.id == "services") ServicesScreen(holderFor(spec), changes, signOut)
                            else if (spec != null) {
                                val loaded = homeStateHolder.state.value
                                val model = when (loaded) {
                                    is HomeUiState.Loaded -> loaded.dashboard.routerName
                                    is HomeUiState.DemoData -> loaded.dashboard.routerName
                                    else -> currentProfile.name
                                }
                                FeatureScreen(spec, holderFor(spec), signOut, routerPageAction, deviceChanges,
                                    onDevice = { nav = nav.openDevice(it) }, routerModel = model, routerAddress = parsedUrl?.host.orEmpty(),
                                    onLiveTraffic = { nav = nav.openMore(MorePage.Feature("live")) }, footer = footerFor(spec.id))
                            }
                        }
                        is MorePage.RouterPages -> RouterWebView(
                            routerOrigin = routerOrigin,
                            pinnedFingerprint = webPin,
                            targetUrl = RouterWebPages.target(routerOrigin, page.webUi, page.featureId),
                            sessionToken = session.token,
                            onConnectionError = { isReconnecting = true }
                        )
                        MorePage.About -> AboutScreen()
                        MorePage.Activity -> ActivityScreen(container.activityStore)
                        MorePage.AllDevices -> AllDevicesScreen(container.deviceCache)
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
                                com.shaazifi.app.ui.components.SectionBar("Alerts", com.shaazifi.app.ui.theme.SfHue.ORANGE)
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

    // Owner feedback 2026-11-18: change forms can offer the router's device list.
    val devicesState by devicesHolder.state.collectAsState()
    LaunchedEffect(devicesHolder) { if (devicesHolder.state.value is LoadState.Loading) runCatching { devicesHolder.refresh() } }
    val deviceList = when (val ds = devicesState) {
        is LoadState.Loaded -> ds.value
        is LoadState.DemoData -> ds.value
        else -> null
    }
    val populatedProfiles = deviceList?.accessRules.orEmpty()
        .mapNotNull { rule -> rule.family?.takeIf { it.kind == "member" }?.profileId }.toSet()
    LaunchedEffect(familyRouterId, populatedProfiles, drafts) {
        val promoted = drafts.map { it.id }.toSet().intersect(populatedProfiles)
        if (promoted.isNotEmpty()) {
            if (isDemoProfile) demoDrafts = demoDrafts.filterNot { it.id in promoted }
            else familyStore.remove(familyRouterId, promoted)
        }
    }
    val trafficHolder = holderFor(Features.byId("bandwidth")!!)
    val trafficState by trafficHolder.state.collectAsState()
    val liveTraffic = when (val state = trafficState) {
        is LoadState.Loaded -> state.value
        is LoadState.DemoData -> state.value
        else -> null
    }
    val openFeature: (String) -> Unit = { id ->
        nav = when (id) {
            "parental" -> nav.openParental()
            "wireless" -> nav.select(MainTab.Wifi)
            "guest", "mesh", "extend" -> nav.openWifiFeature(id)
            else -> nav.openMore(MorePage.Feature(id))
        }
        if (nav.tab != MainTab.More) coroutineScope.launch { runCatching { container.featureUsage.record(id) } }
    }
    CompositionLocalProvider(com.shaazifi.app.feature.router.LocalKnownDevices provides deviceList?.devices.orEmpty()) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = c.bg,
        topBar = {
            ReviewRouterBar(
                name = currentProfile.name,
                modifier = Modifier.statusBarsPadding(),
                demo = isDemoProfile,
                dark = dark,
                onSwitch = signOut,
                onTheme = { coroutineScope.launch { container.themeStore.set(if (dark) ThemeChoice.LIGHT else ThemeChoice.DARK) } },
                menu = if (isDemoProfile) {
                    listOf(TopBarAction("Leave the demo", destructive = true, onClick = signOut))
                } else {
                    listOf(
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
                            changes, isDemoProfile, parsedUrl?.host.orEmpty(),
                            onParental = { nav = nav.openParental(selected.mac) },
                            routerPageAction = { routerPageAction?.invoke("Open in the router pages", "devices") },
                            networkName = network?.name, onNetwork = network?.let { { nav = nav.openWifiNetwork(it.key) } }
                        )
                    }
                }
            } else if (route.wifiNetwork != null) {
                WifiNetworkScreen(holderFor(Features.byId("wireless")!!), route.wifiNetwork, deviceList, signOut, deviceChanges,
                    onDevice = { nav = nav.openDevice(it) })
            } else when (nav.tab) {
                MainTab.Home -> {
                    PollVisible(trafficHolder)
                    // Native Home dashboard (read-only)
                    HomeScreen(
                        stateHolder = homeStateHolder,
                        routerHost = parsedUrl?.host ?: routerOrigin,
                        onOpenRouterTab = { nav = nav.select(MainTab.Devices) },
                        onSignInAgain = signOut,
                        onReconnect = { isReconnecting = true },
                        onOpenFeature = openFeature,
                        onOpenWifi = { key -> nav = if (key == null) nav.select(MainTab.Wifi) else nav.openWifiNetwork(key) },
                        onAllWifi = { nav = nav.select(MainTab.Wifi) },
                        connectedDevices = deviceList?.devices.orEmpty(), liveTraffic = liveTraffic,
                        onDevice = { nav = nav.openDevice(it) }
                    )
                }
                MainTab.Devices -> {
                    PollVisible(trafficHolder)
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
                    liveTraffic = liveTraffic,
                    listState = devicesListState
                    )
                }
                MainTab.Wifi -> {
                    val sub = nav.wifiPage?.let { Features.byId(it) }
                    if (sub != null) {
                        FeatureScreen(sub, holderFor(sub), signOut, routerPageAction, deviceChanges,
                            footer = footerFor(sub.id))
                    } else {
                        val wireless = Features.byId("wireless")!!
                        WifiOverview(wireless, holderFor(wireless), wifiScroll, deviceList, signOut,
                            onOpen = { nav = nav.openWifiNetwork(it) }, changes = deviceChanges) {
                            com.shaazifi.app.ui.components.SectionTitle("Wireless tools")
                            SfList {
                                Features.all.filter { it.group == Features.WIFI && it.id != "wireless" }.forEachIndexed { i, spec ->
                                    if (i > 0) SfDivider()
                                    SfListRow(
                                        title = spec.title,
                                        subtitle = spec.listSubtitle,
                                        onClick = { nav = nav.openWifiFeature(spec.id) },
                                        icon = SfIcons.Wifi,
                                        hue = com.shaazifi.app.ui.theme.SfHue.TEAL,
                                        trailing = { Icon(SfIcons.ChevronRight, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp)) }
                                    )
                                }
                            }
                        }
                    }
                }
                MainTab.AdGuard -> AdGuardTabContent()
                MainTab.Parental -> if (route.familyAssignment != null) {
                    FamilyAssignmentScreen(
                        devicesHolder, drafts, route.familyAssignment.profileId, changes, signOut,
                        onAssigned = { if (nav == route) nav = nav.back() }
                    )
                } else ParentalScreen(
                    devicesHolder, nav.parentalMac, nav.familyProfileId, drafts, parentalScroll, changes, signOut,
                    onProfile = { nav = nav.openFamilyProfile(it) },
                    onAssign = { nav = nav.openFamilyAssignment(it) },
                    onSelect = { nav = nav.openParental(it) },
                    onCreate = { draft ->
                        coroutineScope.launch {
                            if (isDemoProfile) demoDrafts += draft else familyStore.add(familyRouterId, draft)
                            nav = nav.openFamilyProfile(draft.id)
                        }
                    },
                    onDiscard = { id ->
                        coroutineScope.launch {
                            if (isDemoProfile) demoDrafts = demoDrafts.filterNot { it.id == id }
                            else familyStore.remove(familyRouterId, setOf(id))
                            nav = nav.back()
                        }
                    }
                )
                MainTab.More -> when (val page = nav.morePage) {
                    is MorePage -> if (isDemoProfile && !DemoMode.pageAvailable(page)) {
                        DemoUnavailable(onLeaveDemo = signOut)
                    } else {
                        MorePageContent(page)
                    }
                    null -> AllFeaturesList(
                        onOpenFeature = openFeature,
                        isAllowed = { id -> capabilities.feature(id) },
                        frequent = FrequentFeatures.top(usageCounts, { id -> Features.byId(id) != null && capabilities.feature(id) }, 4),
                        scrollState = moreScroll,
                        filter = moreFilter,
                        onFilterChange = { moreFilter = it },
                        extras = listOfNotNull(
                            if (hasUntangled) ExtraEntry("Router pages", "UnTangled pages", "The router's UnTangled web interface") {
                                nav = nav.openMore(MorePage.RouterPages(RouterWebUi.Untangled))
                            } else null,
                            ExtraEntry("Router pages", "Advanced settings (LuCI)", "Every OpenWrt setting") {
                                nav = nav.openMore(MorePage.RouterPages(RouterWebUi.Luci))
                            },
                            ExtraEntry("Tools", "Diagnostics", "Ping, trace route and DNS lookup from the router") { nav = nav.openMore(MorePage.Diagnostics) },
                            ExtraEntry("App", "Settings", "AdGuard address, security, forget this router") { nav = nav.openMore(MorePage.Settings) },
                            ExtraEntry("App", "Activity", "Changes made from this app") { nav = nav.openMore(MorePage.Activity) },
                            ExtraEntry("App", "Devices on all routers", "Search every saved router's devices") { nav = nav.openMore(MorePage.AllDevices) },
                            ExtraEntry("App", stringResource(R.string.app_about, stringResource(R.string.app_name)),
                                "Licence (GPL-3.0) and credits") { nav = nav.openMore(MorePage.About) }
                        )
                    )
                }
            }
        }
    }
    ChangeHost(changes, demo = isDemoProfile)
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
