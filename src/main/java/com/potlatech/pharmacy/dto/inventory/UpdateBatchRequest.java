package com.potlatech.pharmacy.dto.inventory;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.potlatech.pharmacy.entity.InventoryBatch;

/**
 * Update-batch request.
 *
 * <p><strong>Full replacement, not a patch.</strong> Every mutable field is
 * required. A {@code PATCH} here would leave the caller unable to distinguish
 * "clear the supplier" from "leave the supplier alone", and the first one silently
 * loses the link to the purchase order that explains why the batch exists. PUT with
 * mandatory fields is the honest shape for a resource this small.
 *
 * <p><strong>No {@code quantityAvailable}.</strong> The balance moves only through
 * an adjustment that writes a ledger row. Letting a PUT set it would be a second,
 * unlogged door into the one number that must never change quietly.
 *
 * <p><strong>No {@code quantityReceived}.</strong> It is cumulative history. A
 * second delivery against the same batch adds a ledger row; it does not rewrite the
 * first.
 *
 * <p><strong>No {@code status}.</strong> Status changes are state transitions with
 * rules — quarantine, release, expire — each with its own endpoint and its own
 * ledger or audit consequence. A generic PUT would bypass those rules.
 *
 * <p><strong>{@code expiryDate} is editable, and that is deliberate.</strong> A
 * transcription error on a label is common and fixing it in place is legitimate;
 * the change is visible in {@code updatedAt} and in {@code stock_transaction} for
 * any subsequent write-off. Deleting and re-receiving the batch to correct a date
 * would inflate {@code quantityReceived} and corrupt shrinkage reporting.
 */
public record UpdateBatchRequest(
        /** Nullable, and unambiguously so: a PUT that omits it clears the link. */
        UUID supplierId,

        @NotBlank(message = "batchNumber is required")
        @Size(max = 100, message = "batchNumber must be at most 100 characters")
        String batchNumber,

        LocalDate manufacturingDate,

        @NotNull(message = "expiryDate is required")
        LocalDate expiryDate,

        @NotNull(message = "costPrice is required")
        @DecimalMin(value = "0.00", message = "costPrice must not be negative")
        BigDecimal costPrice,

        @NotNull(message = "sellingPrice is required")
        @DecimalMin(value = "0.00", message = "sellingPrice must not be negative")
        BigDecimal sellingPrice,

        @NotNull(message = "location is required")
        InventoryBatch.Location location) {

    public String normalisedBatchNumber() {
        return batchNumber == null ? null : batchNumber.trim();
    }
}
