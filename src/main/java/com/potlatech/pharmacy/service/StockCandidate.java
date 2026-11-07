package com.potlatech.pharmacy.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * One batch FEFO is willing to dispense from, reduced to what the arithmetic needs.
 *
 * <p>A record rather than {@code InventoryBatch}. The allocator is a pure
 * function, and "pure" should mean it cannot reach an association, a lazy proxy
 * or a column it has no business knowing about. Five fields is the whole input
 * contract, and it makes the edge-case tests a matter of constructing values
 * instead of configuring a builder.
 *
 * @param available    the balance at the moment the candidates were read. The
 *                     allocator trusts this and does not re-read it — the
 *                     guarded update is what re-checks, under a row lock.
 * @param sellingPrice this batch's price, which travels with the allocation.
 *                     Two batches of the same medicine can carry different
 *                     prices, and the caller has to know which one it dispensed.
 */
public record StockCandidate(
        UUID id,
        String batchNumber,
        LocalDate expiryDate,
        int available,
        BigDecimal sellingPrice) {

    /**
     * The order FEFO dispenses in, and the only place that order is defined.
     *
     * <p>Expiry first — that is the rule. Batch number second, so two batches
     * sharing an expiry date always come out in the same order: without it, the
     * same request could split across the two differently on two runs, which
     * makes the audit trail irreproducible and the tests flaky. Id last, because
     * batch numbers are not unique (see the {@code inventory_batch} comments)
     * and a comparator that can call two rows equal is not a total order — which
     * would let {@code sort} produce an arbitrary order and reintroduce the very
     * nondeterminism the tie-break exists to remove.
     */
    public static final Comparator<StockCandidate> FEFO_ORDER =
            Comparator.comparing(StockCandidate::expiryDate)
                    .thenComparing(StockCandidate::batchNumber)
                    .thenComparing(StockCandidate::id);
}
