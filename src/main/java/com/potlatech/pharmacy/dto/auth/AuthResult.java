package com.potlatech.pharmacy.dto.auth;

import org.springframework.http.ResponseCookie;

/**
 * What the auth service returns: the JSON body plus the cookie to set.
 *
 * <p>Held together so {@link com.potlatech.pharmacy.service.AuthService} does not
 * need to touch the servlet API. The alternative — the service building the
 * response and setting the header itself — makes every auth path untestable
 * without a mock {@code HttpServletResponse}, and spreads cookie attributes
 * across the service and the controller.
 *
 * @param body          the JSON payload
 * @param refreshCookie {@code Set-Cookie} for the refresh token, or
 *                      {@code null} to leave the cookie alone
 */
public record AuthResult(TokenResponse body, ResponseCookie refreshCookie) {

    public static AuthResult of(TokenResponse body, ResponseCookie refreshCookie) {
        return new AuthResult(body, refreshCookie);
    }
}
