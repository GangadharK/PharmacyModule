package com.potlatech.pharmacy.dto.inventory;

import java.math.BigDecimal;
import java.util.List;

import com.potlatech.pharmacy.service.FefoAllocation;

/**
 * What FEFO decided: the allocations, and what it could not supply.
 *
 * <p>Deliberately has no {@code medicineId}, {@code location} or cost. The
 * request already said what was asked for, and this is the answer. The absence of
 * any price-at-cost field is the same guarantee §4.3 makes everywhere else, and
 * it is structural here — there is no cost to redact, because the allocator never
 * received one.
 *
 * <p>{@code shortfall} is a field rather than an error code because a preview
 * legitimately reports one. "You asked for 20, 12 are available" is a useful
 * answer to a screen, not a failure, and a UI that has to reconstruct it from a
 * 400 body is doing string parsing to answer a question the server already
 * answered.
 */
public record FefoPreviewResponse(
        List<FefoAllocation> allocations,
        int totalAllocated,
        int shortfall) {

    public FefoPreviewResponse {
        allocations = List.copyOf(allocations);
    }

    /** Whether the request could be met in full. A preview may legitimately be false. */
    public boolean isComplete() {
        return shortfall == 0;
    }

    /** The value of the goods dispensed, at each batch's own selling price. */
    public BigDecimal totalValue() {
        return allocations.stream()
                .map(FefoAllocation::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
