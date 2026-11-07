package com.potlatech.pharmacy.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import com.potlatech.pharmacy.config.JwtProperties;
import com.potlatech.pharmacy.entity.User;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Issues and verifies access tokens. Stateless — this class reads no database and
 * holds no state, which is exactly why a token it issues cannot be taken back.
 *
 * <p>Deliberately a thin wrapper over jjwt rather than a framework
 * (Spring Security OAuth2 Resource Server). That framework would want to own
 * token issuance, and we need the roles claim in a form the existing
 * {@code @PreAuthorize} expressions can use. Owning the claim shape is simpler
 * than bending the framework to it.
 *
 * <p>Not a Spring-managed singleton holding a secret in a field that a test can
 * read. The key is derived in the constructor and never exposed.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long accessTokenMinutes;
    private final Clock clock;

    /**
     * Marked explicitly because the class has a second constructor for tests.
     * With two candidates and neither annotated, Spring falls back to the no-arg
     * constructor, which does not exist, and the failure names a missing method
     * rather than the ambiguity that caused it.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public JwtService(JwtProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** Test seam: an injected {@link Clock} makes expiry testable without sleeping. */
    JwtService(JwtProperties properties, Clock clock) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.accessTokenMinutes = properties.accessTokenMinutes();
        this.clock = clock;
    }

    /**
     * @return a signed access token for {@code user}, valid for the configured
     *         window from now.
     */
    public IssuedToken issueAccessToken(User user) {
        Instant now = clock.instant();
        Instant expiry = now.plus(accessTokenMinutes, ChronoUnit.MINUTES);

        String token = Jwts.builder()
                .subject(user.getId().toString())
                .claim("username", user.getUsername())
                .claim("fullName", user.getFullName())
                .claim("roles", List.of(user.getRole().name()))
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();

        return new IssuedToken(token, expiry);
    }

    /**
     * Verifies signature and expiry, returning the claims.
     *
     * <p><strong>Validation uses the injected clock, not the system clock.</strong>
     * jjwt's parser reads the wall clock unless told otherwise, so passing the
     * clock only to the issuer produces a class that looks testable and is not:
     * a token minted at a fixed instant in the past fails to parse against real
     * "now", and an expiry test that appears to pass is only passing because
     * every token looks expired. The adapter below closes that gap.
     *
     * @throws InvalidTokenException if the token is malformed, unsigned, tampered
     *                               with, or expired. One exception type on
     *                               purpose — the caller cannot act differently
     *                               on the cases anyway, and telling a caller
     *                               <em>which</em> of them applied is an
     *                               invitation to log the distinction somewhere
     *                               it becomes an oracle.
     */
    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException("Access token is invalid or expired", e);
        }
    }

    /**
     * @return the token string and the instant it expires, so the caller can set
     *         an expiry that matches the token rather than guessing.
     */
    public record IssuedToken(String token, Instant expiresAt) {
    }

    /** Thrown for any token that fails verification. */
    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Convenience for tests and callers that only need a subject. */
    public static UUID subjectOf(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }
}
