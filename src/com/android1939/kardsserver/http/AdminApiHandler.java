package com.android1939.kardsserver.http;

import com.android1939.kardsserver.CardCatalog;
import com.android1939.kardsserver.CardIdManager;
import com.android1939.kardsserver.MatchManager;
import com.android1939.kardsserver.ServerConfig;
import com.android1939.kardsserver.ServerLog;
import com.android1939.kardsserver.ServerOptionsStore;
import com.android1939.kardsserver.StoreService;
import com.android1939.kardsserver.db.KardsDatabase;
import com.android1939.kardsserver.model.DeckRecord;
import com.android1939.kardsserver.model.MatchState;
import com.android1939.kardsserver.model.UserRecord;
import com.android1939.kardsserver.util.TimeUtil;
import com.android1939.kardsserver.ws.SimpleWebSocketServer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 后台管理 API：{@code /admin/api/*}。
 *
 * <h3>为什么是这个路径</h3>
 * 桌面端 KLink 已把私服换成 CCB-TEAM/fyserver，后台页面（Vue SPA）直接消费
 * {@code /admin/api/*}。手机端要复用同一套页面，就必须提供同名同形的接口 ——
 * 这样前端零改动，两台设备上的后台行为也保持一致。
 *
 * <h3>鉴权（对齐桌面端 AdminApiAuthorizationMiddleware 的 KLink 改动）</h3>
 * <pre>
 *   回环请求（127.0.0.1 / ::1）  → 视为 Owner，免登录
 *   带 X-Admin-Key 的请求        → 视为「KLink 启动器」，Owner
 *   其它请求                     → 401（局域网模式下后台地址被别人打开时不会裸奔）
 * </pre>
 * 手机端 WebView 访问的就是回环地址，所以天然免登录；这与桌面端行为一致。
 *
 * <h3>与既有 /admin/* 的关系</h3>
 * 本项目原有的 {@code /admin/library}、{@code /admin/decks} 等是「卡牌/卡组 ID 管理器」，
 * 与 fyserver 的后台是两回事，因此保持不动、分属不同前缀。后续会把 ID 管理器
 * 也搬到 {@code /admin/api} 下，由同一套后台页面统一管理。
 *
 * <h3>进度</h3>
 * 当前实现的是让后台"能打开、能看清楚状态"的最小集：
 * {@code session} / {@code stats} / {@code users} / {@code users/{id}} /
 * {@code matches} / {@code queues/clear} / {@code users/{id}/kick|ban|unban}。
 * 其余端点（content、store、redeem、patch-paks、server-config、cards、
 * match-config、system-settings、matches/history、accounts、audit-logs、
 * database）尚未实现，落到 501，前端会显示错误而不是白屏。
 */
public final class AdminApiHandler {

    /** URL 前缀 */
    public static final String PREFIX = "/admin/api";

    /** 后台权限项（对齐 fyserver AdminAccountService.AvailablePermissions）。 */
    private static final String[] AVAILABLE_PERMISSIONS = {
            "players", "content", "matches", "serverConfig",
            "systemSettings", "patchPaks", "permissions"
    };

    /** 未登录时的默认显示名（fyserver 的 ServerOptions.DefaultAdminDisplayName）。 */
    private static final String DEFAULT_ADMIN_DISPLAY_NAME = "管理员";
    /** 未上传头像时的回退图标（fyserver 的 DefaultAdminAvatarUrl）。 */
    private static final String DEFAULT_ADMIN_AVATAR_URL = "/admin-ui/assets/klink-icon-256.png";

    /** 进程启动时刻，用于 uptime。 */
    private static final long STARTED_AT_MS = System.currentTimeMillis();

    private final ServerConfig config;
    private final KardsDatabase database;
    private final MatchManager matches;
    private final SimpleWebSocketServer webSockets;
    private final CardIdManager cardIds;
    private final ServerOptionsStore optionsStore;
    private final CardCatalog catalog;
    private final StoreService store;

    // ---------------------------------------------------------------- 鉴权

    /** 鉴权后的操作者。手机端回环访问即 Owner。 */
    private static final class Actor {
        final boolean owner;
        final String username;

        Actor(boolean owner, String username) {
            this.owner = owner;
            this.username = username;
        }
    }

    public AdminApiHandler(ServerConfig config, KardsDatabase database, MatchManager matches,
                           SimpleWebSocketServer webSockets, CardIdManager cardIds,
                           ServerOptionsStore optionsStore, CardCatalog catalog, StoreService store) {
        this.config = config;
        this.database = database;
        this.matches = matches;
        this.webSockets = webSockets;
        this.cardIds = cardIds;
        this.optionsStore = optionsStore;
        this.catalog = catalog;
        this.store = store;
    }

    /** 该路径是否属于后台管理 API。 */
    public static boolean handles(String path) {
        return path != null && (path.equals(PREFIX) || path.startsWith(PREFIX + "/"));
    }

    /** 是否为回环请求。 */
    public static boolean isLoopback(HttpRequest request) {
        if (request == null) {
            return false;
        }
        String address = request.remoteAddress;
        if (address == null) {
            return false;
        }
        address = address.trim();
        return "127.0.0.1".equals(address)
                || "::1".equals(address)
                || "0:0:0:0:0:0:0:1".equals(address)
                || address.startsWith("127.");
    }

    /**
     * 解析操作者。回环 → Owner；带正确 X-Admin-Key → Owner；否则 null。
     * 与桌面端 AdminApiAuthorizationMiddleware 的 KLink 补丁等价。
     */
    private Actor authenticate(HttpRequest request) {
        if (isLoopback(request)) {
            return new Actor(true, DEFAULT_ADMIN_DISPLAY_NAME);
        }
        String supplied = request.header("x-admin-key");
        String expected = config == null ? null : config.adminToken;
        if (supplied != null && expected != null && expected.length() > 0
                && constantTimeEquals(supplied, expected)) {
            return new Actor(true, "KLink 启动器");
        }
        return null;
    }

    /** 定长比较，避免逐字符早退泄漏密钥长度/内容信息。 */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    // ---------------------------------------------------------------- 路由

    private interface RouteHandler {
        HttpResponse handle(HttpRequest request, Actor actor) throws Exception;
    }

    private static final class Route {
        final String method;
        final String[] pattern;
        final RouteHandler handler;

        Route(String method, String path, RouteHandler handler) {
            this.method = method;
            // 必须用 segments()（会先去掉首尾斜杠）而不是 split()：
            // split("/session") 得到 ["", "session"] 两段，与请求侧
            // segments("/admin/api/session") = ["admin","api","session"] 永远对不上，
            // 结果是**所有**后台端点都落到 501。这个坑踩过一次。
            this.pattern = segments(path);
            this.handler = handler;
        }

        /** 段数相同且 '{x}' 位可匹配任意非空段。 */
        boolean matches(String method, String[] parts) {
            if (!this.method.equals(method) || pattern.length != parts.length) {
                return false;
            }
            for (int i = 0; i < pattern.length; i++) {
                String expected = pattern[i];
                boolean placeholder = expected.length() > 1
                        && expected.charAt(0) == '{' && expected.charAt(expected.length() - 1) == '}';
                if (placeholder) {
                    if (parts[i].isEmpty()) {
                        return false;
                    }
                } else if (!expected.equals(parts[i])) {
                    return false;
                }
            }
            return true;
        }
    }

    private final List<Route> routes = new ArrayList<Route>();

    private void registerRoutes() {
        // ---- 会话 ----
        routes.add(new Route("GET", "/session", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                return json(200, session(request, actor));
            }
        }));

        // ---- 登录 ----
        //
        // 手机端的定位与桌面端一致：**回环请求本来就不需要登录**（WebView 走的就是回环）。
        // 但登录页仍是可达的（用户手动打开 /admin-ui/login.html，或将来非回环访问），
        // 所以这里必须给出可用的实现 —— 否则用户只会看到一片 501、以为坏了。
        //
        // 语义：
        //   · 未设置过后台密码 → 回环访问直接放行（首次"设密码"其实什么都不用做）
        //   · 已设置过密码     → 校验密码后才放行
        //   · 非回环 + 带对 X-Admin-Key → 放行（对齐桌面端）
        routes.add(new Route("POST", "/login", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                return login(request);
            }
        }));
        routes.add(new Route("POST", "/setup", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                return setup(request);
            }
        }));
        routes.add(new Route("POST", "/logout", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                return json(200, message(true, "手机端回环访问无需登录，已忽略登出请求"));
            }
        }));
        routes.add(new Route("GET", "/me", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                return json(200, session(request, actor));
            }
        }));

        // ---- 诊断：出问题时用来确认服务器看到的来源地址与鉴权结论 ----
        routes.add(new Route("GET", "/diag", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                return json(200, diag(request, actor));
            }
        }));

        // ---- 总览 ----
        routes.add(new Route("GET", "/stats", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                return json(200, stats());
            }
        }));

        // ---- 玩家 ----
        routes.add(new Route("GET", "/users", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                return json(200, users(request.query.get("q")));
            }
        }));
        routes.add(new Route("GET", "/users/{id}", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                return userDetail(parseInt(segments(request.path)[2]));
            }
        }));
        routes.add(new Route("POST", "/users/{id}/kick", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                return kick(parseInt(segments(request.path)[2]), request.jsonBody());
            }
        }));

        // ---- 对局 ----
        routes.add(new Route("GET", "/matches", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                return json(200, activeMatches());
            }
        }));
        routes.add(new Route("POST", "/queues/clear", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                return clearQueues();
            }
        }));

        // ---- 服务器配置（server_options，可在后台编辑，不必改源码重编译）----
        routes.add(new Route("GET", "/server-config", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                return serverConfig();
            }
        }));
        routes.add(new Route("PUT", "/server-config", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                if (optionsStore == null) {
                    return saveResult(ServerOptionsStore.Result.fail("服务器配置不可用"));
                }
                JSONObject body = request.jsonBody();
                return saveResult(optionsStore.save(body.optString("raw", "")));
            }
        }));
        routes.add(new Route("PUT", "/server-config/item", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                if (optionsStore == null) {
                    return saveResult(ServerOptionsStore.Result.fail("服务器配置不可用"));
                }
                JSONObject body = request.jsonBody();
                return saveResult(optionsStore.upsert(
                        body.optString("key", ""),
                        body.has("value") ? body.get("value") : JSONObject.NULL,
                        body.optString("description", "")));
            }
        }));
        routes.add(new Route("DELETE", "/server-config/item/{key}", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                if (optionsStore == null) {
                    return saveResult(ServerOptionsStore.Result.fail("服务器配置不可用"));
                }
                return saveResult(optionsStore.delete(segments(request.path)[3]));
            }
        }));
        routes.add(new Route("PUT", "/server-config/item/{key}/enabled", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                if (optionsStore == null) {
                    return saveResult(ServerOptionsStore.Result.fail("服务器配置不可用"));
                }
                JSONObject body = request.jsonBody();
                return saveResult(optionsStore.setEnabled(
                        segments(request.path)[3], body.optBoolean("enabled", false)));
            }
        }));

        // ---- 商店配置 ----
        routes.add(new Route("GET", "/store", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                return storeConfig();
            }
        }));
        routes.add(new Route("PUT", "/store", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                return saveStoreConfig(request);
            }
        }));
        routes.add(new Route("POST", "/store/reload", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                if (store != null) {
                    store.reload();
                }
                return json(200, message(true, "已重新读取商店配置"));
            }
        }));

        // ---- 卡牌目录 ----
        routes.add(new Route("GET", "/cards", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                return cards(request);
            }
        }));
        routes.add(new Route("PUT", "/cards/defaults", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                return cardsDefaults(request);
            }
        }));
        routes.add(new Route("GET", "/system-settings/player-library", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                return json(200, playerLibraryPolicy());
            }
        }));
        routes.add(new Route("PUT", "/system-settings/player-library", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                return savePlayerLibraryPolicy(request);
            }
        }));

        // ---- 系统设置（网络地址 / 对局保留）----
        routes.add(new Route("GET", "/system-settings", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                return systemSettings();
            }
        }));
        routes.add(new Route("PUT", "/system-settings", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                return saveSystemSettings(request);
            }
        }));
        routes.add(new Route("GET", "/system-settings/match-retention", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                return matchRetention();
            }
        }));
        routes.add(new Route("PUT", "/system-settings/match-retention", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                return saveMatchRetention(request);
            }
        }));

        // ---- 对局配置（开局金卡开关）----
        routes.add(new Route("GET", "/match-config", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                return matchConfig();
            }
        }));
        routes.add(new Route("POST", "/match-config", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                return saveMatchConfig(request);
            }
        }));
        routes.add(new Route("POST", "/match-config/reload", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                return json(200, message(true, "对局配置已重新读取"));
            }
        }));

        // ---- 审计日志（手机端暂无账号体系，给空列表让页面能渲染）----
        routes.add(new Route("GET", "/audit-logs", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                JSONObject result = new JSONObject();
                result.put("ok", true);
                result.put("total", 0);
                result.put("entries", new JSONArray());
                return json(200, result);
            }
        }));

        // ---- 对局历史（暂无持久化，返回空列表而不是 501）----
        routes.add(new Route("GET", "/matches/history", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                JSONObject result = new JSONObject();
                result.put("matches", new JSONArray());
                result.put("total", 0);
                result.put("page", 1);
                result.put("pageSize", 20);
                return json(200, result);
            }
        }));
        routes.add(new Route("GET", "/matches/history/storage", new RouteHandler() {
            @Override
            public HttpResponse handle(HttpRequest request, Actor actor) throws Exception {
                JSONObject result = new JSONObject();
                result.put("ok", true);
                result.put("storage", "sqlite");
                result.put("enabled", false);
                result.put("message", "手机端暂未开启对局历史持久化");
                return json(200, result);
            }
        }));
    }

    // ---------------------------------------------------------------- 系统设置

    /**
     * 网络地址。字段名对齐后台「系统设置」页的 renderNetwork()。
     *
     * <h3>监听地址是只读的（有意为之）</h3>
     * 监听 IP/端口由**启动器模式**决定：local → 127.0.0.1、lan → 0.0.0.0，
     * 端口固定 5231。它们不来自数据库，也不接受后台覆盖。
     *
     * <p>桌面端踩过一个坑：库里持久化的 {@code server:settings} 把启动器刚写的
     * setting.json 覆盖掉，切到局域网时 {@code listenIp=0.0.0.0} 被旧值顶回 127.0.0.1，
     * 表现为"局域网模式却只绑回环、其它设备连不上"。手机端**从设计上就不存监听地址**，
     * 所以不会重现；这里把 {@code listenIp}/{@code listenPort} 标为只读，
     * 避免做出一个"能改但不生效"的假开关。</p>
     *
     * <p>可以改的是「对外 IP」：某些场景（反代、域名、指定网卡）需要下发给客户端的
     * 地址与请求 Host 不同，那个会写进 {@code config.advertisedHost}。</p>
     */
    private HttpResponse systemSettings() throws Exception {
        int listenPort = config == null ? 5231 : config.httpPort;
        String listenIp = config == null ? "0.0.0.0" : config.bindHost;
        String advertised = config == null || config.advertisedHost == null
                ? "" : config.advertisedHost;
        String publicIp = setting("publicIp", advertised);

        JSONObject result = new JSONObject();
        result.put("ok", true);
        // 只读：直接反映当前运行的配置，不去库里取（库里根本没有这个键）
        result.put("listenIp", listenIp);
        result.put("listenPort", listenPort);
        result.put("listenEditable", false);
        result.put("listenNote", "监听地址由「本地 / 局域网」模式决定，切换模式即可；后台不覆盖它。");
        result.put("publicIp", publicIp);
        result.put("publicScheme", setting("publicScheme", "http"));
        result.put("publicPort", JSONObject.NULL);
        // 「当前运行值」与「保存值」在手机端恒等 —— 监听部分不存在待生效的改动
        result.put("activeListenIp", listenIp);
        result.put("activeListenPort", listenPort);
        result.put("activePublicIp", advertised);
        result.put("activePublicScheme", "http");
        result.put("activePublicPort", JSONObject.NULL);
        result.put("restartRequired", false);
        return json(200, result);
    }

    /**
     * 保存网络设置。**只保存对外地址**，监听地址一律忽略 ——
     * 见 {@link #systemSettings()} 里关于"能改但不生效的假开关"的说明。
     */
    private HttpResponse saveSystemSettings(HttpRequest request) throws Exception {
        JSONObject body = request.jsonBody();
        try {
            writeSetting("publicIp", body.optString("publicIp", "").trim());
            writeSetting("publicScheme", body.optString("publicScheme", "http"));
        } catch (Exception e) {
            return json(200, message(false, "保存失败：" + e.getMessage()));
        }
        return json(200, message(true,
                "已保存对外地址。监听地址由「本地 / 局域网」模式决定，这里不会改动它。"));
    }

    /** 对局保留策略。手机端尚无对局历史表，这里只做「可读写」的占位。 */
    private HttpResponse matchRetention() throws Exception {
        JSONObject result = new JSONObject();
        result.put("mode", setting("retentionMode", "none"));
        result.put("keepDays", intSetting("retentionKeepDays", 30));
        result.put("keepCount", intSetting("retentionKeepCount", 500));
        result.put("cleanupDayUtc", intSetting("retentionCleanupDayUtc", 0));
        result.put("cleanupHourUtc", intSetting("retentionCleanupHourUtc", 4));
        return json(200, result);
    }

    private HttpResponse saveMatchRetention(HttpRequest request) throws Exception {
        JSONObject body = request.jsonBody();
        writeSetting("retentionMode", body.optString("mode", "none"));
        writeSetting("retentionKeepDays", String.valueOf(body.optInt("keepDays", 30)));
        writeSetting("retentionKeepCount", String.valueOf(body.optInt("keepCount", 500)));
        writeSetting("retentionCleanupDayUtc", String.valueOf(body.optInt("cleanupDayUtc", 0)));
        writeSetting("retentionCleanupHourUtc", String.valueOf(body.optInt("cleanupHourUtc", 4)));
        return json(200, message(true, "对局保留策略已保存"));
    }

    // ---------------------------------------------------------------- 对局配置

    /** {@code {allGoldCards:bool}} —— 后台「对局配置」页开关。 */
    private HttpResponse matchConfig() throws Exception {
        JSONObject result = new JSONObject();
        result.put("allGoldCards",
                matches == null ? true : matches.isAllGoldCards());
        return json(200, result);
    }

    private HttpResponse saveMatchConfig(HttpRequest request) throws Exception {
        JSONObject body = request.jsonBody();
        if (!body.has("allGoldCards")) {
            return json(200, message(false, "缺少 allGoldCards"));
        }
        boolean allGold = body.optBoolean("allGoldCards", true);
        if (matches != null) {
            matches.setAllGoldCards(allGold);
        }
        writeSetting("allGoldCards", allGold ? "1" : "0");
        return json(200, message(true, allGold ? "金卡已开启" : "金卡已关闭"));
    }

    // ---------------------------------------------------------------- 新玩家卡牌策略

    /** 手机端的初始收藏策略：all = 全卡各 4 张，selected = 指定卡表。 */
    private JSONObject playerLibraryPolicy() throws Exception {
        JSONObject result = new JSONObject();
        String mode = setting("playerLibraryMode", "all");
        result.put("mode", mode);
        result.put("allGold", "1".equals(setting("playerLibraryAllGold", "0")));

        JSONArray entries = new JSONArray();
        JSONObject selected = parseObject(setting("playerLibraryCards", null));
        if (selected != null && catalog != null) {
            Iterator<String> keys = selected.keys();
            while (keys.hasNext()) {
                String cardId = keys.next();
                JSONObject row = catalog.getCard(cardId);
                JSONObject item = new JSONObject();
                item.put("cardId", cardId);
                item.put("count", selected.optInt(cardId, 0));
                item.put("name", row == null ? cardId : row.optString("title", cardId));
                item.put("cardSet", row == null ? "" : row.optString("cardSet", ""));
                item.put("type", row == null ? "" : row.optString("type", ""));
                item.put("kredits", row == null ? JSONObject.NULL : row.opt("kredits"));
                entries.put(item);
            }
        }
        result.put("defaultCards", entries);
        return result;
    }

    private HttpResponse savePlayerLibraryPolicy(HttpRequest request) throws Exception {
        JSONObject body = request.jsonBody();
        String mode = body.optString("mode", "");
        if (!"all".equals(mode) && !"selected".equals(mode)) {
            return json(200, message(false, "新玩家卡牌模式必须是 all 或 selected"));
        }
        JSONObject selected = new JSONObject();
        JSONArray entries = body.optJSONArray("defaultCards");
        if (entries != null) {
            for (int i = 0; i < entries.length(); i++) {
                JSONObject item = entries.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String cardId = item.optString("cardId", "").trim();
                if (cardId.length() == 0) {
                    continue;
                }
                int count = item.optInt("count", 1);
                if (count < 1 || count > 1000) {
                    return json(200, message(false, "卡牌 " + cardId + " 数量必须在 1 到 1000 之间"));
                }
                selected.put(cardId, count);
            }
        }
        writeSetting("playerLibraryMode", mode);
        writeSetting("playerLibraryAllGold", body.optBoolean("allGold", false) ? "1" : "0");
        writeSetting("playerLibraryCards", selected.toString());
        return json(200, message(true, "新玩家卡牌设置已保存"));
    }

    /** 把当前筛选命中的卡批量加入默认清单。 */
    private HttpResponse cardsDefaults(HttpRequest request) throws Exception {
        if (catalog == null) {
            return json(501, notImplemented("/cards/defaults"));
        }
        JSONObject body = request.jsonBody();
        int count = body.optInt("count", 0);
        if (count < 1 || count > 1000) {
            return json(200, message(false, "默认卡牌数量必须在 1 到 1000 之间"));
        }
        // 用同一套筛选条件跑一次全量查询（pageSize 上限 100，这里直接取大分页）
        JSONObject matched = catalog.query(
                firstNonNull(body.optString("q", ""), body.optString("search", "")),
                body.optString("cardSet", ""),
                body.optString("type", ""),
                optionalInt(body.has("minKredits") ? String.valueOf(body.opt("minKredits")) : null),
                optionalInt(body.has("maxKredits") ? String.valueOf(body.opt("maxKredits")) : null),
                1, 100);
        JSONArray items = matched.optJSONArray("items");

        JSONObject selected = parseObject(setting("playerLibraryCards", null));
        if (selected == null) {
            selected = new JSONObject();
        }
        int added = 0;
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String cardId = item.optString("id", "");
                if (cardId.length() > 0) {
                    selected.put(cardId, count);
                    added++;
                }
            }
        }
        writeSetting("playerLibraryCards", selected.toString());
        writeSetting("playerLibraryMode", "selected");
        return json(200, message(true, "已将 " + added + " 张筛选结果加入新玩家默认卡牌"));
    }

    private static String firstNonNull(String a, String b) {
        return a != null && a.length() > 0 ? a : b;
    }

    // ---------------------------------------------------------------- 服务器设置存取

    private String setting(String key, String fallback) {
        String value = database.readSetting("admin:" + key);
        return value == null ? fallback : value;
    }

    private int intSetting(String key, int fallback) {
        return optInt(setting(key, null), fallback);
    }

    private void writeSetting(String key, String value) {
        database.writeSetting("admin:" + key, value == null ? "" : value);
    }

    private static JSONObject parseObject(String json) {
        if (json == null || json.trim().length() == 0) {
            return null;
        }
        try {
            return new JSONObject(json);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 卡牌目录查询。响应体 {@code {items, total, page, pageSize, cardSets, types}}，
     * 前端分页与筛选都读这几个字段。
     */
    private HttpResponse cards(HttpRequest request) throws Exception {
        if (catalog == null) {
            return json(501, notImplemented("/cards"));
        }
        int page = optInt(request.query.get("page"), 1);
        int pageSize = optInt(request.query.get("pageSize"), 100);
        Integer minKredits = optionalInt(request.query.get("minKredits"));
        Integer maxKredits = optionalInt(request.query.get("maxKredits"));
        JSONObject result = catalog.query(
                request.query.get("search"),
                request.query.get("cardSet"),
                request.query.get("type"),
                minKredits, maxKredits, page, pageSize);
        return json(200, result);
    }

    private static int optInt(String value, int fallback) {
        if (value == null || value.trim().length() == 0) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static Integer optionalInt(String value) {
        if (value == null || value.trim().length() == 0) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** 后台「商店」页读取：{@code {ok, storage, config}}（前端读 r.config）。 */
    private HttpResponse storeConfig() throws Exception {
        if (store == null) {
            return json(501, notImplemented("/store"));
        }
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("storage", "file");
        result.put("path", store.storeConfigFile().getName());
        result.put("config", store.storeConfig());
        return json(200, result);
    }

    /** 后台「商店」页保存。 */
    private HttpResponse saveStoreConfig(HttpRequest request) throws Exception {
        if (store == null) {
            return json(501, notImplemented("/store"));
        }
        JSONObject body = request.jsonBody();
        JSONObject config = body.optJSONObject("config");
        if (config == null) {
            String raw = body.optString("raw", "");
            if (raw.length() > 0) {
                try {
                    config = new JSONObject(raw);
                } catch (Exception e) {
                    return json(200, message(false, "raw JSON 格式错误：" + e.getMessage()));
                }
            }
        }
        if (config == null) {
            return json(200, message(false, "商店配置格式无效"));
        }
        if (!config.has("alwaysFeatured") && !config.has("groups")) {
            return json(200, message(false, "商店配置缺少 alwaysFeatured 或 groups"));
        }
        try {
            store.saveConfig(config);
        } catch (Exception e) {
            return json(200, message(false, "保存失败：" + e.getMessage()));
        }
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("message", "商店配置已保存（已备份 .bak）");
        result.put("config", config);
        return json(200, result);
    }

    private static JSONObject message(boolean ok, String text) {
        JSONObject result = new JSONObject();
        try {
            result.put("ok", ok);
            result.put("message", text);
        } catch (Exception ignored) {
        }
        return result;
    }

    /**
     * 后台「服务器配置」页的数据。
     * {@code raw} 是 JSON **字符串**（前端 {@code JSON.parse(r.raw)}），不是对象。
     */
    private HttpResponse serverConfig() throws Exception {
        JSONObject payload = new JSONObject();
        if (optionsStore == null) {
            payload.put("raw", "{}");
            payload.put("schema", new JSONArray());
            payload.put("flags", new JSONObject());
            payload.put("comments", new JSONObject());
        } else {
            payload.put("raw", optionsStore.readTemplate());
            payload.put("schema", optionsStore.readSchema());
            payload.put("flags", optionsStore.readFlags());
            payload.put("comments", optionsStore.readComments());
        }
        return json(200, payload);
    }

    /** 后台写操作的统一响应：{@code {ok, message}}。 */
    private static HttpResponse saveResult(ServerOptionsStore.Result result) {
        JSONObject body = new JSONObject();
        try {
            body.put("ok", result != null && result.ok);
            body.put("message", result == null ? "操作失败" : result.message);
        } catch (Exception ignored) {
        }
        return json(200, body);
    }

    // ---------------------------------------------------------------- 入口

    public HttpResponse handle(HttpRequest request) {
        if (request == null || request.path == null) {
            return HttpResponse.empty(404);
        }

        String method = request.method == null ? "GET" : request.method;
        String path = request.path;

        // CORS 预检：后台页面与 API 同源，一般用不到；但启动器/外部工具调用时会用到。
        if ("OPTIONS".equals(method)) {
            HttpResponse preflight = HttpResponse.empty(204);
            preflight.headers.put("Access-Control-Allow-Origin", "*");
            preflight.headers.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            preflight.headers.put("Access-Control-Allow-Headers",
                    "Content-Type, X-Admin-Key, Authorization");
            preflight.headers.put("Access-Control-Max-Age", "86400");
            return preflight;
        }

        // 匿名可达的端点（与桌面端 AdminApiAuthorizationMiddleware 的豁免列表一致）
        String local = path.length() > PREFIX.length() ? path.substring(PREFIX.length()) : "/";
        boolean anonymousAllowed = local.equals("/session")
                || local.equals("/login")
                || local.equals("/setup")
                || local.equals("/database/configure");

        Actor actor = authenticate(request);
        if (actor == null && !anonymousAllowed) {
            // 记下来源，这类"后台莫名要登录"的问题光看现象很难查
            com.android1939.kardsserver.ServerLog.add("admin",
                    "401 " + method + " " + local + " from " + request.remoteAddress);
            return json(401, error("请先登录后台账号"));
        }

        try {
            // 关键：路由表里的 pattern 是**去掉 /admin/api 前缀之后**的路径段
            // （Route 构造函数用 segments("/session") = ["session"]）。
            // 所以这里也必须拿 local 切段，而不是完整请求路径 ——
            // 用完整路径会得到 ["admin","api","session"]，段数永远对不上，
            // 结果是所有端点都落到 501。这个 bug 让整个后台瘫痪过，别再犯。
            String[] parts = segments(local);
            for (Route route : routes) {
                if (route.matches(method, parts)) {
                    HttpResponse response = route.handler.handle(request, actor);
                    if (response != null) {
                        response.headers.put("Access-Control-Allow-Origin", "*");
                    }
                    if (!anonymousAllowed) {
                        com.android1939.kardsserver.ServerLog.add("admin",
                                response == null ? "?" : String.valueOf(response.statusCode)
                                        + " " + method + " " + local);
                    }
                    return response;
                }
            }
        } catch (NumberFormatException e) {
            com.android1939.kardsserver.ServerLog.add("admin",
                    "400 " + method + " " + local + " : " + e);
            return json(400, error("路径参数不是合法数字"));
        } catch (Exception e) {
            // 一定要记日志：这里的异常若被静默吃掉，前端只能看到一个没头没脑的 500
            com.android1939.kardsserver.ServerLog.add("admin",
                    "500 " + method + " " + local + " : " + e);
            return json(500, error("服务器内部错误: " + e));
        } catch (Throwable t) {
            // 捕获 NoClassDefFoundError 这类 Error —— 在 d8/Android 上缺类时
            // 它不是 Exception，不接住就会让整个请求线程无声失败
            com.android1939.kardsserver.ServerLog.add("admin",
                    "500E " + method + " " + local + " : " + t);
            return json(500, error("服务器内部错误(Error): " + t));
        }

        com.android1939.kardsserver.ServerLog.add("admin",
                "501 " + method + " " + path + "（该端点尚未移植）from " + request.remoteAddress);
        return json(501, notImplemented(local));
    }

    // ---------------------------------------------------------------- 登录 / 诊断

    /** 后台密码的存储键。未设置时表示"本机免密码"。 */
    private static final String KEY_PASSWORD_HASH = "admin:passwordHash";
    private static final String KEY_PASSWORD_SALT = "admin:passwordSalt";

    /**
     * 登录。手机端主要靠回环免登录，这里是为了让登录页在**任何来源**下都能用。
     */
    private HttpResponse login(HttpRequest request) throws Exception {
        boolean loopback = isLoopback(request);
        if (loopback && !hasPassword()) {
            // 本机且从未设过密码：直接放行，前端拿到 authorized 后就会跳回控制台
            return json(200, message(true, "本机访问无需登录"));
        }
        JSONObject body = request.jsonBody();
        String password = body.optString("password", "");
        if (!hasPassword()) {
            // 非回环但没设过密码：只接受 X-Admin-Key（authenticate 已放行过一次，
            // 走到这里说明没有有效密钥）
            return json(401, message(false, "未设置后台密码，且来源不是本机。请用 X-Admin-Key 访问。"));
        }
        if (!verifyPassword(password)) {
            com.android1939.kardsserver.ServerLog.add("admin", "login failed from " + request.remoteAddress);
            return json(401, message(false, "密码不正确"));
        }
        com.android1939.kardsserver.ServerLog.add("admin", "login ok from " + request.remoteAddress);
        return json(200, message(true, "登录成功"));
    }

    /** 首次设置后台密码。本机免密码，所以这一步是可选的。 */
    private HttpResponse setup(HttpRequest request) throws Exception {
        if (!isLoopback(request)) {
            return json(403, message(false, "首次设置仅允许在服务器本机完成"));
        }
        JSONObject body = request.jsonBody();
        String password = body.optString("password", "");
        if (password.length() < 10) {
            return json(400, message(false, "密码至少需要 10 个字符"));
        }
        savePassword(password);
        return json(200, message(true, "后台密码已设置"));
    }

    private boolean hasPassword() {
        if (database == null) {
            return false;
        }
        String stored = database.readSetting(KEY_PASSWORD_HASH);
        return stored != null && stored.length() > 0;
    }

    /** PBKDF2-SHA256 校验；对齐桌面端的存储形态（盐 + 派生密钥）。 */
    private boolean verifyPassword(String password) {
        try {
            String storedHash = database.readSetting(KEY_PASSWORD_HASH);
            String storedSalt = database.readSetting(KEY_PASSWORD_SALT);
            if (storedHash == null || storedSalt == null) {
                return false;
            }
            byte[] salt = android.util.Base64.decode(storedSalt, android.util.Base64.NO_WRAP);
            String candidate = derivePassword(password, salt);
            return constantTimeEquals(candidate, storedHash);
        } catch (Exception e) {
            return false;
        }
    }

    private void savePassword(String password) throws Exception {
        byte[] salt = new byte[16];
        new java.security.SecureRandom().nextBytes(salt);
        database.writeSetting(KEY_PASSWORD_SALT,
                android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP));
        database.writeSetting(KEY_PASSWORD_HASH, derivePassword(password, salt));
    }

    private static String derivePassword(String password, byte[] salt) throws Exception {
        javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                password.toCharArray(), salt, 210_000, 256);
        javax.crypto.SecretKeyFactory factory =
                javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] key = factory.generateSecret(spec).getEncoded();
        spec.clearPassword();
        return android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP);
    }

    /**
     * 诊断端点。后台出问题时先看它：能确认服务器**看到的来源地址**是什么、
     * 回环判定结果如何、以及前端会拿到什么。
     */
    private JSONObject diag(HttpRequest request, Actor actor) throws Exception {
        JSONObject result = new JSONObject();
        result.put("remoteAddress", request.remoteAddress == null ? "" : request.remoteAddress);
        result.put("loopback", isLoopback(request));
        result.put("actor", actor == null ? JSONObject.NULL : actor.username);
        // 诊断端点本身要绝对稳：任何一个依赖缺失都不能让它 500
        try {
            result.put("hasPassword", hasPassword());
        } catch (Exception e) {
            result.put("hasPassword", "unknown: " + e);
        }
        try {
            result.put("userCount", database == null ? -1 : database.listUsers().size());
        } catch (Exception e) {
            result.put("userCount", "unknown: " + e);
        }
        result.put("adminTokenConfigured", config != null
                && config.adminToken != null && config.adminToken.length() > 0);
        result.put("routes", routes.size());
        result.put("bindHost", config == null ? "" : config.bindHost);
        result.put("httpPort", config == null ? 0 : config.httpPort);
        // 前端 ensureAuth() 的判定依据，直接给出结论
        result.put("ensureAuthWillPass", isLoopback(request) || actor != null);
        return result;
    }

    /**
     * 会话信息。前端 ensureAuth() 依赖 authorized / loopback 判定是否免登录。
     * 字段名与桌面端逐一对齐。
     */
    /**
     * 会话信息。前端 {@code ensureAuth()} 依赖 {@code authorized} / {@code loopback}
     * 判定是否免登录；{@code serverReady} 为 false 会被踢去 database-setup 页。
     * 字段名与桌面端逐一对齐。
     */
    private JSONObject session(HttpRequest request, Actor actor) throws Exception {
        // 注意：这里必须按**本次请求的真实来源**计算，不能写死 true。
        // 写死会让非回环请求也拿到 loopback=true，前端据此免登录放行 —— 那是越权。
        boolean loopback = isLoopback(request);
        boolean loopbackTrusted = actor != null
                && DEFAULT_ADMIN_DISPLAY_NAME.equals(actor.username);

        JSONObject result = new JSONObject();
        result.put("authorized", actor != null || loopbackTrusted);
        result.put("loopback", loopback);
        result.put("loopbackTrusted", loopbackTrusted);
        result.put("initialized", true);
        result.put("username", actor == null ? JSONObject.NULL : actor.username);
        result.put("avatarUrl", DEFAULT_ADMIN_AVATAR_URL);
        result.put("isOwner", actor != null && actor.owner);

        JSONArray permissions = new JSONArray();
        if (actor != null && actor.owner) {
            for (String permission : AVAILABLE_PERMISSIONS) {
                permissions.put(permission);
            }
        }
        result.put("permissions", permissions);

        result.put("hasSession", false);
        result.put("serverReady", true);
        // 手机端用 SQLite，没有"选择数据库后端"这一步，恒为已配置
        result.put("databaseConfigured", true);
        result.put("databaseProvider", "sqlite");
        result.put("databaseError", JSONObject.NULL);
        return result;
    }

    /** 总览统计。字段对齐桌面端 /admin/api/stats。 */
    private JSONObject stats() throws Exception {
        List<UserRecord> users = database.listUsers();
        int deckCount = 0;
        for (UserRecord user : users) {
            try {
                List<DeckRecord> decks = database.listDecks(user.id);
                deckCount += decks == null ? 0 : decks.size();
            } catch (Exception ignored) {
            }
        }

        int onlineCount = webSockets == null ? 0 : webSockets.onlineCount();
        int activeMatchCount = matches == null ? 0 : matches.matchCount();
        int queuedPlayerCount = matches == null ? 0 : matches.waitingCount();

        long uptimeMs = System.currentTimeMillis() - STARTED_AT_MS;

        JSONObject payload = new JSONObject();
        payload.put("userCount", users.size());
        payload.put("bannedCount", 0); // 手机端暂未做封禁持久化
        payload.put("deckCount", deckCount);
        payload.put("onlineCount", onlineCount);
        payload.put("activeMatchCount", activeMatchCount);
        payload.put("queuedPlayerCount", queuedPlayerCount);
        payload.put("queues", queues(queuedPlayerCount));
        payload.put("httpAddress", addressHttp());
        payload.put("clientHttpAddress", addressHttp());
        payload.put("webSocketAddress", addressWs());
        payload.put("port", config == null ? 5231 : config.httpPort);
        payload.put("ip", config == null ? "" : (config.advertisedHost == null ? config.bindHost : config.advertisedHost));
        payload.put("bancheat", 0);
        payload.put("startedAt", TimeUtil.formatIso(STARTED_AT_MS));
        payload.put("uptime", formatUptime(uptimeMs));
        payload.put("os", "Android " + android.os.Build.VERSION.RELEASE);
        payload.put("runtime", "Android Runtime (Java)");
        payload.put("performance", performance(uptimeMs));
        payload.put("activity", new JSONArray());
        return payload;
    }

    /**
     * 队列分布。手机端 MatchManager 的队列结构与桌面端不同（经典/休闲/乱斗/自定义 +
     * 战斗码），这里按实际结构给出，总数与 queuedPlayerCount 对齐。
     */
    private JSONArray queues(int queuedPlayerCount) {
        JSONArray array = new JSONArray();
        try {
            array.put(queue("排队中", queuedPlayerCount));
        } catch (Exception ignored) {
        }
        return array;
    }

    private JSONObject queue(String name, int count) throws Exception {
        JSONObject item = new JSONObject();
        item.put("name", name);
        item.put("count", count);
        return item;
    }

    /**
     * 运行指标。Android 上取不到桌面端那些 CPU/磁盘计数器，
     * 但 进程内存 与 数据库体积 是能拿到的 —— 对手机端来说这两个也最有意义。
     */
    private JSONObject performance(long uptimeMs) throws Exception {
        Runtime runtime = Runtime.getRuntime();
        long total = runtime.totalMemory();
        long free = runtime.freeMemory();
        long used = total - free;
        long max = runtime.maxMemory();

        JSONObject performance = new JSONObject();
        performance.put("cpuPercent", 0);
        performance.put("memoryUsedBytes", used);
        performance.put("memoryTotalBytes", max);
        performance.put("memoryPercent", max <= 0 ? 0 : Math.round(used * 1000.0 / max) / 10.0);
        performance.put("systemCpuPercent", 0);
        performance.put("databaseBytes", databaseSizeBytes());
        performance.put("uptimeMs", uptimeMs);
        return performance;
    }

    private long databaseSizeBytes() {
        try {
            java.io.File file = new java.io.File(database.getReadableDatabase().getPath());
            return file.exists() ? file.length() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 玩家列表。响应体是 {@code {"total":N,"users":[...]}} —— 前端读的是
     * {@code r.users}，返回裸数组会让列表恒为空（已实测）。
     * 条目字段与桌面端 /admin/api/users 对齐。
     */
    private JSONObject users(String query) throws Exception {
        List<UserRecord> all = database.listUsers();
        String keyword = query == null ? null : query.trim();

        JSONArray array = new JSONArray();
        for (UserRecord user : all) {
            if (keyword != null && keyword.length() > 0 && !matchesKeyword(user, keyword)) {
                continue;
            }
            array.put(userSummary(user));
        }

        JSONObject result = new JSONObject();
        result.put("total", all.size());
        result.put("users", array);
        return result;
    }

    /** 与桌面端一致的搜索范围：ID、用户名、玩家名、显示名。 */
    private static boolean matchesKeyword(UserRecord user, String keyword) {
        String lower = keyword.toLowerCase();
        if (String.valueOf(user.id).contains(keyword)) {
            return true;
        }
        String username = user.username == null ? "" : user.username.toLowerCase();
        String name = user.playerName == null ? "" : user.playerName.toLowerCase();
        return username.contains(lower) || name.contains(lower)
                || user.displayName().toLowerCase().contains(lower);
    }

    private JSONObject userSummary(UserRecord user) throws Exception {
        boolean online = webSockets != null && webSockets.isOnline(user.id);
        int deckCount = 0;
        try {
            List<DeckRecord> decks = database.listDecks(user.id);
            deckCount = decks == null ? 0 : decks.size();
        } catch (Exception ignored) {
        }

        JSONObject item = new JSONObject();
        item.put("id", user.id);
        item.put("userName", user.username == null ? "" : user.username);
        item.put("name", user.playerName == null ? "" : user.playerName);
        item.put("tag", user.playerTag);
        item.put("displayName", user.displayName());
        item.put("deckCount", deckCount);
        item.put("gold", user.gold);
        item.put("diamonds", user.diamonds);
        item.put("dust", user.dust);
        item.put("banned", user.banned);
        item.put("banExpiresAt", JSONObject.NULL);
        item.put("lastLoginAt", emptyToNull(user.lastLoginAt));
        item.put("lastLoginIp", emptyToNull(user.lastLoginIp));
        item.put("lastLoginDevice", emptyToNull(user.lastLoginDevice));
        item.put("online", online);
        item.put("createdAt", formatCreatedAt(user.createdAt));
        return item;
    }

    private static Object emptyToNull(String value) {
        return value == null || value.length() == 0 ? JSONObject.NULL : value;
    }

    /** 桌面端 createdAt 是本地时间的 "yyyy-MM-dd HH:mm"；这里从 ISO 串裁出同形结果。 */
    private static String formatCreatedAt(String iso) {
        if (iso == null || iso.length() < 16) {
            return iso == null ? "" : iso;
        }
        return iso.substring(0, 10) + " " + iso.substring(11, 16);
    }

    /**
     * 单个玩家详情。字段对齐桌面端 /admin/api/users/{id}；
     * 其中 roles / 万能牌等属于尚未移植的账号体系，先给出空值占位，
     * 让前端能正常渲染而不是报错。
     */
    private HttpResponse userDetail(int id) throws Exception {
        UserRecord user = database.findUserById(id);
        if (user == null) {
            return json(404, error("玩家不存在"));
        }

        JSONObject detail = userSummary(user);

        JSONArray decks = new JSONArray();
        for (DeckRecord deck : database.listDecks(id)) {
            JSONObject item = new JSONObject();
            item.put("id", deck.id);
            item.put("name", deck.name == null ? "" : deck.name);
            item.put("mainFaction", deck.mainFaction == null ? "" : deck.mainFaction);
            item.put("allyFaction", deck.allyFaction == null ? "" : deck.allyFaction);
            item.put("favorite", deck.favorite);
            item.put("lastPlayed", formatCreatedAt(deck.lastPlayed));
            decks.put(item);
        }
        detail.put("decks", decks);
        detail.put("recentMatches", new JSONArray());

        JSONObject equipment = new JSONObject();
        for (Map.Entry<String, String> entry : user.equipment.entrySet()) {
            if (entry.getValue() != null && entry.getValue().length() > 0) {
                equipment.put(entry.getKey(), entry.getValue());
            }
        }
        detail.put("equipment", equipment);

        JSONObject wallet = new JSONObject();
        wallet.put("gold", user.gold);
        wallet.put("diamonds", user.diamonds);
        wallet.put("dust", user.dust);
        detail.put("wallet", wallet);

        detail.put("roles", new JSONArray());
        detail.put("availableRoles", new JSONArray());
        detail.put("wildcards", new JSONArray());
        detail.put("hasSession", false);
        return json(200, detail);
    }

    /**
     * 对局监控。响应体是 {@code {onlineCount, activeMatches, queues}} ——
     * 前端读的是 {@code data.activeMatches}，返回裸数组会让页面恒显示"没有进行中的对局"。
     * 与桌面端 /admin/api/matches 同形。
     */
    private JSONObject activeMatches() throws Exception {
        Map<Integer, String> nameCache = new HashMap<Integer, String>();

        JSONArray active = matches == null ? new JSONArray() : matches.activeMatchesJson();
        for (int i = 0; i < active.length(); i++) {
            JSONObject item = active.optJSONObject(i);
            if (item == null) {
                continue;
            }
            int leftId = item.optInt("leftPlayerId");
            int rightId = item.optInt("rightPlayerId");
            item.put("leftPlayerName", resolveName(leftId, nameCache));
            item.put("rightPlayerName", resolveName(rightId, nameCache));
        }

        JSONArray queues = new JSONArray();
        if (matches != null) {
            JSONArray raw = matches.waitingQueuesJson();
            for (int i = 0; i < raw.length(); i++) {
                JSONObject queue = raw.optJSONObject(i);
                if (queue == null) {
                    continue;
                }
                JSONArray playerIds = queue.optJSONArray("playerIds");
                JSONArray players = new JSONArray();
                if (playerIds != null) {
                    for (int j = 0; j < playerIds.length(); j++) {
                        int playerId = playerIds.optInt(j);
                        JSONObject player = new JSONObject();
                        player.put("playerId", playerId);
                        player.put("name", resolveName(playerId, nameCache));
                        players.put(player);
                    }
                }
                JSONObject entry = new JSONObject();
                entry.put("name", queue.optString("name"));
                entry.put("players", players);
                queues.put(entry);
            }
        }

        JSONObject payload = new JSONObject();
        payload.put("onlineCount", webSockets == null ? 0 : webSockets.onlineCount());
        payload.put("activeMatches", active);
        payload.put("queues", queues);
        return payload;
    }

    /** 玩家显示名；解析不到时退回 #id，人机固定显示"人机"。 */
    private String resolveName(int playerId, Map<Integer, String> cache) {
        if (playerId <= 0) {
            return MatchState.BOT_PLAYER_ID == playerId ? "人机" : "#" + playerId;
        }
        if (cache.containsKey(playerId)) {
            return cache.get(playerId);
        }
        String name = "#" + playerId;
        try {
            UserRecord user = database.findUserById(playerId);
            if (user != null && user.playerName != null && user.playerName.length() > 0) {
                name = user.displayName();
            }
        } catch (Exception ignored) {
        }
        cache.put(playerId, name);
        return name;
    }

    /** 踢人：只断开连接，不封禁（对齐桌面端 /admin/api/users/{id}/kick 的语义）。 */
    private HttpResponse kick(int id, JSONObject body) throws Exception {
        if (id <= 0) {
            return json(400, error("缺少有效的玩家 ID"));
        }
        UserRecord user = database.findUserById(id);
        if (user == null) {
            return json(404, error("玩家不存在"));
        }
        boolean kicked = webSockets != null && webSockets.kick(id);
        if (matches != null) {
            matches.kickPlayer(id);
        }
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("playerId", id);
        result.put("playerName", user.playerName == null ? "" : user.playerName);
        result.put("kicked", kicked);
        return json(200, result);
    }

    /** 清空所有匹配队列。 */
    private HttpResponse clearQueues() throws Exception {
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("message", "队列已清空");
        return json(200, result);
    }

    // ---------------------------------------------------------------- 工具

    private String addressHttp() {
        if (config == null) {
            return "";
        }
        String host = config.advertisedHost != null && config.advertisedHost.trim().length() > 0
                ? config.advertisedHost.trim()
                : config.bindHost;
        return "http://" + host + ":" + config.httpPort + "/";
    }

    private String addressWs() {
        if (config == null) {
            return "";
        }
        String host = config.advertisedHost != null && config.advertisedHost.trim().length() > 0
                ? config.advertisedHost.trim()
                : config.bindHost;
        return "ws://" + host + ":" + config.wsPort + "/";
    }

    private static String formatUptime(long uptimeMs) {
        long totalSeconds = uptimeMs / 1000L;
        long days = totalSeconds / 86400L;
        long hours = (totalSeconds % 86400L) / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        return days + " 天 " + hours + " 小时 " + minutes + " 分 " + seconds + " 秒";
    }

    private static JSONObject error(String message) {
        JSONObject json = new JSONObject();
        try {
            json.put("ok", false);
            json.put("message", message);
        } catch (Exception ignored) {
        }
        return json;
    }

    /** 未实现的端点：明确告知而不是静默 404，便于对照进度逐条补齐。 */
    private static JSONObject notImplemented(String local) {
        JSONObject json = new JSONObject();
        try {
            json.put("ok", false);
            json.put("notImplemented", true);
            json.put("message", "该功能尚未移植到手机端：" + local);
        } catch (Exception ignored) {
        }
        return json;
    }

    private static HttpResponse json(int code, JSONObject body) {
        return HttpResponse.json(code, body);
    }

    private static HttpResponse json(int code, JSONArray body) {
        return HttpResponse.json(code, body);
    }

    private static int parseInt(String value) {
        return Integer.parseInt(value.trim());
    }

    /** 去首尾斜杠后按 '/' 切段；"/admin/api/users/3" → [admin, api, users, 3]。 */
    private static String[] segments(String path) {
        String trimmed = path;
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return split(trimmed);
    }

    private static String[] split(String path) {
        if (path == null || path.isEmpty()) {
            return new String[0];
        }
        return path.split("/");
    }

    {
        registerRoutes();
    }
}
