# Google Play listing (M10f, documents only)

Draft text and answers for the Play Console. Nothing here has been submitted. The owner decides,
edits and submits. Limits and rules are from Google's Play Console Help (read 2026-11-17):
answer 9859152 (store listing), 9866151 (graphic assets), 10787469 (Data safety).

## 1. Before publishing (blocking)

1. **Real router address in git history.** The test router's real address and certificate
   fingerprint are in about 23 committed files (ai/PROJECT.md, "Open issue"). Scrub before the
   repository or any build leaves this computer. The owner decides how.
2. **Signing key.** Only the owner has it. Release builds are signed by the owner.
3. **Privacy policy URL.** Google requires one for every app, even with no data collected. Draft:
   `docs/play/privacy-policy.md`. It must be hosted at a public web address the owner controls.
4. **Contact email** for the listing: the owner's choice (not written here).
5. **targetSdk.** The app targets 36 (Google Play's requirement for new apps; ai/PROJECT.md).
6. **Names of other products** (OpenWrt, AdGuard Home, Tailscale, UnTangled) are used only to say
   what the app works with. Whether that wording meets each project's trademark rules is
   UNVERIFIED; the owner checks before publishing.

## 2. Store text

**App name** (30 max): `ShaaziFi: Router Control` (24)

**Short description** (80 max):

> Run your OpenWrt router from your phone: devices, Wi-Fi, ad blocking and more. (78)

**Full description** (4,000 max; about 1,900 used):

> ShaaziFi is a phone app for routers running OpenWrt with the UnTangled web interface. It talks
> only to your own router, on your home Wi-Fi or your VPN, and never to any server of ours.
>
> See your network at a glance
> • Internet status, speed, connected devices, processor and memory on one screen
> • Every device with its name and address; give it a fixed address, a name, or block it
> • System health, services list, system log and a quick network check
>
> Change settings safely
> • Wi-Fi name and password, guest Wi-Fi, port forwarding, parental rules, firewall, DDNS,
>   Smart Queue, VPN tunnels and more
> • Every change is shown to you first. The router undoes it by itself if the app loses the
>   connection, so a wrong setting cannot lock you out
>
> Ad blocking with AdGuard Home
> • Protection on or off, pause for 10 minutes, Safe Browsing, parental filter, query log
>
> Made for your phone
> • Sign in with your fingerprint (the password is kept encrypted in the phone's secure hardware)
> • App lock, Quick Settings tiles, a home-screen widget, guest Wi-Fi QR code
> • Optional alerts when a new device joins or the internet goes down
> • Works away from home through your VPN (for example Tailscale)
>
> Private by design
> • No account, no ads, no analytics, no tracking
> • The app only connects to private network addresses: your router and your VPN
> • Each router is checked by its security certificate every time
> • Nothing is copied to cloud backups
>
> Try it without a router
> • "Try the demo" on the first screen shows every screen with sample data
>
> Requirements: a router running OpenWrt with UnTangled installed. Some screens need packages
> on the router (for example AdGuard Home, DDNS or Smart Queue); the app says when one is missing.

Notes on the text:
- No firmware or backup claims (removed from the app, D-20261117-004C63FB).
- "Never to any server of ours" is true of the code today: the app has no analytics or
  remote server, and AddressGuard refuses public addresses. Keep it true.

## 3. Graphics (owner makes these)

| Asset | Requirement (answer 9866151) | Suggested content |
|---|---|---|
| App icon | 512 x 512 px, 32-bit PNG with alpha, max 1024 KB | the app's launcher icon, enlarged |
| Feature graphic | 1024 x 500 px, JPEG or 24-bit PNG (no alpha) | name + one phone showing Home |
| Phone screenshots | 2 to 8; JPEG or 24-bit PNG; sides 320 to 3840 px; 9:16 recommended | see list |

Screenshot list (take them **in demo mode** so no real names, addresses or MACs appear):
1. Home (demo)
2. Devices list
3. Wi-Fi (one card per band)
4. A change confirmation ("Change the Wi-Fi network?")
5. AdGuard Home
6. More > All features
7. Settings > App lock and saved passwords
8. Guest Wi-Fi QR (demo network)

## 4. App access (for Google's reviewers)

Reviewers have no OpenWrt router. Suggested note:

> No account is needed. To see every screen without a router, open the app and tap
> "Try the demo" on the first screen. Demo mode shows sample data and never connects to any
> network. Real use needs the owner's own OpenWrt router on the same Wi-Fi or VPN.

## 5. Data safety form (draft answers; the owner confirms)

What the app does with data, from the code:

| Data | Where it goes | Kept where |
|---|---|---|
| Router password, AdGuard password | sent only to the owner's router / AdGuard Home | optional: encrypted on the phone (Keystore) |
| Router address, certificate fingerprint, VPN address | used to connect to the owner's router | phone (app storage) |
| Device names, MAC and IP addresses on the home network | read from the owner's router, shown on screen | alerts keep a list of known MACs on the phone; widget keeps only counts |
| Analytics, crash reports, ads, location, contacts | none | none |

- Nothing is sent to the developer or any third party. There is no server.
- "Collect" means "transmitting data from your app off a user's device" (answer 10787469). The
  app sends the passwords and reads device lists only between the phone and the user's own
  router. Google's page has no explicit rule for data sent to the user's own device or server
  (UNVERIFIED how Google classifies it). Two options for the owner:
  - **A (recommended to discuss):** "No data collected; no data shared", with this explanation
    kept on file. Matches the app's design.
  - **B (most cautious):** declare "App info and performance: none"; "Personal info: none";
    "Device or other IDs: none"; and declare "User IDs / passwords: collected, not shared,
    processed only to provide the app's function, encrypted in transit when the router uses
    HTTPS". Note: on routers using plain HTTP the password is not encrypted in transit; the
    app shows a warning in that case.
- **Data deletion:** all data is on the phone. "Forget this router" and uninstalling the app
  delete it. No account exists.

## 6. Permissions in the built app (merged manifest of the debug build, 2026-11-17)

| Permission | From | Why |
|---|---|---|
| INTERNET | app | talk to the router (on the home network or VPN) |
| ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE | app | find the router on Wi-Fi; alerts run only on Wi-Fi |
| POST_NOTIFICATIONS | app | optional alerts; asked only when the owner turns alerts on |
| USE_BIOMETRIC, USE_FINGERPRINT | androidx.biometric | fingerprint / PIN prompt for saved passwords and app lock |
| RECEIVE_BOOT_COMPLETED, WAKE_LOCK, FOREGROUND_SERVICE | androidx.work | WorkManager keeps the alert schedule after a restart; the app itself starts no foreground service |
| `<package>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | androidx.core | internal; protects the screen-off receiver |

Re-check this table against the release build's merged manifest before submitting.

Not requested: location, contacts, camera, storage, ACCESS_LOCAL_NETWORK (only needed at
targetSdk 37; see ai/PROJECT.md).

## 7. Content rating

Utility app; no user-generated content shared with others, no ads, no purchases, no violence.
The owner completes the questionnaire in the Play Console.
