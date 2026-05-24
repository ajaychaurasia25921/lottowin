package com.lottowin.game.health;

import com.mongodb.client.MongoClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class MongoReadinessCheck implements HealthCheck {

    @Inject MongoClient mongoClient;

    @Override
    public HealthCheckResponse call() {
        try {
            mongoClient.listDatabaseNames().first();
            return HealthCheckResponse.up("mongodb");
        } catch (RuntimeException exception) {
            return HealthCheckResponse.down("mongodb");
        }
    }
}
