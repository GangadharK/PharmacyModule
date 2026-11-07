package com.potlatech.pharmacy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.potlatech.pharmacy.common.exception.BusinessRuleException;
import com.potlatech.pharmacy.common.exception.ResourceNotFoundException;
import com.potlatech.pharmacy.config.JwtProperties;
import com.potlatech.pharmacy.dto.auth.AuthResult;
import com.potlatech.pharmacy.dto.auth.LoginRequest;
import com.potlatech.pharmacy.dto.auth.TokenResponse;
import com.potlatech.pharmacy.entity.RefreshToken;
import com.potlatech.pharmacy.entity.User;
import com.potlatech.pharmacy.repository.RefreshTokenRepository;
import com.potlatech.pharmacy.repository.UserRepository;
import com.potlatech.pharmacy.security.AuthenticatedUser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Login, refresh and logout.
 *
 * <p>Issues two credentials with deliberately different properties:
 *
 * <table border="1">
 *   <caption>Token comparison</caption>
 *   <tr><th></th><th>access</th><th>refresh</th></tr>
 *   <tr><td>storage</td><td>client memory</td><td>HttpOnly cookie</td></tr>
 *   <tr><td>verifiable without a DB read</td><td>yes</td><td>no</td></tr>
 *   <tr><td>revocable</td><td>no</td><td>yes, by row</td></tr>
 *   <tr><td>lifetime</td><td>15 min</td><td>7 days</td></tr>
 * </table>
 *
 * <p>The access token is fast and unrecallable; the refresh token is slow and
 * recallable. A 15-minute window on the unrecallable one is what makes that
 * trade acceptable.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final int REFRESH_TOKEN_BYTES = 32;

    /**
     * A real BCrypt hash of a value nobody knows, used to spend the same time
     * verifying a password when the username does not exist as when it does.
     *
     * <p>Without it, a missing user returns in microseconds and an existing one
     * in tens of milliseconds, which is a username oracle measurable over the
     * network without any cooperation from the attacker.
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserRepository users;
    private final RefreshTokenRepository tokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final RefreshCookieFactory cookieFactory;
    private final SessionRevoker sessionRevoker;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public AuthService(UserRepository users,
                       RefreshTokenRepository tokens,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       JwtProperties jwtProperties,
                       RefreshCookieFactory cookieFactory,
                       SessionRevoker sessionRevoker) {
        this(users, tokens, passwordEncoder, jwtService, jwtProperties, cookieFactory,
                sessionRevoker, Clock.systemUTC());
    }

    /** Test seam: injected {@link Clock} so expiry is testable without sleeping. */
    AuthService(UserRepository users,
                RefreshTokenRepository tokens,
                PasswordEncoder passwordEncoder,
                JwtService jwtService,
                JwtProperties jwtProperties,
                RefreshCookieFactory cookieFactory,
                SessionRevoker sessionRevoker,
                Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.cookieFactory = cookieFactory;
        this.sessionRevoker = sessionRevoker;
        this.clock = clock;
    }

    @Transactional
    public AuthResult login(LoginRequest request, String userAgent, String ipAddress) {
        Optional<User> found = users.findByUsername(request.normalisedUsername());

        // Both branches run the encoder. The absent-user branch verifies the
        // dummy so the two take comparable time; see DUMMY_HASH.
        if (found.isEmpty()) {
            passwordEncoder.matches(request.password(), DUMMY_HASH);
            throw invalidCredentials();
        }

        User user = found.get();
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            // A distinct message from invalidCredentials, deliberately. Telling a
            // legitimate user their account is deactivated is the difference
            // between them fixing it and them thinking they forgot the password.
            // The username is already known to be real at this point, so this
            // leaks nothing the previous check has not already leaked.
            throw new BusinessRuleException(
                    "This account is deactivated. Contact an administrator.");
        }

        user.setLastLoginAt(LocalDateTime.now(clock));
        JwtService.IssuedToken accessToken = jwtService.issueAccessToken(user);
        IssuedRefresh refresh = issueRefreshToken(user, userAgent, ipAddress);

        log.info("User {} logged in as {}", user.getUsername(), user.getRole());
        return AuthResult.of(
                TokenResponse.of(accessToken.token(), accessToken.expiresAt(), user),
                refreshCookie(refresh.opaque()));
    }

    /**
     * Exchanges a refresh token for a new pair, rotating the refresh token.
     *
     * <p><strong>Replay detection.</strong> Presenting a token that is already
     * revoked means either the client is retrying a request it already
     * succeeded at, or someone else has the token. Both are treated the same way:
     * the whole chain for that user is revoked, so the legitimate holder is
     * logged out too. That is the intended outcome — if a token may have leaked,
     * the safe response is to end every session and make the user log in again,
     * not to guess which of the two parties is the thief.
     */
    @Transactional
    public AuthResult refresh(String presentedToken, String userAgent, String ipAddress) {
        if (presentedToken == null || presentedToken.isBlank()) {
            throw new BusinessRuleException("No refresh token was presented");
        }

        String hash = sha256Hex(presentedToken);
        RefreshToken stored = tokens.findByTokenHash(hash)
                .orElseThrow(() -> new BusinessRuleException("Refresh token is not recognised"));

        LocalDateTime now = LocalDateTime.now(clock);

        if (stored.getRevokedAt() != null) {
            // The throw below rolls this method's transaction back, so the
            // revocation must not be part of it - see SessionRevoker.
            log.warn("Refresh token replay for user {}. Revoking all sessions.",
                    stored.getUserId());
            sessionRevoker.revokeAll(stored.getUserId(), now);
            throw new BusinessRuleException(
                    "This session has already been used. All sessions have been signed out "
                            + "for safety - please log in again.");
        }

        if (!stored.isUsable(now)) {
            throw new BusinessRuleException("Refresh token has expired. Please log in again.");
        }

        User user = users.findById(stored.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User", stored.getUserId()));

        // A user deactivated between issuing this token and using it must not be
        // able to refresh. The check belongs here as well as in login because
        // revoking on deactivation and a token issued a microsecond earlier can
        // interleave.
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            sessionRevoker.revokeAll(user.getId(), now);
            throw new BusinessRuleException("This account is deactivated.");
        }

        JwtService.IssuedToken accessToken = jwtService.issueAccessToken(user);
        IssuedRefresh rotated = issueRefreshToken(user, userAgent, ipAddress);

        // Retire the old row and point it at its successor, so the chain records
        // what replaced what. Detection of a replay is by finding an already
        // revoked row, which needs no link - the link is for audit.
        stored.setRevokedAt(now);
        stored.setReplacedBy(rotated.rowId());
        tokens.save(stored);

        return AuthResult.of(
                TokenResponse.of(accessToken.token(), accessToken.expiresAt(), user),
                refreshCookie(rotated.opaque()));
    }

    /**
     * Revokes the presented session. Idempotent by design: logging out twice, or
     * logging out with an already-stale cookie, is a normal thing for a browser
     * to do and must not surface as an error.
     */
    @Transactional
    public void logout(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            return;
        }
        tokens.findByTokenHash(sha256Hex(presentedToken)).ifPresent(stored -> {
            if (stored.getRevokedAt() == null) {
                stored.setRevokedAt(LocalDateTime.now(clock));
                tokens.save(stored);
            }
        });
    }

    /** Revokes every live session for a user. Called on deactivation and role change. */
    @Transactional
    public void revokeAllSessions(UUID userId) {
        int revoked = sessionRevoker.revokeAll(userId, LocalDateTime.now(clock));
        log.info("Revoked {} refresh session(s) for user {}", revoked, userId);
    }

    @Transactional(readOnly = true)
    public User requireUser(AuthenticatedUser principal) {
        return users.findById(principal.id())
                .orElseThrow(() -> new ResourceNotFoundException("User", principal.id()));
    }

    private IssuedRefresh issueRefreshToken(User user, String userAgent, String ipAddress) {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        random.nextBytes(bytes);
        String opaque = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        UUID rowId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(clock);
        RefreshToken row = RefreshToken.builder()
                .id(rowId)
                .userId(user.getId())
                .tokenHash(sha256Hex(opaque))
                .expiresAt(now.plus(jwtProperties.refreshTokenDays(), ChronoUnit.DAYS))
                .createdAt(now)
                .userAgent(truncate(userAgent, 255))
                .ipAddress(truncate(ipAddress, 45))
                .build();
        tokens.save(row);

        return new IssuedRefresh(opaque, rowId);
    }

    /**
     * The plaintext token handed to the client, plus the id of the row that
     * stores its hash.
     *
     * <p>Both are needed: the plaintext goes into the cookie and is never stored,
     * the id goes into the previous row's {@code replaced_by}.
     */
    private record IssuedRefresh(String opaque, UUID rowId) {
    }

    /**
     * @return the raw cookie the controller should set. Kept here so the
     *         controller stays a thin adapter and the cookie's attributes are
     *         decided in one place.
     */
    public ResponseCookie refreshCookie(String opaqueToken) {
        return cookieFactory.build(opaqueToken, jwtProperties.refreshTokenDays());
    }

    /** The clearing cookie, which must match the setting cookie's attributes. */
    public ResponseCookie clearedRefreshCookie() {
        return cookieFactory.build("", 0);
    }

    /**
     * SHA-256 hex, lowercase.
     *
     * <p>Not BCrypt, unlike the password. The refresh token is 256 bits of
     * {@code SecureRandom} output, so there is nothing to dictionary-attack, and
     * the lookup has to be an indexed equality match — BCrypt is deliberately
     * salted and slow, which would make it useless for finding a row.
     */
    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java platform. If it is missing, the
            // JVM is not one.
            throw new IllegalStateException("SHA-256 is unavailable on this JVM", e);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * One message for both "no such user" and "wrong password".
     *
     * <p>Any difference between the two is a username oracle, and the frontend
     * shows this text to a human anyway, so it has no user-facing cost.
     */
    private static BusinessRuleException invalidCredentials() {
        return new BusinessRuleException("Invalid username or password");
    }
}
