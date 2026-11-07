package com.potlatech.pharmacy.dto.cyclecount;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A cycle-count session with its lines.
 *
 * <p>Carries counts as well as the lines, so the list screen can show "3
 * variances" without a second request per row, and so the drawer can open
 * pre-populated. The counts are the point of the record, not a convenience.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CycleCountResponse(
        UUID id,
        String referenceNo,
        String status,
        String location,
        UUID countedBy,
        String notes,
        LocalDateTime createdAt,
        LocalDateTime postedAt,
        UUID postedBy,
        Integer lineCount,
        /** Lines with no counted figure yet. Non-zero blocks posting. */
        Integer uncountedLines,
        /** Counted lines whose figure differs from the snapshot. */
        Integer varianceLines,
        /**
         * Variances that posting will write to the ledger: the same figure as
         * {@code varianceLines} while the session is open, and zero once it is
         * POSTED, because then they have been written.
         *
         * <p>Given to the UI as its own field so the "post N variances" button can
         * be labelled without the client re-deriving the rule — and so a count of
         * lines is never mistaken for a count of adjustments.
         */
        Integer pendingVariances,
        List<CycleCountLineResponse> lines) {
}
