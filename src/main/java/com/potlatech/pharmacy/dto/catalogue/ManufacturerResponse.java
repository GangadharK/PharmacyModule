package com.potlatech.pharmacy.dto.catalogue;

import java.time.LocalDateTime;
import java.util.UUID;

import com.potlatech.pharmacy.entity.Manufacturer;

/**
 * Manufacturer response.
 *
 * <p>A record with named fields, so adding a column to {@code manufacturer}
 * cannot silently widen the API surface — the compiler forces a decision about
 * whether the new field belongs here. The entity is never returned directly
 * ({@code AGENTS.md} rule 5).
 */
public record ManufacturerResponse(
        UUID id,
        String name,
        String licenseNumber,
        String country,
        LocalDateTime createdAt) {

    public static ManufacturerResponse from(Manufacturer manufacturer) {
        return new ManufacturerResponse(
                manufacturer.getId(),
                manufacturer.getName(),
                manufacturer.getLicenseNumber(),
                manufacturer.getCountry(),
                manufacturer.getCreatedAt());
    }
}
