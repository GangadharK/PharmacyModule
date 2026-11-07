package com.potlatech.pharmacy.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.potlatech.pharmacy.common.dto.PageResponse;
import com.potlatech.pharmacy.dto.user.CreateUserRequest;
import com.potlatech.pharmacy.dto.user.UpdateUserRequest;
import com.potlatech.pharmacy.dto.user.UserResponse;
import com.potlatech.pharmacy.entity.User;
import com.potlatech.pharmacy.service.UserService;

import java.util.UUID;

/**
 * {@code /api/v1/users} — user management.
 *
 * <p>Class-level {@code @PreAuthorize}, not per-method. A new endpoint added to
 * this controller without its own annotation is then denied by default rather
 * than silently exposed, which is the direction a mistake should fail in.
 *
 * <p>All values UPPER_CASE, per {@code AGENTS.md} rule 8. Spring's default
 * authority comparison is case-sensitive, so {@code hasRole('super_admin')} would
 * match nothing and read as a working-but-broken endpoint.
 */
@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(name = "Users", description = "User management (SUPER_ADMIN only)")
public class UserController {

    private static final int MAX_PAGE_SIZE = 100;

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @Operation(summary = "List users, optionally filtered by text, role and active flag")
    public PageResponse<UserResponse> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) User.Role role,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        // Clamped rather than trusted. An unbounded size lets one request ask for
        // a million rows, and the only thing that stops it is a check the caller
        // does not control.
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return userService.list(
                blankToNull(search), role, active,
                PageRequest.of(Math.max(page, 0), safeSize, Sort.by("fullName").ascending()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one user")
    public UserResponse get(@PathVariable UUID id) {
        return userService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a user")
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        return userService.create(request);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update a user. Password or role change revokes their sessions")
    public UserResponse update(@PathVariable UUID id,
                               @Valid @RequestBody UpdateUserRequest request) {
        return userService.update(id, request);
    }

    /**
     * Deactivate, not delete.
     *
     * <p>Mapped as {@code DELETE} because that is what the client means, but it
     * is a soft delete: the row survives so the audit trail that references it
     * still resolves. See {@code UserService#deactivate}.
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Deactivate a user and revoke their sessions")
    public UserResponse deactivate(@PathVariable UUID id) {
        return userService.deactivate(id);
    }

    @PostMapping("/{id}/reactivate")
    @Operation(summary = "Reactivate a deactivated user")
    public UserResponse reactivate(@PathVariable UUID id) {
        return userService.reactivate(id);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
