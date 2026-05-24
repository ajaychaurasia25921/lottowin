package com.lottowin.game.entity;

import io.quarkus.mongodb.panache.PanacheMongoEntity;
import io.quarkus.mongodb.panache.common.MongoEntity;
import java.time.Instant;

@MongoEntity(collection = "paymenttransactions")
public class PaymentTransaction extends PanacheMongoEntity {

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_VERIFIED = "VERIFIED";

    public String userId;
    public String razorpayOrderId;
    public String razorpayPaymentId;
    public long coins;
    public long amountPaise;
    public String currency = "INR";
    public String status = STATUS_CREATED;
    public boolean sandbox;
    public Instant createdAt = Instant.now();
    public Instant verifiedAt;
}
