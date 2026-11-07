package com.potlatech.pharmacy.dto.inventory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.potlatech.pharmacy.entity.InventoryBatch;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Batch response.
 *
 * <p><strong>{@code costPrice} is absent for a CASHIER.</strong> AGENTS rule 7 asks
 * for absence rather than a null, and {@code NON_NULL} is how a record achieves
 * that: the mapper passes {@code null} and Jackson omits the key entirely. A client
 * cannot tell "redacted" from "never set", which is correct, because there is no
 * difference between them worth protecting.
 *
 * <p>This is the first record in the system that carries a cost. The catalogue
 * responses have no cost field <em>by construction</em> (cost lives on the batch that
 * was bought at a price), so this is where the rule first has teeth.
 *
 * <p><strong>{@code sellingPrice} is never redacted.</strong> A cashier must be
 * able to read a price to quote it. Redacting it would break the job the role
 * exists to do.
 *
 * <p>{@code medicineName} / {@code itemName} / {@code supplierName} are passed in
 * rather than fetched here, so a list page resolves them in one batched query
 * instead of one per row.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InventoryBatchResponse(
        UUID id,
        UUID medicineId,
        String medicineName,
        UUID itemId,
        String itemName,
        UUID supplierId,
        String supplierName,
        String batchNumber,
        LocalDate manufacturingDate,
        LocalDate expiryDate,
        Integer quantityReceived,
        Integer quantityAvailable,
        BigDecimal costPrice,
        BigDecimal sellingPrice,
        InventoryBatch.Location location,
        InventoryBatch.Status status,
        /** Whole days until expiry; negative once past. Null when already expired. */
        Integer daysUntilExpiry,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /**
     * @param costPrice {@code null} redacts the field entirely. The service decides,
     *                  never the client and never the UI.
     * @param today     the clock, injected by the caller so a test can fix it.
     */
    public static InventoryBatchResponse from(InventoryBatch batch,
                                             String medicineName,
                                             String itemName,
                                             String supplierName,
                                             BigDecimal costPrice,
                                             LocalDate today) {
        return new InventoryBatchResponse(
                batch.getId(),
                batch.getMedicineId(),
                medicineName,
                batch.getItemId(),
                itemName,
                batch.getSupplierId(),
                supplierName,
                batch.getBatchNumber(),
                batch.getManufacturingDate(),
                batch.getExpiryDate(),
                batch.getQuantityReceived(),
                batch.getQuantityAvailable(),
                costPrice,
                batch.getSellingPrice(),
                batch.getLocation(),
                batch.getStatus(),
                daysUntilExpiry(batch.getExpiryDate(), today),
                batch.getCreatedAt(),
                batch.getUpdatedAt());
    }

    /**
     * Signed days to expiry, or {@code null} once the date has passed.
     *
     * <p>Null rather than a negative number because the two answer different
     * questions. A negative count invites arithmetic ("-3 means 3 days ago") and
     * the UI is better off colouring a row by the status field it already has.
     */
    private static Integer daysUntilExpiry(LocalDate expiryDate, LocalDate today) {
        if (expiryDate == null || !expiryDate.isAfter(today)) {
            return null;
        }
        return (int) (expiryDate.toEpochDay() - today.toEpochDay());
    }
}
