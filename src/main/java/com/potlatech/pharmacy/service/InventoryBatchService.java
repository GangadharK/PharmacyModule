package com.potlatech.pharmacy.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.potlatech.pharmacy.common.Specifications;
import com.potlatech.pharmacy.common.dto.PageResponse;
import com.potlatech.pharmacy.common.exception.BusinessRuleException;
import com.potlatech.pharmacy.common.exception.InsufficientStockException;
import com.potlatech.pharmacy.common.exception.InvalidStateTransitionException;
import com.potlatech.pharmacy.common.exception.ResourceNotFoundException;
import com.potlatech.pharmacy.dto.inventory.AdjustStockRequest;
import com.potlatech.pharmacy.dto.inventory.CreateBatchRequest;
import com.potlatech.pharmacy.dto.inventory.FefoPreviewResponse;
import com.potlatech.pharmacy.dto.inventory.FefoRequest;
import com.potlatech.pharmacy.dto.inventory.InventoryBatchResponse;
import com.potlatech.pharmacy.dto.inventory.ReconciliationResponse;
import com.potlatech.pharmacy.dto.inventory.StockTransactionResponse;
import com.potlatech.pharmacy.dto.inventory.UpdateBatchRequest;
import com.potlatech.pharmacy.entity.InventoryBatch;
import com.potlatech.pharmacy.entity.Medicine;
import com.potlatech.pharmacy.entity.NonMedicalItem;
import com.potlatech.pharmacy.entity.StockTransaction;
import com.potlatech.pharmacy.entity.Supplier;
import com.potlatech.pharmacy.entity.User;
import com.potlatech.pharmacy.repository.InventoryBatchRepository;
import com.potlatech.pharmacy.repository.MedicineRepository;
import com.potlatech.pharmacy.repository.NonMedicalItemRepository;
import com.potlatech.pharmacy.repository.StockTransactionRepository;
import com.potlatech.pharmacy.repository.SupplierRepository;
import com.potlatech.pharmacy.repository.UserRepository;
import com.potlatech.pharmacy.security.AuthenticatedUser;
import com.potlatech.pharmacy.security.CurrentUserContext;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Stock batches and the ledger.
 *
 * <p><strong>The one rule everything here exists to keep:</strong> every change to
 * {@code inventory_batch.quantity_available} writes a
 * {@code stock_transaction} row in the same transaction, so
 * {@code SUM(quantity_changed)} always equals the balance. Not usually — always. A
 * ledger that is "usually" in agreement is one nobody can audit, because a
 * disagreement is indistinguishable from a bug in the arithmetic.
 *
 * <p>That is why the balance is never checked in Java before being written.
 * {@link InventoryBatchRepository#applyDelta} folds the post-condition into
 * {@code UPDATE ... WHERE quantity_available + :delta >= 0}, so a deduction that
 * would go negative matches zero rows and the caller sees the failure. Read, then
 * compare, then write has a race between the compare and the write that no amount of
 * {@code @Transactional} closes.
 *
 * <p><strong>Cost redaction happens here, in the service</strong> — not in the
 * controller, and never in the client. In the controller it is one line that a new
 * endpoint can forget; here every path goes through {@link #toResponse}, so a
 * response cannot leak a cost by being built somewhere new.
 */
@Service
@Transactional(readOnly = true)
public class InventoryBatchService {

    private final InventoryBatchRepository batches;
    private final StockTransactionRepository ledger;
    private final MedicineRepository medicines;
    private final NonMedicalItemRepository items;
    private final SupplierRepository suppliers;
    private final UserRepository users;
    private final CurrentUserContext currentUser;
    private final Clock clock;

    @Autowired
    public InventoryBatchService(InventoryBatchRepository batches,
                                 StockTransactionRepository ledger,
                                 MedicineRepository medicines,
                                 NonMedicalItemRepository items,
                                 SupplierRepository suppliers,
                                 UserRepository users,
                                 CurrentUserContext currentUser) {
        this(batches, ledger, medicines, items, suppliers, users, currentUser,
                Clock.systemDefaultZone());
    }

    /**
     * Test seam: an injected {@link Clock} fixes "today", so an expiry sweep is
     * testable without editing a batch's date or waiting for midnight.
     */
    InventoryBatchService(InventoryBatchRepository batches,
                          StockTransactionRepository ledger,
                          MedicineRepository medicines,
                          NonMedicalItemRepository items,
                          SupplierRepository suppliers,
                          UserRepository users,
                          CurrentUserContext currentUser,
                          Clock clock) {
        this.batches = batches;
        this.ledger = ledger;
        this.medicines = medicines;
        this.items = items;
        this.suppliers = suppliers;
        this.users = users;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    // ==================== reads ====================

    /**
     * Filtered, paginated batch listing.
     *
     * <p>{@code expiringWithinDays} is the one filter whose {@code null} is not the
     * same as zero: {@code null} means "no expiry filter", and {@code 0} means
     * "already expired" — the alert screen's most urgent filter, which a
     * zero-default would make unreachable.
     */
    @Transactional(readOnly = true)
    public PageResponse<InventoryBatchResponse> list(String search,
                                                     UUID medicineId,
                                                     UUID itemId,
                                                     UUID supplierId,
                                                     InventoryBatch.Status status,
                                                     InventoryBatch.Location location,
                                                     Integer expiringWithinDays,
                                                     Pageable pageable) {
        LocalDate today = LocalDate.now(clock);
        LocalDate cutoff = cutoffFor(expiringWithinDays, today);

        Page<InventoryBatch> page = batches.findAll(
                Specifications.allOf(
                        Specifications.textOr(search, "batchNumber"),
                        Specifications.eq("medicineId", medicineId),
                        Specifications.eq("itemId", itemId),
                        Specifications.eq("supplierId", supplierId),
                        Specifications.eq("status", status),
                        Specifications.eq("location", location),
                        expiringBy(cutoff)),
                pageable);

        Map<UUID, String> names = resolveNames(page.getContent());
        List<InventoryBatchResponse> content = page.getContent().stream()
                .map(b -> toResponse(b, names))
                .toList();
        return PageResponse.of(content, page.getNumber(), page.getSize(), page.getTotalElements());
    }

    /**
     * Usable batches of a medicine expiring on or before the cutoff, soonest
     * first.
     *
     * @param withinDays days from today; {@code 0} returns only what is already
     *                   past its date.
     */
    @Transactional(readOnly = true)
    public List<InventoryBatchResponse> expiringSoon(UUID medicineId, int withinDays) {
        if (withinDays < 0) {
            throw new BusinessRuleException("withinDays must not be negative: %d", withinDays);
        }
        LocalDate cutoff = LocalDate.now(clock).plusDays(withinDays);

        List<InventoryBatch> found = batches
                .findByMedicineIdAndStatusAndQuantityAvailableGreaterThanAndExpiryDateLessThanEqualOrderByExpiryDateAsc(
                        medicineId, InventoryBatch.Status.ACTIVE, 0, cutoff);

        return decorate(found);
    }

    @Transactional(readOnly = true)
    public InventoryBatchResponse get(UUID id) {
        InventoryBatch batch = require(id);
        return toResponse(batch, resolveNames(List.of(batch)));
    }

    /**
     * A batch's movements, oldest first.
     *
     * <p>Open to every authenticated role. The movement trail is not commercially
     * sensitive — a cashier disputing a discrepancy needs to read it — and it
     * carries no cost, since cost lives on the batch rather than the row.
     */
    @Transactional(readOnly = true)
    public List<StockTransactionResponse> history(UUID id) {
        require(id);
        List<StockTransaction> rows = ledger.findByBatchIdOrderByCreatedAtAsc(id);
        Map<UUID, String> actors = resolveActorNames(rows);
        return rows.stream()
                .map(t -> StockTransactionResponse.from(t, actors.get(t.getPerformedBy())))
                .toList();
    }

    /**
     * Whether the ledger agrees with the balances, across every batch.
     *
     * <p>One query, so it is cheap enough to call on demand — and a soundness check
     * that can only be run by hand is not a check.
     */
    @Transactional(readOnly = true)
    public ReconciliationResponse reconcile() {
        List<ReconciliationResponse.Mismatch> mismatches = ledger.findDisagreements().stream()
                .map(d -> new ReconciliationResponse.Mismatch(
                        d.getBatchId(),
                        d.getBatchNumber(),
                        d.getLedgerTotal(),
                        d.getBalance(),
                        d.getBalance() - d.getLedgerTotal()))
                .toList();

        return new ReconciliationResponse((int) batches.count(), mismatches);
    }

    // ==================== writes ====================

    /**
     * Receives a delivery: the batch and its opening ledger row, together.
     *
     * <p>The balance is set to {@code quantityReceived} and a {@code PURCHASE_IN}
     * row of the same magnitude is written in this transaction, so the two agree
     * from the instant the row exists. Separate endpoints would leave a window in
     * which a batch exists with a balance its ledger does not explain — a state
     * reconciliation reports as corrupt, created by normal use.
     */
    @Transactional
    public InventoryBatchResponse create(CreateBatchRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);

        if (request.medicineId() == null && request.itemId() == null) {
            throw new BusinessRuleException("medicineId or itemId is required");
        }
        if (request.medicineId() != null && request.itemId() != null) {
            throw new BusinessRuleException(
                    "medicineId and itemId are mutually exclusive - a batch belongs to one product");
        }
        validateDates(request.manufacturingDate(), request.expiryDate(), now);
        validateParentIsActive(request.medicineId(), request.itemId());
        validateSupplierIsActive(request.supplierId());

        Integer requestedQuantity = request.quantityReceived();
        if (requestedQuantity == null || requestedQuantity < 1) {
            throw new BusinessRuleException("quantityReceived must be at least 1");
        }
        int received = requestedQuantity;

        InventoryBatch batch = InventoryBatch.builder()
                .id(UUID.randomUUID())
                .medicineId(request.medicineId())
                .itemId(request.itemId())
                .supplierId(request.supplierId())
                .batchNumber(request.normalisedBatchNumber())
                .manufacturingDate(request.manufacturingDate())
                .expiryDate(request.expiryDate())
                .quantityReceived(received)
                .quantityAvailable(received)
                .costPrice(request.costPrice())
                .sellingPrice(request.sellingPrice())
                .location(request.resolvedLocation())
                .status(InventoryBatch.Status.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();

        batches.save(batch);
        post(StockTransaction.TransactionType.PURCHASE_IN, batch, received, null, null,
                request.note(), now);

        return toResponse(batch, resolveNames(List.of(batch)));
    }

    /**
     * Edits a batch's descriptive and pricing fields.
     *
     * <p>The balance and {@code quantityReceived} are not editable here, and
     * {@code status} is not settable: quantity moves through {@link #adjust} and
     * state through {@link #quarantine} / {@link #release}, each writing the ledger
     * row that makes it explainable afterwards.
     */
    @Transactional
    public InventoryBatchResponse update(UUID id, UpdateBatchRequest request) {
        InventoryBatch batch = require(id);
        LocalDateTime now = LocalDateTime.now(clock);

        validateDates(request.manufacturingDate(), request.expiryDate(), now);
        validateSupplierIsActive(request.supplierId());

        batch.setSupplierId(request.supplierId());
        batch.setBatchNumber(request.normalisedBatchNumber());
        batch.setManufacturingDate(request.manufacturingDate());
        batch.setExpiryDate(request.expiryDate());
        batch.setCostPrice(request.costPrice());
        batch.setSellingPrice(request.sellingPrice());
        batch.setLocation(request.location());
        batch.setUpdatedAt(now);

        return toResponse(batch, resolveNames(List.of(batch)));
    }

    /**
     * Manual stock correction — a cycle count, a break, a found surplus.
     *
     * <p>The balance moves through {@link InventoryBatchRepository#applyDelta} and
     * the ledger row is written beside it. A deduction that fails the non-negative
     * post-condition leaves no ledger row behind, because the exception rolls this
     * whole transaction back: the caller sees {@link InsufficientStockException} and
     * the balance is exactly what it was.
     */
    /**
     * Applies one cycle-count variance.
     *
     * <p>Exists so Unit 09 posts through the same locked, guarded path as every
     * other stock movement, rather than reaching past it. The count service needs
     * the non-negative post-condition, the re-read, and the ledger write; all
     * three are already correct here and reimplementing them is how a second code
     * path starts skipping the invariant.
     *
     * <p><strong>Both directions are typed {@link StockTransaction.TransactionType#CYCLE_COUNT_ADJUST}.</strong>
     * The shared helper's default maps a negative delta to
     * {@code DAMAGE_WRITE_OFF}, which is right for a breakage and wrong here: a
     * shelf that was short at the count was not damaged, it was mis-recorded.
     * Filing it as damage would silently move a stocktaking discrepancy into the
     * damage report, and a pharmacy that writes off "damaged" stock for bookkeeping
     * sloppiness loses the signal that identifies real breakage.
     *
     * <p>A negative variance also cannot exceed the live balance — the
     * post-condition enforces it, so a count taken against a stale snapshot
     * cannot write a batch below zero.
     *
     * @param cycleCountId the session, recorded as the ledger's {@code reference_id}
     *                     so a variance traces back to the count that found it
     */
    @Transactional
    public InventoryBatchResponse applyCycleCountVariance(UUID batchId,
                                                         int variance,
                                                         UUID cycleCountId,
                                                         String note) {
        if (variance == 0) {
            throw new BusinessRuleException(
                    "variance must not be zero - a confirmed count writes no ledger row");
        }
        InventoryBatch batch = require(batchId);
        LocalDateTime now = LocalDateTime.now(clock);
        InventoryBatch updated = applyDelta(batch, variance,
                StockTransaction.TransactionType.CYCLE_COUNT_ADJUST,
                StockTransaction.ReferenceType.CYCLE_COUNT, cycleCountId, note, now);
        return toResponse(updated, resolveNames(List.of(updated)));
    }

    @Transactional
    public InventoryBatchResponse adjust(UUID id, AdjustStockRequest request) {
        InventoryBatch batch = require(id);
        LocalDateTime now = LocalDateTime.now(clock);

        int delta = request.signedDelta();
        if (delta == 0) {
            throw new BusinessRuleException(
                    "quantityChange must not be zero - a movement of no stock is not an event");
        }
        if (batch.getStatus() == InventoryBatch.Status.EXPIRED
                || batch.getStatus() == InventoryBatch.Status.DEPLETED) {
            throw new InvalidStateTransitionException(
                    "InventoryBatch", batch.getStatus().name(), "ADJUSTED");
        }
        if (batch.getStatus() == InventoryBatch.Status.QUARANTINED) {
            throw new InvalidStateTransitionException("InventoryBatch", "QUARANTINED", "ADJUSTED");
        }

        if (batch.getQuantityAvailable() + delta < 0) {
            throw insufficient(batch, -delta, batch.getQuantityAvailable());
        }

        // The returned batch, not `batch`: the bulk update detaches the original.
        InventoryBatch updated = applyDelta(batch, delta, request.referenceType(),
                request.referenceId(), request.note(), now);
        return toResponse(updated, resolveNames(List.of(updated)));
    }

    /**
     * Holds a batch back from dispensing.
     *
     * <p>Writes a zero-delta {@code QUARANTINE} row. No stock moves, so the balance
     * is untouched and the ledger still reconciles — but the reason and the person
     * are on the record, which is the point. "Who quarantined this, when and why" is
     * asked after every recall notice, and a status column cannot answer it.
     */
    @Transactional
    public InventoryBatchResponse quarantine(UUID id, String reason) {
        InventoryBatch batch = require(id);
        LocalDateTime now = LocalDateTime.now(clock);

        if (batch.getStatus() != InventoryBatch.Status.ACTIVE) {
            throw new InvalidStateTransitionException(
                    "InventoryBatch", batch.getStatus().name(), "QUARANTINED");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleException("A quarantine reason is required");
        }

        batch.setStatus(InventoryBatch.Status.QUARANTINED);
        batch.setUpdatedAt(now);
        post(StockTransaction.TransactionType.QUARANTINE, batch, 0, null, null, reason.trim(), now);

        return toResponse(batch, resolveNames(List.of(batch)));
    }

    /**
     * Returns a quarantined batch to dispensable stock.
     *
     * <p>Zero-delta too, and for the same reason: whether a release was correct is
     * exactly what a recall investigation later reconstructs.
     */
    @Transactional
    public InventoryBatchResponse release(UUID id, String note) {
        InventoryBatch batch = require(id);
        LocalDateTime now = LocalDateTime.now(clock);

        if (batch.getStatus() != InventoryBatch.Status.QUARANTINED) {
            throw new InvalidStateTransitionException(
                    "InventoryBatch", batch.getStatus().name(), "RELEASED");
        }

        batch.setStatus(InventoryBatch.Status.ACTIVE);
        batch.setUpdatedAt(now);
        post(StockTransaction.TransactionType.RELEASE, batch, 0, null, null, note, now);

        return toResponse(batch, resolveNames(List.of(batch)));
    }

    // ==================== expiry ====================

    /**
     * Writes off one batch that is past its expiry date.
     *
     * <p><strong>The write-off is {@code -remaining}, not zero.</strong> The balance
     * drops to zero, so the ledger must drop by the same amount or
     * {@code SUM(quantity_changed)} stops equalling the balance and the sweep itself
     * becomes the thing reconciliation reports. A zero delta would also leave
     * expired stock with no trace in the movement history, and shrinkage — the
     * number a pharmacy is judged on — would become uncomputable.
     *
     * <p>{@code chk_txn_changed} still permits zero for this type, deliberately. That
     * constraint's job is to stop a no-op masquerading as an audit event for the
     * seven types where a no-op is meaningless; it is not the place to encode this
     * method's arithmetic. Tightening it would need a V6 migration to enforce a rule
     * every writer here already upholds.
     *
     * <p>Idempotent — a batch already expired or depleted returns empty rather than
     * throwing, because the sweep may legitimately see a row another process has
     * just handled.
     *
     * @return the batch id if a write-off was recorded, empty otherwise.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<UUID> expireOne(UUID id) {
        InventoryBatch batch = require(id);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = LocalDate.now(clock);

        if (batch.getStatus() == InventoryBatch.Status.EXPIRED
                || batch.getStatus() == InventoryBatch.Status.DEPLETED) {
            return List.of();
        }

        LocalDate expiryDate = batch.getExpiryDate();
        if (expiryDate == null || !expiryDate.isBefore(today)) {
            return List.of();
        }

        int remaining = batch.getQuantityAvailable();

        if (remaining > 0) {
            batches.applyDelta(batch.getId(), -remaining, now);
            post(StockTransaction.TransactionType.EXPIRY_WRITE_OFF, batch, -remaining,
                    null, null, "Expired on " + expiryDate, now);
        }

        // Re-read before setting the status. `batch` was loaded before the bulk
        // update above, and clearAutomatically detached it, so setting EXPIRED on
        // it would change nothing the database can see. It has to go on an
        // attached instance or every expired batch stays ACTIVE in the database
        // and remains a FEFO candidate by status indefinitely.
        InventoryBatch attached = batches.findById(batch.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Inventory batch", batch.getId()));
        attached.setStatus(InventoryBatch.Status.EXPIRED);
        attached.setUpdatedAt(now);

        return List.of(attached.getId());
    }

    /**
     * Batches a sweep should look at: past their date, and not yet resolved.
     *
     * <p>{@code QUARANTINED} is included because a quarantined batch still expires,
     * and skipping it would leave expired stock in a location a pharmacist can
     * physically reach. {@code DEPLETED} is excluded — there is nothing left to
     * write off, and the row's status is already right.
     */
    @Transactional(readOnly = true)
    public List<UUID> findExpirable() {
        return batches.findByExpiryDateLessThanAndStatusIn(
                        LocalDate.now(clock),
                        List.of(InventoryBatch.Status.ACTIVE, InventoryBatch.Status.QUARANTINED))
                .stream()
                .map(InventoryBatch::getId)
                .toList();
    }

    // ==================== FEFO dispensing (Unit 06) ====================

    /**
     * Which batches a request would come out of. Writes nothing.
     *
     * <p>Unlocked on purpose. A preview performs no writes, so a stale answer is
     * an old answer rather than a wrong one, and locking it would make every
     * pharmacist opening the FEFO screen contend with real dispensing. See
     * {@link #issueStock} for the locked path — same allocator, so the preview
     * cannot disagree with the outcome about <em>which</em> batches are used.
     */
    @Transactional(readOnly = true)
    public FefoPreviewResponse previewFefo(FefoRequest request) {
        return toPreview(runFefo(request, false));
    }

    /**
     * Dispenses a quantity of a medicine, earliest expiry first.
     *
     * <p><strong>One transaction covers the locked read and every deduction it
     * drives.</strong> That is the whole concurrency story, and it is why the
     * allocation cannot be split across a "plan" step and an "apply" step: the
     * plan is only valid while the rows it was made from are locked.
     *
     * <p>Two concurrent dispenses of the same medicine queue at the
     * {@code FOR UPDATE} read rather than interleaving. Whichever gets there
     * second sees the first's deductions — or waits for the first transaction to
     * commit and then sees them — and if the stock is gone, gets a typed
     * {@link InsufficientStockException} instead of a negative balance.
     *
     * <p>The locked read alone would not be enough, and the guarded update alone
     * would not be enough either. The lock stops the common case; the guard in
     * {@code applyDelta} catches anything that slips past it and turns it into a
     * rollback rather than a corrupt balance. Neither is redundant.
     *
     * @throws InsufficientStockException if the batches cannot cover the request.
     *         Thrown <em>before</em> any deduction is attempted, so a rejected
     *         request leaves no trace: no partial dispense is representable.
     */
    @Transactional
    public FefoPreviewResponse issueStock(FefoRequest request) {
        AllocationResult result = runFefo(request, true);

        if (!result.isComplete()) {
            String name = medicines.findById(request.medicineId())
                    .map(Medicine::getGenericName)
                    .orElse("medicine");
            // Before any write. Deducing what we can and then rolling back would
            // leave the same database state but a far more confusing error, and
            // would burn ledger sequence values for a movement that never happened.
            result.requireComplete(name);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        Map<UUID, InventoryBatch> locked = new HashMap<>();
        for (FefoAllocation allocation : result.allocations()) {
            // The allocation was computed from the locked read, so the batch is
            // expected to be in the candidate set; require() rather than a
            // computeIfAbsent so a reference to something else fails loudly
            // instead of deducting from a batch nobody vetted.
            InventoryBatch batch = locked.computeIfAbsent(allocation.batchId(), this::require);
            // Reassign from the return value: the bulk update detaches `batch`.
            locked.put(batch.getId(),
                    applyDelta(batch, -allocation.quantity(),
                            StockTransaction.TransactionType.SALE_OUT,
                            request.referenceType(), request.referenceId(),
                            "FEFO dispense of " + allocation.quantity()
                                    + " from " + allocation.batchNumber(),
                            now));
        }

        return toPreview(result);
    }

    /**
     * The shared body of preview and dispense: fetch candidates, hand them to the
     * pure allocator.
     *
     * @param locked {@code true} for the issuing path, which takes
     *               {@code FOR UPDATE} row locks. {@code false} for preview.
     */
    private AllocationResult runFefo(FefoRequest request, boolean locked) {
        if (request.medicineId() == null) {
            throw new BusinessRuleException("medicineId is required");
        }
        // FefoAllocator refuses a negative request itself, but checking here too
        // means a bad request never reaches the database at all.
        if (request.requestedQuantity() < 0) {
            throw new BusinessRuleException(
                    "requestedQuantity must not be negative: %d", request.requestedQuantity());
        }
        if (request.location() == null) {
            throw new BusinessRuleException("location is required");
        }
        // The reference pair is checked HERE, before the candidate read, and not
        // left to post() as it is for the other write paths. post() runs after
        // the balance has already moved, so a malformed pair discovered there
        // depends on the caller's transaction rolling back to leave the stock
        // alone. Inside a request-scoped transaction that holds; inside a longer
        // one that catches the exception and carries on, it does not, and a
        // rejected request would have silently deducted stock. Refusing a
        // malformed request before touching anything removes the question.
        if (request.referenceType() != null && request.referenceId() == null) {
            throw new BusinessRuleException("referenceId is required when referenceType is given");
        }
        if (request.referenceType() == null && request.referenceId() != null) {
            throw new BusinessRuleException("referenceType is required when referenceId is given");
        }

        LocalDate today = LocalDate.now(clock);
        List<InventoryBatch> candidates = locked
                ? batches.findFefoCandidatesForUpdate(
                        request.medicineId(), request.location().name(), today)
                : batches.findFefoCandidates(
                        request.medicineId(), request.location().name(), today);

        return new FefoAllocator().allocate(
                candidates.stream().map(InventoryBatchService::toCandidate).toList(),
                request.requestedQuantity(),
                today);
    }

    /** Entity to the allocator's minimal input. Keeps JPA out of the pure function. */
    private static StockCandidate toCandidate(InventoryBatch batch) {
        return new StockCandidate(
                batch.getId(),
                batch.getBatchNumber(),
                batch.getExpiryDate(),
                batch.getQuantityAvailable(),
                batch.getSellingPrice());
    }

    private FefoPreviewResponse toPreview(AllocationResult result) {
        return new FefoPreviewResponse(
                result.allocations(),
                result.totalAllocated(),
                result.shortfall());
    }

    // ==================== internals ====================

    /**
     * Moves a batch's balance and records it, in this transaction.
     *
     * <p>Every quantity-changing path goes through here, so there is exactly one
     * place a balance can move and therefore exactly one place to audit.
     *
     * <p>The ledger type is inferred from the sign of the delta. That is correct
     * for a manual adjustment — a gain is a cycle count, a loss is damage — and
     * wrong for dispensing, which passes {@code SALE_OUT} explicitly rather than
     * letting a sale read as breakage.
     *
     * @return the batch as the database now holds it. See the sibling overload.
     */
    private InventoryBatch applyDelta(InventoryBatch batch,
                                      int delta,
                                      StockTransaction.ReferenceType referenceType,
                                      UUID referenceId,
                                      String note,
                                      LocalDateTime now) {
        return applyDelta(batch, delta, null, referenceType, referenceId, note, now);
    }

    /**
     * The single writer of a balance movement and its ledger row.
     *
     * <p><strong>Returns a re-read, managed entity, and callers must use the
     * return value.</strong> {@code applyDelta} is a bulk {@code @Modifying}
     * query declared {@code clearAutomatically = true}, so the persistence
     * context is cleared the moment it runs and any entity loaded before it is
     * detached. Mutating the caller's instance after that point changes a
     * detached object, and Hibernate will never flush it — the in-memory object
     * looks right, the response built from it looks right, and the database
     * silently keeps the old value.
     *
     * <p>That is not hypothetical. It is how {@code DEPLETED} and {@code EXPIRED}
     * both came to exist in code and never reach the database: the derived status
     * was set on a detached batch, the response reported it, and the tests
     * asserted the response. The database stayed {@code ACTIVE} — which left every
     * expired batch a permanent FEFO candidate by status, with the date predicate
     * as the only thing between an expired medicine and a patient.
     *
     * @return the batch as the database now holds it, attached, with the derived
     *         status already applied.
     */
    private InventoryBatch applyDelta(InventoryBatch batch,
                                      int delta,
                                      StockTransaction.TransactionType explicitType,
                                      StockTransaction.ReferenceType referenceType,
                                      UUID referenceId,
                                      String note,
                                      LocalDateTime now) {
        UUID id = batch.getId();
        int updated = batches.applyDelta(id, delta, now);
        if (updated == 0) {
            // Lost the race: the balance moved between our read and this write.
            // The post-condition refused the change, so the balance is still
            // sound - but the caller's intent is no longer satisfiable.
            throw insufficient(batch, Math.abs(delta), balanceOf(id));
        }

        // Re-read: `batch` is detached from here on. This one is attached, so the
        // derived status below is flushed at commit.
        InventoryBatch fresh = batches.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Inventory batch", id));
        fresh.setUpdatedAt(now);
        markDepletedIfZero(fresh);

        StockTransaction.TransactionType type = explicitType != null
                ? explicitType
                : (delta > 0 ? StockTransaction.TransactionType.CYCLE_COUNT_ADJUST
                             : StockTransaction.TransactionType.DAMAGE_WRITE_OFF);
        post(type, fresh, delta, referenceType, referenceId, note, now);
        return fresh;
    }

    /**
     * Appends one ledger row. The only writer of {@code stock_transaction} in the
     * application.
     */
    private void post(StockTransaction.TransactionType type,
                      InventoryBatch batch,
                      int delta,
                      StockTransaction.ReferenceType referenceType,
                      UUID referenceId,
                      String note,
                      LocalDateTime now) {
        if (referenceType != null && referenceId == null) {
            throw new BusinessRuleException("referenceId is required when referenceType is given");
        }
        if (referenceType == null && referenceId != null) {
            throw new BusinessRuleException("referenceType is required when referenceId is given");
        }

        ledger.save(StockTransaction.builder()
                .id(UUID.randomUUID())
                .batchId(batch.getId())
                .transactionType(type)
                .quantityChanged(delta)
                .referenceType(referenceType)
                .referenceId(referenceId)
                .note(note == null || note.isBlank() ? null : note.trim())
                // Null for a sweep, which CurrentUserContext rejects a missing
                // caller for, so an anonymous human action cannot reach here.
                .performedBy(currentUser.idOrNull())
                .createdAt(now)
                .build());
    }

    private int balanceOf(UUID id) {
        return batches.findById(id).map(InventoryBatch::getQuantityAvailable).orElse(0);
    }

    /**
     * Marks a drained batch {@code DEPLETED}, so expiry and valuation queries skip
     * it without scanning zero-quantity rows.
     *
     * <p>Derived state, set here and nowhere else. {@code DEPLETED} is not a FEFO
     * candidate status, so this cannot affect dispensing — it only stops a drained
     * batch reading as dispensable stock.
     */
    private void markDepletedIfZero(InventoryBatch batch) {
        if (batch.getQuantityAvailable() == 0
                && batch.getStatus() == InventoryBatch.Status.ACTIVE) {
            batch.setStatus(InventoryBatch.Status.DEPLETED);
        }
    }

    private InventoryBatch require(UUID id) {
        return batches.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Inventory batch", id));
    }

    private InsufficientStockException insufficient(InventoryBatch batch, int requested, int available) {
        return new InsufficientStockException(describeFor(batch), requested, available);
    }

    /** A human name for the shortfall message, falling back to the batch number. */
    private String describeFor(InventoryBatch batch) {
        if (batch.getMedicineId() != null) {
            return medicines.findById(batch.getMedicineId())
                    .map(Medicine::getGenericName)
                    .orElseGet(() -> "batch " + batch.getBatchNumber());
        }
        if (batch.getItemId() != null) {
            return items.findById(batch.getItemId())
                    .map(NonMedicalItem::getName)
                    .orElseGet(() -> "batch " + batch.getBatchNumber());
        }
        return "batch " + batch.getBatchNumber();
    }

    private LocalDate cutoffFor(Integer expiringWithinDays, LocalDate today) {
        if (expiringWithinDays == null) {
            return null;
        }
        if (expiringWithinDays < 0) {
            throw new BusinessRuleException(
                    "expiringWithinDays must not be negative: %d", expiringWithinDays);
        }
        return today.plusDays(expiringWithinDays);
    }

    /**
     * {@code expiryDate <= cutoff}, or no predicate when no cutoff was asked for.
     *
     * <p>Written as {@code <=} so {@code expiringWithinDays=0} returns what expired
     * today and earlier. A strict {@code <} would silently exclude today's batches,
     * which is the boundary the alert screen cares about most.
     */
    private Specification<InventoryBatch> expiringBy(LocalDate cutoff) {
        return (root, query, cb) -> cutoff == null
                ? cb.conjunction()
                : cb.lessThanOrEqualTo(root.get("expiryDate"), cutoff);
    }

    private void validateDates(LocalDate manufacturing, LocalDate expiry, LocalDateTime now) {
        if (expiry == null) {
            throw new BusinessRuleException("expiryDate is required");
        }
        if (manufacturing != null && expiry.isBefore(manufacturing)) {
            throw new BusinessRuleException(
                    "expiryDate %s is before manufacturingDate %s", expiry, manufacturing);
        }
        if (expiry.isBefore(now.toLocalDate())) {
            throw new BusinessRuleException("expiryDate %s is in the past", expiry);
        }
    }

    /**
     * Rejects a batch for a deactivated product.
     *
     * <p>Stock for a withdrawn medicine is a real situation — a recall can
     * deactivate a catalogue row while its batches sit on the shelf — but receiving
     * <em>new</em> stock for it is not. Refusing here stops the error where it can
     * still be corrected, rather than leaving a dispense-from-a-withdrawn-medicine
     * finding for later.
     */
    private void validateParentIsActive(UUID medicineId, UUID itemId) {
        if (medicineId != null) {
            Medicine medicine = medicines.findById(medicineId)
                    .orElseThrow(() -> new ResourceNotFoundException("Medicine", medicineId));
            if (!Boolean.TRUE.equals(medicine.getIsActive())) {
                throw new BusinessRuleException(
                        "Medicine %s is deactivated and cannot receive stock",
                        medicine.getGenericName());
            }
        }
        if (itemId != null) {
            NonMedicalItem item = items.findById(itemId)
                    .orElseThrow(() -> new ResourceNotFoundException("Non-medical item", itemId));
            if (!Boolean.TRUE.equals(item.getIsActive())) {
                throw new BusinessRuleException(
                        "Non-medical item %s is deactivated and cannot receive stock",
                        item.getName());
            }
        }
    }

    private void validateSupplierIsActive(UUID supplierId) {
        if (supplierId == null) {
            return;
        }
        Supplier supplier = suppliers.findById(supplierId)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier", supplierId));
        if (!Boolean.TRUE.equals(supplier.getIsActive())) {
            throw new BusinessRuleException("Supplier %s is deactivated", supplier.getName());
        }
    }

    // ==================== mapping ====================

    private List<InventoryBatchResponse> decorate(List<InventoryBatch> rows) {
        Map<UUID, String> names = resolveNames(rows);
        return rows.stream().map(b -> toResponse(b, names)).toList();
    }

    /**
     * The single mapping point, and so the single place cost redaction can happen.
     */
    private InventoryBatchResponse toResponse(InventoryBatch batch, Map<UUID, String> names) {
        return InventoryBatchResponse.from(
                batch,
                names.get(batch.getMedicineId()),
                names.get(batch.getItemId()),
                names.get(batch.getSupplierId()),
                costVisible() ? batch.getCostPrice() : null,
                LocalDate.now(clock));
    }

    /**
     * @return whether the caller may see a cost price.
     *
     *         <p>Written as {@code role != CASHIER} rather than a whitelist of
     *         permitted roles, so a role added later gets a deliberate decision
     *         instead of silently inheriting either a redaction or a leak.
     */
    private boolean costVisible() {
        AuthenticatedUser caller = currentUser.find();
        return caller == null || caller.role() != User.Role.CASHIER;
    }

    /**
     * Resolves display names for a page of batches in three queries rather than
     * three per row.
     *
     * <p>A 20-row list would otherwise be 60 round trips. An unresolvable id is
     * simply absent from the map, so a batch pointing at a missing parent renders a
     * null name instead of failing the page.
     */
    private Map<UUID, String> resolveNames(List<InventoryBatch> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<UUID> medicineIds = distinctNonNull(rows, InventoryBatch::getMedicineId);
        List<UUID> itemIds = distinctNonNull(rows, InventoryBatch::getItemId);
        List<UUID> supplierIds = distinctNonNull(rows, InventoryBatch::getSupplierId);

        Map<UUID, String> names = new HashMap<>();
        if (!medicineIds.isEmpty()) {
            medicines.findAllById(medicineIds).forEach(m -> names.put(m.getId(), m.getGenericName()));
        }
        if (!itemIds.isEmpty()) {
            items.findAllById(itemIds).forEach(i -> names.put(i.getId(), i.getName()));
        }
        if (!supplierIds.isEmpty()) {
            suppliers.findAllById(supplierIds).forEach(s -> names.put(s.getId(), s.getName()));
        }
        return names;
    }

    /** Distinct non-null ids from {@code getter}, for batching a lookup. */
    private static <T> List<UUID> distinctNonNull(List<T> rows, Function<T, UUID> getter) {
        return rows.stream()
                .map(getter)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /** Resolves the display name of each actor across a page of ledger rows. */
    private Map<UUID, String> resolveActorNames(List<StockTransaction> rows) {
        List<UUID> actorIds = distinctNonNull(rows, StockTransaction::getPerformedBy);
        if (actorIds.isEmpty()) {
            return Map.of();
        }
        return actorIds.stream()
                .map(users::findById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toMap(User::getId, User::getFullName, (a, b) -> a));
    }
}
