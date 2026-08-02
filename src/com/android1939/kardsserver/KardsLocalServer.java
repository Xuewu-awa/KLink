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
        webSocketServer = new SimpleWebSocketServer(config, database, matches);
        httpServer = new SimpleHttpServer(config.bindHost, config.httpPort,
                new KardsHttpHandler(config, database, assets, matches, webSocketServer, cardIds, versionPak));
        webSocketServer.start();
        httpServer.start();
        ServerLog.add("room", "server started: " + config.roomName);
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
        return "http://" + hostForDisplay() + ":" + config.httpPort + "/";
    }

    public synchronized String getWebSocketUrl() {
        if (config == null) {
            return "";
        }
        return "ws://" + hostForDisplay() + ":" + config.wsPort + "/ws";
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
}
