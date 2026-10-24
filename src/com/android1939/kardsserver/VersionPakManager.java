package com.android1939.kardsserver;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 版本补丁 pak 管理器 — 动态改版本号。
 * <p>
 * 原理：游戏通过补丁 pak 加载 {@code kards/Config/DefaultGame.ini}，
 * 其中 {@code ProjectVersion=} 后是明文版本号，等长替换即可改版本，游戏不校验内容 hash。
 * </p>
 * <ul>
 *   <li>模板：assets/version.pak 首次自动提取到 {@code filesDir/data/version.pak}（存在则不覆盖，用户可替换更新模板）；磁盘模板优先。</li>
 *   <li>输出：必须 {@code xxx_P.pak}（UE 只加载 _P 结尾），输出到游戏 Paks 目录，每次应用覆盖。</li>
 *   <li>本地模式：直接复制不改版本；远程/局域网模式：改写版本号。</li>
 *   <li>版本号持久化在 {@code filesDir/data/pak_version.txt}。</li>
 * </ul>
 */
public final class VersionPakManager {
    private static final String TEMPLATE_ASSET = "version.pak";
    private static final String VERSION_KEY = "ProjectVersion=";
    private static final String OUTPUT_NAME = "version_P.pak";

    /** 默认版本号：未手动设置版本时，远程/局域网模式用它改写补丁版本。 */
    public static final String DEFAULT_VERSION = "KLink 29452.29452";

    private final Context context;
    private final File templateFile;
    private final File outputDir;
    private final File versionStore;

    public VersionPakManager(Context context) {
        this.context = context;
        File filesDir = context.getFilesDir();
        this.templateFile = new File(filesDir, "data/version.pak");
        this.outputDir = new File(filesDir, "UnrealGame/kards/Engine/Content/Paks");
        this.versionStore = new File(filesDir, "data/pak_version.txt");
    }

    public VersionPakManager(Context context, File templateFile, File outputDir) {
        this.context = context;
        this.templateFile = templateFile;
        this.outputDir = outputDir;
        this.versionStore = new File(context.getFilesDir(), "data/pak_version.txt");
    }

    // ==================== 状态 ====================

    /**
     * 模板状态：模板是否存在、当前版本号、容量、输出路径、已保存版本号。
     */
    public JSONObject status() {
        JSONObject json = new JSONObject();
        try {
            File outputFile = new File(outputDir, OUTPUT_NAME);
            json.put("template_exists", templateFile.exists());
            json.put("template_path", templateFile.getAbsolutePath());
            json.put("output_path", outputFile.getAbsolutePath());
            json.put("output_exists", outputFile.exists());
            json.put("output_version", outputVersion());
            json.put("template_version", templateVersion());
            json.put("saved_version", readStoredVersion());
            json.put("default_version", DEFAULT_VERSION);
            json.put("error", "");
            if (templateFile.exists()) {
                byte[] data = readTemplate();
                int[] range = locateVersionRange(data);
                if (range == null) {
                    json.put("error", "模板中未找到 ProjectVersion=");
                } else {
                    json.put("capacity", range[2]);
                    String value = new String(data, range[0], range[1] - range[0], "ASCII").trim();
                    json.put("template_version", value);
                    json.put("default_version", defaultVersionFor(range[2], value));
                }
            } else {
                json.put("error", "模板不存在，请将 version.pak 放入 " + templateFile.getParent());
            }
        } catch (Exception e) {
            try {
                json.put("error", e.toString());
            } catch (Exception ignored) {
            }
        }
        return json;
    }

    /**
     * 模板里的版本号原文（未找到返回空串）。
     *
     * <p>注意返回**未去空格**的原文：pak 是等长替换，版本号短于容量时会右填空格，
     * 调用方要能判断出"写出去的到底是多少个字符"。</p>
     */
    public String templateVersion() {
        try {
            if (!templateFile.exists()) {
                return "";
            }
            return versionOf(readBytes(templateFile));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 已经装进游戏 Paks 目录的 {@code version_P.pak} 里的版本号原文（未找到返回空串）。
     *
     * <p>这是**客户端真正会读到**的那个值，比模板和保存值都准 ——
     * 服务器下发版本白名单时要按它来，否则客户端拿自己的版本去比对会报「版本错误」。</p>
     */
    public String outputVersion() {
        try {
            File output = new File(outputDir, OUTPUT_NAME);
            if (!output.exists()) {
                return "";
            }
            return versionOf(readBytes(output));
        } catch (Exception e) {
            return "";
        }
    }

    /** 面板里保存过的版本号（空串表示没保存过）。 */
    public String savedVersion() {
        return readStoredVersion();
    }

    private String versionOf(byte[] data) {
        int[] range = locateVersionRange(data);
        if (range == null) {
            return "";
        }
        try {
            return new String(data, range[0], range[1] - range[0], "ASCII");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 启动游戏前调用。
     *
     * @param mode 运行模式："local" 直接复制不改版本；"lan"/"remote" 改写版本号
     */
    public JSONObject applyBeforeLaunch(String mode) throws Exception {
        String version = readStoredVersion();
        // 远程/局域网模式一律改写；未设置版本时使用默认版本号（见 apply）
        boolean rewrite = !"local".equals(mode);
        return apply(version, rewrite);
    }

    /**
     * 应用版本补丁。
     *
     * @param version 目标版本号；null 或空时使用模板自身版本号（此时 rewrite 无效）
     * @param rewrite 是否改写版本号；false 表示原样复制
     */
    public JSONObject apply(String version, boolean rewrite) throws Exception {
        JSONObject result = new JSONObject();
        byte[] data = readTemplate();
        if (data == null) {
            throw new Exception("模板不存在，请将 version.pak 放入 " + templateFile.getParent());
        }
        String stored = null;
        if (version != null && version.trim().length() > 0) {
            version = version.trim();
            if (!isAscii(version)) {
                throw new IllegalArgumentException("版本号必须为纯 ASCII 字符");
            }
            stored = version;
        }
        int[] range = locateVersionRange(data);
        if (range == null) {
            throw new Exception("模板中未找到 ProjectVersion=");
        }
        int start = range[0];
        int end = range[1];
        int capacity = range[2];
        String current = new String(data, start, end - start, "ASCII").trim();

        if (rewrite) {
            if (stored == null) {
                stored = defaultVersionFor(capacity, current); // 未设置版本时用默认版本号
            }
            if (stored.length() > capacity) {
                throw new IllegalArgumentException("版本号过长（最大 " + capacity + " 字符）：" + stored);
            }
            // 等长替换：版本号 + 空格右填充到容量，其余字节原样（hash 不重算）
            StringBuilder padded = new StringBuilder(stored);
            while (padded.length() < capacity) {
                padded.append(' ');
            }
            byte[] replacement = padded.toString().getBytes("ASCII");
            System.arraycopy(replacement, 0, data, start, replacement.length);
        }

        File target = new File(outputDir, OUTPUT_NAME);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new Exception("cannot create dir: " + parent);
        }
        OutputStream out = new FileOutputStream(target);
        try {
            out.write(data);
        } finally {
            out.close();
        }

        if (stored != null) {
            storeVersion(stored);
        }
        result.put("ok", true);
        result.put("output", target.getAbsolutePath());
        result.put("rewritten", rewrite);
        result.put("version", stored == null ? current : stored);
        result.put("capacity", capacity);
        return result;
    }

    // ==================== 内部 ====================

    private byte[] readTemplate() throws Exception {
        if (!templateFile.exists()) {
            extractTemplate();
        }
        if (!templateFile.exists()) {
            return null;
        }
        return readBytes(templateFile);
    }

    /** assets/version.pak → filesDir/data/version.pak（首次提取，存在则不覆盖）。 */
    private void extractTemplate() throws Exception {
        File parent = templateFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return;
        }
        InputStream in = null;
        try {
            in = context.getAssets().open(TEMPLATE_ASSET);
        } catch (Exception e) {
            return; // assets 无模板，用户需自行放入
        }
        try {
            OutputStream out = new FileOutputStream(templateFile);
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

    /**
     * 定位 ProjectVersion= 的值区间。
     *
     * @return [start, end, capacity]；start=值起点，end=值终点（第一个 \r 或 \n），capacity=end-start；找不到返回 null
     */
    private int[] locateVersionRange(byte[] data) {
        byte[] key = VERSION_KEY.getBytes(); // "ProjectVersion=" 15 字节
        for (int i = 0; i + key.length <= data.length; i++) {
            if (data[i] == key[0] && data[i + 1] == key[1]) {
                boolean match = true;
                for (int j = 2; j < key.length; j++) {
                    if (data[i + j] != key[j]) {
                        match = false;
                        break;
                    }
                }
                if (!match) {
                    continue;
                }
                int start = i + key.length;
                int end = start;
                while (end < data.length && data[end] != '\r' && data[end] != '\n') {
                    end++;
                }
                if (end <= start) {
                    return null;
                }
                return new int[]{start, end, end - start};
            }
        }
        return null;
    }

    private static String defaultVersionFor(int capacity, String current) {
        if (DEFAULT_VERSION.length() <= capacity) {
            return DEFAULT_VERSION;
        }
        return current; // 模板容量小于默认版本号时保持模板版本
    }

    private static boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }

    private void storeVersion(String version) throws Exception {
        File parent = versionStore.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new Exception("cannot create dir: " + parent);
        }
        File tmp = new File(parent, versionStore.getName() + ".tmp");
        OutputStream out = new FileOutputStream(tmp);
        try {
            out.write(version.getBytes("UTF-8"));
        } finally {
            out.close();
        }
        if (!tmp.renameTo(versionStore)) {
            OutputStream direct = new FileOutputStream(versionStore);
            try {
                direct.write(version.getBytes("UTF-8"));
            } finally {
                direct.close();
            }
        }
    }

    private String readStoredVersion() {
        try {
            if (!versionStore.exists()) {
                return "";
            }
            byte[] data = readBytes(versionStore);
            return new String(data, "UTF-8").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] readBytes(File file) throws Exception {
        InputStream in = new FileInputStream(file);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
