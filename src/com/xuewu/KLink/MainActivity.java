package com.xuewu.KLink;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * KLink 主 Activity — WebView 壳，夺舍原游戏入口。
 *
 * 启动流程（后台即前台）：
 *   1. 先显示欢迎界面 assets/splash.html（渐变 + 图标 + 进度）
 *   2. 后台线程按上次保存的模式自动启动私服，并轮询就绪状态；
 *      进度通过 JS 桥 KLinkSplash.set() 回传
 *   3. 就绪后加载 http://127.0.0.1:{port}/admin-ui/ —— 即私服自己发出的
 *      管理后台（与桌面端 fyserver 同架构），并保留返回 KLink 本地界面的入口
 *   4. 任一步失败 / 超时 → 回退到本地 assets/index.html，功能不受影响
 */
public class MainActivity extends Activity {

    private WebView webView;
    private KLinkBridge bridge;
    private SplashBridge splashBridge;

    /** 欢迎界面地址（本地 asset） */
    private static final String SPLASH_URL = "file:///android_asset/splash.html";
    /** KLink 原有控制面板（回退入口） */
    private static final String PANEL_URL = "file:///android_asset/index.html";

    /** 自动启动私服的等待上限：约 20 秒（对齐桌面端 FyServerHost 的就绪超时） */
    private static final long SERVER_READY_TIMEOUT_MS = 20000L;
    /** 轮询间隔 */
    private static final long SERVER_POLL_INTERVAL_MS = 400L;

    /**
     * 后台页面的「布局宽度」（CSS 像素）。
     *
     * <p>后台是按桌面浏览器宽度设计的：`.page` 最大 1380px，而「用户管理」表的内容宽度
     * 约 1071px（含 ID/玩家/最近登录/登录 IP/登录设备/卡组/状态/创建时间/操作 共 9 列）。
     * 手机横屏的 CSS 宽度通常只有 640–960，直接渲染会让内容挤成一团、表格疯狂横向滚动。</p>
     *
     * <p>做法：把 viewport 固定成这个宽度，页面按它排版，再由 WebView 等比缩放铺满屏幕。</p>
     *
     * <p><b>注意 initial-scale 绝对不能写</b>：写了 {@code initial-scale=1} 会把缩放锁死在
     * 1.0，等于放弃"缩放到适应屏幕"，页面右侧会被直接裁掉（真机上就是这么翻车的）。
     * 只给 {@code width}，让 WebView 自己算出合适的缩放。</p>
     */
    private static final int ADMIN_LAYOUT_WIDTH = 1320;

    /** 给后台页面注入 viewport（幂等：已存在就改，没有就插到 head 最前）。 */
    private static final String VIEWPORT_FIX_JS =
            "(function(){"
                    + "var w='" + ADMIN_LAYOUT_WIDTH + "';"
                    + "var m=document.querySelector('meta[name=viewport]');"
                    + "if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');"
                    + "(document.head||document.documentElement).insertBefore(m,(document.head||document.documentElement).firstChild);}"
                    + "m.setAttribute('content','width='+w);"
                    + "return w;"
                    + "})()";

    /** 后台 HTML 的 Content-Type（只有这种响应才做 viewport 重写）。 */
    private static final String HTML_CONTENT_TYPE = "text/html";

    /**
     * 注入到后台页面里的「启动游戏」悬浮按钮。
     *
     * <p>后台是 fyserver 的管理界面，**没有游戏入口** —— 直接用它当主界面的话
     * 玩家就进不去游戏了。这里补一个悬浮按钮，点它走 JS 桥调回原生启动游戏。</p>
     *
     * <p>游戏专属的管理项（模组 / 卡牌ID / 版本补丁 / 主题）仍留在 KLink 控制面板，
     * 按钮旁边的「控制面板」进入。</p>
     */
    private static final String GAME_LAUNCHER_JS =
            "(function(){"
                    + "if(window.__klinkLauncher)return;window.__klinkLauncher=1;"
                    + "function jit(){"
                    + "try{ if(!(window.KLinkHost&&window.KLinkHost.launchGame)) return; }catch(e){ return; }"
                    + "if(document.getElementById('klink-game-dock'))return;"
                    + "var host=document.createElement('div');host.id='klink-game-dock';"
                    + "host.innerHTML="
                    + "'<button type=\"button\" id=\"klink-launch-game\">启动游戏</button>'"
                    + "+'<button type=\"button\" id=\"klink-open-panel\">控制面板</button>';"
                    + "var css=document.createElement('style');"
                    + "css.textContent='#klink-game-dock{position:fixed;right:18px;bottom:18px;z-index:99999;"
                    + "display:flex;gap:8px;font-family:inherit}';"
                    + "css.textContent+='#klink-game-dock button{border:0;cursor:pointer;border-radius:12px;"
                    + "padding:11px 18px;font-size:14px;font-weight:700;letter-spacing:1px;"
                    + "box-shadow:0 8px 24px rgba(0,0,0,.28)}';"
                    + "css.textContent+='#klink-launch-game{background:#0f766e;color:#eafffb}';"
                    + "css.textContent+='#klink-launch-game:active{background:#0b5c55}';"
                    + "css.textContent+='#klink-open-panel{background:rgba(255,255,255,.9);color:#334155}';"
                    + "document.head.appendChild(css);document.body.appendChild(host);"
                    + "document.getElementById('klink-launch-game').onclick=function(){"
                    + "try{window.KLinkHost.launchGame()}catch(e){alert('无法启动游戏: '+e)}};"
                    + "document.getElementById('klink-open-panel').onclick=function(){"
                    + "try{window.KLinkHost.openPanel()}catch(e){}};"
                    + "}"
                    + "if(document.readyState==='loading'){"
                    + "document.addEventListener('DOMContentLoaded',jit)}else{jit()}"
                    + "})()";

    private static final String GAME_ACTIVITY = "com.epicgames.unreal.SplashActivity";
    private static final int FILE_SELECT_CODE = 100;
    private static final int THEME_FILE_SELECT_CODE = 101;
    private static final int THEME_SAVE_CODE = 102;
    private static final int BG_IMAGE_SELECT_CODE = 103;
    private static final int BG_VIDEO_SELECT_CODE = 104;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setFullscreen();

        webView = new WebView(this);
        setupWebView();

        bridge = new KLinkBridge(this, webView);
        webView.addJavascriptInterface(bridge, "KLink");

        splashBridge = new SplashBridge();
        webView.addJavascriptInterface(splashBridge, "KLinkSplashHost");
        webView.addJavascriptInterface(new HostBridge(), "KLinkHost");

        setContentView(webView);
        webView.loadUrl(SPLASH_URL);
    }

    // ==================== 欢迎界面 / 启动编排 ====================

    /**
     * 欢迎界面的 JS 桥。只暴露启动流程需要的最小能力。
     * 页面也可以调用 KLinkSplashHost.isServerRunning() 自己判断。
     */
    private final class SplashBridge {
        /** 把进度回传给欢迎界面（主线程执行 evaluateJavascript）。 */
        void progress(final int pct, final String text) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    evalOnSplash("KLinkSplash.set(" + pct + ", " + jsString(text) + ")");
                }
            });
        }

        void markFailed(final String text) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    evalOnSplash("KLinkSplash.fail(" + jsString(text) + ")");
                }
            });
        }

        @JavascriptInterface
        public boolean isServerRunning() {
            return bridge != null && bridge.isServerMode();
        }

        /** 欢迎界面加载完成后，页面调用它开始启动流程。 */
        @JavascriptInterface
        public void beginStartup() {
            startServerAndEnterAdmin();
        }

        /** 用户点击"跳过"或失败后的手动入口。 */
        @JavascriptInterface
        public void openPanel() {
            enterPanel();
        }
    }

    private void evalOnSplash(String js) {
        try {
            webView.evaluateJavascript(js, null);
        } catch (Exception ignored) {
        }
    }

    /**
     * 后台页面用的宿主桥：只暴露「启动游戏」和「回控制面板」两个动作。
     *
     * <p>后台（fyserver 的管理界面）本身没有游戏入口，注入的悬浮按钮通过它调回原生。</p>
     */
    private final class HostBridge {
        @JavascriptInterface
        public void launchGame() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // 与 KLink 面板的「启动游戏」走同一条路：
                    // 先按当前模式应用版本补丁 pak，再拉起游戏 Activity
                    try {
                        com.android1939.kardsserver.VersionPakManager pakManager =
                                new com.android1939.kardsserver.VersionPakManager(getApplicationContext());
                        pakManager.applyBeforeLaunch(bridge == null ? "local" : bridge.currentModeForPatch());
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this,
                                "版本补丁未应用: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                    launchGame();
                }
            });
        }

        @JavascriptInterface
        public void openPanel() {
            enterPanel();
        }

        /**
         * 从 KLink 面板回管理后台。
         * 后台是私服自己发的页面，所以必须等私服在跑才有地址。
         */
        @JavascriptInterface
        public void openAdminUi() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    String adminUrl = bridge == null ? null : bridge.getAdminUiUrl();
                    if (adminUrl != null && adminUrl.length() > 0) {
                        webView.loadUrl(adminUrl);
                    } else {
                        Toast.makeText(MainActivity.this,
                                "私服未运行，请先在「服务器」页启动服务器",
                                Toast.LENGTH_LONG).show();
                    }
                }
            });
        }

        @JavascriptInterface
        public boolean isServerRunning() {
            return bridge != null && bridge.isServerMode();
        }
    }

    /** 简易的 JS 字符串转义（够用于状态文案，避免引号/换行破坏脚本）。 */
    private static String jsString(String s) {
        if (s == null) return "''";
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\'': sb.append("\\'"); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '<':  sb.append("\\u003c"); break;
                case '>':  sb.append("\\u003e"); break;
                default:   sb.append(c);
            }
        }
        return sb.append('\'').toString();
    }

    /**
     * 后台线程：自动启动私服 → 轮询就绪 → 进入后台。
     * 全程不阻塞 UI（WebView 此时正在显示欢迎界面）。
     */
    private void startServerAndEnterAdmin() {
        // 已经在跑（例如从后台页面退回后再进）就直接进
        if (bridge != null && bridge.isServerMode()) {
            enterAdminOrPanel();
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                splashBridge.progress(12, "正在启动本地服务器");

                String mode;
                try {
                    mode = bridge.autoStartSavedMode();
                } catch (Exception e) {
                    mode = null;
                }

                if (mode == null) {
                    // 没保存过模式，或上次是远程转发模式 → 交给 KLink 面板处理
                    splashBridge.progress(100, "未配置本地服务器");
                    sleep(280);
                    enterPanel();
                    return;
                }

                final String modeLabel = "lan".equals(mode) ? "局域网" : "本地";
                splashBridge.progress(38, modeLabel + "服务器启动中");

                long deadline = System.currentTimeMillis() + SERVER_READY_TIMEOUT_MS;
                int step = 0;
                while (System.currentTimeMillis() < deadline) {
                    if (bridge.isServerMode()) {
                        splashBridge.progress(88, "服务器就绪");
                        break;
                    }
                    sleep(SERVER_POLL_INTERVAL_MS);

                    // 38% → 84% 之间缓慢推进，让用户看到进度在走
                    step++;
                    int pct = Math.min(84, 38 + step * 3);
                    splashBridge.progress(pct, modeLabel + "服务器启动中");
                }

                if (bridge.isServerMode()) {
                    String adminUrl = bridge.getAdminUiUrl();
                    if (adminUrl != null && adminUrl.length() > 0) {
                        splashBridge.progress(100, "正在加载管理后台");
                        sleep(320);
                        enterAdmin(adminUrl);
                        return;
                    }
                }

                // 超时或拿不到地址
                splashBridge.markFailed("服务器启动超时，已返回控制面板");
                sleep(1200);
                enterPanel();
            }
        }, "klink-startup").start();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** 判断是否为私服发出的后台页面（需要做布局宽度适配）。 */
    private static boolean isAdminUiUrl(String url) {
        return url != null
                && (url.contains("127.0.0.1") || url.contains("localhost"))
                && url.contains("/admin-ui/");
    }

    /**
     * 取回后台 HTML、插入 viewport meta 后返回。
     *
     * <p>只改 {@code <head>} 里的内容：把 layout viewport 固定成
     * {@link #ADMIN_LAYOUT_WIDTH}，浏览器随后等比缩放到铺满屏幕 —— 页面不会溢出。</p>
     *
     * <p>失败（网络异常 / 非 HTML）返回 null，交回 WebView 自己加载，
     * 保证任何情况下页面都还能打开。</p>
     */
    private android.webkit.WebResourceResponse injectViewport(String url) {
        java.io.InputStream in = null;
        java.net.HttpURLConnection connection = null;
        try {
            connection = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("Accept", "text/html");
            int code = connection.getResponseCode();
            if (code != 200) {
                return null;
            }
            String contentType = connection.getContentType();
            if (contentType == null || !contentType.contains(HTML_CONTENT_TYPE)) {
                return null;
            }

            in = connection.getInputStream();
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(8192);
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            String html = buffer.toString("UTF-8");

            String meta = "<meta name=\"viewport\" content=\"width=" + ADMIN_LAYOUT_WIDTH + "\">";
            // 后台的 17 个页面都是同一套 shell，自带 <meta name="viewport" content="width=device-width,...">。
            // 必须**替换**它，不能新插一个 —— 后面那个会生效，插在前面等于没插。
            String rewritten = replaceViewportMeta(html, meta);

            java.util.Map<String, String> responseHeaders = new java.util.HashMap<String, String>();
            responseHeaders.put("Cache-Control", "no-cache, no-store, must-revalidate");
            return new android.webkit.WebResourceResponse(
                    "text/html", "utf-8",
                    new java.io.ByteArrayInputStream(rewritten.getBytes("UTF-8")));
        } catch (Exception e) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (connection != null) connection.disconnect(); } catch (Exception ignored) {}
        }
    }

    /**
     * 把 HTML 里已有的 viewport meta 换成给定的那个；没有就插到 {@code <head>} 最前。
     *
     * <p>后台页面自带 {@code width=device-width} 的 viewport，如果只是"再插一个"，
     * 后出现的那个会生效，等于没改 —— 所以必须替换。</p>
     */
    private static String replaceViewportMeta(String html, String replacement) {
        String lower = html.toLowerCase(java.util.Locale.US);
        int nameAt = lower.indexOf("name=\"viewport\"");
        if (nameAt < 0) {
            nameAt = lower.indexOf("name='viewport'");
        }
        if (nameAt >= 0) {
            int tagStart = lower.lastIndexOf("<meta", nameAt);
            int tagEnd = html.indexOf('>', nameAt);
            if (tagStart >= 0 && tagEnd > tagStart) {
                return html.substring(0, tagStart) + replacement + html.substring(tagEnd + 1);
            }
        }
        int head = lower.indexOf("<head>");
        if (head >= 0) {
            return html.substring(0, head + 6) + replacement + html.substring(head + 6);
        }
        return replacement + html;
    }

    /** 进入私服发出的管理后台。 */
    private void enterAdmin(final String url) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                webView.loadUrl(url);
            }
        });
    }

    /** 私服已在运行就进后台，否则退回 KLink 面板。 */
    private void enterAdminOrPanel() {
        String adminUrl = bridge == null ? null : bridge.getAdminUiUrl();
        if (adminUrl != null && adminUrl.length() > 0) {
            enterAdmin(adminUrl);
        } else {
            enterPanel();
        }
    }

    /** 回退到 KLink 本地控制面板。 */
    private void enterPanel() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                webView.loadUrl(PANEL_URL);
            }
        });
    }

    private void setFullscreen() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        hideSystemBars();
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        // 后台是 http 页面、KLink 面板是 file 页面，两者都要能访问自己的资源。
        // 不开 setAllowUniversalAccessFromFileURLs —— 那是公认的 XSS 放大器，
        // 而本方案不需要它（后台走 http 同源，面板走 JS 桥）。
        // 仅调试时开启，release 必须关闭（同网段可 Chrome DevTools 控制 WebView）
        // if (Build.VERSION.SDK_INT >= 19) {
        //     WebView.setWebContentsDebuggingEnabled(true);
        // }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (url != null && url.contains("splash.html")) {
                    // 欢迎界面自己会调 KLinkSplashHost.beginStartup()
                    return;
                }
                if (isAdminUiUrl(url)) {
                    // 后台按桌面宽度设计，注入 viewport 让 WebView 缩放到铺满屏幕
                    view.evaluateJavascript(VIEWPORT_FIX_JS, null);
                    // 后台没有游戏入口，补一个悬浮的「启动游戏 / 控制面板」
                    view.evaluateJavascript(GAME_LAUNCHER_JS, null);
                }
                bridge.onWebViewReady();
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view,
                    String url) {
                // 拦截 klink-video://bg → 返回缓存的背景视频文件
                if ("klink-video://bg".equals(url)) {
                    try {
                        java.io.File videoFile = new java.io.File(getCacheDir(), "bg_video.mp4");
                        if (videoFile.exists()) {
                            java.io.FileInputStream in = new java.io.FileInputStream(videoFile);
                            return new android.webkit.WebResourceResponse(
                                    "video/mp4", null, in);
                        }
                    } catch (Exception ignored) {}
                }

                // 后台页面：在**响应阶段**把 viewport 塞进去，而不是等 onPageFinished 再改。
                // 后者会让页面先按默认宽度排版、注入后再重排一次（肉眼可见的闪动）。
                if (isAdminUiUrl(url) && (url.endsWith(".html") || url.endsWith("/admin-ui/"))) {
                    android.webkit.WebResourceResponse rewritten = injectViewport(url);
                    if (rewritten != null) {
                        return rewritten;
                    }
                }
                return super.shouldInterceptRequest(view, url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url == null) {
                    return false;
                }
                // 本机回环地址（私服发出的后台）必须留在 WebView 内，
                // 否则点一下导航就被甩到系统浏览器，后台彻底不可用。
                if (url.contains("127.0.0.1") || url.contains("localhost")
                        || url.startsWith("file://")) {
                    return false;
                }
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                    return true;
                }
                return false;
            }
        });
    }

    /**
     * 打开文件选择器，用于安装模组。
     */
    public void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, FILE_SELECT_CODE);
    }

    /**
     * 打开文件选择器，用于导入主题 JSON。
     */
    public void pickThemeFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        String[] mimeTypes = {"application/json", "text/plain", "text/json"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        startActivityForResult(intent, THEME_FILE_SELECT_CODE);
    }

    /**
     * 保存主题 JSON 到用户指定位置（通过系统文件选择器）。
     */
    public void saveThemeFile(String initialName) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, initialName);
        startActivityForResult(intent, THEME_SAVE_CODE);
    }

    /**
     * 打开图片选择器，用于选取背景图片。
     */
    public void pickImageFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, BG_IMAGE_SELECT_CODE);
    }

    /**
     * 打开视频选择器，用于选取背景视频。
     */
    public void pickVideoFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        startActivityForResult(intent, BG_VIDEO_SELECT_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        Uri uri = data.getData();
        if (uri == null || bridge == null) return;

        switch (requestCode) {
            case FILE_SELECT_CODE:
                bridge.onFilePicked(uri);
                break;
            case THEME_FILE_SELECT_CODE:
                bridge.onThemeFilePicked(uri);
                break;
            case THEME_SAVE_CODE:
                bridge.onThemeSaveUriReady(uri);
                break;
            case BG_IMAGE_SELECT_CODE:
                bridge.onBgImagePicked(uri);
                break;
            case BG_VIDEO_SELECT_CODE:
                bridge.onBgVideoPicked(uri);
                break;
        }
    }

    /**
     * 启动原游戏。
     */
    public void launchGame() {
        try {
            Intent intent = new Intent();
            intent.setClassName(getPackageName(), GAME_ACTIVITY);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法启动游戏: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            // 不退出，最小化到后台
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onDestroy() {
        if (bridge != null) {
            bridge.onDestroy();
        }
        super.onDestroy();
    }
}
