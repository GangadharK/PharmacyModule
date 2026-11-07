package com.potlatech.pharmacy.client;

import java.util.List;
import java.util.UUID;

/**
 * The seam onto the Hospital Management System at {@code ../practi}.
 *
 * <p><strong>Status in v1: STUB.</strong> {@code practi} currently exposes only
 * User, Doctor, Appointment and Image controllers. There is no
 * {@code GET /api/v1/appointments/{id}/prescription}. Building against a live
 * client would block v1 on upstream work that is not scheduled.
 *
 * <p>So v1 ships {@link MockHmsClient}, active by default, returning fixtures
 * behind this interface. When {@code practi} exposes the endpoint, the real
 * Feign client is activated by {@code app.hms.mode=live} and <strong>no calling
 * code changes</strong>.
 *
 * <p>The shapes below are inferred from FDD §3.4 and are <strong>expected to
 * change</strong> when the real contract lands. They are not a promise about
 * the upstream API - see {@code progress-tracker.md} open question 2.
 *
 * <p>Billing is out of v1 scope ({@code MDD.md} §8), so no v1 unit calls this
 * interface. It exists so the seam is present and the contract is written down
 * before something depends on it.
 */
public interface HmsClient {

    /**
     * Fetch the prescription attached to an appointment.
     *
     * @throws HmsUnavailableException if the HMS cannot be reached or returns
     *         something unusable. Never returns null - an absent prescription is
     *         an empty {@link Prescription} list.
     */
    Prescription fetchPrescription(UUID appointmentId);

    /**
     * @param appointmentId the HMS appointment
     * @param items        one per prescribed medicine
     */
    record Prescription(
            UUID appointmentId,
            UUID patientId,
            String patientName,
            LocalDatePrescribedOn prescribedOn,
            List<PrescriptionItem> items) {
    }

    record LocalDatePrescribedOn(java.time.LocalDate value) {
    }

    /**
     * @param medicineName free-text as written by the prescriber. It will
     *                    usually need resolving against the catalogue - that is
     *                    what {@code MedicineMatcher} in unit 10 is for
     * @param dosage       e.g. "1 tab twice daily"
     * @param durationDays number of days, null if unspecified
     * @param quantity     total quantity prescribed, null if unspecified
     */
    record PrescriptionItem(
            String medicineName,
            String dosage,
            Integer durationDays,
            Integer quantity) {
    }

    /** Thrown when the HMS is unreachable or returns an unusable payload. */
    class HmsUnavailableException extends RuntimeException {
        public HmsUnavailableException(String message) {
            super(message);
        }

        public HmsUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
