package com.android1939.kardsserver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DeckCodeManager {
    private final AssetStore assets;

    public DeckCodeManager(AssetStore assets) {
        this.assets = assets;
    }

    public JSONObject parseDeckCode(String deckCode) {
        JSONObject result = new JSONObject();
        try {
            if (deckCode == null || !deckCode.startsWith("%%")) {
                return fallbackDeck("Deck code must start with %%");
            }
            String code = deckCode.substring(2);
            String[] parts = code.split("\\|");
            if (parts.length < 2) {
                return fallbackDeck("Deck code missing card part");
            }
            String country = parts[0];
            String cards = parts[1];
            String hq = parts.length >= 3 ? parts[2] : "0N";
            if (country.length() < 2) {
                return fallbackDeck("Bad country code");
            }
            if (cards.indexOf('~') >= 0) {
                cards = cards.substring(0, cards.indexOf('~'));
            }
            String[] groups = cards.split(";", -1);
            if (groups.length != 4) {
                return fallbackDeck("Bad card groups");
            }
            int[] multipliers = {1, 2, 3, 4};
            Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
            for (int i = 0; i < groups.length; i++) {
                String group = groups[i];
                for (int j = 0; j + 1 < group.length(); j += 2) {
                    String importId = group.substring(j, j + 2);
                    Integer old = counts.get(importId);
                    counts.put(importId, (old == null ? 0 : old) + multipliers[i]);
                }
            }
            JSONObject importIds = new JSONObject();
            int total = 0;
            for (String key : counts.keySet()) {
                int count = counts.get(key);
                importIds.put(key, count);
                total += count;
            }
            result.put("success", true);
            result.put("main_country", countryName(country.substring(0, 1)));
            result.put("ally_country", countryName(country.substring(1, 2)));
            result.put("import_ids", importIds);
            result.put("total_cards", total);
            result.put("unique_cards", counts.size());
            result.put("deck_code", deckCode);
            result.put("hq_code", hq);
            return result;
        } catch (Exception e) {
            return fallbackDeck(e.toString());
        }
    }

    public JSONArray createMatchCards(String side, JSONObject deckData) throws Exception {
        JSONArray cards = new JSONArray();
        JSONObject deckCodeIds = assets.deckCodeIds();
        String hqCode = deckData.optString("hq_code", "0N");
        if (hqCode.length() > 2) {
            hqCode = hqCode.substring(0, 2);
        }
        String mainCountry = deckData.optString("main_country", "Britain");
        boolean left = "left".equals(side);
        JSONObject hq = new JSONObject();
        hq.put("card_id", left ? 1 : 41);
        hq.put("faction", mainCountry);
        hq.put("is_gold", true);
        hq.put("location", left ? "board_hqleft" : "board_hqright");
        hq.put("location_number", 0);
        hq.put("name", cardName(deckCodeIds, hqCode, "card_location_london"));
        cards.put(hq);
        int cardId = left ? 2 : 42;
        int locationNumber = 0;
        String location = left ? "deck_left" : "deck_right";
        JSONObject importIds = deckData.optJSONObject("import_ids");
        if (importIds != null) {
            Iterator<String> keys = importIds.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                int count = importIds.optInt(key, 0);
                for (int i = 0; i < count; i++) {
                    JSONObject card = new JSONObject();
                    card.put("card_id", cardId++);
                    card.put("is_gold", true);
                    card.put("location", location);
                    card.put("location_number", locationNumber++);
                    card.put("name", cardName(deckCodeIds, key, "card_unknown"));
                    cards.put(card);
                }
            }
        }
        return cards;
    }

    private JSONObject fallbackDeck(String error) {
        JSONObject result = new JSONObject();
        try {
            result.put("success", false);
            result.put("error", error);
            result.put("main_country", "Britain");
            result.put("ally_country", "USA");
            result.put("import_ids", new JSONObject());
            result.put("total_cards", 0);
            result.put("unique_cards", 0);
            result.put("deck_code", "");
            result.put("hq_code", "0N");
        } catch (Exception ignored) {
        }
        return result;
    }

    private String countryName(String code) {
        if ("1".equals(code)) return "Germany";
        if ("2".equals(code)) return "Britain";
        if ("3".equals(code)) return "Japan";
        if ("4".equals(code)) return "Soviet";
        if ("5".equals(code)) return "USA";
        if ("6".equals(code)) return "France";
        if ("7".equals(code)) return "Italy";
        if ("8".equals(code)) return "Poland";
        if ("9".equals(code)) return "Finland";
        return "Unknown";
    }

    private String cardName(JSONObject table, String key, String fallback) {
        JSONObject item = table.optJSONObject(key);
        return item == null ? fallback : item.optString("card", fallback);
    }
}
