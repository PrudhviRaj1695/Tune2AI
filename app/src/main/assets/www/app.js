const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];

const conversation = $("#conversation");
const initialConversation = conversation.innerHTML;
const composerForm = $("#composer-form");
const promptInput = $("#prompt-input");
const runButton = $("#run-request");
const toastElement = $("#toast");

let currentMode = "Chat";
let executionMode = "local";
let toastTimeout;
let isRunning = false;

const modelOptions = {
  llama: {
    name: "Llama 3.2 3B Instruct",
    backend: "llama.cpp",
    monogram: "ll",
    description: "GPU acceleration enabled",
    support: "Loaded · 2.4 GB",
    memory: "2.4 / 6 GB",
    throughput: "64 tok/s",
  },
  onnx: {
    name: "Phi-3 Mini",
    backend: "ONNX Runtime",
    monogram: "ON",
    description: "Optimized local execution",
    support: "Ready to load · 1.8 GB",
    memory: "1.8 / 6 GB",
    throughput: "52 tok/s",
  },
  coreml: {
    name: "Core ML Small Language Model",
    backend: "Core ML",
    monogram: "CM",
    description: "Requires the iOS adapter",
    support: "iOS adapter required",
    memory: "—",
    throughput: "—",
  },
};

function showToast(message) {
  toastElement.textContent = message;
  toastElement.classList.add("is-visible");
  window.clearTimeout(toastTimeout);
  toastTimeout = window.setTimeout(() => toastElement.classList.remove("is-visible"), 2600);
}

$$('[data-toast]').forEach((button) => {
  button.addEventListener("click", () => showToast(button.dataset.toast));
});

$$('[data-route]').forEach((button) => {
  button.addEventListener("click", () => {
    if (button.dataset.route === "Playground") return;
    showToast(`${button.dataset.route} is a separate Tune2AI Studio screen.`);
  });
});

function setExecutionMode(mode) {
  executionMode = mode;
  $$('[data-execution]').forEach((button) => {
    const active = button.dataset.execution === mode;
    button.classList.toggle("is-selected", active);
    button.setAttribute("aria-pressed", String(active));
  });

  const remote = mode === "remote";
  $("#mode-badge-label").textContent = remote ? "Remote mode" : "Local mode";
  $("#backend-name").textContent = remote ? "Remote API" : modelOptions[$("#model-select").value].backend;
  $("#backend-monogram").textContent = remote ? "API" : modelOptions[$("#model-select").value].monogram;
  $("#backend-description").textContent = remote ? "Request routed through the API" : modelOptions[$("#model-select").value].description;
  $("#sidebar-backend").textContent = remote ? "Remote API · selected" : `${modelOptions[$("#model-select").value].backend} · Android adapter`;
  $("#trace-route").textContent = remote ? "Remote API selected" : `${modelOptions[$("#model-select").value].backend} selected`;
  $("#throughput").textContent = remote ? "—" : modelOptions[$("#model-select").value].throughput;
  $("#memory-value").textContent = remote ? "Service-managed" : modelOptions[$("#model-select").value].memory;
  $("#memory-progress").style.width = remote ? "0%" : "40%";
}

$$('[data-execution]').forEach((button) => {
  button.addEventListener("click", () => setExecutionMode(button.dataset.execution));
});

function updateModel() {
  const model = modelOptions[$("#model-select").value];
  $("#selected-model-name").textContent = model.name;
  $("#model-backend-chip").textContent = model.backend;
  $("#model-support").textContent = model.support;
  $("#backend-name").textContent = executionMode === "remote" ? "Remote API" : model.backend;
  $("#backend-monogram").textContent = executionMode === "remote" ? "API" : model.monogram;
  $("#backend-description").textContent = executionMode === "remote" ? "Request routed through the API" : model.description;
  $("#sidebar-backend").textContent = executionMode === "remote" ? "Remote API · selected" : `${model.backend} · Android adapter`;
  $("#trace-route").textContent = executionMode === "remote" ? "Remote API selected" : `${model.backend} selected`;
  $("#throughput").textContent = executionMode === "remote" ? "—" : model.throughput;
  $("#memory-value").textContent = executionMode === "remote" ? "Service-managed" : model.memory;
  $("#memory-progress").style.width = executionMode === "remote" ? "0%" : model.memory === "2.4 / 6 GB" ? "40%" : "31%";
}

$("#model-select").addEventListener("change", updateModel);

$$('[data-mode]').forEach((button) => {
  button.addEventListener("click", () => {
    currentMode = button.dataset.mode;
    $$('[data-mode]').forEach((tab) => {
      const active = tab === button;
      tab.classList.toggle("is-active", active);
      tab.setAttribute("aria-selected", String(active));
    });
    const placeholders = {
      Chat: "Ask a follow-up…",
      Agent: "Describe a task for your agent…",
      Workflow: "Describe the workflow input…",
    };
    promptInput.placeholder = placeholders[currentMode];
  });
});

$$('[data-context]').forEach((button) => {
  button.addEventListener("click", () => {
    const enabled = button.getAttribute("aria-pressed") !== "true";
    button.setAttribute("aria-pressed", String(enabled));
    $(".toggle-state", button).textContent = enabled ? "On" : "Off";
    if (button.dataset.context === "memory") {
      $("#memory-source").hidden = !enabled;
      $("#trace-context").textContent = enabled ? "2 memory items" : "Memory disabled";
    } else {
      $("#rag-source").hidden = !enabled;
      const description = $(".setting-copy small", button);
      description.textContent = enabled ? "Retrieve 3 sources" : "Retrieval disabled";
      if (enabled) $("#trace-context").textContent = "Memory + 3 RAG sources";
      else $("#trace-context").textContent = $("[data-context='memory']").getAttribute("aria-pressed") === "true" ? "2 memory items" : "No context sources";
    }
  });
});

function updateRange(input, output, formatter, maxValue) {
  const value = Number(input.value);
  output.textContent = formatter(value);
  const fraction = ((value - Number(input.min)) / (Number(input.max) - Number(input.min))) * 100;
  input.style.background = `linear-gradient(to right, var(--brand) ${fraction}%, var(--border) ${fraction}%)`;
}

const temperature = $("#temperature");
const temperatureValue = $("#temperature-value");
const maxTokens = $("#max-tokens");
const tokenValue = $("#token-value");
temperature.addEventListener("input", () => updateRange(temperature, temperatureValue, (n) => n.toFixed(1)));
maxTokens.addEventListener("input", () => updateRange(maxTokens, tokenValue, (n) => n.toLocaleString("en-US")));
updateRange(temperature, temperatureValue, (n) => n.toFixed(1));
updateRange(maxTokens, tokenValue, (n) => n.toLocaleString("en-US"));

function timeLabel() {
  return new Intl.DateTimeFormat(undefined, { hour: "numeric", minute: "2-digit" }).format(new Date());
}

function setRunStatus(status) {
  const statusElement = $("#run-status");
  statusElement.textContent = status;
  statusElement.classList.toggle("pill-success", status === "Completed");
  statusElement.classList.toggle("pill-running", status === "Running");
}

function makeRequestId() {
  const suffix = Math.random().toString(16).slice(2, 10);
  return `req_${suffix}`;
}

function appendUserMessage(text) {
  const wrapper = document.createElement("div");
  wrapper.className = "message message-user";
  const meta = document.createElement("span");
  meta.className = "message-meta";
  meta.textContent = `You · ${timeLabel()}`;
  const bubble = document.createElement("p");
  bubble.className = "user-bubble";
  bubble.textContent = text;
  wrapper.append(meta, bubble);
  conversation.append(wrapper);
}

function responseText() {
  if (currentMode === "Agent") {
    return "Agent run complete. The Agent Engine checked the available permissions and returned this sample result. Add an approved tool to see tool activity in the trace.";
  }
  if (currentMode === "Workflow") {
    return "Workflow run complete. The Workflow Engine processed the request sequence and returned this sample result. Checkpoints and step results are ready to inspect.";
  }
  return "This sample response completed through the selected Tune2AI path. In the full SDK flow, the request controller routes the task, context is assembled, and the inference backend streams the result.";
}

function appendAssistantMessage(text) {
  const article = document.createElement("article");
  article.className = "message message-assistant";
  const meta = document.createElement("div");
  meta.className = "assistant-meta";
  const avatar = document.createElement("span");
  avatar.className = "assistant-avatar";
  avatar.textContent = "T2";
  const label = document.createElement("strong");
  label.textContent = `Tune2AI · ${modelOptions[$("#model-select").value].name}`;
  const mode = document.createElement("span");
  mode.className = "pill pill-success";
  mode.textContent = executionMode === "local" ? "On-device" : "Remote";
  meta.append(avatar, label, mode);

  const demo = document.createElement("span");
  demo.className = "demo-label";
  demo.textContent = "Prototype response";
  const paragraph = document.createElement("p");
  paragraph.className = "assistant-follow-up";
  paragraph.style.color = "var(--ink)";
  paragraph.style.fontSize = "11px";
  paragraph.style.lineHeight = "1.5";
  paragraph.textContent = text;
  article.append(meta, demo, paragraph);
  if ($("[data-context='memory']").getAttribute("aria-pressed") === "true" || $("[data-context='rag']").getAttribute("aria-pressed") === "true") {
    const chips = document.createElement("div");
    chips.className = "context-chips";
    if ($("[data-context='memory']").getAttribute("aria-pressed") === "true") chips.append(createChip("Memory · 2 items"));
    if ($("[data-context='rag']").getAttribute("aria-pressed") === "true") chips.append(createChip("RAG · 3 sources"));
    article.append(chips);
  }
  conversation.append(article);
}

function createChip(text) {
  const chip = document.createElement("span");
  chip.className = "subtle-chip";
  chip.textContent = text;
  return chip;
}

function updateTraceAfterRun() {
  $("#request-id").textContent = makeRequestId();
  $("#trace-route").textContent = executionMode === "remote" ? "Remote API selected" : `${modelOptions[$("#model-select").value].backend} selected`;
  const memoryOn = $("[data-context='memory']").getAttribute("aria-pressed") === "true";
  const ragOn = $("[data-context='rag']").getAttribute("aria-pressed") === "true";
  $("#trace-context").textContent = [memoryOn ? "2 memory items" : "", ragOn ? "3 RAG sources" : ""].filter(Boolean).join(" + ") || "No context sources";
  $("#first-token").textContent = executionMode === "remote" ? "1.1 s" : "0.8 s";
  $("#throughput").textContent = executionMode === "remote" ? "—" : modelOptions[$("#model-select").value].throughput;
  $$(".trace-state").forEach((node) => { node.textContent = "OK"; });
  $$(".trace-number").forEach((node) => { node.classList.remove("is-running"); });
}

composerForm.addEventListener("submit", (event) => {
  event.preventDefault();
  if (isRunning) return;
  const prompt = promptInput.value.trim();
  if (!prompt) {
    showToast("Enter a prompt before running a request.");
    promptInput.focus();
    return;
  }

  isRunning = true;
  appendUserMessage(prompt);
  promptInput.value = "";
  runButton.disabled = true;
  runButton.textContent = "Running…";
  setRunStatus("Running");
  $("#request-id").textContent = makeRequestId();
  $("#first-token").textContent = "…";
  $$(".trace-state").forEach((node) => { node.textContent = "…"; });
  $$(".trace-number").forEach((node) => node.classList.add("is-running"));
  conversation.scrollTop = conversation.scrollHeight;

  window.setTimeout(() => {
    appendAssistantMessage(responseText());
    updateTraceAfterRun();
    setRunStatus("Completed");
    runButton.disabled = false;
    runButton.textContent = "Run request";
    isRunning = false;
    conversation.scrollTop = conversation.scrollHeight;
  }, 850);
});

promptInput.addEventListener("keydown", (event) => {
  if (event.key === "Enter" && (event.metaKey || event.ctrlKey)) {
    event.preventDefault();
    composerForm.requestSubmit();
  }
});

$("#new-session").addEventListener("click", () => {
  if (isRunning) return;
  conversation.innerHTML = initialConversation;
  promptInput.value = "";
  setRunStatus("Completed");
  $("#request-id").textContent = "req_8f2a_0198";
  $("#first-token").textContent = executionMode === "remote" ? "—" : "0.8 s";
  updateTraceAfterRun();
  showToast("New session started.");
});

$("#composer-context").addEventListener("click", () => {
  showToast("Use the Context Manager settings to choose included sources.");
});
