package com.potlatech.pharmacy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * One batch within a {@link CycleCount}: what the system said, what the counter
 * said, and the gap.
 *
 * <p>{@link #systemQuantity} is a <strong>snapshot taken when the session was
 * created</strong>, not a read at post time. This is the single most important
 * field in the table, and getting it wrong produces a variance that is
 * confidently wrong.
 *
 * <p>The failure it prevents: a shelf is counted at 12 on Monday. On Tuesday a
 * sale takes 3 through FEFO and the balance is 9. On Wednesday the count is
 * posted. Reading the balance at post time gives {@code 12 - 9 = +3} — a surplus
 * of three for a shelf nobody touched, and a real count quietly discarded. The
 * variance would be attributed to a counter who did their job correctly.
 *
 * <p>So the comparison is anchored to what the books claimed when the count
 * started. The Tuesday sale stays visible in the ledger as a sale, which is
 * correct, and the Wednesday count reports 0 variance, which is also correct.
 *
 * <p>{@link #countedQuantity} is nullable, and that null is load-bearing: it is
 * how "not counted yet" is distinguished from "counted and found zero". A
 * {@code NOT NULL DEFAULT 0} would make every uncounted shelf look like an empty
 * one, and a full count would post a write-off for every batch nobody got to.
 */
@Entity
@Table(name = "cycle_count_line")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CycleCountLine {

    @Id
    private UUID id;

    @Column(name = "cycle_count_id", nullable = false)
    private UUID cycleCountId;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    /** The balance at session creation. Immutable thereafter. */
    @Column(name = "system_quantity", nullable = false)
    private Integer systemQuantity;

    /** Null until a human counts. Zero means "counted, and it is empty". */
    @Column(name = "counted_quantity")
    private Integer countedQuantity;

    /**
     * {@code counted - system}, set at post time only.
     *
     * <p>Null before posting, rather than computed on read, because it is not
     * known before then: the counter may not have entered a figure. A derived
     * column that returned 0 for an uncounted line would let an unfinished count
     * post cleanly.
     */
    @Column(name = "variance")
    private Integer variance;

    public boolean isCounted() {
        return countedQuantity != null;
    }

    public boolean hasVariance() {
        return variance != null && variance != 0;
    }
}
