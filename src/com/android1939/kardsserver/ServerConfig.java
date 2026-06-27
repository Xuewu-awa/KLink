package com.android1939.kardsserver;

public final class ServerConfig {
    public String bindHost = "0.0.0.0";
    public int httpPort = 5231;
    public int wsPort = 5232;
    public String advertisedHost = null;
    public String databaseName = "kards_server.db";
    public String jwtSecret = "CometKards-is-a-help-much-kards-players-that-can't-find-gameuser-or-baned";
    public String jwtAlgorithm = "HS256";
    public String gameVersion = "Kards 1.52.25476.launcher";
    public String roomName = "KARDS Room";
    public String hostName = "Host";
    public String adminToken = "";
    public String preferredPlayerName = "";

    public ServerConfig copy() {
        ServerConfig next = new ServerConfig();
        next.bindHost = bindHost;
        next.httpPort = httpPort;
        next.wsPort = wsPort;
        next.advertisedHost = advertisedHost;
        next.databaseName = databaseName;
        next.jwtSecret = jwtSecret;
        next.jwtAlgorithm = jwtAlgorithm;
        next.gameVersion = gameVersion;
        next.roomName = roomName;
        next.hostName = hostName;
        next.adminToken = adminToken;
        next.preferredPlayerName = preferredPlayerName;
        return next;
    }
}
