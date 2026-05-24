package com.lottowin.game.rest;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record ErrorResponse(
        @JsonProperty("error")
        String error,
        @JsonProperty("status")
        int status,
        @JsonProperty("timestamp")
        Instant timestamp) {

    public static ErrorResponse of(String error, int status) {
        return new ErrorResponse(error, status, Instant.now());
    }
}
