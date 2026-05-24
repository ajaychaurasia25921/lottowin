package com.lottowin.game.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import java.time.Instant;

@MongoEntity(collection = "walletledger")
public class WalletLedgerEntry extends PanacheMongoEntity {

    public String userId;
    public long amountCoins;
    public long balanceAfterCoins;
    public String direction;
    public String reason;
    public String referenceId;
    public Instant createdAt = Instant.now();

    public static void record(String userId, long amountCoins, long balanceAfterCoins, String direction, String reason, String referenceId) {
        WalletLedgerEntry entry = new WalletLedgerEntry();
        entry.userId = userId;
        entry.amountCoins = amountCoins;
        entry.balanceAfterCoins = balanceAfterCoins;
        entry.direction = direction;
        entry.reason = reason;
        entry.referenceId = referenceId;
        entry.persist();
    }
}
