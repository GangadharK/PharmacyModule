package com.potlatech.pharmacy.dto.auth;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.potlatech.pharmacy.entity.User;

/**
 * What a successful login or refresh returns in the response body: one access
 * token and who the caller is.
 *
 * <p><strong>The refresh token is not in this body.</strong> It is a 256-bit
 * random value that authorises a new access token, and putting it in a body
 * means it lands in browser devtools, in any HTTP client log, and in whatever
 * the frontend proxy records. A cookie marked HttpOnly is not readable by
 * JavaScript at all, which is the property that actually matters against XSS.
 * The controller sets it from {@link AuthResult#refreshCookie()}.
 *
 * <p><strong>The access token is in the body, not a cookie.</strong> It is
 * short-lived, and holding it in memory in the SPA means a page reload drops it
 * and the app falls back to refresh. It also means a CSRF cannot be used to
 * trigger an authenticated request, which a cookie would allow.
 *
 * <p>Deliberately not carrying the {@code Set-Cookie} itself, so this stays a
 * plain JSON shape with nothing on it that Jackson will try to serialise.
 *
 * @param accessToken signed JWT, the bearer of this
 * @param tokenType   always {@code Bearer}
 * @param expiresAt   expiry of the access token, so the client can refresh
 *                    proactively instead of discovering expiry as a 401
 * @param user        who just authenticated
 */
public record TokenResponse(
        String accessToken,
        String tokenType,
        Instant expiresAt,
        SessionUser user) {

    public static final String BEARER = "Bearer";

    public static TokenResponse of(String accessToken, Instant expiresAt, User user) {
        return new TokenResponse(accessToken, BEARER, expiresAt, SessionUser.from(user));
    }

    /**
     * The user shape returned to the client.
     *
     * <p>Separate from {@code UserResponse} in the {@code user} feature package:
     * this one is a summary of <em>who is logged in</em> and that one is a row in
     * a user-management table. They will drift apart — this always carries the
     * caller's own roles, that one carries whatever the admin is looking at — and
     * sharing a type would invite the wrong half of each.
     *
     * @param authorities every authority implied by the role, so the frontend can
     *                    hide what the user cannot do instead of rendering a
     *                    button that will 403
     */
    public record SessionUser(
            UUID id,
            String username,
            String fullName,
            User.Role role,
            List<String> authorities,
            LocalDateTime lastLoginAt) {

        public static SessionUser from(User user) {
            return new SessionUser(
                    user.getId(),
                    user.getUsername(),
                    user.getFullName(),
                    user.getRole(),
                    user.getRole().authorities().stream()
                            .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                            .toList(),
                    user.getLastLoginAt());
        }
    }
}
