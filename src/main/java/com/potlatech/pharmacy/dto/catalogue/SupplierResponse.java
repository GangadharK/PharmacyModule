package com.potlatech.pharmacy.dto.catalogue;

import java.time.LocalDateTime;
import java.util.UUID;

import com.potlatech.pharmacy.entity.Supplier;

/**
 * Supplier response.
 *
 * <p>No cost figures and no pricing: a supplier row carries none, and the
 * prices that do exist live on {@code inventory_batch}, which is served by a
 * different endpoint with its own role check ({@code AGENTS.md} rule 7).
 */
public record SupplierResponse(
        UUID id,
        String name,
        String drugLicenseNo,
        String contactPerson,
        String phone,
        String email,
        String address,
        String gstNumber,
        Boolean isActive,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static SupplierResponse from(Supplier supplier) {
        return new SupplierResponse(
                supplier.getId(),
                supplier.getName(),
                supplier.getDrugLicenseNo(),
                supplier.getContactPerson(),
                supplier.getPhone(),
                supplier.getEmail(),
                supplier.getAddress(),
                supplier.getGstNumber(),
                supplier.getIsActive(),
                supplier.getCreatedAt(),
                supplier.getUpdatedAt());
    }
}
