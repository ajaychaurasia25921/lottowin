# Lottowin Game Board Service

Quarkus backend for Lottowin game board lifecycle management, secured by Keycloak JWTs, backed by MongoDB Panache, and integrated with Razorpay top-ups.

## Stack

- Java 21
- Quarkus 3.35.3
- Gradle Groovy DSL
- gRPC for game board actions
- REST for Razorpay payment flows
- MongoDB with Panache Active Record
- SmallRye JWT with Keycloak-compatible bearer tokens
- SmallRye OpenAPI and Swagger UI
- SmallRye Health readiness checks
- Flyway SQL migrations for optional PostgreSQL audit/reporting tables

## API Surfaces

### gRPC

Proto file: `src/main/proto/gameboard.proto`

Service: `gameboard.v1.GameBoardService`

- `JoinBoard`
- `ChooseCard`
- `SwapCard`
- `GetBoardState`

Generated Java classes use package:

```text
com.lottowin.game.grpc
```

### REST

OpenAPI file: `src/main/resources/META-INF/openapi.yaml`

- `POST /payments/order`
- `POST /payments/verify`

Swagger UI:

```text
http://localhost:8080/swagger-ui
```

OpenAPI JSON:

```text
http://localhost:8080/q/openapi
```

Health:

```text
http://localhost:8080/q/health
http://localhost:8080/q/health/live
http://localhost:8080/q/health/ready
```

## Game Rules

- Supported board capacities are exactly `5`, `10`, `15`, and `20`.
- Entry fee is charged in game coins when a player joins.
- A full board transitions to `CARD_SELECTION`.
- Card pool size is `capacity * 5`.
- Players get 1 minute to choose a unique number.
- Missing selections are auto-assigned after 1 minute.
- Board then transitions to `CARD_SWAP`.
- Players get 20 seconds to swap with an unselected number.
- Draw selects one assigned number.
- Platform fee is 15% of total pool.
- Winner receives 85% of total pool.
- Corporate wallet receives the 15% platform fee.
- Completed boards are marked `COMPLETED`.

## Wallet Model

The JWT `sub` claim is the authenticated user id. The custom JWT claim `wallet_balance` is read on first wallet creation, but MongoDB is the mutable wallet source after that. This avoids stale Keycloak wallet claims breaking post-payment joins.

Wallet changes are written to:

- `userwallets`
- `walletledger`

Payment transactions are written to:

- `paymenttransactions`

Game boards are written to:

- `gameboards`

Mongo indexes are created at startup by `MongoIndexInitializer`.

## Razorpay

Conversion:

```text
5 Game Coins = 1 INR
1 INR = 100 paise
1 Game Coin = 20 paise
```

Local dev uses mock order ids when:

```properties
%dev.razorpay.dev-mock-enabled=true
```

Dev payment verification accepts:

```json
{
  "razorpay_signature": "dev-valid-signature"
}
```

Production must provide real Razorpay credentials:

```bash
export RAZORPAY_KEY_ID=rzp_live_xxx
export RAZORPAY_KEY_SECRET=xxx
export RAZORPAY_SANDBOX_MODE=false
```

## Security

All gRPC and REST endpoints are protected with:

```java
@RolesAllowed("user")
```

Expected JWT details:

- `sub`: user id
- `wallet_balance`: numeric wallet balance claim
- `realm_access.roles`: includes `user`

Production Keycloak config:

```bash
export KEYCLOAK_ISSUER=https://keycloak.example.com/realms/lottowin
export KEYCLOAK_JWKS_URL=https://keycloak.example.com/realms/lottowin/protocol/openid-connect/certs
```

Dev mode disables authorization checks for manual local loops:

```properties
%dev.quarkus.security.auth.enabled-in-dev-mode=false
%dev.lottowin.dev-security-bypass=true
```

Do not run the `%dev` profile in production.

## Configuration

Important environment variables:

```bash
export MONGODB_CONNECTION_STRING=mongodb://localhost:27017
export MONGODB_DATABASE=lottowin
export HTTP_PORT=8080
export GRPC_PORT=9000
export LOTTOWIN_MAX_TOPUP_COINS=100000
```

Optional Flyway/PostgreSQL audit reporting:

```bash
export POSTGRES_USER=lottowin
export POSTGRES_PASSWORD=lottowin
export POSTGRES_JDBC_URL=jdbc:postgresql://localhost:5432/lottowin
export DATASOURCE_HEALTH_ENABLED=true
export FLYWAY_MIGRATE_AT_START=true
```

Flyway is disabled by default so the Mongo-first service can start without PostgreSQL:

```properties
quarkus.flyway.migrate-at-start=false
```

## Flyway Scripts

Scripts live under:

```text
src/main/resources/db/migration
```

Current migrations:

- `V1__create_wallet_audit_tables.sql`
- `V2__create_gameboard_reporting_tables.sql`

These tables are intended for audit/reporting pipelines. MongoDB remains the transactional runtime store used by the service code.

## Local Development

Start MongoDB locally, then run:

```bash
./gradlew quarkusDev
```

This workspace currently does not include a generated Gradle wrapper. If Gradle is installed globally:

```bash
gradle quarkusDev
```

## Build

```bash
./gradlew clean build
```

## Notes For Production

- Use a real Keycloak realm and JWKS URL.
- Keep Razorpay live credentials in a secret manager.
- Run MongoDB as a replica set if you later add multi-document transactions.
- Keep one service instance per board partition, or replace JVM-local locks with distributed locking before horizontally scaling board joins for the same board ids.
- Keep `FLYWAY_MIGRATE_AT_START=false` unless PostgreSQL is available and intended.
