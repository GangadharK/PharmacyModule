package com.potlatech.pharmacy.dto.inventory;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.UUID;

import com.potlatech.pharmacy.entity.InventoryBatch;
import com.potlatech.pharmacy.entity.StockTransaction;

/**
 * A FEFO request: this much of this medicine, from this location.
 *
 * <p>Shared by {@code fefo-preview} and {@code dispense}. They differ only in
 * whether they write, and a preview that took a different shape from the thing
 * it previews would eventually stop predicting it.
 *
 * <p>{@code location} is required rather than defaulted. FEFO's correctness
 * depends on the candidate set, and a silent {@code MAIN_PHARMACY} default would
 * let a request for the OPD counter quietly draw from the back shelf — a
 * different physical location, and one that may not even be reachable from the
 * counter. Making the caller state it means the UI has to show a picker, which is
 * the honest representation of a constraint the pharmacist already understands.
 */
public record FefoRequest(

        @NotNull(message = "medicineId is required")
        UUID medicineId,

        @PositiveOrZero(message = "requestedQuantity must not be negative")
        int requestedQuantity,

        @NotNull(message = "location is required")
        InventoryBatch.Location location,

        StockTransaction.ReferenceType referenceType,

        UUID referenceId) {
}
