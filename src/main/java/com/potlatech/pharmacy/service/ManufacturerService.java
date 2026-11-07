package com.potlatech.pharmacy.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.potlatech.pharmacy.common.Specifications;
import com.potlatech.pharmacy.common.dto.PageResponse;
import com.potlatech.pharmacy.common.exception.BusinessRuleException;
import com.potlatech.pharmacy.common.exception.ResourceNotFoundException;
import com.potlatech.pharmacy.dto.catalogue.CreateManufacturerRequest;
import com.potlatech.pharmacy.dto.catalogue.ManufacturerResponse;
import com.potlatech.pharmacy.dto.catalogue.UpdateManufacturerRequest;
import com.potlatech.pharmacy.entity.Manufacturer;
import com.potlatech.pharmacy.entity.Medicine;
import com.potlatech.pharmacy.repository.ManufacturerRepository;
import com.potlatech.pharmacy.repository.MedicineRepository;

import java.util.UUID;

/**
 * Manufacturers — CRUD for the reference data behind {@link Medicine}.
 *
 * <p>{@code AGENTS.md} rule 5: the entity never leaves this class. Every method
 * returns a {@link ManufacturerResponse} record.
 *
 * <p><strong>Deletes are refused, not cascaded.</strong> The FK from
 * {@code medicine_catalogue} is {@code ON DELETE SET NULL}, which means the
 * database would accept the delete and quietly strip the manufacturer from every
 * medicine that referenced it — a pharmacist would then see "unknown
 * manufacturer" on drugs they had stocked for years, with no error anywhere. The
 * database cannot express that refusal, so it is made here, where it can be a
 * message instead of silent data loss.
 */
@Service
@Transactional(readOnly = true)
public class ManufacturerService {

    private final ManufacturerRepository manufacturers;
    private final MedicineRepository medicines;

    public ManufacturerService(ManufacturerRepository manufacturers, MedicineRepository medicines) {
        this.manufacturers = manufacturers;
        this.medicines = medicines;
    }

    /**
     * Filtered, paginated list.
     *
     * @param search case-insensitive substring of the name, or {@code null} for all
     */
    @Transactional(readOnly = true)
    public PageResponse<ManufacturerResponse> list(String search, Pageable pageable) {
        Page<Manufacturer> page = manufacturers.findAll(
                Specifications.likeLower("name", search), pageable);
        return PageResponse.from(page.map(ManufacturerResponse::from));
    }

    @Transactional(readOnly = true)
    public ManufacturerResponse get(UUID id) {
        return ManufacturerResponse.from(require(id));
    }

    @Transactional
    public ManufacturerResponse create(CreateManufacturerRequest request) {
        String name = request.normalisedName();
        if (manufacturers.existsByName(name)) {
            throw new BusinessRuleException(
                    "A manufacturer named '%s' already exists", name);
        }

        Manufacturer manufacturer = Manufacturer.builder()
                .id(UUID.randomUUID())
                .name(name)
                .licenseNumber(blankToNull(request.licenseNumber()))
                .country(blankToNull(request.country()))
                .build();

        return ManufacturerResponse.from(manufacturers.save(manufacturer));
    }

    @Transactional
    public ManufacturerResponse update(UUID id, UpdateManufacturerRequest request) {
        Manufacturer manufacturer = require(id);
        String newName = request.normalisedName();

        if (newName != null && !newName.equalsIgnoreCase(manufacturer.getName())
                && manufacturers.existsByName(newName)) {
            throw new BusinessRuleException(
                    "A manufacturer named '%s' already exists", newName);
        }

        if (newName != null) {
            manufacturer.setName(newName);
        }
        if (request.licenseNumber() != null) {
            manufacturer.setLicenseNumber(blankToNull(request.licenseNumber()));
        }
        if (request.country() != null) {
            manufacturer.setCountry(blankToNull(request.country()));
        }

        return ManufacturerResponse.from(manufacturers.save(manufacturer));
    }

    /**
     * Deletes a manufacturer, or refuses if any medicine still names it.
     *
     * <p>The check is a read that races a concurrent medicine insert, so it is
     * not a hard guarantee on its own. It does not need to be: the FK would then
     * null the column instead of losing the row, and the medicine remains
     * dispensible with an unattributed manufacturer. The refusal exists to catch
     * the ordinary case and tell a person about it, not to serialise writes.
     */
    @Transactional
    public void delete(UUID id) {
        Manufacturer manufacturer = require(id);
        if (medicines.existsByManufacturerId(id)) {
            throw new BusinessRuleException(
                    "Cannot delete manufacturer '%s': medicines still reference it. "
                            + "Reassign those medicines first.", manufacturer.getName());
        }
        manufacturers.delete(manufacturer);
    }

    private Manufacturer require(UUID id) {
        return manufacturers.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Manufacturer", id));
    }

    /**
     * @return {@code null} for null/blank, trimmed otherwise.
     *         <p>Blank is normalised to {@code null} rather than stored as
     *         {@code ""}: an empty licence number and an absent one mean the
     *         same thing, and a {@code ""} makes {@code = ''} vs {@code IS NULL}
     *         a distinction every future query has to remember.
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
