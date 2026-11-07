package com.potlatech.pharmacy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A supplier the pharmacy buys from.
 *
 * <p>Schema: {@code MDD.md} §3.4. Unit 03.
 *
 * <p><strong>Name is unique case-insensitively</strong>
 * ({@code uk_supplier_name}, a {@code lower(name)} functional index from
 * {@code V4}). One counterparty is one row: a purchase order and its
 * {@code inventory_batch} both reference the supplier id, so two rows for the
 * same company split one counterparty's purchase history in half, and a
 * "purchases from this supplier" report silently omits half of it.
 *
 * <p><strong>Deleting a supplier is guarded, not free.</strong> Purchase orders
 * and batches reference it, and those are financial records. The service
 * refuses a delete while any reference exists and prefers
 * {@code isActive = false} — a supplier is dropped from the dropdown but keeps
 * its history. The FKs in {@code V5}/{@code V6} therefore do not cascade.
 *
 * <p><strong>{@code gstNumber} and {@code drugLicenseNo} are stored as
 * strings, not numbers or a dedicated type.</strong> A GSTIN is alphanumeric
 * and 15 characters with significant leading zeros; a {@code Long} would
 * discard them. Validation of the checksum belongs in the request DTO, not in
 * a column type.
 *
 * <p>Never returned from a controller — {@code AGENTS.md} rule 5.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "supplier")
public class Supplier {

    @Id
    private UUID id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "drug_license_no", length = 100)
    private String drugLicenseNo;

    @Column(name = "contact_person", length = 255)
    private String contactPerson;


    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "address", length = 500)
    private String address;

    @Column(name = "gst_number", length = 30)
    private String gstNumber;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
