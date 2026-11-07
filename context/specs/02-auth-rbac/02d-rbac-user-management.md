# 02d — RBAC and User Management

**Parent:** `02-auth-rbac.md`
**Persona:** `@pharmacy-backend`

## Goal

Configure Spring Security as stateless JWT, enable method security, route authentication failures into the `ApiError` envelope, and give `SUPER_ADMIN` user management — with no hard delete.

## Design

### `SecurityConfig`

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    JwtAuthenticationFilter jwtFilter,
                                    JwtAuthenticationEntryPoint entryPoint,
                                    JwtAccessDeniedHandler deniedHandler) throws Exception {
        http
            .csrf(csrf -> csrf.disable())   // stateless JWT, no cookie to protect on API calls
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh",
                                 "/actuator/health", "/v3/api-docs/**", "/swagger-ui/**")
                .permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(e -> e
                .authenticationEntryPoint(entryPoint)
                .accessDeniedHandler(deniedHandler))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
```

**`csrf.disable()` is correct here, not lazy.** CSRF defends against a browser automatically attaching credentials to a cross-site request. A `Bearer` token in an `Authorization` header is not attached automatically, so there is nothing for a cross-site form post to abuse. The one cookie-scoped credential — the refresh token — is scoped to `path=/api/v1/auth`, `SameSite=Strict`, and is only readable by the two endpoints that need it.

Turning CSRF on here without also adding a CSRF token to the SPA would break refresh and logout. The reasoning belongs in this spec because "CSRF disabled" reads as a red flag to anyone who reviews it later without context.

### Entry point and denied handler

Both write `ApiError` JSON. Without these, a 401 or 403 from the security filter chain bypasses `@RestControllerAdvice` entirely and the frontend receives an empty body — so `error.code` is `undefined`, and every `code`-based branch silently falls through to a generic message.

```java
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {
    @Override
    public void commence(HttpServletRequest req, HttpServletResponse res,
                         AuthenticationException ex) throws IOException {
        writeError(res, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED",
                "Authentication required", req.getRequestURI());
    }
}
```

Verify which path actually fires in Spring Boot 4 and keep both — the advice handler is harmless if it is bypassed, and the filter handler is harmless if it is not.

### Role hierarchy

```java
@Bean
RoleHierarchy roleHierarchy() {
    return RoleHierarchyImpl.fromHierarchyCode(
        "ROLE_SUPER_ADMIN > ROLE_PHARMACY_MANAGER > ROLE_PHARMACIST > ROLE_CASHIER");
}
```

With `spring.security.role-hierarchy` set, `hasRole('PHARMACIST')` also matches a `SUPER_ADMIN`. This is the convenient behaviour and it matches the authority matrix in the parent spec. If stricter checks are ever needed, `hasRole` plus an explicit `hasRole('X')` per endpoint is the fallback.

**`@PreAuthorize` values are UPPER_CASE.** SCMS mixes case and patches it in the JWT converter by emitting both variants, which grants extra authorities. Not copied.

### `UserController` — SUPER_ADMIN only

| Method | Path | Behaviour |
| --- | --- | --- |
| GET | `/api/v1/auth/users` | Paginated, ordered by name |
| POST | `/api/v1/auth/users` | 201 + `Location` |
| GET | `/api/v1/auth/users/{id}` | 404 via `ResourceNotFoundException` |
| PUT | `/api/v1/auth/users/{id}` | Update full name, role, isActive |
| GET | `/api/v1/auth/roles` | Role list + authority matrix |

**No `DELETE`.** Deactivation preserves the referential integrity of `stock_transaction.performed_by`. A hard-deleted user leaves a ledger row pointing at nothing, and the ledger is the audit trail — the one thing in this system that must never be unresolvable.

A user cannot deactivate or demote themselves. Locking yourself out is not a recoverable state for a single-machine deployment with no SSH.

Password reset is **out of scope for v1** and no endpoint is stubbed for it. Flag it: a forgotten SUPER_ADMIN password currently has no in-app recovery. Worth an open question.

## Dependencies

`jwt-api`, the filter from `02c`, `UserService`.

## Verify when done

- [ ] `/auth/users` as SUPER_ADMIN → 200
- [ ] `/auth/users` as PHARMACY_MANAGER → 403 with an `ApiError` body containing `code`
- [ ] `/auth/users` with no token → 401 with an `ApiError` body containing `code`
- [ ] `RoleHierarchy` works: SUPER_ADMIN passes `hasRole('PHARMACIST')`
- [ ] CASHIER fails `hasRole('PHARMACIST')` → 403
- [ ] Creating a user with a duplicate username → 409 or 400, not a 500
- [ ] Creating a user with a 5-character password → 400, `fieldErrors.password`
- [ ] `PUT` to a nonexistent id → 404 `NOT_FOUND`
- [ ] `PUT` that deactivates the calling user → 400 with a clear message
- [ ] `PUT` that removes the last SUPER_ADMIN → 400
- [ ] No `DELETE` endpoint exists on `/auth/users`
- [ ] `GET /auth/roles` returns all four roles with the matrix
- [ ] Grep for `@PreAuthorize` — every value UPPER_CASE, zero case variants
- [ ] Grep for a custom `JwtAuthenticationConverter` emitting case variants — zero matches
- [ ] `SessionCreationPolicy.STATELESS` confirmed; no `HttpSession` in the security config
