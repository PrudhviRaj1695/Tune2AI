package com.tune2ai.studio;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.tune2ai.sdk.Tune2AI;
import com.tune2ai.sdk.api.AgentLimits;
import com.tune2ai.sdk.api.BackendInfo;
import com.tune2ai.sdk.api.Cancellable;
import com.tune2ai.sdk.api.ModelDescriptor;
import com.tune2ai.sdk.api.ModelInfo;
import com.tune2ai.sdk.api.ModelState;
import com.tune2ai.sdk.api.RequestOptions;
import com.tune2ai.sdk.api.ResourceSnapshot;
import com.tune2ai.sdk.api.Task;
import com.tune2ai.sdk.api.Tune2AIEvent;
import com.tune2ai.sdk.llama.LlamaBackend;
import com.tune2ai.sdk.remote.RemoteBackend;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The page's only door into the SDK, exposed to JavaScript as {@code window.Tune2AIBridge}.
 * Events go back through {@code window.tune2ai.onEvent(...)}. MainActivity keeps the WebView on
 * the bundled page, so no other page can reach these methods.
 */
final class StudioBridge {
    static final int IMPORT_REQUEST = 41;
    static final int FOLDER_REQUEST = 42;
    static final int FILES_REQUEST = 43;
    static final int CONNECT_REQUEST = 44;
    private static final String SESSION = "studio";
    private static final int CONTEXT_LENGTH = ModelAdvisor.CONTEXT_LENGTH;
    private static final String REMOTE_MODEL_ID = "remote-model";

    private final Activity activity;
    private final WebView webView;
    private final Tune2AI ai;
    private final RemoteSettings remote;
    private final File modelsDir;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService downloads = Executors.newSingleThreadExecutor();
    private final AtomicBoolean downloadCancelled = new AtomicBoolean();
    private volatile String downloadingId;
    private final Map<String, Cancellable> running = new ConcurrentHashMap<>();
    private final KnowledgeConnector connector;
    private final ExecutorService indexer = Executors.newSingleThreadExecutor();
    private volatile JSONObject indexing; // current indexing progress, or null
    private final Connections connections;
    private final ChatStore chats;
    private volatile String pendingConnect; // connection waiting for a permission answer or a settings visit

    private final AgentTools agentTools;

    StudioBridge(Activity activity, WebView webView, Tune2AI ai, RemoteSettings remote, PhoneContextSource phone, AgentTools agentTools) {
        this.activity = activity;
        this.webView = webView;
        this.ai = ai;
        this.remote = remote;
        this.modelsDir = new File(activity.getFilesDir(), "models");
        this.connector = new KnowledgeConnector(activity, ai.getKnowledge());
        this.chats = new ChatStore(new File(activity.getFilesDir(), "chats"));
        this.connections = new Connections(activity, ai.getKnowledge(), connector);
        phone.attach(connections);
        this.agentTools = agentTools;
        agentTools.attach(connections, ai.getKnowledge(), ai.getPrivacy());
        restoreSettings();
        indexer.execute(this::syncDueConnections);
        android.content.SharedPreferences prefs = activity.getSharedPreferences("knowledge", Activity.MODE_PRIVATE);
        ai.getKnowledge().setEnabled(prefs.getBoolean("enabled", true));
        ai.getKnowledge().setPassagesPerAnswer(prefs.getInt("passages", 3));
        ai.getKnowledge().setMinSimilarity(prefs.getFloat("minSimilarity", ai.getKnowledge().getMinSimilarity()));
        io.execute(this::registerModelsInStorage);
    }

    /** Starts a chat request. Events are tagged with the page's own {@code id}. */
    @JavascriptInterface
    public void send(String requestJson) {
        try {
            JSONObject req = new JSONObject(requestJson);
            String id = req.getString("id");
            RequestOptions options = new RequestOptions(
                    req.isNull("model") ? null : req.getString("model"),
                    req.isNull("backend") ? null : req.getString("backend"),
                    120_000L,
                    req.optInt("maxTokens", 512),
                    (float) req.optDouble("temperature", 0.7),
                    null,
                    Collections.singletonMap("today", MainActivity.today()));
            String prompt = req.getString("prompt");
            Cancellable handle = "Agent".equals(req.optString("mode"))
                    ? ai.getAgent().stream(prompt, agentToolsFor(req), new AgentLimits(8, 6), options, event -> deliver(id, event))
                    : ai.getChat().session(sessionOf(req)).stream(prompt, options, event -> deliver(id, event));
            running.put(id, handle);
        } catch (JSONException e) {
            notice("Could not read the request: " + e.getMessage());
        }
    }

    /** All tools, or (privacy guard) none that read personal data when a remote model runs the agent. */
    private java.util.Set<String> agentToolsFor(JSONObject req) {
        boolean remoteRun = "remote".equals(req.optString("backend"));
        if (!remoteRun || ai.getPrivacy().getPersonalContextWithRemote()) return null;
        java.util.Set<String> allowed = new java.util.HashSet<>();
        for (com.tune2ai.sdk.api.Tool t : agentTools.tools()) if (!AgentTools.PERSONAL.contains(t.getName())) allowed.add(t.getName());
        allowed.add("current_time");
        allowed.add("device_status");
        allowed.add("list_models");
        return allowed;
    }

    // ---------- Settings: system prompt, privacy guard, chunking ----------

    private static final String PROMPT_ID = "helpful-assistant";
    private static final int FIRST_CUSTOM_VERSION = 100; // above the built-in versions

    private android.content.SharedPreferences settingsPrefs() {
        return activity.getSharedPreferences("studio-settings", Activity.MODE_PRIVATE);
    }

    /** Applies saved settings to the SDK at startup. */
    private void restoreSettings() {
        android.content.SharedPreferences p = settingsPrefs();
        ai.getPrivacy().setRedactSensitive(p.getBoolean("redact", true));
        ai.getPrivacy().setPersonalContextWithRemote(p.getBoolean("remoteContext", false));
        ai.getKnowledge().setChunking(com.tune2ai.sdk.services.rag.ChunkingStrategy.of(p.getString("chunking", "smart")));
        ai.getKnowledge().setIncludeNeighbors(p.getBoolean("neighbors", false));
        String prompt = p.getString("prompt", null);
        if (prompt != null) registerPrompt(prompt, p.getInt("promptVersion", FIRST_CUSTOM_VERSION));
    }

    /** Everything the settings controls show. */
    @JavascriptInterface
    public String settings() {
        android.content.SharedPreferences p = settingsPrefs();
        try {
            JSONArray strategies = new JSONArray();
            for (com.tune2ai.sdk.services.rag.ChunkingStrategy s : com.tune2ai.sdk.services.rag.ChunkingStrategy.ALL) {
                strategies.put(new JSONObject().put("id", s.getId()).put("window", s.getWindowTokens()).put("overlap", s.getOverlapTokens())
                        .put("mode", s.getMode().name()));
            }
            return new JSONObject()
                    .put("prompt", p.getString("prompt", MainActivity.SYSTEM_PROMPT))
                    .put("defaultPrompt", MainActivity.SYSTEM_PROMPT)
                    .put("customPrompt", p.contains("prompt"))
                    .put("promptVersion", p.contains("prompt") ? p.getInt("promptVersion", FIRST_CUSTOM_VERSION) : 5)
                    .put("redact", ai.getPrivacy().getRedactSensitive())
                    .put("remoteContext", ai.getPrivacy().getPersonalContextWithRemote())
                    .put("chunking", ai.getKnowledge().getChunking().getId())
                    .put("neighbors", ai.getKnowledge().getIncludeNeighbors())
                    .put("strategies", strategies)
                    .toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    /**
     * Saves a new system prompt and uses it from the next message. Returns "" on success or an error.
     * The only placeholder allowed is {{today}}; if it is missing, the date line is added so answers
     * about "today" keep working. An empty text restores the built-in prompt.
     */
    @JavascriptInterface
    public String setSystemPrompt(String text) {
        String t = text == null ? "" : text.trim();
        android.content.SharedPreferences p = settingsPrefs();
        int version = p.getInt("promptVersion", FIRST_CUSTOM_VERSION - 1) + 1;
        if (t.isEmpty()) {
            registerPrompt(MainActivity.SYSTEM_PROMPT, version);
            p.edit().remove("prompt").putInt("promptVersion", version).apply();
            return "";
        }
        if (t.length() > 4_000) return "Keep the prompt under 4,000 characters; long prompts leave less room for your sources.";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\}\\}").matcher(t);
        while (m.find()) {
            if (!"today".equals(m.group(1))) return "Only {{today}} can be used as a placeholder; remove {{" + m.group(1) + "}}.";
        }
        if (!t.contains("{{today}}") && !t.contains("{{ today }}")) t = t + "\n\nIt is now {{today}}.";
        try {
            registerPrompt(t, version);
        } catch (RuntimeException e) {
            return "That prompt could not be used: " + e.getMessage();
        }
        p.edit().putString("prompt", t).putInt("promptVersion", version).apply();
        return "";
    }

    private void registerPrompt(String text, int version) {
        ai.addPromptTemplate(new com.tune2ai.sdk.services.prompt.PromptTemplate(PROMPT_ID, version, text, Collections.singleton("today")));
    }

    @JavascriptInterface
    public void setPrivacy(boolean redact, boolean remoteContext) {
        ai.getPrivacy().setRedactSensitive(redact);
        ai.getPrivacy().setPersonalContextWithRemote(remoteContext);
        settingsPrefs().edit().putBoolean("redact", redact).putBoolean("remoteContext", remoteContext).apply();
    }

    /** Chunking strategy for new documents, and neighbour expansion. "Apply" re-splits existing ones. */
    @JavascriptInterface
    public void setChunking(String id, boolean neighbors) {
        ai.getKnowledge().setChunking(com.tune2ai.sdk.services.rag.ChunkingStrategy.of(id));
        ai.getKnowledge().setIncludeNeighbors(neighbors);
        settingsPrefs().edit().putString("chunking", id).putBoolean("neighbors", neighbors).apply();
        knowledgeChanged();
    }

    @JavascriptInterface
    public void rechunkKnowledge() {
        runIndexing("Re-splitting documents", p -> {
            KnowledgeConnector.Summary s = new KnowledgeConnector.Summary();
            s.added = ai.getKnowledge().rechunkBlocking((done, total) -> p.update(done, total, ""));
            return s;
        });
    }

    /** Each saved chat has its own SDK session (model-facing history) with the same id. */
    private static String sessionOf(JSONObject req) {
        String id = req.optString("session");
        return ChatStore.validId(id) ? "chat-" + id : SESSION;
    }

    // ---------- Chats list (saved conversations) ----------

    @JavascriptInterface
    public String listChats() {
        return chats.list();
    }

    @JavascriptInterface
    public String loadChat(String id) {
        return chats.load(id);
    }

    @JavascriptInterface
    public void saveChat(String json) {
        try {
            chats.save(json);
        } catch (JSONException | IOException e) {
            notice("Could not save this chat: " + e.getMessage());
        }
    }

    @JavascriptInterface
    public void renameChat(String id, String title) {
        try {
            chats.rename(id, title);
        } catch (JSONException | IOException e) {
            notice("Could not rename this chat: " + e.getMessage());
        }
    }

    /** Deletes the saved chat and the model-facing history behind it. */
    @JavascriptInterface
    public void deleteChat(String id) {
        if (!ChatStore.validId(id)) return;
        try {
            chats.delete(id);
        } catch (IOException e) {
            notice("Could not delete this chat: " + e.getMessage());
        }
        ai.getChat().session("chat-" + id).clearAsync();
    }

    /**
     * Runs an action the agent prepared (send_sms, add_calendar_event, set_alarm, call, share), only
     * when the user taps its button. Opens the system app filled in; the user confirms there.
     */
    @JavascriptInterface
    public void performAction(String tool, String argumentsJson) {
        if (!AgentTools.ACTIONS.contains(tool)) return;
        try {
            Intent intent = agentTools.intentFor(tool, new JSONObject(argumentsJson));
            activity.runOnUiThread(() -> {
                try {
                    activity.startActivity(intent);
                } catch (android.content.ActivityNotFoundException e) {
                    notice("No app on this phone can do that.");
                }
            });
        } catch (JSONException | java.text.ParseException e) {
            notice("Could not prepare that action: " + e.getMessage());
        } catch (SecurityException e) {
            notice(e.getMessage()); // privacy guard refused it
        }
    }

    @JavascriptInterface
    public void cancel(String id) {
        Cancellable handle = running.remove(id);
        if (handle != null) handle.cancel();
    }

    @JavascriptInterface
    public void newSession() {
        // A new chat only stops what is running; earlier chats keep their history (Chats list).
        for (Cancellable handle : running.values()) handle.cancel();
        running.clear();
    }

    /**
     * Sets the remote endpoint. Returns an error message, or "" on success. The key stays in memory;
     * an empty key keeps the current one.
     */
    @JavascriptInterface
    public String setRemote(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String url = o.optString("baseUrl").trim();
            String model = o.optString("model").trim();
            String key = o.optString("apiKey").trim();
            if (url.isEmpty() || model.isEmpty()) return "Enter the API base URL and a model name.";
            String invalid = RemoteBackend.validateBaseUrl(url);
            if (invalid != null) return invalid;
            remote.baseUrl = url;
            remote.model = model;
            if (!key.isEmpty()) remote.apiKey = key;
            io.execute(() -> {
                ai.getModels().registerBlocking(new ModelDescriptor(REMOTE_MODEL_ID, model, RemoteBackend.FORMAT, model,
                        0, 8192, Collections.singleton(Task.CHAT), Collections.emptySet(), "1"));
                modelsChanged();
            });
            return "";
        } catch (JSONException e) {
            return "Could not read the remote settings.";
        }
    }

    /** Remote endpoint status. Never includes the API key itself. */
    @JavascriptInterface
    public String remoteStatus() {
        JSONObject o = new JSONObject();
        try {
            o.put("baseUrl", remote.baseUrl == null ? "" : remote.baseUrl);
            o.put("model", remote.model == null ? "" : remote.model);
            o.put("hasKey", remote.apiKey != null && !remote.apiKey.isEmpty());
            for (BackendInfo b : ai.backends()) {
                if (b.getId().equals("remote") && b.getUnavailableReason() != null) o.put("unavailable", b.getUnavailableReason());
            }
        } catch (JSONException ignored) {
            // values above are always valid
        }
        return o.toString();
    }

    /** Registered models and their state, as a JSON array. */
    @JavascriptInterface
    public String listModels() {
        JSONArray out = new JSONArray();
        for (ModelInfo info : ai.getModels().listBlocking()) {
            ModelDescriptor m = info.getDescriptor();
            ModelState state = info.getState();
            JSONObject o = new JSONObject();
            try {
                o.put("id", m.getId());
                o.put("name", m.getName());
                o.put("format", m.getFormat());
                o.put("sizeBytes", new File(m.getPath()).length());
                o.put("memoryBytes", m.getMemoryBytes());
                o.put("tasks", new JSONArray(m.getTasks().stream().map(Enum::name).collect(java.util.stream.Collectors.toList())));
                o.put("state", state == null ? "Unloaded" : state.getClass().getSimpleName());
                if (state instanceof ModelState.Unavailable) o.put("reason", ((ModelState.Unavailable) state).getReason());
                if (state instanceof ModelState.Failed) o.put("reason", ((ModelState.Failed) state).getReason());
                out.put(o);
            } catch (JSONException ignored) {
                // keys and values above are always valid
            }
        }
        return out.toString();
    }

    @JavascriptInterface
    public String resources() {
        ResourceSnapshot s = ai.resources();
        JSONObject o = new JSONObject();
        try {
            o.put("availableBytes", s.getAvailableMemoryBytes());
            o.put("totalBytes", s.getTotalMemoryBytes());
            o.put("lowMemory", s.getLowMemory());
            o.put("thermal", s.getThermal().name());
            o.put("batteryPercent", s.getBatteryPercent() == null ? JSONObject.NULL : s.getBatteryPercent());
        } catch (JSONException ignored) {
            // values above are always valid
        }
        return o.toString();
    }

    /**
     * This phone's specs and every catalog model rated for it: which is best, which fit, and why
     * the rest do not. Read on each call, so free memory and storage are current.
     */
    @JavascriptInterface
    public String deviceInfo() {
        ActivityManager am = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mem);
        long freeStorage = activity.getFilesDir().getUsableSpace();
        boolean supported = Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a")
                || Arrays.asList(Build.SUPPORTED_ABIS).contains("x86_64");
        java.util.Set<String> installed = new java.util.HashSet<>();
        for (ModelInfo info : ai.getModels().listBlocking()) installed.add(info.getDescriptor().getId());

        JSONObject o = new JSONObject();
        try {
            JSONObject d = new JSONObject();
            d.put("name", deviceName());
            d.put("chip", chipName());
            d.put("cores", Runtime.getRuntime().availableProcessors());
            d.put("android", Build.VERSION.RELEASE);
            d.put("abi", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "");
            d.put("totalRamBytes", mem.totalMem);
            d.put("availableRamBytes", mem.availMem);
            d.put("freeStorageBytes", freeStorage);
            d.put("supported", supported);
            o.put("device", d);
            JSONArray models = new JSONArray();
            for (ModelAdvisor.Assessment a : ModelAdvisor.assess(mem.totalMem, freeStorage, supported)) {
                JSONObject m = new JSONObject();
                m.put("id", a.entry.id);
                m.put("name", a.entry.name);
                m.put("sizeBytes", a.entry.sizeBytes);
                m.put("memoryBytes", a.entry.memoryBytes());
                m.put("blurb", a.entry.blurb);
                m.put("fit", a.fit.name().toLowerCase(Locale.ROOT).replace('_', '-'));
                m.put("reason", a.reason);
                m.put("installed", installed.contains(a.entry.id));
                m.put("downloading", a.entry.id.equals(downloadingId));
                models.put(m);
            }
            o.put("models", models);
        } catch (JSONException ignored) {
            // values above are always valid
        }
        return o.toString();
    }

    /**
     * Downloads a catalog model, then registers it. One download at a time. Progress and the
     * result arrive through {@code window.tune2ai.onDownload}.
     */
    @JavascriptInterface
    public void downloadModel(String id) {
        ModelAdvisor.Entry entry = ModelAdvisor.find(id);
        if (entry == null) {
            notice("Unknown model.");
            return;
        }
        if (downloadingId != null) {
            notice("Another model is downloading. Wait for it to finish or cancel it.");
            return;
        }
        downloadingId = id;
        downloadCancelled.set(false);
        keepScreenOn(true);
        downloads.execute(() -> runDownload(entry));
    }

    @JavascriptInterface
    public void cancelDownload() {
        downloadCancelled.set(true);
    }

    private void runDownload(ModelAdvisor.Entry entry) {
        try {
            File file = ModelDownloader.download(entry, modelsDir, downloadCancelled,
                    (received, total) -> downloadEvent(entry.id, "running", received, total, null));
            if (!isGguf(file)) {
                file.delete();
                throw new IOException("The downloaded file is not a valid GGUF model");
            }
            ai.getModels().registerBlocking(descriptorFor(file));
            downloadingId = null;
            modelsChanged();
            downloadEvent(entry.id, "done", entry.sizeBytes, entry.sizeBytes, null);
            notice(entry.name + " is ready.");
            if (entry == ModelAdvisor.EMBEDDER) reindexKnowledge(); // embed what was indexed by keyword
        } catch (ModelDownloader.CancelledException e) {
            downloadingId = null;
            downloadEvent(entry.id, "cancelled", 0, entry.sizeBytes, null);
        } catch (IOException | RuntimeException e) {
            downloadingId = null;
            String message = e.getMessage() == null ? "Check the connection and try again." : e.getMessage();
            downloadEvent(entry.id, "failed", 0, entry.sizeBytes, message);
            notice("Download failed: " + message);
        } finally {
            keepScreenOn(false);
        }
    }

    private void downloadEvent(String id, String state, long received, long total, String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("state", state);
            o.put("received", received);
            o.put("total", total);
            if (message != null) o.put("message", message);
        } catch (JSONException ignored) {
            // values above are always valid
        }
        callJs("window.tune2ai && window.tune2ai.onDownload && window.tune2ai.onDownload(" + o + ")");
    }

    /** A multi-hundred-MB download stalls if the phone sleeps; keep the screen on while it runs. */
    private void keepScreenOn(boolean on) {
        activity.runOnUiThread(() -> {
            if (on) activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }

    private static String deviceName() {
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        String model = Build.MODEL == null ? "" : Build.MODEL;
        if (model.toLowerCase(Locale.ROOT).startsWith(maker.toLowerCase(Locale.ROOT))) return model;
        return maker.isEmpty() ? model : Character.toUpperCase(maker.charAt(0)) + maker.substring(1) + " " + model;
    }

    private static String chipName() {
        if (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL != null && !Build.SOC_MODEL.equals(Build.UNKNOWN)) {
            String maker = Build.SOC_MANUFACTURER == null || Build.SOC_MANUFACTURER.equals(Build.UNKNOWN) ? "" : Build.SOC_MANUFACTURER + " ";
            return maker + Build.SOC_MODEL;
        }
        return Build.HARDWARE == null ? "" : Build.HARDWARE;
    }

    // ---------- Sources tab: the knowledge index (RAG) ----------

    /** Everything the Sources tab shows: switch state, search mode, folders, documents, progress. */
    @JavascriptInterface
    public String knowledgeStatus() {
        JSONObject o = new JSONObject();
        try {
            o.put("enabled", ai.getKnowledge().getEnabled());
            o.put("passagesPerAnswer", ai.getKnowledge().getPassagesPerAnswer());
            o.put("minSimilarity", ai.getKnowledge().getMinSimilarity());
            String chunking = ai.getKnowledge().getChunking().getId();
            o.put("chunking", chunking);
            o.put("neighbors", ai.getKnowledge().getIncludeNeighbors());
            int stale = 0;
            for (com.tune2ai.sdk.api.KnowledgeSource src : ai.getKnowledge().sourcesBlocking()) {
                String used = src.getMetadata().get("chunking");
                if (!chunking.equals(used == null ? "smart" : used)) stale++;
            }
            o.put("staleChunks", stale);
            String model = ai.getKnowledge().embeddingModel();
            o.put("embeddingModel", model == null ? JSONObject.NULL : model);
            JSONObject embedder = new JSONObject();
            embedder.put("id", ModelAdvisor.EMBEDDER.id);
            embedder.put("name", ModelAdvisor.EMBEDDER.name);
            embedder.put("sizeBytes", ModelAdvisor.EMBEDDER.sizeBytes);
            embedder.put("installed", model != null);
            embedder.put("downloading", ModelAdvisor.EMBEDDER.id.equals(downloadingId));
            o.put("embedder", embedder);
            o.put("indexing", indexing == null ? JSONObject.NULL : indexing);

            java.util.List<com.tune2ai.sdk.api.KnowledgeSource> sources = ai.getKnowledge().sourcesBlocking();
            JSONArray folders = new JSONArray();
            for (String uri : connector.folders()) {
                int files = 0;
                for (com.tune2ai.sdk.api.KnowledgeSource src : sources) if (uri.equals(src.getMetadata().get("folder"))) files++;
                folders.put(new JSONObject().put("uri", uri).put("name", KnowledgeConnector.folderName(Uri.parse(uri))).put("files", files));
            }
            o.put("folders", folders);
            JSONArray list = new JSONArray();
            for (com.tune2ai.sdk.api.KnowledgeSource src : sources) {
                Map<String, String> meta = src.getMetadata();
                if (meta.containsKey("connector")) continue;
                list.put(new JSONObject()
                        .put("id", src.getId())
                        .put("name", src.getName())
                        .put("kind", meta.containsKey("kind") ? meta.get("kind") : "file")
                        .put("path", meta.containsKey("path") ? meta.get("path") : "")
                        .put("inFolder", meta.containsKey("folder"))
                        .put("chunks", src.getChunks())
                        .put("characters", src.getCharacters())
                        .put("addedAt", src.getAddedAtMillis())
                        .put("smart", src.getEmbeddingModel() != null));
            }
            o.put("sources", list);
            o.put("total", sources.size());
            o.put("pdf", KnowledgeConnector.pdfSupported());
        } catch (JSONException ignored) {
            // values above are always valid
        }
        return o.toString();
    }

    @JavascriptInterface
    public void setKnowledgeEnabled(boolean enabled) {
        ai.getKnowledge().setEnabled(enabled);
        activity.getSharedPreferences("knowledge", Activity.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply();
    }

    /**
     * Retrieval tuning from the Sources tab: how many passages a request includes, and the
     * similarity floor for meaning-based matches. Kept across restarts.
     */
    @JavascriptInterface
    public void setKnowledgeTuning(int passages, double minSimilarity) {
        ai.getKnowledge().setPassagesPerAnswer(passages);
        ai.getKnowledge().setMinSimilarity((float) minSimilarity);
        activity.getSharedPreferences("knowledge", Activity.MODE_PRIVATE).edit()
                .putInt("passages", ai.getKnowledge().getPassagesPerAnswer())
                .putFloat("minSimilarity", ai.getKnowledge().getMinSimilarity())
                .apply();
    }

    /** The passage behind a citation, e.g. "Lisbon trip.md#2-3", as {source, section, text}; "null" if gone. */
    @JavascriptInterface
    public String knowledgePassage(String ref) {
        com.tune2ai.sdk.api.RetrievedChunk c = ai.getKnowledge().passageBlocking(ref);
        if (c == null) return "null";
        try {
            return new JSONObject().put("source", c.getSourceName())
                    .put("section", c.getSection() == null ? "" : c.getSection())
                    .put("text", c.getText()).toString();
        } catch (JSONException e) {
            return "null";
        }
    }

    // ---------- Connections tab ----------

    /** Every connection with its access, on/off state, last sync, and how many documents it indexed. */
    @JavascriptInterface
    public String connectionsStatus() {
        JSONObject o = new JSONObject();
        try {
            Map<String, Integer> counts = connections.counts();
            int storageFiles = 0;
            for (com.tune2ai.sdk.api.KnowledgeSource src : ai.getKnowledge().sourcesBlocking()) {
                if (KnowledgeConnector.STORAGE_SCOPE.equals(src.getMetadata().get("folder"))) storageFiles++;
            }
            JSONArray list = new JSONArray();
            for (Connections.Spec spec : Connections.ALL) {
                int items = spec.id.equals("storage") ? storageFiles : counts.getOrDefault(spec.id, 0);
                list.put(new JSONObject()
                        .put("id", spec.id).put("name", spec.name).put("description", spec.description)
                        .put("icon", spec.icon).put("group", spec.group)
                        .put("available", connections.available(spec))
                        .put("granted", connections.granted(spec))
                        .put("enabled", connections.enabled(spec.id))
                        .put("settings", connections.runtimePermissions(spec) == null)
                        .put("lastSync", connections.lastSync(spec.id))
                        .put("items", items));
            }
            o.put("connections", list);
            o.put("indexing", indexing == null ? JSONObject.NULL : indexing);
            o.put("sdk", Build.VERSION.SDK_INT);
        } catch (JSONException ignored) {
            // values above are always valid
        }
        return o.toString();
    }

    /** Turns a connection on: asks for access if needed, then indexes its data. */
    @JavascriptInterface
    public void connect(String id) {
        Connections.Spec spec = Connections.spec(id);
        if (spec == null) return;
        if (!connections.available(spec)) {
            notice(spec.name + " is only in the full build of Studio (install it with adb). Play Protect blocks it in sideloaded apps.");
            return;
        }
        if (connections.granted(spec)) {
            connections.setEnabled(id, true);
            syncConnection(id);
            return;
        }
        pendingConnect = id;
        String[] perms = connections.runtimePermissions(spec);
        activity.runOnUiThread(() -> {
            if (perms != null) {
                activity.requestPermissions(perms, CONNECT_REQUEST);
            } else {
                try {
                    activity.startActivity(connections.settingsIntent(spec));
                } catch (android.content.ActivityNotFoundException e) {
                    activity.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + activity.getPackageName())));
                }
                notice(id.equals("storage") ? "Turn on \"Allow access to manage all files\" for Tune2AI Studio, then come back."
                        : "Turn on notification access for Tune2AI Studio, then come back. If it is greyed out: App info → ⋮ → Allow restricted settings.");
            }
        });
    }

    /** Turns a connection off and deletes what it indexed. */
    @JavascriptInterface
    public void disconnect(String id) {
        Connections.Spec spec = Connections.spec(id);
        if (spec == null) return;
        indexer.execute(() -> {
            connections.disconnect(spec);
            notice(spec.name + " disconnected. Its data was removed from the index.");
            connectionsChanged();
        });
    }

    @JavascriptInterface
    public void syncConnection(String id) {
        Connections.Spec spec = Connections.spec(id);
        if (spec == null) return;
        runIndexing("Syncing " + spec.name.toLowerCase(Locale.ROOT), p -> connections.sync(spec, p));
    }

    /** Opens this app's system settings, to grant or revoke a permission by hand. */
    @JavascriptInterface
    public void openAppSettings() {
        activity.runOnUiThread(() -> activity.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + activity.getPackageName()))));
    }

    void onPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != CONNECT_REQUEST || pendingConnect == null) return;
        String id = pendingConnect;
        pendingConnect = null;
        Connections.Spec spec = Connections.spec(id);
        if (spec != null && connections.granted(spec)) {
            connections.setEnabled(id, true);
            syncConnection(id);
        } else if (spec != null) {
            notice("Permission not granted, so " + spec.name.toLowerCase(Locale.ROOT) + " stays disconnected.");
        }
        connectionsChanged();
    }

    /** Back from system settings: finish a connection that was waiting for special access. */
    void onResume() {
        String id = pendingConnect;
        Connections.Spec spec = id == null ? null : Connections.spec(id);
        if (spec != null && connections.runtimePermissions(spec) == null) {
            pendingConnect = null;
            if (connections.granted(spec)) {
                connections.setEnabled(id, true);
                syncConnection(id);
            }
        }
        connectionsChanged();
    }

    private void syncDueConnections() {
        for (Connections.Spec spec : connections.dueForSync()) syncConnection(spec.id);
    }

    private void connectionsChanged() {
        callJs("window.tune2ai && window.tune2ai.onConnectionsChanged && window.tune2ai.onConnectionsChanged()");
    }

    /** Opens the system folder picker; every readable file in the folder gets indexed. */
    @JavascriptInterface
    public void addKnowledgeFolder() {
        activity.runOnUiThread(() -> activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), FOLDER_REQUEST));
    }

    @JavascriptInterface
    public void addKnowledgeFiles() {
        activity.runOnUiThread(() -> activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), FILES_REQUEST));
    }

    void onKnowledgeResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) return;
        if (requestCode == FOLDER_REQUEST && data.getData() != null) {
            Uri tree = data.getData();
            runIndexing("Indexing " + KnowledgeConnector.folderName(tree), p -> connector.addFolder(tree, p));
        } else if (requestCode == FILES_REQUEST) {
            java.util.List<Uri> uris = urisOf(data);
            if (!uris.isEmpty()) runIndexing("Indexing files", p -> connector.addFiles(uris, true, "file", p));
        }
    }

    private volatile boolean openSourcesOnLoad;

    /** True once after a share arrived before the page was ready: the page then opens the Sources tab. */
    @JavascriptInterface
    public boolean takeOpenSources() {
        boolean open = openSourcesOnLoad;
        openSourcesOnLoad = false;
        return open;
    }

    /** Text or files shared to Studio from another app (Share → Tune2AI Studio). */
    @SuppressWarnings("deprecation") // typed getParcelable* overloads need API 33
    void onShare(Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return;
        java.util.List<Uri> uris = new java.util.ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            java.util.ArrayList<Uri> many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (many != null) uris.addAll(many);
        } else {
            Uri one = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (one != null) uris.add(one);
        }
        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        String subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        openSourcesOnLoad = true;
        callJs("window.tune2ai && window.tune2ai.onOpenSources && window.tune2ai.onOpenSources()");
        if (!uris.isEmpty()) {
            runIndexing("Adding shared files", p -> connector.addFiles(uris, false, "shared", p));
        } else if (text != null && text.toString().trim().length() > 0) {
            runIndexing("Adding shared text", p -> {
                KnowledgeConnector.Summary s = new KnowledgeConnector.Summary();
                connector.addNote(subject, text.toString(), "shared");
                s.added = 1;
                return s;
            });
        }
    }

    @JavascriptInterface
    public void addKnowledgeNote(String title, String text) {
        if (text == null || text.trim().isEmpty()) {
            notice("Write something first.");
            return;
        }
        runIndexing("Adding note", p -> {
            KnowledgeConnector.Summary s = new KnowledgeConnector.Summary();
            connector.addNote(title, text, "note");
            s.added = 1;
            return s;
        });
    }

    @JavascriptInterface
    public void removeKnowledgeSource(String id) {
        indexer.execute(() -> {
            ai.getKnowledge().removeBlocking(id);
            knowledgeChanged();
        });
    }

    @JavascriptInterface
    public void removeKnowledgeFolder(String uri) {
        indexer.execute(() -> {
            connector.removeFolder(uri);
            knowledgeChanged();
        });
    }

    @JavascriptInterface
    public void refreshKnowledge() {
        runIndexing("Checking folders for changes", connector::refreshFolders);
    }

    /** Best matches for a test query, as the model would receive them. */
    @JavascriptInterface
    public String searchKnowledge(String query) {
        JSONArray out = new JSONArray();
        try {
            for (com.tune2ai.sdk.api.RetrievedChunk c : ai.getKnowledge().searchBlocking(query, ai.getKnowledge().getPassagesPerAnswer())) {
                out.put(new JSONObject().put("source", c.getSourceName()).put("index", c.getIndex() + 1)
                        .put("ref", c.getRef()).put("section", c.getSection() == null ? "" : c.getSection())
                        .put("text", c.getText()).put("score", c.getScore()).put("smart", c.getByEmbedding()));
            }
        } catch (JSONException | RuntimeException e) {
            notice("Search failed: " + e.getMessage());
        }
        return out.toString();
    }

    private void reindexKnowledge() {
        runIndexing("Upgrading search", p -> {
            KnowledgeConnector.Summary s = new KnowledgeConnector.Summary();
            s.added = ai.getKnowledge().reindexBlocking((done, total) -> p.update(done, total, ""));
            return s;
        });
    }

    private interface IndexJob {
        KnowledgeConnector.Summary run(KnowledgeConnector.Progress progress) throws Exception;
    }

    /** Runs one indexing job at a time, reporting progress to the Sources tab. */
    private void runIndexing(String label, IndexJob job) {
        indexer.execute(() -> {
            keepScreenOn(true);
            try {
                KnowledgeConnector.Summary s = job.run((done, total, current) -> {
                    try {
                        indexing = new JSONObject().put("label", label).put("done", done).put("total", total).put("current", current);
                    } catch (JSONException ignored) {
                        // values above are always valid
                    }
                    knowledgeChanged();
                    connectionsChanged();
                });
                StringBuilder msg = new StringBuilder();
                if (s.added == 0 && s.unchanged == 0 && s.skipped == 0 && s.failed == 0 && s.pdfSkipped == 0) {
                    msg.append("No files found there");
                } else {
                    msg.append(s.added == 1 ? "Added 1 document" : "Added " + s.added + " documents");
                }
                if (s.unchanged > 0) msg.append(", ").append(s.unchanged).append(" unchanged");
                if (s.skipped > 0) msg.append(", skipped ").append(s.skipped).append(" (not text, or larger than 4 MB)");
                if (s.pdfSkipped > 0) msg.append(", skipped ").append(s.pdfSkipped).append(" PDFs (reading PDFs needs Android 15)");
                if (s.failed > 0) msg.append(", ").append(s.failed).append(" could not be read");
                notice(msg + ".");
                if (s.lastError != null) notice(s.lastError);
            } catch (Exception e) {
                notice("Indexing failed: " + e.getMessage());
            } finally {
                indexing = null;
                keepScreenOn(false);
                knowledgeChanged();
                connectionsChanged();
            }
        });
    }

    private static java.util.List<Uri> urisOf(Intent data) {
        java.util.List<Uri> uris = new java.util.ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        return uris;
    }

    private void knowledgeChanged() {
        callJs("window.tune2ai && window.tune2ai.onKnowledgeChanged && window.tune2ai.onKnowledgeChanged()");
    }

    /** Opens the system file picker for a .gguf model. */
    @JavascriptInterface
    public void importModel() {
        activity.runOnUiThread(() -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*");
            activity.startActivityForResult(intent, IMPORT_REQUEST);
        });
    }

    void onImportResult(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        notice("Importing model…");
        io.execute(() -> importFrom(uri));
    }

    private void importFrom(Uri uri) {
        String name = displayName(uri);
        if (!name.toLowerCase(Locale.ROOT).endsWith(".gguf")) {
            notice("Pick a .gguf model file. \"" + name + "\" is not one.");
            return;
        }
        File target = new File(modelsDir, name);
        File partial = new File(modelsDir, name + ".partial");
        modelsDir.mkdirs();
        try (InputStream in = activity.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(partial)) {
            if (in == null) throw new IOException("The file could not be opened");
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
        } catch (IOException e) {
            partial.delete();
            notice("Import failed: " + e.getMessage());
            return;
        }
        if (!isGguf(partial) || !partial.renameTo(target)) {
            partial.delete();
            notice("\"" + name + "\" is not a valid GGUF model.");
            return;
        }
        ai.getModels().registerBlocking(descriptorFor(target));
        notice("Imported " + name + ".");
        modelsChanged();
    }

    /** Registers .gguf files already in app storage (imported earlier, or copied in with adb run-as). */
    private void registerModelsInStorage() {
        File[] files = modelsDir.listFiles((dir, n) -> n.toLowerCase(Locale.ROOT).endsWith(".gguf"));
        if (files == null) return;
        java.util.Set<String> known = new java.util.HashSet<>();
        for (ModelInfo info : ai.getModels().listBlocking()) known.add(info.getDescriptor().getId());
        boolean added = false;
        for (File f : files) {
            ModelDescriptor d = descriptorFor(f);
            if (!known.contains(d.getId()) && isGguf(f)) {
                ai.getModels().registerBlocking(d);
                added = true;
            }
        }
        if (added) modelsChanged();
    }

    private ModelDescriptor descriptorFor(File file) {
        String base = file.getName().substring(0, file.getName().length() - ".gguf".length());
        String id = base.toLowerCase(Locale.ROOT);
        ModelAdvisor.Entry known = ModelAdvisor.find(id); // downloaded from the catalog: use its tidy name
        boolean embedder = known == ModelAdvisor.EMBEDDER;
        int context = embedder ? ModelAdvisor.EMBEDDER_CONTEXT : CONTEXT_LENGTH;
        return new ModelDescriptor(
                id,
                known != null ? known.name : base.replace('-', ' ').replace('_', ' '),
                "gguf",
                file.getPath(),
                LlamaBackend.estimateMemoryBytes(file, context),
                context,
                Collections.singleton(embedder ? Task.EMBEDDING : Task.CHAT),
                Collections.emptySet(),
                "1");
    }

    private void deliver(String id, Tune2AIEvent event) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("requestId", event.getRequestId().getValue());
            if (event instanceof Tune2AIEvent.Progress) {
                Tune2AIEvent.Progress p = (Tune2AIEvent.Progress) event;
                o.put("type", "progress");
                o.put("stage", p.getStage().name());
                o.put("detail", p.getDetail());
                o.put("sources", new JSONArray(p.getSources()));
            } else if (event instanceof Tune2AIEvent.TextDelta) {
                o.put("type", "delta");
                o.put("text", ((Tune2AIEvent.TextDelta) event).getText());
            } else if (event instanceof Tune2AIEvent.Completed) {
                Tune2AIEvent.Completed c = (Tune2AIEvent.Completed) event;
                o.put("type", "completed");
                o.put("tokens", c.getUsage().getOutputTokens());
                o.put("firstTokenMillis", c.getUsage().getFirstTokenMillis() == null ? JSONObject.NULL : c.getUsage().getFirstTokenMillis());
                o.put("totalMillis", c.getUsage().getTotalMillis());
            } else if (event instanceof Tune2AIEvent.Cancelled) {
                o.put("type", "cancelled");
            } else if (event instanceof Tune2AIEvent.Failed) {
                Tune2AIEvent.Failed f = (Tune2AIEvent.Failed) event;
                o.put("type", "failed");
                o.put("category", f.getError().getCategory().name());
                o.put("message", f.getError().getMessage());
            } else if (event instanceof Tune2AIEvent.ToolCall) {
                Tune2AIEvent.ToolCall c = (Tune2AIEvent.ToolCall) event;
                o.put("type", "tool-call");
                o.put("callId", c.getCallId());
                o.put("name", c.getName());
                o.put("arguments", c.getArgumentsJson());
            } else if (event instanceof Tune2AIEvent.ToolResult) {
                Tune2AIEvent.ToolResult r = (Tune2AIEvent.ToolResult) event;
                o.put("type", "tool-result");
                o.put("callId", r.getCallId());
                o.put("name", r.getName());
                o.put("result", r.getResultJson());
                o.put("isError", r.isError());
            } else {
                return;
            }
        } catch (JSONException e) {
            return;
        }
        if (event instanceof Tune2AIEvent.Terminal) {
            running.remove(id);
            modelsChanged(); // load state may have changed
        }
        callJs("window.tune2ai && window.tune2ai.onEvent(" + o + ")");
    }

    private void notice(String text) {
        callJs("window.tune2ai && window.tune2ai.onNotice(" + JSONObject.quote(text) + ")");
    }

    private void modelsChanged() {
        callJs("window.tune2ai && window.tune2ai.onModelsChanged()");
    }

    private void callJs(String script) {
        webView.post(() -> webView.evaluateJavascript(script, null));
    }

    private String displayName(Uri uri) {
        try (Cursor c = activity.getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) {
            // fall back to the URI's last segment
        }
        String last = uri.getLastPathSegment();
        return last == null ? "model.gguf" : new File(last).getName();
    }

    private static boolean isGguf(File file) {
        byte[] magic = new byte[4];
        try (InputStream in = new FileInputStream(file)) {
            return in.read(magic) == 4 && magic[0] == 'G' && magic[1] == 'G' && magic[2] == 'U' && magic[3] == 'F';
        } catch (IOException e) {
            return false;
        }
    }

    void close() {
        for (Cancellable handle : running.values()) handle.cancel();
        downloadCancelled.set(true); // the .partial file stays and resumes next time
        indexer.shutdownNow();
        downloads.shutdownNow();
        io.shutdownNow();
    }
}
