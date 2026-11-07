# 02 — Identity, RBAC & Audit Actor

**Status:** Not started
**Spec:** `context/specs/00-build-plan.md` → Unit 02
**Persona:** `@pharmacy-backend`

---

## Goal

Replace Unit 01's stub security with real, self-issued authentication: a `users` table with BCrypt hashes, JWT access tokens, opaque rotating refresh tokens in an HttpOnly cookie, a role hierarchy that every later endpoint's `@PreAuthorize` depends on, and a `CurrentUserContext` that gives the rest of the system a reliable `performed_by` for the stock ledger.

## Design

### Why self-issued, not Keycloak

FDD §1 requires the pharmacy to run standalone. SCMS's approach requires a Keycloak realm JSON, six Docker containers, and a converter workaround that emits case variants of each authority — which silently grants extra permissions. Pharmacy issues its own tokens with jjwt and needs none of that.

### Token strategy

| Token | Form | Storage | Lifetime | Purpose |
| --- | --- | --- | --- | --- |
| Access | JWT, HS256, `roles` claim | Frontend **memory only** | 15 min | Authorises API calls |
| Refresh | 32 random bytes, opaque | **HttpOnly cookie**, DB stores SHA-256 | 7 days | Mints new access tokens |

Access tokens are stateless and short-lived, so a revoked role takes effect within 15 minutes. Refresh tokens are server-side and revocable, so logout is immediate. That pairing is the whole design.

### Roles

```
SUPER_ADMIN  >  PHARMACY_MANAGER  >  PHARMACIST  >  CASHIER
```

Descending authority, UPPER_CASE only. `AGENTS.md` rule 8. Every later spec's `@PreAuthorize` is written against exactly these four strings.

Role authority in practice:

| Capability | CASHIER | PHARMACIST | PHARMACY_MANAGER | SUPER_ADMIN |
| --- | :-: | :-: | :-: | :-: |
| Read catalogue | ✅ | ✅ | ✅ | ✅ |
| Edit catalogue | — | — | ✅ | ✅ |
| See `cost_price` | **—** | ✅ | ✅ | ✅ |
| Read batches | ✅ | ✅ | ✅ | ✅ |
| Create / edit batches | — | ✅ | ✅ | ✅ |
| Quarantine a batch | — | ✅ | ✅ | ✅ |
| Release a quarantined batch | — | — | ✅ | ✅ |
| Create a purchase order | — | ✅ | ✅ | ✅ |
| Approve a purchase order | — | — | ✅ | ✅ |
| Cycle count post | — | ✅ | ✅ | ✅ |
| Manage users | — | — | — | ✅ |

The `cost_price` rule is the one that must be server-enforced. `@PreAuthorize` cannot remove a field from a response body — that is `03e-cost-redaction.md`.

### `CurrentUserContext`

A `ThreadLocal`-backed holder populated by the JWT filter, cleared in `afterCompletion`. Services call `currentUser.requireUserId()` and `currentUser.requireRoles()` rather than threading a user object through every signature. This is the `performed_by` recorded on every `stock_transaction` row, so it must be impossible to write a transaction without an actor.

`ThreadLocal` with a servlet filter is correct here and is the SCMS pattern — but SCMS's version leaks if the filter chain throws before `afterCompletion`. Set it in a `try`/`finally` in the filter, not in the success path only.

## Implementation

### 02a — `User` entity and repository
`V1__create_identity.sql` per `MDD.md` §3.1, including `refresh_token`. `User` entity with nested `Role` enum (`SUPER_ADMIN`, `PHARMACY_MANAGER`, `PHARMACIST`, `CASHIER`). `UserRepository`, `RefreshTokenRepository`.

`User.toString()` must not print `passwordHash`. Lombok's `@Data` generates a `toString` including it, and that string ends up in logs. Override it, or use `@ToString(exclude = "passwordHash")`. **This is a real leak and easy to miss.**

Seed 4 users — one per role — with BCrypt hashes. Passwords in a migration are a known trade-off; use an obvious dev-only password and document it in the README. Never reuse a real credential.

### 02b — `JwtService`
Pure-ish service, `@Component`, `@ConfigurationProperties("app.jwt")`.

- `generateAccessToken(User)` → HS256, `sub` = user id, `username`, `fullName`, `roles` = `List<String>`, `iat`, `exp` = 15 min. **No `secret` in the claims.**
- `generateRefreshToken()` → `SecureRandom`, 32 bytes, Base64URL.
- `hashToken(String)` → SHA-256 hex, for storage.
- `validateAccessToken(String)` → claims, or throw `JwtException`.
- `parseClaims(String)` → `Claims`.

The `roles` claim is a `List<String>` of UPPER_CASE role names. `JwtAuthenticationConverter` maps it to `ROLE_`-prefixed authorities. Do not hand-roll case variants; that is SCMS's bug.

HS256 requires a key of at least 256 bits. jjwt will refuse a shorter one at startup — a loud failure, which is correct. The dev default must therefore be ≥32 bytes.

### 02c — Login / refresh / logout / me API
`AuthController` + `AuthService`. See the sub-spec for the full contract.

Password verification: `BCryptPasswordEncoder.matches(raw, hash)`. Always run a dummy `matches` against a fixed hash when the username is unknown, so response timing does not reveal which usernames exist.

`last_login_at` updated on successful login.

### 02d — RBAC and user management
`SecurityConfig` with `SessionCreationPolicy.STATELESS`, `/api/v1/auth/login` and `/actuator/health` permitted, everything else authenticated. Method security on with `@EnableMethodSecurity`.

`UserController` for SUPER_ADMIN user management: list, create, update role, deactivate. No delete — deactivation preserves the audit trail, and a `stock_transaction.performed_by` pointing at a missing user is an unresolvable hole.

`JwtAuthenticationEntryPoint` and `JwtAccessDeniedHandler` writing the `ApiError` envelope, per the sharp edge flagged in `01b-error-handling.md`.

### 02e — `CurrentUserContext`
`@Component`, `ThreadLocal<UserPrincipal>`, set by the filter, cleared in a `finally`. Exposes `requireUserId()`, `requireUsername()`, `requireRoles()`, `hasRole(String)`, `clear()`.

`require*` throws `BusinessRuleException` if unset; `get*` returns `Optional`. Services use `require*` — forgetting to populate the context should be a loud failure, not a null.

## Dependencies

| Dependency | Why |
| --- | --- |
| `io.jsonwebtoken:jjwt-api` + `jjwt-impl` + `jjwt-jackson` | Added in `01a`, first used here |
| `spring-boot-starter-security` | Already present |
| `spring-security-crypto` | BCrypt — pulled transitively, confirm it resolves |
| `spring-boot-configuration-processor` | Optional, `<optional>true</optional>`, IDE completion for `app.jwt.*` |

No new runtime dependency. Keycloak, Redis and the session store are all deliberately absent.

## API Inventory

| Method | Path | Auth | Role | Description | Spec Ref |
| --- | --- | --- | --- | --- | --- |
| POST | `/api/v1/auth/login` | None | — | Authenticate, return access token, set refresh cookie | `02c-login-refresh-logout-api.md` |
| POST | `/api/v1/auth/refresh` | Refresh cookie | — | Mint a new access token, rotate the refresh token | `02c-login-refresh-logout-api.md` |
| POST | `/api/v1/auth/logout` | Access token | any | Revoke the refresh token, clear the cookie | `02c-login-refresh-logout-api.md` |
| GET | `/api/v1/auth/me` | JWT | any | Current user profile and roles | `02c-login-refresh-logout-api.md` |
| GET | `/api/v1/auth/users` | JWT | SUPER_ADMIN | Paginated user list | `02d-rbac-user-management.md` |
| POST | `/api/v1/auth/users` | JWT | SUPER_ADMIN | Create a user | `02d-rbac-user-management.md` |
| GET | `/api/v1/auth/users/{id}` | JWT | SUPER_ADMIN | User detail | `02d-rbac-user-management.md` |
| PUT | `/api/v1/auth/users/{id}` | JWT | SUPER_ADMIN | Update full name, role, active flag | `02d-rbac-user-management.md` |
| GET | `/api/v1/auth/roles` | JWT | any | Role list with their authority matrix | `02d-rbac-user-management.md` |

`/api/v1/system/ping` loses its public access in this unit.

## Verify when done

### Database
- [ ] `V1__create_identity.sql` applies to an empty PostgreSQL 16 schema
- [ ] `users` and `refresh_token` exist with every column and index from `MDD.md` §3.1
- [ ] The `chk_users_role` CHECK rejects an invalid role — actually test the rejection, do not eyeball the DDL
- [ ] 4 seeded users exist, one per role
- [ ] `ddl-auto: validate` passes

### API
- [ ] Valid credentials → 200, access token in the body, refresh cookie set with `HttpOnly` + `SameSite=Strict` + `Secure`
- [ ] Wrong password → 401, `code` populated, and **no** "user not found" hint
- [ ] Unknown username → 401 with an identical body to the wrong-password case
- [ ] Inactive user → 403
- [ ] Token response contains no `passwordHash` under any key
- [ ] `/auth/me` with a valid token → 200 with id, username, full name, roles
- [ ] `/auth/me` with no token → 401 with the `ApiError` envelope, **not** an empty body
- [ ] `/auth/me` with an expired token → 401
- [ ] `/auth/me` with a tampered token → 401
- [ ] `/auth/refresh` with a valid cookie → new access token, **new** refresh cookie, old token's `replaced_by` set
- [ ] Reusing a rotated refresh token → 401 **and** the whole family revoked
- [ ] `/auth/logout` → 204, refresh row `revoked_at` set, cookie cleared
- [ ] A revoked refresh token → 401
- [ ] `/auth/users` as SUPER_ADMIN → 200; as PHARMACY_MANAGER → 403 with an `ApiError` body
- [ ] Creating a user with a weak password → 400 with `fieldErrors.password`

### Security
- [ ] `User.toString()` does not contain the hash
- [ ] Grep the log output for a BCrypt hash — zero matches
- [ ] Every `@PreAuthorize` value is UPPER_CASE
- [ ] No case-variant authority converter anywhere
- [ ] Timing on unknown-username and wrong-password logins is indistinguishable (spot-check with `curl -w`)

### Tests
- [ ] `JwtServiceTest` — 6 cases: valid parse, expired, tampered signature, wrong key, missing roles claim, 256-bit key enforcement
- [ ] `AuthServiceTest` — happy path, bad password, unknown user, inactive user, refresh rotation, reuse detection
- [ ] `UserControllerTest` — `@WebMvcTest`, all three auth cases on every endpoint
- [ ] `CurrentUserContextTest` — context populated in a request, **cleared after** a request that throws

### Integration
- [ ] `LoginPage` submit now calls `POST /auth/login`, stores the token in `auth.store`, and redirects to `?next=`
- [ ] A page refresh with a valid refresh cookie silently re-authenticates and returns to the same route
- [ ] `Sidebar` shows role-filtered navigation from `/auth/me`
- [ ] Logout from the avatar menu clears the store and returns to `/login`
- [ ] `progress-tracker.md` and `layer-tracker.md` updated
