package com.android1939.kardsserver.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.util.Base64;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.android1939.kardsserver.model.DeckRecord;
import com.android1939.kardsserver.model.UserRecord;
import com.android1939.kardsserver.util.TimeUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public final class KardsDatabase extends SQLiteOpenHelper {
    private static final int VERSION = 1;
    public static final String[] EQUIPMENT_COLUMNS = {
            "avatar",
            "item_Germany", "item_Britain", "item_Soviet", "item_USA", "item_Japan",
            "emote_Start_Germany", "emote_End_Germany", "emote_Good_Germany", "emote_Bad_Germany",
            "emote_Cheer_Germany", "emote_Taunt_Germany", "emote_Poke_Germany", "emote_Proclaim_Germany",
            "emote_Start_Britain", "emote_End_Britain", "emote_Good_Britain", "emote_Bad_Britain",
            "emote_Cheer_Britain", "emote_Taunt_Britain", "emote_Poke_Britain", "emote_Proclaim_Britain",
            "emote_Start_Soviet", "emote_End_Soviet", "emote_Good_Soviet", "emote_Bad_Soviet",
            "emote_Cheer_Soviet", "emote_Taunt_Soviet", "emote_Poke_Soviet", "emote_Proclaim_Soviet",
            "emote_Start_USA", "emote_End_USA", "emote_Good_USA", "emote_Bad_USA",
            "emote_Cheer_USA", "emote_Taunt_USA", "emote_Poke_USA", "emote_Proclaim_USA",
            "emote_Start_Japan", "emote_End_Japan", "emote_Good_Japan", "emote_Bad_Japan",
            "emote_Cheer_Japan", "emote_Taunt_Japan", "emote_Poke_Japan", "emote_Proclaim_Japan"
    };

    private final Random random = new Random();

    public KardsDatabase(Context context, String name) {
        super(context, name, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        StringBuilder userSql = new StringBuilder();
        userSql.append("CREATE TABLE IF NOT EXISTS users (")
                .append("id INTEGER PRIMARY KEY AUTOINCREMENT,")
                .append("username TEXT NOT NULL UNIQUE,")
                .append("password TEXT,")
                .append("player_name TEXT NOT NULL,")
                .append("player_tag INTEGER NOT NULL,")
                .append("player_jwt TEXT,")
                .append("is_online INTEGER NOT NULL DEFAULT 0,")
                .append("created_at TEXT NOT NULL");
        for (String column : EQUIPMENT_COLUMNS) {
            userSql.append(',').append(column).append(" TEXT");
        }
        userSql.append(')');
        db.execSQL(userSql.toString());
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_player_name_tag ON users(player_name, player_tag)");
        db.execSQL("CREATE TABLE IF NOT EXISTS decks ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "user_id INTEGER NOT NULL,"
                + "name TEXT,"
                + "card_back TEXT,"
                + "main_faction TEXT,"
                + "ally_faction TEXT,"
                + "deck_code TEXT,"
                + "favorite INTEGER NOT NULL DEFAULT 0,"
                + "last_played TEXT,"
                + "create_date TEXT,"
                + "modify_date TEXT,"
                + "FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE)");
        db.execSQL("CREATE TABLE IF NOT EXISTS server_settings (key TEXT PRIMARY KEY, value TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS ban_entries (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER, ip TEXT, device TEXT, expires_at INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    public synchronized UserRecord getOrCreateUser(String username, String password) {
        UserRecord existing = findUserByUsername(username);
        if (existing != null) {
            // 验证密码（即使是本地服务器也应校验，防止冒名登录）
            if (!verifyPassword(existing.password, password)) {
                return null;
            }
            return existing;
        }
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("username", username);
        values.put("password", hashPassword(password == null ? "" : password));
        values.put("player_name", "<anon>");
        values.put("player_tag", 0);
        values.put("player_jwt", "");
        values.put("created_at", TimeUtil.nowIso());
        long id = db.insertOrThrow("users", null, values);
        return findUserById((int) id);
    }

    /** 对密码做 SHA-256 + 随机盐哈希，格式为 "base64salt:base64hash" */
    private static String hashPassword(String password) {
        try {
            java.security.SecureRandom rng = new java.security.SecureRandom();
            byte[] salt = new byte[16];
            rng.nextBytes(salt);
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            md.update(salt);
            md.update(password.getBytes("UTF-8"));
            byte[] hash = md.digest();
            return android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP)
                    + ":" + android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            // 极端情况回退（理论上 SHA-256 和 UTF-8 总是可用）
            return ":" + password;
        }
    }

    /** 验证密码是否匹配存储的哈希。兼容旧版明文密码（无 ':' 分隔符时直接比较）。 */
    private static boolean verifyPassword(String stored, String password) {
        if (stored == null) stored = "";
        if (password == null) password = "";
        int sep = stored.indexOf(':');
        if (sep < 0) {
            // 旧版明文密码
            return stored.equals(password);
        }
        try {
            String saltB64 = stored.substring(0, sep);
            String hashB64 = stored.substring(sep + 1);
            byte[] salt = android.util.Base64.decode(saltB64, android.util.Base64.NO_WRAP);
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            md.update(salt);
            md.update(password.getBytes("UTF-8"));
            byte[] hash = md.digest();
            String expectedB64 = android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP);
            return hashB64.equals(expectedB64);
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized UserRecord findUserById(int id) {
        Cursor cursor = getReadableDatabase().query("users", null, "id=?", new String[]{String.valueOf(id)}, null, null, null);
        try {
            return cursor.moveToFirst() ? readUser(cursor) : null;
        } finally {
            cursor.close();
        }
    }

    public synchronized UserRecord findUserByUsername(String username) {
        Cursor cursor = getReadableDatabase().query("users", null, "username=?", new String[]{username}, null, null, null);
        try {
            return cursor.moveToFirst() ? readUser(cursor) : null;
        } finally {
            cursor.close();
        }
    }

    public synchronized UserRecord findUserByJwt(String jwt) {
        Cursor cursor = getReadableDatabase().query("users", null, "player_jwt=?", new String[]{jwt}, null, null, null);
        try {
            return cursor.moveToFirst() ? readUser(cursor) : null;
        } finally {
            cursor.close();
        }
    }

    public synchronized List<UserRecord> listUsers() {
        Cursor cursor = getReadableDatabase().query("users", null, null, null, null, null, "id ASC");
        try {
            List<UserRecord> users = new ArrayList<UserRecord>();
            while (cursor.moveToNext()) {
                users.add(readUser(cursor));
            }
            return users;
        } finally {
            cursor.close();
        }
    }

    public synchronized void updateUserJwt(int id, String jwt) {
        ContentValues values = new ContentValues();
        values.put("player_jwt", jwt);
        getWritableDatabase().update("users", values, "id=?", new String[]{String.valueOf(id)});
    }

    public synchronized UserRecord setPlayerName(int userId, String name) {
        int tag = findFreeTag(name);
        ContentValues values = new ContentValues();
        values.put("player_name", name);
        values.put("player_tag", tag);
        getWritableDatabase().update("users", values, "id=?", new String[]{String.valueOf(userId)});
        return findUserById(userId);
    }

    public synchronized void setOnline(int userId, boolean online) {
        ContentValues values = new ContentValues();
        values.put("is_online", online ? 1 : 0);
        getWritableDatabase().update("users", values, "id=?", new String[]{String.valueOf(userId)});
    }

    public synchronized void updateEquipment(int userId, String slot, String faction, String itemId) {
        String column = equipmentColumn(slot, faction);
        if (column == null) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put(column, itemId);
        getWritableDatabase().update("users", values, "id=?", new String[]{String.valueOf(userId)});
    }

    public synchronized DeckRecord createDeck(int userId, String name, String mainFaction, String allyFaction, String deckCode) {
        String now = TimeUtil.nowIso();
        ContentValues values = new ContentValues();
        values.put("user_id", userId);
        values.put("name", name);
        values.put("main_faction", mainFaction);
        values.put("ally_faction", allyFaction);
        values.put("card_back", "");
        values.put("deck_code", deckCode);
        values.put("favorite", 0);
        values.put("last_played", now);
        values.put("create_date", now);
        values.put("modify_date", now);
        long id = getWritableDatabase().insertOrThrow("decks", null, values);
        return findDeckById((int) id);
    }

    public synchronized List<DeckRecord> listDecks(int userId) {
        Cursor cursor = getReadableDatabase().query("decks", null, "user_id=?", new String[]{String.valueOf(userId)}, null, null, "id ASC");
        try {
            List<DeckRecord> decks = new ArrayList<DeckRecord>();
            while (cursor.moveToNext()) {
                decks.add(readDeck(cursor));
            }
            return decks;
        } finally {
            cursor.close();
        }
    }

    public synchronized DeckRecord findDeckById(int deckId) {
        Cursor cursor = getReadableDatabase().query("decks", null, "id=?", new String[]{String.valueOf(deckId)}, null, null, null);
        try {
            return cursor.moveToFirst() ? readDeck(cursor) : null;
        } finally {
            cursor.close();
        }
    }

    public synchronized boolean hasDeck(int deckId) {
        return findDeckById(deckId) != null;
    }

    public synchronized void renameDeck(int deckId, String name) {
        updateDeckField(deckId, "name", name);
    }

    public synchronized void updateDeckCode(int deckId, String deckCode) {
        updateDeckField(deckId, "deck_code", deckCode);
    }

    public synchronized void updateCardBack(int deckId, String cardBack) {
        updateDeckField(deckId, "card_back", cardBack);
    }

    public synchronized void toggleFavorite(int deckId) {
        DeckRecord deck = findDeckById(deckId);
        if (deck == null) {
            return;
        }
        ContentValues values = new ContentValues();
        values.put("favorite", deck.favorite ? 0 : 1);
        values.put("modify_date", TimeUtil.nowIso());
        getWritableDatabase().update("decks", values, "id=?", new String[]{String.valueOf(deckId)});
    }

    public synchronized void deleteDeck(int deckId) {
        getWritableDatabase().delete("decks", "id=?", new String[]{String.valueOf(deckId)});
    }

    private void updateDeckField(int deckId, String field, String value) {
        ContentValues values = new ContentValues();
        values.put(field, value);
        values.put("modify_date", TimeUtil.nowIso());
        getWritableDatabase().update("decks", values, "id=?", new String[]{String.valueOf(deckId)});
    }

    private int findFreeTag(String playerName) {
        for (int i = 0; i < 40; i++) {
            int tag = random.nextInt(10000);
            if (!playerTagExists(playerName, tag)) {
                return tag;
            }
        }
        for (int tag = 0; tag < 10000; tag++) {
            if (!playerTagExists(playerName, tag)) {
                return tag;
            }
        }
        return random.nextInt(10000);
    }

    private boolean playerTagExists(String playerName, int tag) {
        Cursor cursor = getReadableDatabase().query("users", new String[]{"id"}, "player_name=? AND player_tag=?",
                new String[]{playerName, String.valueOf(tag)}, null, null, null);
        try {
            return cursor.moveToFirst();
        } finally {
            cursor.close();
        }
    }

    private UserRecord readUser(Cursor cursor) {
        UserRecord user = new UserRecord();
        user.id = cursor.getInt(cursor.getColumnIndexOrThrow("id"));
        user.username = cursor.getString(cursor.getColumnIndexOrThrow("username"));
        user.password = cursor.getString(cursor.getColumnIndexOrThrow("password"));
        user.playerName = cursor.getString(cursor.getColumnIndexOrThrow("player_name"));
        user.playerTag = cursor.getInt(cursor.getColumnIndexOrThrow("player_tag"));
        user.playerJwt = cursor.getString(cursor.getColumnIndexOrThrow("player_jwt"));
        user.isOnline = cursor.getInt(cursor.getColumnIndexOrThrow("is_online")) != 0;
        for (String column : EQUIPMENT_COLUMNS) {
            int index = cursor.getColumnIndex(column);
            if (index >= 0) {
                user.equipment.put(column, cursor.getString(index));
            }
        }
        return user;
    }

    private DeckRecord readDeck(Cursor cursor) {
        DeckRecord deck = new DeckRecord();
        deck.id = cursor.getInt(cursor.getColumnIndexOrThrow("id"));
        deck.userId = cursor.getInt(cursor.getColumnIndexOrThrow("user_id"));
        deck.name = cursor.getString(cursor.getColumnIndexOrThrow("name"));
        deck.cardBack = cursor.getString(cursor.getColumnIndexOrThrow("card_back"));
        deck.mainFaction = cursor.getString(cursor.getColumnIndexOrThrow("main_faction"));
        deck.allyFaction = cursor.getString(cursor.getColumnIndexOrThrow("ally_faction"));
        deck.deckCode = cursor.getString(cursor.getColumnIndexOrThrow("deck_code"));
        deck.favorite = cursor.getInt(cursor.getColumnIndexOrThrow("favorite")) != 0;
        deck.lastPlayed = cursor.getString(cursor.getColumnIndexOrThrow("last_played"));
        deck.createDate = cursor.getString(cursor.getColumnIndexOrThrow("create_date"));
        deck.modifyDate = cursor.getString(cursor.getColumnIndexOrThrow("modify_date"));
        return deck;
    }

    public static String equipmentColumn(String slot, String faction) {
        if ("avatar".equals(slot)) {
            return "avatar";
        }
        if (faction == null) {
            return null;
        }
        String cleanFaction = faction.trim();
        if ("item_1".equals(slot)) {
            return "item_" + cleanFaction;
        }
        String prefix = null;
        if ("emote_1".equals(slot)) prefix = "emote_Start_";
        if ("emote_2".equals(slot)) prefix = "emote_End_";
        if ("emote_3".equals(slot)) prefix = "emote_Good_";
        if ("emote_4".equals(slot)) prefix = "emote_Bad_";
        if ("emote_5".equals(slot)) prefix = "emote_Cheer_";
        if ("emote_6".equals(slot)) prefix = "emote_Taunt_";
        if ("emote_7".equals(slot)) prefix = "emote_Poke_";
        if ("emote_8".equals(slot)) prefix = "emote_Proclaim_";
        return prefix == null ? null : prefix + cleanFaction;
    }

    public static String slotFromColumn(String column) {
        if ("avatar".equals(column)) return "avatar";
        if (column.startsWith("item_")) return "item_1";
        if (column.startsWith("emote_Start_")) return "emote_1";
        if (column.startsWith("emote_End_")) return "emote_2";
        if (column.startsWith("emote_Good_")) return "emote_3";
        if (column.startsWith("emote_Bad_")) return "emote_4";
        if (column.startsWith("emote_Cheer_")) return "emote_5";
        if (column.startsWith("emote_Taunt_")) return "emote_6";
        if (column.startsWith("emote_Poke_")) return "emote_7";
        if (column.startsWith("emote_Proclaim_")) return "emote_8";
        return "";
    }

    public static String factionFromColumn(String column) {
        if ("avatar".equals(column)) return "";
        int index = column.lastIndexOf('_');
        return index >= 0 ? column.substring(index + 1) : "";
    }
}
