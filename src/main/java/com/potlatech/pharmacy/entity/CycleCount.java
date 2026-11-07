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
 * A cycle count session: one shelf count, from snapshot to posted.
 *
 * <p>Not a log entry — it is the thing being posted. A variance is a claim that
 * the system was wrong, and a claim needs an author, so the session is the record
 * and {@link CycleCountLine} holds the per-batch evidence. Posting writes the
 * variances to {@link StockTransaction} and points their {@code reference_id}
 * back here, which is what finally gives that column something to reference.
 *
 * <p>Two states, not three. Drafting is expressed by which lines have a
 * {@code counted_quantity} rather than by a state, because a session with all
 * lines counted and a session with none are the same kind of thing: unfinished.
 *
 * <p>{@code countedBy} and {@code postedBy} are separate people on purpose. The
 * person who counted the shelf is not automatically the person who signs off the
 * variance, and in a pharmacy the second signature is the control.
 */
@Entity
@Table(name = "cycle_count")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CycleCount {

    public enum Status {
        /** Accepting counted quantities. The only state that can be written to. */
        OPEN,
        /** Variances written to the ledger. Terminal. */
        POSTED
    }

    @Id
    private UUID id;

    /** Human-facing citation, e.g. {@code CC-2026-000001}. Unique. */
    @Column(name = "reference_no", nullable = false, length = 32)
    private String referenceNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(nullable = false, length = 32)
    private String location;

    @Column(name = "counted_by", nullable = false)
    private UUID countedBy;

    @Column(length = 500)
    private String notes;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    @Column(name = "posted_by")
    private UUID postedBy;

    public boolean isPosted() {
        return status == Status.POSTED;
    }
}
