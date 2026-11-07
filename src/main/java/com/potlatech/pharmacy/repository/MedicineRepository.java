package com.potlatech.pharmacy.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.potlatech.pharmacy.entity.Medicine;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MedicineRepository
        extends JpaRepository<Medicine, UUID>, JpaSpecificationExecutor<Medicine> {

    /**
     * Backs the catalogue duplicate check: the same generic, strength and form
     * twice over is a data-entry slip, whereas the same generic in two forms is
     * two real products.
     *
     * <p>Case-insensitive on the text parts and compared on the enum parts
     * directly, so {@code paracetamol} and {@code PARACETAMOL} collide but
     * {@code TABLET} and {@code SYRUP} do not.
     *
     * <p>Not a database constraint: an optional {@code brandName} makes the
     * uniqueness question ambiguous in the presence of nulls, and a
     * {@code NULLS NOT DISTINCT} unique index would reject the legitimate case
     * of two generics-only entries that differ only in an unstated brand.
     */
    @Query("""
            select case when count(m) > 0 then true else false end from Medicine m
            where lower(m.genericName) = lower(:genericName)
              and m.dosageForm = :dosageForm
              and m.strength = :strength
            """)
    boolean existsByGenericNameFormAndStrength(@Param("genericName") String genericName,
                                               @Param("dosageForm") Medicine.DosageForm dosageForm,
                                               @Param("strength") String strength);

    /**
     * Medicines from one manufacturer, for the catalogue drill-down.
     *
     * <p>Uses the {@code idx_med_mfr} index from {@code V4}.
     */
    List<Medicine> findByManufacturerIdOrderByGenericNameAsc(UUID manufacturerId);

    /**
     * Active medicines, the set a stock batch may reference.
     *
     * <p>{@code findByIsActiveTrue} rather than deriving order here: the caller
     * paginates, and a {@code Pageable} on a derived query composes with the
     * sort it is given.
     */
    Page<Medicine> findByIsActiveTrue(Pageable pageable);

    Optional<Medicine> findByIdAndIsActiveTrue(UUID id);

    /**
     * Whether any catalogue row points at this manufacturer.
     *
     * <p>Guards the delete. The FK is {@code ON DELETE SET NULL}, so the
     * database would happily null out every {@code manufacturer_id} and leave
     * the service with no error — the medicines would simply stop naming who
     * makes them. This query is what turns that silent data loss into a
     * refusal.
     */
    @Query("select case when count(m) > 0 then true else false end from Medicine m "
            + "where m.manufacturerId = :manufacturerId")
    boolean existsByManufacturerId(@Param("manufacturerId") UUID manufacturerId);
}
