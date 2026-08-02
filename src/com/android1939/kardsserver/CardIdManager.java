package com.android1939.kardsserver;

import org.json.JSONArray;
import org.json.JSONObject;

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
import java.util.List;
import java.util.Locale;
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
 */
public final class CardIdManager {
    private final AssetStore assets;
    private final File dataDir;
    private JSONArray cards;
    private JSONObject decks;

    public CardIdManager(AssetStore assets) {
        this.assets = assets;
        this.dataDir = assets.dataDir();
    }

    // ==================== 读取 ====================

    /** 重新从磁盘加载（未保存的修改会丢失）。 */
    public synchronized void reload() throws Exception {
        cards = loadCards();
        decks = loadDecks();
    }

    private JSONArray loadCards() throws Exception {
        File disk = new File(dataDir, "library.json");
        if (disk.exists()) {
            JSONObject obj = new JSONObject(readFile(disk));
            JSONArray arr = obj.optJSONArray("cards");
            return arr == null ? new JSONArray() : arr;
        }
        JSONObject obj = assets.library(); // 回退内置资源（AssetStore 会复制到磁盘）
        JSONArray arr = obj.optJSONArray("cards");
        return arr == null ? new JSONArray() : new JSONArray(arr.toString()); // 深拷贝
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
        if (cards == null || decks == null) {
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
        ensureLoaded();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        JSONObject backups = new JSONObject();
        backups.put("library", writeWithBackup("library.json", "{\"cards\":" + cards.toString() + "}", stamp));
        backups.put("deck_code_ids", writeWithBackup("deck_code_ids.json", decks.toString(), stamp));
        assets.invalidate(); // 无需重启服务器，下次读取即为新数据
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("backups", backups);
        result.put("message", "已保存并生效");
        return result;
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
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
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
