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
    suggestions: ["How does on-device inference protect user data?", "Draft a release note for our chat feature", "Compare 3B and 8B models on a mid-range phone"],
    reply: (c) => c.remote
      ? `This request went to the Remote API backend over HTTPS and streamed back as SSE events. Routing, context assembly, and the event stream are identical to an on-device run, so switching back is one setting.\n\nUse remote for large models or long contexts; keep on-device as the default for privacy and offline use.`
      : `Prompts and outputs never leave this phone: ${c.backend} runs ${c.model} locally, so there is no server to log data and replies keep working offline.\n\nThe trade-off is device headroom. The Model Manager checks free memory before loading, and the Device Resource Monitor can unload idle models under pressure so your app stays responsive.\n\nWant me to draft the privacy note for your release?`,
  },
  Agent: {
    title: "Give an agent a task.",
    copy: "Agents run bounded steps with tools your app registers, and stop at the limits you set.",
    placeholder: "Describe a task for the agent…",
    suggestions: ["How much free memory does this device have?", "Which models are installed, and is one loaded?", "Check device headroom before loading a second model"],
    reply: () => `Done in 3 of 6 allowed steps.\n\n1 · resource_monitor → 3.6 GB free, thermal nominal\n2 · list_models → 1 loaded, 1 ready\n3 · Drafted the plan below\n\nLoading a second model fits now, but unload it when the app goes to the background. Every tool input was validated against its schema before it ran.`,
  },
  Workflow: {
    title: "Chain steps into one run.",
    copy: "Workflows checkpoint after each step, so a run can resume after the app restarts.",
    placeholder: "Describe the workflow input…",
    suggestions: ["Summarize → classify → draft a reply to a support ticket", "Translate, check tone, then shorten", "Extract action items from meeting notes"],
    reply: () => `Workflow finished: 3 of 3 steps.\n\nSummarize → 2 sentences\nClassify → "billing question"\nDraft reply → 86 words, friendly tone\n\nEach step was checkpointed to local storage, so this run could resume after an app restart without repeating completed steps.`,
  },
  /* Finance is a Chat preset: same Chat API, finance-focused starters. Figures stay on the device. */
  Finance: {
    title: "Your money, on-device.",
    copy: "Ask about budgets, spending, and plans. Nothing you type leaves this phone.",
    placeholder: "Ask about budgets or spending…",
    suggestions: ["Build a monthly budget from a ₹60,000 salary", "Explain the 50/30/20 rule with an example", "Split savings between an emergency fund and investments"],
    reply: (c) => `A simple starting point is 50/30/20: half of take-home pay for needs, 30% for wants, and 20% for savings and debt.\n\nOn ₹60,000 that is ₹30,000 for rent, food, and bills, ₹18,000 for everything else, and ₹12,000 saved. Build an emergency fund of three to six months of needs first, then invest the rest.\n\nThis ran on ${c.model}, so your figures never left the phone.`,
  },
};
const apiName = (mode) => (mode === "Finance" ? "Chat" : mode);

const state = { mode: "Chat", execution: "local", model: "llama", memory: true, rag: false, run: null, hasRun: false, sources: null };
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

function loadLiveModels() {
  const all = JSON.parse(bridge.listModels());
  state.remoteModel = all.find((m) => m.format === "remote") || null;
  const list = all.filter((m) => m.format !== "remote");
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
  renderModels();
  renderRuntime();
  renderMode();
  if (!state.hasRun) renderTimeline(routeSteps()); // never overwrite a finished run's trace
}

function contextItems() {
  return [
    { label: "Prompt v3", detail: "Helpful assistant v3" },
    live && { label: "History", detail: "Conversation history (trimmed to fit)" },
    !live && state.memory && { label: "Memory", detail: "Session memory · 2 items" },
    !live && state.rag && { label: "RAG", detail: "RAG sources · 3 chunks" },
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
  if (omitted) return `Trimmed · ${value}`;
  if (kind === "prompt") return `Prompt ${value.replace("@", " v")}`;
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
$("#rag-switch").addEventListener("change", (event) => { state.rag = event.target.checked; renderRuntime(); });

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
  $$('[role="tab"]').forEach((tab) => tab.setAttribute("aria-selected", String(tab.dataset.mode === state.mode)));
  const needsModel = live && state.mode === "Chat" && Object.keys(MODELS).length === 0;
  $("#empty-title").textContent = needsModel ? "Add a model to start." : mode.title;
  $("#empty-copy").textContent = needsModel
    ? "Import a GGUF model from this phone. It runs fully on-device; nothing is sent anywhere."
    : live && state.mode === "Workflow" ? `${mode.copy} Workflow runs are simulated until the Workflow API ships.` : mode.copy;
  els.prompt.placeholder = mode.placeholder;
  $("#suggestions").innerHTML = needsModel
    ? `<button class="suggestion" type="button" data-import><span>Import a .gguf model</span>${svgIcon("i-plus")}</button>`
    : mode.suggestions.map((s) => `<button class="suggestion" type="button"><span>${s}</span>${svgIcon("i-arrow-ne")}</button>`).join("");
}
document.addEventListener("click", (event) => {
  const link = event.target.closest("[data-goto]");
  if (link) $(`[role="tab"][data-mode="${link.dataset.goto}"]`).click();
});
$$('[role="tab"]').forEach((tab) => tab.addEventListener("click", () => {
  state.mode = tab.dataset.mode;
  renderMode();
}));
$("#suggestions").addEventListener("click", (event) => {
  const suggestion = event.target.closest(".suggestion");
  if (!suggestion || suggestion.hasAttribute("data-import")) return;
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
    { name: `${apiName(state.mode)} API`, detail: "Request received" },
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
  const timers = [];
  const at = (ms, fn) => timers.push(setTimeout(fn, ms));
  const stepMs = [2, 4, 3, 7];
  let t = 0;
  for (let i = 0; i < 5; i++) {
    at(t, () => emit({ type: "step", index: i, ms: stepMs[i - 1] }));
    t += 180;
  }
  const firstToken = (request.remote ? 900 : 560) + Math.floor(Math.random() * 280);
  const tps = Math.round((request.remote ? REMOTE_TPS : request.tps) * (0.92 + Math.random() * 0.12));
  const pieces = request.text.split(/(\s+)/);
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
    mode: apiName(state.mode),
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
    <div class="answer is-streaming"><p></p></div>
    <div class="msg-foot"></div>`;
  $(".msg-head strong", msg).textContent = model.short;
  els.conversation.append(msg);
  return msg;
}

/* Appends streamed text, starting a new paragraph at each blank line. */
function appendText(answer, text) {
  text.split(/\n{2,}/).forEach((part, i) => {
    if (i > 0) answer.append(document.createElement("p"));
    answer.lastElementChild.append(part);
  });
}

/* Tool activity in an agent reply: one expandable card per call, with arguments and result. */
const prettyJson = (json) => {
  try { return JSON.stringify(JSON.parse(json), null, 2); } catch { return json; }
};

function addToolCard(msg, e) {
  const card = document.createElement("details");
  card.className = "tool-card";
  card.dataset.call = e.callId;
  card.dataset.state = "running";
  card.innerHTML = `<summary>${svgIcon("i-tool")}<span>Called <strong></strong></span><span class="tool-state">Running…</span></summary><pre></pre>`;
  $("strong", card).textContent = e.name;
  $("pre", card).textContent = prettyJson(e.arguments);
  $(".tools", msg).append(card);
}

function finishToolCard(msg, e) {
  const card = $(`[data-call="${e.callId}"]`, msg);
  if (!card) return;
  card.dataset.state = e.isError ? "error" : "done";
  $(".tool-state", card).textContent = e.isError ? "Error" : "Done";
  const result = document.createElement("pre");
  result.textContent = prettyJson(e.result);
  card.append(result);
}

function run(text) {
  const simulated = !live || state.mode === "Workflow";
  state.hasRun = true;
  els.empty.hidden = true;
  addUserMessage(text);
  const msg = addAssistantMessage(simulated);
  const answer = $(".answer", msg);
  const steps = routeSteps();
  const model = currentModel();
  const remote = state.execution === "remote";
  const requestId = `req_${crypto.getRandomValues(new Uint32Array(1))[0].toString(16).padStart(8, "0")}`;
  let firstMs = null;

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
      appendText(answer, event.text);
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
      foot.innerHTML = `<button class="stats" type="button" data-open="trace" aria-label="Open run trace">${svgIcon("i-activity")}${((firstMs ?? 0) / 1000).toFixed(2)} s · ${stats.tps} tok/s</button>`;
    } else {
      items.forEach((li) => { if (li.dataset.state === "active") li.dataset.state = "cancelled"; });
      const failed = result === "failed";
      setStatus(failed ? "failed" : "cancelled", failed ? "Failed" : "Cancelled");
      if (firstMs === null) setMetrics();
      else { $("#m-tps").textContent = "—"; $("#m-tokens").textContent = "—"; $("#m-total").textContent = "—"; }
      if (!answer.textContent.trim()) answer.remove();
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
    if (openSheetId !== "trace") $("#trace-dot").hidden = false;
    delete els.send.dataset.running;
    els.send.setAttribute("aria-label", "Run request");
    els.send.innerHTML = svgIcon("i-arrow-up");
    updateSend();
    if (live) loadLiveModels(); // load state changed
  }

  const stop = simulated
    ? simulate({ text: MODES[state.mode].reply({ model: model.name, backend: model.backend, remote }), remote, tps: model.tps || 40 }, onEvent)
    : liveSource(text, onEvent);
  state.run = { cancel: () => { stop(); finish("cancelled"); } };
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

$("#new-session").addEventListener("click", () => {
  state.run?.cancel();
  bridge?.newSession();
  $$(".msg", els.conversation).forEach((m) => m.remove());
  els.empty.hidden = false;
  $("#request-id").textContent = "No runs yet";
  state.hasRun = false;
  state.sources = null;
  renderRuntime();
  renderTimeline(routeSteps());
  setStatus("idle", "Idle");
  setMetrics();
  $("#trace-dot").hidden = true;
  els.prompt.value = "";
  updateSend();
  toast("New session started.");
});

/* ---------- Start ---------- */
if (live) {
  // Not wired in Studio yet: memory (T2AI-025), RAG (T2AI-024).
  $(".sim-badge").hidden = true;
  state.memory = false;
  state.rag = false;
  [["#memory-switch", "Arrives with the memory engine (T2AI-025)"], ["#rag-switch", "Arrives with the RAG engine (T2AI-024)"]].forEach(([id, note]) => {
    const input = $(id);
    input.checked = false;
    input.disabled = true;
    $("small", input.closest(".switch-row")).textContent = note;
  });
  MODELS = {};
  state.model = null;
  loadLiveModels();
}
renderModels();
renderRuntime();
renderMode();
renderTimeline(routeSteps());
updateSend();
