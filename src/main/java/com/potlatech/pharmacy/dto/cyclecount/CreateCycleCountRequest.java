package com.potlatech.pharmacy.dto.cyclecount;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

import com.potlatech.pharmacy.entity.InventoryBatch;

/**
 * Opens a count session.
 *
 * <p>{@code batchIds} and {@code medicineIds} select different things, and both
 * are accepted because a pharmacist counts for two different reasons:
 *
 * <ul>
 *   <li>{@code batchIds} — one carton on the shelf. A batch found damaged, or one
 *       lot to re-verify after a recall query.
 *   <li>{@code medicineIds} — every batch of a medicine, which is the usual
 *       "count the paracetamol" request and cannot be expressed as batch ids,
 *       because the client does not know how many batches exist.
 * </ul>
 *
 * <p>Supplying both unions them, deduplicated. Both empty means "everything
 * countable at this location", which is the ordinary case. The distinction
 * matters at post time: a 200-line full count and a 3-line spot check are very
 * different evidence, and conflating them would let a spot check be reported as a
 * full reconciliation.
 */
public record CreateCycleCountRequest(

        @NotNull(message = "location is required")
        InventoryBatch.Location location,

        /** Specific batches. See the class javadoc. */
        List<UUID> batchIds,

        /** Every batch of these medicines. See the class javadoc. */
        List<UUID> medicineIds,

        @Size(max = 500, message = "notes must be at most 500 characters")
        String notes) {
}
