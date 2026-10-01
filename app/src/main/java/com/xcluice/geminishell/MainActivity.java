package com.xcluice.geminishell;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;

public class MainActivity extends Activity {

    private static final String HOME = "https://gemini.google.com/app";

    // Tap highlight off + near-zero animations/blur for old hardware
    private static final String CSS =
        "*{-webkit-tap-highlight-color:transparent!important;-webkit-touch-callout:none}"
        + "*,*::before,*::after{animation-duration:1ms!important;animation-delay:0s!important;"
        + "transition-duration:1ms!important;transition-delay:0s!important;"
        + "scroll-behavior:auto!important;backdrop-filter:none!important;"
        + "-webkit-backdrop-filter:none!important}"
        + "html,body{overscroll-behavior:none}"
        + "img,video,canvas,a{-webkit-user-drag:none!important;user-drag:none!important}";

    private static final String INJECT =
        "(function(){if(document.getElementById('nth'))return;"
        + "document.addEventListener('dragstart',function(e){e.preventDefault();},true);"
        + "document.addEventListener('contextmenu',function(e){var t=e.target;if(t&&t.tagName==='IMG')e.preventDefault();},true);"
        + "var s=document.createElement('style');s.id='nth';"
        + "s.textContent='" + CSS + "';"
        + "(document.head||document.documentElement).appendChild(s);})()";

    private static final String[] BLOCKED = {
        "google-analytics.com", "googletagmanager.com", "doubleclick.net",
        "googlesyndication.com", "googleadservices.com", "play.google.com/log",
        "/gen_204", "adservice.google"
    };

    private WebView web;
    private ValueCallback<Uri[]> fileCb;

    private static boolean isInternal(String host) {
        if (host == null) return false;
        return host.endsWith("google.com") || host.endsWith("gstatic.com")
            || host.endsWith("googleusercontent.com") || host.endsWith("googleapis.com")
            || host.endsWith("gvt1.com") || host.endsWith("ggpht.com")
            || host.endsWith("youtube.com") || host.endsWith("google.co.in")
            || host.endsWith("withgoogle.com");
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        web.setBackgroundColor(0xFF131314);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        setContentView(web, new ViewGroup.LayoutParams(-1, -1));

        web.setHapticFeedbackEnabled(false);
        // Consume long-press on images: it starts a drag that freezes old Android
        web.setOnLongClickListener(v -> {
            int t = web.getHitTestResult().getType();
            return t == WebView.HitTestResult.IMAGE_TYPE
                || t == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE;
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setSupportMultipleWindows(false);
        s.setTextZoom(100);
        String ua = s.getUserAgentString().replace("; wv", "").replace("Version/4.0 ", "");
        s.setUserAgentString(ua);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) {
                v.evaluateJavascript(INJECT, null);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(INJECT, null);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                for (String k : BLOCKED) {
                    if (u.contains(k)) {
                        return new WebResourceResponse("text/plain", "utf-8", 204, "No Content",
                            null, new ByteArrayInputStream(new byte[0]));
                    }
                }
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String sch = u.getScheme();
                boolean web = "https".equals(sch) || "http".equals(sch);
                if (web && isInternal(u.getHost())) return false;
                // Redirects (sign-in chains) always stay inside the app
                if (web && (r.isRedirect() || !r.hasGesture())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    startActivityForResult(p.createIntent(), 1);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }
        });

        if (b != null) web.restoreState(b); else web.loadUrl(HOME);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == 1 && fileCb != null) {
            fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCb = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle o) {
        super.onSaveInstanceState(o);
        web.saveState(o);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}
