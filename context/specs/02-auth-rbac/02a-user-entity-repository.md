# 02a — `User` Entity and Repository

**Parent:** `02-auth-rbac.md`
**Persona:** `@pharmacy-backend`

## Goal

Create the `users` and `refresh_token` tables, the `User` entity with its four-value `Role` enum, and the repositories — with the password hash structurally excluded from logs and serialisation.

## Design

`MDD.md` §3.1 is the schema. This sub-spec covers the Java side.

## Implementation

### `V1__create_identity.sql`

```sql
-- V1__create_identity.sql
-- Identity and audit-actor tables.
-- Depends on: nothing. This is the first migration in the schema.

CREATE TABLE users ( ... );        -- verbatim from MDD 3.1
CREATE TABLE refresh_token ( ... );
```

Header comment stating what it does and what it depends on, per `code-standards.md`
§5. `MigrationGuardTest` asserts the header exists.

**No `INSERT`.** This spec originally seeded four users here, and that is
deliberately dropped — see `progress-tracker.md` decision 20. A Flyway migration
runs in production exactly as it does in development, so this would create a
`SUPER_ADMIN` with a published password in the customer's database on every
deploy. Seeding is `IdentitySeeder`, gated on `app.seed.enabled` (true in `dev`,
false by default), and `MigrationGuardTest.noCredentialsInMigrations` blocks the
regression.

Two later migrations repair V1 and are part of this unit's schema, not separate
work: `V2__token_hash_varchar.sql` (`char(64)` → `varchar(64)`) and
`V3__username_case_insensitive.sql` (`lower()` functional unique index).

Dev credentials, for the README only: `superadmin` / `Pharmacy@Dev123`,
`manager`, `pharmacist`, `cashier` — same password. BCrypt hashes are generated at
runtime by `IdentitySeeder` through `BCryptPasswordEncoder`, never hardcoded.

### `User` entity

Per `code-standards.md` §2.1. `UUID` id, mapped natively to PostgreSQL `uuid` —
**no `@Convert`, no `columnDefinition`**; both existed only to bridge MySQL
`BINARY(16)` and `UuidConverter` is deleted. Nested `Role` enum,
`@Enumerated(EnumType.STRING)`.

`username` must not carry `unique = true` on the entity. The database enforces
case-insensitive uniqueness through `UNIQUE INDEX … ON users (lower(username))`,
which `@Column(unique = true)` cannot express — it would add a second,
case-*sensitive* unique constraint on boot.

**The hash must not escape.** Two separate mechanisms, because either alone is insufficient:

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "username", nullable = false, length = 100, unique = true)
    private String username;

    /**
     * BCrypt hash. Excluded from toString, equals and hashCode.
     * A hash in a log line is a credential leak; a hash in an equals()
     * comparison is a timing oracle. Both are excluded deliberately.
     */
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JsonIgnore
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    private Role role;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @PreUpdate
    protected void onUpdate() {
        // created_at is immutable; last_login_at is set explicitly by the service.
    }

    public enum Role { SUPER_ADMIN, PHARMACY_MANAGER, PHARMACIST, CASHIER }
}
```

`@JsonIgnore` on the hash is a **third** line of defence. The entity is never serialised in this design, but if one ever is, the hash goes with it. The `02c` verify step greps the login response for the hash to prove all three hold.

### `RefreshToken` entity

`id`, `userId` (plain `UUID`, no `@ManyToOne` per `AGENTS.md`), `tokenHash` (`CHAR(64)`), `expiresAt`, `revokedAt`, `replacedBy`, `createdAt`, `userAgent`, `ipAddress`. `@ToString.Exclude` on `tokenHash` for the same reason as above.

### Repositories

```java
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByUsernameIgnoreCase(String username);
    boolean existsByUsernameIgnoreCase(String username);
    Page<User> findAllByOrderByFullNameAsc(Pageable pageable);
}

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);
    List<RefreshToken> findAllByUserIdAndRevokedAtIsNull(UUID userId);
    void deleteByExpiresAtBefore(LocalDateTime cutoff);
}
```

`findByUsernameIgnoreCase` — a pharmacist typing `Pharmacist` should not be told the username is wrong.

## Dependencies

None. `UuidConverter` from `01a` is deleted and must not be reintroduced.

## Verify when done

- [ ] Migrations apply to an empty schema
- [ ] `chk_users_role` rejects `'WADMIN'` — test the rejection, not the DDL text
- [ ] `lower(username)` index rejects `Admin` when `admin` exists
- [ ] `ddl-auto: validate` passes
- [ ] `IdentitySeeder` creates 4 users, one per role, only when `app.seed.enabled=true`
- [ ] Same boot with `app.seed.enabled=false` creates 0 users
- [ ] `User.builder().passwordHash("x").build().toString()` does not contain `x`
- [ ] Two `User` instances differing only in hash are `equals()`
- [ ] `ObjectMapper.writeValueAsString(user)` contains no `passwordHash` key
- [ ] `findByUsernameIgnoreCase("PHARMACIST")` and `("pharmacist")` both find the row
- [ ] `tokenHash` excluded from `RefreshToken.toString()`
