const errorEl = document.getElementById("error");
const ageEl = document.getElementById("age");

let lastRefreshAt = 0;

const cell = (text, cls) => {
  const td = document.createElement("td");
  td.textContent = text;
  if (cls) td.className = cls;
  return td;
};

function renderVisits(visits) {
  const body = document.querySelector("#visits tbody");
  body.replaceChildren();
  for (const v of visits) {
    const tr = document.createElement("tr");
    tr.append(
      cell(v.at.replace("T", " ").slice(0, 19)),
      cell(v.site.replace(".damianhoward.com", "")),
      cell(v.path),
      cell([v.city, v.country].filter(Boolean).join(", ") || "?", "dim"),
      cell(v.org || (v.asn ? `AS${v.asn}` : "?"), "dim"),
      cell(`${v.browser} / ${v.os} / ${v.kind.toLowerCase()}`, "dim"),
      cell(v.referrer || "", "dim"),
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
  for (const d of [...r.visitsPerDay].reverse()) {
    total += d.visits;
    if (d.day === todayKey) today = d.visits;
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

async function refresh() {
  try {
    const [visitsRes, rollupsRes] = await Promise.all([
      fetch("/admin/api/visits?limit=100"),
      fetch("/admin/api/rollups"),
    ]);
    const visits = await visitsRes.json();
    const rollups = await rollupsRes.json();
    if (!visitsRes.ok)
      throw new Error(visits.error || `HTTP ${visitsRes.status}`);
    if (!rollupsRes.ok)
      throw new Error(rollups.error || `HTTP ${rollupsRes.status}`);
    renderVisits(visits);
    renderRollups(rollups);
    errorEl.hidden = true;
    lastRefreshAt = Date.now();
    ageEl.textContent = "just now";
  } catch (e) {
    errorEl.textContent = e.message;
    errorEl.hidden = false;
  }
}

setInterval(() => {
  if (!lastRefreshAt) return;
  const age = (Date.now() - lastRefreshAt) / 1000;
  ageEl.textContent = age < 1.5 ? "just now" : `${Math.round(age)}s ago`;
}, 1000);

setInterval(refresh, 30000);
refresh();
