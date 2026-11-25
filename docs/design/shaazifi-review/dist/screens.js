function showBandwidth(back) {
  const sorted = [...devices()].sort((a, b) => b.down + b.up - a.down - a.up);
  const { down, up } = totals(sorted);
  const modeControl = `<div class="toolbar"><div class="form-row compact"><label for="traffic-mode">Demo readings</label>
    ${selectControl("Demo readings", "traffic-mode", [["split", "Download and upload"], ["combined", "Combined only"], ["unavailable", "Unavailable"]], trafficMode)}</div></div>`;
  const summary = trafficMode === "split"
    ? trafficPanel({ title: "All devices", list: sorted, kind: "traffic" })
    : panel("Combined speed", `<div class="rates">${reading("Download and upload total", "traffic", rate(down + up), "green")}</div>
      <p class="detail-summary">Separate download and upload readings are unavailable.</p>`, "", "hero");
  return `${heading("Who is using the internet", "See how much bandwidth each device is using.", "", back)}
    ${modeControl}
    ${trafficMode === "unavailable"
      ? panel("Traffic data unavailable", '<p class="muted">The router isn’t providing usable traffic counters.</p>')
      : `${summary}<section class="section"><div class="panel-heading"><h2>Devices now</h2>${badge(deviceCount(sorted.length), "neutral")}</div>
          <div class="panel list">${sorted.map(item => bandwidthRow(item, down + up)).join("")}</div></section>`}`;
}

function bandwidthRow(item, total) {
  const base = currentRoute();
  const values = trafficMode === "combined"
    ? `<span>${esc(rate(item.down + item.up))}</span>`
    : `<span class="green">${icon("down")} ${esc(rate(item.down))}</span><span class="gold">${icon("up")} ${esc(rate(item.up))}</span>`;
  return `<div class="bandwidth-item">${row({
    title: item.name, sub: `${item.connection} · ${item.ip}`, name: item.icon, tone: "gold",
    attrs: goAttr(`${base}/device/${item.id}`), htmlValue: `<span class="rate-pair">${values}</span>`
  })}<div class="meter" aria-label="Share of traffic"><span style="width:${total ? 100 * (item.down + item.up) / total : 0}%"></span></div></div>`;
}

function showHealth(back) {
  return `${heading("System health", router().model, badge("Online", "", true), back)}
    <div class="stack">${panel("Resources", systemMetrics(true))}
    ${panel("Router", `<div class="metrics">${metric("Uptime", router().uptime)}${metric("System", "OpenWrt")}${metric("Address", router().address)}</div>`)}
    </div>`;
}

function showInterfaces(back) {
  return `${heading("Interfaces and ports", "See wired links and network addresses.", "", back)}
    <div class="grid detail-layout">
      ${panel("Router ports", `<div class="ports">${["WAN", "LAN 1", "LAN 2", "LAN 3"].map((label, i) =>
        `<div class="port">${icon("network")}<strong>${label}</strong><small>${i === 2 ? "Down" : "1 Gbps"}</small></div>`).join("")}</div>`)}
      ${panel("Networks", `<div class="stack"><div>${badge("WAN", "", true)}<p class="detail-summary mono">203.0.113.10</p></div>
        <div>${badge("LAN", "", true)}<p class="detail-summary mono">${esc(router().address)}/24</p></div></div>`, link("Live traffic", "more/feature/live"))}
    </div>
    <section class="section"><h2>Wireless interfaces</h2><div class="grid two">${networks().map(n => networkCard(n, "wifi")).join("")}</div></section>`;
}

function showLive(back) {
  return `${heading("Live traffic", "Download and upload across the router.", "", back)}
    ${trafficPanel({ kind: "traffic" })}
    <section class="section"><h2>Interfaces</h2>${rows([["WAN", "Internet"], ["br-lan", "Local network"], ["wlan0", "2.4 GHz"], ["wlan1", "5 GHz"]]
      .map(([title, sub], i) => row({ title, sub, name: "network", tone: "blue", value: rate(totals(devices()).down / (i + 1)) })))}</section>`;
}

function showAdGuard() {
  const protection = profile().protection;
  return `${heading("AdGuard Home", "DNS protection and recent activity.", badge(protection ? "Protection on" : "Protection off", protection ? "" : "warn", protection))}
    <div class="grid detail-layout">
      ${panel("Protection", `<div class="metrics">${metric("DNS queries", "2,438", "Last 24 hours")}${metric("Blocked", "326", "13.4% of queries")}${metric("Response", "11 ms", "Average")}</div>`,
        button(protection ? "Pause protection" : "Enable protection", actAttr("protection"), protection ? "" : "primary", "shield"), "detail-hero")}
      ${panel("Recent queries", `<div class="mini-list">${queries.slice(0, 3).map(q => `<button class="mini-row" ${goAttr(`adguard/queries/${q.id}`)}>
        <span class="row-main"><span><strong>${esc(q.domain)}</strong><small>${esc(q.client)}</small></span></span>
        ${badge(queryStatus(q), queryStatus(q) === "Blocked" ? "warn" : "neutral")}</button>`).join("")}</div>`, link("Query log", "adguard/queries"))}
    </div>
    <section class="section"><h2>AdGuard settings</h2>
      <div class="grid two">${adGuardPages.map(([id, title, sub]) => shortcut(title, sub, "shield", "violet", `adguard/settings/${id}`)).join("")}</div>
    </section>`;
}
function queryStatus(item) { return profile().queries[item.id] ?? item.status; }
function queryList() {
  const query = filters.queries.trim().toLowerCase();
  return rows(queries.filter(item => (filters.queryStatus === "all" || filters.queryStatus === queryStatus(item)) &&
    `${item.domain} ${item.client}`.toLowerCase().includes(query)).map(item => row({
      title: item.domain, sub: `${item.time} · ${item.client}`, name: "globe", tone: "blue",
      attrs: goAttr(`adguard/queries/${item.id}`), htmlValue: badge(queryStatus(item), queryStatus(item) === "Blocked" ? "warn" : "neutral")
    })), "No matching queries.");
}
function showQueries() {
  return `${heading("Query log", "Review which domains were allowed or blocked.", "", "adguard")}
    <div class="toolbar">${searchInput("query-search", "Find a domain or device", filters.queries)}
      <div class="compact">${selectControl("Filter query status", "query-status", [["all", "All queries"], ["Allowed", "Allowed"], ["Blocked", "Blocked"]], filters.queryStatus)}</div></div>
    <div id="query-results">${queryList()}</div>`;
}
function showQuery(id) {
  const item = queries.find(q => q.id === id);
  if (!item) return showMissing("Query unavailable", "adguard/queries");
  const status = queryStatus(item);
  return `${heading(item.domain, "DNS query", badge(status, status === "Blocked" ? "warn" : ""), "adguard/queries")}
    <div class="stack">${rows([
      row({ title: "Client", value: item.client, name: "devices" }),
      row({ title: "Record type", value: item.type, name: "list" }),
      row({ title: "Time", value: item.time, name: "clock" })
    ])}<div class="actions">${button(status === "Blocked" ? "Allow domain" : "Block domain", actAttr("query-toggle", id), "primary", "shield")}</div></div>`;
}
function showAdGuardSettings(id) {
  const page = adGuardPages.find(item => item[0] === id);
  if (!page) return showMissing("Setting unavailable", "adguard");
  const preview = profile().settings[`ag-${id}`] ?? {};
  return `${heading(page[1], page[2], "", "adguard")}
    <div class="stack">${rows(page[3].map(([title, value]) => row({
      title, value: preview[title] ?? value, name: "shield", tone: "violet", attrs: actAttr("adguard-setting", `${id}|${title}`)
    })))}</div>`;
}

function shortcut(title, note, symbol, tone, path) {
  return `<button class="shortcut" ${goAttr(path)}><span>${tile(symbol, tone)}${icon("next")}</span>
    <span><strong>${esc(title)}</strong><small>${esc(note)}</small></span></button>`;
}
function featureRoute(id) {
  if (id === "wireless") return "wifi";
  if (["guest", "mesh", "extend"].includes(id)) return `wifi/${id}`;
  return `more/feature/${id}`;
}
function featureList() {
  const filter = filters.more.trim().toLowerCase();
  const filtered = groups.map(group => ({
    ...group, features: group.features.filter(([, title, sub]) => `${title} ${sub} ${group.title}`.toLowerCase().includes(filter))
  })).filter(group => group.features.length);
  return filtered.length
    ? `<div class="feature-columns">${filtered.map(group => `<section class="feature-group"><h2>${esc(group.title)}</h2>
      ${rows(group.features.map(([id, title, sub]) => row({ title, sub, name: group.icon, tone: group.tone, attrs: goAttr(featureRoute(id)) })))}</section>`).join("")}</div>`
    : '<p class="list-empty">No matching features.</p>';
}
function showMore() {
  const fallback = ["bandwidth", "services", "live", "parental"];
  const frequent = [...new Set([...Object.keys(profile().use).sort((a, b) => profile().use[b] - profile().use[a]), ...fallback])].slice(0, 4);
  return `${heading("All features", "", badge(`${groups.reduce((n, group) => n + group.features.length, 0)} features`, "neutral"))}
    <div class="toolbar">${searchInput("feature-search", "Find a feature", filters.more)}</div>
    <div id="frequent-features" ${filters.more ? "hidden" : ""}><section class="section"><h2>Frequently used</h2>
      <div class="cards">${frequent.map(id => {
        const entry = feature(id);
        return shortcut(entry.title, entry.description, entry.group.icon, entry.group.tone, featureRoute(id));
      }).join("")}</div></section></div>
    <section class="section" id="feature-results">${featureList()}</section>`;
}

const serviceNames = [["dnsmasq", "DNS and DHCP"], ["firewall", "Network firewall"], ["uhttpd", "Router web server"], ["odhcpd", "IPv6 DHCP"]];

function showServices(back) {
  return `${heading("Services", "View and control router services.", "", back)}
    ${rows(serviceNames.map(([title, sub]) => row({
      title, sub, name: "server", attrs: actAttr("service", title),
      htmlValue: badge(profile().services[title] === false ? "Stopped" : "Running", profile().services[title] === false ? "neutral" : "")
    })))}`;
}
function parentalList() {
  const query = filters.parental.trim().toLowerCase();
  const schedules = profile().schedules;
  const list = devices().filter(item =>
    `${item.name} ${item.type} ${item.ip} ${item.connection}`.toLowerCase().includes(query)
  ).sort((a, b) => Number(b.online) - Number(a.online) || a.name.localeCompare(b.name));
  return rows(list.map(item => {
    const count = schedules.filter(schedule => schedule.device === item.id).length;
    const countLabel = count === 1 ? "1 schedule" : `${count} schedules`;
    const details = [item.connection, item.ip, count ? countLabel : ""].filter(Boolean).join(" · ");
    return deviceRow(item, `parental/manage/${item.id}`, details);
  }), query ? "No devices match your search." : "No devices available.");
}

function showParental(back = "") {
  return `${heading("Parental controls", "Choose a device to manage its internet access.", "", back)}
    <div class="toolbar">${searchInput("parental-search", "Find a device", filters.parental)}</div>
    <div id="parental-results">${parentalList()}</div>`;
}

function showParentalDevice(item) {
  if (!item) return showMissing("Device unavailable", "parental");
  const schedules = profile().schedules.map((schedule, index) => ({ schedule, index }))
    .filter(({ schedule }) => schedule.device === item.id);
  return `${heading(item.name, `Parental controls · ${item.connection}`, badge(wordStatus(item), item.paused ? "warn" : item.online ? "" : "neutral"), "parental")}
    <div class="actions">${internetButton(item)}${button("Add a schedule", actAttr("schedule", item.id), "", "clock")}</div>
    <section class="section"><h2>Schedules</h2>${rows(schedules.map(({ schedule, index }) => row({
      title: schedule.day, sub: `${schedule.start}–${schedule.end}`, name: "clock",
      attrs: actAttr("remove-schedule", index), value: "Remove"
    })), "No schedules for this device.")}</section>`;
}

function showGuest() {
  return `${heading("Guest network", "Separate Wi-Fi for visitors.", badge(profile().guest ? "On" : "Off", profile().guest ? "" : "neutral"), "wifi")}
    ${rows([
      row({ title: "Cedar Guest", sub: "Internet only · Local device access off", name: "wifi", tone: "blue" }),
      row({ title: profile().guest ? "Turn guest network off" : "Turn guest network on", name: "power", attrs: actAttr("guest") })
    ])}`;
}
function showExtend() {
  const upstream = profile().settings.upstream;
  return `${heading("Extend Wi-Fi", "Select a sample upstream network to join.", "", "wifi")}
    ${upstream ? panel("Upstream network", `<p>${esc(upstream)}</p>`, badge("Connected", "", true)) : ""}
    <section class="section"><h2>Available networks</h2>${rows(["Cedar Annex", "Workshop Guest", "Guest Lab"].map((entry, index) =>
      row({ title: entry, sub: `${index === 1 ? "2.4" : "5"} GHz · WPA2`, name: "wifi",
        attrs: actAttr("join", entry), value: `${-49 - 7 * index} dBm` })))}</section>`;
}
function showDiagnostics(back) {
  const check = profile().settings.check;
  return `${heading("Diagnostics", "Preview connection checks with sample results.", "", back)}
    <div class="actions">${button("Ping", actAttr("diagnostic", "Ping"), "primary", "traffic")}
      ${button("Traceroute", actAttr("diagnostic", "Traceroute"), "", "link")}
      ${button("DNS lookup", actAttr("diagnostic", "DNS lookup"), "", "globe")}</div>
    ${check ? `<section class="section"><h2>${esc(check.name)} result</h2>
      <div class="panel"><div class="mono">${esc(check.host)}</div><p class="detail-summary">${esc(check.result)}</p></div></section>` : ""}`;
}
function showLog(id, back) {
  const messages = id === "kernellog"
    ? ["eth0: link up at 1 Gbps", "br-lan: port entered forwarding state", "wireless: interface ready"]
    : ["dnsmasq: started, cache size 1000", "netifd: WAN is now up", "firewall: rules loaded", "hostapd: AP enabled"];
  return `${heading(id === "kernellog" ? "Kernel log" : "System log", "Sample router messages.", "", back)}
    <div class="panel list">${messages.map((line, i) => `<div class="log-line"><span class="muted">16:4${2 - i}:00</span> ${esc(line)}</div>`).join("")}</div>`;
}
function showSettings(back) {
  return `${heading("Settings", "Choose how this preview looks and behaves.", "", back)}
    ${rows([
      row({ title: "Appearance", sub: "Switch between light and dark", name: state.theme === "dark" ? "sun" : "moon", value: state.theme === "dark" ? "Dark" : "Light", attrs: actAttr("theme") }),
      row({ title: "Reset demo data", sub: "Restore both demo routers and clear previews of changes", name: "settings", attrs: actAttr("reset") })
    ])}`;
}
function showActivity(back) {
  return `${heading("Activity", "Changes saved in this browser for this router.", "", back)}
    ${rows(profile().activity.map(item => row({ title: item.title, sub: item.time, name: "clock" })), "No changes yet.")}`;
}
function showRouters(back) {
  return `${heading("All routers", "Select a demo router.", "", back)}
    ${rows(routerRows())}`;
}
function routerRows() {
  return routers.map(item => row({ title: item.name, sub: `${item.model} · ${item.address}`, name: "router",
    htmlValue: item.id === state.router ? badge("Current") : "", attrs: actAttr("select-router", item.id) }));
}

function showMissing(title, back) {
  return `${heading(title, "Choose another item on this demo router.", "", back)}${link(backLabel(back), back)}`;
}

function showFeature(id, back = "more") {
  switch (id) {
    case "bandwidth": return showBandwidth(back);
    case "interfaces": return showInterfaces(back);
    case "live": return showLive(back);
    case "health": return showHealth(back);
    case "services": return showServices(back);
    case "parental": return showParental(back);
    case "diagnostics": return showDiagnostics(back);
    case "kernellog":
    case "log": return showLog(id, back);
    case "settings": return showSettings(back);
    case "activity": return showActivity(back);
    case "routers": return showRouters(back);
    case "about": return `${heading("About ShaaziFi", "Design preview for the ShaaziFi Android app.", "", back)}
      ${panel("Preview data", '<p class="muted">This demo uses fictional routers and devices. Changes are saved only in this browser.</p>')}`;
  }
  const entry = feature(id);
  if (!entry) return showMissing("Feature unavailable", back);
  const facts = [...(entry.facts ?? [])];
  if (id === "lan") facts.unshift(["Router address", router().address]);
  if (id === "admin") facts.unshift(["Router name", router().name]);
  return `${heading(entry.title, entry.description, "", back)}
    ${facts.length ? rows(facts.map(([title, value]) =>
      row({ title, value, name: entry.group.icon, tone: entry.group.tone }))) : ""}`;
}
