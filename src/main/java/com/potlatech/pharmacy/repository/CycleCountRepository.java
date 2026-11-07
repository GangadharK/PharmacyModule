package com.potlatech.pharmacy.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.potlatech.pharmacy.entity.CycleCount;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CycleCountRepository extends JpaRepository<CycleCount, UUID> {

    Page<CycleCount> findByStatusOrderByCreatedAtDesc(CycleCount.Status status, Pageable pageable);

    Page<CycleCount> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Highest sequence issued for a prefix, for building the next reference number.
     *
     * <p>{@code MAX(...)} on the numeric tail rather than {@code COUNT(*)}, so a
     * deleted session leaves a gap in the numbering rather than handing its
     * number to the next count. Counts are cited on discrepancy reports, and a
     * number that refers to two different events is worse than a gap.
     *
     * <p>Runs under the uniqueness constraint on {@code reference_no} as the real
     * guard: two concurrent creators can both read the same max here, and the
     * loser gets a constraint violation rather than a duplicate citation.
     */
    @Query("SELECT COALESCE(MAX(CAST(SUBSTRING(c.referenceNo, :prefixLength + 1) AS int)), 0) " +
           "FROM CycleCount c WHERE c.referenceNo LIKE CONCAT(:prefix, '%')")
    int maxSequence(@Param("prefix") String prefix, @Param("prefixLength") int prefixLength);

    Optional<CycleCount> findByReferenceNo(String referenceNo);
}
