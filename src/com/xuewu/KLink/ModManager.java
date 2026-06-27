package com.xuewu.KLink;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 模组管理器 — PAK 文件扫描、安装、卸载、启用/禁用。
 * 移植自 rards 项目，去除反作弊相关逻辑。
 */
public class ModManager {

    /**
     * 默认 PAK 目录（游戏数据路径，可在构建时修改）。
     */
    public static final String DEFAULT_PAK_PATH =
            "/data/data/com.xuewu.KLink/files/UnrealGame/kards/Engine/Content/Paks/";

    private final String pakPath;

    public ModManager() {
        this(DEFAULT_PAK_PATH);
    }

    public ModManager(String pakPath) {
        this.pakPath = pakPath;
    }

    // ==================== 扫描 ====================

    /**
     * 扫描 PAK 目录，返回已安装模组列表 JSON。
     */
    public String scanMods() {
        try {
            File dir = new File(pakPath);
            List<ModEntry> list = new ArrayList<>();

            if (dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        String name = file.getName();
                        if (name.endsWith(".pak") || name.endsWith(".pak.disabled")) {
                            list.add(ModEntry.fromFile(file));
                        }
                    }
                }
            }

            // 按优先级降序
            Collections.sort(list, new Comparator<ModEntry>() {
                @Override
                public int compare(ModEntry a, ModEntry b) {
                    return Integer.compare(b.priority, a.priority);
                }
            });

            JSONArray arr = new JSONArray();
            for (ModEntry m : list) {
                arr.put(m.toJson());
            }
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    // ==================== 安装 ====================

    /**
     * 将源文件复制到 PAK 目录。
     * @param sourcePath 源 PAK 文件绝对路径
     * @return 复制后的目标文件路径，失败返回 null
     */
    public String installMod(String sourcePath) {
        if (sourcePath == null || sourcePath.isEmpty()) return null;

        File source = new File(sourcePath);
        if (!source.exists() || !source.isFile()) return null;

        String fileName = source.getName();
        File dest = new File(pakPath, fileName);
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try {
            copyFile(source, dest);
            return dest.getAbsolutePath();
        } catch (IOException e) {
            return null;
        }
    }

    // ==================== 卸载 ====================

    /**
     * 根据模组名删除 PAK 文件。
     */
    public boolean uninstallMod(String modName) {
        if (modName == null || modName.isEmpty()) return false;

        String base = modName.replace(".pak", "").replace(".pak.disabled", "");
        String[] suffixes = {".pak", ".pak.disabled", "_P.pak", "_P.pak.disabled"};

        for (String suffix : suffixes) {
            File file = new File(pakPath, base + suffix);
            if (file.exists() && file.delete()) {
                return true;
            }
        }

        // 尝试匹配 _数字_P 格式
        File dir = new File(pakPath);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String name = f.getName();
                if (name.startsWith(base + "_") && name.contains("_P.pak")) {
                    return f.delete();
                }
            }
        }
        return false;
    }

    // ==================== 启用/禁用 ====================

    /**
     * 切换模组启用状态。启用 = 去掉 .disabled；禁用 = 加上 .disabled。
     */
    public boolean toggleMod(String modName, boolean enable) {
        if (modName == null || modName.isEmpty()) return false;

        File dir = new File(pakPath);
        if (!dir.exists()) return false;

        String base = modName.replace(".pak", "").replace(".pak.disabled", "");
        File[] files = dir.listFiles();
        if (files == null) return false;

        for (File file : files) {
            String name = file.getName();
            if (!name.startsWith(base)) continue;

            if (enable && name.endsWith(".disabled")) {
                String target = name.substring(0, name.length() - ".disabled".length());
                return file.renameTo(new File(pakPath, target));
            }
            if (!enable && !name.endsWith(".disabled")) {
                return file.renameTo(new File(pakPath, name + ".disabled"));
            }
        }
        return false;
    }

    // ==================== 工具 ====================

    private void copyFile(File source, File dest) throws IOException {
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
    }

    // ==================== ModEntry ====================

    static class ModEntry {
        String name;
        long size;
        boolean enabled;
        int priority;

        static ModEntry fromFile(File file) {
            ModEntry m = new ModEntry();
            String fileName = file.getName();
            m.name = extractBaseName(fileName);
            m.size = file.length();
            m.enabled = !fileName.endsWith(".disabled");
            m.priority = parsePriority(fileName);
            return m;
        }

        JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("id", name);
            json.put("name", name);
            json.put("size", formatSize(size));
            json.put("sizeBytes", size);
            json.put("enabled", enabled);
            json.put("priority", priority);
            return json;
        }

        static String extractBaseName(String fileName) {
            // 去掉 .disabled
            String name = fileName;
            if (name.endsWith(".disabled")) {
                name = name.substring(0, name.length() - ".disabled".length());
            }
            // 去掉 .pak
            if (name.endsWith(".pak")) {
                name = name.substring(0, name.length() - ".pak".length());
            }
            return name;
        }

        static int parsePriority(String fileName) {
            // 文件名格式: "数字_名称_P.pak" 或 "数字_名称.pak"
            int underIdx = fileName.indexOf('_');
            if (underIdx > 0) {
                try {
                    return Integer.parseInt(fileName.substring(0, underIdx));
                } catch (NumberFormatException ignored) {}
            }
            return 0;
        }

        static String formatSize(long bytes) {
            if (bytes < 1024) return bytes + "B";
            if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
            if (bytes < 1024 * 1024 * 1024) return String.format("%.1fMB", bytes / (1024.0 * 1024));
            return String.format("%.2fGB", bytes / (1024.0 * 1024 * 1024));
        }
    }
}
