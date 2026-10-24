package com.android1939.kardsserver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 卡牌/卡组 ID 管理器 — 移植自 klink_card_id_manager.py。
 * <p>
 * 管理两个数据文件（磁盘副本优先，内置 assets 回退）：
 * <ul>
 *   <li>{@code library.json} — 卡牌库：{@code {"cards":[{card_type,count,gold_card_count,id,recently_crafted_count}]}}</li>
 *   <li>{@code deck_code_ids.json} — 卡组代码映射：{@code {"01":{"card":"...","deck_code_id":"01","ID":1}}}</li>
 * </ul>
 * 字段顺序固定（card_type, count, gold_card_count, id, recently_crafted_count），
 * 保存时备份 {@code .yyyyMMdd_HHmmss.bak} + 原子写 {@code .tmp → rename}，
 * 序列化紧凑无缩进、中文不转义（org.json toString 满足）。
 * 保存后调用 {@code assets.invalidate()}，下次读取即生效，无需重启。
 * </p>
 * <p>
 * <b>未知字段会被保留</b>：客户端导出的 library.json 可能带 {@code player_id}、
 * {@code new_cards} 之类的顶层字段，以及每条卡牌上的 {@code player_id}。
 * 这些字段手机端用不到，但用户拿这份文件去别处用的时候得还在 ——
 * 所以保存时原样写回，不做裁剪。
 * </p>
 */
public final class CardIdManager {
    /** 导入模式：合并（文件里的条目覆盖同名项，其余现有条目保留）。 */
    public static final String MODE_MERGE = "merge";
    /** 导入模式：替换（文件即全部，现有条目全部丢弃）。 */
    public static final String MODE_REPLACE = "replace";

    private final AssetStore assets;
    private final File dataDir;
    private JSONArray cards;
    private JSONObject decks;
    /** library.json 里除 {@code cards} 之外的顶层字段（player_id / new_cards 等）。 */
    private JSONObject libraryExtras;

    /** 卡牌条目的已知字段（其余字段视为"用户数据"原样保留）。 */
    private static final Set<String> CARD_FIELDS = new HashSet<String>();
    /** 卡组条目的已知字段。 */
    private static final Set<String> DECK_FIELDS = new HashSet<String>();
    /** 报告里每一类最多列多少个样例，避免报告本身大到把界面撑爆。 */
    private static final int SAMPLE_LIMIT = 20;

    static {
        CARD_FIELDS.add("card_type");
        CARD_FIELDS.add("cardType");
        CARD_FIELDS.add("count");
        CARD_FIELDS.add("gold_card_count");
        CARD_FIELDS.add("id");
        CARD_FIELDS.add("recently_crafted_count");
        DECK_FIELDS.add("card");
        DECK_FIELDS.add("deck_code_id");
        DECK_FIELDS.add("ID");
    }

    public CardIdManager(AssetStore assets) {
        this.assets = assets;
        this.dataDir = assets.dataDir();
    }

    // ==================== 读取 ====================

    /** 重新从磁盘加载（未保存的修改会丢失）。 */
    public synchronized void reload() throws Exception {
        loadLibrary();
        decks = loadDecks();
    }

    private void loadLibrary() throws Exception {
        JSONObject obj;
        File disk = new File(dataDir, "library.json");
        if (disk.exists()) {
            obj = new JSONObject(readFile(disk));
        } else {
            obj = assets.library(); // 回退内置资源（AssetStore 会复制到磁盘）
        }
        JSONArray arr = obj.optJSONArray("cards");
        cards = arr == null ? new JSONArray() : new JSONArray(arr.toString()); // 深拷贝
        libraryExtras = new JSONObject();
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if ("cards".equals(key)) {
                continue;
            }
            libraryExtras.put(key, obj.get(key));
        }
    }

    private JSONObject loadDecks() throws Exception {
        File disk = new File(dataDir, "deck_code_ids.json");
        if (disk.exists()) {
            return new JSONObject(readFile(disk));
        }
        JSONObject obj = assets.deckCodeIds();
        return new JSONObject(obj.toString()); // 深拷贝
    }

    private void ensureLoaded() throws Exception {
        if (cards == null || decks == null || libraryExtras == null) {
            reload();
        }
    }

    // ==================== 查询 ====================

    /** 搜索卡牌：子串匹配（不区分大小写），匹配 card_type 或 id。 */
    public synchronized JSONObject listCards(String query, int limit) throws Exception {
        ensureLoaded();
        JSONObject result = new JSONObject();
        JSONArray out = new JSONArray();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.US);
        int matched = 0;
        for (int i = 0; i < cards.length(); i++) {
            JSONObject card = cards.optJSONObject(i);
            if (card == null) {
                continue;
            }
            String type = card.optString("card_type", "");
            String id = String.valueOf(card.optInt("id"));
            if (q.length() == 0 || type.toLowerCase(Locale.US).contains(q) || id.contains(q)) {
                out.put(card);
                matched++;
                if (limit > 0 && matched >= limit) {
                    break;
                }
            }
        }
        result.put("total", cards.length());
        result.put("matched", matched);
        result.put("cards", out);
        return result;
    }

    /** 搜索卡组：子串匹配（不区分大小写），匹配 code / card / ID。 */
    public synchronized JSONObject listDecks(String query, int limit) throws Exception {
        ensureLoaded();
        JSONObject result = new JSONObject();
        JSONArray out = new JSONArray();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.US);
        int matched = 0;
        Iterator<String> keys = decks.keys();
        while (keys.hasNext()) {
            String code = keys.next();
            JSONObject item = decks.optJSONObject(code);
            if (item == null) {
                continue;
            }
            String card = item.optString("card", "");
            String id = String.valueOf(item.optInt("ID"));
            if (q.length() == 0
                    || code.toLowerCase(Locale.US).contains(q)
                    || card.toLowerCase(Locale.US).contains(q)
                    || id.contains(q)) {
                JSONObject outItem = new JSONObject();
                outItem.put("code", code);
                outItem.put("card", card);
                outItem.put("ID", item.optInt("ID"));
                out.put(outItem);
                matched++;
                if (limit > 0 && matched >= limit) {
                    break;
                }
            }
        }
        result.put("total", decks.length());
        result.put("matched", matched);
        result.put("decks", out);
        return result;
    }

    // ==================== 修改 ====================

    /**
     * 添加/更新卡牌：移除所有 card_type 相同 <b>或</b> id 相同的旧项，
     * 追加新项（固定字段顺序）。
     */
    public synchronized void addOrUpdateCard(String cardType, int id, int count) throws Exception {
        ensureLoaded();
        if (cardType == null || cardType.trim().length() == 0) {
            throw new IllegalArgumentException("卡牌资源名（card_type）不能为空");
        }
        cardType = cardType.trim();
        if (count <= 0) {
            count = 4;
        }
        JSONArray next = new JSONArray();
        for (int i = 0; i < cards.length(); i++) {
            JSONObject card = cards.optJSONObject(i);
            if (card == null) {
                continue;
            }
            if (cardType.equals(card.optString("card_type")) || id == card.optInt("id")) {
                continue; // 移除旧项
            }
            next.put(card);
        }
        JSONObject created = new JSONObject();
        created.put("card_type", cardType);
        created.put("count", count);
        created.put("gold_card_count", 0);
        created.put("id", id);
        created.put("recently_crafted_count", 0);
        next.put(created);
        cards = next;
    }

    /** 删除卡牌：按 id 移除。 */
    public synchronized void deleteCards(Set<Integer> ids) throws Exception {
        ensureLoaded();
        if (ids == null || ids.isEmpty()) {
            return;
        }
        JSONArray next = new JSONArray();
        for (int i = 0; i < cards.length(); i++) {
            JSONObject card = cards.optJSONObject(i);
            if (card == null) {
                continue;
            }
            if (ids.contains(card.optInt("id"))) {
                continue;
            }
            next.put(card);
        }
        cards = next;
    }

    /** 添加/更新卡组：decks[code] = {card, deck_code_id:code, ID}（按键覆盖）。 */
    public synchronized void addOrUpdateDeck(String code, String card, int id) throws Exception {
        ensureLoaded();
        code = code == null ? "" : code.trim();
        card = card == null ? "" : card.trim();
        if (code.length() == 0 || card.length() == 0) {
            throw new IllegalArgumentException("卡组代码和对应卡牌不能为空");
        }
        JSONObject item = new JSONObject();
        item.put("card", card);
        item.put("deck_code_id", code);
        item.put("ID", id);
        decks.put(code, item);
    }

    /** 删除卡组：按 code 移除。 */
    public synchronized void deleteDecks(Set<String> codes) throws Exception {
        ensureLoaded();
        if (codes == null || codes.isEmpty()) {
            return;
        }
        for (String code : codes) {
            decks.remove(code);
        }
    }

    // ==================== 批量导入 ====================

    /**
     * 导入卡牌库（整份 library.json 或裸数组）。
     *
     * <p>解决的是"一张一张自己写太费劲"：客户端导出的整份收藏直接扔进来即可。</p>
     *
     * <p><b>id 自动回填</b>：客户端导出有时不带 {@code id}（改用 {@code player_id}），
     * 而 {@code id} 是卡牌ID管理器做匹配/删除/一致性检查的主键 —— 缺了就全变 0，
     * 删除会误删、检查会全量报错。这里按 {@code deck_code_ids.json} 的
     * 卡牌名→ID 映射反查回填，查不到才置 0（并在报告里如实说明有多少条）。</p>
     *
     * @param content 文件内容
     * @param mode    {@link #MODE_MERGE} 或 {@link #MODE_REPLACE}
     * @param apply   false = 只算预览（不落盘、不改内存）
     * @return 报告（snake_case，与 /admin/* 其它端点一致）
     */
    public synchronized JSONObject importLibrary(String content, String mode, boolean apply)
            throws Exception {
        ensureLoaded();
        boolean replace = MODE_REPLACE.equals(mode);
        JSONObject parsed = parseLibraryContent(content);
        JSONArray incoming = parsed.optJSONArray("cards");
        if (incoming == null) {
            throw new IllegalArgumentException("无法识别为卡牌库：文件里没有 cards 数组");
        }

        Map<String, Integer> idByCard = deckIdByCard();
        JSONArray normalizedList = new JSONArray();
        Map<String, JSONObject> incomingByType = new LinkedHashMap<String, JSONObject>();
        List<String> skippedSamples = new ArrayList<String>();
        int skipped = 0;
        int backfilled = 0;
        int unresolved = 0;
        int duplicates = 0;

        for (int i = 0; i < incoming.length(); i++) {
            JSONObject raw = incoming.optJSONObject(i);
            if (raw == null) {
                skipped++;
                addSample(skippedSamples, "#" + i + " 不是对象");
                continue;
            }
            String type = firstNonEmpty(raw, "card_type", "cardType");
            if (type.length() == 0) {
                skipped++;
                addSample(skippedSamples, "#" + i + " 缺少 card_type");
                continue;
            }
            int id = raw.optInt("id", 0);
            if (id <= 0) {
                Integer resolved = idByCard.get(type);
                if (resolved != null && resolved.intValue() > 0) {
                    id = resolved.intValue();
                    backfilled++;
                } else {
                    id = 0;
                    unresolved++;
                }
            }
            JSONObject entry = new JSONObject();
            entry.put("card_type", type);
            entry.put("count", positive(raw.optInt("count", 4), 4));
            entry.put("gold_card_count", nonNegative(raw.optInt("gold_card_count", 0)));
            entry.put("id", id);
            entry.put("recently_crafted_count", nonNegative(raw.optInt("recently_crafted_count", 0)));
            copyUnknownFields(raw, entry, CARD_FIELDS);
            normalizedList.put(entry);
            if (incomingByType.put(type, entry) != null) {
                duplicates++; // 文件内同名条目：合并时后者覆盖前者，替换时原样保留
            }
        }

        Map<String, JSONObject> current = new LinkedHashMap<String, JSONObject>();
        for (int i = 0; i < cards.length(); i++) {
            JSONObject card = cards.optJSONObject(i);
            if (card == null) {
                continue;
            }
            String type = card.optString("card_type", "");
            if (!current.containsKey(type)) {
                current.put(type, card);
            }
        }

        int added = 0;
        int updated = 0;
        int unchanged = 0;
        int removed = 0;
        List<String> addedSamples = new ArrayList<String>();
        List<String> updatedSamples = new ArrayList<String>();
        List<String> removedSamples = new ArrayList<String>();

        for (Map.Entry<String, JSONObject> entry : incomingByType.entrySet()) {
            JSONObject old = current.get(entry.getKey());
            if (old == null) {
                added++;
                addSample(addedSamples, entry.getKey());
            } else if (sameCardValues(old, entry.getValue())) {
                unchanged++;
            } else {
                updated++;
                addSample(updatedSamples, entry.getKey());
            }
        }
        if (replace) {
            for (String type : current.keySet()) {
                if (!incomingByType.containsKey(type)) {
                    removed++;
                    addSample(removedSamples, type);
                }
            }
        }

        JSONArray result = new JSONArray();
        int idReplaced = 0;
        List<String> idReplacedSamples = new ArrayList<String>();
        if (replace) {
            // 替换 = 文件说了算：条数、顺序、重复项都按文件来（不替用户"顺手去重"）
            for (int i = 0; i < normalizedList.length(); i++) {
                result.put(normalizedList.get(i));
            }
        } else {
            Set<Integer> incomingIds = new HashSet<Integer>();
            for (JSONObject entry : incomingByType.values()) {
                int id = entry.optInt("id", 0);
                if (id > 0) {
                    incomingIds.add(id);
                }
            }
            Set<String> used = new HashSet<String>();
            // 遍历原数组而不是上面的 card_type 映射：文件里本来就有同名重复项时
            // （客户端导出确实存在），合并不能顺手把它们抹掉 —— 只有被本次导入
            // 明确覆盖的那个 card_type 才会被收成一条。
            for (int i = 0; i < cards.length(); i++) {
                JSONObject old = cards.optJSONObject(i);
                if (old == null) {
                    continue;
                }
                String type = old.optString("card_type", "");
                JSONObject replacement = incomingByType.get(type);
                if (replacement != null) {
                    if (used.add(type)) {
                        result.put(replacement);
                    }
                    continue;
                }
                // 同一张卡换了资源名：id 撞车时以导入的为准（与单条 addOrUpdateCard 一致）
                int oldId = old.optInt("id", 0);
                if (oldId > 0 && incomingIds.contains(oldId)) {
                    idReplaced++;
                    addSample(idReplacedSamples, type);
                    continue;
                }
                result.put(old);
            }
            for (Map.Entry<String, JSONObject> entry : incomingByType.entrySet()) {
                if (!used.contains(entry.getKey())) {
                    result.put(entry.getValue());
                }
            }
        }

        JSONObject report = new JSONObject();
        report.put("target", "library");
        report.put("target_label", "卡牌库 library.json");
        report.put("mode", replace ? MODE_REPLACE : MODE_MERGE);
        report.put("applied", apply);
        JSONObject summary = new JSONObject();
        summary.put("before", cards.length());
        summary.put("after", result.length());
        summary.put("total", incoming.length());
        summary.put("skipped", skipped);
        summary.put("duplicates", duplicates);
        summary.put("added", added);
        summary.put("updated", updated);
        summary.put("unchanged", unchanged);
        summary.put("removed", removed);
        summary.put("id_backfilled", backfilled);
        summary.put("id_unresolved", unresolved);
        summary.put("id_replaced", idReplaced);
        report.put("summary", summary);
        JSONObject samples = new JSONObject();
        samples.put("added", new JSONArray(addedSamples));
        samples.put("updated", new JSONArray(updatedSamples));
        samples.put("removed", new JSONArray(removedSamples));
        samples.put("skipped", new JSONArray(skippedSamples));
        samples.put("id_replaced", new JSONArray(idReplacedSamples));
        report.put("samples", samples);

        JSONArray warnings = new JSONArray();
        if (unresolved > 0) {
            warnings.put(unresolved + " 张卡牌无法从 deck_code_ids.json 反查 id，已置 0（不影响开包，但按 id 删除会命中它们）");
        }
        if (skipped > 0) {
            warnings.put(skipped + " 条记录缺少 card_type 或格式不对，已跳过");
        }
        if (duplicates > 0) {
            warnings.put(replace
                    ? "文件内有 " + duplicates + " 条同名卡牌，替换模式下按原样保留"
                    : "文件内有 " + duplicates + " 条同名卡牌，合并模式下后者覆盖前者");
        }
        report.put("warnings", warnings);

        if (apply) {
            cards = result;
            if (replace) {
                libraryExtras = extraFieldsOf(parsed, "cards");
            } else {
                mergeExtras(parsed);
            }
            report.put("backups", saveFiles(true, false).optJSONObject("backups"));
        }
        return report;
    }

    /**
     * 导入卡组代码表（整份 deck_code_ids.json）。
     *
     * <p>缺 {@code ID} 时按 {@code card} 去卡牌库里反查（与导入卡牌库时反向的那套映射）。</p>
     */
    public synchronized JSONObject importDecks(String content, String mode, boolean apply)
            throws Exception {
        ensureLoaded();
        boolean replace = MODE_REPLACE.equals(mode);
        JSONObject parsed = parseObject(content, "卡组代码表");
        JSONObject table = parsed;
        if (parsed.optJSONObject("deck_code_ids") != null) {
            table = parsed.optJSONObject("deck_code_ids"); // 容忍 {"deck_code_ids":{...}} 包装
        }

        Map<String, Integer> idByCard = libraryIdByCard();
        Map<String, JSONObject> incoming = new LinkedHashMap<String, JSONObject>();
        List<String> skippedSamples = new ArrayList<String>();
        int skipped = 0;
        int backfilled = 0;
        int unresolved = 0;

        Iterator<String> keys = table.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject raw = table.optJSONObject(key);
            if (raw == null) {
                skipped++;
                addSample(skippedSamples, key + " 不是对象");
                continue;
            }
            String code = firstNonEmpty(raw, "deck_code_id", "code");
            if (code.length() == 0) {
                code = key;
            }
            String card = raw.optString("card", "").trim();
            if (card.length() == 0) {
                skipped++;
                addSample(skippedSamples, key + " 缺少 card");
                continue;
            }
            int id = raw.optInt("ID", 0);
            if (id <= 0) {
                Integer resolved = idByCard.get(card);
                if (resolved != null && resolved.intValue() > 0) {
                    id = resolved.intValue();
                    backfilled++;
                } else {
                    unresolved++;
                }
            }
            JSONObject entry = new JSONObject();
            entry.put("card", card);
            entry.put("deck_code_id", code);
            entry.put("ID", id);
            copyUnknownFields(raw, entry, DECK_FIELDS);
            incoming.put(code, entry);
        }

        int added = 0;
        int updated = 0;
        int unchanged = 0;
        int removed = 0;
        List<String> addedSamples = new ArrayList<String>();
        List<String> updatedSamples = new ArrayList<String>();
        List<String> removedSamples = new ArrayList<String>();
        for (Map.Entry<String, JSONObject> entry : incoming.entrySet()) {
            JSONObject old = decks.optJSONObject(entry.getKey());
            if (old == null) {
                added++;
                addSample(addedSamples, entry.getKey());
            } else if (sameDeckValues(old, entry.getValue())) {
                unchanged++;
            } else {
                updated++;
                addSample(updatedSamples, entry.getKey());
            }
        }
        if (replace) {
            Iterator<String> oldKeys = decks.keys();
            while (oldKeys.hasNext()) {
                String code = oldKeys.next();
                if (!incoming.containsKey(code)) {
                    removed++;
                    addSample(removedSamples, code);
                }
            }
        }

        JSONObject result = new JSONObject();
        if (replace) {
            for (Map.Entry<String, JSONObject> entry : incoming.entrySet()) {
                result.put(entry.getKey(), entry.getValue());
            }
        } else {
            Iterator<String> oldKeys = decks.keys();
            while (oldKeys.hasNext()) {
                String code = oldKeys.next();
                JSONObject replacement = incoming.get(code);
                result.put(code, replacement != null ? replacement : decks.optJSONObject(code));
            }
            for (Map.Entry<String, JSONObject> entry : incoming.entrySet()) {
                if (decks.optJSONObject(entry.getKey()) == null) {
                    result.put(entry.getKey(), entry.getValue());
                }
            }
        }

        JSONObject report = new JSONObject();
        report.put("target", "decks");
        report.put("target_label", "卡组代码 deck_code_ids.json");
        report.put("mode", replace ? MODE_REPLACE : MODE_MERGE);
        report.put("applied", apply);
        JSONObject summary = new JSONObject();
        summary.put("before", decks.length());
        summary.put("after", result.length());
        summary.put("total", table.length());
        summary.put("skipped", skipped);
        summary.put("duplicates", 0);
        summary.put("added", added);
        summary.put("updated", updated);
        summary.put("unchanged", unchanged);
        summary.put("removed", removed);
        summary.put("id_backfilled", backfilled);
        summary.put("id_unresolved", unresolved);
        summary.put("id_replaced", 0);
        report.put("summary", summary);
        JSONObject samples = new JSONObject();
        samples.put("added", new JSONArray(addedSamples));
        samples.put("updated", new JSONArray(updatedSamples));
        samples.put("removed", new JSONArray(removedSamples));
        samples.put("skipped", new JSONArray(skippedSamples));
        report.put("samples", samples);
        JSONArray warnings = new JSONArray();
        if (skipped > 0) {
            warnings.put(skipped + " 条记录缺少 card 字段，已跳过");
        }
        report.put("warnings", warnings);

        if (apply) {
            decks = result;
            report.put("backups", saveFiles(false, true).optJSONObject("backups"));
        }
        return report;
    }

    // ==================== 一致性检查 ====================

    /**
     * 一致性检查：
     * ① 卡牌 id 重复；
     * ② 卡组 card 不在 card_type 集合中（大小写敏感）。
     * 注意：内置数据本身存在历史遗留的悬空引用，不影响游戏运行。
     */
    public synchronized JSONObject checkConsistency() throws Exception {
        ensureLoaded();
        List<String> errors = new ArrayList<String>();
        // ① id 重复
        Set<Integer> seen = new HashSet<Integer>();
        Set<Integer> duplicates = new HashSet<Integer>();
        for (int i = 0; i < cards.length(); i++) {
            JSONObject card = cards.optJSONObject(i);
            if (card == null) {
                continue;
            }
            int id = card.optInt("id");
            if (!seen.add(id)) {
                duplicates.add(id);
            }
        }
        for (int id : duplicates) {
            errors.add("卡牌 ID 重复：" + id);
        }
        // ② 悬空引用（大小写敏感）
        Set<String> types = new HashSet<String>();
        for (int i = 0; i < cards.length(); i++) {
            JSONObject card = cards.optJSONObject(i);
            if (card != null) {
                types.add(card.optString("card_type", ""));
            }
        }
        Iterator<String> keys = decks.keys();
        while (keys.hasNext()) {
            String code = keys.next();
            JSONObject item = decks.optJSONObject(code);
            if (item == null) {
                continue;
            }
            String card = item.optString("card", "");
            if (card.length() > 0 && !types.contains(card)) {
                errors.add(code + " 指向不存在卡牌：" + card);
            }
        }
        JSONObject result = new JSONObject();
        result.put("ok", errors.isEmpty());
        result.put("error_count", errors.size());
        result.put("errors", new JSONArray(errors));
        return result;
    }

    // ==================== 保存 ====================

    /**
     * 保存全部修改：备份原文件为 {@code .yyyyMMdd_HHmmss.bak}，
     * 原子写 {@code .tmp → rename}，最后使服务器缓存失效（立即生效）。
     */
    public synchronized JSONObject saveAll() throws Exception {
        return saveFiles(true, true);
    }

    private synchronized JSONObject saveFiles(boolean saveLibrary, boolean saveDecks)
            throws Exception {
        ensureLoaded();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        JSONObject backups = new JSONObject();
        if (saveLibrary) {
            backups.put("library", writeWithBackup("library.json", libraryText(), stamp));
        }
        if (saveDecks) {
            backups.put("deck_code_ids", writeWithBackup("deck_code_ids.json", decks.toString(), stamp));
        }
        assets.invalidate(); // 无需重启服务器，下次读取即为新数据
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("backups", backups);
        result.put("message", "已保存并生效");
        return result;
    }

    /**
     * 序列化 library.json：{@code {"cards":[...]，其余顶层字段原样跟在后面}}。
     *
     * <p>手工拼 JSON 而不是用 JSONObject.toString()，是为了让 {@code cards}
     * 永远是第一个键 —— 这份文件用户会拿眼睛看、也会拿去跟客户端导出对比。</p>
     */
    private String libraryText() {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\"cards\":").append(cards.toString());
        if (libraryExtras != null) {
            Iterator<String> keys = libraryExtras.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                sb.append(',').append(JSONObject.quote(key)).append(':')
                        .append(jsonValue(libraryExtras.opt(key)));
            }
        }
        return sb.append('}').toString();
    }

    /** 把一个已解析的 JSON 值写回成 JSON 文本（字符串要加引号）。 */
    private static String jsonValue(Object value) {
        if (value == null || JSONObject.NULL.equals(value)) {
            return "null";
        }
        if (value instanceof String) {
            return JSONObject.quote((String) value);
        }
        return value.toString();
    }

    private String writeWithBackup(String fileName, String content, String stamp) throws Exception {
        File target = new File(dataDir, fileName);
        if (!target.exists()) {
            // 磁盘副本不存在则先触发内置回退复制，保证有可备份的原件
            if ("library.json".equals(fileName)) {
                assets.library();
            } else {
                assets.deckCodeIds();
            }
        }
        File backup = new File(dataDir, fileName + "." + stamp + ".bak");
        copyFile(target, backup);
        File tmp = new File(dataDir, fileName + ".tmp");
        OutputStream out = new FileOutputStream(tmp);
        try {
            out.write(content.getBytes("UTF-8"));
        } finally {
            out.close();
        }
        if (!tmp.renameTo(target)) {
            // rename 失败（极少见）时直接覆盖写目标
            OutputStream direct = new FileOutputStream(target);
            try {
                direct.write(content.getBytes("UTF-8"));
            } finally {
                direct.close();
            }
            if (!tmp.delete()) {
                tmp.deleteOnExit();
            }
        }
        return backup.getName();
    }

    // ==================== 内部工具 ====================

    /** 卡牌名 → 数字 ID（取自 deck_code_ids.json，用于回填 library.json 缺失的 id）。 */
    private Map<String, Integer> deckIdByCard() {
        Map<String, Integer> map = new LinkedHashMap<String, Integer>();
        Iterator<String> keys = decks.keys();
        while (keys.hasNext()) {
            JSONObject item = decks.optJSONObject(keys.next());
            if (item == null) {
                continue;
            }
            String card = item.optString("card", "");
            int id = item.optInt("ID", 0);
            if (card.length() > 0 && id > 0 && !map.containsKey(card)) {
                map.put(card, id);
            }
        }
        return map;
    }

    /** 卡牌名 → 数字 ID（取自 library.json，用于回填 deck_code_ids.json 缺失的 ID）。 */
    private Map<String, Integer> libraryIdByCard() {
        Map<String, Integer> map = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < cards.length(); i++) {
            JSONObject card = cards.optJSONObject(i);
            if (card == null) {
                continue;
            }
            String type = card.optString("card_type", "");
            int id = card.optInt("id", 0);
            if (type.length() > 0 && id > 0 && !map.containsKey(type)) {
                map.put(type, id);
            }
        }
        return map;
    }

    /** library.json 内容 → 统一成 {"cards":[...]} 形态（parseObject 已把裸数组包好）。 */
    private static JSONObject parseLibraryContent(String content) {
        return parseObject(content, "卡牌库");
    }

    private static JSONObject parseObject(String content, String what) {
        if (content == null) {
            throw new IllegalArgumentException(what + "内容为空");
        }
        String text = content.trim();
        if (text.length() == 0) {
            throw new IllegalArgumentException(what + "内容为空");
        }
        if (text.charAt(0) == '[') {
            // 裸数组：包一层再交给调用方按 cards 取
            JSONObject wrapper = new JSONObject();
            try {
                wrapper.put("cards", new JSONArray(text));
                wrapper.put("_bare", true);
            } catch (Exception e) {
                throw new IllegalArgumentException(what + "不是合法 JSON：" + e.getMessage());
            }
            return wrapper;
        }
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(what + "不是合法 JSON：" + e.getMessage());
        }
    }

    private static boolean sameCardValues(JSONObject a, JSONObject b) {
        return a.optInt("count", 0) == b.optInt("count", 0)
                && a.optInt("gold_card_count", 0) == b.optInt("gold_card_count", 0)
                && a.optInt("id", 0) == b.optInt("id", 0)
                && a.optInt("recently_crafted_count", 0) == b.optInt("recently_crafted_count", 0);
    }

    private static boolean sameDeckValues(JSONObject a, JSONObject b) {
        return a.optString("card", "").equals(b.optString("card", ""))
                && a.optInt("ID", 0) == b.optInt("ID", 0);
    }

    private static String firstNonEmpty(JSONObject obj, String primary, String fallback) {
        String value = obj.optString(primary, "").trim();
        if (value.length() > 0) {
            return value;
        }
        return fallback == null ? "" : obj.optString(fallback, "").trim();
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static int nonNegative(int value) {
        return value > 0 ? value : 0;
    }

    private static void copyUnknownFields(JSONObject from, JSONObject to, Set<String> known) throws Exception {
        Iterator<String> keys = from.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (known.contains(key)) {
                continue;
            }
            to.put(key, from.opt(key));
        }
    }

    private static JSONObject extraFieldsOf(JSONObject source, String exclude) throws Exception {
        JSONObject extras = new JSONObject();
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (exclude.equals(key) || "_bare".equals(key)) {
                continue;
            }
            extras.put(key, source.opt(key));
        }
        return extras;
    }

    private void mergeExtras(JSONObject source) throws Exception {
        if (libraryExtras == null) {
            libraryExtras = new JSONObject();
        }
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if ("cards".equals(key) || "_bare".equals(key)) {
                continue;
            }
            libraryExtras.put(key, source.opt(key));
        }
    }

    private static void addSample(List<String> samples, String value) {
        if (samples.size() < SAMPLE_LIMIT) {
            samples.add(value);
        }
    }

    private static void copyFile(File from, File to) throws Exception {
        InputStream in = new FileInputStream(from);
        try {
            OutputStream out = new FileOutputStream(to);
            try {
                byte[] buffer = new byte[8192];
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

    private static String readFile(File file) throws Exception {
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }
}
