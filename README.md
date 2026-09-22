# WhenHere backend

Backend MVP for **WhenHere** (working name), an Android app for location-triggered reminders and alarms.

Places and rules live **on the device**. This service only exists for:

1. **The friend feature.** "When any friend enters place X, remind me about the gift." Friends, invites,
   encrypted place shares and fan-out of transition events via FCM.
2. **Entitlement verification.** Google Play purchases (verified server-side with the Play Developer API, kept
   current via Real-time Developer Notifications) to a Pro flag.

Kotlin 2.4.20 · JVM 21 · Ktor 3.6.0 (Netty) · kotlinx.serialization 1.11.0 · Exposed 1.5.0 · PostgreSQL 17 ·
HikariCP 7.1.0 · Flyway 13.7.0 · firebase-admin 9.10.0 · google-auth-library 1.52.0 · Ktor client (CIO) · Gradle 9.7.1

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
* **Purchases:** the `entitlements` row stores product, Play state, expiry, auto-renew/acknowledged/test flags,
  the SHA-256 `token_hash` (unique; used for RTDN lookups and the one-token-one-account rule) and the raw purchase
  token. The raw token is kept because re-verification must send it to Google; on its own it is useless without our
  service-account key, and Google recommends keeping it. It is never logged. No order ids, prices, emails or
  obfuscated account ids from Play are stored.

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
                push/  billing/  auth/   edge adapters behind interfaces (PushSender, PlayPurchaseVerifier,
                                         TokenVerifier, PubSubTokenVerifier)
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
├── auth/                 TokenVerifier (Firebase/dev), PubSubTokenVerifier (Pub/Sub push OIDC JWT)
├── billing/              PlayPurchaseVerifier port, GooglePlayPurchaseVerifier (Android Publisher v3), stub
├── config/               AppConfig (env), DatabaseFactory (Hikari + Flyway), Firebase init
├── controllers/          ViewModels
├── domain/               model/, repository/ (interfaces), service/, DomainErrors.kt
├── persistence/          Exposed tables + repository implementations
├── plugins/              Ktor plugins
├── push/                 PushSender, FcmPushSender, LoggingPushSender
└── routes/               Views
src/main/resources/db/migration/V1__init.sql, V3__entitlements_play.sql
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
| GET | `/v1/entitlements` | Current entitlement `{pro, state, expiresAt, productId, autoRenewing, lastVerifiedAt}`. Re-verified with Play if the expiry passed |
| POST | `/v1/entitlements/verify` | `{purchaseToken, productId}` → entitlement. Verified with Google Play, acknowledged server-side |
| POST | `/v1/play/rtdn` | **Server-to-server.** Pub/Sub push of Play Real-time Developer Notifications. Auth: Pub/Sub OIDC JWT, not a user token |

**Business rules**

* Share recipients must be **accepted friends**, and each `deviceId` must belong to that friend.
* **Max 20 active shares per owner**, because Android geofence budget is 100 per app and the rest is kept
  for local places. Deactivating a share frees a slot.
* Events are accepted only from the share owner. They are **ignored** (202, `status: IGNORED`) if the share
  is inactive or paused, the transition is not subscribed, or `occurredAt` is more than 24 h old. Stored
  events expire after **7 days** (hourly cleanup coroutine, which also purges expired invites).
* FCM tokens reported `UNREGISTERED` are cleared, while the device and its sealed keys are kept.
* A Play purchase token can unlock Pro for one account only.
* Pro = Play state `ACTIVE` or `IN_GRACE_PERIOD` (and not past `expiresAt`), or `CANCELED` until `expiresAt`.
  `pro_lifetime` is `ACTIVE` with no expiry. `ON_HOLD`, `PAUSED`, `PENDING`, `EXPIRED`, `REVOKED` (refund/void),
  `REPLACED` (upgraded away) and `INVALID` (unknown token) grant nothing. `REVOKED` and `REPLACED` are final.
* A valid entitlement is never overwritten by a non-Pro verification, and a valid lifetime purchase is never replaced
  by a subscription. On upgrade/downgrade the old token (`linkedPurchaseToken`) becomes `REPLACED`.

**Errors** always look like this:

```json
{ "error": { "code": "share_limit_reached", "message": "At most 20 active shares per user", "details": [] } }
```

| Status | Codes |
|---|---|
| 400 | `validation_failed`, `bad_request`, `invite_self`, `recipient_device_invalid`, `recipient_is_owner`, `unknown_product` |
| 401 | `unauthorized` (also: bad Pub/Sub token on `/v1/play/rtdn`) |
| 403 | `not_a_friend`, `recipient_not_friend`, `not_share_owner` |
| 404 | `not_found`, `user_not_registered` (GET/DELETE me), `invite_not_found`, `friend_not_found`, `share_not_found`, `device_not_found` |
| 409 | `user_not_registered` (other endpoints), `invite_expired`, `invite_used`, `purchase_token_in_use` |
| 422 | `share_limit_reached` |
| 429 | `rate_limited` |
| 503 | `billing_unavailable` (Google Play unreachable, timed out, rate limited or service account misconfigured; `Retry-After` header) |

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

`AUTH_MODE=dev` accepts `Bearer dev:<uid>` and authenticates as `<uid>`. It also replaces the Google Play verifier
with a stub that never calls Google and grants Pro for tokens starting with `test-valid`. **Never enable it in production.** With `APP_ENV=production`
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
| `PLAY_PACKAGE_NAME` | `com.ars899.geonotes` | Android application id whose purchases are verified |
| `PLAY_SERVICE_ACCOUNT_JSON` | `GOOGLE_APPLICATION_CREDENTIALS` | Service-account JSON with Play Developer API access. Falls back to `GOOGLE_APPLICATION_CREDENTIALS`, then ADC. Required when `AUTH_MODE=firebase` |
| `PLAY_ALLOW_TEST_PURCHASES` | `true` | Whether license-tester purchases (`testPurchase`) grant Pro. Set `false` once you no longer need tester access in production |
| `RTDN_AUDIENCE` | *(unset)* | Expected `aud` of the Pub/Sub push token, e.g. `https://api.example.com/v1/play/rtdn`. RTDN is disabled (401) unless this and the next one are set |
| `RTDN_PUSH_SERVICE_ACCOUNT` | *(unset)* | Expected `email` of the push token: the service account configured on the push subscription |
| `RATE_LIMIT_EVENTS_PER_MINUTE` | `60` | Per-user limit on `POST /v1/events` |
| `RATE_LIMIT_INVITES_PER_MINUTE` | `10` | Per-user limit on invite create/accept |

---

## Google Play billing setup (owner, one-time)

Production (`AUTH_MODE=firebase`) verifies every purchase with the
[Android Publisher API v3](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2):

| Product | Type | API |
|---|---|---|
| `pro_monthly`, `pro_yearly` | subscription | `purchases.subscriptionsv2.get`, acknowledge via `purchases.subscriptions.acknowledge` |
| `pro_lifetime` | one-time product | `purchases.productsv2.getproductpurchasev2`, acknowledge via `purchases.products.acknowledge` |

1. **Products.** In Play Console → *Monetize with Play → Products*, create subscriptions `pro_monthly` and
   `pro_yearly` and the one-time product `pro_lifetime` (ids must match exactly).
2. **Enable the API.** In Google Cloud Console (the project that will own the Pub/Sub topic, too) enable
   *Google Play Android Developer API*.
3. **Service account for verification.** *IAM & Admin → Service accounts → Create* (e.g.
   `play-billing@<project>.iam.gserviceaccount.com`, no Cloud roles needed). Create a JSON key and store it on the
   server as `./secrets/play-service-account.json` (never commit it). You may reuse the Firebase service account
   instead; then leave `PLAY_SERVICE_ACCOUNT_JSON` unset.
4. **Grant Play access.** Play Console → *Users and permissions → Invite new users* → the service-account email.
   Under the app (or account) permissions grant **View financial data, orders and cancellation survey responses**
   and **Manage orders and subscriptions**. Changes can take up to 24 h to apply; until then Google answers 401/403
   and the API returns `503 billing_unavailable` (logged as a misconfiguration).
5. **Pub/Sub topic.** Create topic `play-rtdn`. On the topic's permissions add
   `google-play-developer-notifications@system.gserviceaccount.com` with role **Pub/Sub Publisher**.
6. **Push identity.** Create a second service account, e.g. `play-rtdn-push@<project>.iam.gserviceaccount.com`
   (no roles). In projects created before April 2021 also grant the Pub/Sub service agent
   `service-<project-number>@gcp-sa-pubsub.iam.gserviceaccount.com` the **Service Account Token Creator** role.
7. **Push subscription.** On `play-rtdn` create a subscription: delivery type **Push**, endpoint
   `https://api.<domain>/v1/play/rtdn`, **Enable authentication** with the `play-rtdn-push` service account,
   audience `https://api.<domain>/v1/play/rtdn`. Set `RTDN_AUDIENCE` to that audience and
   `RTDN_PUSH_SERVICE_ACCOUNT` to the service-account email in `.env`.
8. **Connect Play.** Play Console → the app → *Monetize with Play → Monetization setup → Real-time developer
   notifications*: enable, topic `projects/<project>/topics/play-rtdn`, choose
   **Get all notifications for subscriptions and one-time products**, save, then **Send test message**. The server
   logs `RTDN Test … → IGNORED` and answers 200.
9. **Testers.** Add license testers (Play Console → *Settings → License testing*). Their purchases carry
   `testPurchase` and grant Pro while `PLAY_ALLOW_TEST_PURCHASES=true`.

**How it flows:** the app sends `{purchaseToken, productId}` to `POST /v1/entitlements/verify` after every purchase
and on restore. The server asks Play, stores the result per user and acknowledges the purchase if needed (the app may
acknowledge too; both are idempotent). Renewals, cancellations, holds, expiries, upgrades and refunds arrive as RTDN;
the server re-reads the token from Play (a notification is only a hint) and updates the row. `voidedPurchaseNotification`
revokes directly. If Play is down during an RTDN the endpoint answers 503 and Pub/Sub retries with backoff.

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
* **Billing tests** cover the Play response mapping against recorded JSON fixtures (`src/test/resources/play/`:
  active, grace, on hold, canceled-not-expired, expired, pending, upgrade/test, lifetime purchased/refunded), the HTTP
  adapter over a Ktor `MockEngine` (URLs, 404/410/400 → invalid, 401/403/429/5xx/timeout → 503), the Pub/Sub JWT
  checks with a locally generated RSA key, the entitlement rules and RTDN handling with fakes, and
  `/v1/entitlements*` + `/v1/play/rtdn` end to end against PostgreSQL (fake Play + fake Pub/Sub verifier).
* The database comes from **Testcontainers** (`postgres:17-alpine`, needs Docker). To use an existing
  server instead, set `TEST_DATABASE_URL` (+ `TEST_DATABASE_USER`, `TEST_DATABASE_PASSWORD`). Tests
  truncate all tables, so use a disposable database.

CI (`.github/workflows/backend.yml`) runs `./gradlew test build` on JDK 21 and builds the Docker image.

---

## TODO / known gaps

* Billing: RTDN messages are not de-duplicated by `messageId` (handling is idempotent, so redeliveries only cost a
  Play API call). `pendingRefundReviewNotification` is ignored. There is no periodic sweep of expiring subscriptions;
  state is refreshed by RTDN and lazily on `GET /v1/entitlements`. `GET /v1/me` shows the stored state without
  re-verifying.
* Billing: only one entitlement row per user. A user who holds both a subscription and a lifetime purchase keeps the
  lifetime one; the subscription token is not bound.
* Push is sent synchronously inside `POST /v1/events`. For scale, move it to an outbox or queue with retries.
* Account deletion does not delete the Firebase Auth user. The client should do that, or add an admin SDK call.
* The 20-share limit check is not serialized per owner. Two concurrent creates could exceed it by one.
  A row lock on the owner would close that gap.
* Rate-limit buckets are in-memory (single instance). Use a shared store if the service ever scales out.
* Firebase ID-token revocation is not checked (`verifyIdToken(token, checkRevoked = true)` costs a network call).
