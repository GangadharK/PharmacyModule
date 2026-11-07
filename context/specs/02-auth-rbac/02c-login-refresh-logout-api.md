# 02c — Login, Refresh, Logout, Me

**Parent:** `02-auth-rbac.md`
**Persona:** `@pharmacy-backend`

## Goal

The four authentication endpoints, with uniform error responses, timing-safe credential handling, and refresh-token rotation with reuse detection.

## Design

| Endpoint | Auth in | Success | Failure |
| --- | --- | --- | --- |
| `POST /auth/login` | body | 200 + access token in body, refresh cookie set | 401 |
| `POST /auth/refresh` | refresh cookie | 200 + new access token, new cookie | 401 |
| `POST /auth/logout` | access token | 204, cookie cleared | 204 regardless |
| `GET /auth/me` | access token | 200 + profile | 401 |

All four failure paths return the `ApiError` envelope from `01b`. No exception ever escapes as a whitelabel page.

## Implementation

### DTOs

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class LoginRequest {
    @NotBlank(message = "Username is required")
    private String username;

    @NotBlank(message = "Password is required")
    private String password;
}
```

`LoginResponse` — `accessToken`, `tokenType` (`"Bearer"`), `expiresIn` (seconds), `user` (`UserResponse`).

`UserResponse` — `id`, `username`, `fullName`, `role`, `roles` (List). **No `passwordHash`, no field for one.**

### Login

```java
@Transactional
public LoginResponse login(LoginRequest request, HttpServletRequest http) {
    User user = userRepository.findByUsernameIgnoreCase(request.username().trim())
            .orElse(null);

    String hash = user != null ? user.getPasswordHash() : DUMMY_HASH;
    boolean passwordMatches = passwordEncoder.matches(request.password(), hash);

    // Both conditions must hold, and the check runs unconditionally, so the
    // response time does not reveal whether the username exists.
    if (user == null || !passwordMatches) {
        throw new InvalidCredentialsException();
    }
    if (!Boolean.TRUE.equals(user.getIsActive())) {
        throw new AccessForbiddenException("Account is deactivated");
    }
    // ...
}
```

`DUMMY_HASH` is a fixed valid BCrypt hash generated once at startup. Without it, an unknown username short-circuits before `matches` and returns measurably faster — a username enumeration oracle.

`InvalidCredentialsException` extends `RuntimeException`, maps to **401**, `code: "INVALID_CREDENTIALS"`. One exception type for both "no such user" and "wrong password", so the two are indistinguishable to the client *and* in the code.

Inactive → **403** with a distinct message. Knowing your password is correct but your account is suspended is not an information leak; it is a courtesy that prevents a support call.

### Refresh, with reuse detection

```java
@Transactional
public LoginResponse refresh(HttpServletRequest http) {
    String raw = readRefreshCookie(http);
    if (raw == null) throw new InvalidCredentialsException();

    RefreshToken stored = refreshTokenRepository.findByTokenHash(jwtService.hashToken(raw))
            .orElseThrow(InvalidCredentialsException::new);

    // Reuse of an already-rotated token: assume theft, revoke the whole family.
    if (stored.getRevokedAt() != null) {
        log.warn("Refresh token reuse detected for user {}", stored.getUserId());
        revokeFamily(stored.getUserId());
        throw new InvalidCredentialsException();
    }
    if (stored.getExpiresAt().isBefore(LocalDateTime.now())) {
        throw new InvalidCredentialsException();
    }
    // ...
}
```

**Reuse detection is the reason for the `replaced_by` column.** Without rotation, a stolen refresh token works for 7 days with no way to detect it. With rotation, the legitimate client rotates on every use; if an old token is presented later, either the client was compromised or the token was stolen. Either way the family dies. This is the standard OAuth 2.0 BCP recommendation and it is worth the extra column.

### `JwtAuthenticationFilter`

`OncePerRequestFilter`. Extract `Authorization: Bearer …`, validate, build a `UserPrincipal`, set the `SecurityContext`, populate `CurrentUserContext`. On an invalid token, do not throw — leave the context empty and let the authorization rules produce a 401. Throwing from a filter bypasses `@RestControllerAdvice`.

Clear `CurrentUserContext` in a `finally`, not on the success path.

### Controller

```java
@PostMapping("/login")
@Operation(summary = "Authenticate", description = "Returns a JWT access token and sets an HttpOnly refresh cookie")
public ResponseEntity<LoginResponse> login(
        @Valid @RequestBody LoginRequest request,
        HttpServletRequest http) { ... }
```

`@Valid` is not optional. `01a` added the starter it needs.

The refresh cookie:

```java
ResponseCookie.from("pharmacy_refresh", token)
        .httpOnly(true)
        .secure(true)
        .path("/api/v1/auth")
        .maxAge(Duration.ofDays(7))
        .sameSite("Strict")
        .build();
```

`path` scoped to `/api/v1/auth` — the browser then never attaches it to catalogue or inventory calls, so the token's exposure is confined to the two endpoints that need it.

`secure(true)` means the cookie is dropped over plain HTTP. In local dev that means no refresh cookie, so the refresh path must be verified over HTTPS or with the flag env-gated. **Verify this explicitly; a silently-dropped cookie presents as "refresh always 401" and gets misdiagnosed as a token bug.**

## Dependencies

`JwtService` (`02b`), `UserRepository` (`02a`), `RefreshTokenRepository` (`02a`).

## Verify when done

- [ ] Valid login → 200, token in body, cookie set with `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/api/v1/auth`
- [ ] Wrong password → 401 `INVALID_CREDENTIALS`
- [ ] Unknown username → 401, **byte-identical body** to the wrong-password case
- [ ] Timing across 20 runs of each is indistinguishable (record with `curl -w '%{time_total}'`)
- [ ] Inactive user with correct password → 403 with a clear message
- [ ] Response body contains no `passwordHash` under any key
- [ ] `/auth/me` valid → 200 with id, username, fullName, role
- [ ] `/auth/me` no token → 401 with the full `ApiError` envelope
- [ ] `/auth/me` expired token → 401 with the envelope
- [ ] `/auth/me` tampered token → 401 with the envelope
- [ ] `/auth/refresh` valid cookie → 200, new cookie, old row has `revoked_at` and `replaced_by`
- [ ] Reusing the pre-rotation cookie → 401 **and** all that user's unrevoked rows are revoked
- [ ] `/auth/logout` → 204, row revoked, cookie cleared with `Max-Age=0`
- [ ] A revoked cookie on `/auth/refresh` → 401
- [ ] Expired refresh cookie → 401
- [ ] Empty body on login → 400 with `fieldErrors.username` and `fieldErrors.password`
- [ ] `@PreAuthorize` 403 returns the `ApiError` envelope, not an empty body — confirms the `01b` sharp-edge fix
- [ ] `auth.store` receives the token, the redirect honours `?next=`
- [ ] A page refresh with a valid cookie re-authenticates silently
