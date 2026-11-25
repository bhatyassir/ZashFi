const navigation = [
  ["home", "Home", "home"],
  ["devices", "Devices", "devices"],
  ["wifi", "Wi-Fi", "wifi"],
  ["adguard", "AdGuard", "shield"],
  ["parental", "Parental controls", "family", "Parental"],
  ["more", "More", "more"]
];

const routers = [
  {
    id: "home-gateway", name: "Home Gateway", model: "OpenWrt Home Router", address: "192.0.2.1",
    cpu: 8, memory: 64, uptime: "2d 4h", total: "28.4 GB", storage: 42, scale: 1,
    networks: [
      { id: "cedar", name: "Cedar Home", band: "2.4 GHz", channel: "6", width: "20 MHz", security: "WPA2", power: "20 dBm" },
      { id: "cedar-fast", name: "Cedar Fast", band: "5 GHz", channel: "149", width: "80 MHz", security: "WPA3", power: "23 dBm" }
    ],
    devices: [
      { id: "laptop", name: "Studio Laptop", type: "Laptop", icon: "laptop", ip: "192.0.2.24", mac: "02:00:00:00:02:24", net: "cedar-fast", signal: -45, down: 3.42, up: .31, usedMb: 8520, online: true },
      { id: "tv", name: "Living Room TV", type: "TV", icon: "screen", ip: "192.0.2.35", mac: "02:00:00:00:02:35", net: "wired", down: 1.49, up: .04, usedMb: 9230, online: true },
      { id: "phone", name: "Pixel Phone", type: "Phone", icon: "phone", ip: "192.0.2.11", mac: "02:00:00:00:02:11", net: "cedar-fast", signal: -57, down: .89, up: .23, usedMb: 3120, online: true },
      { id: "speaker", name: "Kitchen Speaker", type: "Speaker", icon: "speaker", ip: "192.0.2.18", mac: "02:00:00:00:02:18", net: "cedar", signal: -62, down: .071, up: .012, usedMb: 420, online: true },
      { id: "lamp", name: "Desk Lamp", type: "Smart home", icon: "light", ip: "192.0.2.45", mac: "02:00:00:00:02:45", net: "cedar", signal: -69, down: .004, up: .001, usedMb: 12.6, online: true },
      { id: "printer", name: "Office Printer", type: "Printer", icon: "printer", ip: "192.0.2.53", mac: "02:00:00:00:02:53", net: "cedar", signal: -66, down: 0, up: 0, usedMb: 18, online: true },
      { id: "nas", name: "Media Server", type: "Server", icon: "server", ip: "192.0.2.8", mac: "02:00:00:00:02:08", net: "wired", down: .008, up: .038, usedMb: 5400, online: true },
      { id: "tablet", name: "Family Tablet", type: "Tablet", icon: "phone", ip: "192.0.2.41", mac: "02:00:00:00:02:41", net: "cedar-fast", signal: -71, down: 0, up: 0, usedMb: 1480, online: false }
    ]
  },
  {
    id: "studio-gateway", name: "Studio Gateway", model: "OpenWrt Studio Router", address: "198.51.100.1",
    cpu: 12, memory: 48, uptime: "5d 7h", total: "14.9 GB", storage: 27, scale: .64,
    networks: [
      { id: "studio", name: "Studio", band: "2.4 GHz", channel: "11", width: "20 MHz", security: "WPA2", power: "18 dBm" },
      { id: "studio-fast", name: "Studio Fast", band: "5 GHz", channel: "44", width: "80 MHz", security: "WPA3", power: "20 dBm" }
    ],
    devices: [
      { id: "work", name: "Workstation", type: "Computer", icon: "screen", ip: "198.51.100.25", mac: "02:00:00:00:51:25", net: "wired", down: 2.46, up: .19, usedMb: 10500, online: true },
      { id: "studio-tablet", name: "Studio Tablet", type: "Tablet", icon: "phone", ip: "198.51.100.32", mac: "02:00:00:00:51:32", net: "studio-fast", signal: -47, down: .49, up: .075, usedMb: 2780, online: true },
      { id: "studio-printer", name: "Studio Printer", type: "Printer", icon: "printer", ip: "198.51.100.38", mac: "02:00:00:00:51:38", net: "studio", signal: -63, down: .005, up: 0, usedMb: 22.5, online: true }
    ]
  }
];

const groups = [
  {
    title: "Internet and network", icon: "globe", tone: "blue",
    features: [
      ["interfaces", "Interfaces and ports", "Port status and addresses"],
      ["routes", "Static routes", "Paths to other networks", [["Main table", "2 routes"], ["Default route", "WAN"]]],
      ["wan", "Internet (WAN)", "Upstream connection and DNS", [["Connection", "Online"], ["Type", "DHCP"], ["DNS", "Automatic"]]],
      ["lan", "LAN and DHCP", "Local address and lease pool", [["DHCP", "Enabled"], ["Lease time", "12 hours"]]],
      ["ipv6", "IPv6", "IPv6 status and delegated prefix", [["Status", "Enabled"], ["Delegated prefix", "2001:db8::/56"]]],
      ["dualwan", "Dual WAN", "Backup internet connection", [["Primary", "WAN"], ["Backup", "Not configured"]]],
      ["ddns", "Dynamic DNS", "Hostname updates", [["Status", "Not configured"], ["Profiles", "0"]]]
    ]
  },
  {
    title: "Wi-Fi", icon: "wifi", tone: "blue",
    features: [
      ["wireless", "Wireless", "See all networks and radios"],
      ["guest", "Guest network", "Separate access for visitors"],
      ["mesh", "Mesh Wi-Fi", "Coverage and mesh peers", [["Status", "Not configured"], ["Mesh peers", "0"]]],
      ["extend", "Extend Wi-Fi", "Join an upstream network"]
    ]
  },
  {
    title: "Speed and usage", icon: "traffic", tone: "gold",
    features: [
      ["bandwidth", "Who is using the internet", "Traffic by device"],
      ["live", "Live traffic", "Traffic across router ports"],
      ["traffic", "Traffic monitor", "Totals by interface", [["WAN", "28.4 GB"], ["LAN", "24.3 GB"]]],
      ["usage", "Usage history", "Data totals over time", [["Today", "4.7 GB"], ["This week", "28.4 GB"]]],
      ["qos", "Smart Queue (QoS)", "Latency and queue settings", [["Status", "Enabled"], ["Discipline", "CAKE"], ["Interface", "WAN"]]]
    ]
  },
  {
    title: "Protection", icon: "shield", tone: "violet",
    features: [
      ["protection", "Network protection", "Network filtering status", [["DNS protection", "AdGuard Home"], ["Firewall", "Enabled"]]],
      ["parental", "Parental controls", "Pause a device or add a schedule"],
      ["firewall", "Firewall", "Zones, traffic rules and policies", [["LAN policy", "Accept"], ["WAN inbound", "Reject"]]],
      ["portfwd", "Port forwarding", "Inbound forwarding rules", [["Active rules", "0"], ["Default", "Closed"]]],
      ["dmz", "DMZ", "Default inbound forwarding host", [["Status", "Disabled"]]],
      ["upnp", "UPnP", "Automatic port mappings", [["Status", "Disabled"], ["Mappings", "0"]]]
    ]
  },
  {
    title: "VPN", icon: "link", tone: "violet",
    features: [
      ["vpn", "WireGuard and OpenVPN", "Tunnels and peers", [["WireGuard", "No tunnels"], ["OpenVPN", "Not installed"]]],
      ["tailscale", "Tailscale", "Private remote access", [["Status", "Not connected"], ["Peers", "0"]]]
    ]
  },
  {
    title: "System", icon: "settings", tone: "",
    features: [
      ["admin", "Administration", "Router name and time zone", [["Time zone", "UTC"], ["Clock sync", "On"]]],
      ["health", "System health", "Processor, memory and storage"],
      ["services", "Services", "Router processes and controls"],
      ["diagnostics", "Diagnostics", "Ping, traceroute and DNS lookup"],
      ["doctor", "Network doctor", "Quick connection checks", [["Router", "Reachable"], ["WAN", "Connected"], ["DNS", "Working"]]],
      ["log", "System log", "Recent router messages"],
      ["kernellog", "Kernel log", "Drivers and hardware messages"],
      ["usb", "USB storage", "Mounted drives and sharing", [["USB devices", "0"], ["File sharing", "Off"]]],
      ["software", "Software", "Installed OpenWrt packages", [["Firewall", "firewall4"], ["DNS", "dnsmasq"], ["Shell", "BusyBox"]]],
      ["activity", "Activity", "Changes made in this preview"],
      ["routers", "All routers", "Overview of saved routers"],
      ["settings", "Settings", "Appearance and demo preferences"],
      ["about", "About ShaaziFi", "App information"]
    ]
  }
];

const adGuardPages = [
  ["blocklists", "DNS blocklists", "Manage filter sources", [["Tracking protection", "Enabled"], ["Malware protection", "Enabled"]]],
  ["custom", "Custom rules", "Block or allow domains", [["Active rules", "2"]]],
  ["services", "Blocked services", "Service and app filters", [["Blocked services", "None"]]],
  ["clients", "Clients", "Client-specific settings", [["Known clients", "7"]]],
  ["access", "Access lists", "DNS access rules", [["Mode", "Local network"]]],
  ["rewrites", "DNS rewrites", "Local DNS records", [["Rewrites", "None"]]],
  ["dns", "DNS settings", "Upstream and cache settings", [["Upstream", "Encrypted DNS"], ["Cache", "Enabled"]]],
  ["safe", "Safe search", "Filter search results", [["Status", "Off"]]],
  ["log-settings", "Log and statistics", "Retention and reporting", [["Query log", "On"], ["Retention", "24 hours"]]]
];

const queries = [
  { id: "q1", domain: "updates.example.net", client: "Studio Laptop", time: "16:42:08", status: "Allowed", type: "HTTPS" },
  { id: "q2", domain: "ads.example.com", client: "Living Room TV", time: "16:41:55", status: "Blocked", type: "A" },
  { id: "q3", domain: "media.example.org", client: "Pixel Phone", time: "16:41:32", status: "Allowed", type: "AAAA" },
  { id: "q4", domain: "metrics.example.net", client: "Studio Laptop", time: "16:40:46", status: "Blocked", type: "A" },
  { id: "q5", domain: "clock.example.org", client: "Kitchen Speaker", time: "16:39:51", status: "Allowed", type: "A" },
  { id: "q6", domain: "search.example.com", client: "Pixel Phone", time: "16:39:02", status: "Allowed", type: "HTTPS" }
];

const iconPaths = {
  brand: '<path d="M3 9a15 15 0 0 1 18 0M6 13a10 10 0 0 1 12 0M9 17a5 5 0 0 1 6 0"/><path d="M12 20h.01"/>',
  home: '<path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1z"/>',
  devices: '<rect x="2.5" y="4.5" width="13" height="10" rx="2"/><path d="M1 18h15"/><rect x="17.5" y="8.5" width="5" height="12" rx="1.5"/>',
  wifi: '<path d="M3 8a15 15 0 0 1 18 0M6 12a10 10 0 0 1 12 0M9 16a5 5 0 0 1 6 0m-3 4h.01"/>',
  shield: '<path d="M12 22c5-2 8-6 8-11V5l-8-3-8 3v6c0 5 3 9 8 11Z"/><path d="m8.5 12 2.5 2.5 4.5-5"/>',
  family: '<circle cx="9" cy="7" r="3"/><circle cx="17" cy="10" r="2.5"/><path d="M3 20v-2a6 6 0 0 1 12 0v2m2-5a4 4 0 0 1 4 4v1"/>',
  more: '<circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/>',
  router: '<rect x="3" y="11" width="18" height="8" rx="2"/><path d="M7 11V6m10 5V6M7 15h.01m4 0h.01m4 0h2"/>',
  chevrons: '<path d="m8 9 4-4 4 4m-8 6 4 4 4-4"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.4 1.4m11.2 11.2L19 19M5 19l1.4-1.4M17.6 6.4 19 5"/>',
  moon: '<path d="M12 3a9 9 0 1 0 9 9 7 7 0 0 1-9-9Z"/>',
  down: '<path d="M12 3v17m-5-5 5 5 5-5"/>',
  up: '<path d="M12 21V4m-5 5 5-5 5 5"/>',
  arrow: '<path d="M4 12h15m-6-6 6 6-6 6"/>',
  back: '<path d="M20 12H5m6-6-6 6 6 6"/>',
  next: '<path d="m9 5 7 7-7 7"/>',
  traffic: '<path d="M8 20V4m-4 4 4-4 4 4m4-4v16m-4-4 4 4 4-4"/>',
  globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c5 5 5 13 0 18-5-5-5-13 0-18Z"/>',
  settings: '<circle cx="12" cy="12" r="3"/><path d="M12 2v3m0 14v3M2 12h3m14 0h3M5 5l2 2m10 10 2 2M5 19l2-2M17 7l2-2"/>',
  link: '<path d="M10 14l4-4m-5 6-2 2a3 3 0 0 1-4-4l4-4a3 3 0 0 1 4 0m4-2 2-2a3 3 0 1 1 4 4l-4 4a3 3 0 0 1-4 0"/>',
  edit: '<path d="m15 5 4 4M4 20l4-1 12-12-4-4L4 15z"/>',
  search: '<circle cx="10" cy="10" r="6"/><path d="m15 15 5 5"/>',
  phone: '<rect x="7" y="3" width="10" height="18" rx="2.5"/><path d="M11 18h2"/>',
  laptop: '<rect x="4" y="4" width="16" height="12" rx="2"/><path d="M2 19h20"/>',
  screen: '<rect x="3" y="4" width="18" height="12" rx="2"/><path d="M12 16v4m-4 0h8"/>',
  speaker: '<rect x="6" y="3" width="12" height="18" rx="2"/><circle cx="12" cy="13" r="3"/><path d="M12 7h.01"/>',
  light: '<path d="M9 19h6m-5 3h4M8 15a6 6 0 1 1 8 0l-1 2H9z"/>',
  printer: '<path d="M7 8V3h10v5M7 16H3V8h18v8h-4M7 13h10v8H7z"/>',
  server: '<rect x="3" y="4" width="18" height="7" rx="2"/><rect x="3" y="13" width="18" height="7" rx="2"/><path d="M7 7.5h.01M7 16.5h.01"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  power: '<path d="M12 3v8m-5-5a8 8 0 1 0 10 0"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5m0-8h.01"/>',
  close: '<path d="m6 6 12 12M18 6 6 18"/>',
  check: '<path d="m4 12 5 5L20 6"/>',
  network: '<rect x="4" y="4" width="16" height="13" rx="2"/><path d="M8 20v-3m8 3v-3M7 8h2m2 0h2m2 0h2"/>',
  list: '<path d="M7 6h13M7 12h13M7 18h13M3 6h.01M3 12h.01M3 18h.01"/>',
  filter: '<path d="M3 6h18m-15 6h12m-9 6h6"/>',
  play: '<path d="m8 4 12 8-12 8Z"/>',
  pause: '<path d="M9 5v14m6-14v14"/>'
};
