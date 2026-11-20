package com.tune2ai.studio;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Date;
import java.util.Locale;

import com.tune2ai.sdk.Tune2AI;
import com.tune2ai.sdk.api.ModelDescriptor;
import com.tune2ai.sdk.api.ModelInfo;
import com.tune2ai.sdk.api.ModelState;
import com.tune2ai.sdk.api.ResourceLimits;
import com.tune2ai.sdk.api.ResourceSnapshot;
import com.tune2ai.sdk.api.Tool;
import com.tune2ai.sdk.llama.LlamaBackend;
import com.tune2ai.sdk.remote.RemoteBackend;
import com.tune2ai.sdk.services.prompt.PromptTemplate;

public final class MainActivity extends Activity {
    private static final String PAGE_ROOT = "file:///android_asset/www/";

    private WebView webView;
    private Tune2AI ai;
    private StudioBridge bridge;
    private final RemoteSettings remote = new RemoteSettings();
    private final FinanceDataStore financeData = new FinanceDataStore();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ai = new Tune2AI.Builder(this)
                .addBackend(new LlamaBackend())
                .addBackend(new RemoteBackend(() -> remote.baseUrl, () -> remote.apiKey))
                .addPromptTemplate(new PromptTemplate("helpful-assistant", 3,
                        "Be concise, accurate, and use supplied context when relevant."))
                .defaultPromptTemplate("helpful-assistant")
                // Demo tools for Agent mode. Handlers run on a background thread.
                .addTool(new Tool("device_status",
                        "Reports this device's free memory, battery level, and thermal state.",
                        args -> deviceStatus()))
                .addTool(new Tool("list_models",
                        "Lists the AI models installed in Tune2AI Studio and whether each is loaded.",
                        args -> modelsSummary()))
                .addTool(new Tool("current_time",
                        "Returns the current local date and time.",
                        args -> JSONObject.quote(new Date().toString())))
                .addTool(new Tool("runtime_readiness",
                        "Checks device resources and installed models, and reports which models appear safe to load.",
                        args -> readinessSummary()))
                .addTool(new Tool("finance_summary",
                        "Returns only the aggregated spending summary from transaction messages the user explicitly shared.",
                        args -> financeData.getSummaryJson()))
                .build();

        webView = new WebView(this);
        webView.setBackgroundColor(getColor(R.color.canvas));
        webView.setWebViewClient(new WebViewClient() {
            // The bridge is only safe on our bundled page: never navigate anywhere else.
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !request.getUrl().toString().startsWith(PAGE_ROOT);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (bridge != null) {
                    bridge.onPageReady();
                    handleSharedIntent(getIntent());
                }
            }
        });
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // file:///android_asset stays readable; this only blocks the rest of the filesystem.
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(false);
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true); // chrome://inspect in debug builds only
        }

        bridge = new StudioBridge(this, webView, ai, remote, financeData);
        webView.addJavascriptInterface(bridge, "Tune2AIBridge");

        // Edge-to-edge is enforced from targetSdk 35: pad for system bars and the keyboard.
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.canvas));
        root.addView(webView);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
        webView.loadUrl(PAGE_ROOT + "index.html");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSharedIntent(intent);
    }

    private void handleSharedIntent(Intent intent) {
        if (bridge == null || intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        String mime = intent.getType();
        if (mime == null || !mime.startsWith("text/")) return;
        CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (shared != null) bridge.acceptSharedMessages(shared.toString());
        // Drop the shared text from the Activity's retained Intent after handing it to the in-memory parser.
        setIntent(new Intent(this, MainActivity.class));
    }

    /** Compact, plainly labelled model list: small models misread raw fields like sizeBytes. */
    private String modelsSummary() {
        JSONArray out = new JSONArray();
        for (ModelInfo info : ai.getModels().listBlocking()) {
            JSONObject o = new JSONObject();
            try {
                o.put("name", info.getDescriptor().getName());
                o.put("runs_on", RemoteBackend.FORMAT.equals(info.getDescriptor().getFormat()) ? "remote server" : "this device");
                ModelState state = info.getState();
                o.put("status", state instanceof ModelState.Loaded ? "loaded"
                        : state instanceof ModelState.Unavailable ? "unavailable" : "installed, not loaded");
                out.put(o);
            } catch (JSONException ignored) {
                // values above are always valid
            }
        }
        return out.toString();
    }

    private String deviceStatus() {
        ResourceSnapshot s = ai.resources();
        JSONObject o = new JSONObject();
        try {
            o.put("free_memory_mb", s.getAvailableMemoryBytes() / (1024 * 1024));
            o.put("total_memory_mb", s.getTotalMemoryBytes() / (1024 * 1024));
            o.put("low_memory", s.getLowMemory());
            o.put("battery_percent", s.getBatteryPercent() == null ? JSONObject.NULL : s.getBatteryPercent());
            o.put("charging", s.getCharging() == null ? JSONObject.NULL : s.getCharging());
            o.put("thermal", s.getThermal().name().toLowerCase(java.util.Locale.ROOT));
        } catch (JSONException ignored) {
            // values above are always valid
        }
        return o.toString();
    }

    /** Combines live resource signals and model estimates into a compact, actionable report. */
    private String readinessSummary() {
        ResourceSnapshot snapshot = ai.resources();
        JSONArray models = new JSONArray();
        for (ModelInfo info : ai.getModels().listBlocking()) {
            JSONObject model = new JSONObject();
            ModelDescriptor descriptor = info.getDescriptor();
            ModelState state = info.getState();
            boolean remoteModel = RemoteBackend.FORMAT.equals(descriptor.getFormat());
            String status = state instanceof ModelState.Loaded ? "loaded"
                    : state instanceof ModelState.Unavailable ? "unavailable"
                    : state instanceof ModelState.Failed ? "failed" : "not loaded";
            String reason = null;
            if (state instanceof ModelState.Unavailable) reason = ((ModelState.Unavailable) state).getReason();
            else if (state instanceof ModelState.Failed) reason = ((ModelState.Failed) state).getReason();
            else if (!(state instanceof ModelState.Loaded) && !remoteModel) {
                reason = new ResourceLimits().violation(snapshot, descriptor.getMemoryBytes());
            }
            try {
                model.put("name", descriptor.getName());
                model.put("runs_on", remoteModel ? "remote server" : "this device");
                model.put("status", status);
                model.put("estimated_memory_mb", remoteModel ? JSONObject.NULL
                        : descriptor.getMemoryBytes() / (1024 * 1024));
                model.put("recommendation", remoteModel ? "uses remote endpoint; device memory does not apply"
                        : state instanceof ModelState.Loaded ? "already loaded"
                        : reason == null ? "appears safe to load" : "do not load now");
                if (reason != null) model.put("reason", reason);
                models.put(model);
            } catch (JSONException ignored) {
                // Values are primitive and valid JSON values.
            }
        }
        JSONObject report = new JSONObject();
        try {
            report.put("free_memory_mb", snapshot.getAvailableMemoryBytes() / (1024 * 1024));
            report.put("low_memory", snapshot.getLowMemory());
            report.put("thermal", snapshot.getThermal().name().toLowerCase(Locale.ROOT));
            report.put("battery_percent", snapshot.getBatteryPercent() == null
                    ? JSONObject.NULL : snapshot.getBatteryPercent());
            report.put("models", models);
        } catch (JSONException ignored) {
            // Values are primitive and valid JSON values.
        }
        return report.toString();
    }

    @Override
    @SuppressWarnings("deprecation") // framework Activity API; no AndroidX here
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == StudioBridge.IMPORT_REQUEST) bridge.onImportResult(resultCode, data);
        else if (requestCode == StudioBridge.FINANCE_IMPORT_REQUEST) bridge.onFinanceImportResult(resultCode, data);
        else super.onActivityResult(requestCode, resultCode, data);
    }

    private void handleBack() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else finish();
    }

    @Override
    @SuppressWarnings("deprecation") // only reached below API 33
    public void onBackPressed() {
        handleBack();
    }

    @Override
    protected void onDestroy() {
        financeData.clear();
        if (bridge != null) bridge.close();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        if (ai != null) ai.close();
        super.onDestroy();
    }
}
