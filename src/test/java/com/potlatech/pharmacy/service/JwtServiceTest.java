package com.potlatech.pharmacy.service;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.potlatech.pharmacy.config.JwtProperties;
import com.potlatech.pharmacy.entity.User;
import com.potlatech.pharmacy.service.JwtService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JwtService} — issue, verify, and reject.
 *
 * <p>Pure unit tests: no Spring context, no database. The clock is injected so
 * expiry is tested by moving time rather than by sleeping, which keeps the suite
 * fast and makes the boundary exact.
 */
class JwtServiceTest {

    /** 48 bytes. Comfortably over the 32-byte HS256 minimum. */
    private static final String SECRET = "test-secret-that-is-at-least-32-bytes-long!!";
    private static final long ACCESS_MINUTES = 15;
    private static final long REFRESH_DAYS = 7;

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"), ZoneOffset.UTC);

    private static JwtService serviceAt(Clock clock) {
        return new JwtService(new JwtProperties(SECRET, ACCESS_MINUTES, REFRESH_DAYS), clock);
    }

    private static User user() {
        return User.builder()
                .id(UUID.fromString("11111111-2222-3333-4444-555555555555"))
                .username("pharmacist")
                .passwordHash("irrelevant")
                .fullName("Duty Pharmacist")
                .role(User.Role.PHARMACIST)
                .isActive(true)
                .build();
    }

    @Nested
    @DisplayName("issuing")
    class Issuing {

        @Test
        @DisplayName("carries the claims the frontend and RBAC depend on")
        void carriesRequiredClaims() {
            JwtService.IssuedToken issued = serviceAt(FIXED).issueAccessToken(user());
            Claims claims = serviceAt(FIXED).parse(issued.token());

            assertThat(claims.getSubject())
                    .isEqualTo("11111111-2222-3333-4444-555555555555");
            assertThat(claims.get("username", String.class)).isEqualTo("pharmacist");
            assertThat(claims.get("fullName", String.class)).isEqualTo("Duty Pharmacist");
            assertThat(claims.get("roles")).isEqualTo(java.util.List.of("PHARMACIST"));
        }

        @Test
        @DisplayName("expires exactly access-token-minutes from now")
        void expiresAtConfiguredWindow() {
            JwtService.IssuedToken issued = serviceAt(FIXED).issueAccessToken(user());

            assertThat(issued.expiresAt())
                    .isEqualTo(Instant.parse("2026-09-26T00:15:00Z"));
        }

        @Test
        @DisplayName("never embeds the password hash")
        void neverEmbedsPasswordHash() {
            // A JWT is base64, not encrypted: anyone holding it can read every
            // claim. This asserts the hash is not among them.
            String token = serviceAt(FIXED).issueAccessToken(user()).token();
            String decoded = new String(java.util.Base64.getUrlDecoder()
                    .decode(token.split("\\.")[1]));

            assertThat(decoded).doesNotContain("passwordHash").doesNotContain("irrelevant");
        }
    }

    @Nested
    @DisplayName("verifying")
    class Verifying {

        @Test
        @DisplayName("accepts a token it issued")
        void acceptsOwnToken() {
            JwtService service = serviceAt(FIXED);
            assertThat(service.parse(service.issueAccessToken(user()).token()).getSubject())
                    .isEqualTo(user().getId().toString());
        }

        @Test
        @DisplayName("rejects a token signed with a different key")
        void rejectsForeignSignature() {
            String foreign = new JwtService(
                    new JwtProperties("a-completely-different-secret-of-32-bytes!!", 15, 7), FIXED)
                    .issueAccessToken(user()).token();

            assertThatThrownBy(() -> serviceAt(FIXED).parse(foreign))
                    .isInstanceOf(JwtService.InvalidTokenException.class);
        }

        @Test
        @DisplayName("rejects an expired token once the clock passes expiry")
        void rejectsExpiredToken() {
            JwtService issuer = serviceAt(FIXED);
            String token = issuer.issueAccessToken(user()).token();

            // 16 minutes later: one minute past the 15-minute window.
            JwtService later = serviceAt(Clock.fixed(
                    Instant.parse("2026-09-26T00:16:00Z"), ZoneOffset.UTC));

            assertThatThrownBy(() -> later.parse(token))
                    .isInstanceOf(JwtService.InvalidTokenException.class)
                    .hasMessageContaining("invalid or expired");
        }

        @Test
        @DisplayName("accepts a token one second before it expires")
        void acceptsJustBeforeExpiry() {
            JwtService issuer = serviceAt(FIXED);
            String token = issuer.issueAccessToken(user()).token();

            JwtService almostExpired = serviceAt(Clock.fixed(
                    Instant.parse("2026-09-26T00:14:59Z"), ZoneOffset.UTC));

            assertThat(almostExpired.parse(token).getSubject()).isNotBlank();
        }

        @Test
        @DisplayName("rejects a tampered payload")
        void rejectsTamperedPayload() {
            String token = serviceAt(FIXED).issueAccessToken(user()).token();
            String[] parts = token.split("\\.");
            // Flip a character in the payload; the signature no longer matches.
            String tampered = parts[0] + "." + parts[1].replace('a', 'b') + "." + parts[2];

            assertThatThrownBy(() -> serviceAt(FIXED).parse(tampered))
                    .isInstanceOf(JwtService.InvalidTokenException.class);
        }

        @Test
        @DisplayName("rejects nonsense without throwing an unexpected exception type")
        void rejectsGarbage() {
            for (String junk : new String[]{"", "   ", "not-a-jwt", "a.b.c"}) {
                assertThatThrownBy(() -> serviceAt(FIXED).parse(junk))
                        .as("input: '%s'", junk)
                        .isInstanceOf(JwtService.InvalidTokenException.class);
            }
        }
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {

        @Test
        @DisplayName("refuses a secret shorter than 32 bytes")
        void refusesShortSecret() {
            // A short key means HS256 is not really in use; jjwt would either
            // throw at signing time or silently weaken the signature.
            assertThatThrownBy(() -> new JwtProperties("too-short", 15, 7))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("32 bytes");
        }

        @Test
        @DisplayName("refuses non-positive lifetimes")
        void refusesNonPositiveLifetimes() {
            assertThatThrownBy(() -> new JwtProperties(SECRET, 0, 7))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new JwtProperties(SECRET, 15, 0))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
