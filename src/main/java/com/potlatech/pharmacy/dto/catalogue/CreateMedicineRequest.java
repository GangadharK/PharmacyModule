package com.potlatech.pharmacy.dto.catalogue;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

import com.potlatech.pharmacy.entity.Medicine;

/**
 * Create-medicine request.
 *
 * <p><strong>No {@code searchBlob} field.</strong> It is derived from the names
 * below by the service and persisted, so accepting it here would let a client
 * pin a row's search text to something its own label does not contain — the row
 * would then be unfindable by any term printed on the pack. Same reason there is
 * no {@code soundexCode}: both are computed, and computed columns do not belong
 * on a request.
 *
 * <p><strong>{@code requiresPrescription} is not inferred from
 * {@code scheduleClass}.</strong> H and H1 are both prescription medicines, but
 * so are several schedule-class-exempt items, and the converse is not true. A
 * pharmacist must be able to record that a specific formulation needs a script
 * regardless of its class.
 *
 * @param strength free text as printed, e.g. {@code "500 mg"}. Not a number:
 *                 {@code "500 mg/5 ml"} is a real strength and {@code 0.5} would
 *                 lose the units that make it meaningful.
 */
public record CreateMedicineRequest(
        @NotBlank(message = "genericName is required")
        @Size(max = 255, message = "genericName must be at most 255 characters")
        String genericName,

        @Size(max = 255, message = "brandName must be at most 255 characters")
        String brandName,

        @Size(max = 100, message = "strength must be at most 100 characters")
        String strength,

        @NotNull(message = "dosageForm is required")
        Medicine.DosageForm dosageForm,

        ScheduleClassRequest scheduleClass,

        Boolean requiresPrescription,

        UUID manufacturerId,

        @Min(value = 0, message = "reorderLevel must not be negative")
        Integer reorderLevel) {

    /**
     * Schedule class, restricted to the three values {@code chk_med_schedule}
     * allows.
     *
     * <p>A nested enum rather than reusing {@link Medicine.ScheduleClass}
     * directly, so a future catalogue value cannot be introduced on the entity
     * alone and then arrive in requests the database CHECK would reject.
     * Defaults to {@code H} when the client omits it, matching the column
     * default: omitting a field should not mean "over-the-counter".
     */
    public enum ScheduleClassRequest {
        OTC, H, H1
    }

    public String normalisedGenericName() {
        return genericName == null ? null : genericName.trim();
    }

    /** @return the schedule class, defaulting to {@code H} when omitted. */
    public Medicine.ScheduleClass resolvedScheduleClass() {
        return scheduleClass == null
                ? Medicine.ScheduleClass.H
                : Medicine.ScheduleClass.valueOf(scheduleClass.name());
    }

    /**
     * @return {@code requiresPrescription}, defaulting to {@code false} when
     *         omitted.
     *
     *         <p>The default follows {@code MDD.md} §3.2 and the column default
     *         of {@code FALSE}, and it is deliberately <em>not</em> the safer
     *         {@code true}. Defaulting this to {@code true} would make an
     *         omitted field block a legitimate OTC sale at dispense time, which
     *         is a worse failure than the reverse; and a field whose default
     *         here disagreed with the column default would mean the entity, the
     *         schema and the API each believed a different thing about the same
     *         medicine. A required field would remove the guess entirely — logged
     *         as an open question in {@code context/progress-tracker.md} rather
     *         than decided here.
     */
    public boolean resolvedRequiresPrescription() {
        return requiresPrescription != null && requiresPrescription;
    }

    /** @return the reorder level, defaulting to the column default of 10. */
    public int resolvedReorderLevel() {
        return reorderLevel == null ? 10 : reorderLevel;
    }
}
