package com.android1939.kardsserver;

import com.android1939.kardsserver.db.KardsDatabase;
import com.android1939.kardsserver.model.DeckRecord;
import com.android1939.kardsserver.model.MatchState;
import com.android1939.kardsserver.util.ActionCipher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MatchManager {
    private final KardsDatabase database;
    private final DeckCodeManager deckCodeManager;
    private final ActionCipher actionCipher = new ActionCipher();
    private final Set<Integer> onlinePlayers = new HashSet<Integer>();
    private final ArrayDeque<Integer> rankedQueue = new ArrayDeque<Integer>();
    private final ArrayDeque<Integer> casualQueue = new ArrayDeque<Integer>();
    private final ArrayDeque<Integer> brawlQueue = new ArrayDeque<Integer>();
    private final ArrayDeque<Integer> diyQueue = new ArrayDeque<Integer>();
    private final Map<String, ArrayDeque<Integer>> codeQueues = new HashMap<String, ArrayDeque<Integer>>();
    private final Map<Integer, String> playerCodes = new HashMap<Integer, String>();
    private final Map<Integer, Integer> playerDecks = new HashMap<Integer, Integer>();
    private final Map<Integer, Integer> playing = new HashMap<Integer, Integer>();
    private final Map<Integer, MatchState> matches = new HashMap<Integer, MatchState>();
    private final Set<Integer> kickedPlayers = new HashSet<Integer>();
    private int nextMatchId = 1;

    public MatchManager(KardsDatabase database, AssetStore assets) {
        this.database = database;
        this.deckCodeManager = new DeckCodeManager(assets);
    }

    public synchronized void setOnline(int userId, boolean online) {
        if (online) {
            onlinePlayers.add(userId);
        } else {
            onlinePlayers.remove(userId);
            removeFromQueues(userId);
            Integer matchId = playing.get(userId);
            if (matchId != null) {
                MatchState match = matches.get(matchId);
                if (match != null) {
                    if (match.playerLeft == userId) {
                        match.leftOnline = false;
                    }
                    if (match.playerRight == userId) {
                        match.rightOnline = false;
                    }
                }
            }
        }
        database.setOnline(userId, online);
    }

    public synchronized boolean isOnline(int userId) {
        return onlinePlayers.contains(userId);
    }

    public synchronized void kickPlayer(int userId) {
        kickedPlayers.add(userId);
        setOnline(userId, false);
    }

    public synchronized boolean isKicked(int userId) {
        return kickedPlayers.contains(userId);
    }

    public synchronized int matchCount() {
        return matches.size();
    }

    public synchronized boolean addToQueue(int playerId, int deckId, String extraData) throws Exception {
        Integer existingMatchId = playing.get(playerId);
        if (existingMatchId != null) {
            if (!matches.containsKey(existingMatchId)) {
                playing.remove(playerId);
                playerDecks.remove(playerId);
            } else {
                return false;
            }
        }
        if (playerDecks.containsKey(playerId) || playerCodes.containsKey(playerId)
                || rankedQueue.contains(playerId) || casualQueue.contains(playerId)
                || brawlQueue.contains(playerId) || diyQueue.contains(playerId)) {
            removeFromQueues(playerId);
        }
        if (playerDecks.containsKey(playerId)) {
            return false;
        }
        playerDecks.put(playerId, deckId);
        String matchType = extraData == null ? "" : extraData;
        if (matchType.startsWith("battle_code:")) {
            String code = matchType.substring("battle_code:".length()).trim();
            if (code.length() == 0) {
                return false;
            }
            ArrayDeque<Integer> queue = codeQueues.get(code);
            if (queue == null) {
                queue = new ArrayDeque<Integer>();
                codeQueues.put(code, queue);
            }
            playerCodes.put(playerId, code);
            queue.add(playerId);
            if (queue.size() >= 2) {
                int left = queue.remove();
                int right = queue.remove();
                playerCodes.remove(left);
                playerCodes.remove(right);
                createMatch(left, right, "battle");
                return true;
            }
            return false;
        }
        ArrayDeque<Integer> queue;
        String createdType;
        if ("training".equals(matchType)) {
            // 人机模式：直接创建 Bot 对局
            createBotMatch(playerId, deckId);
            return true;
        } else if ("brawl".equals(matchType)) {
            queue = brawlQueue;
            createdType = "brawl";
        } else if (matchType.length() == 0) {
            queue = rankedQueue;
            createdType = "battle";
        } else {
            queue = casualQueue;
            createdType = "classic";
        }
        queue.add(playerId);
        if (queue.size() >= 2) {
            int left = queue.remove();
            int right = queue.remove();
            createMatch(left, right, createdType);
            return true;
        }
        return false;
    }

    public synchronized boolean removeFromQueues(int playerId) {
        boolean removed = rankedQueue.remove(playerId) | casualQueue.remove(playerId) | brawlQueue.remove(playerId) | diyQueue.remove(playerId);
        String code = playerCodes.remove(playerId);
        if (code != null) {
            ArrayDeque<Integer> queue = codeQueues.get(code);
            if (queue != null) {
                removed |= queue.remove(playerId);
                if (queue.isEmpty()) {
                    codeQueues.remove(code);
                }
            }
        }
        playerDecks.remove(playerId);
        return removed;
    }

    public synchronized boolean isWaiting(int playerId) {
        return playerDecks.containsKey(playerId) && !playing.containsKey(playerId);
    }

    public synchronized MatchState matchForPlayer(int playerId) {
        Integer matchId = playing.get(playerId);
        return matchId == null ? null : matches.get(matchId);
    }

    public synchronized MatchState matchById(int matchId) {
        return matches.get(matchId);
    }

    public synchronized void appendAction(MatchState match, String encryptedAction) {
        match.currentActionId++;
        match.actions.put(match.currentActionId);
        match.actionsData.put(match.currentActionId, encryptedAction);
    }

    public synchronized JSONArray actionsSince(MatchState match, int minActionId) {
        JSONArray output = new JSONArray();
        for (int i = 0; i < match.actions.length(); i++) {
            int actionId = match.actions.optInt(i);
            if (actionId >= minActionId) {
                String data = match.actionsData.get(actionId);
                if (data != null) {
                    output.put(data);
                }
            }
        }
        return output;
    }

    private void createMatch(int leftPlayer, int rightPlayer, String matchType) throws Exception {
        int matchId = ++nextMatchId;
        playing.put(leftPlayer, matchId);
        playing.put(rightPlayer, matchId);
        MatchState match = new MatchState();
        match.matchId = matchId;
        match.playerLeft = leftPlayer;
        match.playerRight = rightPlayer;
        match.actionPlayerId = leftPlayer;
        match.deckIdLeft = playerDecks.get(leftPlayer);
        match.deckIdRight = playerDecks.get(rightPlayer);
        match.matchType = matchType;
        DeckRecord leftDeck = database.findDeckById(match.deckIdLeft);
        DeckRecord rightDeck = database.findDeckById(match.deckIdRight);
        match.leftDeckData = deckCodeManager.parseDeckCode(leftDeck == null ? "" : leftDeck.deckCode);
        match.rightDeckData = deckCodeManager.parseDeckCode(rightDeck == null ? "" : rightDeck.deckCode);
        match.leftCardsData = deckCodeManager.createMatchCards("left", match.leftDeckData);
        match.rightCardsData = deckCodeManager.createMatchCards("right", match.rightDeckData);
        shuffleCards(match.leftCardsData);
        shuffleCards(match.rightCardsData);
        markHands(match);
        matches.put(matchId, match);
        playerDecks.remove(leftPlayer);
        playerDecks.remove(rightPlayer);
    }

    public synchronized void cleanupMatch(int matchId) {
        MatchState match = matches.remove(matchId);
        if (match == null) {
            return;
        }
        cleanupPlayerAfterMatch(match.playerLeft);
        cleanupPlayerAfterMatch(match.playerRight);
    }

    private void cleanupPlayerAfterMatch(int playerId) {
        Integer playingMatch = playing.get(playerId);
        if (playingMatch != null) {
            playing.remove(playerId);
        }
        playerDecks.remove(playerId);
        String code = playerCodes.remove(playerId);
        if (code != null) {
            ArrayDeque<Integer> queue = codeQueues.get(code);
            if (queue != null) {
                queue.remove(playerId);
                if (queue.isEmpty()) {
                    codeQueues.remove(code);
                }
            }
        }
        rankedQueue.remove(playerId);
        casualQueue.remove(playerId);
        brawlQueue.remove(playerId);
        diyQueue.remove(playerId);
    }

    private void shuffleCards(JSONArray cards) throws Exception {
        if (cards.length() <= 2) {
            return;
        }
        JSONObject hq = cards.getJSONObject(0);
        List<JSONObject> rest = new ArrayList<JSONObject>();
        for (int i = 1; i < cards.length(); i++) {
            rest.add(cards.getJSONObject(i));
        }
        Collections.shuffle(rest);
        while (cards.length() > 0) {
            cards.remove(0);
        }
        cards.put(hq);
        for (int i = 0; i < rest.size(); i++) {
            JSONObject card = rest.get(i);
            card.put("location_number", i);
            cards.put(card);
        }
    }

    private void markHands(MatchState match) throws Exception {
        for (int i = 1; i < match.leftCardsData.length(); i++) {
            JSONObject card = match.leftCardsData.getJSONObject(i);
            if (i < 5) {
                card.put("location", "hand_left");
                match.leftHandCards.put(card);
            } else {
                match.leftDeckCards.put(card);
            }
        }
        for (int i = 1; i < match.rightCardsData.length(); i++) {
            JSONObject card = match.rightCardsData.getJSONObject(i);
            if (i < 6) {
                card.put("location", "hand_right");
                match.rightHandCards.put(card);
            } else {
                match.rightDeckCards.put(card);
            }
        }
    }

    // ==================== Bot 匹配 ====================

    public synchronized void createBotMatch(int playerId, int deckId) throws Exception {
        // 已有对局则复用
        Integer existingMatchId = playing.get(playerId);
        if (existingMatchId != null) {
            MatchState existing = matches.get(existingMatchId);
            if (existing != null && existing.botEnabled) {
                prepareBotMulligan(existing);
                return;
            }
        }

        DeckRecord playerDeck = database.findDeckById(deckId);
        if (playerDeck == null) throw new Exception("deck not found");

        int matchId = ++nextMatchId;
        playing.put(playerId, matchId);

        MatchState match = new MatchState();
        match.matchId = matchId;
        match.playerLeft = playerId;
        match.playerRight = MatchState.BOT_PLAYER_ID;
        match.deckIdLeft = deckId;
        match.deckIdRight = deckId;
        match.matchType = "training";
        match.status = "pending";

        match.leftDeckData = deckCodeManager.parseDeckCode(playerDeck.deckCode);
        match.rightDeckData = deckCodeManager.parseDeckCode(playerDeck.deckCode);
        match.leftCardsData = deckCodeManager.createMatchCards("left", match.leftDeckData);
        match.rightCardsData = deckCodeManager.createMatchCards("right", match.rightDeckData);
        shuffleCards(match.leftCardsData);
        shuffleCards(match.rightCardsData);

        // 分牌：玩家 4 张手牌，Bot 5 张手牌
        JSONArray leftDeck = match.leftCardsData;
        for (int i = 1; i < leftDeck.length(); i++) {
            JSONObject card = leftDeck.getJSONObject(i);
            if (i < 5) {
                card.put("location", "hand_left");
                match.leftHandCards.put(card);
            } else {
                card.put("location", "deck_left");
                match.leftDeckCards.put(card);
            }
        }
        JSONArray rightDeck = match.rightCardsData;
        for (int i = 1; i < rightDeck.length(); i++) {
            JSONObject card = rightDeck.getJSONObject(i);
            if (i < 6) {
                card.put("location", "hand_right");
                match.rightHandCards.put(card);
            } else {
                card.put("location", "deck_right");
                match.rightDeckCards.put(card);
            }
        }

        match.botEnabled = true;
        match.botSide = "right";
        match.playerStatusRight = "mulligan_done";
        match.levelLoadedRight = 1;
        match.rightOnline = true;

        prepareBotMulligan(match);

        matches.put(matchId, match);
        playerDecks.remove(playerId);
    }

    /**
     * Bot 回合 Tick — 在 poll/get 时调用。
     * Bot 仅做：开始回合 → 结束回合 → 回合+1，延迟 BOT_TURN_DELAY_MS。
     */
    public synchronized void tickBot(MatchState match) {
        if (match == null || !match.botEnabled) return;
        if (!"running".equals(match.status) || match.actionSessionId == 0) return;
        if (match.currentTurn % 2 != 0) return; // 玩家回合，Bot 不动作
        if (match.botLastEndedTurn == match.currentTurn) return;

        if (match.botPendingTurn != match.currentTurn) {
            match.botPendingTurn = match.currentTurn;
            match.botTurnReadyAt = System.currentTimeMillis() + MatchState.BOT_TURN_DELAY_MS;
            return;
        }
        if (System.currentTimeMillis() < match.botTurnReadyAt) return;

        // 插入起始回合 Action
        try {
            JSONObject startTurn = new JSONObject();
            startTurn.put("action_type", "XActionStartOfTurn");
            startTurn.put("player_id", match.playerRight);
            startTurn.put("action_data", new JSONObject().put("side", "right"));
            startTurn.put("sub_actions", new JSONArray());
            startTurn.put("turn_number", match.currentTurn);
            appendBotAction(match, startTurn);

            // 插入结束回合 Action
            JSONObject endTurn = new JSONObject();
            endTurn.put("action_type", "XActionEndOfTurn");
            endTurn.put("player_id", match.playerRight);
            endTurn.put("action_data", new JSONObject().put("reason", "endTurnButton").put("side", "right"));
            endTurn.put("sub_actions", new JSONArray());
            endTurn.put("turn_number", match.currentTurn);
            appendBotAction(match, endTurn);

            match.currentTurn++;
        } catch (Exception ignored) {}

        match.botLastEndedTurn = match.currentTurn - 1;
        match.botPendingTurn = 0;
        match.botTurnReadyAt = 0;
    }

    private void appendBotAction(MatchState match, JSONObject action) throws Exception {
        String actionType = action.optString("action_type");
        int playerId = action.optInt("player_id");
        JSONObject actionData = action.optJSONObject("action_data");
        int turnNumber = action.optInt("turn_number");

        match.currentActionId++;
        JSONObject fullAction = new JSONObject();
        fullAction.put("action_id", match.currentActionId);
        fullAction.put("action_type", actionType);
        fullAction.put("player_id", playerId);
        fullAction.put("action_data", actionData != null ? actionData : new JSONObject());
        fullAction.put("sub_actions", new JSONArray());
        fullAction.put("turn_number", turnNumber);
        match.actions.put(match.currentActionId);

        String encrypted = actionCipher.encode(match.actionSessionId, fullAction);
        match.actionsData.put(match.currentActionId, encrypted);
    }

    private void prepareBotMulligan(MatchState match) {
        if (match.rightHandCards.length() == 0 || match.rightDeckCards.length() == 0) {
            match.playerStatusRight = "mulligan_done";
            return;
        }
        match.rightReplacementCards = new JSONArray();
        int replacements = Math.min(2, match.rightHandCards.length());
        for (int i = 0; i < replacements; i++) {
            if (match.rightDeckCards.length() == 0) break;
            try {
                int deckIdx = new java.util.Random().nextInt(match.rightDeckCards.length());
                JSONObject oldCard = match.rightHandCards.getJSONObject(i);
                JSONObject newCard = match.rightDeckCards.getJSONObject(deckIdx);
                oldCard.put("location", "deck_right");
                newCard.put("location", "hand_right");
                int tmpNum = oldCard.optInt("location_number");
                oldCard.put("location_number", newCard.optInt("location_number"));
                newCard.put("location_number", tmpNum);
                match.rightHandCards.put(i, newCard);
                match.rightDeckCards.put(deckIdx, oldCard);
                match.rightReplacementCards.put(newCard);
            } catch (Exception ignored) {}
        }
        try {
            shuffleCards(match.rightDeckCards);
        } catch (Exception ignored) {}
        match.playerStatusRight = "mulligan_done";
    }
}
