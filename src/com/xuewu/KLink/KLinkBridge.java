package com.xuewu.KLink;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
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
