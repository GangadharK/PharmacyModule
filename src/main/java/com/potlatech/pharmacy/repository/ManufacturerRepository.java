package com.potlatech.pharmacy.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.potlatech.pharmacy.entity.Manufacturer;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ManufacturerRepository
        extends JpaRepository<Manufacturer, UUID>, JpaSpecificationExecutor<Manufacturer> {

    /**
     * Case-insensitive lookup, matching the {@code lower(name)} functional unique
     * index from {@code V4}.
     *
     * <p>Explicit {@code lower()} on both sides rather than a derived
     * {@code findByNameIgnoreCase}: Hibernate implements the derived form with
     * {@code upper()}, which cannot use that index and degrades the lookup to a
     * sequential scan. Same reasoning as {@code UserRepository#findByUsername}.
     */
    @Query("select m from Manufacturer m where lower(m.name) = lower(:name)")
    Optional<Manufacturer> findByName(@Param("name") String name);

    @Query("select case when count(m) > 0 then true else false end from Manufacturer m "
            + "where lower(m.name) = lower(:name)")
    boolean existsByName(@Param("name") String name);
}
