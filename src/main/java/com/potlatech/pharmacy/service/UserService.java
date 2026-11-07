package com.potlatech.pharmacy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.potlatech.pharmacy.common.Specifications;
import com.potlatech.pharmacy.common.dto.PageResponse;
import com.potlatech.pharmacy.common.exception.AccessForbiddenException;
import com.potlatech.pharmacy.common.exception.BusinessRuleException;
import com.potlatech.pharmacy.common.exception.ResourceNotFoundException;
import com.potlatech.pharmacy.dto.user.CreateUserRequest;
import com.potlatech.pharmacy.dto.user.UpdateUserRequest;
import com.potlatech.pharmacy.dto.user.UserResponse;
import com.potlatech.pharmacy.entity.User;
import com.potlatech.pharmacy.repository.RefreshTokenRepository;
import com.potlatech.pharmacy.repository.UserRepository;
import com.potlatech.pharmacy.security.AuthenticatedUser;
import com.potlatech.pharmacy.security.CurrentUserContext;

import java.util.UUID;

/**
 * User management. Every method here is {@code SUPER_ADMIN}-only, enforced at the
 * controller by {@code @PreAuthorize}; the checks inside are about the
 * relationships between accounts rather than about the caller.
 *
 * <p><strong>Every mutation revokes the target's sessions.</strong> Not
 * optionally — a role change that leaves live refresh tokens behind is a
 * demoted user who is still a manager for up to a week, because refresh
 * re-reads the role and issues a fresh token. This is the single most important
 * invariant in the class.
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository users;
    private final RefreshTokenRepository tokens;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;
    private final CurrentUserContext currentUser;

    public UserService(UserRepository users,
                       RefreshTokenRepository tokens,
                       PasswordEncoder passwordEncoder,
                       AuthService authService,
                       CurrentUserContext currentUser) {
        this.users = users;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.authService = authService;
        this.currentUser = currentUser;
    }

    /**
     * Filters are built with {@link Specifications} rather than a JPQL query
     * with {@code :x is null} guards — see that class for why an untyped null
     * parameter breaks the generated SQL on PostgreSQL.
     *
     * <p>The text filter is an OR across username and full name, ANDed with the
     * role and active filters. Getting that boolean wrong is invisible in
     * testing if the fixtures happen to match every column at once.
     */
    @Transactional(readOnly = true)
    public PageResponse<UserResponse> list(String search, User.Role role, Boolean active,
                                           Pageable pageable) {
        Page<User> page = users.findAll(
                Specifications.allOf(
                        Specifications.textOr(search, "username", "fullName"),
                        Specifications.eq("role", role),
                        Specifications.eq("isActive", active)),
                pageable);
        return PageResponse.from(page.map(UserResponse::from));
    }

    @Transactional(readOnly = true)
    public UserResponse get(UUID id) {
        return UserResponse.from(require(id));
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        String username = request.normalisedUsername();

        if (users.existsByUsername(username)) {
            // BusinessRuleException, not a constraint violation: the unique index
            // would also reject it, but a DataIntegrityViolationException surfaces
            // as a 500 and leaks the index name in some configurations. Checking
            // first gives a clean 400; the index remains the real guarantee
            // against a race between two concurrent creates.
            throw new BusinessRuleException(
                    "Username '%s' is already taken".formatted(username));
        }

        User user = User.builder()
                .id(UUID.randomUUID())
                .username(username)
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.fullName().trim())
                .role(request.role())
                .isActive(true)
                .build();

        users.save(user);
        log.info("User {} created with role {}", user.getUsername(), user.getRole());
        return UserResponse.from(user);
    }

    /**
     * Applies a partial update.
     *
     * <p>Refuses to let a {@code SUPER_ADMIN} remove the last remaining
     * {@code SUPER_ADMIN}. A system that can lock every administrator out has no
     * recovery path short of direct SQL, and this is the moment it would happen.
     */
    @Transactional
    public UserResponse update(UUID id, UpdateUserRequest request) {
        User user = require(id);

        // Decide the outcome before mutating, so the guard below can compare
        // counts against the state that actually exists in the database. Checking
        // after assignment would read the in-memory entity, which is already the
        // new value, and the deactivation branch would always short-circuit.
        boolean losesSuperAdmin = user.getRole() == User.Role.SUPER_ADMIN
                && (Boolean.FALSE.equals(request.active()) || request.role() != null
                    && request.role() != User.Role.SUPER_ADMIN);
        if (losesSuperAdmin) {
            guardNotLastSuperAdmin();
        }

        if (request.fullName() != null) {
            user.setFullName(request.fullName().trim());
        }
        if (request.password() != null) {
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }

        boolean roleChanged = request.role() != null && request.role() != user.getRole();
        if (request.role() != null) {
            user.setRole(request.role());
        }

        boolean deactivated = Boolean.FALSE.equals(request.active());
        if (request.active() != null) {
            user.setIsActive(request.active());
        }

        users.save(user);

        if (roleChanged || deactivated || request.password() != null) {
            // A new password is included: a password reset that leaves old
            // sessions alive means the credential did not actually change.
            authService.revokeAllSessions(user.getId());
            log.info("Revoked sessions for user {} after role/deactivation/password change",
                    user.getUsername());
        }

        return UserResponse.from(user);
    }

    /**
     * Deactivates rather than deletes.
     *
     * <p>A deleted user leaves {@code stock_transaction.performed_by} and
     * {@code purchase_order.created_by} pointing at nothing, and those are the
     * rows that answer "who dispensed this". {@code ON DELETE RESTRICT} would
     * make the delete fail once any history exists, which is a confusing error to
     * hand a user who pressed the wrong button. Deactivation preserves the
     * trail and is reversible.
     */
    @Transactional
    public UserResponse deactivate(UUID id) {
        User user = require(id);

        if (id.equals(currentUser.idOrNull())) {
            throw new BusinessRuleException("You cannot deactivate your own account");
        }
        if (user.getRole() == User.Role.SUPER_ADMIN) {
            guardNotLastSuperAdmin();
        }

        user.setIsActive(false);
        users.save(user);
        authService.revokeAllSessions(user.getId());

        log.info("User {} deactivated", user.getUsername());
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse reactivate(UUID id) {
        User user = require(id);
        user.setIsActive(true);
        users.save(user);
        log.info("User {} reactivated", user.getUsername());
        return UserResponse.from(user);
    }

    private User require(UUID id) {
        return users.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    /**
     * @throws BusinessRuleException if this would leave the system with no
     *                                active {@code SUPER_ADMIN}.
     */
    private void guardNotLastSuperAdmin() {
        long remaining = users.countByRoleAndIsActiveTrue(User.Role.SUPER_ADMIN);
        if (remaining <= 1) {
            throw new BusinessRuleException(
                    "This is the last active SUPER_ADMIN. Promote another user first - "
                            + "removing the last one would lock everyone out of user management.");
        }
    }

    /** @return true if the caller is acting on their own account. */
    boolean isSelf(UUID id) {
        AuthenticatedUser caller = currentUser.find();
        return caller != null && caller.id().equals(id);
    }
}
