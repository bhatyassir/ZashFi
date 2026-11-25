"use strict";

const main = document.querySelector("#main");
const dialog = document.querySelector("#dialog");
const storageKey = "shaazifi-design-preview-v1";
const defaultState = () => ({ router: routers[0].id, theme: "dark", profiles: {} });

function readState() {
  try {
    const saved = JSON.parse(localStorage.getItem(storageKey));
    if (saved && routers.some(r => r.id === saved.router) &&
      ["light", "dark"].includes(saved.theme) && saved.profiles && typeof saved.profiles === "object") return saved;
  } catch {
    // Storage is optional for the local preview.
  }
  return defaultState();
}

let state = readState();
let displayedRoute = "";
let chartRange = "1 min";
let trafficMode = "split";
let toastTimer;
let dialogSubmit;
const positions = new Map();
const filters = { devices: "", deviceKind: "all", sort: "name", more: "", parental: "", queries: "", queryStatus: "all" };
history.scrollRestoration = "manual";

function storeState() {
  try { localStorage.setItem(storageKey, JSON.stringify(state)); } catch {
    // Session still works.
  }
}
function router() { return routers.find(item => item.id === state.router); }
function profile() {
  return state.profiles[state.router] ??= {
    networks: {}, devices: {}, protection: true, guest: false, queries: {}, settings: {},
    services: {}, activity: [], schedules: [], use: {}
  };
}
function networks() {
  return router().networks.map(item => ({ enabled: true, ...item, ...profile().networks[item.id] }));
}
function devices() {
  const wifi = networks();
  return router().devices.map(item => {
    const device = { paused: false, ...item, ...profile().devices[item.id] };
    const network = wifi.find(n => n.id === device.net);
    const online = device.online && (network?.enabled ?? true);
    return {
      ...device,
      connection: network ? network.name : "Wired",
      online,
      down: device.paused || !online ? 0 : device.down,
      up: device.paused || !online ? 0 : device.up
    };
  });
}
function findNetwork(id) { return networks().find(item => item.id === id); }
function findDevice(id) { return devices().find(item => item.id === id); }
function feature(id) {
  for (const group of groups) {
    const match = group.features.find(item => item[0] === id);
    if (match) return { id, title: match[1], description: match[2], facts: match[3], group };
  }
}
function esc(value) {
  return String(value ?? "").replace(/[&<>"']/g, character => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
  }[character]));
}
function icon(name) {
  return `<span class="ico" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round">${iconPaths[name] ?? iconPaths.info}</svg></span>`;
}
function tile(name, tone = "") { return `<span class="icon-tile ${tone}">${icon(name)}</span>`; }
function badge(text, tone = "", dot = false) {
  return `<span class="badge ${tone}">${dot ? '<span class="dot"></span>' : ""}${esc(text)}</span>`;
}
function goAttr(path) { return `data-go="${esc(path)}"`; }
function actAttr(action, key = "") { return `data-action="${esc(action)}" data-key="${esc(key)}"`; }
function button(text, attributes, kind = "", name = "") {
  return `<button class="button ${kind}" type="button" ${attributes}>${name ? icon(name) : ""}${esc(text)}</button>`;
}
function link(text, path) {
  return `<button class="link-button" type="button" ${goAttr(path)}>${esc(text)}${icon("arrow")}</button>`;
}
function heading(title, subtitle = "", extra = "", back = "") {
  const previous = history.state?.from ?? back;
  return `${back ? `<button class="back" data-back="${esc(back)}">${icon("back")}${esc(backLabel(previous))}</button>` : ""}
    <div class="page-head"><div class="page-title"><h1>${esc(title)}</h1>${subtitle ? `<p>${esc(subtitle)}</p>` : ""}</div>${extra}</div>`;
}
function panel(title, content, action = "", style = "") {
  return `<section class="panel ${style}"><div class="panel-heading"><h2>${esc(title)}</h2>${action}</div>${content}</section>`;
}
function metric(label, value, note = "", level) {
  return `<div class="metric"><span class="label">${esc(label)}</span><strong>${esc(value)}</strong>
    ${level == null ? "" : `<div class="meter" aria-hidden="true"><span style="width:${level}%"></span></div>`}
    ${note ? `<small>${esc(note)}</small>` : ""}</div>`;
}
function rows(items, empty = "Nothing to show.") {
  return `<div class="panel list">${items.length ? items.join("") : `<p class="list-empty">${esc(empty)}</p>`}</div>`;
}
function row({ title, sub = "", value = "", htmlValue = "", name = "", tone = "", attrs = "" }) {
  const tag = attrs ? "button" : "div";
  return `<${tag} class="list-row" ${attrs}><span class="row-main">${name ? tile(name, tone) : ""}
    <span><strong>${esc(title)}</strong>${sub ? `<small>${esc(sub)}</small>` : ""}</span></span>
    ${htmlValue || (value ? `<span class="value mono">${esc(value)}</span>` : "")}${attrs ? icon("next") : ""}</${tag}>`;
}
function searchInput(id, hint, value) {
  return `<label class="searchbar">${icon("search")}<input id="${id}" type="search" aria-label="${esc(hint)}" placeholder="${esc(hint)}" value="${esc(value)}"></label>`;
}
function selectControl(label, id, options, selected) {
  return `<select id="${esc(id)}" aria-label="${esc(label)}">${options.map(([value, text]) =>
    `<option value="${esc(value)}"${value === selected ? " selected" : ""}>${esc(text)}</option>`).join("")}</select>`;
}
function chip(text, selected, action, key) {
  return `<button class="chip" type="button" aria-pressed="${selected}" ${actAttr(action, key)}>${esc(text)}</button>`;
}
function rate(amount) {
  if (amount === 0) return "0 Kbps";
  return amount < 1 ? `${Math.round(amount * 1000)} Kbps` : `${amount.toFixed(2)} Mbps`;
}
function usage(amountMb) {
  return amountMb < 1000 ? `${amountMb} MB` : `${(amountMb / 1000).toFixed(2)} GB`;
}
function totals(list) {
  return list.reduce((sum, d) => ({ down: sum.down + d.down, up: sum.up + d.up }), { down: 0, up: 0 });
}
function linkedCount(id) { return devices().filter(item => item.net === id && item.online).length; }
function deviceCount(count) { return `${count} ${count === 1 ? "device" : "devices"}`; }
function wordStatus(item) { return item.paused ? "Paused" : item.online ? "Online" : "Offline"; }
function internetButton(item) {
  return button(item.paused ? "Resume internet" : "Pause internet", actAttr("device-toggle", item.id), "primary", item.paused ? "play" : "pause");
}

function backLabel(path) {
  const parts = path.split("/");
  if (parts.at(-2) === "device") return `Back to ${findDevice(parts.at(-1))?.name ?? "device"}`;
  if (parts[1] === "network") return `Back to ${findNetwork(parts[2])?.name ?? "network"}`;
  if (parts[1] === "bandwidth") return "Back to traffic";
  if (parts[1] === "feature") return `Back to ${feature(parts[2])?.title ?? "More"}`;
  if (parts[1] === "queries") return "Back to query log";
  return `Back to ${navigation.find(([id]) => id === parts[0])?.[1] ?? "Home"}`;
}
function currentRoute() {
  const path = location.hash.slice(1);
  return navigation.some(([id]) => id === path.split("/")[0]) ? path : "home";
}
function navigate(path) {
  if (path === currentRoute()) {
    window.scrollTo(0, 0);
    return;
  }
  positions.set(currentRoute(), window.scrollY);
  const from = currentRoute();
  history.pushState({ from }, "", `#${path}`);
  const featureId = path.match(/^more\/feature\/([a-z-]+)$/)?.[1];
  if (featureId) {
    profile().use[featureId] = (profile().use[featureId] ?? 0) + 1;
    storeState();
  }
  render();
  window.scrollTo(0, 0);
  main.focus({ preventScroll: true });
}
function goBack(fallback) {
  if (history.state?.from) history.back();
  else navigate(fallback);
}
function navButtons(active, compact = false) {
  return navigation.map(([id, label, symbol, shortLabel = label]) =>
    `<button class="nav-button" ${goAttr(id)} aria-label="${esc(label)}"${active === id ? ' aria-current="page"' : ""}>${icon(symbol)}<span>${esc(compact ? shortLabel : label)}</span></button>`).join("");
}

function networkCard(network, origin) {
  const color = network.band === "5 GHz" ? "blue" : "";
  return `<button class="network-card ${color}" ${goAttr(`${origin}/network/${network.id}`)}>
    <span class="network-top">${tile("wifi", color)}${badge(network.enabled ? "On" : "Off", network.enabled ? "" : "neutral", network.enabled)}</span>
    <strong>${esc(network.name)}</strong>
    <span class="network-meta"><span>${esc(network.band)} · Ch ${esc(network.channel)}</span><span>${esc(deviceCount(linkedCount(network.id)))}</span></span>
    <span class="network-link">View network ${icon("arrow")}</span>
  </button>`;
}

function chart(mode = "home", compact = false, device = "") {
  return `<canvas class="chart ${compact ? "mini" : ""}" data-chart="${mode}" data-device="${esc(device)}"
    role="img" aria-label="Sample download and upload history"></canvas>
    <div class="chart-axis"><span>${chartRange === "1 min" ? "60 sec ago" : "5 min ago"}</span><span>Now</span></div>`;
}
function ranges() {
  return `<div class="segment" aria-label="Traffic history period">${["1 min", "5 min"].map(range =>
    `<button type="button" aria-pressed="${range === chartRange}" ${actAttr("range", range)}>${range}</button>`).join("")}</div>`;
}
function reading(label, symbol, value, style) {
  const parts = value.split(" ");
  return `<div class="rate"><span class="rate-label">${icon(symbol)}${esc(label)}</span>
    <span class="rate-reading ${style}">${esc(parts[0])}<small>${esc(parts[1])}</small></span></div>`;
}
function trafficPanel({ title = "Internet traffic", list = devices(), kind = "home", device = null } = {}) {
  const { down, up } = totals(list);
  let footer = "";
  if (kind === "home") {
    footer = `<div class="chart-foot"><span><strong>${esc(router().total)}</strong> since router start</span>${link("Who is using it?", "home/bandwidth")}</div>`;
  } else if (device) {
    footer = `<div class="chart-foot device-usage"><span><strong>Total data used</strong><small>Since router start</small></span>
      <strong class="usage-amount">${esc(usage(device.usedMb))}</strong></div>`;
  }
  return panel(title, `<div class="rates">${reading("Download", "down", rate(down), "green")}${reading("Upload", "up", rate(up), "gold")}</div>
    ${chart(kind, Boolean(device), device?.id)}${footer}`, ranges(), "hero");
}
function systemMetrics(extended = false) {
  const item = router();
  return `<div class="metrics">
    ${metric("CPU", `${item.cpu}%`, "", item.cpu)}${metric("Memory", `${item.memory}%`, "", item.memory)}
    ${metric(extended ? "Storage" : "Uptime", extended ? `${item.storage}%` : item.uptime, "", extended ? item.storage : undefined)}
  </div>`;
}
function miniDevice(item, path, value) {
  return `<button class="mini-row" ${goAttr(path)}><span class="row-main">${tile(item.icon, item.net === "wired" ? "violet" : "blue")}
    <span><strong>${esc(item.name)}</strong><small>${esc(item.connection)}</small></span></span>
    <span class="row-value">${esc(value)}</span></button>`;
}

function showHome() {
  const active = devices().filter(item => item.online);
  const busiest = [...active].sort((a, b) => b.down + b.up - a.down - a.up).slice(0, 3);
  const { model, address } = router();
  return `${heading("Network overview", `${model} · ${address}`, badge("Internet online", "", true))}
    <div class="grid home-grid">
      ${trafficPanel()}
      ${panel("Wi-Fi networks", `<div class="network-cards">${networks().map(n => networkCard(n, "home")).join("")}</div>${link("All Wi-Fi settings", "wifi")}`, "", "network-panel")}
      ${panel("System health", systemMetrics(), link("Details", "home/health"), "systems")}
      ${panel("Top connected devices", `<div class="mini-list">${busiest.map(d => miniDevice(d, `home/device/${d.id}`, rate(d.down + d.up))).join("") || '<p class="muted">No online devices.</p>'}</div>`,
        link(`${active.length} online`, "devices"), "device-panel")}
    </div>`;
}

function showWifi() {
  const wireless = networks();
  return `${heading("Wi-Fi", "Manage your networks and wireless radios.", badge(`${wireless.filter(n => n.enabled).length} radios on`, "", true))}
    <div class="grid two">${wireless.map(n => networkCard(n, "wifi")).join("")}</div>
    <section class="section"><h2>Wireless tools</h2>${rows([
      row({ title: "Guest network", sub: "Separate Wi-Fi for visitors", name: "wifi", tone: "blue", attrs: goAttr("wifi/guest"), htmlValue: badge(profile().guest ? "On" : "Off", profile().guest ? "" : "neutral") }),
      row({ title: "Extend Wi-Fi", sub: "Join another Wi-Fi network", name: "link", tone: "blue", attrs: goAttr("wifi/extend") }),
      row({ title: "Mesh Wi-Fi", sub: "Coverage and mesh peers", name: "network", tone: "blue", attrs: goAttr("wifi/mesh") })
    ])}</section>`;
}
function showNetwork(origin, id) {
  const network = findNetwork(id);
  if (!network) return showMissing("Network unavailable", origin);
  const back = origin === "home" ? "home" : "wifi";
  const clients = devices().filter(item => item.net === id && item.online);
  const detailPath = `${origin}/network/${id}`;
  const color = network.band === "5 GHz" ? "blue" : "";
  return `${heading(network.name, `Wi-Fi network · ${network.band}`, badge(network.enabled ? "On" : "Off", network.enabled ? "" : "neutral", network.enabled), back)}
    <div class="grid detail-layout">
      <div class="stack">
        <section class="panel detail-hero ${color}">
          <div class="panel-heading"><h2>Network at a glance</h2>${tile("wifi", color)}</div>
          <div class="metrics">${metric("Band", network.band)}${metric("Channel", network.channel)}${metric("Security", network.security)}
            ${metric("Width", network.width)}${metric("Transmit power", network.power)}${metric("Connected", deviceCount(clients.length))}</div>
        </section>
        <section><div class="panel-heading"><h2>Connected devices</h2>${badge(deviceCount(clients.length), "neutral")}</div>
          ${rows(clients.map(d => deviceRow(d, `${detailPath}/device/${d.id}`, `${d.ip} · ${d.signal} dBm`)), network.enabled ? "No devices connected." : "This network is off.")}
        </section>
      </div>
      <section><div class="panel-heading"><h2>Network settings</h2></div>
        ${rows([
          row({ title: "Network name", sub: network.name, name: "edit", tone: color, attrs: actAttr("network-name", id) }),
          row({ title: "Channel, width and power", sub: `Channel ${network.channel} · ${network.width} · ${network.power}`, name: "settings", tone: color, attrs: actAttr("radio", id) }),
          row({ title: "Network enabled", sub: network.enabled ? "Tap to turn off" : "Tap to turn on", name: "power", tone: color, attrs: actAttr("network-toggle", id),
            htmlValue: `<span class="switch ${network.enabled ? "on" : ""}" aria-hidden="true"></span>` })
        ])}
      </section>
    </div>`;
}

function deviceRow(item, path, sub = `${item.ip} · ${item.connection}`) {
  return row({ title: item.name, sub, name: item.icon, tone: item.net === "wired" ? "violet" : "blue",
    attrs: goAttr(path), htmlValue: badge(wordStatus(item), item.paused ? "warn" : item.online ? "" : "neutral") });
}
function deviceList() {
  const query = filters.devices.toLowerCase().trim();
  let list = devices().filter(d =>
    (filters.deviceKind === "all" || (filters.deviceKind === "wifi" ? d.net !== "wired" : filters.deviceKind === "online" ? d.online : d.net === "wired")) &&
    `${d.name} ${d.ip} ${d.type} ${d.connection}`.toLowerCase().includes(query)
  );
  list = [...list].sort((a, b) => filters.sort === "traffic" ? b.down + b.up - a.down - a.up : a.name.localeCompare(b.name));
  return rows(list.map(item => deviceRow(item, `devices/device/${item.id}`)), "No devices match these filters.");
}
function showDevices() {
  const list = devices();
  return `${heading("Devices", "Find a device or see what is connected.", badge(`${list.filter(item => item.online).length} online`, "", true))}
    <div class="toolbar">${searchInput("device-search", "Find a device", filters.devices)}
      <div class="compact">${selectControl("Sort devices", "device-sort", [["name", "Name"], ["traffic", "Traffic"]], filters.sort)}</div></div>
    <div id="device-chips" class="pills toolbar">${deviceChips()}</div>
    <div id="device-results">${deviceList()}</div>`;
}
function deviceChips() {
  return [["all", "All devices"], ["online", "Online"], ["wifi", "Wi-Fi"], ["wired", "Wired"]].map(([id, text]) =>
    chip(text, filters.deviceKind === id, "device-filter", id)).join("");
}
function showDevice(item, parent) {
  if (!item) return showMissing("Device unavailable", parent);
  const route = currentRoute();
  const node = findNetwork(item.net);
  const status = badge(wordStatus(item), item.paused ? "warn" : item.online ? "" : "neutral");
  return `${heading(item.name, `${item.type} · ${item.ip}`, `<div class="device-heading-actions">${status}${internetButton(item)}</div>`, parent)}
    <div class="grid detail-layout">
      <div class="stack">
        ${trafficPanel({ title: "Device traffic", list: [item], kind: "device", device: item })}
        <section><div class="panel-heading"><h2>Connection</h2></div>${rows([
          row({ title: "Network", sub: item.connection, name: item.net === "wired" ? "network" : "wifi", tone: "blue",
            attrs: node ? goAttr(`${route.split("/")[0] === "home" ? "home" : "wifi"}/network/${node.id}`) : "" }),
          row({ title: "IP address", value: item.ip, name: "globe", tone: "blue" }),
          row({ title: "MAC address", value: item.mac, name: "devices", tone: "violet" }),
          ...(item.signal ? [row({ title: "Signal", value: `${item.signal} dBm`, name: "wifi", tone: "blue" })] : [])
        ])}</section>
      </div>
      <section><div class="panel-heading"><h2>Device controls</h2></div>${rows([
        row({ title: "Rename device", name: "edit", attrs: actAttr("device-name", item.id) }),
        row({ title: "Parental controls", name: "shield", tone: "violet", attrs: goAttr(`parental/manage/${item.id}`) })
      ])}</section>
    </div>`;
}
