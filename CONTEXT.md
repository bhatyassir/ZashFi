# CONTEXT.md — ZashFi

Last updated: 2026-11-27

## 1. What This Project Is

**ZashFi** (`com.shaazifi.app`, upstream clone of `https://github.com/bhatyassir/ZashFi`) is a native Android (Kotlin + Jetpack Compose) management app for **OpenWrt** routers (via `ubus` JSON-RPC) and **AdGuard Home** (via its HTTP REST API), with integrated router thermal monitoring.

The app is designed for LAN-only use. Connections are restricted to RFC1918 ranges, Tailscale's `100.64.0.0/10`, loopback and local IPv6; public IPs and public hostnames are refused by `AddressGuard`. Remote access is expected to happen through a private VPN address, never through port forwarding or a public domain.

## 2. Toolchain & Build Environment

- **JDK:** Temurin JDK 21 (`app/build.gradle.kts` sets `jvmToolchain(21)`).
- **Android SDK:** platform 36, build-tools 36.0.0 and 35.0.0.
- **Gradle / Kotlin / AGP:** AGP 8.13.2, Kotlin 2.4.21, `compileSdk = 36`, `minSdk = 26`, `targetSdk = 36`.
- **Verification command (PowerShell):**
  ```powershell
  $env:JAVA_HOME = (Get-ChildItem "C:\Program Files\Eclipse Adoptium\jdk-21*" | Select-Object -First 1 -ExpandProperty FullName)
  $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
  $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
  .\gradlew.bat testDebugUnitTest assembleDebug assembleBeta --console=plain
  ```
- **Helper script:** `tools/build_check.bat`.

## 3. Architecture & Code Map

- `AppContainer.kt` — manual dependency injection for `TrustStore`, `WifiBinder`, `HttpFactory`, `UbusClient`, `AdGuardClient`, `RouterReader`, repositories and state holders.
- `core/net/` — `TrustStore.kt` (DataStore-persisted certificate pinning), `WifiBinder.kt` (Wi-Fi socket binding + IPv6 ULA/link-local), `HttpFactory.kt`, `AddressGuard.kt` (SSRF/LAN-only validation).
- `core/ubus/` — `UbusClient.kt`, `UbusError.kt` (JSON-RPC, cancellable calls, session refresh, subpath preservation).
- `core/adguard/` — `AdGuardApi.kt`, `AdGuardClient.kt`, `AdGuardError.kt` (allow-listed REST calls, UTF-8, legacy fallbacks).
- `core/session/`, `feature/routers/`, `feature/settings/` — app lock, biometric gate, Keystore secrets, session manager, profiles, sign-in, reconnect.
- `feature/home/` — home dashboard, parsers, repository/state holder, bandwidth meter and picture view, local history stores.
- `feature/router/` — `RouterReader`, feature specs/mappers/screens, safe change runner, diagnostics, thermal and status panels, cron/Wi-Fi extras.
- `feature/devices/`, `feature/alerts/`, `feature/guestqr/`, `feature/quick/`, `feature/widget/` — devices and quotas, alerts, guest QR and expiry, quick tiles, widget.
- `feature/adguard/` — dashboard, query log with filters/export/inspector, nine management pages including bulk DNS rewrites.
- `feature/web/` — authenticated shell, navigation, secure WebView for full LuCI/UnTangled access.
- `docs/router/shaazifi.json` and `docs/router/README.md` — rpcd ACL definitions and router setup guide.

## 4. Router Thermal Monitoring

Thermal sensors are read from `/sys/class/hwmon/hwmon*/temp*_input|label|max|crit` and `/sys/class/thermal/thermal_zone*/type|temp` through ubus `file read/list`. Because rpcd resolves symlinks before applying the ACL, the ACL contains both `/sys/class/*` and resolved `/sys/devices/*` patterns. Displayed on Home and System health, fed to the high-temperature alert, and recorded locally for 30-minute trend detection.

## 5. Improvement Workstreams

All eight planned workstreams (WS1–WS8) were implemented locally: build/manifest/ProGuard, core net/RPC, auth/session/settings, thermals and home, router mutations, devices/alerts/widget, AdGuard UI/export, and UI/WebView/tests. See the commit history for details.

## 6. Test Status

`.\gradlew.bat testDebugUnitTest assembleDebug assembleBeta --console=plain` → **BUILD SUCCESSFUL**, **547 tests, 0 failures, 0 errors, 0 skipped**.

Ten previously failing tests were fixed: JUnit void-return signatures, redirect SSRF validation, cancellable ubus calls, CSV control-character sanitization, deterministic live-log polling, Windows DataStore test isolation, navigation history expectations, and non-LAN access-rule parsing.

## 7. LuCI Parity Work

- Corrected all deep links so they point at LuCI pages present on a stock OpenWrt install; removed links to optional LuCI apps that may not exist.
- Every feature screen offers an "Open in LuCI" fallback. Change forms are gated by `Capabilities.canChangeFeature()`.
- Added read-only panels: Processes, live routing table, nftables rules, channel analysis, per-radio Wi-Fi clients, port traffic, interfaces, NAT rules, IP sets, custom-rules notice, Tailscale peers, cron jobs, dnsmasq DNS settings, nlbwmon config, AdGuard package config.
- Added a phone-local settings snapshot export (restore deliberately stays in LuCI because it can change the LAN address), guest Wi-Fi creation, and narrowly scoped UCI writes for WPS/WDS, isolation, MAC filtering and guest VLAN.
- `RouterAclSyncTest` keeps `docs/router/shaazifi.json` in step with every read/write the app performs.

## 8. Companion Features

1. **Guest QR with expiry** — whitelisted durations, confirmable one-tap disable plan.
2. **Quick tile actions** — Pause AdGuard and Night LEDs request real confirmed changes.
3. **DNS rewrite bulk editor** — import/export with a preview and confirmation.
4. **AdGuard package config** — read-only panel for `uci adguardhome`.
5. **MAC filtering and WPS/WDS** — read-only panel plus narrowly validated change plans.
6. **Client isolation / guest VLAN** — validated plans and forms.
7. **Per-device quotas** — phone-local daily limits with 80% warning / 100% over.
8. **Internet downtime timeline** — up/down events with outage summary and 7-day uptime.
9. **Cron/LED schedule editor** — strict two-line LED schedule that preserves other jobs.
10. **Thermal history and trends** — per-sensor samples, °C/hour trend, dangerous-rise detection.
11. **MAC filtering device picker** — multi-select devices from the router's known-device list, with manual entry still available.

Firmware flashing, package installation, free-form UCI editing, custom firewall rule text, SSH keys, VPN private keys, theme/language and raw ubus remain LuCI-only, reachable through the app's secure WebView.
