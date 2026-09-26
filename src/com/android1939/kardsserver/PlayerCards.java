package com.android1939.kardsserver;

import com.android1939.kardsserver.db.KardsDatabase;
import com.android1939.kardsserver.model.UserRecord;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 真开包 / 万能牌合成（移植自桌面端 fyserver 的 {@code PlayerCardService}）。
 *
 * <h3>为什么是逐行移植</h3>
 * 抽取规则、保底、金卡判定、尘的换算全都在桌面端跑通了，手机端照译即可，
 * 不要重新设计 —— 两边开出同一张包的概率分布一致才谈得上"对齐"。
 *
 * <p>规则摘要：稀有度权重 {@code [0.66,0.22,0.08,0.04]}（Common→Unique）、
 * 收藏上限 {@code [4,3,2,1]}、金卡概率 15%（5 张的包除外）、
 * 尘按稀有度 {@code [5,10,50,100]} 且总量上限 1000、万能牌 15% 替换。</p>
 *
 * <h3>持久化</h3>
 * 卡包 / 收藏 / 已购商品存成 users 表的三个 TEXT 列（JSON），
 * 与桌面端 {@code User.Packs} / {@code User.UserCards} / {@code User.PurchasedOffers} 同形。
 */
public final class PlayerCards {

    private static final double[] RARITY_WEIGHTS = {0.66, 0.22, 0.08, 0.04};
    private static final int[] COLLECTION_LIMITS = {4, 3, 2, 1};
    private static final int[] DUST_BY_RARITY = {5, 10, 50, 100};
    private static final int DUST_CAP = 1000;
    private static final double GOLD_CHANCE = 0.15;
    private static final double WILDCARD_CHANCE = 0.15;

    private final KardsDatabase database;
    private final CardCatalog catalog;
    private final Random random = new Random();

    public PlayerCards(KardsDatabase database, CardCatalog catalog) {
        this.database = database;
        this.catalog = catalog;
    }

    /** 开包失败的业务异常，调用方据此返回 400 而不是 500。 */
    public static final class CardOperationException extends Exception {
        public CardOperationException(String message) {
            super(message);
        }
    }

    // ---------------------------------------------------------------- 卡包 / 收藏的读写

    public List<JSONObject> readPacks(int userId) {
        List<JSONObject> packs = new ArrayList<JSONObject>();
        JSONArray array = parseArray(database.getUserString(userId, "packs_json", null));
        if (array == null) {
            return packs;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) {
                packs.add(item);
            }
        }
        return packs;
    }

    public void writePacks(int userId, List<JSONObject> packs) {
        JSONArray array = new JSONArray();
        for (JSONObject pack : packs) {
            array.put(pack);
        }
        android.content.ContentValues values = new android.content.ContentValues();
        values.put("packs_json", array.toString());
        database.updateUserFields(userId, values);
    }

    /** 收藏内容：{@code {cards:[{cardType,count,goldCardCount,...}]}}。 */
    public JSONObject readUserCards(int userId) {
        JSONObject stored = parseObject(database.getUserString(userId, "user_cards_json", null));
        JSONObject result = new JSONObject();
        JSONArray cards = stored == null ? null : stored.optJSONArray("cards");
        putQuietly(result, "cards", cards == null ? new JSONArray() : cards);
        return result;
    }

    public void writeUserCards(int userId, List<JSONObject> cards) {
        JSONArray array = new JSONArray();
        for (JSONObject card : cards) {
            array.put(card);
        }
        JSONObject wrapper = new JSONObject();
        putQuietly(wrapper, "cards", array);
        android.content.ContentValues values = new android.content.ContentValues();
        values.put("user_cards_json", wrapper.toString());
        database.updateUserFields(userId, values);
    }

    public JSONObject readPurchasedOffers(int userId) {
        JSONObject stored = parseObject(database.getUserString(userId, "purchased_offers_json", null));
        return stored == null ? new JSONObject() : stored;
    }

    public void writePurchasedOffers(int userId, JSONObject offers) {
        android.content.ContentValues values = new android.content.ContentValues();
        values.put("purchased_offers_json", offers == null ? "{}" : offers.toString());
        database.updateUserFields(userId, values);
    }

    // ---------------------------------------------------------------- 新玩家初始收藏

    /**
     * 新号初始卡包与资源。
     *
     * <p>桌面端由 {@code CardCatalogService.ApplyInitialCollection} 决定收藏模式；
     * 手机端简化为：给几包 Core 卡包让玩家能立刻体验开包，收藏留空由开包填充。
     * 首次登录时调用一次（已有 packs_json 则不动）。</p>
     *
     * <p>同时给一笔初始金币/钻石 —— 否则新号进商店什么都买不起，
     * 而购买正是这套经济系统要验证的核心链路。原实现是硬编码下发 78/91，
     * 现在是真实持久化的初始值。</p>
     */
    public void ensureInitialPacks(UserRecord user, int startingPacks) {
        if (database.getUserString(user.id, "packs_json", null) != null) {
            return;
        }
        List<JSONObject> packs = new ArrayList<JSONObject>();
        long now = System.currentTimeMillis();
        int id = 1;
        for (int i = 0; i < Math.max(0, startingPacks); i++) {
            packs.add(newPack(id++, "5|Core", now, user.id));
        }
        writePacks(user.id, packs);

        android.content.ContentValues values = new android.content.ContentValues();
        values.put("gold", STARTING_GOLD);
        values.put("diamonds", STARTING_DIAMONDS);
        database.updateUserFields(user.id, values);
        user.gold = STARTING_GOLD;
        user.diamonds = STARTING_DIAMONDS;
    }

    /** 新号初始金币（够买几个 Core 包）。 */
    private static final int STARTING_GOLD = 500;
    /** 新号初始钻石。 */
    private static final int STARTING_DIAMONDS = 200;

    private static JSONObject newPack(int id, String cardSet, long timestamp, int playerId) {
        JSONObject pack = new JSONObject();
        putQuietly(pack, "id", id);
        putQuietly(pack, "cardSet", cardSet);
        putQuietly(pack, "createDate", TimeUtilIso(timestamp));
        putQuietly(pack, "modifyDate", TimeUtilIso(timestamp));
        putQuietly(pack, "playerId", playerId);
        return pack;
    }

    private static String TimeUtilIso(long millis) {
        return com.android1939.kardsserver.util.TimeUtil.formatIso(millis);
    }

    // ---------------------------------------------------------------- 开包

    /**
     * 开一个卡包。
     *
     * @return {@code {cards:[...], total_dust:N}}，与桌面端同形
     */
    public JSONObject openPack(UserRecord user, int packId) throws CardOperationException {
        List<JSONObject> packs = readPacks(user.id);
        int packIndex = -1;
        for (int i = 0; i < packs.size(); i++) {
            if (packs.get(i).optInt("id", -1) == packId) {
                packIndex = i;
                break;
            }
        }
        if (packIndex < 0) {
            throw new CardOperationException("No packs of this type");
        }

        JSONObject ownedPack = packs.get(packIndex);
        String cardSetSpec = ownedPack.optString("cardSet", "");
        int separator = cardSetSpec.indexOf('|');
        int cardCount = 0;
        if (separator > 0) {
            try {
                cardCount = Integer.parseInt(cardSetSpec.substring(0, separator).trim());
            } catch (Exception ignored) {
                cardCount = 0;
            }
        }
        if (cardCount < 1) {
            throw new CardOperationException("Invalid pack configuration");
        }
        final String setName = cardSetSpec.substring(separator + 1);
        boolean isCoreSet = "Core".equalsIgnoreCase(setName) || "Basic".equalsIgnoreCase(setName);

        Map<Integer, List<String>> pool = catalog.getPackPool(setName, isCoreSet);
        boolean anyCards = false;
        for (List<String> list : pool.values()) {
            if (!list.isEmpty()) {
                anyCards = true;
                break;
            }
        }
        if (!anyCards) {
            throw new CardOperationException("Card set \"" + setName + "\" not found");
        }

        // 收藏快照（大小写不敏感）
        Map<String, Integer> normal = new HashMap<String, Integer>();
        Map<String, Integer> gold = new HashMap<String, Integer>();
        List<JSONObject> userCards = ensureUserCards(user.id, normal, gold);

        // 可抽卡缓存：同一稀有度只算一次
        Map<Integer, List<String>> availableCache = new HashMap<Integer, List<String>>();

        // 抽卡
        List<Drawn> draws = new ArrayList<Drawn>(cardCount);
        for (int slot = 0; slot < cardCount; slot++) {
            int rarity = rollRarity();
            List<String> available = availableCards(rarity, pool, availableCache, normal, gold);
            while (available.isEmpty() && rarity > 0) {
                rarity--;
                available = availableCards(rarity, pool, availableCache, normal, gold);
            }
            if (available.isEmpty()) {
                // 收藏满了 —— 退回未过滤卡池
                available = pool.get(rarity);
                while ((available == null || available.isEmpty()) && rarity > 0) {
                    rarity--;
                    available = pool.get(rarity);
                }
            }
            if (available == null || available.isEmpty()) {
                throw new CardOperationException("No cards available for rarity "
                        + rarity + " in set " + setName);
            }
            String cardId = available.get(random.nextInt(available.size()));
            boolean isGold = cardCount != 5
                    && random.nextDouble() < GOLD_CHANCE
                    && goldCount(gold, cardId) < COLLECTION_LIMITS[rarity];
            draws.add(new Drawn(rarity, cardId, isGold, false));
        }

        applyGuarantees(draws, cardCount, pool, availableCache, normal, gold);

        for (int i = 0; i < draws.size(); i++) {
            if (cardCount != 5 && random.nextDouble() < WILDCARD_CHANCE) {
                Drawn draw = draws.get(i);
                draws.set(i, new Drawn(draw.rarity, CardCatalog.WILDCARDS[draw.rarity], false, true));
            }
        }

        // 结算：入库 + 折算尘
        long dust = clamp(database.getUserLong(user.id, "dust", 0L), 0, DUST_CAP);
        JSONArray resultCards = new JSONArray();
        int totalDust = 0;

        for (Drawn draw : draws) {
            int awardedDust = 0;
            if (draw.wildcard) {
                normal.put(draw.cardId, get(normal, draw.cardId) + 1);
            } else {
                int normalCount = get(normal, draw.cardId);
                int goldCount = goldCount(gold, draw.cardId);
                if (normalCount + goldCount < COLLECTION_LIMITS[draw.rarity]) {
                    if (draw.isGold && goldCount < COLLECTION_LIMITS[draw.rarity]) {
                        putIgnoreCase(gold, draw.cardId, goldCount + 1);
                    } else if (normalCount < COLLECTION_LIMITS[draw.rarity]) {
                        putIgnoreCase(normal, draw.cardId, normalCount + 1);
                    } else {
                        awardedDust = Math.min(DUST_BY_RARITY[draw.rarity], DUST_CAP - (int) dust);
                        if (awardedDust < 0) {
                            awardedDust = 0;
                        }
                        dust += awardedDust;
                    }
                } else {
                    awardedDust = Math.min(DUST_BY_RARITY[draw.rarity], DUST_CAP - (int) dust);
                    if (awardedDust < 0) {
                        awardedDust = 0;
                    }
                    dust += awardedDust;
                }
                totalDust += awardedDust;
            }

            JSONObject entry = new JSONObject();
            putQuietly(entry, "card_name", draw.cardId);
            putQuietly(entry, "dust", awardedDust);
            putQuietly(entry, "is_gold_card", draw.isGold);
            putQuietly(entry, "new_card", false);
            putQuietly(entry, "recycled", false);
            resultCards.put(entry);
        }

        // 回写收藏
        Set<String> allIds = new HashSet<String>();
        allIds.addAll(normal.keySet());
        allIds.addAll(gold.keySet());
        List<JSONObject> merged = new ArrayList<JSONObject>();
        for (String id : allIds) {
            int count = get(normal, id);
            boolean isWildcard = isWildcard(id);
            int goldCount = isWildcard ? 0 : goldCount(gold, id);
            if (count > 0 || goldCount > 0) {
                merged.add(newUserCard(id, count, goldCount));
            }
        }
        writeUserCards(user.id, merged);

        // 移除已开的包 + 回写尘
        packs.remove(packIndex);
        writePacks(user.id, packs);

        android.content.ContentValues values = new android.content.ContentValues();
        values.put("dust", dust);
        database.updateUserFields(user.id, values);

        JSONObject result = new JSONObject();
        putQuietly(result, "cards", resultCards);
        putQuietly(result, "total_dust", totalDust);
        return result;
    }

    // ---------------------------------------------------------------- 万能牌合成

    /** 用万能牌合成一张目标卡。 */
    public JSONObject craftFromWildcard(UserRecord user, String targetId, String wildcardId)
            throws CardOperationException {
        if (isBlank(targetId) || isBlank(wildcardId) || targetId.equals(wildcardId)) {
            throw new CardOperationException("Invalid wildcard craft value");
        }
        JSONObject target = catalog.getCard(targetId);
        JSONObject wildcard = catalog.getCard(wildcardId);
        String targetRarity = target == null ? "" : target.optString("rarity", "");
        String targetType = target == null ? "" : target.optString("type", "");
        String wildcardType = wildcard == null ? "" : wildcard.optString("type", "");
        if (target == null || wildcard == null
                || "wildcard".equalsIgnoreCase(targetType)
                || "location".equalsIgnoreCase(targetType)
                || targetId.toLowerCase().startsWith("card_location")
                || !"wildcard".equalsIgnoreCase(wildcardType)
                || targetRarity.length() == 0
                || !targetRarity.equals(wildcard.optString("rarity", ""))) {
            throw new CardOperationException("Invalid wildcard craft cards");
        }
        String wildcardFaction = wildcard.optString("faction", "");
        if (wildcardFaction.length() > 0
                && !wildcardFaction.equals(target.optString("faction", ""))) {
            throw new CardOperationException("Wildcard faction mismatch");
        }

        Map<String, Integer> normal = new HashMap<String, Integer>();
        Map<String, Integer> gold = new HashMap<String, Integer>();
        List<JSONObject> entries = ensureUserCards(user.id, normal, gold);

        JSONObject wildcardEntry = findCard(entries, wildcardId);
        if (wildcardEntry == null || wildcardEntry.optInt("count", 0) < 1) {
            throw new CardOperationException("Wildcard not owned");
        }
        int limit = COLLECTION_LIMITS[CardCatalog.rarityIndex(targetRarity)];
        JSONObject targetEntry = findCard(entries, targetId);
        if (targetEntry != null && targetEntry.optInt("count", 0) >= limit) {
            throw new CardOperationException("Card collection limit reached");
        }

        putQuietly(wildcardEntry, "count", wildcardEntry.optInt("count", 0) - 1);
        if (targetEntry == null) {
            targetEntry = newUserCard(targetId, 0, 0);
            entries.add(targetEntry);
        }
        putQuietly(targetEntry, "count", targetEntry.optInt("count", 0) + 1);

        List<JSONObject> kept = new ArrayList<JSONObject>();
        for (JSONObject entry : entries) {
            if (entry.optInt("count", 0) > 0 || entry.optInt("goldCardCount", 0) > 0) {
                kept.add(entry);
            }
        }
        writeUserCards(user.id, kept);

        JSONObject result = new JSONObject();
        putQuietly(result, "success", true);
        return result;
    }

    // ---------------------------------------------------------------- 内部

    /** 一张抽到的牌。 */
    private static final class Drawn {
        final int rarity;
        final String cardId;
        final boolean isGold;
        final boolean wildcard;

        Drawn(int rarity, String cardId, boolean isGold, boolean wildcard) {
            this.rarity = rarity;
            this.cardId = cardId;
            this.isGold = isGold;
            this.wildcard = wildcard;
        }
    }

    /** 读收藏并展开成大小写不敏感的映射。 */
    private List<JSONObject> ensureUserCards(int userId, Map<String, Integer> normal,
                                             Map<String, Integer> gold) {
        JSONObject collection = readUserCards(userId);
        JSONArray cards = collection.optJSONArray("cards");
        List<JSONObject> entries = new ArrayList<JSONObject>();
        if (cards != null) {
            for (int i = 0; i < cards.length(); i++) {
                JSONObject entry = cards.optJSONObject(i);
                if (entry == null) {
                    continue;
                }
                entries.add(entry);
                String cardType = entry.optString("cardType", "");
                if (cardType.length() == 0) {
                    continue;
                }
                // 同一张卡出现多行时保留最后一行（对齐 Nest 的 Map.set 语义）
                putIgnoreCase(normal, cardType, entry.optInt("count", 0));
                putIgnoreCase(gold, cardType, entry.optInt("goldCardCount", 0));
            }
        }
        return entries;
    }

    private List<String> availableCards(int rarity, Map<Integer, List<String>> pool,
                                        Map<Integer, List<String>> cache,
                                        Map<String, Integer> normal, Map<String, Integer> gold) {
        List<String> cached = cache.get(rarity);
        if (cached != null) {
            return cached;
        }
        List<String> result = new ArrayList<String>();
        List<String> candidates = pool.get(rarity);
        if (candidates != null) {
            for (String id : candidates) {
                if (isWildcard(id)
                        || get(normal, id) + goldCount(gold, id) < COLLECTION_LIMITS[rarity]) {
                    result.add(id);
                }
            }
        }
        cache.put(rarity, result);
        return result;
    }

    /** 5 张包至少 1 张 Uncommon；7 张包至少 1 张 Rare + 累计 3 张 Uncommon 以上。 */
    private void applyGuarantees(List<Drawn> cards, int cardCount, Map<Integer, List<String>> pool,
                                 Map<Integer, List<String>> cache,
                                 Map<String, Integer> normal, Map<String, Integer> gold) {
        if (cardCount == 5) {
            boolean allCommon = true;
            for (Drawn card : cards) {
                if (card.rarity != 0) {
                    allCommon = false;
                    break;
                }
            }
            if (allCommon) {
                int index = random.nextInt(cards.size());
                int rarity = (pool.get(1) != null && !pool.get(1).isEmpty()) ? 1 : 0;
                List<String> choices = pickChoices(rarity, pool, cache, normal, gold);
                if (!choices.isEmpty()) {
                    cards.set(index, new Drawn(rarity,
                            choices.get(random.nextInt(choices.size())), false, false));
                }
            }
        } else if (cardCount == 7) {
            int rare = -1;
            for (int i = 0; i < cards.size(); i++) {
                if (cards.get(i).rarity >= 2) {
                    rare = i;
                    break;
                }
            }
            if (rare < 0) {
                List<Integer> candidates = new ArrayList<Integer>();
                for (int i = 0; i < cards.size(); i++) {
                    if (cards.get(i).rarity < 2) {
                        candidates.add(i);
                    }
                }
                if (!candidates.isEmpty()) {
                    int index = candidates.get(random.nextInt(candidates.size()));
                    int rarity = (pool.get(2) != null && !pool.get(2).isEmpty()) ? 2
                            : (pool.get(1) != null && !pool.get(1).isEmpty()) ? 1 : 0;
                    List<String> choices = pickChoices(rarity, pool, cache, normal, gold);
                    if (!choices.isEmpty()) {
                        cards.set(index, new Drawn(rarity,
                                choices.get(random.nextInt(choices.size())), false, false));
                        for (int i = 0; i < cards.size(); i++) {
                            if (cards.get(i).rarity >= 2) {
                                rare = i;
                                break;
                            }
                        }
                    }
                }
            }
            int uncommonOthers = 0;
            for (int i = 0; i < cards.size(); i++) {
                if (cards.get(i).rarity >= 1) {
                    uncommonOthers++;
                }
            }
            if (rare >= 0) {
                uncommonOthers--;
            }
            if (uncommonOthers < 2) {
                List<Integer> zeroIndices = new ArrayList<Integer>();
                for (int i = 0; i < cards.size(); i++) {
                    if (i != rare && cards.get(i).rarity == 0) {
                        zeroIndices.add(i);
                    }
                }
                Collections.shuffle(zeroIndices, random);
                int need = Math.min(2 - uncommonOthers, zeroIndices.size());
                for (int k = 0; k < need; k++) {
                    int index = zeroIndices.get(k);
                    int rarity = (pool.get(1) != null && !pool.get(1).isEmpty()) ? 1 : 0;
                    List<String> choices = pickChoices(rarity, pool, cache, normal, gold);
                    if (!choices.isEmpty()) {
                        cards.set(index, new Drawn(rarity,
                                choices.get(random.nextInt(choices.size())), false, false));
                    }
                }
            }
        }
    }

    private List<String> pickChoices(int rarity, Map<Integer, List<String>> pool,
                                     Map<Integer, List<String>> cache,
                                     Map<String, Integer> normal, Map<String, Integer> gold) {
        List<String> available = availableCards(rarity, pool, cache, normal, gold);
        if (!available.isEmpty()) {
            return available;
        }
        List<String> fallback = pool.get(rarity);
        return fallback == null ? new ArrayList<String>() : fallback;
    }

    private int rollRarity() {
        double roll = random.nextDouble();
        double cumulative = 0;
        for (int rarity = 3; rarity >= 0; rarity--) {
            cumulative += RARITY_WEIGHTS[rarity];
            if (roll <= cumulative) {
                return rarity;
            }
        }
        return 0;
    }

    private static JSONObject newUserCard(String cardType, int count, int goldCardCount) {
        JSONObject card = new JSONObject();
        putQuietly(card, "cardType", cardType);
        putQuietly(card, "count", count);
        putQuietly(card, "goldCardCount", goldCardCount);
        putQuietly(card, "recentlyCraftedCount", 0);
        putQuietly(card, "recentlyCraftedGoldCount", 0);
        return card;
    }

    private static JSONObject findCard(List<JSONObject> entries, String cardType) {
        for (JSONObject entry : entries) {
            if (cardType.equalsIgnoreCase(entry.optString("cardType", ""))) {
                return entry;
            }
        }
        return null;
    }

    private static boolean isWildcard(String cardId) {
        for (String wildcard : CardCatalog.WILDCARDS) {
            if (wildcard.equalsIgnoreCase(cardId)) {
                return true;
            }
        }
        return false;
    }

    private static int get(Map<String, Integer> map, String key) {
        Integer value = map.get(key.toLowerCase());
        return value == null ? 0 : value.intValue();
    }

    /** 金卡数：万能牌不累计金卡。 */
    private static int goldCount(Map<String, Integer> gold, String cardId) {
        return isWildcard(cardId) ? 0 : get(gold, cardId);
    }

    private static void putIgnoreCase(Map<String, Integer> map, String key, int value) {
        map.put(key.toLowerCase(), Integer.valueOf(value));
    }

    private static long clamp(long value, long min, long max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().length() == 0;
    }

    private static JSONArray parseArray(String json) {
        if (json == null || json.trim().length() == 0) {
            return null;
        }
        try {
            return new JSONArray(json);
        } catch (Exception e) {
            return null;
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

    private static void putQuietly(JSONObject target, String key, Object value) {
        try {
            target.put(key, value);
        } catch (Exception ignored) {
        }
    }
}
