package com.android1939.kardsserver.util;

import android.util.Base64;

import org.json.JSONObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class JwtUtil {
    private JwtUtil() {
    }

    public static String create(String secret, int userId, String username, long expiresAtSeconds) throws Exception {
        JSONObject header = new JSONObject();
        header.put("alg", "HS256");
        header.put("typ", "JWT");
        JSONObject payload = new JSONObject();
        payload.put("user_id", userId);
        payload.put("username", username);
        payload.put("exp", expiresAtSeconds);
        String signingInput = base64Url(header.toString().getBytes("UTF-8")) + "."
                + base64Url(payload.toString().getBytes("UTF-8"));
        return signingInput + "." + sign(secret, signingInput);
    }

    public static JSONObject verify(String secret, String token) throws Exception {
        String[] parts = token == null ? new String[0] : token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Bad JWT");
        }
        String signingInput = parts[0] + "." + parts[1];
        String expected = sign(secret, signingInput);
        if (!constantEquals(expected, parts[2])) {
            throw new IllegalArgumentException("Bad JWT signature");
        }
        JSONObject payload = new JSONObject(new String(base64UrlDecode(parts[1]), "UTF-8"));
        long exp = payload.optLong("exp", 0);
        if (exp > 0 && exp < TimeUtil.nowSeconds()) {
            throw new IllegalArgumentException("JWT expired");
        }
        return payload;
    }

    private static String sign(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"));
        return base64Url(mac.doFinal(data.getBytes("UTF-8")));
    }

    private static String base64Url(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static byte[] base64UrlDecode(String value) {
        return Base64.decode(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static boolean constantEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
