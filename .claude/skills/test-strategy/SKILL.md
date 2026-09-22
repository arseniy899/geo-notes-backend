---
name: test-strategy
description: Testing approach for the WhenHere Ktor backend — controller unit tests with fakes, testApplication integration tests against PostgreSQL, negative authz cases, deterministic time.
---

## Layers
1. **Service and controller unit tests** (fast, most of the suite). Use in-memory fakes of the repository interfaces and a `FakePushSender` that records pushes.
2. **Integration tests.** Ktor `testApplication` with the real module and database: PostgreSQL through Testcontainers (`postgres:17-alpine`), or the configured fallback. Run Flyway migrations for each test class and truncate between tests.
3. **Migration test.** Apply every migration to an empty database and check it validates.

## Always cover
- 401 without a token and 403 for non-friends or non-owners
- invite expired, invite reused, self-invite
- the 20-share limit; events on paused or inactive shares are ignored
- fan-out goes to recipients only, never to the sender
- account deletion cascades
- rate limit returns 429

## Determinism
- Inject `Clock`. No sleeps. Use `runTest` for coroutine code.

Command: `./gradlew test`. Report the pass and fail counts.
