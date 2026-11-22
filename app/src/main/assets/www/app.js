const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const svgIcon = (id) => `<svg class="icon" aria-hidden="true"><use href="#${id}" /></svg>`;
const finePointer = matchMedia("(pointer: fine)");

let MODELS = {
  llama: { name: "Llama 3.2 3B Instruct", short: "Llama 3.2 3B", backend: "llama.cpp", mono: "GG", size: "2.4 GB", memory: "2.4 / 6 GB", memoryPct: 40, tps: 64, state: "Loaded" },
  onnx: { name: "Phi-3 Mini", short: "Phi-3 Mini", backend: "ONNX Runtime", mono: "OX", size: "1.8 GB", memory: "1.8 / 6 GB", memoryPct: 30, tps: 52, state: "Ready" },
  coreml: { name: "Core ML small model", short: "Core ML", backend: "Core ML", mono: "ML", size: "1.1 GB", unavailable: "Needs the iOS adapter" },
};
const REMOTE_TPS = 88;

/* Live mode: inside the Android app the SDK bridge is present. In a browser preview there is no
   bridge, and replies come from simulate() with the same event shapes. */
const bridge = window.Tune2AIBridge || null;
const live = Boolean(bridge);
const NO_MODEL = { name: "No model yet", short: "No model", backend: "llama.cpp", mono: "—", size: "", memory: "—", memoryPct: 0, tps: 0 };
const currentModel = () => {
  if (live && state.execution === "remote") {
    const r = state.remoteModel;
    return r
      ? { ...NO_MODEL, name: r.name, short: r.name.length > 18 ? `${r.name.slice(0, 17)}…` : r.name, backend: "Remote API", mono: "API" }
      : { ...NO_MODEL, name: "No remote model", short: "No remote model", backend: "Remote API" };
  }
  return MODELS[state.model] || NO_MODEL;
};
const formatBytes = (n) => (n >= 1e9 ? `${(n / 1e9).toFixed(1)} GB` : `${Math.round(n / 1e6)} MB`);

const MODES = {
  Chat: {
    title: "Ask anything.",
    copy: "Private by design. Every request runs through the same path your app will use.",
    placeholder: "Message Tune2AI…",
    suggestions: ["How does on-device inference protect user data?", "When does my Lisbon flight leave?", "Compare 3B and 8B models on a mid-range phone"],
    reply: (c) => c.remote
      ? `This request went to the **Remote API** backend over HTTPS and streamed back as SSE events.\n\n- Routing, context assembly, and events are **identical** to an on-device run\n- Switching back is one setting\n\nUse remote for large models or long contexts; keep on-device as the default for privacy and offline use.`
      : `**Prompts and outputs never leave this phone.** ${c.backend} runs ${c.model} locally, so:\n\n- There is **no server** that could log your data\n- Replies keep working **offline**\n- Nothing is shared with a third party\n\n### The trade-off\n\n| | On-device | Remote |\n|---|---|---|\n| Privacy | Stays on the phone | Sent over HTTPS |\n| Works offline | Yes | No |\n| Model size | Up to ~3B | Any |\n\nThe Model Manager checks free memory before loading and unloads idle models under pressure, so your app stays responsive.\n\nWant me to draft the privacy note for your release?`,
  },
  Agent: {
    title: "Give the agent a task.",
    copy: "It looks things up in your sources, messages, and calendar, then prepares actions like a text or an event. Nothing is sent until you tap it.",
    placeholder: "Describe a task for the agent…",
    suggestions: ["Find my flight details in my sources and add them to my calendar", "Summarize today's calendar and my latest messages", "Text Mom that I'll be 10 minutes late"],
    reply: () => `Done in 3 of 6 allowed steps.\n\n1 · resource_monitor → 3.6 GB free, thermal nominal\n2 · list_models → 1 loaded, 1 ready\n3 · Drafted the plan below\n\nLoading a second model fits now, but unload it when the app goes to the background. Every tool input was validated against its schema before it ran.`,
  },
  Workflow: {
    title: "Chain steps into one run.",
    copy: "Workflows checkpoint after each step, so a run can resume after the app restarts.",
    placeholder: "Describe the workflow input…",
    suggestions: ["Summarize → classify → draft a reply to a support ticket", "Translate, check tone, then shorten", "Extract action items from meeting notes"],
    reply: () => `Workflow finished: 3 of 3 steps.\n\nSummarize → 2 sentences\nClassify → "billing question"\nDraft reply → 86 words, friendly tone\n\nEach step was checkpointed to local storage, so this run could resume after an app restart without repeating completed steps.`,
  },
};

const state = { chat: null, view: "chat", mode: "Chat", execution: "local", model: "llama", memory: true, run: null, hasRun: false, sources: null };
const els = {
  conversation: $("#conversation"),
  empty: $("#empty-state"),
  prompt: $("#prompt"),
  send: $("#send"),
  timeline: $("#timeline"),
  toast: $("#toast"),
  scrim: $("#scrim"),
};

/* ---------- Toast ---------- */
let toastTimer;
function toast(message) {
  els.toast.textContent = message;
  els.toast.classList.add("is-visible");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => els.toast.classList.remove("is-visible"), 2800);
}
document.addEventListener("click", (event) => {
  const soon = event.target.closest("[data-soon]");
  if (soon) toast(soon.dataset.soon);
});

/* ---------- Sheets: bottom sheet on phone, drawer on tablet, column on desktop ----------
   Each open sheet pushes a history entry so the Android back button closes it. */
let openSheetId = null;
let sheetOpener = null;

function openSheet(id) {
  if (openSheetId) closeSheet(false);
  openSheetId = id;
  sheetOpener = document.activeElement;
  const sheet = $(`#${id}`);
  sheet.classList.add("is-open");
  els.scrim.hidden = false;
  history.pushState({ sheet: id }, "");
  const title = $("h2", sheet);
  title.tabIndex = -1;
  title.focus({ preventScroll: true });
  if (id === "trace") $("#trace-dot").hidden = true;
  if (id === "setup" && device) loadDevice(); // fresh free memory and storage
}

function closeSheet(popHistory = true) {
  if (!openSheetId) return;
  $(`#${openSheetId}`).classList.remove("is-open");
  els.scrim.hidden = true;
  openSheetId = null;
  sheetOpener?.focus({ preventScroll: true });
  if (popHistory && history.state?.sheet) history.back();
}

window.addEventListener("popstate", () => closeSheet(false));
document.addEventListener("click", (event) => {
  const opener = event.target.closest("[data-open]");
  if (opener) openSheet(opener.dataset.open);
  if (event.target.closest("[data-close]") || event.target === els.scrim) closeSheet();
});
document.addEventListener("keydown", (event) => {
  if (event.key === "Escape") closeSheet();
});

/* ---------- Setup ---------- */
function renderModels() {
  const empty = live && Object.keys(MODELS).length === 0
    ? `<p class="hint">No models yet. Import a GGUF file, for example SmolLM2 or Qwen2.5 Instruct.</p>` : "";
  const importButton = live ? `<div class="button-row"><button class="button" type="button" data-import>${svgIcon("i-plus")}Import a .gguf model</button></div>` : "";
  $("#model-list").innerHTML = empty + Object.entries(MODELS).map(([key, m]) => `
    <label class="model-option">
      <input type="radio" name="model" value="${key}" ${key === state.model ? "checked" : ""} ${m.unavailable ? "disabled" : ""} />
      <span class="mono-tile" aria-hidden="true">${m.mono}</span>
      <span class="model-text"><strong>${m.name}</strong><small>${m.backend} · ${m.size}${m.unavailable ? ` · ${m.unavailable}` : ""}</small></span>
      <span class="model-state" data-state="${(m.state || "").toLowerCase()}">${m.unavailable ? "Unavailable" : m.state}</span>
      <span class="radio-mark" aria-hidden="true">${svgIcon("i-check")}</span>
    </label>`).join("") + importButton;
}

document.addEventListener("click", (event) => {
  if (event.target.closest("[data-import]")) bridge?.importModel();
});

/* ---------- Models for this phone: specs, recommendation, downloads ----------
   The bridge reads the phone's specs and rates each catalog model (ModelAdvisor.java). In a
   browser preview there is no bridge, so a sample phone and a simulated download stand in. */
const PREVIEW_DEVICE = {
  device: { name: "Sample phone", chip: "Snapdragon 8 Gen 2", cores: 8, android: "15", totalRamBytes: 7.4e9, availableRamBytes: 3.1e9, freeStorageBytes: 52e9, supported: true },
  models: [
    { id: "smollm2-135m-instruct-q8_0", name: "SmolLM2 135M", sizeBytes: 144811360, blurb: "Tiny and fast. Good for testing, weak answers.", fit: "fits", reason: "Uses about 427 MB of memory.", installed: false },
    { id: "smollm2-360m-instruct-q4_k_m", name: "SmolLM2 360M", sizeBytes: 270590880, blurb: "Small and quick on older phones.", fit: "fits", reason: "Uses about 566 MB of memory.", installed: false },
    { id: "qwen2.5-0.5b-instruct-q4_k_m", name: "Qwen2.5 0.5B", sizeBytes: 491400032, blurb: "Smallest model that handles Agent tools.", fit: "fits", reason: "Uses about 809 MB of memory.", installed: false },
    { id: "llama-3.2-1b-instruct-q4_k_m", name: "Llama 3.2 1B", sizeBytes: 807694464, blurb: "Balanced quality and speed.", fit: "fits", reason: "Uses about 1.2 GB of memory.", installed: false },
    { id: "qwen2.5-1.5b-instruct-q4_k_m", name: "Qwen2.5 1.5B", sizeBytes: 1117320736, blurb: "Better answers and tool use.", fit: "fits", reason: "Uses about 1.5 GB of memory.", installed: false },
    { id: "llama-3.2-3b-instruct-q4_k_m", name: "Llama 3.2 3B", sizeBytes: 2019377696, blurb: "Best quality here. Needs a high-end phone.", fit: "best", reason: "Largest model that runs safely on this phone. Uses about 2.5 GB of memory.", installed: true },
  ],
};
let device = null;
const downloads = {}; // id -> { received, total }

const escapeHtml = (text) => String(text).replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
const gb = (n) => (n >= 1e9 ? `${(n / 1e9).toFixed(1)} GB` : `${Math.round(n / 1e6)} MB`);
const bestModel = () => device?.models.find((m) => m.fit === "best") || null;

function loadDevice() {
  device = live ? JSON.parse(bridge.deviceInfo()) : PREVIEW_DEVICE;
  device.models.forEach((m) => { if (m.downloading && !downloads[m.id]) downloads[m.id] = { received: 0, total: m.sizeBytes }; });
  renderCatalog();
}

function renderCatalog() {
  if (!device) return;
  const d = device.device;
  $("#device-summary").innerHTML = `
    <strong>${escapeHtml(d.name)}</strong>
    <small>${escapeHtml([d.chip, `${d.cores} cores`, `Android ${d.android}`].filter(Boolean).join(" · "))}</small>
    <div class="spec-row">
      <span><b>${gb(d.totalRamBytes)}</b>RAM</span>
      <span><b>${gb(d.availableRamBytes)}</b>Free memory</span>
      <span><b>${gb(d.freeStorageBytes)}</b>Free storage</span>
    </div>`;
  const busy = Object.keys(downloads).length > 0;
  const ordered = [...device.models].sort((a, b) => (b.fit === "best") - (a.fit === "best")); // best first, then by size
  $("#catalog").innerHTML = ordered.map((m) => {
    const dl = downloads[m.id];
    const ok = m.fit === "best" || m.fit === "fits";
    let action;
    if (m.installed) action = `<span class="model-state" data-state="loaded">Installed</span>`;
    else if (dl) action = `<button class="icon-button cancel" type="button" data-cancel-download aria-label="Cancel download">${svgIcon("i-close")}</button>`;
    else if (ok) action = `<button class="pill-button" type="button" data-download="${m.id}" ${busy ? "disabled" : ""}>${svgIcon("i-download")}Get</button>`;
    else action = `<span class="model-state">${m.fit === "no-storage" ? "No space" : m.fit === "unsupported" ? "Unsupported" : "Too large"}</span>`;
    const pct = dl ? Math.floor((dl.received / dl.total) * 100) : 0;
    return `
      <div class="catalog-row" data-fit="${m.fit}">
        <div class="model-text">
          <strong>${escapeHtml(m.name)}${m.fit === "best" ? '<span class="best-badge">Best for this phone</span>' : ""}</strong>
          <small>${escapeHtml(dl ? `${gb(dl.received)} of ${gb(dl.total)} · ${pct}%` : `${gb(m.sizeBytes)} · ${ok ? m.blurb : m.reason}`)}</small>
          ${dl ? `<div class="meter"><span style="width:${pct}%"></span></div>` : ""}
        </div>
        ${action}
      </div>`;
  }).join("");
  $("#catalog-group").hidden = state.execution === "remote";
}

function startDownload(id) {
  if (Object.keys(downloads).length) return;
  const m = device.models.find((x) => x.id === id) || knowledge?.embedders?.find((x) => x.id === id) || null;
  if (!m) return;
  downloads[id] = { received: 0, total: m.sizeBytes };
  renderCatalog();
  renderMode();
  if (live) bridge.downloadModel(id);
  else previewDownload(m);
}

/* Browser preview only: pretend to download in about four seconds. */
let previewTimer = null;
function previewDownload(m) {
  let received = 0;
  previewTimer = setInterval(() => {
    received = Math.min(m.sizeBytes, received + m.sizeBytes / 16);
    onDownload({ id: m.id, state: received >= m.sizeBytes ? "done" : "running", received, total: m.sizeBytes });
  }, 250);
}

function onDownload(e) {
  if (e.state === "running") {
    downloads[e.id] = { received: e.received, total: e.total };
  } else {
    delete downloads[e.id];
    clearInterval(previewTimer);
    if (e.state === "done") {
      const embedder = !!knowledge?.embedders?.some((x) => x.id === e.id);
      if (live) {
        if (!embedder) state.model = e.id;
        loadLiveModels();
      } else if (embedder) {
        Object.assign(knowledge, { embeddingModel: e.id });
        knowledge.embedders.forEach((x) => { x.active = x.id === e.id; if (x.id === e.id) x.installed = true; });
        knowledge.sources.forEach((src) => { src.smart = true; });
        toast(`${knowledge.embedders.find((x) => x.id === e.id).name} is ready. Search now matches by meaning.`);
      } else {
        device.models.find((m) => m.id === e.id).installed = true;
        toast(`${device.models.find((m) => m.id === e.id).name} is ready.`);
      }
    }
  }
  if (e.state !== "running" && live) loadDevice();
  else renderCatalog();
  renderMode();
  if (live && e.state !== "running") loadKnowledge();
  else renderKnowledge();
}

document.addEventListener("click", (event) => {
  const get = event.target.closest("[data-download]");
  if (get) startDownload(get.dataset.download);
  if (event.target.closest("[data-cancel-download]")) {
    if (live) bridge.cancelDownload();
    else Object.keys(downloads).forEach((id) => onDownload({ id, state: "cancelled" }));
  }
});

/* ---------- Views: Chat and Sources ---------- */
function showView(view) {
  state.view = view;
  $$(".view-tabs [role=tab]").forEach((tab) => tab.setAttribute("aria-selected", String(tab.dataset.view === view)));
  $("#chat-view").hidden = view !== "chat";
  $("#sources-view").hidden = view !== "sources";
  $("#connections-view").hidden = view !== "connections";
  if (view === "sources") loadKnowledge();
  if (view === "connections") loadConnections();
}
$$(".view-tabs [role=tab]").forEach((tab) => tab.addEventListener("click", () => showView(tab.dataset.view)));

/* ---------- Settings: answer style, system prompt, privacy guard, chunking ---------- */
const DEFAULT_PROMPT = "You are a helpful assistant running privately on the user's phone. It is now {{today}}. Answer accurately and get to the point. Format replies in Markdown when it helps: short paragraphs, bullet or numbered lists for steps and options, **bold** for key facts, a table for comparisons, and fenced code blocks for code. Skip headings in short answers. If you are not sure, say so.";
const PROMPT_PRESETS = {
  balanced: DEFAULT_PROMPT,
  grounded: "You are a careful assistant running privately on the user's phone. It is now {{today}}.\n\nWhen passages or phone data are provided, answer only from them and cite each passage you use like [1]. If they do not contain the answer, say you could not find it in their data instead of guessing. Copy names, numbers, amounts, and dates exactly as written. Use a short Markdown list when there are several items.",
  concise: "You are a helpful assistant running privately on the user's phone. It is now {{today}}.\n\nAnswer in at most three sentences or a short bullet list. No preamble and no closing offers. Use provided passages when relevant and cite them like [1]. Say so briefly when you are not sure.",
  detailed: "You are a patient expert assistant running privately on the user's phone. It is now {{today}}.\n\nExplain step by step. Use Markdown headings for longer answers, numbered steps for procedures, tables for comparisons, and fenced code blocks for code. When passages are provided, ground the answer in them and cite like [1]. End with a one-line summary.",
};
const CHUNKING_INFO = {
  auto: ["Auto (recommended)", "Small documents stay whole. Large ones split by section into parents (~300 tokens) with small children (~80): search matches a child, the model reads the whole parent."],
  smart: ["Smart", "Paragraphs and sentences, about 200 tokens with a sentence of overlap."],
  precise: ["Precise", "Small passages (about 100 tokens) for exact facts: numbers, names, dates."],
  broad: ["Broad", "Large passages (about 380 tokens) for explanations that need context."],
  sections: ["By section", "One passage per heading or PDF page. Best for structured notes and manuals."],
  fixed: ["Fixed size", "Equal word windows that ignore sentences. Good for logs and CSV."],
};
let settings = null;

function loadSettings() {
  settings = live ? JSON.parse(bridge.settings())
    : settings || { prompt: DEFAULT_PROMPT, defaultPrompt: DEFAULT_PROMPT, customPrompt: false, promptVersion: 5, redact: true, remoteContext: false, chunking: "auto", neighbors: false };
  $("#system-prompt").value = settings.prompt;
  markPreset();
  $("#guard-redact").checked = settings.redact;
  $("#guard-remote").checked = settings.remoteContext;
  $("#prompt-hint").textContent = `${settings.customPrompt ? `Your prompt (v${settings.promptVersion})` : "Built-in prompt"}. Use {{today}} for the current date and time. Changes apply from your next message.`;
}

function markPreset() {
  const text = $("#system-prompt").value.trim();
  $$("[data-prompt-preset]").forEach((b) => b.classList.toggle("is-on", PROMPT_PRESETS[b.dataset.promptPreset].trim() === text));
}

function saveSystemPrompt(text) {
  const error = live ? bridge.setSystemPrompt(text) : (/\{\{\s*(?!today\b)\w+\s*\}\}/.test(text) ? "Only {{today}} can be used as a placeholder." : "");
  if (error) return toast(error);
  if (!live) Object.assign(settings, { prompt: text.trim() || DEFAULT_PROMPT, customPrompt: !!text.trim() && text.trim() !== DEFAULT_PROMPT, promptVersion: settings.promptVersion < 100 ? 100 : settings.promptVersion + 1 });
  loadSettings();
  renderRuntime();
  toast(text.trim() ? "Prompt saved. It applies from your next message." : "Built-in prompt restored.");
}

$("#system-prompt").addEventListener("input", markPreset);
document.addEventListener("click", (event) => {
  const preset = event.target.closest("[data-prompt-preset]")?.dataset.promptPreset;
  if (preset) { $("#system-prompt").value = PROMPT_PRESETS[preset]; markPreset(); }
  if (event.target.closest("[data-prompt-save]")) saveSystemPrompt($("#system-prompt").value);
  if (event.target.closest("[data-prompt-reset]")) saveSystemPrompt("");
  if (event.target.closest("[data-rechunk]")) {
    if (live) bridge.rechunkKnowledge();
    else { knowledge.staleChunks = 0; renderKnowledge(); toast("Documents re-split."); }
  }
});
$$('input[name="answer-style"]').forEach((r) => r.addEventListener("change", () => {
  $("#temperature").value = r.value;
  $("#temperature").dispatchEvent(new Event("input"));
}));
$("#temperature").addEventListener("input", () => {
  const t = Number($("#temperature").value);
  $$('input[name="answer-style"]').forEach((r) => { r.checked = Math.abs(Number(r.value) - t) < 0.05; });
});
["#guard-redact", "#guard-remote"].forEach((id) => $(id).addEventListener("change", () => {
  settings.redact = $("#guard-redact").checked;
  settings.remoteContext = $("#guard-remote").checked;
  bridge?.setPrivacy(settings.redact, settings.remoteContext);
  if (!settings.redact) toast("Sensitive values will now reach the model. Turn the guard back on when you are done.");
}));

function renderChunking(k) {
  const current = k.chunking || "auto";
  $("#chunking-list").innerHTML = Object.entries(CHUNKING_INFO).map(([id, [name, about]]) => `
    <label class="choice${id === current ? " is-on" : ""}">
      <input type="radio" name="chunking" value="${id}" ${id === current ? "checked" : ""} />
      <span><strong>${name}</strong><small>${about}</small></span>
    </label>`).join("");
  $("#neighbors-switch").checked = !!k.neighbors;
  const stale = k.staleChunks || 0;
  $("#chunking-apply").hidden = !stale || !!k.indexing;
  $("#chunking-apply-text").textContent = `${stale} document${stale === 1 ? " was" : "s were"} split another way. Apply re-splits ${stale === 1 ? "it" : "them"} (and re-embeds), which can take a while.`;
}

function saveChunking() {
  const id = $('input[name="chunking"]:checked')?.value || "auto";
  const neighbors = $("#neighbors-switch").checked;
  if (live) bridge.setChunking(id, neighbors);
  else { Object.assign(knowledge, { chunking: id, neighbors, staleChunks: id === "auto" ? 0 : knowledge.sources.length }); renderKnowledge(); }
}
$("#chunking-list").addEventListener("change", saveChunking);
$("#neighbors-switch").addEventListener("change", saveChunking);

/* ---------- Connections: phone data (storage, messages, calendar, contacts, apps) as context ----------
   Each connection is read by StudioBridge/Connections.java and indexed into the same knowledge index
   as Sources, so chat retrieves from it the same way. */
const CONNECTION_GROUPS = [
  { id: "phone", title: "On this phone" },
  { id: "apps", title: "Other apps" },
  { id: "google", title: "Google" },
];
const PREVIEW_CONNECTIONS = {
  sdk: 35,
  indexing: null,
  connections: [
    { id: "storage", name: "Internal storage", description: "Documents, notes, PDFs, and other text files anywhere in the phone's storage, including Download.", icon: "i-folder", group: "phone", granted: true, enabled: true, settings: true, lastSync: Date.now() - 18e5, items: 214 },
    { id: "notifications", name: "App notifications", description: "New messages from WhatsApp, Gmail, Slack, Telegram, and any other app, as they arrive.", icon: "i-bell", group: "apps", granted: false, enabled: false, settings: true, lastSync: 0, items: 0 },
    { id: "sms", name: "Messages", description: "Your SMS conversations, newest first.", icon: "i-message", group: "phone", granted: false, enabled: false, settings: false, lastSync: 0, items: 0 },
    { id: "calendar", name: "Calendar", description: "Events from 30 days ago to 90 days ahead, including Google Calendar.", icon: "i-calendar", group: "google", granted: true, enabled: true, settings: false, lastSync: Date.now() - 3e5, items: 4 },
    { id: "contacts", name: "Contacts", description: "Names, numbers, emails, and companies, including Google Contacts.", icon: "i-person", group: "google", granted: false, enabled: false, settings: false, lastSync: 0, items: 0 },
    { id: "calls", name: "Call history", description: "Recent calls with who, when, and how long.", icon: "i-phone", group: "phone", granted: false, enabled: false, settings: false, lastSync: 0, items: 0 },
    { id: "apps", name: "Installed apps", description: "Which apps are on this phone and when they were installed.", icon: "i-grid", group: "phone", granted: true, enabled: false, settings: false, lastSync: 0, items: 0 },
  ],
};
let connections = null;

function loadConnections() {
  connections = live ? JSON.parse(bridge.connectionsStatus()) : connections || structuredClone(PREVIEW_CONNECTIONS);
  renderConnections();
}

function connectionRow(c) {
  if (c.available === false) {
    return `
    <div class="list-row conn-row is-unavailable">
      <span class="row-icon">${svgIcon(c.icon)}</span>
      <span class="model-text"><strong>${escapeHtml(c.name)}</strong><small>Only in the full build (install with adb). Play Protect blocks this permission in apps installed from a browser or file manager.</small></span>
    </div>`;
  }
  const on = c.enabled && c.granted;
  const empty = on && c.lastSync && !c.items && !connections.indexing;
  const status = empty && c.id === "sms"
    ? `<span class="conn-status is-warn">Connected, but no SMS found</span> · On Xiaomi, Redmi, and POCO phones also allow it: App info → Permissions → Other permissions → Read SMS, then tap refresh.`
    : on
    ? `<span class="conn-status is-on">Connected</span> · ${c.items} ${c.id === "storage" ? "files" : "items"}${c.lastSync ? ` · synced ${ago(c.lastSync)}` : ""}`
    : c.enabled && !c.granted ? `<span class="conn-status is-warn">Permission was removed</span>` : escapeHtml(c.description);
  const busy = !!connections.indexing;
  const actions = on
    ? `<button class="icon-button small" type="button" data-sync="${c.id}" aria-label="Sync ${escapeHtml(c.name)} now" ${busy ? "disabled" : ""}>${svgIcon("i-refresh")}</button>
       <button class="icon-button small" type="button" data-disconnect="${c.id}" aria-label="Disconnect ${escapeHtml(c.name)}">${svgIcon("i-close")}</button>`
    : `<button class="pill-button" type="button" data-connect="${c.id}" ${busy ? "disabled" : ""}>${c.settings && !c.granted ? "Allow" : "Connect"}</button>`;
  return `
    <div class="list-row conn-row${on ? " is-on" : ""}">
      <span class="row-icon">${svgIcon(c.icon)}</span>
      <span class="model-text"><strong>${escapeHtml(c.name)}</strong><small>${status}</small></span>
      ${actions}
    </div>`;
}

function renderConnections() {
  if (!connections) return;
  const ix = connections.indexing;
  $("#connections-progress").innerHTML = ix ? `
    <div class="card progress-card">
      <div class="progress-head"><strong>${escapeHtml(ix.label)}…</strong><span>${ix.total ? `${ix.done} of ${ix.total}` : ""}</span></div>
      <div class="meter"><span style="width:${ix.total ? (ix.done / ix.total) * 100 : 5}%"></span></div>
      ${ix.current ? `<small>${escapeHtml(ix.current)}</small>` : ""}
    </div>` : "";
  const extra = {
    google: `
      <div class="list-row conn-note">
        <span class="row-icon">${svgIcon("i-file")}</span>
        <span class="model-text"><strong>Google Drive</strong><small>Sources → Files or Folder, then choose Drive in the picker.</small></span>
        <button class="pill-button" type="button" data-goto-view="sources">Open</button>
      </div>
      <div class="list-row conn-note">
        <span class="row-icon">${svgIcon("i-message")}</span>
        <span class="model-text"><strong>Gmail</strong><small>New mail arrives through App notifications; share a whole email with Share → Tune2AI Studio.</small></span>
      </div>`,
    apps: `
      <div class="list-row conn-note">
        <span class="row-icon">${svgIcon("i-share")}</span>
        <span class="model-text"><strong>Anything else</strong><small>In any app, tap Share → Tune2AI Studio to add a page, note, or file.</small></span>
      </div>`,
  };
  $("#connections-groups").innerHTML = CONNECTION_GROUPS.map((g) => `
    <h3 class="label">${g.title}</h3>
    <div class="card list">${connections.connections.filter((c) => c.group === g.id).map(connectionRow).join("")}${extra[g.id] || ""}</div>`).join("");
  const n = connections.connections.filter((c) => c.available !== false && c.enabled && c.granted).length;
  const count = $("#connections-count");
  count.hidden = !n;
  count.textContent = n;
}

/* Preview only: pretend to grant access and sync. */
function previewConnect(id) {
  const c = connections.connections.find((x) => x.id === id);
  connections.indexing = { label: `Syncing ${c.name.toLowerCase()}`, done: 0, total: 0, current: "Reading…" };
  renderConnections();
  setTimeout(() => {
    Object.assign(c, { granted: true, enabled: true, lastSync: Date.now(), items: { sms: 38, contacts: 1, calls: 1, apps: 1, notifications: 6 }[id] || 12 });
    connections.indexing = null;
    renderConnections();
    toast(`${c.name} connected.`);
  }, 900);
}

document.addEventListener("click", (event) => {
  const connect = event.target.closest("[data-connect]")?.dataset.connect;
  const disconnect = event.target.closest("[data-disconnect]")?.dataset.disconnect;
  const sync = event.target.closest("[data-sync]")?.dataset.sync;
  const gotoView = event.target.closest("[data-goto-view]")?.dataset.gotoView;
  if (connect) live ? bridge.connect(connect) : previewConnect(connect);
  if (sync) live ? bridge.syncConnection(sync) : previewConnect(sync);
  if (disconnect) {
    if (live) bridge.disconnect(disconnect);
    else { Object.assign(connections.connections.find((x) => x.id === disconnect), { enabled: false, items: 0, lastSync: 0 }); renderConnections(); }
  }
  if (gotoView) showView(gotoView);
});

/* ---------- Sources: files and apps the model can search (RAG through the SDK's knowledge API) ----------
   In a browser preview there is no bridge; a sample index stands in. */
const PREVIEW_KNOWLEDGE = {
  enabled: true,
  chunking: "auto",
  neighbors: false,
  staleChunks: 0,
  passagesPerAnswer: 3,
  minSimilarity: 0.55,
  embeddingModel: null,
  embedder: { id: "bge-small-en-v1.5-q8_0", name: "BGE small", sizeBytes: 36806944, installed: false, downloading: false },
  embedders: [
    { id: "bge-small-en-v1.5-q8_0", name: "BGE small", blurb: "Fastest. English. 384-dimension vectors.", sizeBytes: 36806944, installed: false, active: false, recommended: false },
    { id: "nomic-embed-text-v1.5.q8_0", name: "Nomic Embed v1.5", blurb: "More accurate English search; reads longer passages.", sizeBytes: 146146432, installed: false, active: false, recommended: false },
    { id: "embeddinggemma-300m-q8_0", name: "EmbeddingGemma 300M", blurb: "Best quality. 100+ languages, including Hindi, Telugu, and Tamil.", sizeBytes: 333590944, installed: false, active: false, recommended: true },
  ],
  indexing: null,
  folders: [{ uri: "preview:Documents", name: "Documents", files: 2 }],
  sources: [
    { id: "preview:trip", name: "Lisbon trip.md", kind: "file", path: "Travel/Lisbon trip.md", inFolder: true, chunks: 4, characters: 3120, addedAt: Date.now() - 36e5, smart: false },
    { id: "preview:rota", name: "On-call rota.csv", kind: "file", path: "Work/On-call rota.csv", inFolder: true, chunks: 2, characters: 1410, addedAt: Date.now() - 72e5, smart: false },
    { id: "preview:wifi", name: "Home Wi-Fi", kind: "note", path: "", inFolder: false, chunks: 1, characters: 96, addedAt: Date.now() - 864e5, smart: false },
    { id: "preview:article", name: "How llama.cpp quantization works", kind: "shared", path: "", inFolder: false, chunks: 9, characters: 7020, addedAt: Date.now() - 1728e5, smart: false },
  ],
};
let knowledge = null;

function loadKnowledge() {
  knowledge = live ? JSON.parse(bridge.knowledgeStatus()) : knowledge || structuredClone(PREVIEW_KNOWLEDGE);
  if (knowledge.embedder.downloading && !downloads[knowledge.embedder.id]) downloads[knowledge.embedder.id] = { received: 0, total: knowledge.embedder.sizeBytes };
  renderKnowledge();
  renderRuntime(); // the Sources context pill and trace list depend on it
}

const ago = (ms) => {
  const m = Math.round((Date.now() - ms) / 6e4);
  return m < 1 ? "just now" : m < 60 ? `${m} min ago` : m < 1440 ? `${Math.round(m / 60)} h ago` : `${Math.round(m / 1440)} d ago`;
};
const KIND_ICON = { file: "i-file", note: "i-note", shared: "i-share" };

function renderKnowledge() {
  if (!knowledge) return;
  const k = knowledge;
  const chunks = k.sources.reduce((n, src) => n + src.chunks, 0);
  $("#sources-switch").checked = k.enabled;
  $("#tuning").hidden = !k.enabled;
  $("#passages").value = k.passagesPerAnswer ?? 3;
  $("#passages").dispatchEvent(new Event("input"));
  const floor = k.minSimilarity ?? 0.55;
  const strict = STRICTNESS.reduce((best, v) => (Math.abs(v - floor) < Math.abs(best - floor) ? v : best));
  $$('input[name="strictness"]').forEach((r) => { r.checked = Number(r.value) === strict; });
  $("#strictness-hint").textContent = STRICTNESS_HINT[strict];
  renderChunking(k);
  $("#sources-summary").textContent = k.sources.length
    ? `${k.sources.length} document${k.sources.length === 1 ? "" : "s"} · ${chunks} passages · ${k.embeddingModel ? "search by meaning" : "keyword search"}`
    : "Connect a folder, files, or a note below.";
  const count = $("#sources-count");
  count.hidden = !k.sources.length;
  count.textContent = k.sources.length;

  const ix = k.indexing;
  $("#sources-progress").innerHTML = ix ? `
    <div class="card progress-card">
      <div class="progress-head"><strong>${escapeHtml(ix.label)}…</strong><span>${ix.total ? `${ix.done} of ${ix.total}` : ""}</span></div>
      <div class="meter"><span style="width:${ix.total ? (ix.done / ix.total) * 100 : 5}%"></span></div>
      ${ix.current ? `<small>${escapeHtml(ix.current)}</small>` : ""}
    </div>` : "";

  const embedders = k.embedders || [];
  const active = embedders.find((x) => x.active);
  const busy = Object.keys(downloads).length > 0 || !!k.indexing;
  $("#sources-embedder").innerHTML = `
    <h3 class="label">Search by meaning</h3>
    <div class="card list embedders">
      <p class="embedders-note">${active
        ? `Using <b>${escapeHtml(active.name)}</b>. Switching re-embeds your documents.`
        : "Search matches words right now. An offline embedding model also finds passages that say the same thing differently."}</p>
      ${embedders.map((x) => {
        const dl = downloads[x.id];
        const action = x.active ? `<span class="model-state" data-state="loaded">Active</span>`
          : dl ? `<button class="icon-button cancel" type="button" data-cancel-download aria-label="Cancel download">${svgIcon("i-close")}</button>`
          : x.installed ? `<button class="pill-button" type="button" data-use-embedder="${x.id}" ${busy ? "disabled" : ""}>Use</button>`
          : `<button class="pill-button" type="button" data-download="${x.id}" ${busy ? "disabled" : ""}>${svgIcon("i-download")}Get</button>`;
        return `
        <div class="list-row">
          <div class="model-text">
            <strong>${escapeHtml(x.name)}${x.recommended ? '<span class="best-badge">Best for this phone</span>' : ""}</strong>
            <small>${dl ? `Downloading ${gb(dl.received)} of ${gb(dl.total)}` : `${gb(x.sizeBytes)} · ${escapeHtml(x.blurb)}`}</small>
            ${dl ? `<div class="meter"><span style="width:${(dl.received / dl.total) * 100}%"></span></div>` : ""}
          </div>
          ${action}
        </div>`;
      }).join("")}
    </div>`;

  $("#folders-group").hidden = !k.folders.length;
  $("#folders-list").innerHTML = k.folders.map((f) => `
    <div class="list-row">
      <span class="row-icon">${svgIcon("i-folder")}</span>
      <span class="model-text"><strong>${escapeHtml(f.name)}</strong><small>${f.files} file${f.files === 1 ? "" : "s"} indexed</small></span>
      <button class="icon-button small" type="button" data-k="refresh" aria-label="Check ${escapeHtml(f.name)} for changes" ${ix ? "disabled" : ""}>${svgIcon("i-refresh")}</button>
      <button class="icon-button small" type="button" data-remove-folder="${escapeHtml(f.uri)}" aria-label="Disconnect ${escapeHtml(f.name)}">${svgIcon("i-close")}</button>
    </div>`).join("");

  $("#sources-list").innerHTML = k.sources.length ? k.sources.map((src) => `
    <div class="list-row">
      <span class="row-icon">${svgIcon(KIND_ICON[src.kind] || "i-file")}</span>
      <span class="model-text">
        <strong>${escapeHtml(src.name)}</strong>
        <small>${escapeHtml([src.path && src.path !== src.name ? src.path : null, `${src.chunks} passage${src.chunks === 1 ? "" : "s"}`, ago(src.addedAt)].filter(Boolean).join(" · "))}</small>
      </span>
      ${src.smart ? '<span class="chip">Meaning</span>' : ""}
      ${src.inFolder ? "" : `<button class="icon-button small" type="button" data-remove-source="${escapeHtml(src.id)}" aria-label="Remove ${escapeHtml(src.name)}">${svgIcon("i-close")}</button>`}
    </div>`).join("") : `<p class="empty-row">Nothing here yet. Connected documents appear here.</p>`;
}

/* Preview only: pretend to index something. */
function previewIndex(label, add) {
  knowledge.indexing = { label, done: 0, total: 3, current: "" };
  renderKnowledge();
  let n = 0;
  const t = setInterval(() => {
    n++;
    knowledge.indexing = n < 3 ? { label, done: n, total: 3, current: `File ${n + 1}` } : null;
    if (!knowledge.indexing) {
      clearInterval(t);
      knowledge.sources.unshift(...add);
      toast(`Added ${add.length} document${add.length === 1 ? "" : "s"}.`);
    }
    renderKnowledge();
    renderRuntime();
  }, 500);
}

document.addEventListener("click", (event) => {
  const k = event.target.closest("[data-k]")?.dataset.k;
  const remove = event.target.closest("[data-remove-source]")?.dataset.removeSource;
  const removeFolder = event.target.closest("[data-remove-folder]")?.dataset.removeFolder;
  if (k === "folder") live ? bridge.addKnowledgeFolder() : previewIndex("Indexing Notes", [
    { id: `preview:${Date.now()}`, name: "Meeting notes.md", kind: "file", path: "Notes/Meeting notes.md", inFolder: true, chunks: 3, characters: 2100, addedAt: Date.now(), smart: !!knowledge.embeddingModel },
  ]);
  if (k === "files") live ? bridge.addKnowledgeFiles() : previewIndex("Indexing files", [
    { id: `preview:${Date.now()}`, name: "Lease agreement.docx", kind: "file", path: "", inFolder: false, chunks: 12, characters: 9400, addedAt: Date.now(), smart: !!knowledge.embeddingModel },
  ]);
  if (k === "note") { $("#note-form").hidden = false; $("#note-text").focus(); }
  if (k === "note-cancel") $("#note-form").hidden = true;
  if (k === "refresh") live ? bridge.refreshKnowledge() : previewIndex("Checking folders for changes", []);
  if (remove) {
    if (live) bridge.removeKnowledgeSource(remove);
    else { knowledge.sources = knowledge.sources.filter((src) => src.id !== remove); renderKnowledge(); renderRuntime(); }
  }
  if (removeFolder) {
    if (live) bridge.removeKnowledgeFolder(removeFolder);
    else { knowledge.folders = knowledge.folders.filter((f) => f.uri !== removeFolder); knowledge.sources = knowledge.sources.filter((src) => !src.inFolder); renderKnowledge(); renderRuntime(); }
  }
});

document.addEventListener("click", (event) => {
  const use = event.target.closest("[data-use-embedder]")?.dataset.useEmbedder;
  if (!use) return;
  if (live) bridge.useEmbedder(use);
  else {
    knowledge.embedders.forEach((x) => { x.active = x.id === use; });
    knowledge.embeddingModel = use;
    renderKnowledge();
    toast("Switched. Your documents are re-embedded in the background.");
  }
});

$("#note-form").addEventListener("submit", (event) => {
  event.preventDefault();
  const title = $("#note-title").value.trim();
  const text = $("#note-text").value.trim();
  if (!text) return toast("Write something first.");
  if (live) bridge.addKnowledgeNote(title, text);
  else previewIndex("Adding note", [{ id: `preview:${Date.now()}`, name: title || text.split("\n")[0].slice(0, 48), kind: "note", path: "", inFolder: false, chunks: 1, characters: text.length, addedAt: Date.now(), smart: !!knowledge.embeddingModel }]);
  $("#note-title").value = "";
  $("#note-text").value = "";
  $("#note-form").hidden = true;
});

const STRICTNESS = [0.45, 0.55, 0.65];
const STRICTNESS_HINT = {
  0.45: "Loose also uses loosely related passages. Use it when answers miss things you know are there.",
  0.55: "Balanced suits most documents.",
  0.65: "Strict only uses close matches. Use it when answers pull in unrelated passages.",
};
function saveTuning() {
  knowledge.passagesPerAnswer = Number($("#passages").value);
  knowledge.minSimilarity = Number($('input[name="strictness"]:checked').value);
  bridge?.setKnowledgeTuning(knowledge.passagesPerAnswer, knowledge.minSimilarity);
  $("#strictness-hint").textContent = STRICTNESS_HINT[knowledge.minSimilarity];
}
bindRange($("#passages"), $("#passages-value"), (n) => String(n));
$("#passages").addEventListener("change", saveTuning);
$$('input[name="strictness"]').forEach((r) => r.addEventListener("change", saveTuning));

$("#sources-switch").addEventListener("change", (event) => {
  knowledge.enabled = event.target.checked;
  bridge?.setKnowledgeEnabled(event.target.checked);
  renderKnowledge();
  renderRuntime();
});

$("#search-form").addEventListener("submit", (event) => {
  event.preventDefault();
  const q = $("#search-input").value.trim();
  if (!q) return;
  const hits = live ? JSON.parse(bridge.searchKnowledge(q))
    : knowledge.sources.length ? [{ source: knowledge.sources[0].name, index: 1, text: "Sample passage: in the app this shows the exact text the model would receive.", score: 0.71, smart: !!knowledge.embeddingModel }] : [];
  $("#search-results").innerHTML = hits.length ? hits.map((h) => `
    <div class="hit">
      <div class="hit-head"><strong>${escapeHtml([h.source, h.section].filter(Boolean).join(" › "))}</strong><span>passage ${h.ref ? refPassage(h.ref) : h.index} · ${h.smart ? `${Math.round(h.score * 100)}% match` : "keyword match"}</span></div>
      <p>${escapeHtml(h.text.length > 360 ? `${h.text.slice(0, 360)}…` : h.text)}</p>
    </div>`).join("") : `<p class="empty-row">No passage matches. The model would answer without your sources.</p>`;
});

function loadLiveModels() {
  const all = JSON.parse(bridge.listModels());
  state.remoteModel = all.find((m) => m.format === "remote") || null;
  const list = all.filter((m) => m.format !== "remote" && (m.tasks || ["CHAT"]).includes("CHAT"));
  MODELS = Object.fromEntries(list.map((m) => [m.id, {
    name: m.name,
    short: m.name.length > 18 ? `${m.name.slice(0, 17)}…` : m.name,
    backend: "llama.cpp",
    mono: "GG",
    size: formatBytes(m.sizeBytes),
    state: m.state === "Unloaded" ? "Ready" : m.state,
    unavailable: m.state === "Unavailable" || m.state === "Failed" ? m.reason : null,
    tps: 0,
  }]));
  const usable = Object.keys(MODELS).filter((k) => !MODELS[k].unavailable);
  if (!usable.includes(state.model)) state.model = usable[0] || null;
  loadDevice(); // installed flags and the recommendation feed the empty state below
  renderModels();
  renderRuntime();
  renderMode();
  if (!state.hasRun) renderTimeline(routeSteps()); // never overwrite a finished run's trace
}

function contextItems() {
  return [
    { label: `Prompt v${settings?.promptVersion ?? 5}`, detail: `Helpful assistant v${settings?.promptVersion ?? 5}${settings?.customPrompt ? " (your prompt)" : ""}` },
    live && { label: "History", detail: "Conversation history (trimmed to fit)" },
    !live && state.memory && { label: "Memory", detail: "Session memory · 2 items" },
    knowledge?.enabled && knowledge.sources.length && { label: "Sources", detail: `Your sources · best ${knowledge.embeddingModel ? "matches" : "keyword matches"}, up to 3` },
  ].filter(Boolean);
}

function remoteHint() {
  const s = JSON.parse(bridge.remoteStatus());
  if (s.unavailable) return `Not ready: ${s.unavailable}.`;
  return `Sends requests to ${s.baseUrl}. Same route, same events.`;
}

$("#remote-save").addEventListener("click", () => {
  const error = bridge.setRemote(JSON.stringify({
    baseUrl: $("#remote-url").value,
    apiKey: $("#remote-key").value,
    model: $("#remote-model").value,
  }));
  if (error) return toast(error);
  $("#remote-key").value = "";
  $("#remote-key").placeholder = "Saved · kept in memory only";
  toast("Remote endpoint saved.");
});

/* Trace labels for the SDK's context sources, e.g. "prompt:helpful-assistant@3". */
function describeSource(source) {
  const omitted = source.startsWith("omitted ");
  const [kind, value = ""] = source.replace(/^omitted /, "").split(/:(.*)/s);
  if (omitted && kind === "guard") return `Privacy guard · ${value}`;
  if (omitted) return `Trimmed · ${value}`;
  if (kind === "prompt") return `Prompt ${value.replace("@", " v")}`;
  if (kind === "guard") return `Privacy guard · ${value}`;
  if (kind === "rag") return `Source · ${value.replace(/#([\d-]+)$/, ", passage $1")}`;
  return `${kind[0].toUpperCase()}${kind.slice(1)} · ${value}`;
}

function renderRuntime() {
  const model = currentModel();
  const remote = state.execution === "remote";
  const chip = $("#model-chip");
  $("#model-chip-label").textContent = model.short;
  chip.toggleAttribute("data-remote", remote);
  chip.setAttribute("aria-label", `${model.short}, ${remote ? "remote" : "on-device"}. Change model and settings`);
  $("#runtime-label").textContent = remote ? "Remote · HTTPS" : "On-device · Private";
  $("#execution-hint").textContent = remote
    ? live ? remoteHint() : "Sent to the Remote API backend over HTTPS. Same route, same events."
    : "Runs on this phone. Nothing leaves the device.";
  $("#remote-form").hidden = !(live && remote);
  $("#catalog-group").hidden = remote;
  if (live) {
    const r = JSON.parse(bridge.resources());
    const used = r.totalBytes - r.availableBytes;
    $("#memory-value").textContent = `${(used / 1e9).toFixed(1)} / ${(r.totalBytes / 1e9).toFixed(1)} GB`;
    $("#memory-meter").style.width = `${Math.round((used / r.totalBytes) * 100)}%`;
  } else {
    $("#memory-value").textContent = remote ? "0.3 / 6 GB" : model.memory;
    $("#memory-meter").style.width = `${remote ? 5 : model.memoryPct}%`;
  }

  const items = contextItems();
  $("#included-list").innerHTML = state.sources
    ? state.sources.map((s) => `<li>${svgIcon("i-check")}${describeSource(s)}</li>`).join("")
    : items.map((i) => `<li>${svgIcon("i-check")}${i.detail}</li>`).join("");
}

$$('input[name="execution"]').forEach((input) => input.addEventListener("change", () => {
  state.execution = input.value;
  renderRuntime();
}));
$("#model-list").addEventListener("change", (event) => {
  state.model = event.target.value;
  renderRuntime();
});
$("#memory-switch").addEventListener("change", (event) => { state.memory = event.target.checked; renderRuntime(); });

function bindRange(input, output, format) {
  const update = () => {
    const value = Number(input.value);
    output.textContent = format(value);
    input.style.setProperty("--fill", `${((value - input.min) / (input.max - input.min)) * 100}%`);
  };
  input.addEventListener("input", update);
  update();
}
bindRange($("#temperature"), $("#temperature-value"), (n) => n.toFixed(1));
bindRange($("#max-tokens"), $("#token-value"), (n) => n.toLocaleString("en-US"));

/* ---------- Modes and empty state ---------- */
function renderMode() {
  const mode = MODES[state.mode];
  $$('.tabs [role="tab"]').forEach((tab) => tab.setAttribute("aria-selected", String(tab.dataset.mode === state.mode)));
  const needsModel = live && state.mode !== "Workflow" && state.execution === "local" && Object.keys(MODELS).length === 0;
  const best = bestModel();
  const dlId = Object.keys(downloads)[0];
  const dl = dlId && downloads[dlId];
  $("#empty-title").textContent = needsModel ? (dl ? "Downloading your model…" : "Add a model to start.") : mode.title;
  $("#empty-copy").textContent = needsModel
    ? dl ? `${gb(dl.received)} of ${gb(dl.total)}. Keep Studio open; the model is ready to chat when this finishes.`
      : best ? `${device.device.name} can safely run ${best.name}. It downloads once (${gb(best.sizeBytes)}) and then runs fully on-device.`
        : "This phone has no room for the downloadable models. Import a small GGUF model instead."
    : live && state.mode === "Workflow" ? `${mode.copy} Workflow runs are simulated until the Workflow API ships.` : mode.copy;
  els.prompt.placeholder = mode.placeholder;
  $("#suggestions").innerHTML = needsModel
    ? [
      dl ? `<div class="suggestion progress-row"><span>${Math.floor((dl.received / dl.total) * 100)}%</span><div class="meter"><span style="width:${(dl.received / dl.total) * 100}%"></span></div><button class="icon-button cancel" type="button" data-cancel-download aria-label="Cancel download">${svgIcon("i-close")}</button></div>`
        : best ? `<button class="suggestion is-primary" type="button" data-download="${best.id}"><span>Download ${escapeHtml(best.name)} · ${gb(best.sizeBytes)}</span>${svgIcon("i-download")}</button>` : "",
      `<button class="suggestion" type="button" data-open="setup"><span>See all models for this phone</span>${svgIcon("i-arrow-ne")}</button>`,
      `<button class="suggestion" type="button" data-import><span>Import a .gguf file</span>${svgIcon("i-plus")}</button>`,
    ].join("")
    : mode.suggestions.map((s) => `<button class="suggestion" type="button"><span>${s}</span>${svgIcon("i-arrow-ne")}</button>`).join("");
}
document.addEventListener("click", (event) => {
  const link = event.target.closest("[data-goto]");
  if (link) $(`[role="tab"][data-mode="${link.dataset.goto}"]`).click();
});
$$('.tabs [role="tab"]').forEach((tab) => tab.addEventListener("click", () => {
  state.mode = tab.dataset.mode;
  renderMode();
}));
$("#suggestions").addEventListener("click", (event) => {
  const suggestion = event.target.closest(".suggestion");
  if (!suggestion || suggestion.matches("[data-import], [data-download], [data-open], .progress-row")) return;
  els.prompt.value = suggestion.textContent.trim();
  updateSend();
  els.prompt.focus();
});

/* ---------- Trace ---------- */
function routeSteps() {
  const model = currentModel();
  const remote = state.execution === "remote";
  const context = contextItems().map((i) => i.label).join(" + ");
  return [
    { name: `${state.mode} API`, detail: "Request received" },
    { name: "Request Controller", detail: `Validated · deadline ${live ? 120 : 30} s` },
    { name: "Model & Task Router", detail: remote ? `Remote API · ${model.short}` : `${model.backend} · ${model.short}` },
    { name: "Context Manager", detail: context },
    { name: "Inference", detail: remote ? "Remote API · SSE stream" : `${model.backend} · streaming` },
  ];
}

function renderTimeline(steps, activeIndex = -1) {
  els.timeline.innerHTML = steps.map((step, i) => `
    <li data-state="${i < activeIndex ? "done" : i === activeIndex ? "active" : "idle"}">
      <span class="node">${svgIcon("i-check")}</span>
      <span class="step-text"><strong>${step.name}</strong><small>${step.detail}</small></span>
      <span class="step-time"></span>
    </li>`).join("");
}

function setStatus(stateName, label) {
  const status = $("#run-status");
  status.dataset.state = stateName;
  status.textContent = label;
}

function setMetrics(first = "—", tps = "—", tokens = "—", total = "—") {
  $("#m-first").textContent = first;
  $("#m-tps").textContent = tps;
  $("#m-tokens").textContent = tokens;
  $("#m-total").textContent = total;
}

/* ---------- Event source ----------
   ponytail: simulated replies with the SDK's event shapes (step, first-token, delta, completed).
   T2AI-050 replaces simulate() with the native bridge; everything below it stays. */
function simulate(request, emit) {
  const contextSources = request.sources || null;
  const toolSteps = request.tools || [];
  const timers = [];
  const at = (ms, fn) => timers.push(setTimeout(fn, ms));
  const stepMs = [2, 4, 3, 7];
  let t = 0;
  for (let i = 0; i < 5; i++) {
    at(t, () => emit({ type: "step", index: i, ms: stepMs[i - 1], sources: i === 3 ? contextSources : undefined }));
    t += 180;
  }
  const firstToken = (request.remote ? 900 : 560) + Math.floor(Math.random() * 280);
  const tps = Math.round((request.remote ? REMOTE_TPS : request.tps) * (0.92 + Math.random() * 0.12));
  const pieces = request.text.split(/(\s+)/);
  toolSteps.forEach((tool, i) => {
    at(t, () => emit({ type: "tool-call", callId: `call_${i + 1}`, name: tool.name, arguments: JSON.stringify(tool.args) }));
    t += 450;
    at(t, () => emit({ type: "tool-result", callId: `call_${i + 1}`, name: tool.name, result: JSON.stringify(tool.result), isError: false }));
    t += 200;
  });
  at(t, () => emit({ type: "first-token", ms: firstToken }));
  pieces.forEach((piece, i) => at(t + i * 22, () => emit({ type: "delta", text: piece })));
  const tokens = Math.round(pieces.length * 0.7);
  at(t + pieces.length * 22 + 60, () => emit({
    type: "completed", tokens, tps, totalMs: firstToken + Math.round((tokens / tps) * 1000),
  }));
  return () => timers.forEach(clearTimeout);
}

/* Live event source: the SDK through the native bridge, mapped to the UI's event shapes. */
const STAGES = ["RECEIVED", "VALIDATED", "ROUTED", "CONTEXT_ASSEMBLED", "GENERATING"];
const liveListeners = new Map();
let liveSeq = 0;

function liveSource(prompt, emit) {
  const id = `ui_${++liveSeq}`;
  const start = performance.now();
  let last = start;
  let first = null;
  liveListeners.set(id, (e) => {
    if (e.type === "progress") {
      const now = performance.now();
      emit({ type: "step", index: STAGES.indexOf(e.stage), ms: Math.round(now - last), detail: e.detail, sources: e.sources });
      last = now;
      return;
    }
    if (e.type === "tool-call" || e.type === "tool-result") {
      emit(e);
      return;
    }
    if (e.type === "delta") {
      if (first === null) {
        first = Math.round(performance.now() - start);
        emit({ type: "first-token", ms: first });
      }
      emit({ type: "delta", text: e.text });
      return;
    }
    liveListeners.delete(id);
    if (e.type === "completed") {
      const firstMs = e.firstTokenMillis ?? first ?? 0;
      const tps = Math.round((e.tokens * 1000) / Math.max(1, e.totalMillis - firstMs));
      emit({ type: "completed", tokens: e.tokens, tps, totalMs: e.totalMillis, firstMs });
    } else {
      emit(e); // failed or cancelled
    }
  });
  bridge.send(JSON.stringify({
    id,
    prompt,
    mode: state.mode,
    session: state.chat?.id ?? null,
    model: state.execution === "remote" ? state.remoteModel?.id ?? null : state.model || null,
    backend: state.execution === "remote" ? "remote" : null,
    temperature: Number($("#temperature").value),
    maxTokens: Number($("#max-tokens").value),
  }));
  return () => {
    liveListeners.delete(id);
    bridge.cancel(id);
  };
}

window.tune2ai = {
  onEvent: (e) => liveListeners.get(e.id)?.(e),
  onModelsChanged: () => loadLiveModels(),
  onNotice: (text) => toast(text),
  onDownload: (e) => onDownload(e),
  onKnowledgeChanged: () => loadKnowledge(),
  onConnectionsChanged: () => { if (state.view === "connections" || connections) loadConnections(); },
  onOpenSources: () => showView("sources"),
};

/* ---------- Conversation ---------- */
function stickToBottom(fn) {
  const c = els.conversation;
  const atBottom = c.scrollHeight - c.scrollTop - c.clientHeight < 80;
  fn();
  if (atBottom) c.scrollTop = c.scrollHeight;
}

function addUserMessage(text) {
  const msg = document.createElement("div");
  msg.className = "msg msg-user";
  const bubble = document.createElement("p");
  bubble.className = "bubble";
  bubble.textContent = text;
  msg.append(bubble);
  els.conversation.append(msg);
}

function addAssistantMessage(simulated) {
  const model = currentModel();
  const msg = document.createElement("article");
  msg.className = "msg";
  msg.innerHTML = `
    <div class="msg-head">
      <span class="dot" aria-hidden="true"></span><strong></strong><span>${state.execution === "remote" ? "Remote" : "On-device"}</span>${simulated && live ? '<span class="chip">Simulated</span>' : ""}
    </div>
    <div class="tools"></div>
    <div class="answer is-streaming"><span class="thinking">Thinking</span></div>
    <div class="answer-sources" hidden></div>
    <div class="passage" hidden></div>
    <div class="msg-foot"></div>`;
  $(".msg-head strong", msg).textContent = model.short;
  els.conversation.append(msg);
  return msg;
}

/* Replies are Markdown: re-rendered as text streams in, at most once per frame. Citations like
   [1] become buttons that open the passage the model was given. */
function renderAnswer(msg) {
  const refs = msg._refs || [];
  $(".answer", msg).innerHTML = Markdown.render(msg._raw, {
    cite: (n) => (refs[n - 1] ? `<button class="cite" type="button" data-cite="${n}" aria-label="Source ${n}">${n}</button>` : null),
  });
}

function scheduleRender(msg) {
  if (msg._pending) return;
  msg._pending = true;
  requestAnimationFrame(() => {
    msg._pending = false;
    stickToBottom(() => renderAnswer(msg));
  });
}

const refName = (ref) => ref.replace(/#[\d-]+$/, "");
const refPassage = (ref) => ref.match(/#([\d-]+)$/)?.[1] || "";

function renderSourceChips(msg) {
  const refs = msg._refs || [];
  const box = $(".answer-sources", msg);
  box.hidden = !refs.length;
  box.innerHTML = refs.length ? `<span class="answer-sources-label">Sources</span>` + refs.map((ref, i) => `
    <button class="source-chip" type="button" data-cite="${i + 1}"><b>${i + 1}</b><span>${escapeHtml(refName(ref))}</span></button>`).join("") : "";
}

/* Opens (or closes) the passage behind citation n under the reply. */
function togglePassage(msg, n) {
  const ref = (msg._refs || [])[n - 1];
  const panel = $(".passage", msg);
  if (!ref) return;
  if (!panel.hidden && panel.dataset.n === String(n)) {
    panel.hidden = true;
    $$("[data-cite]", msg).forEach((b) => b.classList.remove("is-open"));
    return;
  }
  const p = live ? JSON.parse(bridge.knowledgePassage(ref) || "null")
    : { source: refName(ref), section: "Flights", text: "We fly out on Friday at 07:40 from Terminal 2 (TAP TP1351). Check-in closes 45 minutes before departure. Hotel check-in is after 15:00." };
  panel.dataset.n = String(n);
  panel.hidden = false;
  panel.innerHTML = p ? `
    <div class="passage-head"><b>${n}</b><strong>${escapeHtml(p.source)}</strong><span>${escapeHtml([p.section, `passage ${refPassage(ref)}`].filter(Boolean).join(" · "))}</span></div>
    <p>${escapeHtml(p.text.length > 700 ? `${p.text.slice(0, 700)}…` : p.text)}</p>`
    : `<p class="passage-missing">This passage is no longer in your sources.</p>`;
  $$("[data-cite]", msg).forEach((b) => b.classList.toggle("is-open", b.dataset.cite === String(n)));
}

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    const area = Object.assign(document.createElement("textarea"), { value: text });
    area.style.cssText = "position:fixed;opacity:0";
    document.body.append(area);
    area.select();
    document.execCommand("copy");
    area.remove();
  }
}

document.addEventListener("click", (event) => {
  const cite = event.target.closest("[data-cite]");
  if (cite) togglePassage(cite.closest(".msg"), Number(cite.dataset.cite));
  const code = event.target.closest("[data-copy-code]");
  if (code) {
    copyText($("pre", code.closest(".md-code")).textContent);
    code.textContent = "Copied";
    setTimeout(() => { code.textContent = "Copy"; }, 1500);
  }
  const copy = event.target.closest("[data-copy-reply]");
  if (copy) {
    copyText(Markdown.toPlainText(copy.closest(".msg")._raw));
    toast("Copied.");
  }
  const retry = event.target.closest("[data-retry]");
  if (retry && !state.run) run(retry.closest(".msg")._prompt);
});

/* Agent tools (AgentTools.java). Actions only prepare; the button below the reply opens the system
   app filled in, and the user confirms there. */
const TOOL_VERB = {
  search_sources: "Searched your sources", read_messages: "Read your messages", calendar_events: "Checked your calendar",
  find_contact: "Looked up a contact", recent_calls: "Checked recent calls", read_notifications: "Read notifications",
  save_note: "Saved a note", send_sms: "Prepared a text", add_calendar_event: "Prepared an event", set_alarm: "Prepared an alarm",
  call: "Prepared a call", share: "Prepared text to share", current_time: "Checked the time", device_status: "Checked the device",
};
const TOOL_ICON = {
  search_sources: "i-search", read_messages: "i-message", calendar_events: "i-calendar", find_contact: "i-person",
  recent_calls: "i-phone", read_notifications: "i-bell", save_note: "i-note", send_sms: "i-message",
  add_calendar_event: "i-calendar", set_alarm: "i-bell", call: "i-phone", share: "i-share",
};
const ACTIONS = {
  send_sms: { label: "Review in Messages", icon: "i-message", describe: (a) => `To ${a.to}: “${a.text}”` },
  add_calendar_event: { label: "Add to Calendar", icon: "i-calendar", describe: (a) => `${a.title} · ${a.start}${a.end ? `–${a.end.slice(-5)}` : ""}${a.location ? ` · ${a.location}` : ""}` },
  set_alarm: { label: "Set alarm", icon: "i-bell", describe: (a) => `${a.time}${a.label ? ` · ${a.label}` : ""}` },
  call: { label: "Call", icon: "i-phone", describe: (a) => `Call ${a.to}` },
  share: { label: "Share", icon: "i-share", describe: (a) => (a.text.length > 90 ? `${a.text.slice(0, 89)}…` : a.text) },
};

function addActionButton(msg, card, name, argsJson) {
  let args = {};
  try { args = JSON.parse(argsJson); } catch { return; }
  const a = ACTIONS[name];
  const box = document.createElement("div");
  box.className = "action-card";
  box.innerHTML = `
    <span class="row-icon">${svgIcon(a.icon)}</span>
    <span class="model-text"><strong></strong><small></small></span>
    <button class="pill-button" type="button" data-action="${name}">${a.label}</button>`;
  $("strong", box).textContent = TOOL_VERB[name];
  $("small", box).textContent = a.describe(args);
  box.querySelector("button").dataset.args = JSON.stringify(args);
  card.after(box);
}

document.addEventListener("click", (event) => {
  const btn = event.target.closest("[data-action]");
  if (!btn) return;
  if (live) bridge.performAction(btn.dataset.action, btn.dataset.args);
  else toast(`On the phone this opens ${{ send_sms: "Messages", add_calendar_event: "Calendar", set_alarm: "Clock", call: "the dialer", share: "the share sheet" }[btn.dataset.action]}, filled in, for you to confirm.`);
});

/* Tool activity in an agent reply: one expandable card per call, with arguments and result. */
const prettyJson = (json) => {
  try { return JSON.stringify(JSON.parse(json), null, 2); } catch { return json; }
};

function addToolCard(msg, e) {
  const card = document.createElement("details");
  card.className = "tool-card";
  card.dataset.call = e.callId;
  card.dataset.state = "running";
  card.innerHTML = `<summary>${svgIcon(TOOL_ICON[e.name] || "i-tool")}<span>${TOOL_VERB[e.name] || "Called"} <strong></strong></span><span class="tool-state">Running…</span></summary><pre></pre>`;
  $("strong", card).textContent = TOOL_VERB[e.name] ? "" : e.name;
  $("pre", card).textContent = prettyJson(e.arguments);
  $(".tools", msg).append(card);
  (msg._tools ||= []).push({ callId: e.callId, name: e.name, arguments: e.arguments, result: null, isError: false });
}

function finishToolCard(msg, e) {
  const card = $(`[data-call="${e.callId}"]`, msg);
  if (!card) return;
  card.dataset.state = e.isError ? "error" : "done";
  $(".tool-state", card).textContent = e.isError ? "Error" : "Done";
  const result = document.createElement("pre");
  result.textContent = prettyJson(e.result);
  card.append(result);
  const saved = (msg._tools || []).find((t) => t.callId === e.callId);
  if (saved) Object.assign(saved, { result: e.result, isError: e.isError });
  if (!e.isError && ACTIONS[e.name]) addActionButton(msg, card, e.name, e.arguments ?? saved?.arguments ?? "{}");
}

function run(text) {
  const simulated = !live || state.mode === "Workflow";
  state.hasRun = true;
  els.empty.hidden = true;
  if (!state.chat) state.chat = newChat();
  state.chat.messages.push({ role: "user", text, mode: state.mode, at: Date.now() });
  if (!state.chat.renamed && state.chat.messages.filter((m) => m.role === "user").length === 1) state.chat.title = chatTitle(text);
  addUserMessage(text);
  const msg = addAssistantMessage(simulated);
  const answer = $(".answer", msg);
  const steps = routeSteps();
  const model = currentModel();
  const remote = state.execution === "remote";
  const requestId = `req_${crypto.getRandomValues(new Uint32Array(1))[0].toString(16).padStart(8, "0")}`;
  let firstMs = null;
  msg._raw = "";
  msg._refs = [];
  msg._prompt = text;

  $("#request-id").textContent = requestId;
  renderTimeline(steps, 0);
  setStatus("running", "Running");
  setMetrics("…", "…", "…", "…");
  els.send.dataset.running = "";
  els.send.setAttribute("aria-label", "Stop");
  els.send.innerHTML = svgIcon("i-stop");
  els.conversation.scrollTop = els.conversation.scrollHeight;

  const items = $$("li", els.timeline);
  const onEvent = (event) => stickToBottom(() => {
    if (event.type === "step") {
      items.forEach((li, i) => { li.dataset.state = i < event.index ? "done" : i === event.index ? "active" : "idle"; });
      if (event.index > 0) $(".step-time", items[event.index - 1]).textContent = `${event.ms} ms`;
      if (event.detail) $(".step-text small", items[event.index]).textContent = event.detail;
      if (event.sources?.length) {
        msg._refs = event.sources.filter((src) => src.startsWith("rag:")).map((src) => src.slice(4));
        state.sources = event.sources;
        $("#included-list").innerHTML = event.sources.map((s) => `<li>${svgIcon("i-check")}${describeSource(s)}</li>`).join("");
      }
    } else if (event.type === "first-token") {
      firstMs = event.ms;
      $("#m-first").textContent = `${(event.ms / 1000).toFixed(2)} s`;
    } else if (event.type === "tool-call") {
      addToolCard(msg, event);
    } else if (event.type === "tool-result") {
      finishToolCard(msg, event);
    } else if (event.type === "delta") {
      msg._raw += event.text;
      scheduleRender(msg);
    } else if (event.type === "completed") {
      if (event.firstMs != null) firstMs = event.firstMs;
      finish("ok", event);
    } else if (event.type === "failed" || event.type === "cancelled") {
      finish(event.type, event);
    }
  });

  function finish(result, stats = {}) {
    state.run = null;
    answer.classList.remove("is-streaming");
    const foot = $(".msg-foot", msg);
    if (result === "ok") {
      items.forEach((li) => { li.dataset.state = "done"; });
      $(".step-time", items[4]).textContent = `${stats.totalMs} ms`;
      setStatus("ok", "Completed");
      setMetrics(`${((firstMs ?? 0) / 1000).toFixed(2)} s`, `${stats.tps} tok/s`, `${stats.tokens} tok`, `${(stats.totalMs / 1000).toFixed(1)} s`);
      renderAnswer(msg);
      renderSourceChips(msg);
      foot.innerHTML = `
        <button class="foot-action" type="button" data-copy-reply aria-label="Copy reply">${svgIcon("i-copy")}</button>
        <button class="foot-action" type="button" data-retry aria-label="Ask again">${svgIcon("i-refresh")}</button>
        <button class="stats" type="button" data-open="trace" aria-label="Open run trace">${svgIcon("i-activity")}${((firstMs ?? 0) / 1000).toFixed(2)} s · ${stats.tps} tok/s</button>`;
    } else {
      items.forEach((li) => { if (li.dataset.state === "active") li.dataset.state = "cancelled"; });
      const failed = result === "failed";
      setStatus(failed ? "failed" : "cancelled", failed ? "Failed" : "Cancelled");
      if (firstMs === null) setMetrics();
      else { $("#m-tps").textContent = "—"; $("#m-tokens").textContent = "—"; $("#m-total").textContent = "—"; }
      if (msg._raw.trim()) renderAnswer(msg);
      else answer.remove();
      if (failed) {
        const error = document.createElement("p");
        error.className = "error-text";
        error.textContent = stats.message;
        foot.before(error);
        foot.innerHTML = `<span class="chip" data-state="failed">Failed · ${stats.category.toLowerCase().replace(/_/g, " ")}</span>`;
      } else {
        foot.innerHTML = `<span class="chip" data-state="cancelled">Cancelled</span>`;
      }
    }
    if (result === "ok" || msg._raw.trim()) {
      state.chat.messages.push({
        role: "assistant", text: msg._raw, refs: msg._refs, tools: msg._tools || [], model: model.short,
        remote, mode: state.mode, status: result,
        stats: result === "ok" ? { firstMs: firstMs ?? 0, tps: stats.tps } : null, at: Date.now(),
      });
      saveChat();
    }
    if (openSheetId !== "trace") $("#trace-dot").hidden = false;
    delete els.send.dataset.running;
    els.send.setAttribute("aria-label", "Run request");
    els.send.innerHTML = svgIcon("i-arrow-up");
    updateSend();
    if (live) loadLiveModels(); // load state changed
  }

  // Preview: a question about the sample trip shows a cited answer from the sample sources.
  const previewAgent = !live && state.mode === "Agent" ? previewAgentRun(text) : null;
  const previewCited = !previewAgent && !live && knowledge?.enabled && /lisbon|flight|trip/i.test(text) && knowledge.sources.some((src) => src.name === "Lisbon trip.md");
  const stop = simulated
    ? simulate(previewAgent ? { ...previewAgent, remote, tps: model.tps || 40 } : previewCited
      ? { text: "Your flight leaves **Friday at 07:40** from **Terminal 2** [1].\n\n- Check-in closes **45 minutes** before departure [1]\n- Hotel check-in is after **15:00** [1]\n\nWant a reminder the evening before?", sources: ["prompt:helpful-assistant@5", "rag:Lisbon trip.md#2-3"], remote, tps: model.tps || 40 }
      : { text: MODES[state.mode].reply({ model: model.name, backend: model.backend, remote }), remote, tps: model.tps || 40 }, onEvent)
    : liveSource(text, onEvent);
  state.run = { cancel: () => { stop(); finish("cancelled"); } };
}

/* Browser preview only: what an agent run looks like, with tool calls. */
function previewAgentRun(text) {
  if (/flight|calendar.*sources|sources.*calendar/i.test(text)) {
    return {
      tools: [
        { name: "search_sources", args: { query: "flight details" }, result: [{ source: "Lisbon trip.md", text: "We fly out on Friday at 07:40 from Terminal 2 (TAP TP1351)." }] },
        { name: "add_calendar_event", args: { title: "Flight TP1351 to Lisbon", start: "2026-11-27 07:40", end: "2026-11-27 10:15", location: "Terminal 2" }, result: { status: "ready_for_user" } },
      ],
      text: "I found your flight in **Lisbon trip.md**: TAP **TP1351**, Friday 27 November at **07:40** from Terminal 2.\n\nI've prepared the calendar event. Tap **Add to Calendar** to review and save it.",
    };
  }
  if (/text|sms|message .* late|tell/i.test(text)) {
    return {
      tools: [
        { name: "find_contact", args: { name: "Mom" }, result: "- Mom: phone +91 98450 12345" },
        { name: "send_sms", args: { to: "+91 98450 12345", text: "Running about 10 minutes late, see you soon!" }, result: { status: "ready_for_user" } },
      ],
      text: "Your text to **Mom** is ready. Tap **Review in Messages** to check it and send.",
    };
  }
  return {
    tools: [
      { name: "calendar_events", args: { days: 1 }, result: "- Sun 22 Nov, 10:00–10:30: Team standup\n- Sun 22 Nov, 19:00: Dinner with Priya · at Toit" },
      { name: "read_messages", args: { limit: 5 }, result: "- 22 Nov 09:12 · HDFC Bank: Rs 2,500 debited\n- 22 Nov 08:40 · Priya: See you at 7!" },
    ],
    text: "### Today\n\n- **10:00** Team standup (30 min)\n- **19:00** Dinner with Priya at Toit\n\n### Latest messages\n\n- **HDFC Bank:** ₹2,500 debited this morning\n- **Priya:** \"See you at 7!\"",
  };
}

/* ---------- Composer ---------- */
function updateSend() {
  els.send.disabled = !state.run && !els.prompt.value.trim();
}
els.prompt.addEventListener("input", updateSend);
els.prompt.addEventListener("keydown", (event) => {
  const sendKey = event.key === "Enter" && (event.metaKey || event.ctrlKey || (finePointer.matches && !event.shiftKey));
  if (sendKey) {
    event.preventDefault();
    $("#composer").requestSubmit();
  }
});
$("#composer").addEventListener("submit", (event) => {
  event.preventDefault();
  if (state.run) return state.run.cancel();
  const text = els.prompt.value.trim();
  if (!text) return;
  els.prompt.value = "";
  run(text);
});

$("#new-session").addEventListener("click", () => startNewChat(true));

/* ---------- Chats: saved conversations ----------
   The page keeps the transcript (what it needs to redraw a chat) and saves it after every reply:
   live through the bridge (ChatStore.java, app-private files), in a browser preview to localStorage.
   The model-facing history is the SDK chat session with the same id. */
const newChat = () => ({
  id: `c${Date.now().toString(36)}${Math.random().toString(36).slice(2, 7)}`,
  title: "New chat", renamed: false, createdAt: Date.now(), updatedAt: Date.now(), messages: [],
});
const chatTitle = (text) => {
  const t = text.replace(/\s+/g, " ").trim();
  return t.length > 48 ? `${t.slice(0, 47)}…` : t || "New chat";
};

const previewChats = {
  read() {
    try { return JSON.parse(localStorage.getItem("t2a.chats") || "null") || previewChats.seed(); } catch { return previewChats.seed(); }
  },
  write(all) {
    try { localStorage.setItem("t2a.chats", JSON.stringify(all)); } catch { /* private mode: keep in memory only */ }
    previewChats.memory = all;
  },
  seed() {
    if (previewChats.memory) return previewChats.memory;
    const day = 864e5;
    const chat = (id, title, ago, q, a) => ({ id, title, renamed: false, createdAt: Date.now() - ago, updatedAt: Date.now() - ago,
      messages: [{ role: "user", text: q, at: Date.now() - ago }, { role: "assistant", text: a, refs: [], tools: [], model: "Llama 3.2 3B", mode: "Chat", status: "ok", stats: { firstMs: 640, tps: 62 }, at: Date.now() - ago }] });
    previewChats.memory = {
      c1: chat("c1", "Plan a weekend in Lisbon", 2 * 36e5, "Plan a weekend in Lisbon", "### Saturday\n\n- **Morning:** Alfama walk and the castle\n- **Afternoon:** Tram 28 to Estrela\n\n### Sunday\n\n- Belém tower and pastéis de nata"),
      c2: chat("c2", "Explain the 50/30/20 budget rule", day + 4e6, "Explain the 50/30/20 budget rule", "Split take-home pay into **50%** needs, **30%** wants, **20%** savings."),
      c3: chat("c3", "Compare 3B and 8B models on a phone", 5 * day, "Compare 3B and 8B models on a phone", "| | 3B | 8B |\n|---|---|---|\n| RAM | ~2.5 GB | ~5.5 GB |\n| Speed | Fast | Slow |"),
      c4: chat("c4", "Draft a release note for chat", 20 * day, "Draft a release note for our chat feature", "**New:** private on-device chat."),
    };
    return previewChats.memory;
  },
};

function listChats() {
  if (live) return JSON.parse(bridge.listChats());
  return Object.values(previewChats.read()).map((c) => ({ id: c.id, title: c.title, updatedAt: c.updatedAt, count: c.messages.length,
    preview: (c.messages.at(-1)?.text || "").replace(/[#*`>|_~-]+/g, " ").replace(/\s+/g, " ").trim().slice(0, 120) }))
    .sort((a, b) => b.updatedAt - a.updatedAt);
}
function loadChat(id) {
  return live ? JSON.parse(bridge.loadChat(id)) : structuredClone(previewChats.read()[id] || null);
}
function saveChat() {
  if (!state.chat?.messages.length) return;
  state.chat.updatedAt = Date.now();
  if (live) bridge.saveChat(JSON.stringify(state.chat));
  else previewChats.write({ ...previewChats.read(), [state.chat.id]: structuredClone(state.chat) });
  if (openSheetId === "history") renderHistory();
}
function deleteChat(id) {
  if (live) bridge.deleteChat(id);
  else { const all = previewChats.read(); delete all[id]; previewChats.write(all); }
  if (state.chat?.id === id) startNewChat(false);
  renderHistory();
  toast("Chat deleted.");
}
function renameChat(id, title) {
  if (live) bridge.renameChat(id, title);
  else { const all = previewChats.read(); if (all[id]) Object.assign(all[id], { title, renamed: true }); previewChats.write(all); }
  if (state.chat?.id === id) Object.assign(state.chat, { title, renamed: true });
  renderHistory();
}

function resetConversationView() {
  $$(".msg", els.conversation).forEach((m) => m.remove());
  $("#request-id").textContent = "No runs yet";
  state.hasRun = false;
  state.sources = null;
  renderRuntime();
  renderTimeline(routeSteps());
  setStatus("idle", "Idle");
  setMetrics();
  $("#trace-dot").hidden = true;
}

function startNewChat(announce) {
  state.run?.cancel();
  bridge?.newSession();
  state.chat = null;
  resetConversationView();
  els.empty.hidden = false;
  els.prompt.value = "";
  updateSend();
  closeSheet();
  showView("chat");
  if (announce) toast("New chat.");
}

/* Redraws a saved reply: Markdown, tool cards, citations, and actions, as it looked when it ran. */
function addSavedAssistant(m, prompt) {
  const msg = document.createElement("article");
  msg.className = "msg";
  msg.innerHTML = `
    <div class="msg-head"><span class="dot" aria-hidden="true"></span><strong></strong><span>${m.remote ? "Remote" : "On-device"}</span>${m.mode && m.mode !== "Chat" ? `<span class="chip">${escapeHtml(m.mode)}</span>` : ""}</div>
    <div class="tools"></div>
    <div class="answer"></div>
    <div class="answer-sources" hidden></div>
    <div class="passage" hidden></div>
    <div class="msg-foot"></div>`;
  $(".msg-head strong", msg).textContent = m.model || "Assistant";
  els.conversation.append(msg);
  const savedTools = (m.tools || []).map((t) => ({ ...t }));
  Object.assign(msg, { _raw: m.text || "", _refs: m.refs || [], _prompt: prompt, _tools: [] });
  savedTools.forEach((t) => { // redraws cards (and action buttons) without duplicating the saved list
    addToolCard(msg, t);
    if (t.result != null) finishToolCard(msg, t);
  });
  msg._tools = savedTools;
  renderAnswer(msg);
  renderSourceChips(msg);
  $(".msg-foot", msg).innerHTML = m.status === "cancelled" ? `<span class="chip" data-state="cancelled">Cancelled</span>` : `
    <button class="foot-action" type="button" data-copy-reply aria-label="Copy reply">${svgIcon("i-copy")}</button>
    <button class="foot-action" type="button" data-retry aria-label="Ask again">${svgIcon("i-refresh")}</button>
    ${m.stats ? `<span class="stats">${svgIcon("i-activity")}${(m.stats.firstMs / 1000).toFixed(2)} s · ${m.stats.tps} tok/s</span>` : ""}`;
}

function openChat(id) {
  const chat = loadChat(id);
  if (!chat) return toast("That chat is no longer saved.");
  state.run?.cancel();
  bridge?.newSession();
  state.chat = chat;
  resetConversationView();
  let lastPrompt = "";
  chat.messages.forEach((m) => {
    if (m.role === "user") { lastPrompt = m.text; addUserMessage(m.text); } else addSavedAssistant(m, lastPrompt);
  });
  els.empty.hidden = chat.messages.length > 0;
  state.hasRun = chat.messages.length > 0;
  closeSheet();
  showView("chat");
  els.conversation.scrollTop = els.conversation.scrollHeight;
}

function historyBucket(t) {
  const start = new Date(); start.setHours(0, 0, 0, 0);
  const day = 864e5;
  if (t >= start.getTime()) return "Today";
  if (t >= start.getTime() - day) return "Yesterday";
  if (t >= start.getTime() - 6 * day) return "Previous 7 days";
  if (t >= start.getTime() - 29 * day) return "Previous 30 days";
  return "Older";
}

let pendingDelete = null;
function renderHistory() {
  const q = $("#history-search").value.trim().toLowerCase();
  const chats = listChats().filter((c) => !q || `${c.title} ${c.preview}`.toLowerCase().includes(q));
  if (!chats.length) {
    $("#history-list").innerHTML = `<p class="empty-row">${q ? "No chats match." : "No saved chats yet. Your conversations appear here after the first reply."}</p>`;
    return;
  }
  const groups = new Map();
  chats.forEach((c) => { const b = historyBucket(c.updatedAt); if (!groups.has(b)) groups.set(b, []); groups.get(b).push(c); });
  $("#history-list").innerHTML = [...groups].map(([bucket, list]) => `
    <h3 class="label">${bucket}</h3>
    <div class="card list">${list.map((c) => `
      <div class="list-row history-row${state.chat?.id === c.id ? " is-current" : ""}">
        <button class="history-open" type="button" data-open-chat="${c.id}">
          <strong>${escapeHtml(c.title)}</strong>
          <small>${escapeHtml(c.preview || "")}</small>
        </button>
        <button class="icon-button small" type="button" data-rename-chat="${c.id}" aria-label="Rename ${escapeHtml(c.title)}">${svgIcon("i-pencil")}</button>
        ${pendingDelete === c.id
          ? `<button class="pill-button danger" type="button" data-delete-chat="${c.id}">Delete</button>`
          : `<button class="icon-button small" type="button" data-ask-delete="${c.id}" aria-label="Delete ${escapeHtml(c.title)}">${svgIcon("i-trash")}</button>`}
      </div>`).join("")}</div>`).join("");
}

$("#history-search").addEventListener("input", renderHistory);
document.addEventListener("click", (event) => {
  const open = event.target.closest("[data-open-chat]")?.dataset.openChat;
  const askDelete = event.target.closest("[data-ask-delete]")?.dataset.askDelete;
  const del = event.target.closest("[data-delete-chat]")?.dataset.deleteChat;
  const rename = event.target.closest("[data-rename-chat]")?.dataset.renameChat;
  if (event.target.closest("[data-open='history']")) { pendingDelete = null; $("#history-search").value = ""; renderHistory(); }
  if (event.target.closest("[data-new-chat]")) startNewChat(true);
  if (open) openChat(open);
  if (askDelete) { pendingDelete = askDelete; renderHistory(); setTimeout(() => { if (pendingDelete === askDelete) { pendingDelete = null; if (openSheetId === "history") renderHistory(); } }, 3000); }
  if (del) { pendingDelete = null; deleteChat(del); }
  if (rename) startRename(rename);
});

/* Inline rename (WebView has no prompt() dialog): the title becomes a text field; Enter or leaving saves. */
function startRename(id) {
  const row = $(`[data-open-chat="${id}"]`)?.closest(".history-row");
  if (!row) return;
  const current = listChats().find((c) => c.id === id)?.title || "";
  const form = document.createElement("form");
  form.className = "rename-form";
  form.innerHTML = `<input type="text" maxlength="80" aria-label="Chat name" /><button class="pill-button" type="submit">Save</button>`;
  const input = $("input", form);
  input.value = current;
  row.replaceChildren(form);
  input.focus();
  input.select();
  let done = false;
  const commit = () => {
    if (done) return;
    done = true;
    const title = input.value.trim();
    if (title && title !== current) renameChat(id, title);
    else renderHistory();
  };
  form.addEventListener("submit", (e) => { e.preventDefault(); commit(); });
  input.addEventListener("keydown", (e) => { if (e.key === "Escape") { done = true; renderHistory(); } });
  input.addEventListener("blur", () => setTimeout(commit, 150));
}


/* ---------- Start ---------- */
if (live) {
  // Not wired in Studio yet: memory (T2AI-025), RAG (T2AI-024).
  $(".sim-badge").hidden = true;
  state.memory = false;
  [["#memory-switch", "Arrives with the memory engine (T2AI-025)"]].forEach(([id, note]) => {
    const input = $(id);
    input.checked = false;
    input.disabled = true;
    $("small", input.closest(".switch-row")).textContent = note;
  });
  MODELS = {};
  state.model = null;
  loadLiveModels();
}
if (!live) loadDevice();
loadSettings();
loadKnowledge();
loadConnections();
if (live && bridge.takeOpenSources()) showView("sources");
renderModels();
renderRuntime();
renderMode();
renderTimeline(routeSteps());
updateSend();
