const errorEl = document.getElementById("error");
const ageEl = document.getElementById("age");
const filterMeBtn = document.getElementById("filter-me");
const filterNoiseBtn = document.getElementById("filter-noise");
const filterEngagedBtn = document.getElementById("filter-engaged");

let lastRefreshAt = 0;
let lastVisits = [];

// Persisted across refreshes (the table reloads every 30s) and page loads. Client-side only: the
// dashboard has no ipHash to match on (it's deliberately never sent to the browser -- see
// AdminServer), so "me" is approximated by the one location/network combination that's actually
// mine, not an exact identity match.
const filterState = {
  me: localStorage.getItem("hideMe") === "1",
  noise: localStorage.getItem("hideNoise") === "1",
  engaged: localStorage.getItem("engagedVisitors") === "1",
};

// The most rows the API will return. Enough history to scroll back through a day of traffic; the
// table scrolls inside its panel, so the length costs the page nothing.
const VISIT_LIMIT = 500;

// Mirrors RequestFilter.SCAN_TOKENS on the ingest side deliberately: that filter stops new scan
// hits from ever being stored, but doesn't retroactively clean up ones already recorded, and the
// two lists are allowed to diverge over time as new scan patterns turn up.
const SCAN_TOKENS = [
  "wp-login",
  "wp-admin",
  "wp-content",
  "wp-json",
  "wp-includes",
  "xmlrpc.php",
  "wordpress",
];

const isMine = (v) =>
  (v.city || "").toLowerCase() === "watford" &&
  (v.org || "").toLowerCase().includes("virgin media");

const isScanNoise = (v) =>
  SCAN_TOKENS.some(
    (t) =>
      (v.path || "").toLowerCase().includes(t) ||
      (v.referrer || "").toLowerCase().includes(t),
  );

const cell = (text, cls) => {
  const td = document.createElement("td");
  td.textContent = text;
  if (cls) td.className = cls;
  return td;
};

// Long free-form values (URLs, org names, paths) are ellipsis-truncated so they can never
// force the table wider than its panel; the full value stays available on hover.
const truncCell = (text, cls) => {
  const td = cell(text, cls ? `${cls} trunc` : "trunc");
  td.title = text;
  return td;
};

function renderVisits(visits) {
  const body = document.querySelector("#visits tbody");
  body.replaceChildren();
  const shown = visits.filter(
    (v) =>
      !(filterState.me && isMine(v)) && !(filterState.noise && isScanNoise(v)),
  );
  for (const v of shown) {
    const tr = document.createElement("tr");
    tr.append(
      cell(v.at.replace("T", " ").slice(0, 19)),
      cell(`#${v.visitor}`, "dim"),
      cell(v.site.replace(".damianhoward.com", "")),
      truncCell(v.path),
      truncCell(
        [v.city, v.country].filter(Boolean).join(", ") || "?",
        "dim wide",
      ),
      truncCell(
        [v.org || (v.asn ? `AS${v.asn}` : "?"), v.orgDomain]
          .filter(Boolean)
          .join(" · "),
        "dim wide",
      ),
      cell(`${v.browser} / ${v.os} / ${v.kind.toLowerCase()}`, "dim"),
      truncCell(v.referrer || "", "dim"),
      cell(v.engaged ? "yes" : "", v.engaged ? "pos" : ""),
    );
    body.append(tr);
  }
}

function renderRollups(r) {
  const perDay = document.querySelector("#per-day tbody");
  perDay.replaceChildren();
  let total = 0;
  let today = 0;
  const todayKey = new Date().toISOString().slice(0, 10);
  const days = [...r.visitsPerDay].reverse();
  for (const d of days) {
    total += d.visits;
    if (d.day === todayKey) today = d.visits;
  }
  // The 30-day window feeds the stat tiles; the panel itself lists only the latest days so
  // the summary row cannot grow past the visits table below it.
  for (const d of days.slice(0, 10)) {
    const tr = document.createElement("tr");
    tr.append(
      cell(d.day),
      cell(String(d.visits)),
      cell(String(d.engaged), d.engaged > 0 ? "pos" : "dim"),
    );
    perDay.append(tr);
  }

  const fill = (id, counts) => {
    const body = document.querySelector(`#${id} tbody`);
    body.replaceChildren();
    for (const c of counts) {
      const tr = document.createElement("tr");
      tr.append(cell(c.label), cell(String(c.visits)));
      body.append(tr);
    }
  };
  fill("countries", r.topCountries);
  fill("referrers", r.topReferrers);

  document.getElementById("st-visits").textContent = String(total);
  document.getElementById("st-today").textContent = String(today);
  document.getElementById("st-engaged").textContent =
    `${Math.round(r.engagedRate * 100)}%`;
  document.getElementById("st-country").textContent = r.topCountries.length
    ? r.topCountries[0].label
    : "—";
}

// Toggling the engaged filter refetches while the 30s refresh may already be in flight, and
// whichever answers last would win. Only the newest request is allowed to render.
let latestRequest = 0;

async function refresh() {
  const request = ++latestRequest;
  try {
    // "Engaged visitors" is answered by the server, because it needs the IP hash to tie a
    // visitor's rows together and the hash never reaches the browser. The other two filters
    // stay here and apply on top of whichever list comes back.
    const scope = filterState.engaged ? "&visitors=engaged" : "";
    const [visitsRes, rollupsRes] = await Promise.all([
      fetch(`/admin/api/visits?limit=${VISIT_LIMIT}${scope}`),
      fetch("/admin/api/rollups"),
    ]);
    const visits = await visitsRes.json();
    const rollups = await rollupsRes.json();
    if (request !== latestRequest) return;
    if (!visitsRes.ok)
      throw new Error(visits.error || `HTTP ${visitsRes.status}`);
    if (!rollupsRes.ok)
      throw new Error(rollups.error || `HTTP ${rollupsRes.status}`);
    lastVisits = visits;
    renderVisits(lastVisits);
    renderRollups(rollups);
    errorEl.hidden = true;
    lastRefreshAt = Date.now();
    ageEl.textContent = "just now";
  } catch (e) {
    if (request !== latestRequest) return;
    errorEl.textContent = e.message;
    errorEl.hidden = false;
  }
}

function toggleFilter(key, button, storageKey) {
  filterState[key] = !filterState[key];
  localStorage.setItem(storageKey, filterState[key] ? "1" : "0");
  button.setAttribute("aria-pressed", String(filterState[key]));
}

filterMeBtn.setAttribute("aria-pressed", String(filterState.me));
filterNoiseBtn.setAttribute("aria-pressed", String(filterState.noise));
filterEngagedBtn.setAttribute("aria-pressed", String(filterState.engaged));
filterMeBtn.onclick = () => {
  toggleFilter("me", filterMeBtn, "hideMe");
  renderVisits(lastVisits);
};
filterNoiseBtn.onclick = () => {
  toggleFilter("noise", filterNoiseBtn, "hideNoise");
  renderVisits(lastVisits);
};
// A different list, not a different view of this one, so it refetches.
filterEngagedBtn.onclick = () => {
  toggleFilter("engaged", filterEngagedBtn, "engagedVisitors");
  refresh();
};

setInterval(() => {
  if (!lastRefreshAt) return;
  const age = (Date.now() - lastRefreshAt) / 1000;
  ageEl.textContent = age < 1.5 ? "just now" : `${Math.round(age)}s ago`;
}, 1000);

setInterval(refresh, 30000);
refresh();
