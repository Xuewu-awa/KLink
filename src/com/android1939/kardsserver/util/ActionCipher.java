package com.android1939.kardsserver.util;

import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.Random;

public final class ActionCipher {
    private static final int[] KEY_LENGTHS = {
            47, 53, 73, 55, 61, 103, 47, 103, 33, 45, 73, 37, 97, 71, 39, 71,
            31, 61, 83, 101, 53, 97, 79, 75, 37, 31, 33, 69, 43, 63, 39, 43,
            79, 55, 49, 73, 83, 67, 59, 69, 103, 39, 47, 37, 41, 71, 89, 55,
            49, 45, 33, 45, 69, 49, 43, 53, 59, 31, 59, 101, 61, 41, 79, 75,
            83, 89, 75, 67, 41, 89, 63, 101, 67, 63, 97
    };

    private final Random random = new Random();

    public Decoded decode(String packet) throws Exception {
        int index = Integer.parseInt(packet.substring(0, 2));
        int length = Integer.parseInt(packet.substring(2, 8));
        int keyLength = KEY_LENGTHS[index];
        String header = packet.substring(8, 12);
        String key = packet.substring(12, 12 + keyLength);
        String body = packet.substring(12 + keyLength);
        byte[] encrypted = looseBase64Decode(body);
        byte[] plain = new byte[Math.min(length, encrypted.length)];
        for (int i = 0; i < plain.length; i++) {
            plain[i] = (byte) (encrypted[i] ^ key.charAt(i % key.length()));
        }
        byte[] actionHeader = looseBase64Decode(header + "==");
        int actionId = ((actionHeader[0] ^ key.charAt(0)) & 0xff) << 16;
        actionId |= ((actionHeader[1] ^ key.charAt(1 % key.length())) & 0xff) << 8;
        actionId |= ((actionHeader[2] ^ key.charAt(2 % key.length())) & 0xff);
        String text = new String(plain, "UTF-8");
        return new Decoded(actionId, new JSONObject(text));
    }

    public String encode(int actionId, JSONObject payload) throws Exception {
        int keyIndex = random.nextInt(KEY_LENGTHS.length);
        String key = randomKey(KEY_LENGTHS[keyIndex]);
        byte[] actionBytes = new byte[]{
                (byte) ((actionId >> 16) & 0xff),
                (byte) ((actionId >> 8) & 0xff),
                (byte) (actionId & 0xff)
        };
        byte[] headerBytes = new byte[3];
        for (int i = 0; i < 3; i++) {
            headerBytes[i] = (byte) (actionBytes[i] ^ key.charAt(i % key.length()));
        }
        String header = Base64.encodeToString(headerBytes, Base64.NO_WRAP).substring(0, 4);
        String text = payload.toString();
        byte[] plain = text.getBytes("UTF-8");
        byte[] encrypted = new byte[plain.length];
        for (int i = 0; i < plain.length; i++) {
            encrypted[i] = (byte) (plain[i] ^ key.charAt(i % key.length()));
        }
        String body = Base64.encodeToString(encrypted, Base64.NO_WRAP).replace("=", "");
        return String.format("%02d%06d%s%s%s", keyIndex, text.length(), header, key, body);
    }

    private String randomKey(int length) {
        StringBuilder key = new StringBuilder(length);
        while (key.length() < length) {
            int c = 33 + random.nextInt(94);
            if (c != '"' && c != '\'' && c != '\\') {
                key.append((char) c);
            }
        }
        return key.toString();
    }

    private static byte[] looseBase64Decode(String value) {
        String input = value == null ? "" : trimPadding(value);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int offset = 0;
        while (offset < input.length()) {
            int packed = 0;
            int count = 0;
            for (int i = 0; i < 4; i++) {
                if (offset < input.length()) {
                    int decoded = decodeBase64Char(input.charAt(offset));
                    offset++;
                    if (decoded < 0) {
                        i--;
                        continue;
                    }
                    packed = (packed << 6) | decoded;
                    count++;
                }
            }
            for (int i = count; i < 4; i++) {
                packed <<= 6;
            }
            if (count >= 2) out.write((packed >> 16) & 0xff);
            if (count >= 3) out.write((packed >> 8) & 0xff);
            if (count >= 4) out.write(packed & 0xff);
        }
        return out.toByteArray();
    }

    private static String trimPadding(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '=') {
            end--;
        }
        return value.substring(0, end);
    }

    private static int decodeBase64Char(char c) {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        if (c == '+') return 62;
        if (c == '/') return 63;
        if (c == '=') return -1;
        return -1;
    }

    public static final class Decoded {
        public final int actionId;
        public final JSONObject payload;

        Decoded(int actionId, JSONObject payload) {
            this.actionId = actionId;
            this.payload = payload;
        }
    }
}
