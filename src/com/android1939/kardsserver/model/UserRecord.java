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
}
