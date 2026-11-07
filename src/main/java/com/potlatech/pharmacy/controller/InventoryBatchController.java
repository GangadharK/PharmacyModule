package com.potlatech.pharmacy.controller;

import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.potlatech.pharmacy.common.dto.PageResponse;
import com.potlatech.pharmacy.dto.inventory.AdjustStockRequest;
import com.potlatech.pharmacy.dto.inventory.CreateBatchRequest;
import com.potlatech.pharmacy.dto.inventory.FefoPreviewResponse;
import com.potlatech.pharmacy.dto.inventory.FefoRequest;
import com.potlatech.pharmacy.dto.inventory.InventoryBatchResponse;
import com.potlatech.pharmacy.dto.inventory.ReconciliationResponse;
import com.potlatech.pharmacy.dto.inventory.StockTransactionResponse;
import com.potlatech.pharmacy.dto.inventory.UpdateBatchRequest;
import com.potlatech.pharmacy.entity.InventoryBatch;
import com.potlatech.pharmacy.service.InventoryBatchService;

import java.util.List;
import java.util.UUID;

/**
 * Batch inventory.
 *
 * <p><strong>Reads are {@code CASHIER}+, writes are {@code PHARMACY_MANAGER}+.</strong>
 * A cashier must be able to see what is in stock to dispense it, and must not be
 * able to change it. The distinction is a real boundary, not a display
 * preference: the ledger is the audit trail a pharmacy is regulated on.
 *
 * <p>UPPER_CASE role names only — AGENTS rule 8. SCMS mixes case and works around
 * it by emitting case variants from the JWT converter, which quietly grants extra
 * authorities.
 *
 * <p><strong>No delete endpoint.</strong> AGENTS rule 2: stock is never deleted. A
 * batch is written off with a ledger row, not removed, and a hard delete would
 * leave a gap in the movement history indistinguishable from "it never existed".
 */
@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryBatchController {

    private static final int MAX_PAGE_SIZE = 100;

    private final InventoryBatchService inventory;

    public InventoryBatchController(InventoryBatchService inventory) {
        this.inventory = inventory;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('CASHIER', 'PHARMACIST', 'PHARMACY_MANAGER', 'SUPER_ADMIN')")
    public PageResponse<InventoryBatchResponse> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID medicineId,
            @RequestParam(required = false) UUID itemId,
            @RequestParam(required = false) UUID supplierId,
            @RequestParam(required = false) InventoryBatch.Status status,
            @RequestParam(required = false) InventoryBatch.Location location,
            @RequestParam(required = false) Integer expiringWithinDays,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        // Default sort is expiry-then-batch rather than insertion order: an
        // inventory list is read to answer "what runs out first", and the
        // soonest-expiring row is the answer FEFO would give anyway.
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                safeSize,
                Sort.by(Sort.Order.asc("expiryDate"), Sort.Order.asc("batchNumber")));

        return inventory.list(search, medicineId, itemId, supplierId, status, location,
                expiringWithinDays, pageable);
    }

    /**
     * Batches running out, soonest expiry first — the alert screen's query.
     *
     * <p>Separate from the paged list because it answers a different question: not
     * "what is in stock" but "what should I reorder". It is bounded rather than
     * paged because the caller wants the whole list to act on.
     */
    @GetMapping("/expiring")
    @PreAuthorize("hasAnyRole('CASHIER', 'PHARMACIST', 'PHARMACY_MANAGER', 'SUPER_ADMIN')")
    public List<InventoryBatchResponse> expiring(
            @RequestParam UUID medicineId,
            @RequestParam(defaultValue = "90") int withinDays) {
        return inventory.expiringSoon(medicineId, withinDays);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('CASHIER', 'PHARMACIST', 'PHARMACY_MANAGER', 'SUPER_ADMIN')")
    public InventoryBatchResponse get(@PathVariable UUID id) {
        return inventory.get(id);
    }

    /** A batch's movements, oldest first. */
    @GetMapping("/{id}/history")
    @PreAuthorize("hasAnyRole('CASHIER', 'PHARMACIST', 'PHARMACY_MANAGER', 'SUPER_ADMIN')")
    public List<StockTransactionResponse> history(@PathVariable UUID id) {
        return inventory.history(id);
    }

    // ==================== FEFO dispensing (Unit 06) ====================

    /**
     * Which batches a request would come out of. Writes nothing.
     *
     * <p>{@code POST} rather than {@code GET}: the request carries a quantity, and
     * a quantity in a query string invites a link prefetch, a crawler, or a
     * well-meaning "just checking" of a magnitude nobody intended to request.
     */
    @PostMapping("/fefo-preview")
    @PreAuthorize("hasAnyRole('CASHIER', 'PHARMACIST', 'PHARMACY_MANAGER', 'SUPER_ADMIN')")
    public FefoPreviewResponse fefoPreview(@Valid @RequestBody FefoRequest request) {
        return inventory.previewFefo(request);
    }

    /**
     * Dispenses, earliest expiry first.
     *
     * <p>Open to {@code CASHIER}. Handing medicine over the counter is the role's
     * actual job; restricting dispensing to a pharmacist would mean a cashier
     * stands there holding a prescription while a pharmacist walks over. The
     * response still omits {@code cost_price} — a cashier learns which batch was
     * used and what it sells for, never what it cost.
     *
     * <p>{@code 201}, because this creates ledger rows. It is not a read that
     * happens to move a balance.
     */
    @PostMapping("/dispense")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('CASHIER', 'PHARMACIST', 'PHARMACY_MANAGER', 'SUPER_ADMIN')")
    public FefoPreviewResponse dispense(@Valid @RequestBody FefoRequest request) {
        return inventory.issueStock(request);
    }

    /**
     * Whether the ledger agrees with the balances.
     *
     * <p>Readable by a pharmacist as well as a manager: the pharmacist is the one
     * who will be asked why a count does not match, and making them ask a manager
     * to run it is how the check stops being run.
     */
    @GetMapping("/reconciliation")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'PHARMACY_MANAGER', 'SUPER_ADMIN')")
    public ReconciliationResponse reconciliation() {
        return inventory.reconcile();
    }

    /** Receives a delivery: the batch and its opening ledger row, atomically. */
    @PostMapping
    @PreAuthorize("hasAnyRole('PHARMACY_MANAGER', 'SUPER_ADMIN')")
    public ResponseEntity<InventoryBatchResponse> create(
            @Valid @RequestBody CreateBatchRequest request) {
        InventoryBatchResponse created = inventory.create(request);
        return ResponseEntity
                .created(UriComponentsBuilder.fromPath("/api/v1/inventory/{id}")
                        .buildAndExpand(created.id()).toUri())
                .body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('PHARMACY_MANAGER', 'SUPER_ADMIN')")
    public InventoryBatchResponse update(@PathVariable UUID id,
                                         @Valid @RequestBody UpdateBatchRequest request) {
        return inventory.update(id, request);
    }

    /**
     * Manual stock correction.
     *
     * <p>POST rather than PATCH, and it takes a signed quantity rather than a
     * target level. A target would be ambiguous — set to 10 when 10 is what is
     * already there means "no change" or "recounted and it is 10", and those have
     * different ledger entries — and an empty POST is a malformed request rather
     * than a silent no-op.
     */
    @PostMapping("/{id}/adjust")
    @PreAuthorize("hasAnyRole('PHARMACY_MANAGER', 'SUPER_ADMIN')")
    public InventoryBatchResponse adjust(@PathVariable UUID id,
                                         @Valid @RequestBody AdjustStockRequest request) {
        return inventory.adjust(id, request);
    }

    /** Holds a batch back from dispensing. Zero-delta, but it is on the record. */
    @PostMapping("/{id}/quarantine")
    @PreAuthorize("hasAnyRole('PHARMACY_MANAGER', 'SUPER_ADMIN')")
    public InventoryBatchResponse quarantine(@PathVariable UUID id,
                                             @RequestParam String reason) {
        return inventory.quarantine(id, reason);
    }

    @PostMapping("/{id}/release")
    @PreAuthorize("hasAnyRole('PHARMACY_MANAGER', 'SUPER_ADMIN')")
    public InventoryBatchResponse release(@PathVariable UUID id,
                                          @RequestParam(required = false) String note) {
        return inventory.release(id, note);
    }
}
