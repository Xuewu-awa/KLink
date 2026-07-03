package com.xuewu.KLink;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.android1939.kardsserver.KardsLocalServer;
import com.android1939.kardsserver.ServerConfig;
import com.android1939.kardsserver.ServerLog;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * JavaScript ↔ Java 桥接。
 * 前端通过 window.KLink 调用所有原生功能。
 *
 * 三种运行模式：
 *   local  — 本地 KardsLocalServer (127.0.0.1)，自娱自乐
 *   lan    — 局域网 KardsLocalServer (0.0.0.0)，好友联机
 *   remote — 代理转发到远程服务器（step-3 实现）
 */
public class KLinkBridge {

    private final Activity activity;
    private final WebView webView;

    // ----- 服务器实例 -----
    private KardsLocalServer kardsServer;
    private ProxyServer proxyServer;
    private ServerConfig serverConfig;
    private LanDiscovery lanDiscovery;
    private ModManager modManager;

    // ----- 状态 -----
    private boolean serverRunning = false;
    private String currentMode = "none"; // "local" | "lan" | "remote" | "none"
    private String remoteAddress = "";
    private int remotePort = 5231;

    // ----- 持久化 -----
    private static final String PREF_NAME = "KLink_Settings";
    private static final String KEY_MODE = "server_mode";
    private static final String KEY_ROOM_NAME = "room_name";
    private static final String KEY_HOST_NAME = "host_name";
    private static final String KEY_REMOTE_ADDR = "remote_address";
    private static final String KEY_REMOTE_PORT = "remote_port";

    public KLinkBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        this.lanDiscovery = new LanDiscovery(activity, new LanDiscovery.RoomListener() {
            @Override
            public void onRoomFound(JSONObject room) {
                emitRoomFound(room);
            }
        });
        this.modManager = new ModManager();
    }

    // ==================== WebView 生命周期 ====================

    public void onWebViewReady() {
        // 回传已保存的配置给前端
        restoreSettings();
        evalJs("if(window.onKLinkReady) window.onKLinkReady();");
    }

    public void onDestroy() {
        stopServer();
        if (lanDiscovery != null) {
            lanDiscovery.destroy();
        }
    }

    // ==================== 服务器控制 ====================

    /**
     * 启动服务器。
     * @param mode "local" / "lan" / "remote"
     * @param configJson JSON 配置：{ roomName, hostName, remoteAddress, remotePort, ... }
     */
    @JavascriptInterface
    public void startServer(String mode, String configJson) {
        try {
            if (serverRunning) {
                stopServerInternal();
            }

            JSONObject config = new JSONObject(configJson != null ? configJson : "{}");
            currentMode = mode;

            switch (mode) {
                case "local":
                    startLocalServer(config);
                    break;
                case "lan":
                    startLanServer(config);
                    break;
                case "remote":
                    startRemoteProxy(config);
                    break;
                default:
                    emitError("未知模式: " + mode);
                    return;
            }

            serverRunning = true;
            saveSettings(config);
            emitStatus("服务器已启动 (" + mode + ")");
            pushFullStatus();
        } catch (Exception e) {
            serverRunning = false;
            currentMode = "none";
            emitError("启动失败: " + e.getMessage());
        }
    }

    @JavascriptInterface
    public void stopServer() {
        stopServerInternal();
        emitStatus("服务器已停止");
        pushFullStatus();
    }

    private void stopServerInternal() {
        if (kardsServer != null) {
            try {
                kardsServer.stop();
            } catch (Exception ignored) {}
        }
        if (proxyServer != null) {
            try {
                proxyServer.stop();
            } catch (Exception ignored) {}
        }
        if (lanDiscovery != null) {
            lanDiscovery.stopBroadcast();
        }
        serverRunning = false;
        currentMode = "none";
        serverConfig = null;
    }

    @JavascriptInterface
    public String getServerStatus() {
        try {
            if (kardsServer != null && kardsServer.isRunning()) {
                JSONObject json = kardsServer.getStatusJson();
                json.put("mode", currentMode);
                json.put("remoteAddress", remoteAddress);
                json.put("remotePort", remotePort);
                return json.toString();
            }
        } catch (Exception ignored) {}

        try {
            JSONObject json = new JSONObject();
            json.put("running", serverRunning);
            json.put("mode", currentMode);
            json.put("remoteAddress", remoteAddress);
            json.put("remotePort", remotePort);
            return json.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 定时轮询状态（前端每 2 秒调用）。
     */
    @JavascriptInterface
    public String pollStatus() {
        return getServerStatus();
    }

    // ==================== 局域网房间发现 ====================

    /**
     * 开始扫描局域网房间。
     * 结果通过 emitRoomFound 回调推送给前端。
     */
    @JavascriptInterface
    public void startScanRooms() {
        try {
            lanDiscovery.startScan();
            emitStatus("开始扫描局域网房间...");
        } catch (Exception e) {
            emitError("扫描失败: " + e.getMessage());
        }
    }

    @JavascriptInterface
    public void stopScanRooms() {
        lanDiscovery.stopScan();
    }

    /**
     * 连接到发现的房间（切换到远程模式）。
     */
    @JavascriptInterface
    public void connectToRoom(String address, int port) {
        try {
            JSONObject config = new JSONObject();
            config.put("remoteAddress", address);
            config.put("remotePort", port);
            startServer("remote", config.toString());
        } catch (Exception e) {
            emitError("连接失败: " + e.getMessage());
        }
    }

    /**
     * 更新广播出去的房间信息。
     */
    @JavascriptInterface
    public void updateRoomInfo(String roomInfoJson) {
        try {
            JSONObject info = new JSONObject(roomInfoJson != null ? roomInfoJson : "{}");
            lanDiscovery.setRoomInfo(
                    info.optString("roomName", "KARDS Room"),
                    info.optString("hostName", "Host"),
                    info.optInt("httpPort", 5231),
                    info.optInt("wsPort", 5232),
                    info.optInt("players", 0));
        } catch (Exception ignored) {}
    }

    // ==================== 模组管理 ====================

    @JavascriptInterface
    public String scanMods(String directoryPath) {
        return modManager.scanMods();
    }

    /**
     * 打开文件选择器让用户选择 PAK 模组文件。
     */
    @JavascriptInterface
    public void installMod() {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (activity instanceof MainActivity) {
                    ((MainActivity) activity).pickFile();
                }
            }
        });
    }

    /**
     * 文件选择器回调 — 复制选中文件到模组目录。
     */
    public void onFilePicked(android.net.Uri uri) {
        try {
            // 从 content URI 复制到临时文件
            java.io.InputStream in = activity.getContentResolver().openInputStream(uri);
            if (in == null) {
                emitError("无法读取文件");
                return;
            }
            // 获取文件名
            String fileName = "mod_" + System.currentTimeMillis() + ".pak";
            String displayName = uri.getLastPathSegment();
            if (displayName != null && displayName.contains(".")) {
                fileName = displayName.substring(displayName.lastIndexOf('/') + 1);
            }
            // 写到私有临时目录
            java.io.File tmp = new java.io.File(activity.getFilesDir(), fileName);
            java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            in.close();
            out.close();
            // 用 ModManager 安装
            String result = modManager.installMod(tmp.getAbsolutePath());
            tmp.delete(); // 清理临时文件
            if (result != null) {
                emitStatus("安装成功: " + fileName);
            } else {
                emitError("安装失败: " + fileName);
            }
        } catch (Exception e) {
            emitError("安装出错: " + e.getMessage());
        }
    }

    @JavascriptInterface
    public boolean uninstallMod(String modId) {
        boolean ok = modManager.uninstallMod(modId);
        if (ok) {
            emitStatus("卸载成功: " + modId);
        } else {
            emitError("卸载失败: " + modId);
        }
        return ok;
    }

    @JavascriptInterface
    public boolean toggleMod(String modId, boolean enable) {
        return modManager.toggleMod(modId, enable);
    }

    @JavascriptInterface
    public String getModList() {
        return modManager.scanMods();
    }

    // ==================== 主题导入导出 ====================

    /** 暂存待写入的主题 JSON，等用户选好保存位置后写入 */
    private String pendingThemeJson = null;

    /**
     * 导出主题 — 通过系统文件选择器让用户选择保存位置。
     * 前端调用此方法后，系统弹出保存对话框。
     */
    @JavascriptInterface
    public void exportTheme(String themeJson) {
        pendingThemeJson = themeJson;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (activity instanceof MainActivity) {
                    // 解析主题名用于默认文件名
                    String name = "klink-theme";
                    try {
                        JSONObject obj = new JSONObject(pendingThemeJson);
                        String themeName = obj.optString("name", "");
                        if (!themeName.isEmpty()) {
                            name = "klink-theme-" + themeName.replaceAll("[^a-zA-Z0-9_\\-\\u4e00-\\u9fff]", "_");
                        }
                    } catch (Exception ignored) {}
                    ((MainActivity) activity).saveThemeFile(name + ".json");
                }
            }
        });
    }

    /**
     * 快速分享主题 — 通过 Android 分享面板发送 JSON 文本。
     */
    @JavascriptInterface
    public void shareTheme(String themeJson) {
        final String json = themeJson;
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_TEXT, json);
                    intent.putExtra(Intent.EXTRA_SUBJECT, "KLink Theme");
                    activity.startActivity(Intent.createChooser(intent, "分享主题"));
                } catch (Exception e) {
                    showToast("分享失败: " + e.getMessage());
                }
            }
        });
    }

    /**
     * 用户选好保存位置后的回调 — 将主题 JSON 写入该 URI。
     */
    public void onThemeSaveUriReady(final Uri uri) {
        if (pendingThemeJson == null) return;
        final String json = pendingThemeJson;
        pendingThemeJson = null;

        try {
            java.io.OutputStream out = activity.getContentResolver().openOutputStream(uri);
            if (out == null) {
                emitError("无法写入文件");
                return;
            }
            out.write(json.getBytes("UTF-8"));
            out.flush();
            out.close();
            showToast("主题已保存");
        } catch (Exception e) {
            emitError("保存失败: " + e.getMessage());
        }
    }

    /**
     * 导入主题 — 打开文件选择器选取 JSON 文件。
     */
    @JavascriptInterface
    public void pickThemeFile() {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (activity instanceof MainActivity) {
                    ((MainActivity) activity).pickThemeFile();
                }
            }
        });
    }

    // ==================== 背景图片选取 ====================

    /**
     * 选取背景图片 — 打开系统图片选择器。
     */
    @JavascriptInterface
    public void pickBgImageFile() {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (activity instanceof MainActivity) {
                    ((MainActivity) activity).pickImageFile();
                }
            }
        });
    }

    /**
     * 用户选好背景图片后的回调 — 压缩后以 Base64 传给前端。
     * 原图可能 10MB+，直接 Base64 会导致 WebView 卡死，
     * 因此先缩放到合理尺寸再编码为 JPEG。
     */
    public void onBgImagePicked(final Uri uri) {
        try {
            // 1. 只读尺寸，不加载全图
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            java.io.InputStream in1 = activity.getContentResolver().openInputStream(uri);
            android.graphics.BitmapFactory.decodeStream(in1, null, opts);
            in1.close();

            // 2. 计算缩放比例，目标最长边 800px
            int maxDim = 800;
            int scale = 1;
            int w = opts.outWidth;
            int h = opts.outHeight;
            while (w / scale > maxDim || h / scale > maxDim) {
                scale *= 2;
            }

            // 3. 按缩放比例解码
            android.graphics.BitmapFactory.Options decodeOpts = new android.graphics.BitmapFactory.Options();
            decodeOpts.inSampleSize = scale;
            java.io.InputStream in2 = activity.getContentResolver().openInputStream(uri);
            android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeStream(in2, null, decodeOpts);
            in2.close();

            if (bitmap == null) {
                emitError("无法解码图片");
                return;
            }

            // 4. 编码为 JPEG，质量 75%
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, bos);
            bitmap.recycle();
            byte[] bytes = bos.toByteArray();
            bos.close();

            // 5. Base64
            String base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
            final String dataUri = "data:image/jpeg;base64," + base64;

            // 传给前端
            final String escaped = dataUri.replace("\\", "\\\\").replace("'", "\\'");
            evalJs("if(window.onBgImageLoaded) window.onBgImageLoaded('" + escaped + "');");
        } catch (Exception e) {
            emitError("读取图片失败: " + e.getMessage());
        }
    }

    /**
     * 用户选好主题文件后的回调 — 读取内容并传给前端。
     */
    public void onThemeFilePicked(final Uri uri) {
        try {
            java.io.InputStream in = activity.getContentResolver().openInputStream(uri);
            if (in == null) {
                emitError("无法读取文件");
                return;
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            in.close();
            String json = bos.toString("UTF-8");
            bos.close();

            // 传给前端
            final String escaped = json.replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r");
            evalJs("if(window.onThemeFileLoaded) window.onThemeFileLoaded('" + escaped + "');");
        } catch (Exception e) {
            emitError("读取主题失败: " + e.getMessage());
        }
    }

    // ==================== 实用方法 ====================

    @JavascriptInterface
    public void launchGame() {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (activity instanceof MainActivity) {
                    ((MainActivity) activity).launchGame();
                }
            }
        });
    }

    @JavascriptInterface
    public void setKeepScreenOn(boolean keep) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (keep) {
                    activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            }
        });
    }

    @JavascriptInterface
    public void showToast(String message) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }

    @JavascriptInterface
    public String getAppVersion() {
        try {
            return activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0.0";
        }
    }

    // ==================== 前端回调 ====================

    private void evalJs(String js) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                webView.evaluateJavascript(js, null);
            }
        });
    }

    private void emitStatus(String message) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "status");
            json.put("message", message);
            json.put("timestamp", System.currentTimeMillis());
            evalJs("if(window.onServerEvent) window.onServerEvent(" + json + ");");
        } catch (Exception ignored) {}
    }

    private void emitError(String message) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "error");
            json.put("message", message);
            json.put("timestamp", System.currentTimeMillis());
            evalJs("if(window.onServerEvent) window.onServerEvent(" + json + ");");
        } catch (Exception ignored) {}
    }

    public void emitRoomFound(JSONObject roomInfo) {
        try {
            roomInfo.put("type", "room_found");
            evalJs("if(window.onRoomFound) window.onRoomFound(" + roomInfo + ");");
        } catch (Exception ignored) {}
    }

    private void pushFullStatus() {
        evalJs("if(window.onServerEvent) window.onServerEvent(" + getServerStatus() + ");");
    }

    // ==================== 持久化 ====================

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private void saveSettings(JSONObject config) {
        SharedPreferences.Editor editor = prefs().edit();
        editor.putString(KEY_MODE, currentMode);
        editor.putString(KEY_ROOM_NAME, config.optString("roomName", "KARDS Room"));
        editor.putString(KEY_HOST_NAME, config.optString("hostName", "Host"));
        if ("remote".equals(currentMode)) {
            editor.putString(KEY_REMOTE_ADDR, config.optString("remoteAddress", ""));
            editor.putInt(KEY_REMOTE_PORT, config.optInt("remotePort", 5231));
        }
        editor.apply();
    }

    private void restoreSettings() {
        SharedPreferences p = prefs();
        String savedMode = p.getString(KEY_MODE, "");
        String roomName = p.getString(KEY_ROOM_NAME, "KARDS Room");
        String hostName = p.getString(KEY_HOST_NAME, "Host");
        String savedAddr = p.getString(KEY_REMOTE_ADDR, "");
        int savedPort = p.getInt(KEY_REMOTE_PORT, 5231);

        try {
            JSONObject json = new JSONObject();
            json.put("savedMode", savedMode);
            json.put("roomName", roomName);
            json.put("hostName", hostName);
            json.put("remoteAddress", savedAddr);
            json.put("remotePort", savedPort);
            evalJs("if(window.onSettingsRestored) window.onSettingsRestored(" + json + ");");
        } catch (Exception ignored) {}
    }

    // ==================== 内部：启动服务器 ====================

    private void startLocalServer(JSONObject config) throws Exception {
        serverConfig = buildServerConfig(config, "127.0.0.1");
        ensureServerStarted();
    }

    private void startLanServer(JSONObject config) throws Exception {
        serverConfig = buildServerConfig(config, "0.0.0.0");
        ensureServerStarted();
        // LAN 模式下自动开始广播房间
        lanDiscovery.setRoomInfo(
                config.optString("roomName", "KARDS Room"),
                config.optString("hostName", "Host"),
                5231, 5232, 0);
        lanDiscovery.startBroadcast();
    }

    private void startRemoteProxy(JSONObject config) throws Exception {
        remoteAddress = config.optString("remoteAddress", "");
        remotePort = config.optInt("remotePort", 5231);
        if (remoteAddress.isEmpty()) {
            throw new IllegalArgumentException("请输入远程服务器地址");
        }
        if (proxyServer == null) {
            proxyServer = new ProxyServer(remoteAddress, remotePort);
        } else {
            proxyServer.stop();
            proxyServer = new ProxyServer(remoteAddress, remotePort);
        }
        proxyServer.start();
    }

    private ServerConfig buildServerConfig(JSONObject json, String bindHost) {
        ServerConfig cfg = new ServerConfig();
        cfg.bindHost = bindHost;
        cfg.httpPort = 5231;
        cfg.wsPort = 5232;
        cfg.roomName = json.optString("roomName", "KARDS Room");
        cfg.hostName = json.optString("hostName", "Host");
        // adminToken 可让房主踢人
        cfg.adminToken = json.optString("adminToken", "");
        cfg.preferredPlayerName = json.optString("preferredPlayerName", "");
        return cfg;
    }

    private void ensureServerStarted() throws Exception {
        if (kardsServer == null) {
            kardsServer = new KardsLocalServer();
        }
        if (kardsServer.isRunning()) {
            kardsServer.stop();
        }
        Context appCtx = activity.getApplicationContext();
        kardsServer.start(appCtx, serverConfig);
    }
}
