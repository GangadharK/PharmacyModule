package com.potlatech.pharmacy.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.potlatech.pharmacy.common.Specifications;
import com.potlatech.pharmacy.common.dto.PageResponse;
import com.potlatech.pharmacy.common.exception.BusinessRuleException;
import com.potlatech.pharmacy.common.exception.ResourceNotFoundException;
import com.potlatech.pharmacy.dto.catalogue.CreateMedicineRequest;
import com.potlatech.pharmacy.dto.catalogue.MedicineResponse;
import com.potlatech.pharmacy.dto.catalogue.UpdateMedicineRequest;
import com.potlatech.pharmacy.entity.Manufacturer;
import com.potlatech.pharmacy.entity.Medicine;
import com.potlatech.pharmacy.repository.ManufacturerRepository;
import com.potlatech.pharmacy.repository.MedicineRepository;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Medicines — the catalogue a pharmacist searches and a batch is stocked
 * against.
 *
 * <p><strong>Every write rebuilds {@code searchBlob}.</strong> The blob is a
 * denormalised cache of the searchable text, and a stale one is a medicine that
 * cannot be found by its own name — so it is never patched field-by-field but
 * always recomputed from the entity's current state after the change. Deriving
 * it inside the service (not the database) is what lets the same value be unit
 * tested without a database at all; see {@link CatalogueSearchText}.
 */
@Service
@Transactional(readOnly = true)
public class MedicineService {

    private final MedicineRepository medicines;
    private final ManufacturerRepository manufacturers;

    public MedicineService(MedicineRepository medicines, ManufacturerRepository manufacturers) {
        this.medicines = medicines;
        this.manufacturers = manufacturers;
    }

    /**
     * Filtered, paginated catalogue listing.
     *
     * @param search  substring matched against the generic name, brand and the
     *                denormalised blob. Blank is treated as absent, so a cleared
     *                search box returns the whole catalogue rather than nothing.
     * @param activeOnly {@code true} returns active rows only, {@code false} returns
     *                deactivated rows only. {@code null} applies no filter and
     *                returns both, which is what the UI's "All" state asks for.
     *                Every other optional filter here treats {@code null} as
     *                "no restriction"; making this one the exception meant the
     *                tri-state control had a third option the wire format could
     *                not express.
     */
    @Transactional(readOnly = true)
    public PageResponse<MedicineResponse> list(String search, Boolean activeOnly,
                                               Medicine.DosageForm dosageForm,
                                               Medicine.ScheduleClass scheduleClass,
                                               UUID manufacturerId,
                                               Pageable pageable) {
        Page<Medicine> page = medicines.findAll(
                Specifications.allOf(
                        Specifications.textOr(search, "searchBlob", "genericName", "brandName"),
                        Specifications.eq("isActive", activeOnly),
                        Specifications.eq("dosageForm", dosageForm),
                        Specifications.eq("scheduleClass", scheduleClass),
                        Specifications.eq("manufacturerId", manufacturerId)),
                pageable);

        // Resolved once for the whole page, then looked up per row from the map.
        // Computing it inside the map lambda would re-query for every medicine,
        // which is the N+1 this whole exercise exists to avoid.
        Map<UUID, String> names = manufacturerNames(page.getContent());
        return PageResponse.from(
                page.map(m -> MedicineResponse.from(m, nameFor(names, m.getManufacturerId()))));
    }

    @Transactional(readOnly = true)
    public MedicineResponse get(UUID id) {
        return toResponse(require(id));
    }

    @Transactional
    public MedicineResponse create(CreateMedicineRequest request) {
        String genericName = request.normalisedGenericName();

        if (medicines.existsByGenericNameFormAndStrength(
                genericName, request.dosageForm(), request.strength())) {
            throw new BusinessRuleException(
                    "A %s entry for '%s' %s already exists",
                    request.dosageForm(), genericName, request.strength());
        }

        UUID manufacturerId = resolveManufacturerId(request.manufacturerId());

        Medicine medicine = Medicine.builder()
                .id(UUID.randomUUID())
                .genericName(genericName)
                .brandName(blankToNull(request.brandName()))
                .strength(blankToNull(request.strength()))
                .dosageForm(request.dosageForm())
                .scheduleClass(request.resolvedScheduleClass())
                .requiresPrescription(request.resolvedRequiresPrescription())
                .manufacturerId(manufacturerId)
                .reorderLevel(request.resolvedReorderLevel())
                .build();

        medicine.setSearchBlob(CatalogueSearchText.forMedicine(
                medicine.getGenericName(), medicine.getBrandName(),
                medicine.getStrength(), medicine.getDosageForm().name()));

        return toResponse(medicines.save(medicine));
    }

    @Transactional
    public MedicineResponse update(UUID id, UpdateMedicineRequest request) {
        Medicine medicine = require(id);
        String newGenericName = request.normalisedGenericName();

        // Only re-check the duplicate when the tuple actually moves, or a
        // pharmacist correcting the brand name of a row would be told it
        // conflicts with itself.
        boolean tupleChanges = newGenericName != null
                && !newGenericName.equalsIgnoreCase(medicine.getGenericName());
        if (tupleChanges
                && medicines.existsByGenericNameFormAndStrength(
                        newGenericName, medicine.getDosageForm(), medicine.getStrength())) {
            throw new BusinessRuleException(
                    "A %s entry for '%s' %s already exists",
                    medicine.getDosageForm(), newGenericName, medicine.getStrength());
        }

        if (newGenericName != null) {
            medicine.setGenericName(newGenericName);
        }
        if (request.brandName() != null) {
            medicine.setBrandName(blankToNull(request.brandName()));
        }
        if (request.strength() != null) {
            medicine.setStrength(blankToNull(request.strength()));
        }
        if (request.scheduleClass() != null) {
            medicine.setScheduleClass(
                    Medicine.ScheduleClass.valueOf(request.scheduleClass().name()));
        }
        if (request.requiresPrescription() != null) {
            medicine.setRequiresPrescription(request.requiresPrescription());
        }
        if (request.manufacturerId() != null) {
            medicine.setManufacturerId(resolveManufacturerId(request.manufacturerId()));
        }
        if (request.reorderLevel() != null) {
            medicine.setReorderLevel(request.reorderLevel());
        }

        medicine.setSearchBlob(CatalogueSearchText.forMedicine(
                medicine.getGenericName(), medicine.getBrandName(),
                medicine.getStrength(), medicine.getDosageForm().name()));
        medicine.setUpdatedAt(LocalDateTime.now());

        return toResponse(medicines.save(medicine));
    }

    /**
     * Deactivates rather than deletes.
     *
     * <p>A discontinued medicine keeps its batches and its dispensing history.
     * Deleting it would cascade into the batch ledger — the one table in this
     * system that must never lose a row.
     */
    @Transactional
    public MedicineResponse deactivate(UUID id) {
        Medicine medicine = require(id);
        if (Boolean.FALSE.equals(medicine.getIsActive())) {
            return toResponse(medicine);
        }
        medicine.setIsActive(false);
        medicine.setUpdatedAt(LocalDateTime.now());
        return toResponse(medicines.save(medicine));
    }

    @Transactional
    public MedicineResponse reactivate(UUID id) {
        Medicine medicine = require(id);
        if (Boolean.TRUE.equals(medicine.getIsActive())) {
            return toResponse(medicine);
        }
        medicine.setIsActive(true);
        medicine.setUpdatedAt(LocalDateTime.now());
        return toResponse(medicine);
    }

    // ------------------------------------------------------------------ helpers

    private Medicine require(UUID id) {
        return medicines.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Medicine", id));
    }

    /**
     * Validates a manufacturer id before storing it.
     *
     * <p>The FK would reject a bad id, but only at flush time and as a raw
     * constraint violation. Checking here turns it into a 400 that names the
     * problem, which is the difference between a usable form error and a stack
     * trace in the pharmacy's error log.
     *
     * @return the id, or {@code null} when none was supplied
     */
    private UUID resolveManufacturerId(UUID manufacturerId) {
        if (manufacturerId == null) {
            return null;
        }
        if (!manufacturers.existsById(manufacturerId)) {
            throw new ResourceNotFoundException("Manufacturer", manufacturerId);
        }
        return manufacturerId;
    }

    /**
     * Single-row case: one lookup, so no batching needed.
     *
     * <p>The list path deliberately does <em>not</em> route through here — it
     * resolves names for the whole page in one query instead, see
     * {@link #manufacturerNames(List)}.
     */
    private MedicineResponse toResponse(Medicine medicine) {
        String manufacturerName = medicine.getManufacturerId() == null
                ? null
                : manufacturers.findById(medicine.getManufacturerId())
                        .map(Manufacturer::getName)
                        .orElse(null);
        return MedicineResponse.from(medicine, manufacturerName);
    }

    /**
     * @return id to display name for just the manufacturers appearing on this
     *         page. An id with no row yields no entry, and the response reports
     *         a null name rather than failing: a medicine whose manufacturer was
     *         deleted is unattributed, not unreadable.
     */
    private Map<UUID, String> manufacturerNames(List<Medicine> page) {
        List<UUID> ids = page.stream()
                .map(Medicine::getManufacturerId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            // A HashMap, not Map.of(). Map.of() returns an immutable map whose
            // get(null) throws NullPointerException, so a page containing a
            // medicine with no manufacturer would blow up on lookup instead of
            // reporting a null name.
            return new HashMap<>();
        }
        return manufacturers.findAllById(ids).stream()
                .collect(Collectors.toMap(Manufacturer::getId, Manufacturer::getName,
                        (first, duplicate) -> first, HashMap::new));
    }

    /**
     * Null-safe name lookup.
     *
     * <p>{@code Map#get} is specified to throw on a null key for the immutable
     * factories, so a medicine with no manufacturer must be handled before the
     * lookup rather than passed into it.
     */
    private static String nameFor(Map<UUID, String> names, UUID manufacturerId) {
        return manufacturerId == null ? null : names.get(manufacturerId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
