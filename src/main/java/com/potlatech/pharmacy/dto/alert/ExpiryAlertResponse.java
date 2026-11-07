package com.potlatech.pharmacy.dto.alert;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.potlatech.pharmacy.entity.InventoryBatch;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * One batch approaching or past its expiry date.
 *
 * <p>Global, unlike {@code GET /inventory/expiring}, which requires a
 * {@code medicineId} and answers a different question: "when does <em>this</em>
 * medicine run out", which is a dispensing aid. This answers "what is expiring
 * across the pharmacy", which is why it is its own endpoint rather than an
 * optional parameter on the other one.
 *
 * <p>{@code daysRemaining} is negative once past, which is what makes it
 * sortable into the right order without a separate overdue bucket — and an
 * overdue batch is the most urgent line on the screen, so it must not be pushed
 * below "expired" for being a special case.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExpiryAlertResponse(
        UUID batchId,
        String batchNumber,
        UUID medicineId,
        String medicineName,
        Integer quantityAvailable,
        LocalDate expiryDate,
        Integer daysRemaining,
        ExpiryState expiryState,
        InventoryBatch.Status status) {

    public enum ExpiryState {
        /** Past the date. Stock is written off by the expiry sweep. */
        EXPIRED,
        /** 30 days or fewer. Destroy or return. */
        CRITICAL,
        /** 90 days or fewer. Rotate. */
        WARNING,
        OK
    }

    public static ExpiryAlertResponse from(UUID batchId,
                                           String batchNumber,
                                           UUID medicineId,
                                           String medicineName,
                                           int quantityAvailable,
                                           LocalDate expiryDate,
                                           LocalDate today,
                                           InventoryBatch.Status status) {
        long days = ChronoUnit.DAYS.between(today, expiryDate);
        return from(batchId, batchNumber, medicineId, medicineName, quantityAvailable,
                expiryDate, clamp(days), status);
    }

    /**
     * Builds from a day count the query already computed.
     *
     * <p>Preferred over recomputing the difference, because the query sorts on the
     * same expression: if the SQL and the DTO each derived "days remaining"
     * independently they could disagree, and the row order would then contradict
     * the badge on it. One derivation, used by both.
     */
    public static ExpiryAlertResponse from(UUID batchId,
                                           String batchNumber,
                                           UUID medicineId,
                                           String medicineName,
                                           int quantityAvailable,
                                           LocalDate expiryDate,
                                           int daysRemaining,
                                           InventoryBatch.Status status) {
        return new ExpiryAlertResponse(
                batchId, batchNumber, medicineId, medicineName,
                quantityAvailable, expiryDate, daysRemaining,
                stateOf(daysRemaining), status);
    }

    private static int clamp(long days) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, days));
    }

    private static ExpiryState stateOf(int days) {
        if (days < 0) {
            return ExpiryState.EXPIRED;
        }
        if (days <= 30) {
            return ExpiryState.CRITICAL;
        }
        return days <= 90 ? ExpiryState.WARNING : ExpiryState.OK;
    }

    public boolean isOverdue() {
        return expiryState == ExpiryState.EXPIRED;
    }
}
