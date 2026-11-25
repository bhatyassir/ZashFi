# Router calls: what LuCI Mobile uses, what ShaaziFi uses (2026-11-17)

Sources read 2026-11-17: cogwheel0/luci-mobile `main` (GPL-3.0; `lib/services/*.dart`,
`lib/models/router_capabilities.dart`), openwrt/luci `master` (Apache-2.0; menu.d files and
diagnostics/startup views). Read for study; no code copied into ShaaziFi yet.

## ubus calls in LuCI Mobile (object method → ShaaziFi today)

| Call | LuCI Mobile uses it for | ShaaziFi |
|---|---|---|
| session access, session list | capability check (what the login may do) | gate via system board only → **add** |
| luci getFeatures | which LuCI features exist | **add** |
| luci getRealtimeStats | live traffic / load graphs | **add** |
| luci getTimezones | time zone list | **add** (with the time zone change) |
| luci setPassword | change the router password | not allowed today (deliberately) → owner decides |
| luci-rpc getNetworkDevices | interfaces screen | **add** |
| luci-rpc getWirelessDevices, getHostHints, getDHCPLeases | Wi-Fi, clients | yes |
| luci.wireguard getWgInstances | WireGuard peers | **add** (read only) |
| iwinfo assoclist | Wi-Fi clients and signal | yes |
| iwinfo scan | scan nearby networks | **add** |
| network.interface dump, system board, system info | dashboard | yes |
| rc list | services | yes (10b) |
| rc init | start / stop / restart services | **add** (with confirmation) |
| system reboot | reboot | not allowed today → owner decides |
| uci get/set/add/delete/changes/revert/apply/confirm/rollback/commit/configs | changes with rollback (falls back to commit when rollback is not permitted, and says so) | yes, except the commit fallback → **check** |
| file exec | ping, traceroute, nslookup, dmesg, logread, `ip neigh show`, etherwake, nlbwmon actions, `/sbin/wifi` | ping/traceroute/nslookup/etherwake/logread-like log/nlbw → **add** dmesg, `ip neigh` |

Also in LuCI Mobile: GL.iNet routers' own API (`glinet_api_service.dart`), an add-on catalogue
(installing packages), an activity feed with background notifications, client aliases and
presence, per-client traffic (nlbwmon), 12+ app languages.

## LuCI page addresses used by ShaaziFi's fallback

From openwrt/luci `master` menu.d: admin/network/{wireless, network, routes, dhcp, dns,
diagnostics, firewall, firewall/forwards, firewall/rules, sqm}, admin/services/{ddns, upnp},
admin/system/{system, admin/password, startup, crontab, mounts, package-manager, flash, reboot},
admin/status/{overview, logs, logs/dmesg, realtime/bandwidth, channel_analysis}.
Older LuCI releases may use admin/system/opkg for software (UNVERIFIED per release).

## Router packages needed (any OpenWrt with LuCI)

`rpcd`, `rpcd-mod-luci` (luci-rpc, luci objects), `rpcd-mod-iwinfo` (Wi-Fi details), `rpcd-mod-rc`
(services), `rpcd-mod-file` (commands, logs). LuCI Mobile's README names rpcd-mod-luci and
rpcd-mod-iwinfo; the others are UNVERIFIED as separate packages on every release.
