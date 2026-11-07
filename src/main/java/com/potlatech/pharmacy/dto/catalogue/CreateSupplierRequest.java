package com.potlatech.pharmacy.dto.catalogue;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Create-supplier request.
 *
 * @param drugLicenseNo FDD §3.1 regulatory drug licence
 * @param gstNumber     15-character alphanumeric GSTIN, optional
 */
public record CreateSupplierRequest(
        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name must be at most 255 characters")
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

    /**
     * @return the name trimmed, for the uniqueness check and the row itself.
     *         See {@link CreateManufacturerRequest#normalisedName()} for why.
     */
    public String normalisedName() {
        return name == null ? null : name.trim();
    }
}
