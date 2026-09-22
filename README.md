# WhenHere backend

Backend MVP for **WhenHere** (working name), an Android app for location-triggered reminders and alarms.

Places and rules live **on the device**. This service only exists for:

1. **The friend feature.** "When any friend enters place X, remind me about the gift." Friends, invites,
   encrypted place shares and fan-out of transition events via FCM.
2. **Entitlement verification.** Google Play purchase to Pro flag (stubbed for now).

Kotlin 2.4.20 · JVM 21 · Ktor 3.6.0 (Netty) · kotlinx.serialization 1.11.0 · Exposed 1.5.0 · PostgreSQL 17 ·
HikariCP 7.1.0 · Flyway 13.7.0 · firebase-admin 9.10.0 · Gradle 9.7.1

---

## Privacy model

The server **never receives coordinates**.

```
 Owner's phone (Alice)                         Server                               Friend's phone (Bob)
 ─────────────────────                         ──────                               ────────────────────
 place = {lat, lon, radius, label}
 K = random content key
 encryptedPlace = AEAD(K, place)
 for each Bob device d:                  GET /v1/friends/bob/devices ──▶  public keys only
   sealedKey_d = seal(K, pub_d)
 POST /v1/shares {encryptedPlace, ──────▶ stores opaque blobs ──────────▶ GET /v1/shares (received)
   recipients:[{bob, d, sealedKey_d}]}     (cannot decrypt)                 K = open(sealedKey_d, priv_d)
                                                                            place = decrypt → shows "Alice at <label>"
 Geofence evaluated LOCALLY on Alice's phone
 on ENTER: POST /v1/events ─────────────▶ {shareId, transition, ts} ─FCM─▶ data msg {type: friend_transition,
           {shareId, ENTER, occurredAt}   stored 7 days, then purged        shareId, fromUserId, transition, occurredAt}
                                                                            → Bob's app decrypts & shows the reminder
```

* **Shared place definitions are end-to-end encrypted per share.** The client encrypts with a fresh
  content key and seals that key to each recipient **device** public key. The server stores only
  `encrypted_place` / `sealed_key` as opaque `BYTEA`.
* **The person whose movement triggers the share (the owner) evaluates the geofence locally.** Only
  `{shareId, transition, occurredAt}` reaches the server. There is no location, and no location history.
* **No PostGIS, and no geo columns in the schema.** The server does no spatial computation because it holds
  no coordinates. Even a full database dump shows only *who shares something with whom* and *when a share
  fired* (events expire after 7 days).
* FCM messages are **data-only** (no notification payload). The receiving app renders the reminder after
  decrypting the share locally.
* Logs contain method, path, status and request id. They never contain bodies, bearer tokens or FCM tokens.
* Unfriending revokes all shares between the two users. `DELETE /v1/me` cascades **everything**.

---

## Architecture: MVVM adapted to an HTTP API

```
View            routes/              thin Ktor bindings: parse path/body → request DTO, call controller, respond
  │
ViewModel       controllers/         one per feature; takes request DTO + caller id, orchestrates services,
  │                                  maps domain results → immutable response models (api/model/*Response)
  │                                  pure Kotlin: no ApplicationCall, unit-testable with fakes
  ▼
Model           domain/service/      business rules (friendship, share budget, event gating, TTLs …)
                domain/model/        entities (User, Device, Share, FriendEvent …)
                domain/repository/   repository INTERFACES + TransactionRunner
                     ▲
                persistence/         Exposed/PostgreSQL implementations of those interfaces
                push/  billing/  auth/   edge adapters behind interfaces (PushSender, PlayPurchaseVerifier, TokenVerifier)
```

* Dependency direction: `routes → controllers → domain services → repository interfaces ← persistence`.
* **Composition root:** `AppModule` (manual constructor DI, no framework). `main()` builds it from env.
  Tests build it with `FakePushSender`, `DevTokenVerifier` and a `MutableClock`.
* `api/model/` holds the request DTOs and the response "view state" (`@Serializable`, immutable).
* `plugins/` holds the Ktor cross-cutting concerns: Serialization, Auth, StatusPages, RateLimit, Monitoring
  (CallId, CallLogging), RequestValidation and the background cleanup job.
* Syntactic request validation happens in `plugins/Validation.kt`. Semantic rules live in domain services
  and throw `DomainException`s, which StatusPages maps to HTTP.

```
src/main/kotlin/com/geonotes/backend/
├── Application.kt        main() + Application.module(AppModule)
├── AppModule.kt          composition root
├── api/model/            request/response DTOs
├── auth/                 TokenVerifier, FirebaseTokenVerifier, DevTokenVerifier
├── billing/              PlayPurchaseVerifier (+ stub)
├── config/               AppConfig (env), DatabaseFactory (Hikari + Flyway), Firebase init
├── controllers/          ViewModels
├── domain/               model/, repository/ (interfaces), service/, DomainErrors.kt
├── persistence/          Exposed tables + repository implementations
├── plugins/              Ktor plugins
├── push/                 PushSender, FcmPushSender, LoggingPushSender
└── routes/               Views
src/main/resources/db/migration/V1__init.sql
```

---

## API

All `/v1` endpoints require `Authorization: Bearer <Firebase ID token>`. In `AUTH_MODE=dev` the token is
`dev:<uid>`. Binary fields are base64 and timestamps are ISO-8601 UTC. The full spec is in [`openapi.yaml`](openapi.yaml).

| Method | Path | Description |
|---|---|---|
| GET | `/health` | Liveness + DB check (no auth) |
| PUT | `/v1/me` | Create/update profile `{displayName}`. Required before other calls |
| GET | `/v1/me` | Profile + entitlement `{pro, expiresAt}` |
| DELETE | `/v1/me` | **Account deletion** (Google Play requirement). Cascades all data |
| POST | `/v1/devices` | Register device `{deviceId, fcmToken, publicKey, platform}` |
| DELETE | `/v1/devices/{id}` | Unregister own device |
| POST | `/v1/invites` | Create single-use invite `{code, expiresAt}` (48 h TTL, rate limited) |
| POST | `/v1/invites/{code}/accept` | Accept, which creates a mutual friendship (rate limited) |
| GET | `/v1/friends` | List friends |
| DELETE | `/v1/friends/{userId}` | Unfriend (either side). Revokes shares between the two |
| GET | `/v1/friends/{userId}/devices` | Friend's device public keys (accepted friends only) |
| POST | `/v1/shares` | Create share `{encryptedPlace, recipients:[{userId, deviceId, sealedKey}], transitions}` |
| GET | `/v1/shares` | `{owned, received}`. Received shares include only the caller's sealed keys |
| PATCH | `/v1/shares/{id}` | `{active?, pausedUntil?}` (`pausedUntil: null` clears) |
| DELETE | `/v1/shares/{id}` | Delete own share |
| POST | `/v1/events` | Owner reports `{shareId, transition, occurredAt}` → FCM fan-out (rate limited) |
| POST | `/v1/entitlements/verify` | `{purchaseToken, productId}` → `{pro, expiresAt}` (Play verification stubbed) |

**Business rules**

* Share recipients must be **accepted friends**, and each `deviceId` must belong to that friend.
* **Max 20 active shares per owner**, because Android geofence budget is 100 per app and the rest is kept
  for local places. Deactivating a share frees a slot.
* Events are accepted only from the share owner. They are **ignored** (202, `status: IGNORED`) if the share
  is inactive or paused, the transition is not subscribed, or `occurredAt` is more than 24 h old. Stored
  events expire after **7 days** (hourly cleanup coroutine, which also purges expired invites).
* FCM tokens reported `UNREGISTERED` are cleared, while the device and its sealed keys are kept.
* A Play purchase token can unlock Pro for one account only.

**Errors** always look like this:

```json
{ "error": { "code": "share_limit_reached", "message": "At most 20 active shares per user", "details": [] } }
```

| Status | Codes |
|---|---|
| 400 | `validation_failed`, `bad_request`, `invite_self`, `recipient_device_invalid`, `recipient_is_owner`, `unknown_product` |
| 401 | `unauthorized` |
| 403 | `not_a_friend`, `recipient_not_friend`, `not_share_owner` |
| 404 | `not_found`, `user_not_registered` (GET/DELETE me), `invite_not_found`, `friend_not_found`, `share_not_found`, `device_not_found` |
| 409 | `user_not_registered` (other endpoints), `invite_expired`, `invite_used`, `purchase_token_in_use` |
| 422 | `share_limit_reached` |
| 429 | `rate_limited` |

---

## Running locally

Requirements: JDK 21 and Docker. The Gradle wrapper downloads Gradle 9.7.1.

```bash
# 1. PostgreSQL 17
docker run -d --name whenhere-db -p 5432:5432 \
  -e POSTGRES_DB=geonotes -e POSTGRES_USER=geonotes -e POSTGRES_PASSWORD=secret postgres:17

# 2. App in dev auth mode (no Firebase needed; pushes are logged instead of sent)
DATABASE_URL=jdbc:postgresql://localhost:5432/geonotes DATABASE_USER=geonotes DATABASE_PASSWORD=secret \
AUTH_MODE=dev ./gradlew run

# 3. Try it
curl localhost:8080/health
curl -X PUT localhost:8080/v1/me -H 'Authorization: Bearer dev:alice' \
     -H 'Content-Type: application/json' -d '{"displayName":"Alice"}'
curl -X POST localhost:8080/v1/invites -H 'Authorization: Bearer dev:alice'
```

`AUTH_MODE=dev` accepts `Bearer dev:<uid>` and authenticates as `<uid>`. It also lets the Play stub grant
Pro for tokens starting with `test-valid`. **Never enable it in production.** With `APP_ENV=production`
(as set in `docker-compose.yml`) the server refuses to start in dev mode.

The full stack (Caddy + app + Postgres) runs with `docker compose up -d --build`. See [`deploy/README.md`](deploy/README.md).

### Environment variables

| Variable | Default | Description |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/geonotes` | JDBC URL |
| `DATABASE_USER` | `geonotes` | DB user |
| `DATABASE_PASSWORD` | *(required)* | DB password |
| `DATABASE_POOL_SIZE` | `10` | Hikari max pool size |
| `AUTH_MODE` | `firebase` | `firebase` (verify Firebase ID tokens) or `dev` (`dev:<uid>` tokens) |
| `APP_ENV` | *(unset)* | Set to `production` to make startup fail if `AUTH_MODE=dev` (set in `docker-compose.yml`) |
| `GOOGLE_APPLICATION_CREDENTIALS` | *(ADC)* | Firebase service-account JSON path. Used for token verification and FCM. Without it, FCM is disabled (pushes are logged) and `firebase` auth mode refuses to start |
| `RATE_LIMIT_EVENTS_PER_MINUTE` | `60` | Per-user limit on `POST /v1/events` |
| `RATE_LIMIT_INVITES_PER_MINUTE` | `10` | Per-user limit on invite create/accept |

---

## Tests

```bash
./gradlew test
```

* **Controller unit tests** (`controllers/*ControllerTest`) cover the ViewModels plus real domain services
  over in-memory fake repositories and a `FakePushSender`. They need no DB and no Ktor.
* **Integration tests** (`integration/*IntegrationTest`) use Ktor `testApplication` against a **real
  PostgreSQL 17** with the Flyway migrations. They cover invites/friends, shares (friend-only recipients,
  20-share limit), event fan-out (asserting pushes), account-deletion cascade, auth rejection and rate limits.
* The database comes from **Testcontainers** (`postgres:17-alpine`, needs Docker). To use an existing
  server instead, set `TEST_DATABASE_URL` (+ `TEST_DATABASE_USER`, `TEST_DATABASE_PASSWORD`). Tests
  truncate all tables, so use a disposable database.

CI (`.github/workflows/backend.yml`) runs `./gradlew test build` on JDK 21 and builds the Docker image.

---

## TODO / known gaps

* **Google Play verification is a stub** (`billing/StubPlayPurchaseVerifier`). Next steps: implement
  `purchases.subscriptionsv2.get`, acknowledge purchases and consume RTDN via Pub/Sub.
* Push is sent synchronously inside `POST /v1/events`. For scale, move it to an outbox or queue with retries.
* Account deletion does not delete the Firebase Auth user. The client should do that, or add an admin SDK call.
* The 20-share limit check is not serialized per owner. Two concurrent creates could exceed it by one.
  A row lock on the owner would close that gap.
* Rate-limit buckets are in-memory (single instance). Use a shared store if the service ever scales out.
* Firebase ID-token revocation is not checked (`verifyIdToken(token, checkRevoked = true)` costs a network call).
