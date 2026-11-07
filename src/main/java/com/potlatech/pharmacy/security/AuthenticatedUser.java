package com.potlatech.pharmacy.security;

import java.util.List;
import java.util.UUID;

import com.potlatech.pharmacy.entity.User;

/**
 * The authenticated caller, as carried in the {@code SecurityContext}.
 *
 * <p>A dedicated type rather than reusing the {@link User} entity. Loading the
 * entity on every request would mean a database round trip per request for data
 * the filter already has, and it would put a JPA entity inside the security
 * context where it can be accidentally serialised back to a client.
 *
 * <p>{@code record} so it is immutable — an authentication principal that can be
 * mutated after the fact is a confused-deputy bug waiting to happen.
 *
 * @param id       the user's {@code UUID}, used as the JWT subject
 * @param username as typed at login, preserved for the audit trail
 * @param fullName display name, carried so the client need not re-fetch it
 * @param role     single role; the authority list is derived, not stored, so it
 *                 cannot disagree with the role
 */
public record AuthenticatedUser(
        UUID id,
        String username,
        String fullName,
        User.Role role) {

    /**
     * @return every authority this role implies, in the same shape
     *         {@link User.Role#authorities()} produces, so there is one
     *         interpretation of the role hierarchy in the whole application.
     */
    public List<org.springframework.security.core.GrantedAuthority> authorities() {
        return role.authorities();
    }
}
