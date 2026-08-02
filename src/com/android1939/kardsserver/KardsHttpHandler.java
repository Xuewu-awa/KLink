package com.android1939.kardsserver;

import com.android1939.kardsserver.db.KardsDatabase;
import com.android1939.kardsserver.http.HttpRequest;
import com.android1939.kardsserver.http.HttpResponse;
import com.android1939.kardsserver.http.SimpleHttpServer;
import com.android1939.kardsserver.model.DeckRecord;
import com.android1939.kardsserver.model.MatchState;
import com.android1939.kardsserver.model.UserRecord;
import com.android1939.kardsserver.util.ActionCipher;
import com.android1939.kardsserver.util.JwtUtil;
import com.android1939.kardsserver.util.TimeUtil;
import com.android1939.kardsserver.ws.SimpleWebSocketServer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public final class KardsHttpHandler implements SimpleHttpServer.Handler {
    private final ServerConfig config;
    private final KardsDatabase database;
    private final AssetStore assets;
    private final MatchManager matches;
    private final SimpleWebSocketServer webSockets;
    private final CardIdManager cardIds;
    private final VersionPakManager versionPak;
    private final ActionCipher actionCipher = new ActionCipher();
    private final Random random = new Random();
    private final Set<String> excluded = new HashSet<String>();
    private final Map<String, String> nicknamesByAddress = new HashMap<String, String>();

    public KardsHttpHandler(ServerConfig config, KardsDatabase database, AssetStore assets,
                            MatchManager matches, SimpleWebSocketServer webSockets,
                            CardIdManager cardIds, VersionPakManager versionPak) {
        this.config = config;
        this.database = database;
        this.assets = assets;
        this.matches = matches;
        this.webSockets = webSockets;
        this.cardIds = cardIds;
        this.versionPak = versionPak;
        excluded.add("/");
        excluded.add("/session");
        excluded.add("/.com/config");
        excluded.add("/announcement");
        excluded.add("/activity");
        excluded.add("/broadcast");
        excluded.add("/ban-user");
        excluded.add("/search-user");
        excluded.add("/search-match");
        excluded.add("/sendid");
        excluded.add("/record/data");
        excluded.add("/api/check_update");
        excluded.add("/api/check_update/pc");
        excluded.add("/api/topbar_config");
        excluded.add("/download_config");
        excluded.add("/clientfp");
        excluded.add("/version");
        excluded.add("/library");
    }

    @Override
    public HttpResponse handle(HttpRequest request) throws Exception {
        try {
            HttpResponse response = route(request);
            if (response != null) {
                response.headers.put("Access-Control-Allow-Origin", "*");
            }
            return response;
        } catch (Unauthorized e) {
            JSONObject error = new JSONObject();
            error.put("title", "401 Unauthorized");
            error.put("description", "Warning");
            return HttpResponse.json(401, error);
        } catch (NotFound e) {
            return HttpResponse.text(404, "Not Found");
        } catch (Exception e) {
            JSONObject error = new JSONObject();
            error.put("error", e.toString());
            return HttpResponse.json(500, error);
        }
    }

    private HttpResponse route(HttpRequest request) throws Exception {
        String path = request.path;
        String method = request.method;
        if ("GET".equals(method) && "/".equals(path)) return root(request);
        if ("GET".equals(method) && "/.com/config".equals(path)) return dotComConfig();
        if ("POST".equals(method) && "/session".equals(path)) return session(request);
        if ("GET".equals(method) && "/library".equals(path)) return HttpResponse.json(200, assets.library());
        if ("GET".equals(method) && "/announcement".equals(path)) return announcement();
        if ("GET".equals(method) && "/activity".equals(path)) return activity();
        if ("GET".equals(method) && "/api/check_update".equals(path)) return checkUpdate(request);
        if ("GET".equals(method) && "/api/check_update/pc".equals(path)) return checkUpdate(request);
        if ("GET".equals(method) && "/api/topbar_config".equals(path)) return topbar();
        if ("GET".equals(method) && "/download_config".equals(path)) return downloadConfig();
        if ("GET".equals(method) && "/clientfp".equals(path)) return clientFp();
        if ("GET".equals(method) && "/fp/".equals(path)) return frontPage();
        if ("GET".equals(method) && "/version".equals(path)) return version();
        if ("GET".equals(method) && "/record/data".equals(path)) return recordData();
        if ("POST".equals(method) && "/broadcast".equals(path)) return broadcast(request);
        if ("POST".equals(method) && "/sendid".equals(path)) return sendId(request);
        if ("POST".equals(method) && "/ban-user".equals(path)) return adminNotImplemented(request);
        if ("POST".equals(method) && "/search-user".equals(path)) return emptySearch(request);
        if ("POST".equals(method) && "/search-match".equals(path)) return emptySearch(request);
        if (path.startsWith("/launcher/room/")) return launcherRoom(request);
        if (path.startsWith("/admin/")) return admin(request);

        UserRecord user = authenticate(request);
        String[] parts = segments(path);
        if (parts.length == 3 && "players".equals(parts[0])
                && ("library".equals(parts[2]) || "librarynew".equals(parts[2])) && "GET".equals(method)) {
            return HttpResponse.json(200, assets.library());
        }
        if (parts.length == 3 && "players".equals(parts[0]) && "packs".equals(parts[2]) && "GET".equals(method)) {
            int playerId = parseId(parts[1]);
            requireSameUser(user, playerId);
            return packs();
        }
        if (parts.length == 3 && "players".equals(parts[0]) && "notifications".equals(parts[1]) && "GET".equals(method)) {
            int playerId = parseId(parts[2]);
            requireSameUser(user, playerId);
            return HttpResponse.json(200, new JSONObject());
        }
        if (parts.length == 2 && "items".equals(parts[0])) {
            int playerId = parseId(parts[1]);
            requireSameUser(user, playerId);
            if ("GET".equals(method)) return items(user);
            if ("POST".equals(method)) return updateItem(request, user);
        }
        if (parts.length == 2 && "players".equals(parts[0]) && "PUT".equals(method)) {
            int playerId = parseId(parts[1]);
            requireSameUser(user, playerId);
            return updatePlayer(request, user);
        }
        if (parts.length == 3 && "players".equals(parts[0]) && "heartbeat".equals(parts[2]) && "PUT".equals(method)) {
            int playerId = parseId(parts[1]);
            requireSameUser(user, playerId);
            return HttpResponse.json(200, new JSONObject());
        }
        if (parts.length == 3 && "players".equals(parts[0]) && "friends".equals(parts[2]) && ("GET".equals(method) || "POST".equals(method))) {
            int playerId = parseId(parts[1]);
            requireSameUser(user, playerId);
            return HttpResponse.json(200, new JSONObject().put("friends", new JSONArray()));
        }
        if (parts.length == 3 && "players".equals(parts[0]) && "decks".equals(parts[2])) {
            int playerId = parseId(parts[1]);
            requireSameUser(user, playerId);
            if ("POST".equals(method)) return createDeck(request, user);
            if ("PUT".equals(method)) return updateDeck(request);
        }
        if (parts.length == 4 && "players".equals(parts[0]) && "decks".equals(parts[2])) {
            int playerId = parseId(parts[1]);
            int deckId = parseId(parts[3]);
            requireSameUser(user, playerId);
            if ("PUT".equals(method)) return fillDeck(request, deckId);
            if ("DELETE".equals(method)) {
                database.deleteDeck(deckId);
                return HttpResponse.text(200, "OK");
            }
        }
        if (("POST".equals(method) || "DELETE".equals(method))
                && ("/lobbyplayers".equals(path) || "/singleplayerlobby".equals(path))) {
            return lobby(request, user, "DELETE".equals(method));
        }
        if (parts.length == 2 && "matches".equals(parts[0]) && "v2".equals(parts[1]) && "GET".equals(method)) {
            return matchesV2(request, user);
        }
        if (parts.length == 3 && "matches".equals(parts[0]) && "v2".equals(parts[1])) {
            int matchId = parseId(parts[2]);
            if ("GET".equals(method)) return matchStatus(user, matchId);
            if ("PUT".equals(method)) return endMatch(request, user, matchId);
        }
        if (parts.length == 4 && "matches".equals(parts[0]) && "v2".equals(parts[1])) {
            int matchId = parseId(parts[2]);
            if ("actions".equals(parts[3])) {
                if ("PUT".equals(method)) return pollActions(request, matchId);
                if ("POST".equals(method)) return postAction(request, user, matchId);
                if ("GET".equals(method)) return pollActions(request, matchId);
            }
            if ("post".equals(parts[3]) && "GET".equals(method)) return matchPost(user, matchId);
            if ("mulligan".equals(parts[3]) && "POST".equals(method)) return mulligan(request, user, matchId);
        }
        if (parts.length == 5 && "matches".equals(parts[0]) && "v2".equals(parts[1]) && "mulligan".equals(parts[3])) {
            int matchId = parseId(parts[2]);
            if ("GET".equals(method) && "left".equals(parts[4])) return mulliganSide(matchId, true);
            if ("GET".equals(method) && "right".equals(parts[4])) return mulliganSide(matchId, false);
        }
        if ("/matches/v2/reconnect".equals(path) && "GET".equals(method)) {
            return reconnect(request, user);
        }
        throw new NotFound();
    }

    private HttpResponse root(HttpRequest request) throws Exception {
        String base = baseUrl(request);
        JSONObject endpoints = new JSONObject();
        endpoints.put("draft", base + "/draft/");
        endpoints.put("email", base + "/email/set");
        endpoints.put("lobbyplayers", base + "/lobbyplayers");
        endpoints.put("matches", base + "/matches");
        endpoints.put("matches2", base + "/matches/v2/");
        endpoints.put("my_draft", JSONObject.NULL);
        endpoints.put("my_items", JSONObject.NULL);
        endpoints.put("my_player", JSONObject.NULL);
        endpoints.put("players", base + "/players");
        endpoints.put("purchase", base + "/store/v2/txn");
        endpoints.put("root", base);
        endpoints.put("session", base + "/session");
        endpoints.put("store", base + "/store/");
        endpoints.put("tourneys", base + "/tourney/");
        endpoints.put("transactions", base + "/store/txn");
        endpoints.put("view_offers", base + "/store/v2/");

        UserRecord user = optionalUser(request);
        JSONObject currentUser = new JSONObject();
        if (user != null) {
            endpoints.put("my_draft", base + "/draft/" + user.id);
            endpoints.put("my_items", base + "/items/" + user.id);
            endpoints.put("my_player", base + "/players/" + user.id);
            currentUser.put("client_id", user.id);
            currentUser.put("exp", user.id);
            currentUser.put("external_id", user.username);
            currentUser.put("iat", TimeUtil.nowSeconds());
            currentUser.put("identity_id", user.id);
            currentUser.put("iss", "cometkards");
            currentUser.put("jti", "");
            currentUser.put("language", "zh-Hans");
            currentUser.put("payment", "notavailable");
            currentUser.put("player_id", user.id);
            currentUser.put("provider", "device");
            currentUser.put("roles", new JSONArray());
            currentUser.put("tier", "LIVE");
            currentUser.put("user_id", user.id);
            currentUser.put("user_name", user.username);
        }

        JSONObject buildInfo = new JSONObject();
        buildInfo.put("build_timestamp", "2025-10-13T17:31:40Z");
        buildInfo.put("commit_hash", "local-java");
        buildInfo.put("version", config.gameVersion);

        JSONObject hostInfo = new JSONObject();
        hostInfo.put("container_name", "kards-backend-LIVE");
        hostInfo.put("docker_image", "android-local-java");
        hostInfo.put("host_address", hostOnly(request));
        hostInfo.put("host_name", "cometkards");
        hostInfo.put("instance_id", "android-local");

        JSONObject root = new JSONObject();
        root.put("build_info", buildInfo);
        root.put("current_user", currentUser);
        root.put("endpoints", endpoints);
        root.put("host_info", hostInfo);
        root.put("server_time", TimeUtil.nowIso());
        root.put("service_name", "kards-backend");
        root.put("tenant_name", "1939-kardslive");
        root.put("tier_name", "LIVE");
        return HttpResponse.json(200, root);
    }

    private HttpResponse dotComConfig() throws Exception {
        JSONObject json = new JSONObject();
        json.put("xserver_closed", "");
        json.put("xserver_closed_header", "");
        json.put("forgot_password_url", "https://www.kards.com/auth/recovery?lang={lang}");
        return HttpResponse.json(200, json);
    }

    private HttpResponse session(HttpRequest request) throws Exception {
        JSONObject body = request.jsonBody();
        String username = body.optString("username", "guest");
        String password = body.optString("password", "");
        UserRecord user = database.getOrCreateUser(username, password);
        String preferredName = preferredNameFor(request);
        if (preferredName.length() > 0 && !preferredName.equals(user.playerName)) {
            user = database.setPlayerName(user.id, preferredName);
        }
        String token = JwtUtil.create(config.jwtSecret, user.id, user.username, TimeUtil.nowSeconds() + 86400);
        database.updateUserJwt(user.id, token);
        user = database.findUserById(user.id);
        JSONObject response = playerSessionJson(request, user, token);
        return HttpResponse.json(200, response);
    }

    private HttpResponse launcherRoom(HttpRequest request) throws Exception {
        if ("GET".equals(request.method) && "/launcher/room/status".equals(request.path)) {
            return HttpResponse.json(200, launcherRoomStatus());
        }
        if ("GET".equals(request.method) && "/launcher/room/logs".equals(request.path)) {
            return HttpResponse.json(200, ServerLog.recent());
        }
        if ("GET".equals(request.method) && "/launcher/room/players".equals(request.path)) {
            return HttpResponse.json(200, launcherPlayers());
        }
        if ("POST".equals(request.method) && "/launcher/room/nickname".equals(request.path)) {
            JSONObject body = request.jsonBody();
            String nickname = cleanNickname(body.optString("nickname", ""));
            if (nickname.length() > 0) {
                synchronized (nicknamesByAddress) {
                    nicknamesByAddress.put(remoteKey(request), nickname);
                }
            }
            return HttpResponse.json(200, new JSONObject().put("ok", true).put("nickname", nickname));
        }
        if ("POST".equals(request.method) && "/launcher/room/kick".equals(request.path)) {
            JSONObject body = request.jsonBody();
            if (!isAdminRequest(request, body)) {
                return HttpResponse.json(403, new JSONObject().put("error", "forbidden"));
            }
            int playerId = body.optInt("player_id", -1);
            if (playerId <= 0) {
                return HttpResponse.json(400, new JSONObject().put("error", "missing player_id"));
            }
            boolean online = webSockets.kick(playerId);
            return HttpResponse.json(200, new JSONObject().put("ok", true).put("player_id", playerId).put("was_online", online));
        }
        throw new NotFound();
    }

    /**
     * 管理端点 /admin/*：本机地址直接放行，非本机需 admin_token。
     */
    private HttpResponse admin(HttpRequest request) throws Exception {
        try {
            return adminInner(request);
        } catch (IllegalArgumentException e) {
            return HttpResponse.json(400, new JSONObject().put("error", e.getMessage()));
        }
    }

    private HttpResponse adminInner(HttpRequest request) throws Exception {
        String path = request.path;
        String method = request.method;
        if ("GET".equals(method) && "/admin/library".equals(path)) {
            requireAdmin(request, null);
            return HttpResponse.json(200, cardIds.listCards(request.query.get("q"), 0));
        }
        if ("POST".equals(method) && "/admin/library".equals(path)) {
            JSONObject body = request.jsonBody();
            requireAdmin(request, body);
            int id = body.optInt("id");
            if (id <= 0) {
                throw new IllegalArgumentException("数字 ID（id）必须为正整数");
            }
            cardIds.addOrUpdateCard(body.optString("card_type"), id, body.optInt("count", 4));
            return HttpResponse.json(200, new JSONObject().put("ok", true));
        }
        if ("DELETE".equals(method) && "/admin/library".equals(path)) {
            requireAdmin(request, null);
            Set<Integer> ids = parseIdSet(request.query.get("id"));
            cardIds.deleteCards(ids);
            return HttpResponse.json(200, new JSONObject().put("ok", true).put("deleted", ids.size()));
        }
        if ("GET".equals(method) && "/admin/decks".equals(path)) {
            requireAdmin(request, null);
            return HttpResponse.json(200, cardIds.listDecks(request.query.get("q"), 0));
        }
        if ("POST".equals(method) && "/admin/decks".equals(path)) {
            JSONObject body = request.jsonBody();
            requireAdmin(request, body);
            cardIds.addOrUpdateDeck(body.optString("code"), body.optString("card"), body.optInt("ID"));
            return HttpResponse.json(200, new JSONObject().put("ok", true));
        }
        if ("DELETE".equals(method) && "/admin/decks".equals(path)) {
            requireAdmin(request, null);
            Set<String> codes = parseCodeSet(request.query.get("code"));
            cardIds.deleteDecks(codes);
            return HttpResponse.json(200, new JSONObject().put("ok", true).put("deleted", codes.size()));
        }
        if ("POST".equals(method) && "/admin/check".equals(path)) {
            requireAdmin(request, null);
            return HttpResponse.json(200, cardIds.checkConsistency());
        }
        if ("POST".equals(method) && "/admin/save".equals(path)) {
            requireAdmin(request, null);
            return HttpResponse.json(200, cardIds.saveAll());
        }
        if ("GET".equals(method) && "/admin/pak/status".equals(path)) {
            requireAdmin(request, null);
            return HttpResponse.json(200, versionPak.status());
        }
        if ("POST".equals(method) && "/admin/pak/apply".equals(path)) {
            JSONObject body = request.jsonBody();
            requireAdmin(request, body);
            boolean rewrite = body.optBoolean("rewrite", true);
            String version = body.has("version") ? body.optString("version") : null;
            return HttpResponse.json(200, versionPak.apply(version, rewrite));
        }
        throw new NotFound();
    }

    private void requireAdmin(HttpRequest request, JSONObject body) throws Unauthorized {
        if (isLocalAddress(request.remoteAddress)) {
            return;
        }
        if (isAdminRequest(request, body == null ? new JSONObject() : body)) {
            return;
        }
        throw new Unauthorized();
    }

    private static Set<Integer> parseIdSet(String raw) {
        Set<Integer> ids = new HashSet<Integer>();
        if (raw == null) {
            return ids;
        }
        for (String part : raw.split(",")) {
            part = part.trim();
            if (part.length() == 0) {
                continue;
            }
            try {
                ids.add(Integer.parseInt(part));
            } catch (Exception ignored) {
            }
        }
        return ids;
    }

    private static Set<String> parseCodeSet(String raw) {
        Set<String> codes = new HashSet<String>();
        if (raw == null) {
            return codes;
        }
        for (String part : raw.split(",")) {
            part = part.trim();
            if (part.length() > 0) {
                codes.add(part);
            }
        }
        return codes;
    }

    private JSONObject launcherRoomStatus() throws Exception {
        JSONObject json = new JSONObject();
        json.put("room_name", config.roomName);
        json.put("host_name", config.hostName);
        json.put("http_port", config.httpPort);
        json.put("ws_port", config.wsPort);
        json.put("online_players", webSockets.onlineCount());
        json.put("matches", matches.matchCount());
        json.put("players", launcherPlayers());
        json.put("logs", ServerLog.recent());
        return json;
    }

    private JSONArray launcherPlayers() throws Exception {
        JSONArray array = new JSONArray();
        List<UserRecord> users = database.listUsers();
        for (UserRecord user : users) {
            if (!user.isOnline && !matches.isKicked(user.id)) {
                continue;
            }
            JSONObject item = new JSONObject();
            item.put("player_id", user.id);
            item.put("player_name", user.playerName);
            item.put("player_tag", user.playerTag);
            item.put("username", user.username);
            item.put("online", user.isOnline);
            item.put("kicked", matches.isKicked(user.id));
            array.put(item);
        }
        return array;
    }

    private boolean isAdminRequest(HttpRequest request, JSONObject body) {
        String token = body.optString("admin_token", "");
        if (token.length() == 0) {
            token = request.header("x-kards-room-admin");
        }
        return config.adminToken != null && config.adminToken.length() > 0 && config.adminToken.equals(token);
    }

    private String preferredNameFor(HttpRequest request) {
        String localName = cleanNickname(config.preferredPlayerName);
        if (isLocalAddress(request.remoteAddress) && localName.length() > 0) {
            return localName;
        }
        synchronized (nicknamesByAddress) {
            return cleanNickname(nicknamesByAddress.get(remoteKey(request)));
        }
    }

    private String remoteKey(HttpRequest request) {
        String address = request.remoteAddress == null ? "" : request.remoteAddress.trim();
        return address.length() == 0 ? "unknown" : address;
    }

    private boolean isLocalAddress(String address) {
        return "127.0.0.1".equals(address) || "0:0:0:0:0:0:0:1".equals(address) || "::1".equals(address);
    }

    private String cleanNickname(String value) {
        String name = value == null ? "" : value.trim();
        if (name.length() > 24) {
            name = name.substring(0, 24);
        }
        return name.replaceAll("[\\r\\n\\t]", " ");
    }

    private JSONObject playerSessionJson(HttpRequest request, UserRecord user, String token) throws Exception {
        String base = baseUrl(request);
        JSONObject json = new JSONObject();
        json.put("client_id", user.id);
        json.put("user_id", user.id);
        json.put("player_id", user.id);
        json.put("jwt", token);
        json.put("jti", "1");
        json.put("player_name", user.playerName);
        json.put("player_tag", user.playerTag);
        json.put("server_time", compactServerTime());
        json.put("currency", "CNY");
        json.put("locale", "zh-hans");
        json.put("is_online", true);
        json.put("online_flag", true);
        json.put("is_officer", true);
        json.put("has_been_officer", true);
        json.put("stars", 120);
        json.put("gold", 78);
        json.put("diamonds", 91);
        json.put("dust", 0);
        json.put("draft_admissions", 1);
        json.put("claimable_crate_level", 0);
        json.put("email", JSONObject.NULL);
        json.put("email_reward_received", false);
        json.put("email_verified", false);
        json.put("extended_rewards", true);
        json.put("npc", false);
        putNationProgress(json);
        json.put("achievements_url", base + "/players/" + user.id + "/achievements");
        json.put("dailymissions_url", base + "/players/" + user.id + "/dailymissions");
        json.put("decks_url", base + "/players/" + user.id + "/decks");
        json.put("heartbeat_url", base + "/players/" + user.id + "/heartbeat");
        json.put("library_url", base + "/library");
        json.put("items_url", base + "/items/" + user.id);
        json.put("packs_url", base + "/players/" + user.id + "/packs");
        JSONObject decks = new JSONObject();
        decks.put("headers", decksArray(database.listDecks(user.id)));
        json.put("decks", decks);
        json.put("double_xp_end_date", "2026-07-03T12:13:36.889692Z");
        json.put("last_crate_claimed_date", "2026-02-24T03:12:20.011489Z");
        json.put("last_daily_mission_cancel", JSONObject.NULL);
        json.put("last_daily_mission_renewal", "2026-02-09T08:48:44.675532Z");
        json.put("last_logon_date", "2026-02-24T03:11:52.877909Z");
        json.put("linker_account", "");
        json.put("launch_messages", new JSONArray());
        json.put("tutorials_done", 0);
        json.put("tutorials_finished", tutorialsFinished());
        json.put("cards_blacklist", cardsBlacklist());
        json.put("new_cards", newCards());
        json.put("new_player_login_reward", new JSONObject()
                .put("day", 8)
                .put("reset", "0001-01-01 00:00:00")
                .put("seconds", 0));
        json.put("misc", new JSONObject()
                .put("createDate", "2026-02-09T08:48:18.482180Z")
                .put("featuredAchievements", new JSONArray()));
        json.put("rewards", new JSONObject()
                .put("packs", 0)
                .put("gold_max", 114)
                .put("gold_min", 514));
        json.put("season_end", "2027-01-01T00:00:00Z");
        json.put("season_id", 85);
        json.put("season_wins", 91);
        json.put("server_options", serverOptions(request));
        json.put("all_knockout_tourneys", new JSONArray());
        json.put("current_knockout_tourney", new JSONObject());
        json.put("current_mini_sit_n_go", currentMiniSitNGo());
        return json;
    }

    private HttpResponse updatePlayer(HttpRequest request, UserRecord user) throws Exception {
        JSONObject body = request.jsonBody();
        if ("set-name".equals(body.optString("action"))) {
            String value = body.optString("value", "").trim();
            if (value.length() == 0 || "<anon>".equals(value)) {
                return HttpResponse.json(400, new JSONObject().put("error", "name unavailable"));
            }
            UserRecord updated = database.setPlayerName(user.id, value);
            JSONObject json = new JSONObject();
            json.put("player_name", updated.playerName);
            json.put("player_tag", updated.playerTag);
            return HttpResponse.json(200, json);
        }
        return HttpResponse.text(200, "OK");
    }

    private HttpResponse items(UserRecord user) throws Exception {
        JSONObject json = assets.items();
        JSONArray equipped = new JSONArray();
        String[] factions = {"Germany", "Britain", "Soviet", "USA", "Japan"};
        String[] slots = {"emote_1", "emote_2", "emote_3", "emote_4", "emote_5", "emote_6", "emote_7", "emote_8"};
        for (String slot : slots) {
            for (String faction : factions) {
                putEquipped(equipped, user, slot, faction);
            }
        }
        for (String faction : factions) {
            putEquipped(equipped, user, "item_1", faction);
        }
        JSONObject avatar = new JSONObject();
        avatar.put("item_id", itemValue(user.equipment.get("avatar")));
        avatar.put("slot", "avatar");
        avatar.put("faction", "NotAvailable");
        equipped.put(avatar);
        json.put("equipped_items", equipped);
        return HttpResponse.json(200, json);
    }

    private HttpResponse updateItem(HttpRequest request, UserRecord user) throws Exception {
        JSONObject body = request.jsonBody();
        database.updateEquipment(user.id, body.optString("slot"), body.optString("faction"), body.optString("item_id"));
        return HttpResponse.text(200, "OK");
    }

    private HttpResponse createDeck(HttpRequest request, UserRecord user) throws Exception {
        JSONObject body = request.jsonBody();
        String mainFaction = normalizeFaction(body, "main_faction", "main_country", "main_nation");
        String allyFaction = body.optString("ally_faction");
        DeckRecord deck = database.createDeck(
                user.id,
                body.optString("name"),
                mainFaction,
                allyFaction,
                body.optString("deck_code"));
        return HttpResponse.json(200, deckJson(deck));
    }

    private HttpResponse updateDeck(HttpRequest request) throws Exception {
        JSONObject body = request.jsonBody();
        int deckId = body.optInt("id");
        String action = body.optString("action");
        if ("change_card_back".equals(action)) database.updateCardBack(deckId, body.optString("name"));
        if ("rename".equals(action)) database.renameDeck(deckId, body.optString("name"));
        if ("make_favorite".equals(action)) database.toggleFavorite(deckId);
        return HttpResponse.text(200, "ok");
    }

    private HttpResponse fillDeck(HttpRequest request, int deckId) throws Exception {
        JSONObject body = request.jsonBody();
        if ("fill".equals(body.optString("action"))) {
            String deckCode = body.optString("deck_code");
            if (!isPlayableDeckCode(deckCode)) {
                return HttpResponse.text(400, "invalid deck code");
            }
            database.updateDeckCode(deckId, deckCode);
        }
        return HttpResponse.text(200, "OK");
    }

    private HttpResponse lobby(HttpRequest request, UserRecord user, boolean delete) throws Exception {
        JSONObject body = request.jsonBody();
        int playerId = body.optInt("player_id");
        if (user == null || user.id != playerId) {
            return HttpResponse.text(403, "unauthorized");
        }
        if (delete) {
            boolean removed = matches.removeFromQueues(playerId);
            return HttpResponse.text(removed ? 200 : 404, removed ? "OK" : "player not queued");
        }
        int deckId = body.optInt("deck_id");
        DeckRecord deck = database.findDeckForUser(user.id, deckId);
        if (deck == null) {
            return HttpResponse.text(400, "deck not found");
        }
        if (!isPlayableDeckCode(deck.deckCode)) {
            return HttpResponse.text(400, "invalid deck code");
        }
        if (!webSockets.isOnline(playerId)) {
            return HttpResponse.text(400, "WebSocket not connected");
        }
        Object extra = body.opt("extra_data");
        String extraData = extra instanceof JSONObject && "training".equals(((JSONObject) extra).optString("match_type"))
                ? "training" : (extra == null ? "" : String.valueOf(extra));
        boolean matched = matches.addToQueue(playerId, deckId, extraData);
        return HttpResponse.text(matched || matches.isWaiting(playerId) ? 200 : 402, matched || matches.isWaiting(playerId) ? "OK" : "match failed");
    }

    private HttpResponse matchesV2(HttpRequest request, UserRecord user) throws Exception {
        MatchState match = matches.matchForPlayer(user.id);
        if (match == null) {
            return HttpResponse.text(200, "null");
        }
        return HttpResponse.json(200, matchAndStartingData(request, match));
    }

    private HttpResponse matchStatus(UserRecord user, int matchId) {
        MatchState match = matches.matchById(matchId);
        if (match == null) return HttpResponse.text(404, "missing");
        if (user.id == match.playerLeft) match.levelLoadedLeft = 1;
        if (user.id == match.playerRight) match.levelLoadedRight = 1;
        if (match.levelLoadedLeft == 1 && match.levelLoadedRight == 1) match.status = "running";
        return HttpResponse.text(200, match.status);
    }

    private HttpResponse endMatch(HttpRequest request, UserRecord user, int matchId) throws Exception {
        MatchState match = matches.matchById(matchId);
        if (match == null) return HttpResponse.text(404, "missing");
        JSONObject body = request.jsonBody();
        if (body.has("a")) {
            ActionCipher.Decoded decoded = actionCipher.decode(body.getString("a"));
            if (match.actionSessionId == 0) {
                match.actionSessionId = decoded.actionId;
            }
            JSONObject payload = decoded.payload;
            JSONObject value = payload.optJSONObject("value");
            if ("end-match".equals(payload.optString("action")) && value != null) {
                match.winnerSide = value.optString("winner_side");
                match.winnerId = value.optInt("winner_id");
                match.currentTurn = 1;
                JSONObject action = new JSONObject();
                action.put("action_id", match.currentActionId + 1);
                action.put("action_type", "ActionEndMatch");
                action.put("player_id", user.id);
                action.put("action_data", new JSONObject()
                        .put("winner_id", match.winnerId)
                        .put("reason", value.optString("result"))
                        .put("winner_side", match.winnerSide));
                action.put("sub_actions", new JSONArray());
                action.put("turn_number", match.currentTurn);
                int session = match.actionSessionId == 0 ? decoded.actionId : match.actionSessionId;
                matches.appendAction(match, actionCipher.encode(session, action));
                match.status = "finished";
            }
        }
        return HttpResponse.text(200, "OK");
    }

    private HttpResponse pollActions(HttpRequest request, int matchId) throws Exception {
        MatchState match = matches.matchById(matchId);
        if (match == null) return HttpResponse.text(404, "missing");
        matches.tickBot(match);
        int minActionId = request.jsonBody().optInt("min_action_id", 1);
        JSONObject json = new JSONObject();
        JSONObject matchJson = new JSONObject();
        matchJson.put("player_status_left", match.playerStatusLeft);
        matchJson.put("player_status_right", match.playerStatusRight);
        matchJson.put("status", match.status);
        json.put("match", matchJson);
        int opponentId = request.jsonBody().optInt("opponent_id", 0);
        boolean opponentPolling = true;
        if (opponentId == match.playerLeft) opponentPolling = match.leftOnline;
        if (opponentId == match.playerRight) opponentPolling = match.rightOnline;
        json.put("opponent_polling", opponentPolling);
        JSONArray actions = matches.actionsSince(match, Math.max(1, minActionId));
        if (actions.length() > 0) json.put("actions", actions);
        return HttpResponse.json(200, json);
    }

    private HttpResponse tickAndPollActions(HttpRequest request, int matchId) throws Exception {
        MatchState match = matches.matchById(matchId);
        if (match == null) return HttpResponse.text(404, "missing");
        matches.tickBot(match);
        return pollActions(request, matchId);
    }

    private HttpResponse postAction(HttpRequest request, UserRecord user, int matchId) throws Exception {
        MatchState match = matches.matchById(matchId);
        if (match == null) return HttpResponse.text(404, "missing");
        JSONObject body = request.jsonBody();
        if (body.has("a")) {
            ActionCipher.Decoded decoded = actionCipher.decode(body.getString("a"));
            String actionType = decoded.payload.optString("action_type");
            if ("XStartOfGame".equals(actionType) || match.actionSessionId == 0) {
                match.actionSessionId = decoded.actionId;
            }
            if ("XActionStartOfTurn".equals(actionType)) {
                match.actionPlayerId = user.id;
            }
            JSONObject payload = actionPayload(match, user.id, decoded.payload);
            matches.appendAction(match, actionCipher.encode(match.actionSessionId, payload));
        }
        matches.tickBot(match);
        return HttpResponse.text(201, "OK");
    }

    private JSONObject actionPayload(MatchState match, int playerId, JSONObject payload) throws Exception {
        String actionType = payload.optString("action_type");
        if ("XActionEndOfTurn".equals(actionType)) {
            match.currentTurn++;
        }
        JSONObject action = new JSONObject();
        action.put("action_id", match.currentActionId + 1);
        action.put("action_type", actionType);
        action.put("player_id", playerId);
        action.put("action_data", payload.opt("action_data"));
        action.put("sub_actions", new JSONArray());
        action.put("turn_number", match.currentTurn);
        return action;
    }

    private HttpResponse matchPost(UserRecord user, int matchId) throws Exception {
        MatchState match = matches.matchById(matchId);
        if (match == null) return HttpResponse.text(404, "");
        if (match.actions.length() == 0) return HttpResponse.text(404, "");
        String encrypted = match.actionsData.get(match.currentActionId);
        if (encrypted == null) return HttpResponse.text(404, "");
        JSONObject lastAction = actionCipher.decode(encrypted).payload;
        if (!"ActionEndMatch".equals(lastAction.optString("action_type"))) {
            return HttpResponse.text(204, "");
        }
        boolean left = user.id == match.playerLeft;
        boolean right = user.id == match.playerRight;
        if (!left && !right) {
            return HttpResponse.text(403, "");
        }
        String winnerSide = lastAction.optJSONObject("action_data") == null
                ? match.winnerSide : lastAction.optJSONObject("action_data").optString("winner_side", match.winnerSide);
        boolean winner = (left && "left".equals(winnerSide)) || (right && "right".equals(winnerSide));
        JSONObject deckData = left ? match.leftDeckData : match.rightDeckData;
        JSONObject json = new JSONObject();
        json.put("faction", deckData.optString("main_country"));
        json.put("is_double_xp", true);
        json.put("new_level", 500);
        json.put("new_xp", 0);
        json.put("old_level", 500);
        json.put("old_xp", 0);
        json.put("winner", winner);
        match.endConfirmCount++;
        if (match.endConfirmCount >= 2) {
            matches.cleanupMatch(matchId);
        }
        return HttpResponse.json(200, json);
    }

    private JSONObject matchJson(HttpRequest request, MatchState match) throws Exception {
        String base = baseUrl(request);
        JSONObject matchJson = new JSONObject();
        int actionPlayer = match.actionPlayerId == 0 ? match.playerLeft : match.actionPlayerId;
        matchJson.put("action_player_id", actionPlayer);
        matchJson.put("action_side", actionPlayer == match.playerRight ? "right" : "left");
        matchJson.put("actions", match.actions);
        matchJson.put("actions_url", base + "/matches/v2/" + match.matchId + "/actions");
        matchJson.put("current_action_id", match.currentActionId);
        matchJson.put("current_turn", match.currentTurn);
        matchJson.put("deck_id_left", match.deckIdLeft);
        matchJson.put("deck_id_right", match.deckIdRight);
        matchJson.put("left_is_online", match.leftOnline);
        matchJson.put("right_is_online", match.rightOnline);
        matchJson.put("match_id", match.matchId);
        matchJson.put("match_type", match.matchType);
        matchJson.put("match_url", base + "/matches/v2/" + match.matchId);
        matchJson.put("modify_date", TimeUtil.nowIso());
        matchJson.put("notifications", match.notifications);
        matchJson.put("player_id_left", match.playerLeft);
        matchJson.put("player_id_right", match.playerRight);
        matchJson.put("player_status_left", match.playerStatusLeft);
        matchJson.put("player_status_right", match.playerStatusRight);
        matchJson.put("start_side", "left");
        matchJson.put("status", match.status);
        matchJson.put("winner_id", match.winnerId);
        matchJson.put("winner_side", match.winnerSide);
        return matchJson;
    }

    private JSONObject startingData(MatchState match) throws Exception {
        UserRecord left = database.findUserById(match.playerLeft);
        UserRecord right = database.findUserById(match.playerRight);
        DeckRecord leftDeck = database.findDeckById(match.deckIdLeft);
        DeckRecord rightDeck = database.findDeckById(match.deckIdRight);
        JSONObject starting = new JSONObject();
        starting.put("ally_faction_left", match.leftDeckData.optString("ally_country"));
        starting.put("ally_faction_right", match.rightDeckData.optString("ally_country"));
        starting.put("card_back_left", leftDeck == null ? "" : leftDeck.cardBack);
        starting.put("card_back_right", rightDeck == null ? "" : rightDeck.cardBack);
        starting.put("starting_hand_left", match.leftHandCards);
        starting.put("starting_hand_right", match.rightHandCards);
        starting.put("deck_left", match.leftDeckCards);
        starting.put("deck_right", match.rightDeckCards);
        starting.put("equipment_left", equipmentList(left, match.leftDeckData.optString("main_country")));
        starting.put("equipment_right", equipmentList(right, match.rightDeckData.optString("main_country")));
        starting.put("is_ai_match", false);
        starting.put("left_player_name", left == null ? "Player" : left.playerName);
        starting.put("right_player_name", right == null ? "Player" : right.playerName);
        starting.put("left_player_tag", left == null ? 0 : left.playerTag);
        starting.put("right_player_tag", right == null ? 0 : right.playerTag);
        starting.put("left_player_officer", true);
        starting.put("right_player_officer", true);
        starting.put("location_card_left", match.leftCardsData.length() > 0 ? match.leftCardsData.get(0) : new JSONObject());
        starting.put("location_card_right", match.rightCardsData.length() > 0 ? match.rightCardsData.get(0) : new JSONObject());
        starting.put("player_id_left", match.playerLeft);
        starting.put("player_id_right", match.playerRight);
        starting.put("player_stars_left", 120);
        starting.put("player_stars_right", 120);
        return starting;
    }

    private JSONObject matchAndStartingData(HttpRequest request, MatchState match) throws Exception {
        JSONObject outer = new JSONObject();
        outer.put("local_subactions", true);
        outer.put("match_and_starting_data", new JSONObject()
                .put("match", matchJson(request, match))
                .put("starting_data", startingData(match)));
        outer.put("action_player_id", match.playerRight);
        outer.put("action_side", "right");
        return outer;
    }

    private JSONArray equipmentList(UserRecord user, String faction) {
        JSONArray items = new JSONArray();
        if (user == null) return items;
        String[] columns = {
                "item_" + faction,
                "emote_Start_" + faction, "emote_End_" + faction, "emote_Good_" + faction, "emote_Bad_" + faction,
                "emote_Cheer_" + faction, "emote_Taunt_" + faction, "emote_Poke_" + faction, "emote_Proclaim_" + faction,
                "avatar"
        };
        for (String column : columns) {
            items.put(user.equipment.get(column));
        }
        return items;
    }

    private HttpResponse mulligan(HttpRequest request, UserRecord user, int matchId) throws Exception {
        MatchState match = matches.matchById(matchId);
        if (match == null) return HttpResponse.text(404, "missing");
        JSONObject body = request.jsonBody();
        JSONArray discardedIds = body.optJSONArray("discarded_card_ids");
        if (discardedIds == null) discardedIds = new JSONArray();
        boolean left = user.id == match.playerLeft;
        JSONArray hand = left ? match.leftHandCards : match.rightHandCards;
        JSONArray deck = left ? match.leftDeckCards : match.rightDeckCards;
        JSONArray discarded = new JSONArray();
        JSONArray replacements = new JSONArray();
        for (int i = 0; i < hand.length(); i++) {
            JSONObject card = hand.optJSONObject(i);
            if (card == null || !containsInt(discardedIds, card.optInt("card_id"))) {
                continue;
            }
            discarded.put(new JSONObject(card.toString()));
            if (deck.length() > 0) {
                int deckIndex = random.nextInt(deck.length());
                JSONObject replacement = deck.optJSONObject(deckIndex);
                if (replacement != null) {
                    replacement = new JSONObject(replacement.toString());
                    JSONObject oldCard = new JSONObject(card.toString());
                    int oldLocationNumber = card.optInt("location_number", i);
                    int replacementLocationNumber = replacement.optInt("location_number", deckIndex);
                    replacement.put("location", left ? "hand_left" : "hand_right");
                    replacement.put("location_number", oldLocationNumber);
                    hand.put(i, replacement);
                    replacements.put(replacement);
                    oldCard.put("location", left ? "deck_left" : "deck_right");
                    oldCard.put("location_number", replacementLocationNumber);
                    deck.put(deckIndex, oldCard);
                }
            }
        }
        if (left) {
            match.playerStatusLeft = "mulligan_done";
            match.leftDiscardedCards = discarded;
            match.leftReplacementCards = replacements;
        } else {
            match.playerStatusRight = "mulligan_done";
            match.rightDiscardedCards = discarded;
            match.rightReplacementCards = replacements;
        }
        return HttpResponse.json(200, new JSONObject()
                .put("deck", deck)
                .put("replacement_cards", replacements));
    }

    private HttpResponse mulliganSide(int matchId, boolean left) throws Exception {
        MatchState match = matches.matchById(matchId);
        if (match == null) return HttpResponse.text(404, "missing");
        return HttpResponse.json(200, new JSONObject()
                .put("deck", left ? match.leftDeckCards : match.rightDeckCards)
                .put("replacement_cards", left ? match.leftReplacementCards : match.rightReplacementCards));
    }

    private HttpResponse reconnect(HttpRequest request, UserRecord user) throws Exception {
        MatchState match = matches.matchForPlayer(user.id);
        if (match == null) {
            return HttpResponse.json(200, new JSONObject());
        }
        if (user.id == match.playerLeft) match.leftOnline = true;
        if (user.id == match.playerRight) match.rightOnline = true;
        JSONArray actions = new JSONArray();
        for (int i = 0; i < match.actions.length(); i++) {
            String data = match.actionsData.get(match.actions.optInt(i));
            if (data != null) actions.put(data);
        }
        JSONObject json = new JSONObject();
        json.put("actions", actions);
        json.put("local_subactions", true);
        json.put("match", matchJson(request, match));
        json.put("mulligan_left", new JSONObject()
                .put("deck", match.leftDeckCards)
                .put("discarded_cards", match.leftDiscardedCards)
                .put("replacement_cards", match.leftReplacementCards));
        json.put("mulligan_right", new JSONObject()
                .put("deck", match.rightDeckCards)
                .put("discarded_cards", match.rightDiscardedCards)
                .put("replacement_cards", match.rightReplacementCards));
        json.put("same_turn", true);
        json.put("starting_data", startingData(match));
        json.put("time_since_start_of_turn", 60);
        json.put("unranked", false);
        json.put("waiting_for_sit_n_go_match", false);
        return HttpResponse.json(200, json);
    }

    private boolean containsInt(JSONArray array, int value) {
        for (int i = 0; i < array.length(); i++) {
            if (array.optInt(i, Integer.MIN_VALUE) == value) return true;
        }
        return false;
    }

    private void putEquipped(JSONArray array, UserRecord user, String slot, String faction) throws Exception {
        String column = KardsDatabase.equipmentColumn(slot, faction);
        JSONObject item = new JSONObject();
        item.put("item_id", itemValue(column == null ? null : user.equipment.get(column)));
        item.put("slot", slot);
        item.put("faction", faction);
        array.put(item);
    }

    private Object itemValue(String value) {
        return value == null || value.length() == 0 ? JSONObject.NULL : value;
    }

    private HttpResponse announcement() throws Exception {
        JSONObject item = new JSONObject();
        item.put("title", "KARDS Local Server");
        item.put("content", "Local Java backend is running.");
        item.put("date", "2026-05-24");
        item.put("type", "info");
        return HttpResponse.json(200, new JSONObject()
                .put("code", 200)
                .put("message", "success")
                .put("data", new JSONArray().put(item)));
    }

    private HttpResponse activity() throws Exception {
        return HttpResponse.json(200, new JSONObject()
                .put("code", 200)
                .put("message", "success")
                .put("data", new JSONArray()));
    }

    private HttpResponse checkUpdate(HttpRequest request) throws Exception {
        JSONObject json = new JSONObject();
        json.put("code", 200);
        json.put("message", "success");
        json.put("data", new JSONObject().put("mods", new JSONArray()));
        return HttpResponse.json(200, json);
    }

    private HttpResponse topbar() throws Exception {
        JSONObject data = new JSONObject();
        data.put("title", "");
        data.put("subtitle", "");
        data.put("link", "");
        data.put("link_type", "web");
        data.put("fallback_url", JSONObject.NULL);
        data.put("package_name", JSONObject.NULL);
        return HttpResponse.json(200, new JSONObject()
                .put("code", 200)
                .put("data", data));
    }

    private HttpResponse downloadConfig() throws Exception {
        JSONObject data = new JSONObject();
        data.put("title", "");
        data.put("message", "");
        data.put("links", new JSONArray());
        return HttpResponse.json(200, new JSONObject()
                .put("code", 200)
                .put("data", data));
    }

    private HttpResponse clientFp() throws Exception {
        JSONObject above = new JSONObject();
        above.put("title", "KARDS Local Server");
        above.put("text", "Local Java backend");
        above.put("link", "");
        JSONObject popup = new JSONObject();
        popup.put("num", 0);
        popup.put("title", "");
        popup.put("text", "");
        popup.put("link", "");
        return HttpResponse.json(200, new JSONObject()
                .put("above_left_message", above)
                .put("activites", new JSONArray())
                .put("pop_up", popup));
    }

    private HttpResponse frontPage() throws Exception {
        return HttpResponse.json(200, new JSONObject()
                .put("changed", true)
                .put("elements", new JSONArray())
                .put("message", "OK")
                .put("status_code", 200)
                .put("targeted", new JSONArray()));
    }

    private HttpResponse version() throws Exception {
        JSONObject json = new JSONObject();
        json.put("pak_version", "1.52.25476");
        json.put("game_version", "1.52.25476");
        json.put("pak_md5", "05e128fea79e2a85b387b430bdbfec6e");
        json.put("game_updata", "");
        return HttpResponse.json(200, json);
    }

    private HttpResponse packs() throws Exception {
        JSONArray packs = new JSONArray();
        for (int i = 0; i < 40; i++) {
            packs.put(new JSONObject().put("card_set", "5|Core").put("id", 0));
            packs.put(new JSONObject().put("card_set", "7|Core").put("id", 1));
        }
        return HttpResponse.json(200, packs);
    }

    private HttpResponse recordData() throws Exception {
        JSONObject stats = new JSONObject();
        stats.put("online_players", webSockets.onlineCount());
        stats.put("waiting_ranked", 0);
        stats.put("waiting_casual", 0);
        stats.put("waiting_total", 0);
        stats.put("wait_code_players", 0);
        stats.put("playing_players", 0);
        stats.put("playing_decks", 0);
        stats.put("matches", matches.matchCount());
        stats.put("match_id", 0);
        stats.put("status", webSockets.isRunning());
        stats.put("battle_codes", 0);
        JSONObject queues = new JSONObject();
        queues.put("ranked", new JSONArray());
        queues.put("casual", new JSONArray());
        queues.put("ranked_count", 0);
        queues.put("casual_count", 0);
        queues.put("total", 0);
        JSONObject json = new JSONObject();
        json.put("statistics", stats);
        json.put("queues", queues);
        json.put("battle_code_queues", new JSONObject());
        json.put("battle_code_count", 0);
        json.put("player_mappings", new JSONObject()
                .put("playing", new JSONObject())
                .put("decks", new JSONObject())
                .put("codes", new JSONObject()));
        json.put("matches_detail", new JSONObject());
        json.put("matches_count", matches.matchCount());
        json.put("online_players_detail", new JSONObject());
        json.put("online_count", webSockets.onlineCount());
        json.put("system", new JSONObject()
                .put("message", JSONObject.NULL)
                .put("timestamp", TimeUtil.nowIso()));
        return HttpResponse.json(200, json);
    }

    private HttpResponse broadcast(HttpRequest request) throws Exception {
        JSONObject body = request.jsonBody();
        if (!"CometServer1234567890@ABC".equals(body.optString("password"))) {
            return HttpResponse.json(401, new JSONObject().put("message", "unauthorized"));
        }
        JSONObject message = body.optJSONObject("message");
        int sent = webSockets.broadcast(message == null ? new JSONObject() : message);
        return HttpResponse.json(200, new JSONObject().put("status", "OK").put("sent", sent));
    }

    private HttpResponse sendId(HttpRequest request) throws Exception {
        JSONObject body = request.jsonBody();
        if (!"CometServer1234567890@ABC".equals(body.optString("password"))) {
            return HttpResponse.json(401, new JSONObject().put("message", "unauthorized"));
        }
        JSONObject message = body.optJSONObject("message");
        boolean sent = webSockets.sendToId(body.optInt("id"), message == null ? new JSONObject() : message);
        return HttpResponse.json(200, new JSONObject().put("status", sent ? "OK" : "offline"));
    }

    private HttpResponse adminNotImplemented(HttpRequest request) throws Exception {
        JSONObject body = request.jsonBody();
        if (!"CometServer123@BanUserID".equals(body.optString("password"))) {
            return HttpResponse.json(401, new JSONObject().put("message", "unauthorized"));
        }
        return HttpResponse.json(501, new JSONObject()
                .put("error", "not_implemented")
                .put("message", "Android v1 does not implement ban persistence yet"));
    }

    private HttpResponse emptySearch(HttpRequest request) throws Exception {
        JSONObject body = request.jsonBody();
        if (!"CometServer123@BanUserID".equals(body.optString("password"))) {
            return HttpResponse.json(401, new JSONObject().put("message", "unauthorized"));
        }
        int page = Math.max(1, body.optInt("page", 1));
        int limit = Math.max(1, body.optInt("limit", 50));
        return HttpResponse.json(200, new JSONObject()
                .put("status", "success")
                .put("data", new JSONArray())
                .put("page", page)
                .put("limit", limit)
                .put("total_count", 0)
                .put("total_pages", 0));
    }

    private UserRecord authenticate(HttpRequest request) throws Exception {
        if (excluded.contains(request.path) || request.path.startsWith("/static/")) {
            return null;
        }
        String token = normalizeAuthToken(request.header("authorization"));
        if (token == null) {
            throw new Unauthorized();
        }
        JSONObject payload = JwtUtil.verify(config.jwtSecret, token);
        int userId = payload.optInt("user_id");
        UserRecord user = database.findUserById(userId);
        if (user == null || !tokenMatchesUserSession(token, payload, user)) {
            throw new Unauthorized();
        }
        return user;
    }

    private UserRecord optionalUser(HttpRequest request) {
        try {
            String token = normalizeAuthToken(request.header("authorization"));
            if (token == null) {
                return null;
            }
            UserRecord user = database.findUserByJwt(token);
            if (user != null) {
                return user;
            }
            JSONObject payload = JwtUtil.verify(config.jwtSecret, token);
            user = database.findUserById(payload.optInt("user_id"));
            return user != null && tokenMatchesUserSession(token, payload, user) ? user : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean tokenMatchesUserSession(String token, JSONObject payload, UserRecord user) {
        try {
            if (user.playerJwt == null || user.playerJwt.length() == 0) {
                return false;
            }
            if (token.equals(user.playerJwt)) {
                return true;
            }
            JSONObject serverPayload = JwtUtil.verify(config.jwtSecret, user.playerJwt);
            long clientExp = payload.optLong("exp", 0);
            long serverExp = serverPayload.optLong("exp", 0);
            return clientExp > 0 && serverExp > 0 && Math.abs(clientExp - serverExp) < 86400;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String normalizeAuthToken(String value) {
        if (value == null) {
            return null;
        }
        String token = value.trim();
        if (token.regionMatches(true, 0, "JWT ", 0, 4)) {
            token = token.substring(4).trim();
        } else if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = token.substring(7).trim();
        }
        return token.length() == 0 ? null : token;
    }

    private void requireSameUser(UserRecord user, int playerId) {
        if (user == null || user.id != playerId) {
            throw new Unauthorized();
        }
    }

    private JSONArray decksArray(List<DeckRecord> decks) throws Exception {
        JSONArray array = new JSONArray();
        for (DeckRecord deck : decks) {
            array.put(deckJson(deck));
        }
        return array;
    }

    private JSONObject deckJson(DeckRecord deck) throws Exception {
        String mainFaction = deck.mainFaction;
        String allyFaction = deck.allyFaction;
        // 数据库阵营为空时从卡组代码恢复
        if ((mainFaction == null || mainFaction.isEmpty()) && deck.deckCode != null && deck.deckCode.startsWith("%%") && deck.deckCode.length() >= 4) {
            String country = deck.deckCode.substring(2, 4);
            mainFaction = countryName(country.substring(0, 1));
            allyFaction = countryName(country.substring(1, 2));
        }
        JSONObject json = new JSONObject();
        json.put("name", deck.name);
        json.put("main_faction", mainFaction);
        json.put("main_country", mainFaction);
        json.put("main_nation", mainFaction);
        json.put("ally_faction", allyFaction);
        json.put("card_back", deck.cardBack);
        json.put("deck_code", deck.deckCode);
        json.put("favorite", deck.favorite);
        json.put("id", deck.id);
        json.put("player_id", deck.userId);
        json.put("last_played", deck.lastPlayed);
        json.put("create_date", deck.createDate);
        json.put("modify_date", deck.modifyDate);
        return json;
    }

    private void putNationProgress(JSONObject json) throws Exception {
        String[] nations = {"britain", "germany", "japan", "soviet", "usa"};
        for (String nation : nations) {
            json.put(nation + "_level", 500);
            json.put(nation + "_level_claimed", 500);
            json.put(nation + "_xp", 0);
        }
    }

    private JSONArray tutorialsFinished() {
        JSONArray tutorials = new JSONArray();
        String[] values = {
                "unlocking_germany_1", "unlocking_germany_2", "unlocking_germany_0",
                "germany_cards_rewarded", "unlocking_usa_8", "recruit_missions_done",
                "draft_1", "draft_ally", "draft_kredits", "unlocking_japan_0",
                "japan_cards_rewarded", "unlocking_soviet_0", "soviet_cards_rewarded",
                "unlocking_usa_0", "usa_cards_rewarded", "unlocking_britain_0",
                "britain_cards_rewarded"
        };
        for (String value : values) tutorials.put(value);
        return tutorials;
    }

    private JSONArray newCards() {
        return stringArray(new String[]{
                "card_unit_panzer_i_dak",
                "card_unit_fw189",
                "card_event_dive_bombing",
                "card_unit_welsh_guards",
                "card_unit_ms_460",
                "card_event_reich_defenders",
                "card_unit_panzer_iii_h",
                "card_event_u_375",
                "card_event_careless_talk",
                "card_unit_panzergrenadier",
                "card_unit_fw_190",
                "card_unit_kv_1",
                "card_unit_42nd_rifles",
                "card_event_the_hammer",
                "card_unit_89th_infantry",
                "card_event_sturmovik",
                "card_unit_43_infantry_regiment"
        });
    }

    private JSONArray cardsBlacklist() throws Exception {
        return new JSONArray().put(new JSONObject()
                .put("card_type", "card_unit_8th_cavalry_regiment")
                .put("end_date", "2026-03-08T11:00:00"));
    }

    private JSONObject currentMiniSitNGo() throws Exception {
        JSONObject rules = new JSONObject();
        rules.put("reward", new JSONObject().put("type", "random_pack"));
        rules.put("name", "Skirmish #45");
        rules.put("hq_starting_defense", 90);
        JSONObject json = new JSONObject();
        json.put("end_date", "2026-04-29T18:00:00.000000Z");
        json.put("hasWon", false);
        json.put("id", 254937);
        json.put("name", "Skirmish #45");
        json.put("rules_json_str", rules.toString());
        json.put("start_date", "2026-03-27T12:00:00.000000Z");
        return json;
    }

    private String serverOptions(HttpRequest request) {
        try {
            JSONObject options = new JSONObject();
            options.put("christmas_music", 0);
            options.put("nui_mobile", 1);
            options.put("beta_expiry_date", "2023-10-01 00:00:00");
            JSONObject scalability = new JSONObject();
            JSONObject console = new JSONObject().put("console_commands", stringArray(new String[]{"r.Screenpercentage 100"}));
            scalability.put("Android_Low", new JSONObject(console.toString()));
            scalability.put("Android_Mid", new JSONObject(console.toString()));
            scalability.put("Android_High", new JSONObject(console.toString()));
            options.put("scalability_override", scalability);
            options.put("appscale_desktop_default", 1.0);
            options.put("appscale_desktop_max", 1.4);
            options.put("appscale_mobile_default", 1.4);
            options.put("appscale_mobile_max", 1.4);
            options.put("appscale_mobile_min", 1.0);
            options.put("appscale_tablet_min", 1.0);
            options.put("battle_wait_time", 6000);
            options.put("brothers_in_arms_date", "2023.06.18-09.30.00");
            options.put("covert_ops_date", "2024.06.11-11.00.00");
            options.put("naval_warfare_date", "2025.05.22-12.00.00");
            options.put("first_purchase_bonus_date", "2025.07.24-11.10.00");
            options.put("reconnect", 1);
            options.put("logger_disabled", 0);
            options.put("new_rewards", 1);
            options.put("most_popular_products", "304;238;318;319;320;321;322;324;11;270;1;52;45;18;56;72;7;149;70;143;9;53;57;75;151;228;153;79");
            options.put("winter_war_date", "2023.11.29-09.00.00");
            options.put("websocketurl", webSocketUrl(request));
            options.put("homefront_date", "2025.11.27-09.00.00");
            options.put("show_full_image", true);
            options.put("new_effect_bar", 1);
            options.put("new_effect_bar_pc", 1);
            options.put("new_effect_icons", 1);
            options.put("feature_socketerror_popup_enabled", 1);
            options.put("versions", stringArray(new String[]{"Kards 1.47", "Kards 1.49", "Kards 1.50", "Kards 1.52", "Kards 1.52.25476.launcher", "Kards 1.53", "Kards 1.54", "Kards 1.54.26471.APK", "Kards 1.56"}));
            JSONArray locked = new JSONArray();
            locked.put(new JSONObject()
                    .put("cards", stringArray(new String[]{
                            "card_unit_whirlwind", "card_event_pound", "card_unit_tiger_moth",
                            "card_event_harass", "card_unit_salamander", "card_unit_henschel_he_129",
                            "card_unit_ilyushin_10", "card_unit_p_39_airacobra", "card_event_out_with_the_old",
                            "card_unit_tigercat", "card_unit_seahawk", "card_event_screening_force",
                            "card_unit_n1k1_kyofu", "card_event_flight_to_oblivion", "card_unit_kyushu_j7w3",
                            "card_event_sally", "card_event_air_strips", "card_event_pilot_escape",
                            "card_unit_fokker_finland"
                    }))
                    .put("unlock_date", "2025-09-23T12:10:00"));
            options.put("locked_cards", locked);
            options.put("reserve_changes", new JSONArray());
            options.put("draft_card_limits", new JSONObject()
                    .put("blacklist", stringArray(new String[]{
                            "card_event_tactical_withdrawal", "card_event_hms_spectre", "card_event_lure",
                            "card_event_naval_power", "card_event_fortification", "card_event_for_the_king",
                            "card_event_overrun", "card_event_creeping_barrage", "card_unit_qf_40mm_mk_iii",
                            "card_unit_no43_commando", "card_event_bpf", "card_event_hms_formidable"
                    }))
                    .put("whitelist", stringArray(new String[]{
                            "card_unit_baluch_regiment", "card_unit_the_glamour_boys",
                            "card_unit_east_surray_regiment", "card_unit_3rd_canadian_division",
                            "card_unit_spitfire_v", "card_unit_rnzaf_kittyhawk"
                    })));
            options.put("give_guest_name", 0);
            options.put("anzac", 1);
            options.put("oceania_storm_date", "2026.06.11-08.00.00");
            return options.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private JSONArray stringArray(String[] values) {
        JSONArray array = new JSONArray();
        for (String value : values) {
            array.put(value);
        }
        return array;
    }

    private String compactServerTime() {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy.MM.dd-HH.mm.ss", java.util.Locale.US);
        format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return format.format(new java.util.Date());
    }

    private String baseUrl(HttpRequest request) {
        String host = config.advertisedHost;
        if (host == null || host.trim().length() == 0) {
            host = request.header("host");
        }
        if (host == null || host.trim().length() == 0) {
            host = "127.0.0.1:" + config.httpPort;
        }
        return "http://" + host;
    }

    private String hostOnly(HttpRequest request) {
        String host = config.advertisedHost;
        if (host == null || host.trim().length() == 0) {
            host = request.header("host");
        }
        if (host == null || host.trim().length() == 0) {
            return "127.0.0.1";
        }
        int colon = host.indexOf(':');
        return colon >= 0 ? host.substring(0, colon) : host;
    }

    private String webSocketUrl(HttpRequest request) {
        String host = config.advertisedHost;
        if (host == null || host.trim().length() == 0) {
            host = request.header("host");
            if (host != null && host.indexOf(':') >= 0) {
                host = host.substring(0, host.indexOf(':')) + ":" + config.wsPort;
            }
        }
        if (host == null || host.trim().length() == 0) {
            host = "127.0.0.1:" + config.wsPort;
        }
        return "ws://" + host + "/ws";
    }

    private String[] segments(String path) {
        String clean = path.startsWith("/") ? path.substring(1) : path;
        return clean.length() == 0 ? new String[0] : clean.split("/");
    }

    private int parseId(String value) {
        return Integer.parseInt(value);
    }

    private boolean isPlayableDeckCode(String deckCode) {
        if (deckCode == null || !deckCode.startsWith("%%")) return false;
        String code = deckCode.substring(2);
        String[] parts = code.split("\\|");
        if (parts.length < 2) return false;
        String country = parts[0];
        String cards = parts[1];
        if (country.length() < 2) return false;
        if (cards.indexOf('~') >= 0) cards = cards.substring(0, cards.indexOf('~'));
        String[] groups = cards.split(";", -1);
        if (groups.length != 4) return false;
        // 不能是空卡组
        for (String group : groups) {
            if (group.length() > 0) return true;
        }
        return false;
    }

    private String normalizeFaction(JSONObject body, String... keys) {
        for (String key : keys) {
            String value = body.optString(key);
            if (value != null && value.length() > 0) return value;
        }
        return "";
    }

    private static String countryName(String code) {
        if ("1".equals(code)) return "Germany";
        if ("2".equals(code)) return "Britain";
        if ("3".equals(code)) return "Japan";
        if ("4".equals(code)) return "Soviet";
        if ("5".equals(code)) return "USA";
        if ("6".equals(code)) return "France";
        if ("7".equals(code)) return "Italy";
        if ("8".equals(code)) return "Poland";
        if ("9".equals(code)) return "Finland";
        return "Unknown";
    }

    private static final class Unauthorized extends RuntimeException {
    }

    private static final class NotFound extends RuntimeException {
    }
}
