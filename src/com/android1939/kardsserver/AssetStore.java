package com.android1939.kardsserver;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 静态数据仓库。
 * <p>
 * 数据策略：首次访问时把 assets 内置文件复制到私有目录
 * {@code filesDir/data/kards-server/}，之后一律以磁盘副本为准
 * （assets 原件保持不动，修改只发生在磁盘副本上）。
 * 磁盘副本被删除时自动回退并重新复制 assets 内置文件。
 * </p>
 * <p>
 * 所有 getter 返回深拷贝，防止调用方污染内部缓存；
 * 外部修改数据后调用 {@link #invalidate()} 使缓存失效。
 * </p>
 */
public final class AssetStore {
    private final Context context;
    private final File dataDir;
    private JSONObject library;
    private JSONObject deckCodeIds;
    private JSONObject items;

    public AssetStore(Context context) {
        this.context = context;
        this.dataDir = new File(context.getFilesDir(), "data/kards-server");
    }

    /** 数据目录（磁盘副本所在目录）。 */
    public File dataDir() {
        return dataDir;
    }

    /** 应用 Context（供其它服务读取 assets 内置文件用）。 */
    public Context context() {
        return context;
    }

    public synchronized JSONObject library() throws Exception {
        if (library == null) {
            library = load("kards-server/library.json", "library.json");
        }
        return deepCopy(library);
    }

    public synchronized JSONObject deckCodeIds() throws Exception {
        if (deckCodeIds == null) {
            deckCodeIds = load("kards-server/deck_code_ids.json", "deck_code_ids.json");
        }
        return deepCopy(deckCodeIds);
    }

    public synchronized JSONObject items() throws Exception {
        if (items == null) {
            items = load("kards-server/items.json", "items.json");
        }
        return deepCopy(items);
    }

    /** 丢弃缓存，下次访问重新读取磁盘副本（数据修改后调用）。 */
    public synchronized void invalidate() {
        library = null;
        deckCodeIds = null;
        items = null;
    }

    private JSONObject load(String assetPath, String fileName) throws Exception {
        File disk = new File(dataDir, fileName);
        if (!disk.exists()) {
            copyAsset(assetPath, disk);
        }
        return new JSONObject(readFile(disk));
    }

    private void copyAsset(String assetPath, File target) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new Exception("cannot create dir: " + parent);
        }
        InputStream in = context.getAssets().open(assetPath);
        try {
            OutputStream out = new FileOutputStream(target);
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

    private String readFile(File file) throws Exception {
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

    private static JSONObject deepCopy(JSONObject source) throws Exception {
        return source == null ? new JSONObject() : new JSONObject(source.toString());
    }
}
