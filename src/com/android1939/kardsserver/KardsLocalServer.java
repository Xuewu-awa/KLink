package com.android1939.kardsserver;

import android.content.Context;

import com.android1939.kardsserver.db.KardsDatabase;
import com.android1939.kardsserver.http.SimpleHttpServer;
import com.android1939.kardsserver.model.UserRecord;
import com.android1939.kardsserver.ws.SimpleWebSocketServer;

import org.json.JSONArray;
import org.json.JSONObject;

public final class KardsLocalServer {
    private ServerConfig config;
    private AssetStore assets;
    private KardsDatabase database;
    private MatchManager matches;
    private SimpleHttpServer httpServer;
    private SimpleWebSocketServer webSocketServer;
    private CardIdManager cardIds;
    private VersionPakManager versionPak;
    private CardCatalog cardCatalog;
    private PlayerCards playerCards;
    private StoreService store;

    /** 新号初始卡包数（0 = 不给，让玩家自己买）。 */
    private static final int STARTING_PACKS = 5;

    public synchronized void start(Context context, ServerConfig requestedConfig) throws Exception {
        if (isRunning()) {
            return;
        }
        config = requestedConfig == null ? new ServerConfig() : requestedConfig.copy();
        Context appContext = context.getApplicationContext();
        assets = new AssetStore(appContext);
        cardIds = new CardIdManager(assets);
        versionPak = new VersionPakManager(appContext);
        database = new KardsDatabase(appContext, config.databaseName);
        database.getWritableDatabase();
        matches = new MatchManager(database, assets);
        // 后台「对局配置」里改过的开局金卡开关，重启后仍生效
        try {
            String saved = database.readSetting("admin:allGoldCards");
            if (saved != null) {
                matches.setAllGoldCards("1".equals(saved));
            }
        } catch (Exception ignored) {
        }
        // 对外通告地址：只有「本机 mode 定的 bindHost」归启动器所有，
        // 「告诉其它设备用哪个地址连」可以由后台配置（远程转发/域名场景）。
        //
        // 注意这里**绝不**从库里读监听 IP/端口 —— 监听地址由模式决定
        // （local=127.0.0.1 / lan=0.0.0.0），不允许被持久化配置覆盖。
        // 桌面端踩过这个坑：库里存着旧的 listenIp，切换局域网时把启动器写的
        // 0.0.0.0 顶回 127.0.0.1，结果"局域网模式却只绑回环"。
        try {
            String savedPublicIp = database.readSetting("admin:publicIp");
            if (savedPublicIp != null && savedPublicIp.trim().length() > 0) {
                config.advertisedHost = savedPublicIp.trim();
            }
        } catch (Exception ignored) {
        }
        // 真开包 / 商店：卡牌目录（2067 张）+ 开包结算 + 商店配置
        cardCatalog = new CardCatalog(appContext, assets);
        playerCards = new PlayerCards(database, cardCatalog);
        store = new StoreService(database, assets, playerCards);
        webSocketServer = new SimpleWebSocketServer(config, database, matches);
        httpServer = new SimpleHttpServer(config.bindHost, config.httpPort,
                new KardsHttpHandler(appContext, config, database, assets, matches,
                        webSocketServer, cardIds, versionPak, cardCatalog, playerCards, store));
        webSocketServer.start();
        httpServer.start();
        ServerLog.add("room", "server started: " + config.roomName);
        // 明确回报"别的设备该连哪里" —— 0.0.0.0 不是可连接地址，只显示它会让人以为连不上是 bug
        ServerLog.add("room", "监听 " + config.bindHost + ":" + config.httpPort
                + "（WS " + config.wsPort + "）");
        if ("0.0.0.0".equals(config.bindHost)) {
            String lanIp = localLanIp();
            if (lanIp.length() > 0) {
                ServerLog.add("room", "局域网地址: http://" + lanIp + ":" + config.httpPort
                        + "/  （其它设备用这个地址连接）");
            } else {
                ServerLog.add("room", "局域网模式，但没拿到本机局域网 IP —— 请确认已连上 WiFi/热点");
            }
        }
    }

    /** 新号初始卡包数，供 KardsHttpHandler 在首次登录时使用。 */
    public static int startingPacks() {
        return STARTING_PACKS;
    }

    public synchronized void stop() {
        if (httpServer != null) {
            httpServer.stop();
        }
        if (webSocketServer != null) {
            webSocketServer.stop();
        }
        ServerLog.add("room", "server stopped");
        httpServer = null;
        webSocketServer = null;
        matches = null;
        database = null;
        assets = null;
        cardIds = null;
        versionPak = null;
    }

    public synchronized boolean isRunning() {
        return httpServer != null && httpServer.isRunning()
                && webSocketServer != null && webSocketServer.isRunning();
    }

    public synchronized String getHttpUrl() {
        if (config == null) {
            return "";
        }
        // 局域网模式显示**真实**局域网 IP —— 显示 <device-ip> 或 0.0.0.0 等于没告诉用户该连哪
        return getConnectAddress();
    }

    public synchronized String getWebSocketUrl() {
        if (config == null) {
            return "";
        }
        boolean lan = "0.0.0.0".equals(config.bindHost);
        String host = config.advertisedHost != null && config.advertisedHost.trim().length() > 0
                ? config.advertisedHost.trim()
                : (lan ? localLanIp() : "127.0.0.1");
        if (host == null || host.length() == 0) {
            host = lan ? "<未获取到局域网IP>" : "127.0.0.1";
        }
        return "ws://" + host + ":" + config.wsPort + "/ws";
    }

    public synchronized JSONObject getStatusJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("running", isRunning());
            json.put("http_url", getHttpUrl());
            json.put("websocket_url", getWebSocketUrl());
            json.put("room_name", config == null ? "" : config.roomName);
            json.put("host_name", config == null ? "" : config.hostName);
            json.put("online_players", webSocketServer == null ? 0 : webSocketServer.onlineCount());
            json.put("matches", matches == null ? 0 : matches.matchCount());
            json.put("players", roomPlayersJson());
            json.put("logs", ServerLog.recent());
        } catch (Exception ignored) {
        }
        return json;
    }

    private JSONArray roomPlayersJson() throws Exception {
        JSONArray array = new JSONArray();
        if (database == null) {
            return array;
        }
        for (UserRecord user : database.listUsers()) {
            boolean kicked = matches != null && matches.isKicked(user.id);
            if (!user.isOnline && !kicked) {
                continue;
            }
            JSONObject item = new JSONObject();
            item.put("player_id", user.id);
            item.put("player_name", user.playerName);
            item.put("player_tag", user.playerTag);
            item.put("username", user.username);
            item.put("online", user.isOnline);
            item.put("kicked", kicked);
            array.put(item);
        }
        return array;
    }

    private String hostForDisplay() {
        if (config.advertisedHost != null && config.advertisedHost.trim().length() > 0) {
            return config.advertisedHost.trim();
        }
        return "0.0.0.0".equals(config.bindHost) ? "<device-ip>" : config.bindHost;
    }

    /**
     * 本机真实的局域网 IPv4，用于在控制台/日志里告诉用户"别的设备该连哪个地址"。
     *
     * <p>不要把 {@code 0.0.0.0} 当成可连接地址：它只是"监听所有网卡"的意思，
     * 别的设备连不上它。局域网模式下必须显示真实 IP。</p>
     */
    public static String localLanIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            String fallback = null;
            while (interfaces != null && interfaces.hasMoreElements()) {
                java.net.NetworkInterface nic = interfaces.nextElement();
                if (nic == null || !nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                java.util.Enumeration<java.net.InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();
                    if (address.isLoopbackAddress() || address.isAnyLocalAddress()) {
                        continue;
                    }
                    String host = address.getHostAddress();
                    if (host == null || host.indexOf(':') >= 0) {
                        continue;
                    }
                    if (host.startsWith("192.168.") || host.startsWith("10.")
                            || host.startsWith("172.")) {
                        return host;
                    }
                    if (fallback == null) {
                        fallback = host;
                    }
                }
            }
            if (fallback != null) {
                return fallback;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** 供界面显示：局域网模式下其它设备应用的完整地址；本机模式返回回环地址。 */
    public synchronized String getConnectAddress() {
        if (config == null) {
            return "";
        }
        boolean lan = "0.0.0.0".equals(config.bindHost);
        String host = lan ? localLanIp() : "127.0.0.1";
        if (host.length() == 0) {
            host = lan ? "<未获取到局域网IP>" : "127.0.0.1";
        }
        return "http://" + host + ":" + config.httpPort + "/";
    }
}
