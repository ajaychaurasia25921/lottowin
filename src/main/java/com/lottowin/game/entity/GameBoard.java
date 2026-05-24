package com.lottowin.game.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@MongoEntity(collection = "gameboards")
public class GameBoard extends PanacheMongoEntity {

    public static final String STATE_OPEN = "OPEN";
    public static final String STATE_CARD_SELECTION = "CARD_SELECTION";
    public static final String STATE_CARD_SWAP = "CARD_SWAP";
    public static final String STATE_DRAWING = "DRAWING";
    public static final String STATE_COMPLETED = "COMPLETED";

    public int capacity;
    public long entryFeeCoins;
    public String state = STATE_OPEN;

    public List<String> players = new ArrayList<>();
    public List<Integer> availableNumbers = new ArrayList<>();
    public Map<String, Integer> playerAssignments = new HashMap<>();

    public long totalPoolCoins;
    public long platformFeeCoins;
    public long winnerPayoutCoins;
    public int winningNumber;
    public String winnerUserId;

    public Instant createdAt = Instant.now();
    public Instant cardSelectionStartedAt;
    public Instant cardSelectionEndsAt;
    public Instant cardSwapStartedAt;
    public Instant cardSwapEndsAt;
    public Instant completedAt;

    public boolean cardSelectionTimerScheduled;
    public boolean cardSwapTimerScheduled;

    public static boolean isSupportedCapacity(int capacity) {
        return capacity == 5 || capacity == 10 || capacity == 15 || capacity == 20;
    }

    public boolean isFull() {
        return players.size() >= capacity;
    }

    public boolean hasPlayer(String userId) {
        return players.contains(userId);
    }

    public boolean hasAssignment(String userId) {
        return playerAssignments.containsKey(userId);
    }
}
