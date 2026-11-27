# AGENTS.md — Working Rules for AI Agents on ZashFi

Read this file and `CONTEXT.md` before making changes in the project root.

## 1. Build & Test Environment

- **Use JDK 21 and the Android SDK installed locally** before running Gradle. On Windows:
  ```powershell
  $env:JAVA_HOME = (Get-ChildItem "C:\Program Files\Eclipse Adoptium\jdk-21*" | Select-Object -First 1 -ExpandProperty FullName)
  $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
  $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
  ```
  (`app/build.gradle.kts` sets `jvmToolchain(21)`.)
- **Run Gradle verification in one command:** `.\gradlew.bat testDebugUnitTest assembleDebug assembleBeta --console=plain`. Never report tests as passing unless `testDebugUnitTest` exited 0 with 0 failures after your final edit.
- **Avoid running multiple concurrent Gradle commands on Windows** — `gradle.properties` uses `-Xmx3072m` + parallel workers; concurrent invocations contend on `.gradle` locks and `DataStore` temp files.

## 2. Critical Technical Pitfalls (Observed in This Repo)

1. **Kotlin 2.4.21 + AGP 8.13.2 D8 non-local return bug:**
   Never use `return@key` (or non-local returns) inside `@Composable` inline `key(...) { ... }` blocks (e.g., in `FeatureScreens.kt`). D8 fails with `$$$$$NON_LOCAL_RETURN$$$$$`. Use early `if (...) { ... } else { ... }` instead.
2. **OkHttp Redirect SSRF Protection (`HttpFactory.kt` + `AddressGuard.kt`):**
   `AddressGuardInterceptor` MUST stay wired via `.addNetworkInterceptor(...)` (or an equivalent post-redirect hook), NOT `.addInterceptor(...)` alone. OkHttp's `RetryAndFollowUpInterceptor` resolves redirect targets between app and network interceptors; moving `AddressGuardInterceptor` to `.addInterceptor(...)` lets HTTP redirects to public IPs slip through. The current equivalent hook is: `HttpFactory` sets `followRedirects(false)`/`followSslRedirects(false)` and the single app `AddressGuardInterceptor` loops over `Location` headers, validating each target before its own second `chain.proceed(...)`. Do not re-enable OkHttp automatic redirects without restoring the network interceptor.
3. **Windows `DataStore` Unit Tests (`AlertRulesTest`, `LocalNetworkAccessTest`):**
   AndroidX `DataStore` fails on Windows (`IOException: Unable to rename ...preferences_pb.tmp`) when multiple instances target the same file or a previous instance wasn't closed/scoped uniquely per test. Use an injected in-memory/test `DataStore` rather than sharing a singleton path across tests (see `com.shaazifi.app.test.InMemoryPreferencesDataStore`).
4. **JUnit 4 `@Test` + `runTest`:**
   `= runTest { ... }` can infer a non-`Unit` return type and trigger JUnit4 `InvalidTestClassError: Method ... should be void`. Always declare `: Unit = runTest { ... }` or use block bodies `{ runTest { ... } }`.
5. **OpenWrt 25.x Compatibility (`apk` vs `opkg`):**
   Some OpenWrt 25.x builds use `/usr/bin/apk` rather than `opkg`. Keep package queries compatible with both (`RouterReader.kt`).
6. **`rpcd` ACL Sync (`docs/router/shaazifi.json`):**
   Every new `ubus` call added in `RouterReader.kt` or `RouterChanges.kt` (for example thermal sysfs reads or cron writes) MUST have matching read/write permissions in `docs/router/shaazifi.json`. `RouterAclSyncTest` checks this automatically. Note that `rpcd` resolves symlinks before applying the ACL, so resolved `/sys/devices/*` patterns are needed in addition to `/sys/class/*`.

## 3. Live Router & Network Safety Rules

- **Do not run ADB or live network tests against a real router's mutating endpoints unless the owner explicitly asks for that exact change.**
- **No hardcoded credentials or secrets** in code, tests, or committed config.
- **Keep `AddressGuard` strict:** never weaken private/LAN-only IP guards (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `100.64.0.0/10` Tailscale CGNAT, ULA/link-local IPv6). Remote access must go through a private VPN address; public IPs and hostnames are refused by design.
