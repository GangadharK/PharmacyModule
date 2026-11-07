package com.potlatech.pharmacy.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.potlatech.pharmacy.common.exception.BusinessRuleException;

/**
 * FEFO: decide which batches a request comes out of.
 *
 * <p><strong>Pure.</strong> No Spring annotation, no repository, no clock, no
 * writes. Constructed with {@code new} in its test and nowhere else. It takes
 * {@code today} as a parameter for the same reason — a function that reads the
 * clock is not a function you can test without waiting for midnight, and the
 * {@code expiryDate >= today} boundary is precisely the behaviour most worth
 * pinning down.
 *
 * <p>It also re-sorts its input rather than trusting the caller to have sorted
 * it. The query already returns candidates in FEFO order, so this is redundant
 * on the happy path — and redundant until the day someone calls it with a list
 * built by {@code HashSet.stream().toList()}. The allocator is the one place
 * where "expired stock first" would be a silent, invisible correctness bug, so
 * it owns its own ordering.
 *
 * <p>It does <strong>not</strong> filter on {@code status}. Expired and
 * quarantined batches are excluded by the candidate query
 * ({@code architecture-context.md} §5.2), not here, so the pure function cannot
 * be the thing that decides whether a quarantined batch is dispensable. The one
 * expiry check it does make is the date boundary, which is defence in depth for
 * a caller that assembled candidates by hand.
 */
public final class FefoAllocator {

    /**
     * Splits {@code requestedQuantity} across {@code candidates}, earliest
     * expiry first.
     *
     * @param candidates      candidate batches. Re-sorted defensively; order in
     *                        the argument is not trusted.
     * @param requestedQuantity how much is needed. {@code 0} yields an empty
     *                        result and no error — the FEFO preview screen's
     *                        initial state is 0, and that should not be an error
     *                        state. Negative is refused.
     * @param today           the current date, for the expiry boundary. A batch
     *                        expiring <em>today</em> is still dispensable today.
     * @return the allocations plus any shortfall. Never {@code null}.
     * @throws BusinessRuleException if {@code requestedQuantity} is negative.
     */
    public AllocationResult allocate(List<StockCandidate> candidates,
                                     int requestedQuantity,
                                     LocalDate today) {
        if (requestedQuantity < 0) {
            throw new BusinessRuleException(
                    "requestedQuantity must not be negative: %d", requestedQuantity);
        }
        if (requestedQuantity == 0) {
            // Empty in, empty out. Still worth a totalAllocated of 0 and a
            // shortfall of 0 so the sum invariant holds without a special case
            // in every caller.
            return new AllocationResult(List.of(), 0, 0);
        }

        List<StockCandidate> ordered = new ArrayList<>(candidates);
        ordered.sort(StockCandidate.FEFO_ORDER);

        List<FefoAllocation> allocations = new ArrayList<>();
        int remaining = requestedQuantity;
        int allocated = 0;

        for (StockCandidate candidate : ordered) {
            if (remaining <= 0) {
                break;
            }
            // Defence in depth. The candidate query already excludes these, but
            // an expired batch reaching the allocator means the filter was
            // bypassed, and dispensing expired stock is the one outcome this
            // whole unit exists to prevent.
            if (isNotUsable(candidate, today)) {
                continue;
            }
            if (candidate.available() <= 0) {
                continue;
            }

            int take = Math.min(candidate.available(), remaining);
            // `take` is at least 1 here: available > 0 and remaining > 0 both
            // hold. That is what guarantees no zero-quantity allocation is ever
            // emitted, which is the boundary case a naive loop gets wrong — it
            // would append a 0 for the batch the request landed exactly on the
            // end of, and write a ledger row for a movement of nothing.
            allocations.add(new FefoAllocation(
                    candidate.id(),
                    candidate.batchNumber(),
                    candidate.expiryDate(),
                    take,
                    candidate.sellingPrice()));

            remaining -= take;
            allocated += take;
        }

        return new AllocationResult(allocations, allocated, remaining);
    }

    /**
     * Stock is unusable if it has passed its date, or has no balance to give.
     *
     * <p>The date comparison is {@code expiryDate.isBefore(today)} — so a batch
     * dated today survives. Using "on or after" here would expire a day's stock
     * a day early, and the error would be invisible until someone counted the
     * shelf.
     */
    private boolean isNotUsable(StockCandidate candidate, LocalDate today) {
        return candidate.expiryDate() == null
                || candidate.expiryDate().isBefore(today)
                || candidate.available() <= 0;
    }
}
