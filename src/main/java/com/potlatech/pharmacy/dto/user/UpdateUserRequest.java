package com.potlatech.pharmacy.dto.user;

import com.potlatech.pharmacy.entity.User;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Update-user request.
 *
 * <p>Every field optional, and {@code null} means "leave it alone". That is why
 * this is a separate type from {@link CreateUserRequest} rather than one type
 * with nullable fields used for both: on create, a missing username is a
 * validation failure, and on update it is a no-op. Expressing both with one
 * shape means one of the two is always wrong.
 *
 * <p>{@code username} is not updatable. It is the account's identity and is
 * referenced by every audit row; changing it would mean history that no longer
 * matches the account it describes. The endpoint rejects it rather than ignoring
 * it silently.
 *
 * @param password new password, or {@code null} to keep the current one. A
 *                 dedicated password-reset path is not in v1, so a
 *                 {@code SUPER_ADMIN} sets it here.
 */
public record UpdateUserRequest(
        @Size(max = 255, message = "fullName must be at most 255 characters")
        String fullName,

        @Size(min = 8, max = 72, message = "password must be 8 to 72 characters")
        String password,

        @NotNull(message = "role is required")
        User.Role role,

        Boolean active) {
}
