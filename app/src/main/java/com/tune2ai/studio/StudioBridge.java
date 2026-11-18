package com.tune2ai.studio;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.tune2ai.sdk.Tune2AI;
import com.tune2ai.sdk.api.Cancellable;
import com.tune2ai.sdk.api.ModelDescriptor;
import com.tune2ai.sdk.api.ModelState;
import com.tune2ai.sdk.api.RequestOptions;
import com.tune2ai.sdk.api.ResourceSnapshot;
import com.tune2ai.sdk.api.Task;
import com.tune2ai.sdk.api.Tune2AIEvent;
import com.tune2ai.sdk.llama.LlamaBackend;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The page's only door into the SDK, exposed to JavaScript as {@code window.Tune2AIBridge}.
 * Events go back through {@code window.tune2ai.onEvent(...)}. MainActivity keeps the WebView on
 * the bundled page, so no other page can reach these methods.
 */
final class StudioBridge {
    static final int IMPORT_REQUEST = 41;
    private static final String SESSION = "studio";
    private static final int CONTEXT_LENGTH = 4096;

    private final Activity activity;
    private final WebView webView;
    private final Tune2AI ai;
    private final File modelsDir;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, Cancellable> running = new ConcurrentHashMap<>();

    StudioBridge(Activity activity, WebView webView, Tune2AI ai) {
        this.activity = activity;
        this.webView = webView;
        this.ai = ai;
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
                    null,
                    120_000L,
                    req.optInt("maxTokens", 512),
                    (float) req.optDouble("temperature", 0.7));
            running.put(id, ai.getChat().session(SESSION).stream(req.getString("prompt"), options, event -> deliver(id, event)));
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

    /** Registered models and their state, as a JSON array. */
    @JavascriptInterface
    public String listModels() {
        JSONArray out = new JSONArray();
        Map<String, ModelState> states = ai.getModels().getStates().getValue();
        for (ModelDescriptor m : ai.getModels().listBlocking()) {
            ModelState state = states.get(m.getId());
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
        for (ModelDescriptor m : ai.getModels().listBlocking()) known.add(m.getId());
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
        return new ModelDescriptor(
                base.toLowerCase(Locale.ROOT),
                base.replace('-', ' ').replace('_', ' '),
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
            } else {
                return; // tool events arrive with the Agent API (T2AI-042)
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
        io.shutdownNow();
    }
}
