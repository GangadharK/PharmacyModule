package com.potlatech.pharmacy.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Fixture implementation of {@link HmsClient}, active by default.
 *
 * <p>Returns deterministic data derived from the requested id so a screen can be
 * developed against it without an HMS running, and so a demo produces the same
 * result every time.
 *
 * <p>Names are drawn from the seed catalogue in unit 03 so
 * {@code MedicineMatcher} has something it can actually resolve. Names that
 * match nothing are included on purpose - an unresolvable line is a real case
 * the review screen has to handle.
 */
public class MockHmsClient implements HmsClient {

    private static final Logger log = LoggerFactory.getLogger(MockHmsClient.class);

    private static final List<String> CATALOGUE_NAMES = List.of(
            "Azithromycin 500mg",
            "Amoxicillin 500mg",
            "Metformin 500mg",
            "Cetirizine 10mg",
            "Omeprazole 20mg",
            "Panadol 500mg",
            "Cough Syrup 100ml",
            "Vitamin D3 60k");

    @Override
    public Prescription fetchPrescription(UUID appointmentId) {
        if (appointmentId == null) {
            throw new HmsUnavailableException("appointmentId is required");
        }

        // Deterministic selection so repeated calls agree. hashCode on a UUID is
        // stable within and across JVMs for a given value.
        int seed = Math.abs(appointmentId.hashCode());
        int itemCount = 1 + (seed % 3);

        List<PrescriptionItem> items = new java.util.ArrayList<>(itemCount);
        for (int i = 0; i < itemCount; i++) {
            String name = CATALOGUE_NAMES.get((seed + i) % CATALOGUE_NAMES.size());
            items.add(new PrescriptionItem(
                    name,
                    "1 tablet twice daily",
                    5 + (seed + i) % 10,
                    10 + (seed + i) % 20));
        }

        // One deliberately unresolvable name, so the review path for a low or
        // zero-confidence match is exercised rather than theoretical.
        if (seed % 4 == 0) {
            items.add(new PrescriptionItem("Unlisted Compound X 5mg", "1 tablet at night", 7, 10));
        }

        log.debug("MockHmsClient returning {} item(s) for appointment {}",
                items.size(), appointmentId);

        return new Prescription(
                appointmentId,
                UUID.nameUUIDFromBytes(("patient-" + appointmentId).getBytes()),
                "Mock Patient",
                new LocalDatePrescribedOn(LocalDate.now().minusDays(1)),
                List.copyOf(items));
    }
}
