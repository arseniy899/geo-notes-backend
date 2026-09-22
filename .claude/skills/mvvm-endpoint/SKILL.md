---
name: mvvm-endpoint
description: Recipe for adding/changing an HTTP endpoint in the WhenHere Ktor backend using the MVVM-style layering (route = View, controller = ViewModel, services/repositories = Model).
---

# Endpoint recipe

```
routes/FooRoutes.kt          View:      parse → controller → call.respond
controllers/FooController.kt ViewModel: validate, orchestrate, map to FooResponse
domain/FooService.kt         Model:     business rules; depends on FooRepository interface
domain/FooRepository.kt      Model:     interface
persistence/ExposedFooRepository.kt     implementation
api/model/FooModels.kt       request/response DTOs (@Serializable, immutable)
```

## 1. Migration
`src/main/resources/db/migration/V<n>__add_foo.sql`. Include foreign keys with `ON DELETE CASCADE` wherever the row belongs to a user.

## 2. Repository
```kotlin
interface FooRepository { suspend fun create(ownerId: UserId, blob: ByteArray): Foo; suspend fun findOwned(ownerId: UserId): List<Foo> }
```
The Exposed implementation wraps calls in `suspendTransaction` / `newSuspendedTransaction` on the IO dispatcher.

## 3. Service (business rules)
```kotlin
class FooService(private val repo: FooRepository, private val friends: FriendRepository) {
    suspend fun create(caller: UserId, cmd: CreateFoo): Foo {
        if (!friends.areFriends(caller, cmd.recipient)) throw DomainError.Forbidden("not a friend")
        return repo.create(caller, cmd.blob)
    }
}
```

## 4. Controller (the ViewModel of the API)
```kotlin
class FooController(private val service: FooService) {
    suspend fun create(caller: UserId, req: CreateFooRequest): FooResponse {
        val cmd = req.validate()          // throws DomainError.BadRequest
        return service.create(caller, cmd).toResponse()
    }
}
```
It must not use `ApplicationCall` or Exposed. Test it with fake repositories.

## 5. Route (thin)
```kotlin
fun Route.fooRoutes(c: FooController) = authenticate {
    post("/v1/foo") { call.respond(HttpStatusCode.Created, c.create(call.userId(), call.receive())) }
}
```

## 6. Wiring and docs
- Register the new pieces in `AppModule` and in the routing setup.
- Update `openapi.yaml` and the API table in README.md.

## 7. Tests
- controller unit tests: happy path plus every `DomainError` branch
- one `testApplication` integration test: 2xx path, plus 401 or 403

## Anti-patterns
- Database queries in routes or controllers
- `call.receive` inside a service
- Returning Exposed rows or entities directly as JSON
- Business rules duplicated across controllers
