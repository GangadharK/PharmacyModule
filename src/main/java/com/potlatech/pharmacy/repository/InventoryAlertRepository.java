package com.potlatech.pharmacy.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.potlatech.pharmacy.repository.projection.CycleCountCandidate;
import com.potlatech.pharmacy.repository.projection.ExpiryAlertRow;
import com.potlatech.pharmacy.repository.projection.LowStockRow;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read-only alert queries for Unit 09.
 *
 * <p>Both are hand-written SQL rather than JPQL or derived methods, for one
 * reason: {@code SUM(quantity_available) <= reorder_level} is a comparison
 * between an aggregate and another table's column, which means grouping the
 * catalogue and joining a pre-aggregated stock total. Deriving that from a
 * Spring Data method name is not possible, and doing it in Java would mean
 * loading every active batch and every catalogue row into the heap to compare
 * them — a few thousand rows today, and the wrong shape to grow in.
 */
public interface InventoryAlertRepository extends JpaRepository<com.potlatech.pharmacy.entity.InventoryBatch, UUID> {

    /**
     * Items at or below their reorder level, most short first.
     *
     * <p>Medicines and non-medical items are unioned into one list because they
     * are worked from the same screen: a pharmacy orders gloves on the same
     * reorder run as it orders amoxicillin, and splitting them would mean two
     * lists to remember to check. {@code item_type} is the discriminator.
     *
     * <p>Three decisions in this query that are not obvious:
     *
     * <ul>
     *   <li><strong>Summing, not testing batches.</strong> Three batches of four
     *       against a reorder level of 10 holds 12 and must not appear. A
     *       per-batch test flags all three and is the obvious wrong version.
     *   <li><strong>QUARANTINED stock excluded.</strong> It is not sellable, so it
     *       does not reduce the shortfall — a medicine with 4 sellable and 20
     *       quarantined is low, and counting the quarantine would hide that.
     *   <li><strong>{@code reorder_level = 0} excluded.</strong> The check
     *       constraint permits 0, and it has to mean "no threshold configured for
     *       this line" — otherwise it means "alert me when stock reaches zero",
     *       which flags every never-ordered item on day one and trains people to
     *       ignore the screen. A threshold of 0 is not a threshold.
     * </ul>
     *
     * <p>A medicine with no batches at all still appears, via the LEFT JOIN and
     * {@code COALESCE}, as {@code OUT_OF_STOCK}. No stock <em>is</em> the most
     * urgent low-stock state there is.
     */
    @Query(value = """
            SELECT * FROM (
                SELECT m.id                                   AS id,
                       m.generic_name                        AS item_name,
                       'MEDICINE'::text                      AS item_type,
                       m.dosage_form                         AS form,
                       m.strength                            AS strength,
                       COALESCE(s.total_available, 0)::int   AS total_available,
                       m.reorder_level                       AS reorder_level,
                       COALESCE(s.batch_count, 0)::int       AS active_batch_count
                  FROM medicine_catalogue m
                  LEFT JOIN (
                        SELECT b.medicine_id,
                               SUM(b.quantity_available) AS total_available,
                               COUNT(*)                 AS batch_count
                          FROM inventory_batch b
                         WHERE b.medicine_id IS NOT NULL
                           AND b.item_id IS NULL
                           AND b.status = 'ACTIVE'
                         GROUP BY b.medicine_id
                       ) s ON s.medicine_id = m.id
                 WHERE m.is_active = TRUE
                   AND m.reorder_level > 0

                UNION ALL

                SELECT i.id,
                       i.name,
                       'NON_MEDICAL'::text,
                       NULL,
                       NULL,
                       COALESCE(s.total_available, 0)::int,
                       i.reorder_level,
                       COALESCE(s.batch_count, 0)::int
                  FROM non_medical_item i
                  LEFT JOIN (
                        SELECT b.item_id,
                               SUM(b.quantity_available) AS total_available,
                               COUNT(*)                 AS batch_count
                          FROM inventory_batch b
                         WHERE b.item_id IS NOT NULL
                           AND b.medicine_id IS NULL
                           AND b.status = 'ACTIVE'
                         GROUP BY b.item_id
                       ) s ON s.item_id = i.id
                 WHERE i.is_active = TRUE
                   AND i.reorder_level > 0
            ) t
            WHERE t.total_available <= t.reorder_level
            ORDER BY (t.reorder_level - t.total_available) DESC, lower(t.item_name)
            """, nativeQuery = true)
    List<LowStockRow> findLowStock();

    /**
     * Batches at or near expiry, soonest first.
     *
     * <p>{@code EXPIRED} is excluded: those batches are already written off, and
     * reporting them as "expiring soon" would put rows on an urgent screen about
     * stock that no longer exists. {@code DEPLETED} goes with them for the same
     * reason — nothing left to destroy.
     *
     * <p>{@code QUARANTINED} is <em>included</em>, and the asymmetry is
     * deliberate. A quarantined batch still has to be physically destroyed on its
     * date; it is not sellable, but it is not gone either, and dropping it would
     * hide a pile of stock that someone has to deal with.
     *
     * <p>Named rather than positional parameter binding, so the cutoff date and
     * the list of statuses cannot be silently swapped.
     *
     * <p>Filtering is by <em>status</em>, not by date, and a batch whose expiry date
     * has passed while it is still {@code ACTIVE} is therefore listed, with a
     * negative {@code days_remaining}. That is deliberate: it is still on the shelf
     * and still needs writing off, and the sweep that marks it {@code EXPIRED} runs
     * on a schedule. Hiding it would bury a compliance problem on exactly the
     * screen meant to surface one.
     */
    @Query(value = """
            SELECT b.id                AS batch_id,
                   b.batch_number,
                   b.medicine_id,
                   COALESCE(m.generic_name, '(non-medicine)') AS medicine_name,
                   b.quantity_available,
                   b.expiry_date,
                   (b.expiry_date - CAST(:today AS date))::int AS days_remaining,
                   b.status
              FROM inventory_batch b
              LEFT JOIN medicine_catalogue m ON m.id = b.medicine_id
             WHERE b.status IN ('ACTIVE', 'QUARANTINED')
               AND b.expiry_date IS NOT NULL
               AND b.expiry_date <= (CAST(:today AS date) + (:withinDays || ' days')::interval)
             ORDER BY b.expiry_date ASC, b.batch_number ASC
            """, nativeQuery = true)
    List<ExpiryAlertRow> findExpiringSoon(@Param("today") LocalDate today,
                                               @Param("withinDays") int withinDays);

    /**
     * Per-batch snapshot for a new count session, at one location.
     *
     * <p>Returns the balance now so {@code cycle_count_line.system_quantity} can
     * record what the books claimed at the moment counting started.
     *
     * <p>Same status set as the expiry query and for the same reason: a count
     * covers sellable stock and stock that must be disposed of, and not stock
     * that is already gone.
     */
    @Query(value = """
            SELECT b.id                AS id,
                   b.batch_number,
                   b.medicine_id,
                   COALESCE(m.generic_name, '(non-medicine)') AS medicine_name,
                   b.quantity_available,
                   b.expiry_date,
                   b.status,
                   b.location
              FROM inventory_batch b
              LEFT JOIN medicine_catalogue m ON m.id = b.medicine_id
             WHERE b.status IN ('ACTIVE', 'QUARANTINED')
               AND b.location = CAST(:location AS varchar)
               AND (CAST(:medicineId AS uuid) IS NULL OR b.medicine_id = CAST(:medicineId AS uuid))
             ORDER BY COALESCE(m.generic_name, ''), b.expiry_date ASC NULLS LAST, b.batch_number
            """, nativeQuery = true)
    List<CycleCountCandidate> findCountCandidates(
            @Param("location") String location,
            @Param("medicineId") UUID medicineId);

    /**
     * The same rows, addressed by batch id.
     *
     * <p>Separate from {@link #findCountCandidates} because the two filters cannot
     * be one nullable parameter: {@code batch_id = ANY(:ids)} against an empty or
     * null array matches nothing at all, and a count of nothing is refused as a
     * business rule, so a caller passing only medicine ids would be told "no
     * countable batches" for the wrong reason.
     */
    @Query(value = """
            SELECT b.id                AS id,
                   b.batch_number,
                   b.medicine_id,
                   COALESCE(m.generic_name, '(non-medicine)') AS medicine_name,
                   b.quantity_available,
                   b.expiry_date,
                   b.status,
                   b.location
              FROM inventory_batch b
              LEFT JOIN medicine_catalogue m ON m.id = b.medicine_id
             WHERE b.status IN ('ACTIVE', 'QUARANTINED')
               AND b.location = CAST(:location AS varchar)
               AND b.id IN (SELECT unnest(CAST(:batchIds AS uuid[])))
             ORDER BY COALESCE(m.generic_name, ''), b.expiry_date ASC NULLS LAST, b.batch_number
            """, nativeQuery = true)
    List<CycleCountCandidate> findCountCandidatesByBatchIds(
            @Param("location") String location,
            @Param("batchIds") UUID[] batchIds);
}
