package com.potlatech.pharmacy.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Login request body.
 *
 * <p>A record, per the DTO convention in {@code code-standards.md} §2.4.
 *
 * <p>No {@code @Email} on the username: it is a login name, not an address, and
 * the seeded dev account is {@code superadmin}.
 *
 * @param username case-insensitive; the database compares on {@code lower(username)}
 * @param password bounded at 72 characters because that is where BCrypt stops
 *                 reading — accepting a longer password silently ignores its
 *                 tail, so a user typing a long passphrase would be admitted
 *                 with only the first 72 characters mattering. Rejecting it is
 *                 more honest than pretending to check the whole thing.
 */
public record LoginRequest(
        @NotBlank(message = "username is required")
        @Size(max = 100, message = "username must be at most 100 characters")
        String username,

        @NotBlank(message = "password is required")
        @Size(max = 72, message = "password must be at most 72 characters")
        String password) {

    /**
     * @return the username trimmed and lowercased.
     *
     * <p>Trimming matters: a user who copy-pastes a username with a trailing
     * space should not be told their password is wrong. Lowercasing is cosmetic
     * here — the query already compares with {@code lower()} — but it means the
     * value that reaches the log line is the canonical one.
     */
    public String normalisedUsername() {
        return username == null ? null : username.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
