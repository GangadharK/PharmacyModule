package com.potlatech.pharmacy.dto.catalogue;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create-manufacturer request.
 *
 * @param name          display name, unique case-insensitively
 * @param licenseNumber FDD §3.1 regulatory licence; optional for an
 *                      unregistered local manufacturer
 * @param country       optional
 */
public record CreateManufacturerRequest(
        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        @Size(max = 100, message = "licenseNumber must be at most 100 characters")
        String licenseNumber,

        @Size(max = 100, message = "country must be at most 100 characters")
        String country) {

    /**
     * @return the name trimmed, for the uniqueness check and the row itself.
     *         Trimmed because {@code "Cipla "} and {@code "Cipla"} are the same
     *         company, and a trailing space is invisible in a form field — so it
     *         would sail past the unique index and create the duplicate the
     *         index exists to prevent.
     */
    public String normalisedName() {
        return name == null ? null : name.trim();
    }
}
