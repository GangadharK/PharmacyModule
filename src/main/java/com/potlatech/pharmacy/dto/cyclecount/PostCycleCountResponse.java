package com.potlatech.pharmacy.dto.cyclecount;

/**
 * Outcome of posting a count.
 *
 * <p>Reports shortages and surpluses separately rather than a single net figure.
 * A net number is the one number that must not be used here: a session with ten
 * shelves short by 3 and eleven over by 3 nets to zero, and "the count balanced"
 * is exactly the conclusion that would let a systematic miscount through.
 */
public record PostCycleCountResponse(
        String referenceNo,
        Integer batchesCounted,
        /** Lines whose variance moved stock. */
        Integer variancesApplied,
        Integer shortages,
        Integer surpluses,
        /** Signed sum of applied variances. Informational only — see above. */
        Integer netUnitChange) {
}
