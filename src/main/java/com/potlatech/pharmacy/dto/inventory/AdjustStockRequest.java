package com.potlatech.pharmacy.dto.inventory;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

import com.potlatech.pharmacy.entity.StockTransaction;

/**
 * Manual stock adjustment — a cycle count, a break, a correction.
 *
 * <p><strong>{@code quantityChange} is signed and must not be zero.</strong>
 * Positive for a found surplus, negative for a break or a correction. The sign is
 * the caller's statement of which way stock moved, and it is what gets written to
 * {@code stock_transaction.quantity_changed}.
 *
 * <p>Zero is rejected rather than accepted and ignored. A zero adjustment is either
 * a mistake or a duplicate submission, and either way it is not an event: writing a
 * row for it would put a line in the audit trail that says nothing happened, which
 * makes the trail harder to trust for every reader who has to check it. The
 * database enforces this independently in {@code chk_txn_changed}, so a
 * hand-written SQL insert cannot slip a no-op in either.
 *
 * <p>Absence is handled by the HTTP layer, not here: this endpoint is
 * {@code POST}, so an empty body is a malformed request rather than a patch that
 * changes nothing. An adjustment that does nothing is a bug report, not a
 * no-op to be tolerated.
 *
 * <p>{@code referenceType} / {@code referenceId} attribute the change to a cycle
 * count or a return. They are validated as a pair by {@code chk_txn_reference}.
 */
public record AdjustStockRequest(
        @NotNull(message = "quantityChange is required")
        @Min(value = -1000000, message = "quantityChange is out of range")
        Integer quantityChange,

        @Size(max = 500, message = "note must be at most 500 characters")
        String note,

        StockTransaction.ReferenceType referenceType,

        UUID referenceId) {

    /**
     * @return the signed delta. Never zero — see the class javadoc and the
     *         service's explicit rejection.
     */
    public int signedDelta() {
        return quantityChange == null ? 0 : quantityChange;
    }
}
