package com.xcluice.geminishell;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class MainActivity extends Activity {

    private static final String HOME = "https://gemini.google.com/app";
    private static final int REQ_FILE = 1;
    private static final int REQ_MIC = 2;

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

    // Only pure tracking hosts; nothing Gemini itself needs
    private static final String[] BLOCKED = {
        "google-analytics.com", "googletagmanager.com", "doubleclick.net",
        "googlesyndication.com", "googleadservices.com"
    };

    private WebView web;
    private boolean webDead;
    private ValueCallback<Uri[]> fileCb;
    private File camFile;
    private Uri camUri;
    private PermissionRequest pendingPerm;

    private static boolean isInternal(String host) {
        if (host == null) return false;
        return host.endsWith("google.com") || host.endsWith("gstatic.com")
            || host.endsWith("googleusercontent.com") || host.endsWith("googleapis.com")
            || host.endsWith("gvt1.com") || host.endsWith("ggpht.com")
            || host.endsWith("youtube.com") || host.endsWith("google.co.in")
            || host.endsWith("withgoogle.com");
    }

    private static boolean wantsImage(String[] types) {
        if (types == null || types.length == 0) return true;
        for (String t : types) {
            if (t == null || t.isEmpty() || t.startsWith("image") || t.equals("*/*")) return true;
        }
        return false;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cleanOldFiles();

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
            public void onPageStarted(WebView v, String url, Bitmap f) {
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
                boolean isWeb = "https".equals(sch) || "http".equals(sch);
                if (isWeb && isInternal(u.getHost())) return false;
                // Redirects (sign-in chains) always stay inside the app
                if (isWeb && (r.isRedirect() || !r.hasGesture())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }

            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                // Low memory killed the page: restart cleanly instead of crashing
                webDead = true;
                recreate();
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    Intent chooser = Intent.createChooser(p.createIntent(), "Select");
                    if (wantsImage(p.getAcceptTypes())) {
                        Intent cam = cameraIntent();
                        if (cam != null) {
                            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cam});
                        }
                    }
                    startActivityForResult(chooser, REQ_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    cb.onReceiveValue(null);
                }
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest r) {
                boolean wantsAudio = false;
                for (String res : r.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) wantsAudio = true;
                }
                if (!wantsAudio) {
                    r.deny();
                    return;
                }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED) {
                    r.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                } else {
                    if (pendingPerm != null) pendingPerm.deny();
                    pendingPerm = r;
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                }
            }
        });

        if (b == null || web.restoreState(b) == null) web.loadUrl(HOME);
    }

    private Intent cameraIntent() {
        Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (cam.resolveActivity(getPackageManager()) == null) return null;
        File f = new File(CamProvider.dir(this), "cam_" + System.currentTimeMillis() + ".jpg");
        Uri u = CamProvider.uriFor(f.getName());
        camFile = f;
        camUri = u;
        int flags = Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION;
        cam.putExtra(MediaStore.EXTRA_OUTPUT, u);
        cam.setClipData(ClipData.newRawUri("", u));
        cam.addFlags(flags);
        for (ResolveInfo ri : getPackageManager()
                .queryIntentActivities(cam, PackageManager.MATCH_DEFAULT_ONLY)) {
            grantUriPermission(ri.activityInfo.packageName, u, flags);
        }
        return cam;
    }

    // Shrink big photos before upload: far less data and much faster
    private Uri compress(Uri src) {
        try {
            ContentResolver cr = getContentResolver();
            String type = cr.getType(src);
            if (type == null || !type.startsWith("image/")
                    || type.contains("gif") || type.contains("svg")) return src;

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(src)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int max = Math.max(o.outWidth, o.outHeight);
            if (max <= 0) return src;
            int sample = 1;
            while (max / sample > 1800) sample *= 2;

            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream in = cr.openInputStream(src)) {
                bmp = BitmapFactory.decodeStream(in, null, o);
            }
            if (bmp == null) return src;

            int deg = 0;
            try (InputStream in = cr.openInputStream(src)) {
                int ori = new ExifInterface(in)
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
                deg = ori == 6 ? 90 : ori == 3 ? 180 : ori == 8 ? 270 : 0;
            } catch (Exception ignored) { }

            float scale = Math.min(1f, 1600f / Math.max(bmp.getWidth(), bmp.getHeight()));
            if (deg != 0 || scale < 1f) {
                Matrix m = new Matrix();
                m.postRotate(deg);
                m.postScale(scale, scale);
                Bitmap nb = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (nb != bmp) bmp.recycle();
                bmp = nb;
            }

            File f = new File(CamProvider.dir(this), "up_" + System.nanoTime() + ".jpg");
            try (FileOutputStream out = new FileOutputStream(f)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, out);
            }
            bmp.recycle();
            return CamProvider.uriFor(f.getName());
        } catch (Throwable t) {
            return src;
        }
    }

    private void cleanOldFiles() {
        new Thread(() -> {
            File[] fs = CamProvider.dir(this).listFiles();
            if (fs == null) return;
            long cutoff = System.currentTimeMillis() - 6L * 3600 * 1000;
            for (File f : fs) {
                if (f.lastModified() < cutoff) f.delete();
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req != REQ_FILE || fileCb == null) return;
        final ValueCallback<Uri[]> cb = fileCb;
        final File cf = camFile;
        final Uri cu = camUri;
        fileCb = null;
        camFile = null;
        camUri = null;
        final int r = res;
        final Intent d = data;
        new Thread(() -> {
            Uri[] out = null;
            if (r == RESULT_OK) {
                Uri[] picked = d != null
                    ? WebChromeClient.FileChooserParams.parseResult(r, d) : null;
                if (picked == null || picked.length == 0) {
                    if (cf != null && cf.length() > 0) picked = new Uri[]{cu};
                }
                if (picked != null) {
                    out = new Uri[picked.length];
                    for (int i = 0; i < picked.length; i++) out[i] = compress(picked[i]);
                }
            }
            if (cf != null && cf.length() == 0) cf.delete();
            final Uri[] fin = out;
            runOnUiThread(() -> cb.onReceiveValue(fin));
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_MIC && pendingPerm != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                pendingPerm.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                pendingPerm.deny();
            }
            pendingPerm = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle o) {
        super.onSaveInstanceState(o);
        if (!webDead) web.saveState(o);
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
