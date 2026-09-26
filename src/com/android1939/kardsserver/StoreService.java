package com.android1939.kardsserver;

import com.android1939.kardsserver.model.UserRecord;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * 商店：配置读取、商品列表下发与购买结算（移植自桌面端 fyserver 的
 * {@code StoreConfigService} + {@code PlayerEndpoints.BuildStoreResponse/BuyOfferAsync}）。
 *
 * <h3>货币语义（与 NestJS 原实现一致，别改）</h3>
 * {@code transactionType}：1 = 扣金币，3 = 扣钻石，0/2 不扣游戏内货币
 * （2 是 Xsolla 等外部支付渠道，支付由外部完成，服务端只负责发货）。
 *
 * <h3>限购</h3>
 * {@code limit > 0} 或在参考限购名单里的商品，购买次数记在
 * {@code users.purchased_offers_json}；默认上限 1 次。
 *
 * <h3>礼包过滤</h3>
 * 第 1 组里名字含 bundle / weekend / 2_for_1 / gold_edition，或含多个奖励项的
 * 商品视为"礼包"，不下发也不可购买 —— 这是上游对齐真实客户端的取舍，照搬。
 */
public final class StoreService {

    private static final String ASSET_PATH = "kards-server/store.json";
    private static final String DISK_NAME = "store.json";

    /** 上游参考实现里的限购商品 ID。 */
    private static final Set<Integer> REFERENCE_LIMITED_OFFERS = new HashSet<Integer>();

    static {
        REFERENCE_LIMITED_OFFERS.add(6715394);
        REFERENCE_LIMITED_OFFERS.add(6754571);
        REFERENCE_LIMITED_OFFERS.add(6762899);
        REFERENCE_LIMITED_OFFERS.add(6762900);
    }

    /** 支持的奖励类型。 */
    private static final Set<String> SUPPORTED_REWARDS = new HashSet<String>();

    static {
        SUPPORTED_REWARDS.add("gold");
        SUPPORTED_REWARDS.add("diamonds");
        SUPPORTED_REWARDS.add("pack");
        SUPPORTED_REWARDS.add("card");
        SUPPORTED_REWARDS.add("draft");
        SUPPORTED_REWARDS.add("medkit");
        SUPPORTED_REWARDS.add("prop");
        SUPPORTED_REWARDS.add("alt_art");
        SUPPORTED_REWARDS.add("avatar");
        SUPPORTED_REWARDS.add("cardback");
        SUPPORTED_REWARDS.add("emote");
        SUPPORTED_REWARDS.add("deck");
        SUPPORTED_REWARDS.add("equipment");
        SUPPORTED_REWARDS.add("token");
    }

    /** 需要 name 字段的奖励类型。 */
    private static final Set<String> NEEDS_NAME = new HashSet<String>();

    static {
        NEEDS_NAME.add("card");
        NEEDS_NAME.add("prop");
        NEEDS_NAME.add("alt_art");
        NEEDS_NAME.add("avatar");
        NEEDS_NAME.add("cardback");
        NEEDS_NAME.add("emote");
        NEEDS_NAME.add("deck");
        NEEDS_NAME.add("equipment");
        NEEDS_NAME.add("token");
    }

    private final AssetStore assets;
    private final PlayerCards playerCards;
    private final com.android1939.kardsserver.db.KardsDatabase database;
    private JSONObject config;

    public StoreService(com.android1939.kardsserver.db.KardsDatabase database, AssetStore assets,
                        PlayerCards playerCards) {
        this.database = database;
        this.assets = assets;
        this.playerCards = playerCards;
    }

    /** 购买失败的业务异常。 */
    public static final class StoreException extends Exception {
        public StoreException(String message) {
            super(message);
        }
    }

    // ---------------------------------------------------------------- 配置

    public synchronized JSONObject storeConfig() throws Exception {
        if (config == null) {
            config = new JSONObject(readConfigJson());
        }
        return config;
    }

    public synchronized void reload() {
        config = null;
    }

    /** 后台保存配置（并留一份 .bak，与桌面端一致）。 */
    public synchronized void saveConfig(JSONObject next) throws Exception {
        File file = storeConfigFile();
        if (file.exists()) {
            try {
                copyFile(file, new File(file.getParentFile(), DISK_NAME + ".bak"));
            } catch (Exception ignored) {
            }
        }
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(next.toString().getBytes("UTF-8"));
        } finally {
            out.close();
        }
        config = next;
    }

    /** 配置文件的磁盘路径（与桌面端一致，放在磁盘副本目录里）。 */
    public File storeConfigFile() {
        return new File(assets.dataDir(), DISK_NAME);
    }

    private String readConfigJson() throws Exception {
        File disk = storeConfigFile();
        if (disk.exists()) {
            return readAll(disk);
        }
        File parent = disk.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        InputStream in = null;
        byte[] bytes;
        try {
            in = assets.context().getAssets().open(ASSET_PATH);
            ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            bytes = out.toByteArray();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
        try {
            FileOutputStream fileOut = new FileOutputStream(disk);
            try {
                fileOut.write(bytes);
            } finally {
                fileOut.close();
            }
        } catch (Exception ignored) {
        }
        return new String(bytes, "UTF-8");
    }

    private static String readAll(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(1024, file.length()));
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    private static void copyFile(File from, File to) throws Exception {
        FileInputStream in = new FileInputStream(from);
        try {
            FileOutputStream out = new FileOutputStream(to);
            try {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    // ---------------------------------------------------------------- 商品列表

    /**
     * 客户端 {@code GET /store/v2/} 的响应。
     * 结构对齐 {@code StoreResponse}：currency / groups / alwaysFeatured / message / status / ts。
     */
    public JSONObject buildStoreResponse(UserRecord user) throws Exception {
        JSONObject source = storeConfig();
        JSONObject purchased = user == null ? new JSONObject() : playerCards.readPurchasedOffers(user.id);

        JSONArray groups = new JSONArray();
        JSONArray sourceGroups = source.optJSONArray("groups");
        if (sourceGroups != null) {
            for (int i = 0; i < sourceGroups.length(); i++) {
                JSONObject group = sourceGroups.optJSONObject(i);
                if (group == null) {
                    continue;
                }
                groups.put(rewriteGroup(group, group.optInt("group", 0), purchased));
            }
        }

        JSONObject featuredSource = source.optJSONObject("alwaysFeatured");
        JSONObject featured = featuredSource == null
                ? new JSONObject()
                : rewriteGroup(featuredSource, featuredSource.optInt("group", 1), purchased);

        JSONObject response = new JSONObject();
        putQuietly(response, "currency", source.optString("currency", "USD"));
        putQuietly(response, "groups", groups);
        putQuietly(response, "alwaysFeatured", featured);
        putQuietly(response, "message", "Offers for " + isoTimestamp(System.currentTimeMillis()));
        putQuietly(response, "status", 200);
        putQuietly(response, "ts", System.currentTimeMillis() / 1000.0);
        return response;
    }

    /** 过滤礼包并写入 purchased 标记。 */
    private JSONObject rewriteGroup(JSONObject group, int groupNumber, JSONObject purchased) {
        JSONObject copy = deepCopy(group);
        if (copy == null) {
            copy = new JSONObject();
        }
        JSONArray offers = copy.optJSONArray("offers");
        JSONArray kept = new JSONArray();
        if (offers != null) {
            for (int i = 0; i < offers.length(); i++) {
                JSONObject offer = offers.optJSONObject(i);
                if (offer == null || isGiftOffer(groupNumber, offer)) {
                    continue;
                }
                int offerId = offer.optInt("offerId", 0);
                boolean bought = isLimitedOffer(offer)
                        && purchased.optInt(String.valueOf(offerId), 0) >= offerLimit(offer);
                putQuietly(offer, "purchased", bought);
                kept.put(offer);
            }
        }
        putQuietly(copy, "offers", kept);
        return copy;
    }

    // ---------------------------------------------------------------- 购买

    /**
     * 购买结算。
     *
     * @param body 客户端请求体：{@code {offerId, transactionType}}
     * @return 收据 JSON，结构对齐桌面端
     */
    public JSONObject buyOffer(UserRecord user, JSONObject body) throws Exception {
        if (user == null) {
            throw new StoreException("玩家不存在");
        }
        int offerId = body.optInt("offerId", 0);
        if (offerId == 0) {
            throw new StoreException("offerId is required");
        }
        int transactionType = body.optInt("transactionType", 0);
        if (transactionType != 0 && transactionType != 1 && transactionType != 2 && transactionType != 3) {
            throw new StoreException("Unsupported transaction type");
        }

        JSONObject offer = findActiveOffer(offerId);
        if (offer == null) {
            throw new StoreException("Offer not found or not currently available");
        }

        int priceGold = offer.optInt("gold", 0);
        int priceDiamonds = offer.optInt("diamonds", 0);
        int price = transactionType == 1 ? priceGold : transactionType == 3 ? priceDiamonds : 0;
        if (transactionType == 1 && priceGold <= 0) {
            throw new StoreException("This offer cannot be purchased with gold");
        }
        if (transactionType == 3 && priceDiamonds <= 0) {
            throw new StoreException("This offer cannot be purchased with diamonds");
        }

        JSONArray items = offer.optJSONArray("items");
        if (items == null || items.length() == 0) {
            throw new StoreException("This offer contains unsupported rewards");
        }
        long totalPacks = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            JSONObject data = item == null ? null : item.optJSONObject("data");
            int qty = item == null ? 0 : item.optInt("qty", 0);
            if (data == null || qty <= 0 || qty > 10000) {
                throw new StoreException("This offer contains unsupported rewards");
            }
            String itemType = data.optString("itemType", "");
            if (!SUPPORTED_REWARDS.contains(itemType)) {
                throw new StoreException("This offer contains unsupported rewards");
            }
            if ("pack".equals(itemType)) {
                if (data.optInt("cardCount", 0) <= 0 || data.optString("cardSet", "").length() == 0) {
                    throw new StoreException("Pack reward configuration is incomplete");
                }
                totalPacks += qty;
            }
            if (NEEDS_NAME.contains(itemType) && data.optString("name", "").trim().length() == 0) {
                throw new StoreException("A reward is missing its name");
            }
        }
        if (totalPacks > 1000) {
            throw new StoreException("Too many packs in one transaction");
        }

        // ---- 结算 ----
        JSONObject purchased = playerCards.readPurchasedOffers(user.id);
        if (isLimitedOffer(offer)
                && purchased.optInt(String.valueOf(offerId), 0) >= offerLimit(offer)) {
            throw new StoreException("Offer purchase limit reached");
        }
        long gold = user.gold;
        long diamonds = user.diamonds;
        if (transactionType == 1 && gold < price) {
            throw new StoreException("Insufficient gold");
        }
        if (transactionType == 3 && diamonds < price) {
            throw new StoreException("Insufficient diamonds");
        }
        if (transactionType == 1) {
            gold -= price;
        }
        if (transactionType == 3) {
            diamonds -= price;
        }

        List<JSONObject> packs = playerCards.readPacks(user.id);
        int nextPackId = 1;
        for (JSONObject pack : packs) {
            nextPackId = Math.max(nextPackId, pack.optInt("id", 0) + 1);
        }

        // 奖励发放
        List<JSONObject> userCards;
        {
            JSONObject collection = playerCards.readUserCards(user.id);
            userCards = new ArrayList<JSONObject>();
            JSONArray cards = collection.optJSONArray("cards");
            if (cards != null) {
                for (int i = 0; i < cards.length(); i++) {
                    JSONObject entry = cards.optJSONObject(i);
                    if (entry != null) {
                        userCards.add(entry);
                    }
                }
            }
        }

        long now = System.currentTimeMillis();
        // 收据里每个卡包奖励要带上本次真实分配的 id（上游写死 0 会让客户端拿到不存在的包）
        JSONArray receiptItems = new JSONArray();

        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            JSONObject data = item.optJSONObject("data");
            int qty = item.optInt("qty", 0);
            String itemType = data.optString("itemType", "");
            JSONArray grantedPackIds = new JSONArray();

            if ("gold".equals(itemType)) {
                gold += qty;
            } else if ("diamonds".equals(itemType)) {
                diamonds += qty;
            } else if ("pack".equals(itemType)) {
                String cardSet = qty == 0 ? "" : data.optInt("cardCount", 0) + "|" + data.optString("cardSet", "");
                for (int n = 0; n < qty; n++) {
                    JSONObject pack = new JSONObject();
                    putQuietly(pack, "id", nextPackId);
                    putQuietly(pack, "cardSet", cardSet);
                    putQuietly(pack, "createDate", isoTimestamp(now));
                    putQuietly(pack, "modifyDate", isoTimestamp(now));
                    putQuietly(pack, "playerId", user.id);
                    packs.add(pack);
                    grantedPackIds.put(nextPackId);
                    nextPackId++;
                }
            } else if ("card".equals(itemType)) {
                addUserCard(userCards, data, qty);
            } else {
                // draft / medkit / prop / token / 外观类：手机端暂不做专门计数，
                // 与桌面端一致地"接受但不持久化"，避免客户端因 400 而刷不出结果。
            }

            JSONObject receiptItem = deepCopy(item);
            if ("pack".equals(itemType) && receiptItem != null) {
                JSONObject receiptData = receiptItem.optJSONObject("data");
                if (receiptData != null) {
                    putQuietly(receiptData, "packIds", grantedPackIds);
                }
            }
            receiptItems.put(receiptItem == null ? new JSONObject() : receiptItem);
        }

        if (gold > Integer.MAX_VALUE || diamonds > Integer.MAX_VALUE) {
            throw new StoreException("Currency balance limit reached");
        }

        // 写回
        playerCards.writePacks(user.id, packs);
        playerCards.writeUserCards(user.id, userCards);
        if (isLimitedOffer(offer)) {
            putQuietly(purchased, String.valueOf(offerId),
                    purchased.optInt(String.valueOf(offerId), 0) + 1);
            playerCards.writePurchasedOffers(user.id, purchased);
        }
        android.content.ContentValues values = new android.content.ContentValues();
        values.put("gold", gold);
        values.put("diamonds", diamonds);
        user.gold = gold;
        user.diamonds = diamonds;
        database.updateUserFields(user.id, values);

        long receiptId = System.currentTimeMillis();
        JSONObject receipt = new JSONObject();
        putQuietly(receipt, "items", receiptItems);
        putQuietly(receipt, "offerName", offer.optString("offerName", ""));
        putQuietly(receipt, "receiptId", receiptId);

        JSONArray receipts = new JSONArray();
        receipts.put(receipt);

        JSONObject result = new JSONObject();
        putQuietly(result, "message", "OK");
        putQuietly(result, "status", 200);
        putQuietly(result, "newGold", gold);
        putQuietly(result, "newDiamonds", diamonds);
        putQuietly(result, "purchasedCount", 1);
        putQuietly(result, "receiptId", receiptId);
        putQuietly(result, "receipts", receipts);
        return result;
    }

    private void databaseUpdate(int userId, android.content.ContentValues values) {
        database.updateUserFields(userId, values);
    }



    /** 加卡到收藏（普通/金卡分开计数）。 */
    private static void addUserCard(List<JSONObject> userCards, JSONObject data, int quantity) {
        String name = data.optString("name", "").trim();
        if (name.length() == 0) {
            return;
        }
        boolean isGold = data.optBoolean("isGold", false)
                || data.optBoolean("isGoldCard", false)
                || data.optBoolean("goldCard", false);
        JSONObject card = null;
        for (JSONObject entry : userCards) {
            if (name.equalsIgnoreCase(entry.optString("cardType", ""))) {
                card = entry;
                break;
            }
        }
        if (card == null) {
            card = new JSONObject();
            putQuietly(card, "cardType", name);
            putQuietly(card, "count", 0);
            putQuietly(card, "goldCardCount", 0);
            putQuietly(card, "recentlyCraftedCount", 0);
            putQuietly(card, "recentlyCraftedGoldCount", 0);
            userCards.add(card);
        }
        if (isGold) {
            putQuietly(card, "goldCardCount", card.optInt("goldCardCount", 0) + quantity);
        } else {
            putQuietly(card, "count", card.optInt("count", 0) + quantity);
        }
    }

    // ---------------------------------------------------------------- 商品查找

    /** 在所有"当前生效"的分组里找商品（排除礼包）。 */
    private JSONObject findActiveOffer(int offerId) throws Exception {
        JSONObject source = storeConfig();
        long now = System.currentTimeMillis();

        JSONArray groups = source.optJSONArray("groups");
        if (groups != null) {
            for (int i = 0; i < groups.length(); i++) {
                JSONObject group = groups.optJSONObject(i);
                if (group == null || !isActive(group.optString("startDate", ""),
                        group.optString("endDate", ""), now)) {
                    continue;
                }
                JSONObject found = findOffer(group, group.optInt("group", 0), offerId);
                if (found != null) {
                    return found;
                }
            }
        }
        JSONObject featured = source.optJSONObject("alwaysFeatured");
        if (featured != null && isActive(featured.optString("startDate", ""),
                featured.optString("endDate", ""), now)) {
            return findOffer(featured, featured.optInt("group", 1), offerId);
        }
        return null;
    }

    private JSONObject findOffer(JSONObject group, int groupNumber, int offerId) {
        JSONArray offers = group.optJSONArray("offers");
        if (offers == null) {
            return null;
        }
        for (int i = 0; i < offers.length(); i++) {
            JSONObject offer = offers.optJSONObject(i);
            if (offer == null || offer.optInt("offerId", 0) != offerId) {
                continue;
            }
            if (isGiftOffer(groupNumber, offer)) {
                return null;
            }
            return offer;
        }
        return null;
    }

    // ---------------------------------------------------------------- 判定

    private static boolean isGiftOffer(int groupNumber, JSONObject offer) {
        if (groupNumber != 1) {
            return false;
        }
        String name = offer.optString("offerName", "").toLowerCase();
        JSONArray items = offer.optJSONArray("items");
        return (items != null && items.length() > 1)
                || name.contains("weekend")
                || name.contains("bundle")
                || name.contains("2_for_1")
                || name.contains("gold_edition");
    }

    private static boolean isLimitedOffer(JSONObject offer) {
        return offer.optInt("limit", 0) > 0
                || REFERENCE_LIMITED_OFFERS.contains(Integer.valueOf(offer.optInt("offerId", 0)));
    }

    private static int offerLimit(JSONObject offer) {
        int limit = offer.optInt("limit", 0);
        return limit > 0 ? limit : 1;
    }

    /** 起止时间为空视为始终生效；解析失败也放行（与桌面端一致）。 */
    private static boolean isActive(String start, String end, long nowMillis) {
        Long from = parseTime(start);
        Long until = parseTime(end);
        if (from != null && from.longValue() > nowMillis) {
            return false;
        }
        return until == null || nowMillis < until.longValue();
    }

    private static Long parseTime(String value) {
        if (value == null || value.trim().length() == 0) {
            return null;
        }
        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss",
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
                if (pattern.endsWith("'Z'")) {
                    format.setTimeZone(TimeZone.getTimeZone("UTC"));
                }
                return Long.valueOf(format.parse(value.trim()).getTime());
            } catch (ParseException ignored) {
            }
        }
        return null;
    }

    private static String isoTimestamp(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
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
