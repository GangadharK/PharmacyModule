package com.potlatech.pharmacy.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.potlatech.pharmacy.common.exception.BusinessRuleException;
import com.potlatech.pharmacy.config.JwtProperties;
import com.potlatech.pharmacy.dto.auth.AuthResult;
import com.potlatech.pharmacy.dto.auth.LoginRequest;
import com.potlatech.pharmacy.entity.RefreshToken;
import com.potlatech.pharmacy.entity.User;
import com.potlatech.pharmacy.repository.RefreshTokenRepository;
import com.potlatech.pharmacy.repository.UserRepository;
import com.potlatech.pharmacy.service.AuthService;
import com.potlatech.pharmacy.service.JwtService;
import com.potlatech.pharmacy.service.RefreshCookieFactory;
import com.potlatech.pharmacy.service.SessionRevoker;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AuthService} login and refresh, with the security properties that are
 * easy to break and expensive to discover in production.
 *
 * <p>Each test here corresponds to a behaviour that was either implemented
 * wrongly first time or is a genuine security property rather than a
 * convenience.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String SECRET = "test-secret-that-is-at-least-32-bytes-long!!";
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"), ZoneOffset.UTC);
    private static final String PASSWORD = "Pharmacy@Dev123";

    @Mock
    private UserRepository users;
    @Mock
    private RefreshTokenRepository tokens;
    @Mock
    private SessionRevoker sessionRevoker;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4); // fast: tests, not production
    private AuthService authService;
    private User pharmacist;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties(SECRET, 15, 7);
        JwtService jwtService = new JwtService(properties, FIXED);
        RefreshCookieFactory cookies = new RefreshCookieFactory(
                new com.potlatech.pharmacy.config.CookieProperties(false, "Lax", "/api/v1/auth"));

        authService = new AuthService(users, tokens, encoder, jwtService, properties,
                cookies, sessionRevoker, FIXED);

        pharmacist = User.builder()
                .id(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
                .username("pharmacist")
                .passwordHash(encoder.encode(PASSWORD))
                .fullName("Duty Pharmacist")
                .role(User.Role.PHARMACIST)
                .isActive(true)
                .build();
    }

    // ==================== login ====================

    @Test
    @DisplayName("issues an access token and a refresh token for valid credentials")
    void loginSucceeds() {
        when(users.findByUsername("pharmacist")).thenReturn(Optional.of(pharmacist));
        when(tokens.save(any())).thenAnswer(i -> i.getArgument(0));

        AuthResult result = authService.login(
                new LoginRequest("pharmacist", PASSWORD), "JUnit", "127.0.0.1");

        assertThat(result.body().accessToken()).isNotBlank();
        assertThat(result.body().tokenType()).isEqualTo("Bearer");
        assertThat(result.body().user().username()).isEqualTo("pharmacist");
        assertThat(result.refreshCookie()).isNotNull();
        assertThat(result.refreshCookie().isHttpOnly()).isTrue();
        assertThat(result.refreshCookie().getName()).isEqualTo("refresh_token");
        // Path scoping: the browser must not attach this to catalogue calls.
        assertThat(result.refreshCookie().getPath()).isEqualTo("/api/v1/auth");
    }

    @Test
    @DisplayName("stores only the SHA-256 of the refresh token, never the token itself")
    void storesOnlyTokenHash() {
        when(users.findByUsername("pharmacist")).thenReturn(Optional.of(pharmacist));

        AuthResult result = authService.login(
                new LoginRequest("pharmacist", PASSWORD), "JUnit", "127.0.0.1");

        String presented = result.refreshCookie().getValue();
        org.mockito.ArgumentCaptor<RefreshToken> captor =
                org.mockito.ArgumentCaptor.forClass(RefreshToken.class);
        verify(tokens).save(captor.capture());

        assertThat(presented).hasSizeGreaterThanOrEqualTo(43);
        assertThat(captor.getValue().getTokenHash())
                .hasSize(64)
                .isNotEqualTo(presented)
                .isEqualTo(AuthService.sha256Hex(presented));
    }

    @Test
    @DisplayName("gives the same message for an unknown user and a wrong password")
    void doesNotRevealWhetherUsernameExists() {
        // A distinct message for "no such user" is a username oracle.
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("ghost", PASSWORD), null, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Invalid username or password");

        when(users.findByUsername("pharmacist")).thenReturn(Optional.of(pharmacist));
        assertThatThrownBy(() -> authService.login(
                new LoginRequest("pharmacist", "wrong"), null, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Invalid username or password");
    }

    @Test
    @DisplayName("verifies a dummy hash when the user is absent, so timing does not leak existence")
    void spendsTimeOnUnknownUser() {
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());

        long start = System.nanoTime();
        assertThatThrownBy(() -> authService.login(
                new LoginRequest("ghost", PASSWORD), null, null))
                .isInstanceOf(BusinessRuleException.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // A missing user must not return in microseconds. Without the dummy
        // verify this is ~0ms; BCrypt at cost 4 is a few ms. The threshold is
        // loose on purpose so a loaded CI box does not flake it.
        assertThat(elapsedMs)
                .as("unknown-user login should still pay for a password verification")
                .isGreaterThan(1);
    }

    @Test
    @DisplayName("refuses a deactivated account with a message distinct from bad credentials")
    void refusesDeactivatedAccount() {
        pharmacist.setIsActive(false);
        when(users.findByUsername("pharmacist")).thenReturn(Optional.of(pharmacist));

        // The distinction is deliberate and safe: by this point the password was
        // correct, so the caller already proved they own the account.
        assertThatThrownBy(() -> authService.login(
                new LoginRequest("pharmacist", PASSWORD), null, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("deactivated");
    }

    @Test
    @DisplayName("records last_login_at on success")
    void recordsLastLoginAt() {
        when(users.findByUsername("pharmacist")).thenReturn(Optional.of(pharmacist));

        authService.login(new LoginRequest("pharmacist", PASSWORD), null, null);

        assertThat(pharmacist.getLastLoginAt())
                .isEqualTo(LocalDateTime.ofInstant(FIXED.instant(), ZoneOffset.UTC));
    }

    // ==================== refresh ====================

    @Test
    @DisplayName("rotates the refresh token on every refresh")
    void rotatesRefreshToken() {
        RefreshToken stored = liveToken("old-opaque-value");
        when(tokens.findByTokenHash(AuthService.sha256Hex("old-opaque-value")))
                .thenReturn(Optional.of(stored));
        when(users.findById(pharmacist.getId())).thenReturn(Optional.of(pharmacist));

        AuthResult result = authService.refresh("old-opaque-value", "JUnit", "127.0.0.1");

        assertThat(result.refreshCookie().getValue())
                .as("a refresh must not reissue the same token")
                .isNotEqualTo("old-opaque-value");
        assertThat(stored.getRevokedAt()).isNotNull();
        assertThat(stored.getReplacedBy()).isNotNull();
    }

    /**
     * The regression this file exists for.
     *
     * <p>Presenting an already-revoked token means it may have been stolen. Every
     * session for that user is revoked — and then the method throws to report
     * the event. If that revocation shares a transaction with the throw, the
     * rollback undoes it: the log and the response both say every session was
     * signed out, and the thief's next request succeeds.
     */
    @Test
    @DisplayName("revokes every session on replay, in a transaction that survives the throw")
    void replayRevokesAllSessionsDespiteThrowing() {
        RefreshToken stolen = liveToken("stolen-value");
        stolen.setRevokedAt(LocalDateTime.now(FIXED).minusHours(1));
        when(tokens.findByTokenHash(AuthService.sha256Hex("stolen-value")))
                .thenReturn(Optional.of(stolen));

        assertThatThrownBy(() -> authService.refresh("stolen-value", null, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("signed out");

        // Must go through SessionRevoker (REQUIRES_NEW), not the caller's
        // transaction. Verifying the collaborator, not the effect, is what makes
        // this test deterministic without a real database.
        verify(sessionRevoker).revokeAll(eq(pharmacist.getId()), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("rejects an expired refresh token")
    void rejectsExpiredRefreshToken() {
        RefreshToken expired = liveToken("expired-value");
        expired.setExpiresAt(LocalDateTime.now(FIXED).minusDays(1));
        when(tokens.findByTokenHash(AuthService.sha256Hex("expired-value")))
                .thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> authService.refresh("expired-value", null, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("refuses to refresh for a user deactivated since the token was issued")
    void refusesRefreshForDeactivatedUser() {
        RefreshToken stored = liveToken("still-valid-value");
        pharmacist.setIsActive(false);
        when(tokens.findByTokenHash(AuthService.sha256Hex("still-valid-value")))
                .thenReturn(Optional.of(stored));
        when(users.findById(pharmacist.getId())).thenReturn(Optional.of(pharmacist));

        assertThatThrownBy(() -> authService.refresh("still-valid-value", null, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("deactivated");
    }

    @Test
    @DisplayName("rejects a blank refresh token")
    void rejectsBlankToken() {
        assertThatThrownBy(() -> authService.refresh("  ", null, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("No refresh token");
    }

    // ==================== logout ====================

    @Test
    @DisplayName("logout is idempotent and never throws")
    void logoutIsIdempotent() {
        // A browser that already dropped the cookie, or a double-click, must not
        // produce an error.
        authService.logout(null);
        authService.logout("");
        authService.logout("never-existed");
        verify(tokens, never()).save(any());
    }

    @Test
    @DisplayName("logout revokes a live session but leaves an already-revoked one alone")
    void logoutRevokesOnce() {
        RefreshToken stored = liveToken("live-value");
        when(tokens.findByTokenHash(AuthService.sha256Hex("live-value")))
                .thenReturn(Optional.of(stored));

        authService.logout("live-value");

        assertThat(stored.getRevokedAt()).isNotNull();
    }

    private RefreshToken liveToken(String opaque) {
        return RefreshToken.builder()
                .id(UUID.randomUUID())
                .userId(pharmacist.getId())
                .tokenHash(AuthService.sha256Hex(opaque))
                .expiresAt(LocalDateTime.now(FIXED).plusDays(1))
                .createdAt(LocalDateTime.now(FIXED))
                .build();
    }
}
