package com.lottowin.game.service;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class MongoIndexInitializer {

    private static final Logger LOG = Logger.getLogger(MongoIndexInitializer.class);

    @Inject MongoClient mongoClient;

    @ConfigProperty(name = "quarkus.mongodb.database")
    String databaseName;

    void onStart(@Observes StartupEvent event) {
        MongoDatabase database = mongoClient.getDatabase(databaseName);
        database.getCollection("userwallets")
                .createIndex(Indexes.ascending("userId"), new IndexOptions().unique(true).background(true));
        database.getCollection("paymenttransactions")
                .createIndex(Indexes.ascending("razorpayOrderId"), new IndexOptions().unique(true).background(true));
        database.getCollection("paymenttransactions")
                .createIndex(Indexes.ascending("userId", "status"), new IndexOptions().background(true));
        database.getCollection("gameboards")
                .createIndex(Indexes.ascending("state", "cardSelectionEndsAt", "cardSwapEndsAt"), new IndexOptions().background(true));
        database.getCollection("walletledger")
                .createIndex(Indexes.ascending("userId", "createdAt"), new IndexOptions().background(true));
        LOG.infof("MongoDB indexes ensured for database %s", databaseName);
    }
}
