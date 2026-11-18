const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const svgIcon = (id) => `<svg class="icon" aria-hidden="true"><use href="#${id}" /></svg>`;
const wide = matchMedia("(min-width: 1200px)");
const finePointer = matchMedia("(pointer: fine)");

const MODELS = {
  llama: { name: "Llama 3.2 3B Instruct", short: "Llama 3.2 3B", backend: "llama.cpp", mono: "GG", size: "2.4 GB", memory: "2.4 / 6 GB", memoryPct: 40, tps: 64, state: "Loaded" },
  onnx: { name: "Phi-3 Mini", short: "Phi-3 Mini", backend: "ONNX Runtime", mono: "OX", size: "1.8 GB", memory: "1.8 / 6 GB", memoryPct: 30, tps: 52, state: "Ready" },
  coreml: { name: "Core ML small model", short: "Core ML", backend: "Core ML", mono: "ML", size: "1.1 GB", unavailable: "Needs the iOS adapter" },
};
const REMOTE_TPS = 88;

const MODES = {
  Chat: {
    title: "Ask anything. Watch the whole route.",
    copy: "Every request runs the path your app will: API, controller, router, context, inference.",
    placeholder: "Message Tune2AI…",
    suggestions: ["How does on-device inference protect user data?", "Draft a release note for our chat feature", "Compare 3B and 8B models on a mid-range phone"],
    reply: (c) => c.remote
      ? `This request went to the Remote API backend over HTTPS and streamed back as SSE events. Routing, context assembly, and the event stream are identical to an on-device run, so switching back is one setting.\n\nUse remote for large models or long contexts; keep on-device as the default for privacy and offline use.`
      : `Prompts and outputs never leave this phone: ${c.backend} runs ${c.model} locally, so there is no server to log data and replies keep working offline.\n\nThe trade-off is device headroom. The Model Manager checks free memory before loading, and the Device Resource Monitor can unload idle models under pressure so your app stays responsive.\n\nWant me to draft the privacy note for your release?`,
  },
  Agent: {
    title: "Give an agent a task. See every tool call.",
    copy: "Agents run bounded steps with tools your app registers, and stop at the limits you set.",
    placeholder: "Describe a task for the agent…",
    suggestions: ["Check device headroom before loading a second model", "Plan a test run for the llama.cpp backend", "Find stale workflow checkpoints and propose a cleanup"],
    reply: () => `Done in 3 of 6 allowed steps.\n\n1 · resource_monitor → 3.6 GB free, thermal nominal\n2 · list_models → 1 loaded, 1 ready\n3 · Drafted the plan below\n\nLoading a second model fits now, but unload it when the app goes to the background. Every tool input was validated against its schema before it ran.`,
  },
  Workflow: {
    title: "Chain model and tool steps into one run.",
    copy: "Workflows checkpoint after each step, so a run can resume after the app restarts.",
    placeholder: "Describe the workflow input…",
    suggestions: ["Summarize → classify → draft a reply to a support ticket", "Translate, check tone, then shorten", "Extract action items from meeting notes"],
    reply: () => `Workflow finished: 3 of 3 steps.\n\nSummarize → 2 sentences\nClassify → "billing question"\nDraft reply → 86 words, friendly tone\n\nEach step was checkpointed to local storage, so this run could resume after an app restart without repeating completed steps.`,
  },
};

const state = { mode: "Chat", execution: "local", model: "llama", memory: true, rag: false, run: null };
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
  if (wide.matches) return;
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
wide.addEventListener("change", () => closeSheet());

/* ---------- Setup ---------- */
function renderModels() {
  $("#model-list").innerHTML = Object.entries(MODELS).map(([key, m]) => `
    <label class="model-option">
      <input type="radio" name="model" value="${key}" ${key === state.model ? "checked" : ""} ${m.unavailable ? "disabled" : ""} />
      <span class="mono-tile" aria-hidden="true">${m.mono}</span>
      <span class="model-text"><strong>${m.name}</strong><small>${m.backend} · ${m.size}${m.unavailable ? ` · ${m.unavailable}` : ""}</small></span>
      <span class="model-state" data-state="${(m.state || "").toLowerCase()}">${m.unavailable ? "Unavailable" : m.state}</span>
    </label>`).join("");
}

function contextItems() {
  return [
    { label: "Prompt v3", detail: "Helpful assistant v3" },
    state.memory && { label: "Memory", detail: "Session memory · 2 items" },
    state.rag && { label: "RAG", detail: "RAG sources · 3 chunks" },
  ].filter(Boolean);
}

function renderRuntime() {
  const model = MODELS[state.model];
  const remote = state.execution === "remote";
  const chip = $("#model-chip");
  $("#model-chip-label").textContent = model.short;
  chip.toggleAttribute("data-remote", remote);
  $(".model-chip-kind use", chip).setAttribute("href", remote ? "#i-cloud" : "#i-chip");
  chip.setAttribute("aria-label", `${model.short}, ${remote ? "remote" : "on-device"}. Change run setup`);
  $("#rail-backend").textContent = remote ? "Remote API" : `${model.backend} · Android`;
  $("#execution-hint").textContent = remote
    ? "Sent to the Remote API backend over HTTPS. Same route, same events."
    : "Runs on this phone. Nothing leaves the device.";
  $("#memory-value").textContent = remote ? "0.3 / 6 GB" : model.memory;
  $("#memory-meter").style.width = `${remote ? 5 : model.memoryPct}%`;

  const items = contextItems();
  $("#included-list").innerHTML = items.map((i) => `<li>${svgIcon("i-check")}${i.detail}</li>`).join("");
  $("#context-pills").innerHTML = items.map((i) => `<button class="chip" type="button" data-open="setup">${i.label}</button>`).join("");
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
  $("#empty-title").textContent = mode.title;
  $("#empty-copy").textContent = mode.copy;
  els.prompt.placeholder = mode.placeholder;
  $("#suggestions").innerHTML = mode.suggestions
    .map((s) => `<button class="suggestion" type="button">${svgIcon("i-spark")}<span>${s}</span></button>`).join("");
}
$$('[role="tab"]').forEach((tab) => tab.addEventListener("click", () => {
  state.mode = tab.dataset.mode;
  renderMode();
}));
$("#suggestions").addEventListener("click", (event) => {
  const suggestion = event.target.closest(".suggestion");
  if (!suggestion) return;
  els.prompt.value = suggestion.textContent.trim();
  updateSend();
  els.prompt.focus();
});

/* ---------- Trace ---------- */
const ROUTE_LABELS = ["API", "Controller", "Router", "Context", "Inference"];

function routeSteps() {
  const model = MODELS[state.model];
  const remote = state.execution === "remote";
  const context = contextItems().map((i) => i.label).join(" + ");
  return [
    { name: `${state.mode} API`, detail: "Request received" },
    { name: "Request Controller", detail: "Validated · deadline 30 s" },
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

function addAssistantMessage() {
  const model = MODELS[state.model];
  const msg = document.createElement("article");
  msg.className = "msg";
  msg.innerHTML = `
    <div class="msg-head">
      <svg class="avatar" viewBox="0 0 32 32" aria-hidden="true"><rect width="32" height="32" rx="9" fill="url(#brand-gradient)" /><path d="M6 17c2 0 2-6 4-6s2 10 4 10 2-13 4-13 2 9 4 9 2-4 4-4" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" /></svg>
      <strong>Tune2AI</strong><span>${state.execution === "remote" ? "Remote" : "On-device"} · ${model.short}</span>
    </div>
    <div class="route" aria-label="Request route">${ROUTE_LABELS.map((l, i) => `${i ? '<span class="route-link"></span>' : ""}<span class="route-step" data-state="idle">${l}</span>`).join("")}</div>
    <div class="answer is-streaming"><p></p></div>
    <div class="msg-foot"></div>`;
  els.conversation.append(msg);
  return msg;
}

function run(text) {
  els.empty.hidden = true;
  addUserMessage(text);
  const msg = addAssistantMessage();
  const routeEls = $$(".route-step", msg);
  const answer = $(".answer", msg);
  const steps = routeSteps();
  const model = MODELS[state.model];
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
      routeEls.forEach((el, i) => { el.dataset.state = i < event.index ? "done" : i === event.index ? "active" : "idle"; });
      items.forEach((li, i) => { li.dataset.state = i < event.index ? "done" : i === event.index ? "active" : "idle"; });
      if (event.index > 0) $(".step-time", items[event.index - 1]).textContent = `${event.ms} ms`;
    } else if (event.type === "first-token") {
      firstMs = event.ms;
      $("#m-first").textContent = `${(event.ms / 1000).toFixed(2)} s`;
    } else if (event.type === "delta") {
      if (/\n\n/.test(event.text)) answer.append(document.createElement("p"));
      else answer.lastElementChild.append(event.text);
    } else if (event.type === "completed") {
      finish("ok", event);
    }
  });

  function finish(result, stats = {}) {
    state.run = null;
    answer.classList.remove("is-streaming");
    const foot = $(".msg-foot", msg);
    if (result === "ok") {
      routeEls.forEach((el) => { el.dataset.state = "done"; });
      items.forEach((li) => { li.dataset.state = "done"; });
      $(".step-time", items[4]).textContent = `${stats.totalMs} ms`;
      setStatus("ok", "Completed");
      setMetrics(`${(firstMs / 1000).toFixed(2)} s`, `${stats.tps} tok/s`, `${stats.tokens} tok`, `${(stats.totalMs / 1000).toFixed(1)} s`);
      foot.innerHTML = `<button class="chip chip-stats" type="button" data-open="trace">${svgIcon("i-activity")}${(firstMs / 1000).toFixed(2)} s · ${stats.tps} tok/s · ${remote ? "Remote API" : model.backend}</button>`;
    } else {
      routeEls.forEach((el) => { if (el.dataset.state !== "done") el.dataset.state = "idle"; });
      items.forEach((li) => { if (li.dataset.state === "active") li.dataset.state = "cancelled"; });
      setStatus("cancelled", "Cancelled");
      if (firstMs === null) setMetrics();
      else { $("#m-tps").textContent = "—"; $("#m-tokens").textContent = "—"; $("#m-total").textContent = "—"; }
      if (!answer.textContent.trim()) answer.remove();
      foot.innerHTML = `<span class="chip" data-state="cancelled">Cancelled</span>`;
    }
    foot.insertAdjacentHTML("beforeend", contextItems().map((i) => `<span class="chip">${i.label}</span>`).join(""));
    if (!wide.matches && openSheetId !== "trace") $("#trace-dot").hidden = false;
    delete els.send.dataset.running;
    els.send.setAttribute("aria-label", "Run request");
    els.send.innerHTML = svgIcon("i-arrow-up");
    updateSend();
  }

  const stop = simulate({ text: MODES[state.mode].reply({ model: model.name, backend: model.backend, remote }), remote, tps: model.tps }, onEvent);
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
  $$(".msg", els.conversation).forEach((m) => m.remove());
  els.empty.hidden = false;
  $("#request-id").textContent = "No runs yet";
  renderTimeline(routeSteps());
  setStatus("idle", "Idle");
  setMetrics();
  $("#trace-dot").hidden = true;
  els.prompt.value = "";
  updateSend();
  toast("New session started.");
});

/* ---------- Start ---------- */
renderModels();
renderRuntime();
renderMode();
renderTimeline(routeSteps());
updateSend();
