package com.android1939.kardsserver.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public final class MatchState {
    public int matchId;
    public int playerLeft;
    public int playerRight;
    public int deckIdLeft;
    public int deckIdRight;
    public String status = "pending";
    public String matchType = "battle";
    public String winnerSide = "";
    public int winnerId = 0;
    public int currentTurn = 1;
    public int currentActionId = 0;
    public int actionSessionId = 0;
    public int actionPlayerId = 0;
    public int endConfirmCount = 0;
    public boolean leftOnline = true;
    public boolean rightOnline = true;
    public int levelLoadedLeft = 0;
    public int levelLoadedRight = 0;
    public String playerStatusLeft = "not_done";
    public String playerStatusRight = "not_done";
    public JSONObject leftDeckData = new JSONObject();
    public JSONObject rightDeckData = new JSONObject();
    public JSONArray leftCardsData = new JSONArray();
    public JSONArray rightCardsData = new JSONArray();
    public JSONArray leftHandCards = new JSONArray();
    public JSONArray rightHandCards = new JSONArray();
    public JSONArray leftDeckCards = new JSONArray();
    public JSONArray rightDeckCards = new JSONArray();
    public JSONArray leftDiscardedCards = new JSONArray();
    public JSONArray rightDiscardedCards = new JSONArray();
    public JSONArray leftReplacementCards = new JSONArray();
    public JSONArray rightReplacementCards = new JSONArray();
    public final JSONArray notifications = new JSONArray();
    public final JSONArray actions = new JSONArray();
    public final Map<Integer, String> actionsData = new HashMap<Integer, String>();

    // ---- Bot 字段 ----
    public boolean botEnabled = false;
    public String botSide = "right";
    public int botLastEndedTurn = 0;
    public int botPendingTurn = 0;
    public long botTurnReadyAt = 0; // System.currentTimeMillis() + delay

    public static final int BOT_PLAYER_ID = 900000001;
    public static final int BOT_TURN_DELAY_MS = 3000;
}
