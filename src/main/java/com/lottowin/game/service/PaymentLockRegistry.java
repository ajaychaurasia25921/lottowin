package com.lottowin.game.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@ApplicationScoped
public class PaymentLockRegistry {

    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    public Object lockFor(String razorpayOrderId) {
        return locks.computeIfAbsent(razorpayOrderId, ignored -> new Object());
    }
}
