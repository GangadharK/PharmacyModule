package com.potlatech.pharmacy.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.potlatech.pharmacy.common.exception.AccessForbiddenException;

import java.util.UUID;

/**
 * Reads the current principal out of Spring Security's context.
 *
 * <p>Registered as a bean so services can inject an interface and be tested
 * against a stub. The alternative — a static call to
 * {@code SecurityContextHolder} from inside a service — is what makes domain
 * code untestable without a security context, and it hides a hard dependency on
 * ambient state behind what looks like a plain getter.
 */
@Component
public class SpringSecurityCurrentUserContext implements CurrentUserContext {

    @Override
    public AuthenticatedUser require() {
        AuthenticatedUser user = find();
        if (user == null) {
            throw new AccessForbiddenException(
                    "No authenticated user in the security context. This endpoint is "
                            + "reachable without authentication, which is a security "
                            + "configuration error rather than a caller error.");
        }
        return user;
    }

    @Override
    public AuthenticatedUser find() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication.getPrincipal() instanceof AuthenticatedUser user ? user : null;
    }

    @Override
    public UUID idOrNull() {
        AuthenticatedUser user = find();
        return user == null ? null : user.id();
    }
}
