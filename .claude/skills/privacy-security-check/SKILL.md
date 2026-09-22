---
name: privacy-security-check
description: GDPR/privacy and security audit for WhenHere backend changes — no coordinates server-side, E2E blobs opaque, data minimisation, retention, deletion cascade, logging hygiene, auth token handling.
---

Check each item and report it as ✅ / ⚠️ / ❌ with the file and line:
1. **No location on the server.** No latitude, longitude, address or place-name fields in DTOs, tables or logs. Shared places are opaque `encryptedPlace` blobs. The server never decrypts them.
2. **Data minimisation.** Store only what the feature needs. Event rows hold `{shareId, fromUserId, transition, occurredAt}` and are deleted after 7 days.
3. **Deletion.** `DELETE /v1/me` removes all of the user's rows (devices, friendships, shares, recipients, events, entitlements, invites). A test covers it.
4. **Consent.** A share only reaches accepted friends. Either side can revoke it, and the effect is immediate: no more pushes after unfriending or pausing.
5. **Logging.** No bearer tokens, FCM tokens, ciphertext, emails or display names at info level or above.
6. **Auth.** Firebase ID tokens are verified (issuer, audience, expiry). `AUTH_MODE=dev` can't be enabled in production (checked at startup).
7. **Transport and secrets.** TLS through Caddy. Secrets come only from env or files, never from the repo.
8. **Abuse.** Rate limits on invites and events. Invite codes are high-entropy, expire after 48 hours, and are single-use.
9. **GDPR documents.** Update the Data safety form and privacy policy notes whenever collected data changes.
