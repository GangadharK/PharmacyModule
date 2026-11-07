package com.potlatech.pharmacy.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.potlatech.pharmacy.entity.StockTransaction;

import java.util.List;
import java.util.UUID;

/**
 * Ledger reads.
 *
 * <p>There is deliberately no {@code delete} override, no {@code update} query and
 * no method returning a mutable collection. The append-only property is a schema
 * rule ({@code ON DELETE RESTRICT} on {@code fk_txn_batch}) plus the absence of any
 * API here that could rewrite history.
 */
public interface StockTransactionRepository extends JpaRepository<StockTransaction, UUID> {

    /** A batch's history, oldest first. */
    List<StockTransaction> findByBatchIdOrderByCreatedAtAsc(UUID batchId);

    Page<StockTransaction> findByBatchId(UUID batchId, Pageable pageable);

    /** The ledger's own sum for one batch — the value the balance must equal. */
    @Query("SELECT COALESCE(SUM(t.quantityChanged), 0) FROM StockTransaction t WHERE t.batchId = :batchId")
    long sumForBatch(@Param("batchId") UUID batchId);

    /**
     * Every batch whose balance disagrees with its ledger, as a projection.
     *
     * <p>Native SQL rather than JPQL because the natural form of this check is a
     * {@code LEFT JOIN} against a grouped subquery, and the JPQL equivalent —
     * {@code HAVING SUM(x) <> (SELECT y FROM ...)} with a correlation into the
     * grouped query — is not portable and does not reliably parse. A reconciliation
     * query that only compiles on one dialect is a liability, and this project's
     * one dialect is a choice rather than an accident.
     *
     * <p><strong>The {@code LEFT JOIN} is load-bearing.</strong> A batch with no
     * ledger rows at all is the single most important case to catch: hand-inserted
     * stock, a migration, a rollback. An inner join or a
     * "which sums disagree" query omits them entirely, because there is no group
     * to disagree. {@code COALESCE(t.total, 0)} on both sides means a batch at
     * balance 0 with no ledger is sound, and a batch at balance 5 with no ledger is
     * reported.
     *
     * <p>One round trip, and it runs against the whole table, so a caller cannot
     * accidentally reconcile only the page it happens to be looking at.
     */
    @Query(value = """
            SELECT b.id                AS batchId,
                   b.batch_number      AS batchNumber,
                   b.quantity_available AS balance,
                   COALESCE(t.total, 0) AS ledgerTotal
              FROM inventory_batch b
              LEFT JOIN (SELECT batch_id, SUM(quantity_changed) AS total
                           FROM stock_transaction
                          GROUP BY batch_id) t
                ON t.batch_id = b.id
             WHERE COALESCE(t.total, 0) <> b.quantity_available
            """, nativeQuery = true)
    List<LedgerDisagreement> findDisagreements();

    /**
     * One row of {@link #findDisagreements()}.
     *
     * <p>An interface projection rather than {@code Object[]}, so the column names
     * in the SQL and the accessors here are checked against each other at compile
     * time instead of failing at runtime on a typo.
     */
    interface LedgerDisagreement {
        UUID getBatchId();

        String getBatchNumber();

        int getBalance();

        long getLedgerTotal();
    }
}
