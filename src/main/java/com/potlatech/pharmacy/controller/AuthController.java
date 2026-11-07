package com.potlatech.pharmacy.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.potlatech.pharmacy.dto.auth.AuthResult;
import com.potlatech.pharmacy.dto.auth.LoginRequest;
import com.potlatech.pharmacy.dto.auth.TokenResponse;
import com.potlatech.pharmacy.entity.User;
import com.potlatech.pharmacy.security.AuthenticatedUser;
import com.potlatech.pharmacy.security.CurrentUserContext;
import com.potlatech.pharmacy.service.AuthService;

/**
 * {@code /api/v1/auth} — login, refresh, logout, and "who am I".
 *
 * <p>A thin adapter. It extracts the request metadata, calls the service, and
 * writes the cookie. No branching on business rules, because a rule that lives
 * in a controller cannot be reused by the refresh path and will drift from the
 * login path.
 *
 * <p>All four are under {@code /auth}, which is also the cookie's path. The
 * browser therefore attaches the refresh cookie to these endpoints and to nothing
 * else, so a compromised catalogue endpoint cannot make the browser present it.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Login, refresh, logout")
public class AuthController {

    private final AuthService authService;
    private final CurrentUserContext currentUser;

    public AuthController(AuthService authService, CurrentUserContext currentUser) {
        this.authService = authService;
        this.currentUser = currentUser;
    }

    @PostMapping("/login")
    @Operation(summary = "Exchange username and password for an access token")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest httpRequest) {
        AuthResult result = authService.login(
                request, httpRequest.getHeader("User-Agent"), clientIp(httpRequest));
        return withCookie(result);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange the refresh cookie for a new access token")
    public ResponseEntity<TokenResponse> refresh(
            @CookieValue(name = "refresh_token", required = false) String refreshToken,
            HttpServletRequest httpRequest) {
        AuthResult result = authService.refresh(
                refreshToken, httpRequest.getHeader("User-Agent"), clientIp(httpRequest));
        return withCookie(result);
    }

    /**
     * Always 200, even with no cookie.
     *
     * <p>Logging out is idempotent on purpose. A browser that has already dropped
     * the cookie, or a double-click on a logout button, must not produce an
     * error — and returning 401 from a logout endpoint is how a user ends up
     * stuck on a login screen they already satisfied.
     */
    @PostMapping("/logout")
    @Operation(summary = "Revoke the current session and clear the refresh cookie")
    public ResponseEntity<Void> logout(
            @CookieValue(name = "refresh_token", required = false) String refreshToken) {
        authService.logout(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, authService.clearedRefreshCookie().toString())
                .build();
    }

    @GetMapping("/me")
    @Operation(summary = "The currently authenticated user")
    public TokenResponse.SessionUser me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return TokenResponse.SessionUser.from(authService.requireUser(principal));
    }

    private ResponseEntity<TokenResponse> withCookie(AuthResult result) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, result.refreshCookie().toString())
                .body(result.body());
    }

    /**
     * Best-effort client address, for the audit columns on the session row.
     *
     * <p>Deliberately does not trust {@code X-Forwarded-For}. A client can set
     * that header to anything, so recording it verbatim would let an attacker
     * write an arbitrary string into the audit trail. Behind a real proxy,
     * {@code ForwardedHeaderFilter} rewrites the remote address from the proxy's
     * own view, which is the value to use.
     */
    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
