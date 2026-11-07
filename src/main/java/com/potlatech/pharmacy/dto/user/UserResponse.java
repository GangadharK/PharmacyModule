package com.potlatech.pharmacy.dto.user;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.potlatech.pharmacy.entity.User;

/**
 * A user row in a user-management response.
 *
 * <p><strong>Never contains the password hash.</strong> Not because it is
 * inconvenient, but because a DTO that could carry it will eventually be
 * serialised somewhere it should not be. The hash is not on this record at all,
 * so there is no code path that can leak it — the field simply does not exist.
 * {@code AGENTS.md} rule 5 requires it.
 */
public record UserResponse(
        UUID id,
        String username,
        String fullName,
        User.Role role,
        boolean active,
        LocalDateTime createdAt,
        LocalDateTime lastLoginAt) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                user.getRole(),
                Boolean.TRUE.equals(user.getIsActive()),
                user.getCreatedAt(),
                user.getLastLoginAt());
    }

    public static List<UserResponse> from(List<User> users) {
        return users.stream().map(UserResponse::from).toList();
    }
}
