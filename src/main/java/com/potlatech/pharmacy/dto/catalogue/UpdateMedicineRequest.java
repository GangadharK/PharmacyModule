package com.potlatech.pharmacy.dto.catalogue;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.UUID;

import com.potlatech.pharmacy.entity.Medicine;

/**
 * Update-medicine request. PATCH semantics: {@code null} means "unchanged".
 *
 * <p><strong>{@code dosageForm} is deliberately not updatable.</strong> A
 * medicine's form is part of what makes it a distinct catalogue row, and
 * batches are stocked against the row. Correcting a mis-keyed form is a delete
 * and re-create while no batch references it, not an edit — silently rewriting
 * it would leave existing batches pointing at a product that now claims a
 * different form, and the FEFO index would group them under the wrong one.
 * The service enforces that with a check on referenced batches.
 *
 * <p><strong>{@code scheduleClass} is updatable</strong>, unlike the form: a
 * regulator reclassifying a product changes its legal category without changing
 * the product, and the H/H1 register must reflect the new class immediately.
 *
 * <p>No {@code searchBlob} — see {@link CreateMedicineRequest}.
 */
public record UpdateMedicineRequest(
        @Size(min = 1, max = 255, message = "genericName must be 1 to 255 characters")
        String genericName,

        @Size(max = 255, message = "brandName must be at most 255 characters")
        String brandName,

        @Size(max = 100, message = "strength must be at most 100 characters")
        String strength,

        CreateMedicineRequest.ScheduleClassRequest scheduleClass,

        Boolean requiresPrescription,

        UUID manufacturerId,

        @Min(value = 0, message = "reorderLevel must not be negative")
        Integer reorderLevel) {

    public String normalisedGenericName() {
        return genericName == null ? null : genericName.trim();
    }
}
