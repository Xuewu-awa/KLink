package com.android1939.kardsserver.model;

import java.util.HashMap;
import java.util.Map;

public final class UserRecord {
    public int id;
    public String username;
    public String password;
    public String playerName;
    public int playerTag;
    public String playerJwt;
    public boolean isOnline;
    public final Map<String, String> equipment = new HashMap<String, String>();

    // ---- v2 追加：后台展示 + 经济系统 ----
    /** 注册时间（ISO-8601）。 */
    public String createdAt = "";
    /** 最近一次成功登录时间（ISO-8601）。 */
    public String lastLoginAt = "";
    public String lastLoginIp = "";
    public String lastLoginDevice = "";
    public long gold = 0;
    public long diamonds = 0;
    public long dust = 0;
    public boolean banned = false;

    /** 显示名 {@code 名称#0000}，与桌面端 fyserver 的 displayName 同形。 */
    public String displayName() {
        String name = playerName == null ? "" : playerName;
        String tag = String.valueOf(playerTag);
        StringBuilder builder = new StringBuilder(name).append('#');
        for (int i = tag.length(); i < 4; i++) {
            builder.append('0');
        }
        return builder.append(tag).toString();
    }
}
