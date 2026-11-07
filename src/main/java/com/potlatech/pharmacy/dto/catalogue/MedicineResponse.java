package com.potlatech.pharmacy.dto.catalogue;

import java.time.LocalDateTime;
import java.util.UUID;

import com.potlatech.pharmacy.entity.Medicine;

/**
 * Medicine response.
 *
 * <p><strong>No cost field, by construction.</strong> There is no cost on
 * {@code medicine_catalogue} to leak: cost lives on the batch that was bought at
 * a price, and is served by the inventory endpoint under its own role check
 * ({@code AGENTS.md} rule 7). Keeping the cost off this record means no future
 * column addition can accidentally surface a purchase price to a CASHIER.
 *
 * <p><strong>No {@code searchBlob}, no {@code soundexCode}.</strong> Both are
 * internal search machinery. Returning the blob would tell a client the exact
 * string the similarity index matches against, and the soundex code is an
 * internal tie-breaker with no business meaning.
 */
public record MedicineResponse(
        UUID id,
        String genericName,
        String brandName,
        String strength,
        Medicine.DosageForm dosageForm,
        Medicine.ScheduleClass scheduleClass,
        Boolean requiresPrescription,
        UUID manufacturerId,
        String manufacturerName,
        Integer reorderLevel,
        Boolean isActive,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /**
     * @param manufacturerName resolved display name, or {@code null} when the
     *                         medicine has no manufacturer. Passed in rather
     *         than fetched here so a catalogue page does not issue one query
     *         per row; the service resolves names in a single batch.
     */
    public static MedicineResponse from(Medicine medicine, String manufacturerName) {
        return new MedicineResponse(
                medicine.getId(),
                medicine.getGenericName(),
                medicine.getBrandName(),
                medicine.getStrength(),
                medicine.getDosageForm(),
                medicine.getScheduleClass(),
                medicine.getRequiresPrescription(),
                medicine.getManufacturerId(),
                manufacturerName,
                medicine.getReorderLevel(),
                medicine.getIsActive(),
                medicine.getCreatedAt(),
                medicine.getUpdatedAt());
    }
}
