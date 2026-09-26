package com.android1939.kardsserver;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 卡牌目录与开包卡池策略（移植自桌面端 fyserver 的 {@code CardCatalogService}）。
 *
 * <h3>为什么需要它</h3>
 * 手机端原有 {@code library.json} 是**玩家收藏**格式（只有 card_type/count/gold_card_count/id），
 * 没有稀有度、没有卡包资格，**无法构造开包卡池**。真开包必须用完整卡牌目录
 * {@code cards-from-fmodel.json}（2067 张，含 rarity / cardSet / faction / type /
 * packEligible / corePackEligible / kredits 等元数据）。
 *
 * <h3>数据来源与覆盖</h3>
 * 优先读 {@code filesDir/data/kards-server/cards-from-fmodel.json}（用户可自行覆盖，
 * 与 AssetStore 的磁盘副本机制一致），不存在时回退到 APK 内置 assets 并复制过去。
 *
 * <h3>客户端卡牌标识</h3>
 * 客户端卡牌库里的 {@code id} 字段必须来自 deck 码表
 * （{@code deck_code_ids.json} 的 {@code ID}），不是卡牌目录里的任意值 ——
 * 桌面端最初写死 1902 是错的，后来改为查表、查不到退化为 0。
 */
public final class CardCatalog {

    private static final String ASSET_PATH = "kards-server/cards-from-fmodel.json";
    private static final String DISK_NAME = "cards-from-fmodel.json";

    /**
     * 不参与开包/收藏的卡集。
     *
     * <p>注意这里**没有** Homefront：桌面端上游曾把它一并排除，导致
     * {@code config/store.json} 里 cardSet=Homefront 的卡包买到手就开不了（死包）。
     * Homefront 实际是完整的 113 张扩展，四档稀有度齐全，已确认应保留。</p>
     */
    private static final Set<String> EXCLUDED_SETS = new HashSet<String>();

    static {
        EXCLUDED_SETS.add("Special");
        EXCLUDED_SETS.add("Placeholder");
        EXCLUDED_SETS.add("Expansion1");
        EXCLUDED_SETS.add("OnlySpawnable");
    }

    private static final Set<String> RARITIES = new HashSet<String>();

    static {
        RARITIES.add("Common");
        RARITIES.add("Uncommon");
        RARITIES.add("Rare");
        RARITIES.add("Unique");
    }

    /** 四档稀有度对应的万能牌。 */
    public static final String[] WILDCARDS = {
            "card_wildcard_standard", "card_wildcard_limited",
            "card_wildcard_special", "card_wildcard_elite"
    };

    /** 稀有度名 → 档位 0..3；未知返回 -1。 */
    public static int rarityIndex(String rarity) {
        if ("Common".equals(rarity)) return 0;
        if ("Uncommon".equals(rarity)) return 1;
        if ("Rare".equals(rarity)) return 2;
        if ("Unique".equals(rarity)) return 3;
        return -1;
    }

    private final AssetStore assets;
    private final Context context;
    private JSONObject cards;
    /** 卡牌 ID → 客户端卡牌标识（deck 码表 ID）。 */
    private final Map<String, Integer> clientCardIds = new HashMap<String, Integer>();

    public CardCatalog(Context context, AssetStore assets) {
        this.context = context.getApplicationContext();
        this.assets = assets;
    }

    // ---------------------------------------------------------------- 载入

    public synchronized void ensureLoaded() throws Exception {
        if (cards != null) {
            return;
        }
        String json = readCatalogJson();
        cards = new JSONObject(json);
        loadClientCardIds();
    }

    private String readCatalogJson() throws Exception {
        // 磁盘副本优先（允许用户覆盖卡池）
        java.io.File disk = new java.io.File(assets.dataDir(), DISK_NAME);
        if (disk.exists()) {
            return readFile(disk);
        }
        // 首次：从 APK 内置 assets 复制
        java.io.File parent = disk.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        InputStream in = null;
        try {
            in = context.getAssets().open(ASSET_PATH);
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 21);
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            byte[] bytes = out.toByteArray();
            try {
                java.io.FileOutputStream fileOut = new java.io.FileOutputStream(disk);
                try {
                    fileOut.write(bytes);
                } finally {
                    fileOut.close();
                }
            } catch (Exception ignored) {
                // 落盘失败不影响本次加载
            }
            return new String(bytes, "UTF-8");
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String readFile(java.io.File file) throws Exception {
        java.io.FileInputStream stream = new java.io.FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(1024, file.length()));
            byte[] buffer = new byte[65536];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            stream.close();
        }
    }

    /**
     * 从 deck 码表构建「卡牌 ID → 客户端卡牌标识」。
     * 同一张卡可能出现多次，保留首次映射（与卡组解析口径一致）。
     */
    private void loadClientCardIds() {
        try {
            JSONObject table = assets.deckCodeIds();
            Iterator<String> keys = table.keys();
            while (keys.hasNext()) {
                JSONObject row = table.optJSONObject(keys.next());
                if (row == null) {
                    continue;
                }
                String card = row.optString("card", "");
                if (card.length() == 0) {
                    continue;
                }
                if (!clientCardIds.containsKey(card)) {
                    clientCardIds.put(card, row.optInt("ID", 0));
                }
            }
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- 查询

    public synchronized JSONObject getCard(String cardId) {
        try {
            ensureLoaded();
        } catch (Exception e) {
            return null;
        }
        JSONObject row = cards.optJSONObject(cardId);
        return row == null ? null : deepCopy(row);
    }

    public synchronized int size() {
        try {
            ensureLoaded();
        } catch (Exception e) {
            return 0;
        }
        return cards.length();
    }

    /**
     * 开包卡池：稀有度档位 → 卡牌 ID 列表。
     *
     * @param cardSet  卡集名（"Core" 会归一成 "Basic"，与桌面端一致）
     * @param coreOnly 是否只要 core 卡池（受 isReserved 影响）
     */
    public synchronized Map<Integer, List<String>> getPackPool(String cardSet, boolean coreOnly) {
        Map<Integer, List<String>> pool = new LinkedHashMap<Integer, List<String>>();
        for (int i = 0; i < 4; i++) {
            pool.put(i, new ArrayList<String>());
        }
        try {
            ensureLoaded();
        } catch (Exception e) {
            return pool;
        }
        String normalizedSet = "Core".equals(cardSet) ? "Basic" : cardSet;
        Iterator<String> keys = cards.keys();
        while (keys.hasNext()) {
            String id = keys.next();
            JSONObject row = cards.optJSONObject(id);
            if (row == null || !isPackEligible(id, row, coreOnly)) {
                continue;
            }
            String set = cardSetOf(row);
            if (!coreOnly && !normalizedSet.equalsIgnoreCase(set == null ? "" : set)) {
                continue;
            }
            int rarity = rarityIndex(row.optString("rarity", ""));
            if (rarity >= 0) {
                pool.get(rarity).add(id);
            }
        }
        return pool;
    }

    public synchronized boolean isPackEligible(String cardId, boolean core) {
        try {
            ensureLoaded();
        } catch (Exception e) {
            return false;
        }
        JSONObject row = cards.optJSONObject(cardId);
        return row != null && isPackEligible(cardId, row, core);
    }

    // ---------------------------------------------------------------- 后台查询

    /** 后台「卡牌列表」页的分页查询。字段名对齐桌面端 /admin/api/cards。 */
    public synchronized JSONObject query(String search, String cardSet, String type,
                                         Integer minKredits, Integer maxKredits,
                                         int page, int pageSize) {
        JSONObject result = new JSONObject();
        JSONArray items = new JSONArray();
        try {
            ensureLoaded();
        } catch (Exception e) {
            putQuietly(result, "items", items);
            putQuietly(result, "total", 0);
            return result;
        }

        List<String> matched = new ArrayList<String>();
        Iterator<String> keys = cards.keys();
        while (keys.hasNext()) {
            String id = keys.next();
            if (matches(id, cards.optJSONObject(id), search, cardSet, type, minKredits, maxKredits)) {
                matched.add(id);
            }
        }
        Collections.sort(matched, String.CASE_INSENSITIVE_ORDER);

        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        int from = (safePage - 1) * safeSize;
        for (int i = from; i < Math.min(matched.size(), from + safeSize); i++) {
            String id = matched.get(i);
            JSONObject row = deepCopy(cards.optJSONObject(id));
            if (row == null) {
                continue;
            }
            putQuietly(row, "id", id);
            putQuietly(row, "packEligible", isPackEligible(id, row, false));
            putQuietly(row, "corePackEligible", isPackEligible(id, row, true));
            items.put(row);
        }

        putQuietly(result, "items", items);
        putQuietly(result, "total", matched.size());
        putQuietly(result, "page", safePage);
        putQuietly(result, "pageSize", safeSize);
        putQuietly(result, "cardSets", distinctValues("cardSet"));
        putQuietly(result, "types", distinctValues("type"));
        return result;
    }

    private JSONArray distinctValues(String field) {
        Set<String> values = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        Iterator<String> keys = cards.keys();
        while (keys.hasNext()) {
            JSONObject row = cards.optJSONObject(keys.next());
            if (row == null) {
                continue;
            }
            String value = row.optString(field, "");
            if ("cardSet".equals(field) && value.length() == 0) {
                value = row.optString("cardset", "");
            }
            if (value.length() > 0) {
                values.add(value);
            }
        }
        JSONArray array = new JSONArray();
        for (String value : values) {
            array.put(value);
        }
        return array;
    }

    /**
     * 客户端卡牌库（{@code GET /players/{id}/library}）。
     *
     * <p>按 KLink 的取舍：给**全部**可用卡各 4 张，让玩家能直接组卡
     * （个人测试服定位，不想让组卡被收藏进度卡住）。真正按收藏发放的是
     * 开包/合成/新玩家卡牌策略那条线。</p>
     *
     * <p><b>结果会缓存</b>：这份数据有 2000 多条，每次登录/重进游戏都重建一遍
     * 会造成肉眼可见的卡顿（尤其在低端机上）。内容只取决于卡牌目录，而目录在
     * 进程内不会变，所以缓存到进程结束即可 —— 返回的是序列化好的字符串，
     * 调用方直接下发，不需要再 toString()。</p>
     */
    public synchronized String buildClientLibraryJson() {
        if (clientLibraryJson == null) {
            clientLibraryJson = buildClientLibrary().toString();
        }
        return clientLibraryJson;
    }

    /** 缓存的卡牌库 JSON 字符串。 */
    private String clientLibraryJson;

    public synchronized JSONObject buildClientLibrary() {
        JSONObject result = new JSONObject();
        JSONArray rows = new JSONArray();
        try {
            ensureLoaded();
        } catch (Exception e) {
            putQuietly(result, "cards", rows);
            putQuietly(result, "new_cards", new JSONArray());
            return result;
        }
        Iterator<String> keys = cards.keys();
        while (keys.hasNext()) {
            String id = keys.next();
            JSONObject row = cards.optJSONObject(id);
            if (row == null || !isClientLibraryCard(row)) {
                continue;
            }
            JSONObject entry = new JSONObject();
            putQuietly(entry, "card_type", id);
            putQuietly(entry, "count", 4);
            // 上游写死 5 是错的（等于告诉客户端每张卡都有 5 张金卡）；
            // 真实数据里没有金卡时该字段为 0。
            putQuietly(entry, "gold_card_count", 0);
            Integer clientId = clientCardIds.get(id);
            putQuietly(entry, "id", clientId == null ? 0 : clientId.intValue());
            putQuietly(entry, "recently_crafted_count", 0);
            rows.put(entry);
        }
        putQuietly(result, "cards", rows);
        putQuietly(result, "new_cards", new JSONArray());
        return result;
    }

    // ---------------------------------------------------------------- 内部判定

    private static String cardSetOf(JSONObject row) {
        String value = row.optString("cardSet", "");
        return value.length() > 0 ? value : row.optString("cardset", "");
    }

    private static String typeOf(JSONObject row) {
        return row.optString("type", "");
    }

    private static boolean isClientLibraryCard(JSONObject row) {
        String set = cardSetOf(row);
        return set.length() > 0 && !EXCLUDED_SETS.contains(set)
                && row.optString("faction", "").length() > 0
                && RARITIES.contains(row.optString("rarity", ""));
    }

    private static boolean isPackEligible(String id, JSONObject row, boolean core) {
        String set = cardSetOf(row);
        boolean eligible = RARITIES.contains(row.optString("rarity", ""))
                && set.length() > 0 && !EXCLUDED_SETS.contains(set)
                && row.optString("faction", "").length() > 0
                && !"location".equalsIgnoreCase(typeOf(row))
                && !id.startsWith("card_location");
        if (!eligible) {
            return false;
        }
        if (!core) {
            return true;
        }
        return !(row.optBoolean("isReserved", row.optBoolean("is_reserved", false)));
    }

    private static boolean matches(String id, JSONObject row, String search, String cardSet,
                                   String type, Integer minKredits, Integer maxKredits) {
        if (row == null) {
            return false;
        }
        if (cardSet != null && cardSet.length() > 0
                && !cardSet.equalsIgnoreCase(cardSetOf(row))) {
            return false;
        }
        if (type != null && type.length() > 0 && !type.equalsIgnoreCase(typeOf(row))) {
            return false;
        }
        if (minKredits != null || maxKredits != null) {
            if (!row.has("kredits")) {
                return false;
            }
            int kredits = row.optInt("kredits", -1);
            if (minKredits != null && kredits < minKredits.intValue()) {
                return false;
            }
            if (maxKredits != null && kredits > maxKredits.intValue()) {
                return false;
            }
        }
        if (search == null || search.length() == 0) {
            return true;
        }
        String lower = search.toLowerCase();
        return id.toLowerCase().contains(lower)
                || row.optString("title", "").toLowerCase().contains(lower)
                || row.optString("name", "").toLowerCase().contains(lower);
    }

    private static JSONObject deepCopy(JSONObject source) {
        if (source == null) {
            return null;
        }
        try {
            return new JSONObject(source.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static void putQuietly(JSONObject target, String key, Object value) {
        try {
            target.put(key, value);
        } catch (Exception ignored) {
        }
    }
}
