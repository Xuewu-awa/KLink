package com.android1939.kardsserver;

import com.android1939.kardsserver.db.KardsDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * 游戏客户端 {@code server_options} 的模板、发送开关与注释。
 *
 * <h3>要解决的问题</h3>
 * 手机端原本把 40 多项 {@code server_options}（{@code anzac}、{@code versions}、
 * 各种活动日期、{@code most_popular_products} …）**硬编码**在
 * {@code KardsHttpHandler.serverOptions()} 里 —— 想开个活动就得改 Java 源码重新编译再注入 APK。
 * 桌面端 fyserver 有 {@code ClientServerConfigService} + 后台「服务器配置」页可以把这些
 * 做成可编辑项；这里对齐同一套能力与后台接口契约。
 *
 * <h3>存哪</h3>
 * {@code server_settings} 表（本来就存在），键：
 * <ul>
 *   <li>{@link #KEY_CONFIG} —— 模板 JSON</li>
 *   <li>{@link #KEY_FLAGS} —— {@code {key: bool}} 发送开关；缺省视为启用</li>
 *   <li>{@link #KEY_COMMENTS} —— {@code {key: 说明}}，与镜像参考说明分开保存</li>
 * </ul>
 *
 * <h3>安全兜底</h3>
 * 存储值解析失败一律回落到代码内置默认模板 —— {@code server_options} 是登录链路的一部分，
 * 这里出问题会导致整个客户端登不进来，所以宁可丢弃用户改动也不能返回非法内容。
 */
public final class ServerOptionsStore {

    private static final String KEY_CONFIG = "serverOptions:raw";
    private static final String KEY_FLAGS = "serverOptions:flags";
    private static final String KEY_COMMENTS = "serverOptions:comments";

    /** 不允许后台改写取值的键：由服务器按运行环境计算。 */
    private static final Set<String> FORCED_KEYS = new HashSet<String>();

    static {
        // 下发的 WS 地址必须跟着当前监听端口/通告 IP 走
        FORCED_KEYS.add("websocketurl");
        // 版本白名单必须包含版本补丁 pak 写入的版本号，否则客户端会被版本检测挡住
        FORCED_KEYS.add("versions");
    }

    private final KardsDatabase database;
    /** 代码内置的默认模板（由 KardsHttpHandler 提供，保证与原硬编码值一致）。 */
    private final String defaultTemplateJson;
    /** 内置注释（键 → 说明）。 */
    private final JSONObject defaultComments;

    private JSONObject config;
    private JSONObject flags;
    private JSONObject comments;
    private boolean loaded;

    public ServerOptionsStore(KardsDatabase database, JSONObject defaultTemplate) {
        this.database = database;
        this.defaultTemplateJson = defaultTemplate == null ? "{}" : defaultTemplate.toString();
        this.defaultComments = describe(defaultTemplate);
    }

    /** 该键是否由服务器强制决定（后台只读）。 */
    public static boolean isForced(String key) {
        return key != null && FORCED_KEYS.contains(key);
    }

    // ---------------------------------------------------------------- 加载 / 保存

    private synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;

        config = parseObject(database.readSetting(KEY_CONFIG));
        if (config == null || config.length() == 0) {
            // 首次运行：用代码内置默认值做种子并落盘，
            // 这样后台一打开就能看到全部可编辑项，而不是空白。
            config = parseObject(defaultTemplateJson);
            if (config == null) {
                config = new JSONObject();
            }
            persist(KEY_CONFIG, config.toString());
        }

        flags = parseObject(database.readSetting(KEY_FLAGS));
        if (flags == null) {
            flags = new JSONObject();
        }

        comments = parseObject(database.readSetting(KEY_COMMENTS));
        if (comments == null) {
            comments = new JSONObject();
        }
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

    private void persist(String key, String value) {
        try {
            database.writeSetting(key, value);
        } catch (Exception ignored) {
            // 落盘失败不影响本次会话（内存里仍是有效配置）
        }
    }

    // ---------------------------------------------------------------- 后台读取

    /** 模板 JSON 字符串（后台「服务器配置」页的 raw 字段）。 */
    public synchronized String readTemplate() {
        ensureLoaded();
        return config.toString();
    }

    /** 发送开关。 */
    public synchronized JSONObject readFlags() {
        ensureLoaded();
        try {
            return new JSONObject(flags.toString());
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** 注释（内置说明 + 管理员自定义，后者优先）。 */
    public synchronized JSONObject readComments() {
        ensureLoaded();
        JSONObject merged = new JSONObject();
        try {
            Iterator<String> keys = defaultComments.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                merged.put(key, defaultComments.opt(key));
            }
            Iterator<String> custom = comments.keys();
            while (custom.hasNext()) {
                String key = custom.next();
                merged.put(key, comments.opt(key));
            }
        } catch (Exception ignored) {
        }
        return merged;
    }

    /** 后台「新增配置项」不支持——没有镜像 schema，这里恒返回空数组。 */
    public synchronized JSONArray readSchema() {
        return new JSONArray();
    }

    // ---------------------------------------------------------------- 后台写入

    /** 写操作结果。用类型而不是靠解析提示文案判断成败。 */
    public static final class Result {
        public final boolean ok;
        public final String message;

        private Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }

        public static Result ok(String message) {
            return new Result(true, message);
        }

        public static Result fail(String message) {
            return new Result(false, message);
        }
    }

    /** 整体替换模板。 */
    public synchronized Result save(String raw) {
        ensureLoaded();
        JSONObject parsed = parseObject(raw);
        if (parsed == null) {
            return Result.fail("配置内容不是合法的 JSON 对象");
        }
        config = parsed;
        persist(KEY_CONFIG, config.toString());
        return Result.ok("已保存");
    }

    /** 新增或修改单项。 */
    public synchronized Result upsert(String key, Object value, String description) {
        ensureLoaded();
        if (key == null || key.trim().length() == 0) {
            return Result.fail("缺少配置项名称");
        }
        String name = key.trim();
        if (isForced(name)) {
            return Result.fail("该配置项由服务器自动生成，不能修改取值（可以修改注释）");
        }
        if (!name.matches("[A-Za-z0-9_]+")) {
            return Result.fail("配置项名称只能包含英文字母、数字和下划线");
        }
        try {
            config.put(name, value);
        } catch (Exception e) {
            return Result.fail("取值无法序列化：" + e.getMessage());
        }
        persist(KEY_CONFIG, config.toString());

        if (description != null && description.trim().length() > 0) {
            try {
                comments.put(name, description.trim());
                persist(KEY_COMMENTS, comments.toString());
            } catch (Exception ignored) {
            }
        }
        return Result.ok("已保存");
    }

    /** 删除单项。 */
    public synchronized Result delete(String key) {
        ensureLoaded();
        if (key == null || key.trim().length() == 0) {
            return Result.fail("缺少配置项名称");
        }
        String name = key.trim();
        if (isForced(name)) {
            return Result.fail("该配置项由服务器自动生成，不能删除");
        }
        if (!config.has(name)) {
            return Result.fail("配置项不存在");
        }
        config.remove(name);
        persist(KEY_CONFIG, config.toString());
        flags.remove(name);
        persist(KEY_FLAGS, flags.toString());
        return Result.ok("已删除");
    }

    /** 设置是否下发给客户端。关闭只停止发送，不删除已保存的值。 */
    public synchronized Result setEnabled(String key, boolean enabled) {
        ensureLoaded();
        if (key == null || key.trim().length() == 0) {
            return Result.fail("缺少配置项名称");
        }
        String name = key.trim();
        if (isForced(name)) {
            return Result.fail("该配置项由服务器自动生成，始终下发");
        }
        if (!config.has(name)) {
            return Result.fail("配置项不存在，请先新增");
        }
        try {
            flags.put(name, enabled);
            persist(KEY_FLAGS, flags.toString());
        } catch (Exception e) {
            return Result.fail("保存失败：" + e.getMessage());
        }
        return Result.ok(enabled ? "已启用配置项" : "已关闭发送（值仍保留）");
    }

    // ---------------------------------------------------------------- 会话下发

    /**
     * 生成本次 {@code /session} 要下发的 {@code server_options}。
     *
     * @param webSocketAddress 当前应下发的 WS 地址（{@code ws://host:port/}）
     * @param clientVersions   允许连接的客户端版本白名单
     * @return JSON 字符串（与响应里 {@code server_options} 字段的类型保持一致）
     */
    public synchronized String readForSession(String webSocketAddress, String[] clientVersions) {
        ensureLoaded();

        JSONObject effective = new JSONObject();
        try {
            Iterator<String> keys = config.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                // 开关缺省为启用；显式关掉的跳过
                if (flags.has(key) && !flags.optBoolean(key, true)) {
                    continue;
                }
                effective.put(key, config.opt(key));
            }

            // 强制项：始终下发，且用服务器计算的值覆盖
            effective.put("websocketurl", webSocketAddress == null ? "" : webSocketAddress);
            if (clientVersions != null && clientVersions.length > 0) {
                effective.put("versions", toJsonArray(clientVersions));
            }
        } catch (Exception ignored) {
        }
        return effective.toString();
    }

    private static JSONArray toJsonArray(String[] values) {
        JSONArray array = new JSONArray();
        for (String value : values) {
            array.put(value);
        }
        return array;
    }

    // ---------------------------------------------------------------- 内置注释

    /**
     * 从默认模板生成一份「键 → 说明」的内置注释。
     *
     * <p>没有可移植的镜像 schema 文件（桌面端那份来自 qa-1939api-mirror，31 KB），
     * 所以这里给出精炼的、针对手机端语境的中文说明 —— 后台列表页没有注释时会整列空白，
     * 对着一堆 {@code covert_ops_date} 之类的键名很难知道该不该动。</p>
     */
    private static JSONObject describe(JSONObject template) {
        JSONObject result = new JSONObject();
        if (template == null) {
            return result;
        }
        put(result, "websocketurl", "客户端连接对战长连接使用的地址。由服务器按当前监听端口与通告 IP 自动生成，不可修改。");
        put(result, "versions", "允许登录的客户端版本白名单。必须包含版本补丁 pak 写入的版本号，否则客户端会被版本检测挡住。由服务器维护。");
        put(result, "anzac", "是否开启 ANZAC（澳新）阵营内容。1 开启，0 关闭。");
        put(result, "oceania_storm_date", "Oceania Storm 卡包/活动的开放时间点。格式 yyyy.MM.dd-HH.mm.ss。");
        put(result, "homefront_date", "Homefront 扩展的开放时间点。");
        put(result, "naval_warfare_date", "Naval Warfare 扩展的开放时间点。");
        put(result, "winter_war_date", "Winter War 扩展的开放时间点。");
        put(result, "brothers_in_arms_date", "Brothers in Arms 扩展的开放时间点。");
        put(result, "covert_ops_date", "Covert Ops 扩展的开放时间点。");
        put(result, "first_purchase_bonus_date", "首充奖励活动的生效时间点。");
        put(result, "battle_wait_time", "匹配等待超时（毫秒）。超过后客户端会提示等待过久。");
        put(result, "reconnect", "是否允许断线重连。1 开启。");
        put(result, "logger_disabled", "是否关闭客户端日志上报。1 关闭上报。");
        put(result, "new_rewards", "是否启用新版奖励界面。");
        put(result, "show_full_image", "卡牌是否显示完整原画（不裁切）。");
        put(result, "new_effect_bar", "是否启用新版效果栏。");
        put(result, "new_effect_bar_pc", "PC 端是否启用新版效果栏。");
        put(result, "new_effect_icons", "是否启用新版效果图标。");
        put(result, "feature_socketerror_popup_enabled", "WebSocket 出错时是否弹窗提示。");
        put(result, "nui_mobile", "移动端 UI 开关。");
        put(result, "christmas_music", "圣诞主题音乐开关。");
        put(result, "beta_expiry_date", "Beta 标识的过期时间，仅影响界面显示。");
        put(result, "most_popular_products", "商店「热门商品」展示的商品 ID 列表，以分号分隔。");
        put(result, "appscale_desktop_default", "桌面端 UI 默认缩放。");
        put(result, "appscale_desktop_max", "桌面端 UI 最大缩放。");
        put(result, "appscale_mobile_default", "移动端 UI 默认缩放。");
        put(result, "appscale_mobile_max", "移动端 UI 最大缩放。");
        put(result, "appscale_mobile_min", "移动端 UI 最小缩放。");
        put(result, "appscale_tablet_min", "平板端 UI 最小缩放。");
        put(result, "scalability_override", "按设备档位下发 UE 控制台命令（画质/分辨率相关）。");
        put(result, "locked_cards", "被锁定的卡牌与解锁时间，用于活动期卡池控制。");
        put(result, "reserve_changes", "预留变更列表。");
        put(result, "draft_card_limits", "竞技场（Draft）卡池限制：黑名单与数量上限。");
        put(result, "give_guest_name", "是否给访客自动分配名字。");
        return result;
    }

    private static void put(JSONObject target, String key, String description) {
        try {
            target.put(key, description);
        } catch (Exception ignored) {
        }
    }
}
