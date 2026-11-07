package com.potlatech.pharmacy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.potlatech.pharmacy.repository.RefreshTokenRepository;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Revokes live sessions in a transaction that commits independently of its
 * caller.
 *
 * <p><strong>This exists because of a specific bug.</strong> The refresh path
 * detects a replayed token, revokes every session for that user, and then throws
 * to tell the caller what happened. In an ordinary {@code @Transactional}
 * method that throw rolls the revocation back: the log says every session was
 * signed out, the response says every session was signed out, and the next
 * request with the thief's token succeeds. The security response to an attack
 * was undone by the exception reporting the attack.
 *
 * <p>{@code REQUIRES_NEW} fixes it. The inner transaction commits before the
 * outer one is rolled back, so the revocation survives.
 *
 * <p>Its own bean rather than a method on {@code AuthService}, because Spring's
 * {@code @Transactional} is proxy-based: a self-invocation never passes through
 * the proxy, so {@code authService.revokeAllInNewTransaction(...)} called from
 * inside {@code AuthService} would run in the caller's transaction and the
 * annotation would be silently inert. Splitting it out makes the propagation
 * actually take effect.
 */
@Component
public class SessionRevoker {

    private static final Logger log = LoggerFactory.getLogger(SessionRevoker.class);

    private final RefreshTokenRepository tokens;

    public SessionRevoker(RefreshTokenRepository tokens) {
        this.tokens = tokens;
    }

    /**
     * Revokes every live session for a user, committing regardless of whether
     * the caller goes on to throw.
     *
     * @return how many sessions were revoked
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeAll(UUID userId, LocalDateTime now) {
        int revoked = tokens.revokeAllForUser(userId, now);
        log.warn("Committed revocation of {} refresh session(s) for user {} "
                + "in an independent transaction", revoked, userId);
        return revoked;
    }

    /** Convenience overload using the system clock. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeAll(UUID userId) {
        return revokeAll(userId, LocalDateTime.now());
    }
}
