package com.potlatech.pharmacy.service;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.potlatech.pharmacy.config.CookieProperties;

import java.time.Duration;

/**
 * Builds the refresh-token cookie.
 *
 * <p>Every attribute is set here rather than at the call site so the setting
 * cookie and the clearing cookie cannot drift apart. They must match: a browser
 * only removes a cookie when the {@code Set-Cookie} that clears it has the same
 * name, path and domain, and a mismatch produces a cookie that survives logout
 * and is sent on the next request.
 *
 * <p>{@code maxAge} of zero is how a cookie is deleted — there is no "delete"
 * verb in HTTP.
 */
@Component
public class RefreshCookieFactory {

    /**
     * The cookie's name, distinct from its {@code Path}.
     *
     * <p>Kept as a constant because the name has to be byte-identical in three
     * places: the controller's {@code @CookieValue} reads, the setting cookie,
     * and the clearing cookie. Deriving the name from configuration is how the
     * two drift — a browser only deletes a cookie when the clearing
     * {@code Set-Cookie} matches it exactly.
     */
    public static final String COOKIE_NAME = "refresh_token";

    private final CookieProperties properties;

    public RefreshCookieFactory(CookieProperties properties) {
        this.properties = properties;
    }

    /**
     * @param opaqueToken plaintext refresh token, or {@code ""} to clear
     * @param days        lifetime in days; {@code 0} clears the cookie
     */
    public ResponseCookie build(String opaqueToken, long days) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(COOKIE_NAME, opaqueToken)
                .httpOnly(true)
                .secure(properties.secure())
                .path(properties.path());

        if ("Strict".equalsIgnoreCase(properties.sameSite())) {
            builder.sameSite("Strict");
        } else if ("None".equalsIgnoreCase(properties.sameSite())) {
            // SameSite=None is only accepted by browsers on a Secure cookie.
            // Sending None over plain HTTP is rejected outright, which in local
            // development looks like "the cookie silently does not appear".
            builder.sameSite("None").secure(true);
        } else {
            builder.sameSite("Lax");
        }

        if (days <= 0) {
            builder.maxAge(Duration.ZERO);
        } else {
            builder.maxAge(Duration.ofDays(days));
        }

        return builder.build();
    }
}
