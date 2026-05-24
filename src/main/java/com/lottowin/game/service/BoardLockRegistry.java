package com.lottowin.game.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@ApplicationScoped
public class BoardLockRegistry {

    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    public Object lockFor(String boardId) {
        return locks.computeIfAbsent(boardId, ignored -> new Object());
    }
}
