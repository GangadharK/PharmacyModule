# 02b — `JwtService`

**Parent:** `02-auth-rbac.md`
**Persona:** `@pharmacy-backend`

## Goal

Issue and validate HS256 access tokens and opaque refresh tokens, and hash refresh tokens for storage. Small, isolated, and unit-testable without a Spring context.

## Design

| Concern | Choice |
| --- | --- |
| Algorithm | HS256 |
| Key source | `app.jwt.secret`, ≥32 bytes enforced by jjwt at startup |
| Access TTL | 15 min (`app.jwt.access-token-minutes`) |
| Refresh TTL | 7 days (`app.jwt.refresh-token-days`) |
| Access claims | `sub`, `username`, `fullName`, `roles`, `iat`, `exp` |
| Refresh form | 32 `SecureRandom` bytes, Base64URL, opaque |

**No `secret` in the claims.** Putting the signing key in its own token is indefensible.

## Implementation

```java
@Component
@Slf4j
public class JwtService {

    private final SecretKey signingKey;
    private final long accessTokenMinutes;
    private final long refreshTokenDays;
    private final SecureRandom secureRandom = new SecureRandom();

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.access-token-minutes}") long accessTokenMinutes,
                      @Value("${app.jwt.refresh-token-days}") long refreshTokenDays) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                "app.jwt.secret must be at least 32 bytes for HS256; got %d".formatted(keyBytes.length));
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        // ...
    }

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("username", user.getUsername())
                .claim("fullName", user.getFullName())
                .claim("roles", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenMinutes, ChronoUnit.MINUTES)))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Opaque refresh token. Random bytes, not a JWT - see MDD 3.1 for why
     * a JWT refresh token cannot be revoked.
     */
    public String generateRefreshToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 hex. Stored in the DB so a dump yields no usable credential. */
    public String hashToken(String token) {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(token.getBytes(StandardCharsets.UTF_8)));
    }

    public Claims parseClaims(String token) {
        return Jwts.parser().verifyWith(signingKey).build()
                .parseSignedClaims(token).getPayload();
    }

    public boolean isValid(String token) {
        try { parseClaims(token); return true; }
        catch (JwtException | IllegalArgumentException e) {
            log.debug("Rejected access token: {}", e.getMessage());
            return false;
        }
    }

    public long getRefreshTokenDays() { return refreshTokenDays; }
}
```

### Points that matter

- **`SecureRandom`, not `Random`.** `Random` is seeded from the clock and its output is predictable. For a credential that is disqualifying.
- **`SIG.HS256`, not `alg: none`.** jjwt 0.12 will not emit an unsigned token, and the parser rejects one. Verify this in the test suite.
- **Catch `JwtException` broadly.** Expired, malformed, bad signature and unsupported algorithm all surface as subclasses. A narrow catch lets the wrong exception escape as a 500.
- **The 32-byte check throws at construction.** A short key is a deployment error; failing at startup is right, failing at first login is not.

### Clock injection

`Instant.now()` is called directly. Inject a `Clock` if the expiry tests become painful — they will not, because `JwtServiceTest` builds its own expired token by minting one with a past `exp`, not by waiting. Keep it simple.

## Dependencies

`jjwt-api`, `jjwt-impl`, `jjwt-jackson` from `01a`. Nothing else.

## Verify when done

- [ ] `JwtServiceTest` — 6 cases: valid token parses; expired rejected; tampered signature rejected; wrong key rejected; missing `roles` claim rejected; sub-32-byte key throws at construction
- [ ] An unsigned token (`alg: none`) is rejected
- [ ] `generateRefreshToken()` twice returns different values, both ≥43 chars
- [ ] `hashToken` returns 64 lowercase hex chars
- [ ] `hashToken` is deterministic; the raw token never appears in the output
- [ ] The access token's `sub` equals `user.getId().toString()`
- [ ] The `roles` claim is the UPPER_CASE enum name
- [ ] No claim named `secret` exists — assert the full claim set
- [ ] The generated token splits into exactly 3 dot-separated parts
