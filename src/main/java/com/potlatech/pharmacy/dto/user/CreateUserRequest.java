package com.potlatech.pharmacy.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

import com.potlatech.pharmacy.entity.User;

/**
 * Create-user request.
 *
 * @param username login name, unique case-insensitively
 * @param password at most 72 characters — the point at which BCrypt stops
 *                 reading, so anything longer is partly ignored
 * @param fullName display name, shown on dispensed records
 * @param role     one of the four roles. Not validated against the hierarchy
 *                 here: the hierarchy governs what a role <em>may do</em>, not
 *                 what it may be set to. {@code UserService} handles who is
 *                 allowed to grant which role.
 */
public record CreateUserRequest(
        @NotBlank(message = "username is required")
        @Size(min = 3, max = 100, message = "username must be 3 to 100 characters")
        @Pattern(regexp = "^[A-Za-z0-9._-]+$",
                message = "username may contain letters, digits, dot, underscore and hyphen only")
        String username,

        @NotBlank(message = "password is required")
        @Size(min = 8, max = 72, message = "password must be 8 to 72 characters")
        String password,

        @NotBlank(message = "fullName is required")
        @Size(max = 255, message = "fullName must be at most 255 characters")
        String fullName,

        @NotNull(message = "role is required")
        User.Role role) {

    /** @return the username trimmed, for the uniqueness check. */
    public String normalisedUsername() {
        return username == null ? null : username.trim();
    }
}
