# Router permission file (ShaaziFi)

`shaazifi.json` tells the router's RPC daemon (rpcd) what the app may read and change.
It is in the same format as UnTangled's own file
(`openwrt/files/usr/share/rpcd/acl.d/untangled.json` in the UnTangled repository).

## Do I need it?

Usually **no**. While UnTangled is installed on the router, its own permission file already
covers everything the app uses. Install this file only if:

- you remove UnTangled from the router, or
- a screen in the app says **"The router does not allow this"**.
- **Diagnostics** says "Not permitted" or **Services** says "The router does not allow this".
  Whether UnTangled's own file covers these is UNVERIFIED; LuCI's own files do for LuCI's logins.

## What it allows

- **Read:** router status, Wi-Fi status, device lists, the system log, and the settings for
  network, DHCP, firewall, DDNS, Dual WAN, UPnP, Smart Queue, VPN, USB sharing and protection
  packages. It also allows six commands that only print information: vnstat, nlbw, tailscale
  status (two possible paths), and the package list (apk or opkg).
- **Diagnostics and services (M10b, read only):** the list of services (`rc list`; starting or
  stopping them is not allowed), and ping, trace route and DNS lookup with exactly the options
  LuCI's own Diagnostics page uses (`luci-mod-network` ACL and `diagnostics.js`). Only the host
  name at the end changes; the app accepts only plain host names and IPv4 addresses there.
  The router matches these lines with a `*` at the end, so the app's own check is the narrow one.
- **Wi-Fi radios and interfaces (phase 2 and 3, read only):** Wi-Fi scan and the channel,
  power and country lists (`iwinfo scan/freqlist/txpowerlist/countrylist`, as LuCI's Wi-Fi page),
  interfaces and ports (`luci-rpc getNetworkDevices`), live traffic (`luci getRealtimeStats`),
  WireGuard peers (`luci.wireguard getWgInstances`) and the kernel log (`/bin/dmesg -r`).
- **Change (named settings only, always with the router's automatic undo):** device fixed
  addresses and names; firewall block, parental, port-forward and DMZ rules; guest Wi-Fi on/off
  and the main Wi-Fi name and password; UPnP, DDNS, Smart Queue, media sharing, adblock, banIP
  and OpenVPN on/off; Smart Queue limits; the address pool; the router name; internet (WAN)
  type and settings; IPv6 from the provider on/off; WireGuard tunnels on/off. The app itself
  refuses everything else before sending (see `RouterWriter.RULES` and the section rules for
  the network config: the LAN is never changed).
- **Commands:** firewall reload, miniupnpd restart/stop/enable, ddns reload, sqm restart,
  minidlna restart/stop/enable, wake-on-LAN.
- **Phase 4 (owner-approved, each confirmed in the app):** start / stop / restart / enable /
  disable services (`rc init`; the app refuses to stop or disable uhttpd, rpcd, network,
  dnsmasq and firewall), restart the router (`system reboot`, type RESTART), change the root
  password (`luci setPassword`, type CHANGE), time zone, firewall zone policies, traffic rules
  and static routes (these three with the router's automatic undo).
- **Never:** firmware, the LAN address, or anything else not listed here.

Note: rpcd ACLs work per config, not per setting. The narrower per-setting limits are
enforced by the app.

## How to install it (you run these; the app never changes the router by itself)

From the project folder on your computer, with the router at 192.168.1.1:

```
scp -O docs/router/shaazifi.json root@192.168.1.1:/usr/share/rpcd/acl.d/shaazifi.json
```

```
ssh root@192.168.1.1 "/etc/init.d/rpcd reload"
```

`-O` makes scp use the older copy method, which OpenWrt's small SSH server (dropbear)
accepts. UNVERIFIED on this router; if scp fails, try without `-O`.

## How to undo it

```
ssh root@192.168.1.1 "rm /usr/share/rpcd/acl.d/shaazifi.json && /etc/init.d/rpcd reload"
```

## Notes

- Which users get these permissions is set in `/etc/config/rpcd` on the router. On a
  standard OpenWrt install the `root` login gets all permission groups. UNVERIFIED on this
  router.
- Keep this file in step with `RouterReader` and `RouterWriter` in the app. Tests check
  the app's own lists, but not this file.
