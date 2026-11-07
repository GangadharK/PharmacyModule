package com.potlatech.pharmacy.service;

import java.util.List;

/**
 * What FEFO decided, including what it could not do.
 *
 * <p><strong>The shortfall is returned, not thrown.</strong> The allocator knows
 * how much was short but not what the medicine is called, and
 * {@link com.potlatech.pharmacy.common.exception.InsufficientStockException}
 * needs that name to say "only 12 of 20 available" rather than something
 * generic. So the arithmetic comes back here and the service — which knows the
 * name — decides whether a shortfall is an error.
 *
 * <p>That also makes the partial case representable. A caller that wants "give me
 * what you can" can read {@code shortfall} and proceed; one that needs all-or-
 * nothing calls {@link #requireComplete()}. A pure function that threw could
 * only ever express the second, and would have to be rewritten to express the
 * first.
 *
 * @param allocations    at least one entry per batch touched, never a
 *                       zero-quantity entry — see {@link FefoAllocator}.
 * @param totalAllocated the sum of the allocations' quantities. Always
 *                       {@code requestedQuantity - shortfall}, by construction.
 * @param shortfall      what could not be supplied. {@code 0} on a complete
 *                       allocation.
 */
public record AllocationResult(
        List<FefoAllocation> allocations,
        int totalAllocated,
        int shortfall) {

    public AllocationResult {
        allocations = List.copyOf(allocations);
    }

    public boolean isComplete() {
        return shortfall == 0;
    }

    /**
     * @param medicineName the product's name, for the error message. The
     *                     allocator never had it — it only sees batch numbers —
     *                     which is why the exception is raised here rather than
     *                     inside {@link FefoAllocator}.
     * @return this result, when nothing is short.
     * @throws com.potlatech.pharmacy.common.exception.InsufficientStockException
     *         if anything is short.
     */
    public AllocationResult requireComplete(String medicineName) {
        if (shortfall > 0) {
            // requested is reconstructed rather than stored: the sum invariant
            // guarantees totalAllocated + shortfall == requested, and keeping one
            // source of truth for the number beats storing it twice and hoping.
            throw new com.potlatech.pharmacy.common.exception.InsufficientStockException(
                    medicineName, totalAllocated + shortfall, totalAllocated);
        }
        return this;
    }
}
