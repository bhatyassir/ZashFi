function routeContent(path) {
  const [tab, view, id] = path.split("/");
  const parts = path.split("/");
  if (parts.at(-2) === "device") {
    const back = parts.length > 3 ? parts.slice(0, -2).join("/") : tab;
    return showDevice(findDevice(parts.at(-1)), back);
  }
  if ((tab === "home" || tab === "wifi") && view === "network") return showNetwork(tab, id);
  if (tab === "home") {
    if (view === "bandwidth") return showBandwidth("home");
    if (view === "health") return showHealth("home");
    return showHome();
  }
  if (tab === "devices") return showDevices();
  if (tab === "wifi") {
    if (view === "guest") return showGuest();
    if (view === "extend") return showExtend();
    if (view === "mesh") return showFeature("mesh", "wifi");
    return showWifi();
  }
  if (tab === "adguard") {
    if (view === "queries") return id ? showQuery(id) : showQueries();
    if (view === "settings") return showAdGuardSettings(id);
    return showAdGuard();
  }
  if (tab === "parental") return view === "manage" ? showParentalDevice(findDevice(id)) : showParental();
  return view === "feature" ? showFeature(id) : showMore();
}

function render() {
  const path = currentRoute();
  displayedRoute = path;
  const active = path.split("/")[0];
  document.documentElement.dataset.theme = state.theme;
  document.querySelector("#router-name").textContent = router().name;
  document.querySelector("#router-status").textContent = "Online · OpenWrt";
  const themeButton = document.querySelector(".header-actions [data-action='theme']");
  themeButton.innerHTML = icon(state.theme === "dark" ? "sun" : "moon");
  themeButton.setAttribute("aria-label", state.theme === "dark" ? "Use light theme" : "Use dark theme");
  document.querySelector("#desktop-nav").innerHTML = navButtons(active);
  document.querySelector("#mobile-nav").innerHTML = navButtons(active, true);
  main.innerHTML = routeContent(path);
  document.title = `${main.querySelector("h1").textContent} · ShaaziFi`;
  requestAnimationFrame(drawCharts);
}

const downloadShape = [22, 31, 25, 29, 40, 34, 47, 29, 37, 34, 43, 65, 52, 68, 45, 49, 38, 32, 49, 41, 66, 59, 72, 54, 49, 69, 89, 73, 78, 59, 72, 68, 84, 70, 83, 73, 78, 84];
const uploadShape = [9, 11, 12, 13, 11, 14, 14, 10, 11, 14, 18, 23, 20, 14, 19, 16, 12, 17, 12, 16, 21, 19, 26, 18, 15, 22, 22, 24, 20, 22, 20, 28, 26, 23, 21, 24, 21, 25];

function drawCharts() {
  const palette = getComputedStyle(document.documentElement);
  for (const canvas of main.querySelectorAll("canvas[data-chart]")) {
    const width = canvas.clientWidth;
    const height = canvas.clientHeight;
    if (width === 0 || height === 0) continue;
    const pixelRatio = Math.min(devicePixelRatio || 1, 2);
    canvas.width = width * pixelRatio;
    canvas.height = height * pixelRatio;
    const context = canvas.getContext("2d");
    if (!context) continue;
    context.scale(pixelRatio, pixelRatio);
    context.strokeStyle = palette.getPropertyValue("--line").trim();
    context.lineWidth = 1;
    for (let line = 0; line < 5; line++) {
      const y = 7 + (height - 13) * line / 4;
      context.beginPath();
      context.moveTo(0, y);
      context.lineTo(width, y);
      context.stroke();
    }

    const device = findDevice(canvas.dataset.device);
    const scale = device ? device.online && !device.paused ? Math.min(.9, .22 + device.down / 5) : 0 : router().scale;
    const series = (values, color, shade) => {
      const plotted = chartRange === "1 min" ? values : values.map((n, i) => (n + values[(i + 7) % values.length]) * .47);
      const points = plotted.map((n, i) => [2 + (width - 4) * i / (plotted.length - 1), height - 6 - n * scale * (height - 14) / 100]);
      const trace = () => {
        context.beginPath();
        context.moveTo(...points[0]);
        for (let i = 1; i < points.length - 1; i++) {
          const [x, y] = points[i];
          const [nextX, nextY] = points[i + 1];
          context.quadraticCurveTo(x, y, (x + nextX) / 2, (y + nextY) / 2);
        }
        context.lineTo(...points.at(-1));
      };
      if (shade) {
        trace();
        context.lineTo(width - 2, height - 5);
        context.lineTo(2, height - 5);
        context.closePath();
        const gradient = context.createLinearGradient(0, 8, 0, height);
        gradient.addColorStop(0, color);
        gradient.addColorStop(1, "transparent");
        context.save();
        context.globalAlpha = .23;
        context.fillStyle = gradient;
        context.fill();
        context.restore();
      }
      trace();
      context.lineWidth = 2;
      context.lineCap = "round";
      context.strokeStyle = color;
      context.stroke();
      context.fillStyle = color;
      context.beginPath();
      context.arc(...points.at(-1), 2.6, 0, Math.PI * 2);
      context.fill();
    };
    series(downloadShape, palette.getPropertyValue("--green").trim(), true);
    series(uploadShape, palette.getPropertyValue("--gold").trim(), false);
  }
}

function showToast(message) {
  const toast = document.querySelector("#toast");
  clearTimeout(toastTimer);
  toast.textContent = message;
  toast.hidden = false;
  toastTimer = setTimeout(() => { toast.hidden = true; }, 3000);
}
function commitChange(title) {
  const changes = profile().activity;
  changes.unshift({ title, time: new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) });
  if (changes.length > 30) changes.length = 30;
  storeState();
  render();
  showToast(title);
}
function openDialog(title, content, submitLabel, complete, footer = null) {
  dialogSubmit = complete;
  const defaultFooter = `<button type="button" class="button" data-action="close">${submitLabel ? "Cancel" : "Done"}</button>
    ${submitLabel ? `<button class="button primary" type="submit">${esc(submitLabel)}</button>` : ""}`;
  dialog.innerHTML = `<form class="dialog-inner">
    <div class="dialog-head"><h2 id="dialog-title">${esc(title)}</h2>
      <button class="icon-button" type="button" data-action="close" aria-label="Close">${icon("close")}</button></div>
    <div class="dialog-body">${content}<p id="dialog-error" role="alert"></p></div>
    <div class="dialog-foot">${footer ?? defaultFooter}</div>
    </form>`;
  if (!dialog.open) dialog.showModal();
}
function confirm(title, message, submitLabel, complete) {
  openDialog(title, `<p>${esc(message)}</p>`, submitLabel, complete);
}
function field(name, label, value = "", attributes = "") {
  return `<div class="form-row"><label for="form-${esc(name)}">${esc(label)}</label>
    <input id="form-${esc(name)}" name="${esc(name)}" value="${esc(value)}" ${attributes}></div>`;
}
function selectField(name, label, options, selected) {
  return `<div class="form-row"><label for="form-${esc(name)}">${esc(label)}</label><select id="form-${esc(name)}" name="${esc(name)}">
    ${options.map(option => {
      const [value, text] = Array.isArray(option) ? option : [option, option];
      return `<option value="${esc(value)}"${value === selected ? " selected" : ""}>${esc(text)}</option>`;
    }).join("")}</select></div>`;
}
function formError(text) {
  dialog.querySelector("#dialog-error").textContent = text;
  return false;
}
function changeNetwork(id, updates, message) {
  profile().networks[id] = { ...profile().networks[id], ...updates };
  commitChange(message);
}
function changeDevice(id, updates, message) {
  profile().devices[id] = { ...profile().devices[id], ...updates };
  commitChange(message);
}

const actions = {
  close: () => dialog.close(),
  switch: () => openDialog("Switch router", rows(routerRows()), ""),
  "select-router": id => {
    if (!routers.some(item => item.id === id)) return;
    if (dialog.open) dialog.close();
    if (state.router === id) return;
    state.router = id;
    chartRange = "1 min";
    trafficMode = "split";
    positions.clear();
    storeState();
    history.replaceState({}, "", "#home");
    render();
    window.scrollTo(0, 0);
    showToast(`Connected to ${router().name} in the demo`);
  },
  theme: () => {
    state.theme = state.theme === "dark" ? "light" : "dark";
    storeState();
    render();
  },
  range: value => { chartRange = value; render(); },
  "device-filter": value => {
    filters.deviceKind = value;
    document.querySelector("#device-chips").innerHTML = deviceChips();
    document.querySelector("#device-results").innerHTML = deviceList();
    document.querySelector("#device-chips [aria-pressed='true']").focus();
  },
  "network-name": id => {
    const item = findNetwork(id);
    openDialog("Change network name", field("name", "Network name", item.name, "required maxlength=\"32\""), "Save name", data => {
      const name = data.get("name").trim();
      if (!name) return formError("Enter a network name.");
      changeNetwork(id, { name }, "Network name saved");
    });
  },
  radio: id => {
    const item = findNetwork(id);
    const high = item.band === "5 GHz";
    const channels = high ? ["36", "44", "149", "157"] : ["1", "6", "11"];
    const widths = high ? ["20 MHz", "40 MHz", "80 MHz"] : ["20 MHz", "40 MHz"];
    openDialog("Channel, width and power", `${selectField("channel", "Channel", channels, item.channel)}
      ${selectField("width", "Channel width", widths, item.width)}
      ${selectField("power", "Transmit power", ["18 dBm", "20 dBm", "23 dBm"], item.power)}`, "Save radio settings", data => {
      changeNetwork(id, Object.fromEntries(data), "Radio settings saved");
    });
  },
  "network-toggle": id => {
    const item = findNetwork(id);
    const verb = item.enabled ? "Turn off" : "Turn on";
    confirm(`${verb} ${item.name}?`, item.enabled ? "Demo devices connected to this network will go offline." : "Demo devices on this network can reconnect.", verb, () =>
      changeNetwork(id, { enabled: !item.enabled }, `${item.name} turned ${item.enabled ? "off" : "on"}`));
  },
  "device-name": id => {
    const item = findDevice(id);
    openDialog("Rename device", field("name", "Device name", item.name, "required maxlength=\"40\""), "Save name", data => {
      const name = data.get("name").trim();
      if (!name) return formError("Enter a device name.");
      changeDevice(id, { name }, "Device name saved");
    });
  },
  "device-toggle": id => {
    const item = findDevice(id);
    const verb = item.paused ? "Resume" : "Pause";
    confirm(`${verb} internet?`, `${verb} internet for ${item.name} in the demo.`, verb, () =>
      changeDevice(id, { paused: !item.paused }, `${item.name}: internet ${item.paused ? "resumed" : "paused"}`));
  },
  schedule: id => {
    const item = findDevice(id);
    if (!item) return;
    openDialog("Add a schedule", `<p class="schedule-target">${esc(item.name)}</p>
      ${selectField("day", "Day", ["Every day", "Weekdays", "Weekends", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"])}
      ${field("start", "Start", "21:00", "required type=\"time\"")}${field("end", "End", "22:00", "required type=\"time\"")}`,
    "Save schedule", data => {
      if (data.get("start") === data.get("end")) return formError("Choose different start and end times.");
      profile().schedules.push({ device: id, ...Object.fromEntries(data) });
      commitChange("Schedule saved");
    });
  },
  "remove-schedule": key => confirm("Remove this schedule?", "The selected schedule will be cleared from the demo.", "Remove", () => {
    profile().schedules.splice(Number(key), 1);
    commitChange("Schedule removed");
  }),
  protection: () => {
    const current = profile().protection;
    const verb = current ? "Pause" : "Enable";
    confirm(`${verb} protection?`, `${verb} DNS filtering in the demo.`, verb, () => {
      profile().protection = !current;
      commitChange(current ? "Protection paused" : "Protection enabled");
    });
  },
  "query-toggle": id => {
    const item = queries.find(q => q.id === id);
    const block = queryStatus(item) !== "Blocked";
    confirm(`${block ? "Block" : "Allow"} this domain?`, item.domain, block ? "Block domain" : "Allow domain", () => {
      profile().queries[id] = block ? "Blocked" : "Allowed";
      commitChange(block ? "Domain blocked" : "Domain allowed");
    });
  },
  "adguard-setting": key => {
    const [id, label] = key.split("|");
    const page = adGuardPages.find(item => item[0] === id);
    const settings = profile().settings[`ag-${id}`] ??= {};
    const value = settings[label] ?? page[3].find(item => item[0] === label)[1];
    openDialog(`Edit ${label.toLowerCase()}`, field("value", label, value, "required maxlength=\"64\""), "Save", data => {
      const newValue = data.get("value").trim();
      if (!newValue) return formError("Enter a value.");
      settings[label] = newValue;
      commitChange(`${label} saved`);
    });
  },
  service: name => {
    const active = profile().services[name] !== false;
    const description = serviceNames.find(([id]) => id === name)?.[1];
    const footer = active
      ? `<button class="button danger" type="submit" name="operation" value="stop">Stop</button>
         <button class="button primary" type="submit" name="operation" value="restart">Restart</button>`
      : `<button class="button primary" type="submit" name="operation" value="start">Start</button>`;
    openDialog(name, `<div class="service-status"><span>${esc(description)}</span>${badge(active ? "Running" : "Stopped", active ? "" : "neutral")}</div>`,
      null, data => {
      const operation = data.get("operation");
      if (!operation) return false;
      profile().services[name] = operation !== "stop";
      const result = { stop: "stopped", start: "started", restart: "restarted" };
      commitChange(`${name} ${result[operation]}`);
    }, footer);
  },
  guest: () => {
    const on = profile().guest;
    confirm(`Turn guest network ${on ? "off" : "on"}?`, "This change applies to the current demo router.", on ? "Turn off" : "Turn on", () => {
      profile().guest = !on;
      commitChange(`Guest network turned ${on ? "off" : "on"}`);
    });
  },
  join: name => confirm(`Join ${name}?`, "This simulates joining a sample upstream network.", "Join network", () => {
    profile().settings.upstream = name;
    commitChange(`Joined ${name} in the demo`);
  }),
  diagnostic: name => openDialog(name, field("host", "Host", "example.com", "required maxlength=\"80\""), "Run sample check", data => {
    const host = data.get("host").trim();
    if (!host) return formError("Enter a host.");
    const result = { Ping: "4 packets received · Average round trip 12 ms", Traceroute: "Destination reached in 4 hops", "DNS lookup": "Sample address 203.0.113.20" };
    profile().settings.check = { name, host, result: result[name] };
    storeState();
    render();
  }),
  reset: () => confirm("Reset demo data?", "This clears demo changes for both routers in this browser.", "Reset", () => {
    state = defaultState();
    trafficMode = "split";
    chartRange = "1 min";
    for (const key of Object.keys(filters)) filters[key] = key === "sort" ? "name" : key === "deviceKind" || key === "queryStatus" ? "all" : "";
    storeState();
    render();
    showToast("Demo data reset");
  })
};

for (const element of document.querySelectorAll("[data-icon]")) element.innerHTML = icon(element.dataset.icon);
document.querySelector(".skip").addEventListener("click", event => {
  event.preventDefault();
  main.focus();
});
document.addEventListener("click", event => {
  const target = event.target.closest("button[data-action], button[data-go], button[data-back]");
  if (!target) return;
  if (target.dataset.go) {
    if (dialog.open) dialog.close();
    navigate(target.dataset.go);
  } else if (target.dataset.back) {
    goBack(target.dataset.back);
  } else {
    actions[target.dataset.action]?.(target.dataset.key);
  }
});
document.addEventListener("input", event => {
  const { id, value } = event.target;
  if (id === "device-search") {
    filters.devices = value;
    document.querySelector("#device-results").innerHTML = deviceList();
  } else if (id === "parental-search") {
    filters.parental = value;
    document.querySelector("#parental-results").innerHTML = parentalList();
  } else if (id === "feature-search") {
    filters.more = value;
    document.querySelector("#feature-results").innerHTML = featureList();
    document.querySelector("#frequent-features").hidden = Boolean(value.trim());
  } else if (id === "query-search") {
    filters.queries = value;
    document.querySelector("#query-results").innerHTML = queryList();
  }
});
document.addEventListener("change", event => {
  const { id, value } = event.target;
  if (id === "device-sort") {
    filters.sort = value;
    document.querySelector("#device-results").innerHTML = deviceList();
  } else if (id === "query-status") {
    filters.queryStatus = value;
    document.querySelector("#query-results").innerHTML = queryList();
  } else if (id === "traffic-mode") {
    const scroll = window.scrollY;
    trafficMode = value;
    render();
    window.scrollTo(0, scroll);
    document.querySelector("#traffic-mode").focus({ preventScroll: true });
  }
});
dialog.addEventListener("submit", event => {
  event.preventDefault();
  if (dialogSubmit && dialogSubmit(new FormData(event.target, event.submitter)) !== false) dialog.close();
});
dialog.addEventListener("close", () => { dialogSubmit = undefined; });
window.addEventListener("resize", drawCharts);
window.addEventListener("popstate", () => {
  positions.set(displayedRoute, window.scrollY);
  render();
  window.scrollTo(0, positions.get(currentRoute()) ?? 0);
  main.focus({ preventScroll: true });
});
render();
