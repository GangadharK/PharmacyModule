package com.potlatech.pharmacy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code app.jwt.*}.
 *
 * <p>A typed record rather than scattered {@code @Value} fields, so the token
 * settings live in one place and the compiler catches a rename. The
 * {@code jwt} field on {@code PharmacyApplication}'s configuration-properties
 * scan picks this up automatically.
 *
 * @param secret               HS256 signing key. Must be at least 32 bytes —
 *                             jjwt refuses anything shorter, and a key that
 *                             short means HS256 is not really being used.
 * @param accessTokenMinutes   lifetime of an access token. Deliberately short:
 *                             a JWT cannot be revoked, so this is the window
 *                             during which a deactivated user or a changed role
 *                             still has access.
 * @param refreshTokenDays     lifetime of a refresh token. Longer than the
 *                             access token because it is revocable server-side,
 *                             so a long window costs nothing.
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String secret,
        long accessTokenMinutes,
        long refreshTokenDays) {

    public JwtProperties {
        if (secret == null || secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "app.jwt.secret must be at least 32 bytes for HS256. Override JWT_SECRET.");
        }
        if (accessTokenMinutes <= 0) {
            throw new IllegalStateException("app.jwt.access-token-minutes must be positive");
        }
        if (refreshTokenDays <= 0) {
            throw new IllegalStateException("app.jwt.refresh-token-days must be positive");
        }
    }
}
