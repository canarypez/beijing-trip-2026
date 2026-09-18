package com.bjtrip.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 北京 5 日行程 —— 本地 WebView 外壳。
 *
 * 页面资源不打 file:// 加载，而是通过 shouldInterceptRequest 以
 * https://appassets.androidplatform.net/assets/... 这个安全源提供，
 * 这样 localStorage、定位、剪贴板在 WebView 里都按正常网页规则工作。
 *
 * 所有「跳出去」的动作都走原生 Intent：
 *   androidamap://  高德地图 App（标注点 / 路线规划）
 *   weixin://       微信
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String BASE = "https://" + HOST + "/assets/";
    private static final String UA_SUFFIX = " BJTripApp/1.0";

    private static final String PKG_AMAP = "com.autonavi.minimap";
    private static final String PKG_WECHAT = "com.tencent.mm";

    private static final int REQ_LOCATION = 1001;

    private WebView web;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(Color.WHITE);

        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setLoadsImagesAutomatically(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // 让 CDN 上的 Leaflet 也按现代浏览器处理
        s.setUserAgentString(s.getUserAgentString() + UA_SUFFIX);

        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setBackgroundColor(Color.WHITE);

        if (state == null) {
            web.loadUrl(BASE + "mobile.html");
        } else {
            web.restoreState(state);
        }

        applyBars("#ffffff", "#ffffff", true);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    /* ---------------- 资源拦截：把 assets 当成 https 站点提供 ---------------- */

    private class Client extends WebViewClient {

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (!HOST.equals(u.getHost())) return null;
            String path = u.getPath();
            if (path == null) return null;
            if (path.startsWith("/assets/")) path = path.substring("/assets/".length());
            else if (path.startsWith("/")) path = path.substring(1);
            if (path.isEmpty()) path = "mobile.html";
            try {
                InputStream in = getAssets().open(path);
                return new WebResourceResponse(mimeOf(path), null, in);
            } catch (IOException e) {
                return new WebResourceResponse("text/plain", "utf-8",
                        404, "Not Found", new HashMap<String, String>(), null);
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
            return route(req.getUrl());
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView v, String url) {
            return route(Uri.parse(url));
        }
    }

    /** 决定一个 URL 是在 WebView 内打开，还是丢给别的 App */
    private boolean route(Uri u) {
        if (u == null) return false;
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();

        // 自己的资源：留在 WebView
        if (HOST.equals(u.getHost())) return false;

        switch (scheme) {
            case "androidamap":
            case "amapuri":
                return launchFirst(new String[]{u.toString()}, "高德地图", PKG_AMAP);
            case "weixin":
            case "wechat":
                return launchFirst(new String[]{u.toString()}, "微信", PKG_WECHAT);
            case "http":
            case "https":
            case "tel":
            case "mailto":
            case "sms":
                return launchFirst(new String[]{u.toString()}, null, null);
            default:
                // file:// 之类一律挡掉
                return true;
        }
    }

    /**
     * 依次尝试一组 URI，第一个能拉起的生效。
     * 用来兼容高德新旧两种 scheme（新的 amapuri://route/plan/ 需要较新版本）。
     */
    private boolean launchFirst(String[] uris, String appName, String pkg) {
        for (String s : uris) {
            if (tryLaunch(Uri.parse(s), pkg)) return true;
        }
        if (appName != null) toast("未安装「" + appName + "」");
        else toast("没有可以打开这个链接的应用");
        return true;
    }

    private boolean tryLaunch(Uri u, String pkg) {
        Intent i = new Intent(Intent.ACTION_VIEW, u);
        // 从 Activity 之外的上下文发起必须带这个 flag，否则抛 AndroidRuntimeException
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // 指定包名 = 显式 Intent，不会被浏览器等别的 App 抢走
        if (pkg != null && isInstalled(pkg)) i.setPackage(pkg);
        try {
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }

    /* ---------------- 定位授权 ---------------- */

    private class Chrome extends WebChromeClient {
        @Override
        public void onGeolocationPermissionsShowPrompt(String origin,
                                                      GeolocationPermissions.Callback cb) {
            if (hasLocationPermission()) {
                cb.invoke(origin, true, false);
            } else {
                pendingGeoCallback = cb;
                pendingGeoOrigin = origin;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    requestPermissions(new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
                } else {
                    cb.invoke(origin, false, false);
                }
            }
        }
    }

    private boolean hasLocationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_LOCATION || pendingGeoCallback == null) return;
        boolean ok = false;
        for (int r : results) if (r == PackageManager.PERMISSION_GRANTED) { ok = true; break; }
        pendingGeoCallback.invoke(pendingGeoOrigin, ok, false);
        if (!ok) toast("定位权限被拒绝，可在系统设置里开启");
        pendingGeoCallback = null;
        pendingGeoOrigin = null;
    }

    /* ---------------- 给网页调用的桥 ---------------- */

    private class Bridge {

        @JavascriptInterface
        public boolean isApp() { return true; }

        @JavascriptInterface
        public boolean hasAmap() { return isInstalled(PKG_AMAP); }

        @JavascriptInterface
        public boolean hasWeChat() { return isInstalled(PKG_WECHAT); }

        /** 在高德地图里显示一个标注点（坐标须为 GCJ-02） */
        @JavascriptInterface
        public void amapMarker(double lat, double lng, String name) {
            String uri = "androidamap://viewMap"
                    + "?sourceApplication=" + enc(getPackageName())
                    + "&poiname=" + enc(name)
                    + "&lat=" + lat
                    + "&lon=" + lng
                    + "&dev=0";
            runOnUi(new String[]{uri}, "高德地图", PKG_AMAP);
        }

        /**
         * 高德路线规划。
         * mode: drive / bus / walk / ride；起点传空串表示用「我的位置」。
         *
         * 官方现行写法是 amapuri://route/plan/（旧版高德只认 androidamap://route），
         * 所以两个都拼出来，按顺序试着拉。
         */
        @JavascriptInterface
        public void amapRoute(String sLat, String sLng, String sName,
                              double dLat, double dLng, String dName, String mode) {
            String head = "?sourceApplication=" + enc(getPackageName());
            StringBuilder mid = new StringBuilder();
            boolean hasStart = sLat != null && !sLat.isEmpty()
                            && sLng != null && !sLng.isEmpty();
            if (hasStart) {
                mid.append("&slat=").append(sLat).append("&slon=").append(sLng);
                if (sName != null && !sName.isEmpty())
                    mid.append("&sname=").append(enc(sName));
            }
            mid.append("&dlat=").append(dLat).append("&dlon=").append(dLng);
            if (dName != null && !dName.isEmpty())
                mid.append("&dname=").append(enc(dName));
            // dev=0：传进去的已经是 GCJ-02，不需要高德再做国测加密
            mid.append("&dev=0&t=").append(routeType(mode))
               .append("&m=").append(routePolicy(mode));

            runOnUi(new String[]{
                    "amapuri://route/plan/" + head + mid,
                    "androidamap://route"  + head + mid
            }, "高德地图", PKG_AMAP);
        }

        /** 打开微信。用启动 Intent 比 weixin:// 更稳，scheme 在新版微信上不保证认。 */
        @JavascriptInterface
        public void wechat() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    Intent i = getPackageManager().getLaunchIntentForPackage(PKG_WECHAT);
                    if (i == null) {
                        if (!tryLaunch(Uri.parse("weixin://"), null)) toast("未安装「微信」");
                        return;
                    }
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { startActivity(i); }
                    catch (Exception e) { toast("未安装「微信」"); }
                }
            });
        }

        /** 复制到剪贴板，返回是否成功 */
        @JavascriptInterface
        public boolean copy(String text) {
            try {
                ClipboardManager cm = (ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null) return false;
                cm.setPrimaryClip(ClipData.newPlainText("行程", text));
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        @JavascriptInterface
        public void toast(final String msg) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                }
            });
        }

        /** 让网页把状态栏 / 导航栏配色同步过来 */
        @JavascriptInterface
        public void setBars(final String statusHex, final String navHex, final boolean lightIcons) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    applyBars(statusHex, navHex, lightIcons);
                }
            });
        }
    }

    /** t：出行方式 —— 0 驾车 / 1 公交 / 2 步行 / 3 骑行 */
    private static String routeType(String mode) {
        if (mode == null) return "0";
        switch (mode) {
            case "bus":  return "1";
            case "walk": return "2";
            case "ride": return "3";
            default:     return "0";   // drive
        }
    }

    /** m：出行策略。公交取「换乘较少」，驾车取「速度快」。 */
    private static String routePolicy(String mode) {
        return "bus".equals(mode) ? "2" : "0";
    }

    private void runOnUi(final String[] uris, final String appName, final String pkg) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                launchFirst(uris, appName, pkg);
            }
        });
    }

    private boolean isInstalled(String pkg) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getPackageManager().getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0));
            } else {
                getPackageManager().getPackageInfo(pkg, 0);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void applyBars(String statusHex, String navHex, boolean lightIcons) {
        Window w = getWindow();
        try {
            w.setStatusBarColor(Color.parseColor(statusHex));
            w.setNavigationBarColor(Color.parseColor(navHex));
        } catch (Throwable ignored) {}
        View decor = w.getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = lightIcons
                    ? (flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
                    : (flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags = lightIcons
                    ? (flags | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
                    : (flags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        decor.setSystemUiVisibility(flags);
    }

    /* ---------------- 杂项 ---------------- */

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    @Override
    public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        // 主题切换由网页负责，这里只让它重新上报状态栏配色
        web.evaluateJavascript(
                "window.__syncBars && window.__syncBars();", null);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface("AndroidBridge");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private static String enc(String s) {
        return s == null ? "" : Uri.encode(s);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private static String mimeOf(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html";
        if (p.endsWith(".js"))   return "application/javascript";
        if (p.endsWith(".css"))  return "text/css";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".webmanifest")) return "application/manifest+json";
        if (p.endsWith(".svg"))  return "image/svg+xml";
        if (p.endsWith(".png"))  return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".ico"))  return "image/x-icon";
        if (p.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }
}
