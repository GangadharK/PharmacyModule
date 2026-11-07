package com.potlatech.pharmacy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row of the stock ledger. Append-only: no code path updates or deletes one.
 *
 * <p><strong>{@link #quantityChanged} is signed.</strong> Positive in, negative
 * out. This is the single most important audit property in the system, and it is
 * what makes reconciliation a single {@code SUM}:
 *
 * <pre>{@code
 * SELECT batch_id, SUM(quantity_changed) FROM stock_transaction
 *  GROUP BY batch_id;   -- must equal inventory_batch.quantity_available
 * }</pre>
 *
 * <p>An unsigned magnitude would force every consumer to re-derive direction from
 * {@link #transactionType}, and — worse — a sign error would be invisible: the
 * absolute value of a wrongly-signed delta still looks like a plausible number.
 *
 * <p><strong>Zero deltas are legal for two types only.</strong> Quarantine and
 * release change state without moving stock. Recording those as rows is what makes
 * "quarantined on the 3rd by whom, released on the 7th by whom" answerable; a
 * ledger that only records quantities answers it with a gap, and a gap is
 * indistinguishable from "it never happened".
 *
 * <p>Zero is rejected for the other eight types by {@code chk_txn_changed}, because
 * a no-op that looks like an audit event makes the trail lie.
 *
 * <p><strong>Expiry is the case that changed.</strong> It was specified as a
 * zero-delta state change, on the reasoning that expired stock is not "leaving"
 * anywhere. Implemented, that breaks the invariant directly: the write-off sets
 * {@code quantity_available} to 0, so the ledger must drop by the remaining balance
 * or {@code SUM(quantity_changed)} stops equalling the balance and
 * {@link #getBatchId()}'s own reconciliation query reports the sweep's own work as
 * corruption. It also leaves expired stock with no trace in the movement history,
 * which makes shrinkage — the number a pharmacy is judged on — uncomputable. It is
 * now {@code -remaining}, like any other write-off.
 *
 * <p>The CHECK still <em>permits</em> zero for {@code EXPIRY_WRITE_OFF}, and that is
 * deliberate: the constraint's job is to stop a no-op masquerading as an audit
 * event for the types where a no-op is meaningless, not to encode one service
 * method's arithmetic. Tightening it would need a V6 migration to enforce a rule
 * every writer already upholds.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "stock_transaction")
public class StockTransaction {

    @Id
    private UUID id;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 30)
    private TransactionType transactionType;

    /** Signed: positive in, negative out. Zero only for the three state-only types. */
    @Column(name = "quantity_changed", nullable = false)
    private Integer quantityChanged;

    /** What this movement is attributed to. Null only when unpaired with {@link #referenceId}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", length = 30)
    private ReferenceType referenceType;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(name = "note", length = 500)
    private String note;

    /**
     * Null means a system action.
     *
     * <p>The only writer permitted to leave this null is the expiry sweep, and it is
     * permitted to because {@code CurrentUserContext} <em>rejects</em> rather than
     * defaults when there is no authenticated caller. A human action with no actor
     * therefore cannot reach this column by accident — it fails earlier.
     */
    @Column(name = "performed_by")
    private UUID performedBy;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum TransactionType {
        /** Stock received. The only type that may create a batch. */
        PURCHASE_IN,
        /** Dispensed or sold. Unit 06 writes these through FEFO. */
        SALE_OUT,
        /** Returned to sellable stock. */
        RETURN_RESTOCK,
        /** Broken, spoiled, or otherwise written off while still in stock. */
        DAMAGE_WRITE_OFF,
        /**
         * Swept as past its expiry date. A negative delta of the remaining
         * balance — see the class javadoc for why this is not zero.
         */
        EXPIRY_WRITE_OFF,
        TRANSFER_OUT,
        TRANSFER_IN,
        /** Unit 09. */
        CYCLE_COUNT_ADJUST,
        /** State only, zero delta. */
        QUARANTINE,
        /** State only, zero delta. */
        RELEASE
    }

    /** What a movement is attributed to, so a purchase order's lines can be traced. */
    public enum ReferenceType {
        PURCHASE_ORDER,
        INVOICE,
        RETURN,
        CYCLE_COUNT
    }
}
