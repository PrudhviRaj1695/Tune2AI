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

import java.util.Collections;
import java.util.Date;

import com.tune2ai.sdk.Tune2AI;
import com.tune2ai.sdk.api.ModelInfo;
import com.tune2ai.sdk.api.ModelState;
import com.tune2ai.sdk.api.ResourceSnapshot;
import com.tune2ai.sdk.api.Tool;
import com.tune2ai.sdk.llama.LlamaBackend;
import com.tune2ai.sdk.remote.RemoteBackend;
import com.tune2ai.sdk.services.prompt.PromptTemplate;

public final class MainActivity extends Activity {
    private static final String PAGE_ROOT = "file:///android_asset/www/";
    static final String SYSTEM_PROMPT = "You are a helpful assistant running privately on the user's phone. It is now {{today}}. Answer the question in the first sentence, then stop: two to four sentences, or a short list, unless the user asks for detail. For questions about the user's messages, files, or phone, answer only from the information given; if the answer is not there, say so instead of guessing. Use Markdown only when it helps: a list for steps, **bold** for the key fact, a table for comparisons, fenced code blocks for code. No headings.";

    private WebView webView;
    private Tune2AI ai;
    private final PhoneContextSource phone = new PhoneContextSource();
    private AgentTools agentTools;
    private StudioBridge bridge;
    private final RemoteSettings remote = new RemoteSettings();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        agentTools = new AgentTools();
        Tune2AI.Builder builder = new Tune2AI.Builder(this);
        // Agent tools: look things up in sources and phone data; prepare actions the user confirms.
        for (Tool tool : agentTools.tools()) builder.addTool(tool);
        ai = builder
                .addBackend(new LlamaBackend(LlamaBackend.defaultThreads(), true, getFilesDir()))
                .addBackend(new RemoteBackend(() -> remote.baseUrl, () -> remote.apiKey))
                // v5: asks for Markdown structure (Studio renders it) and knows the date, so questions about
                // "today" or "next Friday" over calendar and message sources make sense.
                .addPromptTemplate(new PromptTemplate("helpful-assistant", 6, SYSTEM_PROMPT, Collections.singleton("today")))
                .defaultPromptTemplate("helpful-assistant")
                // Live messages, calls, calendar, and notifications for questions about them.
                .addContextSource(phone)
                // Context in the latest message: the conversation prefix stays cached between turns.
                .contextInLatestMessage(true)
                // Demo tools for Agent mode. Handlers run on a background thread.
                .addTool(new Tool("device_status",
                        "Reports this device's free RAM, battery level, and thermal state.",
                        args -> deviceStatus()))
                .addTool(new Tool("list_models",
                        "Lists the AI models installed in Tune2AI Studio and whether each is loaded.",
                        args -> modelsSummary()))
                .addTool(new Tool("current_time",
                        "Returns the current local date and time.",
                        args -> JSONObject.quote(new Date().toString())))
                .build();

        webView = new WebView(this);
        webView.setBackgroundColor(getColor(R.color.canvas));
        webView.setWebViewClient(new WebViewClient() {
            // The bridge is only safe on our bundled page: never navigate anywhere else.
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !request.getUrl().toString().startsWith(PAGE_ROOT);
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

        bridge = new StudioBridge(this, webView, ai, remote, phone, agentTools);
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
        if (savedInstanceState == null) bridge.onShare(getIntent()); // launched from another app's Share sheet
    }

    /**
     * The {{today}} prompt variable, e.g. "Sunday 22 November 2026". Date only: the system prompt then
     * stays identical all day, so llama.cpp can reuse its prompt cache between messages. The time of
     * day reaches the model in the live phone-data block, which sits in the latest message.
     */
    static String today() {
        return new java.text.SimpleDateFormat("EEEE d MMMM yyyy", java.util.Locale.getDefault()).format(new Date());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        bridge.onPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (bridge != null) bridge.onResume(); // back from system settings (all files / notification access)
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        bridge.onShare(intent);
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

    @Override
    @SuppressWarnings("deprecation") // framework Activity API; no AndroidX here
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == StudioBridge.IMPORT_REQUEST) bridge.onImportResult(resultCode, data);
        else if (requestCode == StudioBridge.FOLDER_REQUEST || requestCode == StudioBridge.FILES_REQUEST) bridge.onKnowledgeResult(requestCode, resultCode, data);
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
        if (bridge != null) bridge.close();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        if (ai != null) ai.close();
        super.onDestroy();
    }
}
