# 02e — `CurrentUserContext`

**Parent:** `02-auth-rbac.md`
**Persona:** `@pharmacy-backend`

## Goal

One reliable way for any service to learn who is acting. Every `stock_transaction.performed_by` depends on it, so it must be impossible to write a ledger row without an actor.

## Design

A `ThreadLocal<UserPrincipal>` populated by the JWT filter and cleared in a `finally`. Services call `requireUserId()`; they never take a `User` parameter. Threading an actor through every method signature pollutes signatures that have other concerns, and a caller that forgets to pass it produces a null instead of an error.

## Implementation

```java
@Component
public class CurrentUserContext {

    private static final ThreadLocal<UserPrincipal> HOLDER = new ThreadLocal<>();

    void set(UserPrincipal principal) { HOLDER.set(principal); }

    public void clear() { HOLDER.remove(); }

    public Optional<UserPrincipal> current() { return Optional.ofNullable(HOLDER.get()); }

    /**
     * @throws BusinessRuleException if no user is bound - a service reached from a
     *         path that bypassed the security filter. Failing loudly beats writing
     *         a ledger row with a null performed_by.
     */
    public UUID requireUserId() {
        return current().orElseThrow(() ->
                new BusinessRuleException("No authenticated user bound to the current request"))
                .userId();
    }

    public String requireUsername() { /* ... */ }

    public Set<String> requireRoles() { /* ... */ }

    public boolean hasRole(Role role) {
        return current().map(p -> p.roles().contains(role)).orElse(false);
    }
}
```

`UserPrincipal` — a record `(UUID userId, String username, String fullName, Set<Role> roles)` implementing `UserDetails` or stored alongside it in the `SecurityContext`. A record is right: immutable identity, no behaviour.

### The leak

`ThreadLocal` on a pooled request thread is a genuine leak. If a request populates the context and the thread returns to the pool still holding it, the **next** request on that thread sees the **previous** request's user. That is an authorisation defect, not a memory leak — the wrong pharmacist's actions get attributed to the right one, in the audit trail.

Two mitigations, both required:

1. **`finally` in the filter.** Not the success path.

```java
@Override
protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                FilterChain chain) throws ServletException, IOException {
    try {
        String token = extractBearer(req);
        if (token != null && jwtService.isValid(token)) {
            Claims claims = jwtService.parseClaims(token);
            UserPrincipal principal = toPrincipal(claims);
            currentUserContext.set(principal);
            SecurityContextHolder.getContext()
                    .setAuthentication(new UsernamePasswordAuthenticationToken(
                            principal, null, principal.authorities()));
        }
        chain.doFilter(req, res);
    } finally {
        currentUserContext.clear();
        SecurityContextHolder.clearContext();
    }
}
```

2. **An explicit test** that makes a request which throws, then asserts the next request on the same thread sees no user. Without this test the `finally` gets dropped in a refactor and nothing fails.

`ExpirySweepJob` (Unit 05) runs on a scheduler thread with no `SecurityContext`. It writes `stock_transaction` rows with `performed_by = NULL` and a `reason` of `EXPIRY_WRITE_OFF`. That is the documented, intended exception — a system-initiated action has no human actor. Make the column nullable deliberately and note it in `MDD.md`, not as an accident.

## Dependencies

`Role` enum from `02a`, `JwtService` from `02b`.

## Verify when done

- [ ] `CurrentUserContextTest` — populated in a normal request, `requireUserId()` returns the JWT's `sub`
- [ ] **A request that throws still clears the context** — the leak test
- [ ] A second request on the same thread sees no user
- [ ] `requireUserId()` with an empty context throws `BusinessRuleException`
- [ ] `current()` returns `Optional.empty()` with no binding
- [ ] `hasRole` returns `false` rather than throwing on an empty context
- [ ] `clear()` is called in a `finally` in the filter — read the code, do not assume
- [ ] `UserPrincipal` is a `record` and is immutable
- [ ] The scheduler thread path in Unit 05 writes `performed_by = NULL` without throwing
- [ ] No service method takes a `User` or `UserPrincipal` parameter for the purposes of recording the actor — grep for it in later units
