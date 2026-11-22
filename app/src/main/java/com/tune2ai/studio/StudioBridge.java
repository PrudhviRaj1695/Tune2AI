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

    StudioBridge(Activity activity, WebView webView, Tune2AI ai, RemoteSettings remote) {
        this.activity = activity;
        this.webView = webView;
        this.ai = ai;
        this.remote = remote;
        this.modelsDir = new File(activity.getFilesDir(), "models");
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
                    (float) req.optDouble("temperature", 0.7));
            String prompt = req.getString("prompt");
            Cancellable handle = "Agent".equals(req.optString("mode"))
                    ? ai.getAgent().stream(prompt, null, new AgentLimits(), options, event -> deliver(id, event))
                    : ai.getChat().session(SESSION).stream(prompt, options, event -> deliver(id, event));
            running.put(id, handle);
        } catch (JSONException e) {
            notice("Could not read the request: " + e.getMessage());
        }
    }

    @JavascriptInterface
    public void cancel(String id) {
        Cancellable handle = running.remove(id);
        if (handle != null) handle.cancel();
    }

    @JavascriptInterface
    public void newSession() {
        for (Cancellable handle : running.values()) handle.cancel();
        running.clear();
        ai.getChat().session(SESSION).clearAsync();
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
        return new ModelDescriptor(
                id,
                known != null ? known.name : base.replace('-', ' ').replace('_', ' '),
                "gguf",
                file.getPath(),
                LlamaBackend.estimateMemoryBytes(file, CONTEXT_LENGTH),
                CONTEXT_LENGTH,
                Collections.singleton(Task.CHAT),
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
        downloads.shutdownNow();
        io.shutdownNow();
    }
}
