package com.potlatech.pharmacy.repository.projection;

import java.time.LocalDate;
import java.util.UUID;

import com.potlatech.pharmacy.entity.InventoryBatch;

/**
 * Raw row behind an {@code ExpiryAlertResponse}: the dates, with no state.
 *
 * <p>State is derived in the DTO so that the 30- and 90-day bands live next to
 * each other in one readable method, and so the DTO can be built identically
 * from SQL and from a test fixture.
 */
public interface ExpiryAlertRow {

    UUID getBatchId();

    String getBatchNumber();

    UUID getMedicineId();

    String getMedicineName();

    Integer getQuantityAvailable();

    LocalDate getExpiryDate();

    Integer getDaysRemaining();

    InventoryBatch.Status getStatus();
}
