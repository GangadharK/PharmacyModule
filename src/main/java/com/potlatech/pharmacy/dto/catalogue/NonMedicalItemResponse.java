package com.potlatech.pharmacy.dto.catalogue;

import java.time.LocalDateTime;
import java.util.UUID;

import com.potlatech.pharmacy.entity.NonMedicalItem;

/**
 * Non-medical-item response.
 *
 * <p>No cost field, for the same reason as {@link MedicineResponse}: cost lives
 * on the batch, under its own role check.
 */
public record NonMedicalItemResponse(
        UUID id,
        String name,
        NonMedicalItem.Category category,
        NonMedicalItem.Unit unit,
        Integer reorderLevel,
        Boolean isActive,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static NonMedicalItemResponse from(NonMedicalItem item) {
        return new NonMedicalItemResponse(
                item.getId(),
                item.getName(),
                item.getCategory(),
                item.getUnit(),
                item.getReorderLevel(),
                item.getIsActive(),
                item.getCreatedAt(),
                item.getUpdatedAt());
    }
}
