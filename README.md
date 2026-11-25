# ZashFi

A free Android app for managing OpenWrt routers with LuCI, and AdGuard Home, from your phone.
Free software under the GNU General Public License version 3.

ZashFi connects directly to the OpenWrt router and AdGuard Home instance you configure on your
local network or VPN. Demo mode lets you try the interface with sample data.

## Install the GitHub beta

Download the ZashFi Beta APK from [Releases](../../releases). This beta uses the separate Android
package `com.shaazifi.app.v2.beta` and can be installed alongside other ZashFi and ShaaziFi builds.
It uses a test signing certificate. Use releases from this repository for beta updates.

## What it does

**See your network**
- Home: internet status, speed, devices, processor and memory at a glance
- Devices: names, addresses, makers; fixed addresses, names, blocking; wake-on-LAN
- Interfaces and ports, live traffic, system health, kernel and system logs, services
- Wi-Fi: networks, guest network with a QR code, mesh (with UnTangled), WireGuard peers
- Diagnostics run on the router: ping, trace route, DNS lookup

**Change settings safely**
- Wi-Fi name, password, channel, width, country and power; join another Wi-Fi network
- Port forwarding, DMZ, parental rules, firewall zones and traffic rules, static routes
- Internet (WAN), IPv6, DDNS, UPnP, Smart Queue, VPN tunnels, time zone, router name
- Every change is shown to you first and uses the router's automatic undo: if the app loses
  contact after a change, the router puts the old settings back by itself
- One-off actions (restart a service, restart the router, change the router password) ask first,
  and the risky ones need a word typed

**AdGuard Home**
- Dashboard, protection on/off and temporary pause
- Live DNS queries, retained history, per-device filtering and query details
- Export all retained queries, a device's queries, or the current filters as CSV or plain text
- Blocklists, custom rules, blocked services, clients, access lists, DNS rewrites, DNS
  settings, log and statistics settings, safe search

**Made for a phone**
- Fingerprint sign-in (password kept encrypted in the phone's secure hardware) and app lock
- Quick Settings tiles, home-screen widget, alerts for new devices and internet outages
- Home address plus VPN address (for example Tailscale) per router
- Activity log of changes; devices from all your routers in one searchable list
- Light, dark or system theme; demo mode without a router

## Router setup

Any OpenWrt router with LuCI should work. The app uses rpcd (the router's RPC service) through
LuCI's web server:

```sh
# OpenWrt with opkg
opkg update && opkg install rpcd-mod-luci rpcd-mod-iwinfo
# OpenWrt with apk
apk update && apk add rpcd-mod-luci rpcd-mod-iwinfo
/etc/init.d/rpcd restart
```

Live traffic uses `luci-bwc`, which comes with LuCI's status module (`luci-mod-status`). Other
packages (AdGuard Home, DDNS, Smart Queue, WireGuard, Tailscale) are optional; screens that need
a missing package say so.

**Logins other than root:** install the app's permission file `docs/router/shaazifi.json`
(instructions in `docs/router/README.md`). Features the login cannot use are hidden.

UnTangled (https://github.com/bidhata/untangled) is optional: when installed, its pages are used
as the fallback for settings the app does not change itself; otherwise LuCI's pages are.

## Troubleshooting

- **Cannot connect:** open the same address in a browser on the phone; check http/https and the
  port. The app only connects to private addresses (home network, Tailscale range).
- **Certificate changed:** the app refuses to connect until you approve the new certificate.
  Approve it only if you know why it changed (for example a router reset).
- **Sign-in or screens time out while LuCI works in a browser:** some LuCI versions stall on
  parallel requests (openwrt/luci issue 9091). Limiting the web server to one request at a time
  helps (tip from LuCI Mobile's README):
  ```sh
  uci set uhttpd.main.max_requests=1 && uci commit uhttpd && /etc/init.d/uhttpd restart
  ```
- **A screen says "The router does not allow this":** see "Logins other than root" above.

## Build

Android Studio or the command line (JDK 21, Android SDK with platform 36):

```sh
./gradlew assembleDebug
./gradlew assembleBeta
./gradlew test
```

## Privacy

ZashFi sends sign-in credentials to the router or AdGuard Home you configure. Saved passwords
are encrypted on the phone. DNS exports open Android's share menu so you can choose where to
send the file. See the [privacy policy draft](docs/play/privacy-policy.md).

## Credits

- [UnTangled](https://github.com/bidhata/untangled) by Krish Paul and contributors helped shape
  the original router-management experience.
- [LuCI Mobile](https://github.com/cogwheel0/luci-mobile) by cogwheel0 and contributors informed
  the OpenWrt feature coverage and troubleshooting.
- [OpenWrtManager](https://github.com/hagaygo/OpenWrtManager) by hagaygo is credited as an early
  inspiration for LuCI Mobile.
- [AdGuard Home Manager](https://github.com/JGeek00/adguard-home-manager) by JGeek00 informed
  the AdGuard control and query-log feature set.
- The OpenWrt and LuCI contributors and the AdGuard Home team maintain the router interfaces
  and DNS service that ZashFi connects to.

See [CREDITS.md](CREDITS.md) for the full acknowledgments and licences.

## Licence

ZashFi is an independent project licensed under the [GNU General Public License version 3](LICENSE).
