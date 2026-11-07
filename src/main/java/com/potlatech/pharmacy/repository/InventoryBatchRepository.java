package com.potlatech.pharmacy.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.potlatech.pharmacy.entity.InventoryBatch;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Batch queries.
 *
 * <p>{@link JpaSpecificationExecutor} for the list endpoint's optional filters,
 * following the catalogue services: every filter is independently absent, and
 * {@code Specifications} drops the nulls so an unsupplied one contributes no
 * predicate.
 */
public interface InventoryBatchRepository
        extends JpaRepository<InventoryBatch, UUID>, JpaSpecificationExecutor<InventoryBatch> {

    /**
     * FEFO candidate rows, ascending expiry — the order stock leaves in.
     *
     * <p>Backed by the partial index {@code idx_fefo_search}, which contains only
     * {@code status = 'ACTIVE' AND quantity_available > 0} rows, so this is a
     * single index range scan with no residual filtering and no in-memory sort.
     * That is the entire reason the index is partial.
     *
     * <p><strong>Not the locked variant.</strong> Unit 06 adds
     * {@code @Lock(PESSIMISTIC_WRITE)} for the allocation path, which must hold
     * locks for the read and every deduction it drives in one transaction. This
     * method is for read-only screens and stays lock-free.
     */
    List<InventoryBatch> findByMedicineIdAndStatusAndQuantityAvailableGreaterThanOrderByExpiryDateAsc(
            UUID medicineId, InventoryBatch.Status status, int quantity);

    /** The non-medical-item mirror of the FEFO candidate read. */
    List<InventoryBatch> findByItemIdAndStatusAndQuantityAvailableGreaterThanOrderByExpiryDateAsc(
            UUID itemId, InventoryBatch.Status status, int quantity);

    /**
     * Usable batches expiring on or before {@code cutoff}, soonest first.
     *
     * <p>This is the shape the low-stock and expiry-alert screens actually want:
     * the candidate set <em>and</em> the date bound in one statement.
     *
     * <p>Filtering the date in Java after
     * {@link #findByMedicineIdAndStatusAndQuantityAvailableGreaterThanOrderByExpiryDateAsc}
     * would be simpler to write and wrong to ship — it pulls every active batch of
     * the medicine across the database to return the handful inside the window,
     * which for a fast-moving item is most of them. The bound belongs in the
     * {@code WHERE} clause so the partial index does the discarding.
     */
    List<InventoryBatch> findByMedicineIdAndStatusAndQuantityAvailableGreaterThanAndExpiryDateLessThanEqualOrderByExpiryDateAsc(
            UUID medicineId, InventoryBatch.Status status, int quantity, LocalDate cutoff);

    /** Total received of a product across batches — the low-stock denominator. */
    @Query("SELECT COALESCE(SUM(b.quantityAvailable), 0) FROM InventoryBatch b WHERE b.medicineId = :medicineId")
    long sumAvailableForMedicine(@Param("medicineId") UUID medicineId);

    /**
     * Batches past their expiry date that are still marked usable — the sweep's
     * working set.
     *
     * <p>{@code QUARANTINED} is included deliberately: a quarantined batch still
     * expires, and skipping it would leave expired stock sitting in a location a
     * pharmacist can physically reach. {@code DEPLETED} is excluded because there
     * is nothing left to expire.
     *
     * <p>{@code LocalDate}, not {@code LocalDateTime}: {@code expiry_date} is a
     * {@code DATE} column, and a timestamp here would silently exclude everything
     * expiring today.
     */
    List<InventoryBatch> findByExpiryDateLessThanAndStatusIn(
            LocalDate today, List<InventoryBatch.Status> statuses);

    // ==================== FEFO candidate selection (Unit 06) ====================

    /**
     * The FEFO candidate read: exactly the rows {@code architecture-context.md}
     * §5.2 specifies, in exactly the order it specifies.
     *
     * <p><strong>Native, and {@code status = 'ACTIVE'} is a literal. That is
     * load-bearing, not a style choice.</strong> {@code idx_fefo_search} is
     * <em>partial</em>: it contains only rows where
     * {@code status = 'ACTIVE' AND quantity_available > 0}. For the planner to use
     * a partial index it must be able to <em>prove</em> that predicate holds for
     * the rows the query wants. With {@code status} bound as a parameter, it
     * cannot — the value is simply not known at planning time — so once
     * PostgreSQL stops building a custom plan and switches to a generic one, the
     * index becomes unusable.
     *
     * <p>Measured on this database with {@code plan_cache_mode =
     * force_generic_plan}, for the same query:
     *
     * <pre>
     *   status = $3   -&gt; Bitmap Index Scan on idx_near_expiry
     *                    Filter: medicine_id = $1 AND quantity_available &gt; 0
     *
     *   status = 'ACTIVE' -&gt; Index Scan using idx_fefo_search
     *                    Incremental Sort (Presorted Key: expiry_date)
     * </pre>
     *
     * <p>Read the first plan again. It is not a slightly worse plan — it scans
     * every ACTIVE batch in the entire system, by expiry, and discards the ones
     * for other medicines as a residual filter. For the hottest query in the
     * application. All because someone passed a bind parameter where a literal
     * would do.
     *
     * <p>So: do not "tidy" this into a JPQL query with a {@code :status}
     * parameter. The unit test {@code FefoQueryPlanTest} fails if you do.
     *
     * <p>{@code expiry_date &gt;= :today} — greater-or-equal, not
     * {@code &gt; CURRENT_DATE}. Stock expiring <em>today</em> is still dispensable
     * today; the sweep writes it off tomorrow. Using {@code >} would make stock
     * vanish a day early, and would make that behaviour depend on the server's
     * timezone. {@code :today} is passed in from the service's clock for the same
     * reason.
     *
     * <p>{@code id ASC} is the final tie-break. {@code batch_number} is not unique
     * (§3.5 of the MDD), and an order that can call two rows equal is not a total
     * order — {@code sort} is then free to return them in either order, which
     * would make the same request dispense differently on two runs.
     *
     * <p><strong>No {@code LIMIT}.</strong> A cap would bound the scan, but it
     * would also silently under-report the shortfall for a medicine split across
     * more batches than the cap — reporting "only 40 available" when 400 are. A
     * wrong shortfall is worse than a slow query, so the read is bounded only by
     * the index range for that one medicine.
     */
    @Query(value = """
            SELECT * FROM inventory_batch
             WHERE medicine_id = :medicineId
               AND location = :location
               AND status = 'ACTIVE'
               AND quantity_available > 0
               AND expiry_date >= :today
             ORDER BY expiry_date ASC, batch_number ASC, id ASC
            """, nativeQuery = true)
    List<InventoryBatch> findFefoCandidates(@Param("medicineId") UUID medicineId,
                                            @Param("location") String location,
                                            @Param("today") LocalDate today);

    /**
     * The same read, locked for update. See {@link #findFefoCandidates} for the
     * query and for why the status literal matters.
     *
     * <p><strong>{@code FOR UPDATE} serialises concurrent dispenses of the same
     * medicine at the read</strong>, so the two transactions queue here instead of
     * interleaving their reads and both deciding the same batch has enough. SCMS
     * omits this and has a real race.
     *
     * <p>Two honest limits. It locks the rows the scan returns, <em>not</em> the
     * whole range, so it does not stop a concurrent {@code INSERT} of a new
     * earlier-expiring batch — that batch simply is not in the result, and the
     * request is filled from the batches that existed when it started. And it is
     * the reason the preview path is a separate, unlocked method: a preview does
     * no writes, so it has nothing to serialise against, and locking it would
     * make every pharmacist opening the FEFO screen contend with real dispensing.
     *
     * <p><strong>No {@code @Lock} annotation, deliberately.</strong> The obvious
     * way to express this is {@code @Lock(PESSIMISTIC_WRITE)}, and it does not
     * work here: Spring Data rejects it on a native query with
     * {@code "Illegal attempt to set lock mode for a native query"}. The
     * {@code FOR UPDATE} is therefore written into the SQL above, which is where
     * it has to live anyway given the status literal. Same lock, same guarantee —
     * the annotation would have been a second, broken way of asking for it.
     */
    @Query(value = """
            SELECT * FROM inventory_batch
             WHERE medicine_id = :medicineId
               AND location = :location
               AND status = 'ACTIVE'
               AND quantity_available > 0
               AND expiry_date >= :today
             ORDER BY expiry_date ASC, batch_number ASC, id ASC
             FOR UPDATE
            """, nativeQuery = true)
    List<InventoryBatch> findFefoCandidatesForUpdate(@Param("medicineId") UUID medicineId,
                                                     @Param("location") String location,
                                                     @Param("today") LocalDate today);

    /**
     * Moves the balance by a signed delta, and refuses to go negative.
     *
     * <p><strong>This is the concurrency guard.</strong> The post-condition lives in
     * the {@code WHERE} clause rather than in a Java check, so two transactions
     * that both read a balance of 5 and both try to take 5 cannot both succeed: the
     * second matches zero rows and the caller sees 0.
     *
     * <p>A service that reads the balance, compares it in Java, then updates has a
     * race between the compare and the write, and no amount of {@code @Transactional}
     * closes it without a lock on every batch. Folding the post-condition into the
     * statement makes the race unrepresentable.
     *
     * <p>{@code quantityAvailable + :delta >= 0} rather than
     * {@code quantityAvailable >= :n}, so the same statement serves both an
     * increase and a decrease.
     *
     * @return rows changed — {@code 0} when the deduction would have gone negative.
     *         The caller must treat 0 as {@code InsufficientStockException}; it is
     *         not an error to be swallowed.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE InventoryBatch b
               SET b.quantityAvailable = b.quantityAvailable + :delta,
                   b.updatedAt = :now
             WHERE b.id = :id
               AND b.quantityAvailable + :delta >= 0
            """)
    int applyDelta(@Param("id") UUID id,
                   @Param("delta") int delta,
                   @Param("now") LocalDateTime now);
}
