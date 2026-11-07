package com.potlatech.pharmacy.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.potlatech.pharmacy.entity.NonMedicalItem;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface NonMedicalItemRepository
        extends JpaRepository<NonMedicalItem, UUID>, JpaSpecificationExecutor<NonMedicalItem> {

    /**
     * Backs the duplicate check.
     *
     * <p>Scoped by {@code unit} rather than by name alone, because a box of 100
     * and a box of 50 are genuinely different stock lines that a pharmacy may
     * well keep under one label. Same name and same unit is the data-entry
     * slip worth refusing.
     */
    @Query("""
            select case when count(i) > 0 then true else false end from NonMedicalItem i
            where lower(i.name) = lower(:name) and i.unit = :unit
            """)
    boolean existsByNameAndUnit(@Param("name") String name,
                                @Param("unit") NonMedicalItem.Unit unit);

    Page<NonMedicalItem> findByIsActiveTrue(Pageable pageable);

    Optional<NonMedicalItem> findByIdAndIsActiveTrue(UUID id);

    Optional<NonMedicalItem> findByNameIgnoreCaseAndUnit(String name, NonMedicalItem.Unit unit);

    Page<NonMedicalItem> findByCategory(NonMedicalItem.Category category, Pageable pageable);
}
