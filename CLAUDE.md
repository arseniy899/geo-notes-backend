# WhenHere backend: guide for AI agents

This is the Kotlin/Ktor backend for WhenHere, an app for geolocation-triggered reminders. It exists **only** for friend and social features (invites, friendships, end-to-end encrypted shared places, a relay for transition events, FCM push) and for entitlements.
**The server never stores coordinates.** Shared places arrive as opaque ciphertext. Product docs live in the `geo-notes-app` repo under `docs/`.

## Architecture (non-negotiable): MVVM adapted to an HTTP API
```
routes/        View        thin Ktor routes: parse request → call controller → respond. No logic, no DB.
controllers/   ViewModel   per feature: validate + orchestrate services, map domain → immutable *Response models.
                           Pure Kotlin, no ApplicationCall. Unit-tested with fakes.
domain/        Model       services (business rules), entities, repository & port INTERFACES
persistence/   Model impl  Exposed repositories + Flyway migrations
push/, billing/  adapters  FCM sender and Play purchase verifier behind interfaces
AppModule      composition root (manual DI)
```
Dependencies point this way: routes → controllers → services → interfaces ← implementations.

## Commands
- Tests: `./gradlew test`
- Build: `./gradlew build`
- Local run: `AUTH_MODE=dev ./gradlew run`, or `docker compose up`

## Team agents (`.claude/agents/`)
| Agent | Use for |
|---|---|
| `architect` | API and data-model design, ADRs, privacy protocol decisions |
| `developer` | Implementing endpoints and features following the layering |
| `reviewer` | Read-only review: correctness, authorization, privacy, layering |
| `tester` | Integration and controller tests, running the suites |

Workflow: architect → developer → tester → reviewer → PR.

## Skills (`.claude/skills/`)
`adr`, `mvvm-endpoint`, `code-review-checklist`, `privacy-security-check`, `test-strategy`.

## Conventions
- Every new endpoint is documented in `openapi.yaml` and README.md.
- Schema changes go only through a new Flyway migration `V<n>__desc.sql`. Never edit an applied migration.
- Never log tokens, ciphertext payloads or personal data. Never commit secrets or service-account JSON.
- Conventional commits.
