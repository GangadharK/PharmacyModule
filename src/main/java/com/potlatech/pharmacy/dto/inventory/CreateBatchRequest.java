package com.potlatech.pharmacy.dto.inventory;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.potlatech.pharmacy.entity.InventoryBatch;

/**
 * Create-batch request — one goods-receipt line.
 *
 * <p><strong>{@code quantityReceived} is required and must be positive.</strong>
 * This is the one place the quantity and the ledger row are created together, and
 * the two are written in a single transaction, so {@code quantity_available} can
 * never disagree with {@code SUM(quantity_changed)}. There is no "create the batch
 * shell now, count the stock later" path: a zero-quantity batch is a catalogue of
 * paperwork, and the sweep would eventually expire it while it sat in a shelf
 * location implying stock was there. If a delivery is genuinely short, receipt the
 * real count.
 *
 * <p><strong>{@code sellingPrice} is required, not defaulted from the catalogue.</strong>
 * A price is a property of the batch, because a price change between two deliveries
 * of the same medicine means the older batch keeps its own price. Defaulting to the
 * medicine's current price would make the second delivery silently reprice the
 * first.
 *
 * <p><strong>{@code costPrice} is accepted but never echoed to a CASHIER.</strong>
 * See {@link InventoryBatchResponse}. It is validated here for the same reason it
 * is redacted there: a cost that arrives as a string, or as {@code -1}, is a cost
 * the reports cannot use.
 *
 * <p><strong>No {@code status} field.</strong> A new batch is {@code ACTIVE} or
 * nothing. Accepting {@code EXPIRED} at creation would let a caller fabricate an
 * expiry event, and {@code DEPLETED} at creation is a contradiction the CHECK
 * constraints would not catch.
 *
 * <p><strong>No {@code quantityAvailable} field.</strong> It is set to
 * {@code quantityReceived}. Making it settable would allow a batch whose balance
 * disagrees with its own first ledger row, which is the exact corruption
 * reconciliation exists to detect.
 */
public record CreateBatchRequest(
        @NotNull(message = "medicineId is required")
        UUID medicineId,

        UUID itemId,

        UUID supplierId,

        @NotBlank(message = "batchNumber is required")
        @Size(max = 100, message = "batchNumber must be at most 100 characters")
        String batchNumber,

        LocalDate manufacturingDate,

        @NotNull(message = "expiryDate is required")
        LocalDate expiryDate,

        @NotNull(message = "quantityReceived is required")
        @Min(value = 1, message = "quantityReceived must be at least 1")
        Integer quantityReceived,

        @NotNull(message = "costPrice is required")
        @DecimalMin(value = "0.00", message = "costPrice must not be negative")
        BigDecimal costPrice,

        @NotNull(message = "sellingPrice is required")
        @DecimalMin(value = "0.00", message = "sellingPrice must not be negative")
        BigDecimal sellingPrice,

        InventoryBatch.Location location,

        String note) {

    /**
     * @return {@code location}, defaulting to the column default of
     *         {@code MAIN_PHARMACY}. The default is the main store rather than the
     *         counter because a delivery goes to the store and is transferred to a
     *         counter deliberately; defaulting to {@code OPD_COUNTER} would
     *         scatter stock the moment someone forgot the field.
     */
    public InventoryBatch.Location resolvedLocation() {
        return location == null ? InventoryBatch.Location.MAIN_PHARMACY : location;
    }

    public String normalisedBatchNumber() {
        return batchNumber == null ? null : batchNumber.trim();
    }
}
