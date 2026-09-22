---
name: code-review-checklist
description: Review checklist for WhenHere backend diffs — correctness, authz, layering, migrations, performance, tests.
---

- [ ] **Authorization:** every endpoint checks ownership or friendship. IDs taken from the path are never trusted without a check (IDOR).
- [ ] **Validation:** sizes, enums, timestamps (reject far-future dates), base64 decoding, pagination limits.
- [ ] **Layering:** routes are thin. Controllers have no Ktor or Exposed types. Services depend on interfaces. Wiring happens in `AppModule`.
- [ ] **Migrations:** changes only in a new file, backward-compatible, indexes on foreign keys and lookup columns, cascades correct for account deletion.
- [ ] **Transactions:** multi-row writes are atomic. No N+1 queries in loops. No blocking calls on Netty threads.
- [ ] **Errors:** consistent error JSON. No stack traces or internal details leaked.
- [ ] **Push:** handles FCM invalid-token responses by deleting the device row. Messages are data-only with high priority.
- [ ] **Rate limits** apply to invites and events.
- [ ] **Privacy:** see the `privacy-security-check` skill.
- [ ] **Tests:** added for new branches, including negative authorization cases. `./gradlew test` is green.
- [ ] **Docs:** `openapi.yaml` and README are updated.
