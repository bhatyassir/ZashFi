# ShaaziFi Android UI implementation plan

The owner approved the web design on 25 November 2026 with two final changes. Device details put **Pause internet** or **Resume internet** above traffic. Parental Controls opens a list of devices and lets the owner choose one before showing its controls. The [updated prototype](shaazifi-review/dist/index.html) is the interaction reference. Implementation will use native Kotlin and Compose.

Each screen consumes a typed UI state. Router replies stay in the existing reader and parsing layer. The work uses the current Compose components, demo data and router permissions.

## Screen behavior

| Area | Approved behavior |
| --- | --- |
| Bottom navigation | Home, Devices, Wi-Fi, AdGuard, Parental, More. The short Parental label has the accessible name Parental Controls. |
| Home | The traffic card opens per-device live traffic. Each Wi-Fi card opens that specific network's settings and clients. Back returns to Home. |
| Wi-Fi | The bottom tab opens the full wireless overview. Opening a network here gives the same details with Back to Wi-Fi. |
| Devices | The list opens a full device detail screen. The header has the current pause or resume action. The traffic card shows live rates and a separately labeled cumulative total when the router supplies one. |
| Parental Controls | A searchable list shows known devices, online first, with their status and rule counts. Selecting a device opens its pause or resume action and its schedules. The shortcut in device details opens controls for that same device. |
| Services | A running service opens a dialog with Stop and Restart. A stopped service has Start. The X dismisses the dialog. For a protected service, the native Stop button is disabled with a reason. |
| AdGuard and More | Keep the approved visual hierarchy, protection and query-log entry points, searchable feature list, frequent shortcuts, and the existing routes into tools and settings. |

## Phase 1 — Navigation, Home and Wi-Fi

- Add Parental to `MainTab` and adapt `SfBottomBar` for six items. Apply the preview's active-tab styling with existing theme colors and icons. Check touch targets, system insets and larger text at narrow widths.
- Extend `ShellNavState` and its Back behavior with destinations for individual networks, device details and device-specific parental controls. Keep the originating tab and restore the prior scroll state. Scope selections and history to the active router session.
- Update `HomeScreen` and `HomeModels` so each Wi-Fi card passes a stable network identity. Prefer the configured interface section and radio. Use a validated interface name when the router supplies that identity instead. Handle renamed and duplicate SSIDs and multiple networks on the same band.
- Build the native network detail from the existing Wi-Fi status/settings parsers and read path. Use per-access-point memberships from the already supported hostapd and iwinfo reads when known. Keep band, channel, security, width and power conditional on actual data. Show unavailable membership when the router cannot identify that network's clients. Reuse existing Wi-Fi change forms and their reconnect flow.
- Keep the Home traffic graph, health and device links within the new layout. Use the prototype's hierarchy and Compose theme tokens for light and dark modes.

**Acceptance check** — Open two networks from Home and from Wi-Fi, including a fixture with the same SSID on two interfaces. Each path shows the right clients and settings and Back returns to its origin. Verify wide and narrow phone layouts and switching routers from a detail screen.

## Phase 2 — Device details and parental controls

- Reuse the router-scoped `devicesHolder` and `Device` model in `AuthenticatedRouterShell`. Lift the existing `DevicesScreen` device-sheet selection into the native detail destination. Reuse the app's rename, fixed-address and Wake actions in the secondary controls. Present the pause or resume button at the top, with the current state visible.
- Render parental rules from typed records keyed by canonical device MAC and rule section ID. Reuse the existing firewall parsing and `FeatureChanges` builders. Use the same device list in both features. Include known offline devices for scheduling and keep search and scroll position on Back. A row or shortcut enters the selected device directly. The schedule form receives that device's identity and displays it without reopening a picker.
- Give both screens the same immediate pause/resume state. Recognize previously saved immediate rules created by Devices and Parental Controls, update existing app-owned rules instead of duplicating them, and keep time-based rules separate. Show when a schedule still applies after resuming a manual pause. Use the selected MAC rather than its display name to target changes. Preserve the router clock semantics, and verify overnight rules before promising their behavior on the owner's router.
- Route all live changes through `ChangeController`, `SafeChangeRunner` and `RouterWriter`. Use the existing confirmation and rollback flow, refresh device and rule state after success, and surface read-only or denied states clearly.

**Acceptance check** — Pause on device details and see Resume on that device in Parental. Cancel a confirmation without changing any state. Add and remove schedules for one of two similarly named devices. Verify the other device's rules and status stay intact.

## Phase 3 — Device traffic and cumulative totals

- Connect device detail and the existing live-usage page to the session-scoped bandwidth sampling path behind `RouterReader`, `Features` and `LiveUsageMeter`. Share readings when both destinations use the same router. Keep sampling tied to a visible view and reset history on router or session changes. Retain real samples for the 1-minute and 5-minute views and show gaps or a warm-up state when there is insufficient history.
- Preserve the existing split, combined-only and unavailable states. Use the selected device's data for its detail graph and label combined-only readings clearly. Separate the cumulative total from the graph's range controls.
- Reuse the read-only `nlbw` command already allowed by `RouterReader` and used by Usage history. Extract the byte parsing from `FeatureMappers.usage` for both consumers. Aggregate valid received and sent bytes across rows for the normalized MAC, including IPv4 and IPv6. Preserve a measured zero and show “Usage data unavailable” for missing counters, an unsupported package, an unmatched device or a denied read.
- Label the real total **Current accounting period** when using the active `nlbwmon` database. The demo's “Since router start” is sample copy. The router's accounting period can be configured independently of uptime. Display exact dates only when the verified source provides them. The owner's router's package availability, permissions and actual counters still need checking. [nlbwmon documentation](https://github.com/jow-/nlbwmon/blob/master/README.md) · [OpenWrt package configuration](https://github.com/openwrt/packages/blob/master/net/nlbwmon/files/nlbwmon.config)

**Acceptance check** — Compare a real device's total to the router's matching accounting period when available. Test dual-stack totals, offline devices with recorded usage, zero versus unavailable, the combined-rate fallback, and router switching with both chart histories populated.

## Phase 4 — Services and polish

- Present service details and actions in a native dialog using `FeatureMappers.services` and `Phase4.serviceAction`. Use the existing service status fields and permission checks. Keep the current protection against stopping essential services such as the web server, RPC, network, DNS/DHCP and firewall. Service start at boot and status unknown remain separate from current running status.
- Reuse the current confirmed, one-shot operation path for service actions. If restarting briefly interrupts access, re-read service state after reconnecting. A failed request or a lost connection does not establish that the service changed.
- Match the approved spacing and hierarchy on AdGuard, More and their existing detail screens. Keep the same setup, query log, permissions, feature availability and color themes. Demo fixtures supply the screen data without issuing router calls.

**Acceptance check** — Confirm the X, Stop, Restart and Start flows with fake data. Check protected services, permissions, unknown status, successful refresh and the brief-disconnect case. Use device demo mode to compare all six tabs and their main drill-downs with the web prototype.

## Delivery checks

- Extend the existing navigation, Home parser, feature-change, bandwidth and demo tests where the phases change behavior. Add focused tests for exact Wi-Fi identity, pause/schedule separation, totals and service protections.
- Deliver all four phases in one build on `codex/shaazifi-v2` as requested by the owner. Run `gradlew.bat assembleDebug`, `gradlew.bat test` and `python tools/ai_continuity_check.py` before delivery.
- Install the debug APK as **ShaaziFi V2** (`com.shaazifi.app.v2.debug`, version code 2). Verify that the original app's package and V2 are both present on the phone.
- On the Galaxy S25 Ultra, check the responsive layout, Back, saved sign-in, toolbar and bottom bar. The owner performs any real-router change checks. Keep the normal permission and confirmation flow and record which behaviors were verified on the phone and router.
