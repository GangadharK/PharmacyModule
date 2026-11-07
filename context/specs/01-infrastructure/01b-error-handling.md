# 01b — Error Handling

**Parent:** `01-infrastructure.md`
**Persona:** `@pharmacy-backend`

## Goal

One `ApiError` envelope for every failure the client can encounter, produced by one `@RestControllerAdvice`, with a stable machine-readable `code` on each. The frontend branches on `code`; nothing in the codebase may branch on a message string.

## Design

### The envelope

```json
{
  "timestamp": "2026-09-26T10:14:32Z",
  "status": 400,
  "code": "INSUFFICIENT_STOCK",
  "message": "Insufficient stock for Azithromycin 500mg: requested 20, available 12",
  "path": "/api/v1/purchase-orders/7/approve",
  "fieldErrors": { "quantity": "must be greater than or equal to 1" }
}
```

- `fieldErrors` is omitted entirely when empty — a `"fieldErrors": {}` on every success-adjacent error is noise.
- `message` is for humans. `code` is for code. Both are stable contracts.
- Validation failures populate `fieldErrors` from `MethodArgumentNotValidException`.
- The 500 handler **masks** the message. A SQL error or a stack trace must never reach a client.

### Code registry

| Exception | Status | `code` |
| --- | --- | --- |
| `ResourceNotFoundException` | 404 | `NOT_FOUND` |
| `InsufficientStockException` | 400 | `INSUFFICIENT_STOCK` |
| `BusinessRuleException` | 400 | `BUSINESS_RULE_VIOLATION` |
| `InvalidStateTransitionException` | 409 | `INVALID_STATE_TRANSITION` |
| `AccessForbiddenException` | 403 | `FORBIDDEN` |
| `MethodArgumentNotValidException` | 400 | `VALIDATION_FAILED` |
| `AccessDeniedException` | 403 | `FORBIDDEN` |
| `NoResourceFoundException` | 404 | `NOT_FOUND` |
| anything else | 500 | `INTERNAL_ERROR` |

## Implementation

### `ApiError`

A Java `record` in `common/dto/`. Records are right here — the exception DTOs are immutable value carriers with no behaviour, so the `code-standards.md` §2.5 "Lombok class, not record" rule does not apply. Note the exception explicitly; it is the sort of thing an agent will otherwise "fix" in the wrong direction.

```java
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        Map<String, String> fieldErrors) {

    public static ApiError of(int status, String code, String message, String path) {
        return new ApiError(Instant.now(), status, code, message, path, null);
    }
}
```

Static factories, not a builder.

### Exceptions

Five classes in `common/exception/`, all `extends RuntimeException`, each taking a formatted message:

```java
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String resource, Object id) {
        super("%s not found: %s".formatted(resource, id));
    }
}
```

`InsufficientStockException` takes `(medicineName, requested, available)` so the message is meaningful without string-building at the call site.

`InvalidStateTransitionException` takes `(entity, from, to)`.

### `GlobalExceptionHandler`

`@RestControllerAdvice` + `@Slf4j`. One `@ExceptionHandler` per type in the registry. Method visibility `public`, return type `ResponseEntity<ApiError>`.

Two handlers need care:

- **400 validation.** `MethodArgumentNotValidException` → walk `getBindingResult().getFieldErrors()`, first message per field.
- **500 catch-all.** `@ExceptionHandler(Exception.class)`. Log at `ERROR` with the stack trace; return `"An unexpected error occurred"` and log the real message. Never echo `ex.getMessage()`.

Method security failures (`AccessDeniedException` from `@PreAuthorize`) are raised **after** the controller method returns, so they bypass `@RestControllerAdvice` in some Spring Security configurations. In Spring Boot 4 the `ExceptionTranslationFilter` handles it. Confirm in Unit 02 which path fires and add an `AuthenticationEntryPoint` + `AccessDeniedHandler` writing the same `ApiError` JSON if the advice is bypassed — otherwise a 403 returns an empty body and the frontend's `code` branch falls through. **This is a known sharp edge, not a hypothetical.**

### `PageResponse<T>`

```java
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    public static <T> PageResponse<T> from(Page<T> page) { ... }
}
```

Spring's `Page` serialises with an unstable internal shape. If it ever changes, the frontend breaks with no visible cause. This wrapper is the contract.

### `PingController`

```java
@RestController
@RequestMapping("/api/v1/system")
@Tag(name = "System", description = "System diagnostics")
public class PingController {

    @GetMapping("/ping")
    @Operation(summary = "Liveness probe", description = "Returns PONG. Pass ?fail=true to exercise the error envelope.")
    public ResponseEntity<Map<String, String>> ping(
            @RequestParam(defaultValue = "false") boolean fail) {
        if (fail) {
            throw new BusinessRuleException("Deliberate failure for error-envelope verification");
        }
        return ResponseEntity.ok(Map.of("status", "PONG", "service", "pharmacy"));
    }
}
```

Unauthenticated until Unit 02. It is a health probe; it touches no data.

## Dependencies

None beyond the parent. No new packages.

## Verify when done

- [ ] `GET /api/v1/system/ping` → 200 `{"status":"PONG","service":"pharmacy"}`
- [ ] `GET /api/v1/system/ping?fail=true` → 400 with `code: BUSINESS_RULE_VIOLATION`
- [ ] `GET /api/v1/nonexistent` → 404 with the envelope, not a whitelabel page
- [ ] `timestamp` is ISO-8601 UTC; `path` matches the request path
- [ ] `fieldErrors` absent on a non-validation error
- [ ] A POST with an invalid body → 400, `code: VALIDATION_FAILED`, `fieldErrors` keyed by field name
- [ ] Throwing an unhandled `RuntimeException` → 500, `code: INTERNAL_ERROR`, message masked, real detail in the log only
- [ ] `GlobalExceptionHandlerTest` asserts every row of the registry — all 9, not a sample
- [ ] `PageResponse.from(...)` unit test covers `first`/`last` on page 0, a middle page, and the last page
- [ ] Grep the frontend for `error.message ===` — zero matches
