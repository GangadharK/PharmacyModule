package com.potlatech.pharmacy.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.potlatech.pharmacy.entity.Supplier;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SupplierRepository
        extends JpaRepository<Supplier, UUID>, JpaSpecificationExecutor<Supplier> {

    /**
     * Case-insensitive lookup matching the {@code lower(name)} functional unique
     * index from {@code V4}.
     */
    @Query("select s from Supplier s where lower(s.name) = lower(:name)")
    Optional<Supplier> findByName(@Param("name") String name);

    @Query("select case when count(s) > 0 then true else false end from Supplier s "
            + "where lower(s.name) = lower(:name)")
    boolean existsByName(@Param("name") String name);

    /** Active suppliers: the set a purchase order may be raised against. */
    Page<Supplier> findByIsActiveTrue(Pageable pageable);

    Optional<Supplier> findByIdAndIsActiveTrue(UUID id);

    /**
     * Suppliers carrying a given GSTIN.
     *
     * <p>Existence-only, not a uniqueness guarantee. Two supplier rows sharing a
     * GSTIN is legal in India — a company can buy through several local
     * counters — so the service surfaces it as a warning rather than a
     * constraint violation.
     */
    @Query("select case when count(s) > 0 then true else false end from Supplier s "
            + "where s.gstNumber = :gstNumber and s.id <> :excludeId")
    boolean existsByGstNumberOtherThan(@Param("gstNumber") String gstNumber,
                                       @Param("excludeId") UUID excludeId);
}
