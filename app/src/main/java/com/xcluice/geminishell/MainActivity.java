package com.xcluice.geminishell;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {

    private static final String HOME = "https://gemini.google.com/app";
    private static final String INJECT =
        "(function(){if(document.getElementById('nth'))return;"
        + "var s=document.createElement('style');s.id='nth';"
        + "s.textContent='*{-webkit-tap-highlight-color:transparent!important;"
        + "-webkit-touch-callout:none}';"
        + "(document.head||document.documentElement).appendChild(s);})()";

    private WebView web;
    private ValueCallback<Uri[]> fileCb;

    private static boolean isInternal(String host) {
        if (host == null) return false;
        return host.endsWith("google.com") || host.endsWith("gstatic.com")
            || host.endsWith("googleusercontent.com") || host.endsWith("googleapis.com")
            || host.endsWith("gvt1.com") || host.endsWith("ggpht.com");
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        web.setBackgroundColor(0xFF131314);
        setContentView(web, new ViewGroup.LayoutParams(-1, -1));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setSupportMultipleWindows(false);
        // Remove the WebView marker so Google sign-in is not blocked
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
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String sch = u.getScheme();
                if (("https".equals(sch) || "http".equals(sch)) && isInternal(u.getHost())) {
                    return false;
                }
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
