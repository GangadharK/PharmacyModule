package com.potlatech.pharmacy.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.potlatech.pharmacy.entity.User;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository
        extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    /**
     * Case-insensitive username lookup, the one hot path of the auth subsystem.
     *
     * <p>Written as an explicit {@code lower()} comparison rather than a derived
     * {@code findByUsernameIgnoreCase} query. Both are semantically correct, but
     * Hibernate implements the derived form with {@code upper()}, which will not
     * match the {@code lower(username)} functional unique index created by
     * {@code V3__username_case_insensitive.sql} — so the login query would fall
     * back to a sequential scan of {@code users}. Explicit {@code lower()} on both
     * sides matches the index.
     *
     * <p>Case-insensitivity itself is a database concern: a functional unique index
     * on {@code lower(username)} created by
     * {@code V3__username_case_insensitive.sql}. That collapses
     * {@code Aiswarya} and {@code aiswarya} into one account, so a pharmacist
     * cannot end up with two accounts differing only in case. It is a plain index
     * rather than the {@code citext} extension, which reports as
     * {@code Types#OTHER} and fails {@code ddl-auto: validate}.
     *
     * <p><strong>Does this leak whether a username exists?</strong> Yes, and
     * deliberately. A distinct "no such user" response is enumeration, but the
     * mitigation is to make the wrong-password path cost the same as the
     * right-password path, not to hide which usernames exist. The service
     * verifies against a dummy hash when the user is absent, so both branches
     * take comparable time and both return the same message.
     */
    @Query("select u from User u where lower(u.username) = lower(:username)")
    Optional<User> findByUsername(@Param("username") String username);

    /**
     * @return true if the username is taken, case-insensitively. Backs the
     *         create-user validation so a duplicate is a 409 rather than an
     *         unhandled constraint violation.
     */
    @Query("select case when count(u) > 0 then true else false end from User u "
            + "where lower(u.username) = lower(:username)")
    boolean existsByUsername(@Param("username") String username);

    Page<User> findAllByOrderByFullNameAsc(Pageable pageable);

    long countByRoleAndIsActiveTrue(User.Role role);
}
