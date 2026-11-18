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

import com.tune2ai.sdk.Tune2AI;
import com.tune2ai.sdk.llama.LlamaBackend;
import com.tune2ai.sdk.services.prompt.PromptTemplate;

public final class MainActivity extends Activity {
    private static final String PAGE_ROOT = "file:///android_asset/www/";

    private WebView webView;
    private Tune2AI ai;
    private StudioBridge bridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ai = new Tune2AI.Builder(this)
                .addBackend(new LlamaBackend())
                .addPromptTemplate(new PromptTemplate("helpful-assistant", 3,
                        "Be concise, accurate, and use supplied context when relevant."))
                .defaultPromptTemplate("helpful-assistant")
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

        bridge = new StudioBridge(this, webView, ai);
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
    @SuppressWarnings("deprecation") // framework Activity API; no AndroidX here
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == StudioBridge.IMPORT_REQUEST) bridge.onImportResult(resultCode, data);
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
