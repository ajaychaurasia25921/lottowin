package com.lottowin.game.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import java.time.Instant;

@MongoEntity(collection = "userwallets")
public class UserWallet extends PanacheMongoEntity {

    public String userId;
    public long balanceCoins;
    public Instant createdAt = Instant.now();
    public Instant updatedAt = Instant.now();

    public static UserWallet findOrCreate(String userId, long jwtWalletBalance) {
        UserWallet wallet = find("userId", userId).firstResult();
        if (wallet != null) {
            return wallet;
        }

        UserWallet created = new UserWallet();
        created.userId = userId;
        created.balanceCoins = Math.max(jwtWalletBalance, 0);
        created.persist();
        return created;
    }

    public void credit(long coins) {
        if (coins <= 0) {
            throw new IllegalArgumentException("Credit amount must be positive.");
        }
        balanceCoins += coins;
        updatedAt = Instant.now();
        update();
    }

    public void debit(long coins) {
        if (coins <= 0) {
            throw new IllegalArgumentException("Debit amount must be positive.");
        }
        if (balanceCoins < coins) {
            throw new IllegalStateException("Wallet balance cannot go negative.");
        }
        balanceCoins -= coins;
        updatedAt = Instant.now();
        update();
    }
}
