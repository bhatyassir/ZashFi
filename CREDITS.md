# Credits

ZashFi is free software under the GNU General Public License version 3 (see `LICENSE`).
It stands on the work of the people and projects below. Thank you.

Licences were checked on 2026-11-17 on each project's own repository. All are compatible with
GPL-3.0. The same list is shown in the app under More > About ZashFi
(`app/src/main/java/com/shaazifi/app/feature/about/Credits.kt`; a test keeps the two in step).

| Project | By | Licence | How ShaaziFi uses it |
|---|---|---|---|
| [OpenWrt](https://openwrt.org) | The OpenWrt Project | GPL-2.0-or-later | The router system ShaaziFi talks to (ubus, rpcd, uci, fw4). |
| [LuCI](https://github.com/openwrt/luci) | The OpenWrt Project and LuCI contributors | Apache-2.0 | Reference for router calls, page addresses and diagnostics (luci-rpc, access lists, menus). |
| [UnTangled](https://github.com/bidhata/untangled) | Krish Paul | GPL-2.0-or-later | The web interface ShaaziFi was first built around; reference for features and router calls. |
| [LuCI Mobile](https://github.com/cogwheel0/luci-mobile) | cogwheel0 and contributors | GPL-3.0 | Reference for features and router calls (Wi-Fi scan, services, interfaces, capability checks). |
| [OpenWrtManager](https://github.com/hagaygo/OpenWrtManager) | hagaygo | GPL-3.0 | Early inspiration for LuCI Mobile, credited by it; acknowledged here too. |
| [AdGuard Home](https://github.com/AdguardTeam/AdGuardHome) | AdGuard Software Ltd | GPL-3.0 | The ad blocker ShaaziFi manages; its API definition and server code are the reference. |
| [AdGuard Home Manager](https://github.com/JGeek00/adguard-home-manager) | JGeek00 | Apache-2.0 | Reference list of AdGuard Home features and API calls. |
| [Wireshark (device maker list)](https://www.wireshark.org) | The Wireshark developers and the IEEE Registration Authority | GPL-2.0-or-later | Device makers by hardware address in `app/src/main/assets/oui/makers.tsv`. Our `tools/make_makers.py` converts Wireshark's `manuf` data. [Wireshark's generator](https://gitlab.com/wireshark/wireshark/-/blob/master/tools/make-manuf.py) identifies the data sources and licence. |
| [ZXing](https://github.com/zxing/zxing) | ZXing authors | Apache-2.0 | Guest Wi-Fi QR codes. |
| [OkHttp](https://github.com/square/okhttp) | Square, Inc. | Apache-2.0 | Network connections. |
| [Android Jetpack (AndroidX, Compose, WorkManager, Biometric, DataStore)](https://developer.android.com/jetpack) | Google and the Android Open Source Project | Apache-2.0 | App framework, screens, background checks, fingerprint prompt, storage. |
| [Kotlin and kotlinx (coroutines, serialization)](https://kotlinlang.org) | JetBrains and contributors | Apache-2.0 | Programming language and libraries. |
| [Bricolage Grotesque](https://github.com/ateliertriay/bricolage) | The Bricolage Grotesque Project Authors | OFL-1.1 | Heading font (licence: `app/src/main/assets/licenses/OFL-BricolageGrotesque.txt`). |
| [IBM Plex Sans and Mono](https://github.com/IBM/plex) | IBM Corp. | OFL-1.1 | Text and number fonts (licence: `app/src/main/assets/licenses/OFL-IBMPlex.txt`). |

## How code from others is handled

- No code has been copied from these projects so far. Router and AdGuard calls were learned from
  their documentation and source and written fresh in Kotlin.
- If code from a GPL-3.0 or Apache-2.0 project is adapted in future, the file says where it came
  from and keeps the original copyright notice, as both licences require.
- Names and logos of these projects belong to their owners and are used only to say what
  ShaaziFi works with.
