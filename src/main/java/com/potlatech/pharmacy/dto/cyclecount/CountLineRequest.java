package com.potlatech.pharmacy.dto.cyclecount;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * A counted figure for one line.
 *
 * <p>{@code countedQuantity} may legitimately be 0 — "counted, and the shelf is
 * empty" is a real and important result, and it is the one that most needs to be
 * distinguishable from an uncounted line. That is why the field is nullable in the
 * database and only non-null here: the caller is stating a finished count, so 0
 * is meaningful, while a blank input is not sent at all.
 */
public record CountLineRequest(

        @NotNull(message = "countedQuantity is required - omit the line to leave it uncounted")
        @Min(value = 0, message = "countedQuantity cannot be negative")
        Integer countedQuantity) {
}
