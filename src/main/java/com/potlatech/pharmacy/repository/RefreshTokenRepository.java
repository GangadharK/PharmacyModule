package com.potlatech.pharmacy.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.potlatech.pharmacy.entity.RefreshToken;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /**
     * The refresh path. {@code token_hash} is unique and indexed, so this is a
     * single index seek regardless of table size.
     */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Every live session for a user. Used by "revoke all sessions" on logout-all. */
    List<RefreshToken> findAllByUserIdAndRevokedAtIsNull(UUID userId);

    /** Bulk purge of rows that expired before the cutoff. Drives the sweep job. */
    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Revokes every live session for a user and returns how many were affected.
     *
     * <p>Used when a role changes or an account is deactivated. Without this, a
     * demoted user keeps refreshing for up to the access-token lifetime with
     * their old role baked into an unexpired JWT.
     */
    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now "
            + "where t.userId = :userId and t.revokedAt is null")
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") LocalDateTime now);
}
