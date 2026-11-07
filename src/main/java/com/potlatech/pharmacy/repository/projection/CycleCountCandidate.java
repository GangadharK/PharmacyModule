package com.potlatech.pharmacy.repository.projection;

import java.time.LocalDate;
import java.util.UUID;

import com.potlatech.pharmacy.entity.InventoryBatch;

/**
 * A batch eligible to be counted, with the balance it has <em>right now</em>.
 *
 * <p>The quantity here is read once, at session creation, and written to
 * {@code cycle_count_line.system_quantity}. From that moment it is a historical
 * fact about what the books claimed when counting started — which is the only
 * thing a count can fairly be measured against. See
 * {@link CycleCountLine} for the sale-during-a-count case this prevents.
 */
public interface CycleCountCandidate {

    UUID getId();

    String getBatchNumber();

    UUID getMedicineId();

    String getMedicineName();

    Integer getQuantityAvailable();

    LocalDate getExpiryDate();

    InventoryBatch.Status getStatus();

    InventoryBatch.Location getLocation();
}
