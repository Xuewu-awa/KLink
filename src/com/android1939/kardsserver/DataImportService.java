package com.android1939.kardsserver;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

/**
 * 数据文件导入服务 —— 「不用一条一条自己写」。
 *
 * <p>用户从客户端/别人那里拿到整份 JSON 数据文件，选中文件即可导入，
 * 不再需要对着界面一条一条录入。支持五种目标：</p>
 *
 * <table>
 *   <tr><th>目标</th><th>文件</th><th>语义</th></tr>
 *   <tr><td>{@code library}</td><td>library.json</td><td>结构化合并/替换，缺 id 自动回填</td></tr>
 *   <tr><td>{@code decks}</td><td>deck_code_ids.json</td><td>结构化合并/替换</td></tr>
 *   <tr><td>{@code catalog}</td><td>cards-from-fmodel.json</td><td>整文件替换（卡牌目录）</td></tr>
 *   <tr><td>{@code store}</td><td>store.json</td><td>整文件替换（商店配置）</td></tr>
 *   <tr><td>{@code items}</td><td>items.json</td><td>整文件替换（物品）</td></tr>
 * </table>
 *
 * <p><b>文件怎么进来</b>：Android 10+ 的文件选择器给的是 {@code content://} URI，
 * 服务端读不了，所以由前端（{@code KLink.pickDataFile()}）把选中的文件复制到
 * {@link #spoolDir(Context)} 临时目录，再把**路径**传给本服务。
 * 本服务只允许读那个目录里的文件 —— 否则就成了"任意路径读取"的洞。</p>
 *
 * <p><b>两步走</b>：{@code apply=false} 只出报告（改了会怎样），
 * {@code apply=true} 才真正落盘。落盘前一律备份 {@code .yyyyMMdd_HHmmss.bak}。</p>
 */
public final class DataImportService {
    public static final String TARGET_AUTO = "auto";
    public static final String TARGET_LIBRARY = "library";
    public static final String TARGET_DECKS = "decks";
    public static final String TARGET_CATALOG = "catalog";
    public static final String TARGET_STORE = "store";
    public static final String TARGET_ITEMS = "items";

    /** 单个文件上限：客户端导出的最大一份（卡牌目录）约 1.3MB，留足余量但不给内存炸弹的机会。 */
    private static final long MAX_BYTES = 32L * 1024 * 1024;

    private final Context context;
    private final AssetStore assets;
    private final CardIdManager cardIds;
    private final CardCatalog catalog;
    private final StoreService store;

    public DataImportService(Context context, AssetStore assets, CardIdManager cardIds,
                             CardCatalog catalog, StoreService store) {
        this.context = context.getApplicationContext();
        this.assets = assets;
        this.cardIds = cardIds;
        this.catalog = catalog;
        this.store = store;
    }

    /**
     * 前端复制待导入文件的临时目录。
     *
     * <p>前端与服务端共用这个路径（都从 Context 推出来），
     * 也是本服务唯一允许读取的目录。</p>
     */
    public static File spoolDir(Context context) {
        return new File(context.getCacheDir(), "import-spool");
    }

    /**
     * 执行（或预演）一次导入。
     *
     * @param body {@code {path, target, mode, apply}}
     * @return 报告（snake_case，与 /admin/* 其它端点一致）
     */
    public JSONObject run(JSONObject body) throws Exception {
        String path = body.optString("path", "").trim();
        if (path.length() == 0) {
            throw new IllegalArgumentException("缺少 path（待导入文件路径）");
        }
        File file = resolveSpoolFile(path);
        String content = readText(file);

        String target = normalizeTarget(body.optString("target", TARGET_AUTO));
        if (TARGET_AUTO.equals(target)) {
            target = detectTarget(file.getName(), content);
        }
        String mode = body.optString("mode", "").trim();
        if (mode.length() == 0) {
            mode = CardIdManager.MODE_MERGE;
        }
        boolean apply = body.optBoolean("apply", false);

        JSONObject report;
        if (TARGET_LIBRARY.equals(target)) {
            report = cardIds.importLibrary(content, mode, apply);
        } else if (TARGET_DECKS.equals(target)) {
            report = cardIds.importDecks(content, mode, apply);
        } else {
            report = replaceWholeFile(target, content, apply);
        }
        report.put("target", target);
        report.put("target_label", label(target));
        JSONObject source = new JSONObject();
        source.put("name", file.getName());
        source.put("bytes", file.length());
        report.put("source", source);

        if (apply) {
            // 导入成功后清掉临时文件（失败时留着，方便重试/排查）
            if (!file.delete()) {
                file.deleteOnExit();
            }
        }
        return report;
    }

    // ==================== 整文件替换 ====================

    private JSONObject replaceWholeFile(String target, String content, boolean apply)
            throws Exception {
        String diskName = diskName(target);
        String targetLabel = label(target);
        JSONObject parsed;
        try {
            parsed = new JSONObject(content.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException(targetLabel + "不是合法 JSON：" + e.getMessage());
        }

        JSONArray warnings = new JSONArray();
        int before = countEntries(target);
        int after = 0;
        if (TARGET_CATALOG.equals(target)) {
            after = countCatalogEntries(parsed);
            if (after <= 0) {
                throw new IllegalArgumentException("卡牌目录里没有任何有效条目（应形如 {\"card_xxx\":{\"name\":...}}）");
            }
        } else if (TARGET_STORE.equals(target)) {
            after = countStoreOffers(parsed);
            if (parsed.length() == 0) {
                throw new IllegalArgumentException("商店配置是空的");
            }
            if (parsed.optJSONObject("alwaysFeatured") == null) {
                warnings.put("没有 alwaysFeatured 字段，商店首页可能没有常驻推荐");
            }
        } else if (TARGET_ITEMS.equals(target)) {
            JSONArray items = parsed.optJSONArray("items");
            if (items == null) {
                throw new IllegalArgumentException("items.json 缺少 items 数组");
            }
            after = items.length();
        }
        warnings.put("整文件导入只能整体替换，不做合并");

        JSONObject summary = new JSONObject();
        summary.put("before", before);
        summary.put("after", after);
        summary.put("total", after);
        summary.put("replaced", 1);
        JSONObject report = new JSONObject();
        report.put("mode", CardIdManager.MODE_REPLACE);
        report.put("applied", false);
        report.put("summary", summary);
        report.put("warnings", warnings);
        report.put("samples", new JSONObject());

        if (apply) {
            File dir = assets.dataDir();
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("无法创建数据目录：" + dir);
            }
            File file = new File(dir, diskName);
            String backup = null;
            if (file.exists()) {
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                backup = diskName + "." + stamp + ".bak";
                copyFile(file, new File(dir, backup));
            }
            writeText(file, content.trim());
            invalidate(target);
            report.put("applied", true);
            JSONObject backups = new JSONObject();
            if (backup != null) {
                backups.put("data", backup);
            }
            report.put("backups", backups);
        }
        return report;
    }

    private void invalidate(String target) {
        if (TARGET_CATALOG.equals(target)) {
            catalog.reload();
        } else if (TARGET_STORE.equals(target)) {
            store.reload();
        } else {
            assets.invalidate();
        }
    }

    private int countEntries(String target) {
        try {
            if (TARGET_CATALOG.equals(target)) {
                return catalog.size();
            }
            if (TARGET_STORE.equals(target)) {
                return countStoreOffers(store.storeConfig());
            }
            JSONArray items = assets.items().optJSONArray("items");
            return items == null ? 0 : items.length();
        } catch (Exception e) {
            return 0; // 现有文件本身就是坏的，报 0 即可，不要因此挡住导入
        }
    }

    private static int countCatalogEntries(JSONObject parsed) {
        int count = 0;
        Iterator<String> keys = parsed.keys();
        while (keys.hasNext()) {
            JSONObject row = parsed.optJSONObject(keys.next());
            if (row != null && (row.has("name") || row.has("rarity"))) {
                count++;
            }
        }
        return count;
    }

    private static int countStoreOffers(JSONObject config) {
        int count = 0;
        Iterator<String> keys = config.keys();
        while (keys.hasNext()) {
            JSONObject group = config.optJSONObject(keys.next());
            if (group == null) {
                continue;
            }
            JSONArray offers = group.optJSONArray("offers");
            if (offers != null) {
                count += offers.length();
            }
        }
        return count;
    }

    private static String diskName(String target) {
        if (TARGET_CATALOG.equals(target)) {
            return "cards-from-fmodel.json";
        }
        if (TARGET_STORE.equals(target)) {
            return "store.json";
        }
        return "items.json";
    }

    // ==================== 识别 ====================

    private static String label(String target) {
        if (TARGET_LIBRARY.equals(target)) {
            return "卡牌库 library.json";
        }
        if (TARGET_DECKS.equals(target)) {
            return "卡组代码 deck_code_ids.json";
        }
        if (TARGET_CATALOG.equals(target)) {
            return "卡牌目录 cards-from-fmodel.json";
        }
        if (TARGET_STORE.equals(target)) {
            return "商店配置 store.json";
        }
        if (TARGET_ITEMS.equals(target)) {
            return "物品 items.json";
        }
        return target;
    }

    private static String normalizeTarget(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.US);
        if (value.length() == 0 || TARGET_AUTO.equals(value)) {
            return TARGET_AUTO;
        }
        if (TARGET_LIBRARY.equals(value) || "library.json".equals(value)) {
            return TARGET_LIBRARY;
        }
        if (TARGET_DECKS.equals(value) || "deck_code_ids".equals(value)
                || "deck_code_ids.json".equals(value) || "decks.json".equals(value)) {
            return TARGET_DECKS;
        }
        if (TARGET_CATALOG.equals(value) || "cards".equals(value)
                || "cards-from-fmodel.json".equals(value)) {
            return TARGET_CATALOG;
        }
        if (TARGET_STORE.equals(value) || "store.json".equals(value)) {
            return TARGET_STORE;
        }
        if (TARGET_ITEMS.equals(value) || "items.json".equals(value)) {
            return TARGET_ITEMS;
        }
        throw new IllegalArgumentException("未知的导入目标：" + raw);
    }

    /** 先按文件名，再按内容形态猜。猜不出来就让用户自己选。 */
    private static String detectTarget(String fileName, String content) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.US);
        if (name.endsWith("library.json")) {
            return TARGET_LIBRARY;
        }
        if (name.endsWith("deck_code_ids.json")) {
            return TARGET_DECKS;
        }
        if (name.endsWith("cards-from-fmodel.json")) {
            return TARGET_CATALOG;
        }
        if (name.endsWith("store.json")) {
            return TARGET_STORE;
        }
        if (name.endsWith("items.json")) {
            return TARGET_ITEMS;
        }

        String text = content.trim();
        if (text.length() == 0) {
            throw new IllegalArgumentException("文件是空的");
        }
        if (text.charAt(0) == '[') {
            return TARGET_LIBRARY; // 裸数组 = 卡牌列表
        }
        JSONObject obj;
        try {
            obj = new JSONObject(text);
        } catch (Exception e) {
            throw new IllegalArgumentException("不是合法 JSON：" + e.getMessage());
        }
        if (obj.optJSONArray("cards") != null) {
            return TARGET_LIBRARY;
        }
        if (obj.optJSONArray("items") != null) {
            return TARGET_ITEMS;
        }
        if (obj.optJSONObject("alwaysFeatured") != null
                || obj.optJSONObject("rotating") != null
                || obj.has("currency")) {
            return TARGET_STORE;
        }
        if (isDeckTable(obj)) {
            return TARGET_DECKS;
        }
        if (countCatalogEntries(obj) > 0) {
            return TARGET_CATALOG;
        }
        throw new IllegalArgumentException("认不出这是什么数据文件，请手动选择导入目标");
    }

    /** 所有值都是带 card 的对象 → 卡组代码表。 */
    private static boolean isDeckTable(JSONObject obj) {
        if (obj.length() == 0) {
            return false;
        }
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            JSONObject row = obj.optJSONObject(keys.next());
            if (row == null || row.optString("card", "").length() == 0) {
                return false;
            }
        }
        return true;
    }

    // ==================== 文件读写 ====================

    /** 只允许读临时目录里的文件（防任意路径读取）。 */
    private File resolveSpoolFile(String path) throws Exception {
        File dir = spoolDir(context).getCanonicalFile();
        File file = new File(path).getCanonicalFile();
        String prefix = dir.getPath() + File.separator;
        if (!file.getPath().startsWith(prefix)) {
            throw new IllegalArgumentException("只能导入应用缓存目录里的临时文件");
        }
        if (!file.isFile()) {
            throw new IllegalArgumentException("文件不存在或已被清理，请重新选择");
        }
        if (file.length() > MAX_BYTES) {
            throw new IllegalArgumentException("文件太大（上限 32MB）");
        }
        return file;
    }

    private static String readText(File file) throws Exception {
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(1024, file.length()));
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    private static void writeText(File file, String content) throws Exception {
        OutputStream out = new FileOutputStream(file);
        try {
            out.write(content.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    private static void copyFile(File from, File to) throws Exception {
        InputStream in = new FileInputStream(from);
        try {
            OutputStream out = new FileOutputStream(to);
            try {
                byte[] buffer = new byte[65536];
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
}
