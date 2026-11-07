package com.potlatech.pharmacy.dto.catalogue;

import jakarta.validation.constraints.Size;

/**
 * Update-manufacturer request. Every field optional; {@code null} means
 * "leave as it is".
 *
 * <p>Distinguishing "absent" from "set to null" is why this is a PATCH and not a
 * PUT. A pharmacist correcting a country has no reason to restate the licence
 * number, and a PUT that required the full object would silently blank any field
 * the client forgot to send.
 *
 * <p>There is no {@code isActive} here, unlike {@link com.potlatech.pharmacy.dto.user.UpdateUserRequest}.
 * A manufacturer cannot be switched off: it has no stock of its own, so
 * deactivating it would only mean hiding live batches from FEFO. It is deleted
 * instead, and the delete is refused while a medicine still references it.
 */
public record UpdateManufacturerRequest(
        @Size(min = 1, max = 255, message = "name must be 1 to 255 characters")
        String name,

        @Size(max = 100, message = "licenseNumber must be at most 100 characters")
        String licenseNumber,

        @Size(max = 100, message = "country must be at most 100 characters")
        String country) {

    /** @return the name trimmed, or {@code null} when not supplied. */
    public String normalisedName() {
        return name == null ? null : name.trim();
    }
}
