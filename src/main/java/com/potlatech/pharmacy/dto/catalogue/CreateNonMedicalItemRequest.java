package com.potlatech.pharmacy.dto.catalogue;

import com.potlatech.pharmacy.entity.NonMedicalItem;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Create-non-medical-item request.
 *
 * <p>No {@code searchBlob} — see {@link CreateMedicineRequest} for why computed
 * search columns do not belong on a request.
 *
 * <p><strong>{@code reorderLevel} defaults to 10</strong>, matching the column
 * default. It is advisory: it drives a reorder alert, and a stock level
 * momentarily below it is not an error.
 */
public record CreateNonMedicalItemRequest(
        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        @NotNull(message = "category is required")
        NonMedicalItem.Category category,

        @NotNull(message = "unit is required")
        NonMedicalItem.Unit unit,

        @Min(value = 0, message = "reorderLevel must not be negative")
        Integer reorderLevel) {

    public String normalisedName() {
        return name == null ? null : name.trim();
    }

    public int resolvedReorderLevel() {
        return reorderLevel == null ? 10 : reorderLevel;
    }
}
