package com.potlatech.pharmacy.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.potlatech.pharmacy.dto.alert.ExpiryAlertResponse;
import com.potlatech.pharmacy.dto.alert.StockAlertResponse;
import com.potlatech.pharmacy.repository.InventoryAlertRepository;
import com.potlatech.pharmacy.repository.projection.ExpiryAlertRow;
import com.potlatech.pharmacy.repository.projection.LowStockRow;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Stock alerts (Unit 09).
 *
 * <p>Separate from {@link InventoryBatchController} rather than added to it,
 * because the two answer different questions. A batch list is "what is on the
 * shelf"; these are "what should I reorder" and "what should I destroy". Mixing
 * them produces a screen where an alert is indistinguishable from a record.
 *
 * <p>Reads are {@code CASHIER}+, matching the batch list they sit beside: a
 * cashier seeing that a medicine is short is useful, and neither endpoint accepts
 * a write, so the role grants nothing.
 */
@RestController
@RequestMapping("/api/v1/inventory/alerts")
public class InventoryAlertController {

    /**
     * Ceiling on the near-expiry window.
     *
     * <p>{@code withinDays=99999} is not a request for a long window, it is a
     * typo, and without a ceiling it returns the entire batch table for a
     * pharmacy with deep history — expensive, and a screen nobody wanted.
     */
    private static final int MAX_WITHIN_DAYS = 365;

    private final InventoryAlertRepository alerts;
    private final Clock clock;

    /**
     * @param clock injected as a constructor seam so the near-expiry cutoff is
     *             testable; Spring has no {@code Clock} bean, so the second
     *             constructor supplies the system clock exactly as
     *             {@code InventoryBatchService} does.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public InventoryAlertController(InventoryAlertRepository alerts) {
        this(alerts, Clock.systemDefaultZone());
    }

    InventoryAlertController(InventoryAlertRepository alerts, Clock clock) {
        this.alerts = alerts;
        this.clock = clock;
    }

    @GetMapping("/low-stock")
    @PreAuthorize("hasAnyRole('CASHIER', 'PHARMACIST', 'PHARMACY_MANAGER', 'SUPER_ADMIN')")
    public List<StockAlertResponse> lowStock() {
        return alerts.findLowStock().stream()
                .map(InventoryAlertController::toAlert)
                .toList();
    }

    @GetMapping("/near-expiry")
    @PreAuthorize("hasAnyRole('CASHIER', 'PHARMACIST', 'PHARMACY_MANAGER', 'SUPER_ADMIN')")
    public List<ExpiryAlertResponse> nearExpiry(
            @RequestParam(defaultValue = "90") int withinDays) {
        int window = Math.clamp(withinDays, 0, MAX_WITHIN_DAYS);
        LocalDate today = LocalDate.now(clock);
        return alerts.findExpiringSoon(today, window).stream()
                .map(InventoryAlertController::toAlert)
                .toList();
    }

    private static StockAlertResponse toAlert(LowStockRow row) {
        return StockAlertResponse.of(
                row.getId(), row.getItemName(), row.getItemType(),
                row.getForm(), row.getStrength(),
                nullSafe(row.getTotalAvailable()), nullSafe(row.getReorderLevel()),
                nullSafe(row.getActiveBatchCount()));
    }

    private static ExpiryAlertResponse toAlert(ExpiryAlertRow row) {
        // daysRemaining comes from the query, not from a second date subtraction
        // here: the SQL sorts on that same expression, and a DTO that recomputed it
        // could order a row differently from the badge it displays.
        return ExpiryAlertResponse.from(
                row.getBatchId(), row.getBatchNumber(), row.getMedicineId(), row.getMedicineName(),
                nullSafe(row.getQuantityAvailable()), row.getExpiryDate(),
                nullSafe(row.getDaysRemaining()), row.getStatus());
    }

    private static int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }
}
