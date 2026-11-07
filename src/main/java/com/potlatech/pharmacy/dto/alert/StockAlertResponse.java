package com.potlatech.pharmacy.dto.alert;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * One low-stock line: a medicine (or non-medical) whose total sellable stock has
 * fallen to or below its reorder level.
 *
 * <p><strong>The figure is a sum across batches</strong>, deliberately. A medicine
 * in three batches of four, reorder level 10, holds 12 and is not low; testing
 * batches individually flags all three. {@code reorderLevel} is a claim about the
 * item, so the comparison has to be about the item.
 *
 * <p>No {@code costPrice}. Nothing on an alert screen is a buying decision yet,
 * and this response is reachable by every role that can read stock.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StockAlertResponse(
        UUID id,
        String itemName,
        String itemType,
        String form,
        String strength,
        Integer totalAvailable,
        Integer reorderLevel,
        Integer shortfall,
        Integer activeBatchCount,
        Severity severity) {

    public enum Severity {
        /** Nothing sellable. Order today, not this week. */
        OUT_OF_STOCK,
        /** At or under a quarter of the reorder level. */
        CRITICAL,
        /** At or under the reorder level, with stock left. */
        LOW
    }

    public static StockAlertResponse of(UUID id,
                                        String itemName,
                                        String itemType,
                                        String form,
                                        String strength,
                                        int totalAvailable,
                                        int reorderLevel,
                                        int activeBatchCount) {
        return new StockAlertResponse(
                id, itemName, itemType, form, strength,
                totalAvailable, reorderLevel,
                Math.max(0, reorderLevel - totalAvailable),
                activeBatchCount,
                severityOf(totalAvailable, reorderLevel));
    }

    /**
     * CRITICAL at a quarter of the reorder level, not at zero.
     *
     * <p>The threshold is a judgement, not a derivation, and pretending otherwise
     * would be dishonest about it. Its job is to stop a plain LOW from meaning
     * "normal" — if a stock-out and a barely-stocked line share a label, the
     * urgency that distinguishes them is lost, and the list gets worked in
     * alphabetical order by someone who cannot tell which lines matter.
     */
    private static Severity severityOf(int total, int reorderLevel) {
        if (total <= 0) {
            return Severity.OUT_OF_STOCK;
        }
        if (reorderLevel > 0 && total * 4 <= reorderLevel) {
            return Severity.CRITICAL;
        }
        return Severity.LOW;
    }
}
