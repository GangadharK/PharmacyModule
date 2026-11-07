package com.potlatech.pharmacy.dto.inventory;

import java.util.List;
import java.util.UUID;

/**
 * The answer to "does the ledger agree with the balances on every batch?".
 *
 * <p><strong>An empty {@code mismatches} list is the healthy state and is what a
 * correct system returns on essentially every call.</strong> The mismatches are
 * carried anyway rather than reduced to a boolean, because the caller of a failing
 * check is almost always a person who then has to find out <em>which</em> batches
 * disagree, and a bare {@code false} sends them back to the database by hand.
 *
 * <p>{@code checkedBatches} is present so an empty list is distinguishable from a
 * check that never ran. A sweep that silently matched zero batches and a healthy
 * system with no stock yet look identical without it.
 *
 * <p>Exposed as a read-only endpoint: a reconciliation an accountant cannot run is
 * not a reconciliation.
 *
 * @param checkedBatches how many batches were compared.
 * @param mismatches     empty when the ledger is sound.
 */
public record ReconciliationResponse(
        int checkedBatches,
        List<Mismatch> mismatches) {

    /**
     * One batch whose balance and ledger sum disagree.
     *
     * @param expectedFromLedger {@code SUM(quantity_changed)} — what the history says.
     * @param actualBalance      {@code quantity_available} — what the shelf says.
     * @param difference         {@code actualBalance - expectedFromLedger}. Non-zero
     *                           is the whole finding; the two other numbers only
     *                           explain it.
     */
    public record Mismatch(
            UUID batchId,
            String batchNumber,
            long expectedFromLedger,
            int actualBalance,
            long difference) {
    }
}
