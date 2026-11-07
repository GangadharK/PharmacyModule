package com.potlatech.pharmacy.dto.cyclecount;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One batch within a count: the snapshot, the count, and the gap.
 *
 * <p>{@code liveQuantity} is the batch's balance <em>now</em>, and it is not the
 * same as {@code systemQuantity} when a sale has happened since the session
 * opened. Surfacing both is deliberate: a variance computed from a stale snapshot
 * looks inexplicable to a counter unless they can see that the stock moved in
 * between, and hiding the live figure would leave them with a number they cannot
 * reconcile.
 *
 * <p>{@code variance} is null while uncounted, and a number afterwards —
 * computed live rather than reading the stored column, which is only populated
 * at post. {@code postedVariance} is the archived value, so a reviewer can see
 * what was actually applied even if the batch has moved since.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CycleCountLineResponse(
        String id,
        String batchId,
        String referenceNo,
        String batchNumber,
        String medicineId,
        String medicineName,
        String medicineForm,
        String batchStatus,
        Integer systemQuantity,
        Integer countedQuantity,
        Integer liveQuantity,
        Integer variance,
        Integer postedVariance) {
}
