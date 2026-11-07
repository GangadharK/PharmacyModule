package com.potlatech.pharmacy.dto.catalogue;

import com.potlatech.pharmacy.entity.NonMedicalItem;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Update-non-medical-item request. PATCH semantics: {@code null} is "unchanged".
 *
 * <p>Neither {@code category} nor {@code unit} is updatable. Both are part of
 * what the row <em>is</em> rather than descriptive metadata: a box of gloves
 * counted in {@code BOX} and then reclassified as {@code PIECE} would make every
 * existing stock quantity mean something different, and the reorder alert would
 * fire on a number that was never true. Correcting either is a delete and
 * re-create while no batch references the item.
 */
public record UpdateNonMedicalItemRequest(
        @Size(min = 1, max = 255, message = "name must be 1 to 255 characters")
        String name,

        @Min(value = 0, message = "reorderLevel must not be negative")
        Integer reorderLevel) {

    public String normalisedName() {
        return name == null ? null : name.trim();
    }
}
