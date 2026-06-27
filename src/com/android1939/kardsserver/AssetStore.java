package com.android1939.kardsserver;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public final class AssetStore {
    private final Context context;
    private JSONObject library;
    private JSONObject deckCodeIds;
    private JSONObject items;

    public AssetStore(Context context) {
        this.context = context;
    }

    public synchronized JSONObject library() throws Exception {
        if (library == null) {
            library = new JSONObject(readAsset("kards-server/library.json"));
        }
        return new JSONObject(library.toString());
    }

    public synchronized JSONObject deckCodeIds() throws Exception {
        if (deckCodeIds == null) {
            deckCodeIds = new JSONObject(readAsset("kards-server/deck_code_ids.json"));
        }
        return deckCodeIds;
    }

    public synchronized JSONObject items() throws Exception {
        if (items == null) {
            items = new JSONObject(readAsset("kards-server/items.json"));
        }
        return new JSONObject(items.toString());
    }

    private String readAsset(String path) throws Exception {
        InputStream in = context.getAssets().open(path);
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
