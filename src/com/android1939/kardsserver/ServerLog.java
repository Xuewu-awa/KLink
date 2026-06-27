package com.android1939.kardsserver;

import com.android1939.kardsserver.util.TimeUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;

public final class ServerLog {
    private static final int MAX_ENTRIES = 160;
    private static final ArrayDeque<Entry> ENTRIES = new ArrayDeque<Entry>();

    private ServerLog() {
    }

    public static synchronized void add(String level, String message) {
        if (ENTRIES.size() >= MAX_ENTRIES) {
            ENTRIES.removeFirst();
        }
        Entry entry = new Entry();
        entry.time = TimeUtil.nowIso();
        entry.level = level == null || level.length() == 0 ? "info" : level;
        entry.message = message == null ? "" : message;
        ENTRIES.addLast(entry);
    }

    public static synchronized JSONArray recent() {
        JSONArray array = new JSONArray();
        for (Entry entry : ENTRIES) {
            try {
                array.put(new JSONObject()
                        .put("time", entry.time)
                        .put("level", entry.level)
                        .put("message", entry.message));
            } catch (Exception ignored) {
            }
        }
        return array;
    }

    private static final class Entry {
        String time;
        String level;
        String message;
    }
}
