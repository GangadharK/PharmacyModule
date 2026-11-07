package com.potlatech.pharmacy.dto.catalogue;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Update-supplier request. PATCH semantics: {@code null} means "unchanged".
 *
 * <p>No {@code isActive} field. A supplier is dropped from the purchase-order
 * dropdown by setting {@code isActive = false} through the dedicated
 * deactivate endpoint rather than by patching it here, so that "deactivate this
 * supplier" is one reviewable action rather than an incidental field edit
 * buried in a form that also changes the phone number.
 */
public record UpdateSupplierRequest(
        @Size(min = 1, max = 255, message = "name must be 1 to 255 characters")
        String name,

        @Size(max = 100, message = "drugLicenseNo must be at most 100 characters")
        String drugLicenseNo,

        @Size(max = 255, message = "contactPerson must be at most 255 characters")
        String contactPerson,

        @Size(max = 20, message = "phone must be at most 20 characters")
        String phone,

        @Email(message = "email must be a valid address")
        @Size(max = 255, message = "email must be at most 255 characters")
        String email,

        @Size(max = 500, message = "address must be at most 500 characters")
        String address,

        @Pattern(regexp = "^$|^[0-9A-Z]{15}$",
                message = "gstNumber must be 15 uppercase alphanumeric characters")
        String gstNumber) {

    /** @return the name trimmed, or {@code null} when not supplied. */
    public String normalisedName() {
        return name == null ? null : name.trim();
    }
}
