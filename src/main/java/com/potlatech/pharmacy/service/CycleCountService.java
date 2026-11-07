package com.potlatech.pharmacy.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.potlatech.pharmacy.common.exception.BusinessRuleException;
import com.potlatech.pharmacy.common.exception.InvalidStateTransitionException;
import com.potlatech.pharmacy.common.exception.ResourceNotFoundException;
import com.potlatech.pharmacy.dto.cyclecount.CountLineRequest;
import com.potlatech.pharmacy.dto.cyclecount.CreateCycleCountRequest;
import com.potlatech.pharmacy.dto.cyclecount.CycleCountLineResponse;
import com.potlatech.pharmacy.dto.cyclecount.CycleCountResponse;
import com.potlatech.pharmacy.dto.cyclecount.PostCycleCountResponse;
import com.potlatech.pharmacy.entity.CycleCount;
import com.potlatech.pharmacy.entity.CycleCountLine;
import com.potlatech.pharmacy.entity.InventoryBatch;
import com.potlatech.pharmacy.entity.Medicine;
import com.potlatech.pharmacy.repository.CycleCountLineRepository;
import com.potlatech.pharmacy.repository.CycleCountRepository;
import com.potlatech.pharmacy.repository.InventoryAlertRepository;
import com.potlatech.pharmacy.repository.InventoryBatchRepository;
import com.potlatech.pharmacy.repository.MedicineRepository;
import com.potlatech.pharmacy.repository.projection.CycleCountCandidate;
import com.potlatech.pharmacy.security.CurrentUserContext;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Unit 09: cycle-count sessions.
 *
 * <p>The governing decision here is that a count is a <em>record with an author</em>,
 * not a stock edit. Everything below follows from that:
 *
 * <ul>
 *   <li>{@code systemQuantity} is snapshotted at creation, so a variance is
 *       measured against the books as they stood when counting began rather than
 *       as they stand at posting — otherwise a sale during the count is reported
 *       as a miscount;</li>
 *   <li>posting requires every line to be counted, because a half-finished count
 *       that posts would write off every shelf nobody reached;</li>
 *   <li>a zero-variance line writes no ledger row but is still recorded, because
 *       "we counted and the books were right" is a reportable outcome and an
 *       adjust-only flow records nothing at all in that case.</li>
 * </ul>
 */
@Service
public class CycleCountService {

    private final CycleCountRepository counts;
    private final CycleCountLineRepository lines;
    private final InventoryAlertRepository alerts;
    private final InventoryBatchRepository batches;
    private final MedicineRepository medicines;
    private final InventoryBatchService inventory;
    private final CurrentUserContext currentUser;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public CycleCountService(CycleCountRepository counts,
                             CycleCountLineRepository lines,
                             InventoryAlertRepository alerts,
                             InventoryBatchRepository batches,
                             MedicineRepository medicines,
                             InventoryBatchService inventory,
                             CurrentUserContext currentUser) {
        this(counts, lines, alerts, batches, medicines, inventory, currentUser,
                Clock.systemDefaultZone());
    }

    /**
     * Test seam, matching {@link InventoryBatchService}.
     *
     * <p>A cycle count is dated by the clock twice — the session's {@code created_at}
     * and its reference number's year — so pinning the clock is what makes
     * {@code CC-2026-000001} assertable at all.
     */
    CycleCountService(CycleCountRepository counts,
                      CycleCountLineRepository lines,
                      InventoryAlertRepository alerts,
                      InventoryBatchRepository batches,
                      MedicineRepository medicines,
                      InventoryBatchService inventory,
                      CurrentUserContext currentUser,
                      Clock clock) {
        this.counts = counts;
        this.lines = lines;
        this.alerts = alerts;
        this.batches = batches;
        this.medicines = medicines;
        this.inventory = inventory;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    // ==================== sessions ====================

    /**
     * Opens a count, snapshotting the balances of the batches it covers.
     *
     * <p>With no {@code medicineIds} this snapshots every ACTIVE and QUARANTINED
     * batch at the location — the ordinary case is a full shelf count. Named
     * medicines narrow it to a spot check.
     */
    @Transactional
    public CycleCountResponse create(CreateCycleCountRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        UUID countedBy = currentUser.require().id();
        String location = request.location().name();

        List<UUID> batchIds = request.batchIds() == null
                ? List.of() : request.batchIds();
        List<UUID> medicineIds = request.medicineIds() == null
                ? List.of() : request.medicineIds();

        // Deduplicated, because a batch named directly and again through its own
        // medicine would otherwise produce two lines and trip the unique
        // (cycle_count_id, batch_id) constraint — a 500 for a request that is
        // perfectly reasonable to make.
        Map<UUID, CycleCountCandidate> candidates = new LinkedHashMap<>();

        if (batchIds.isEmpty() && medicineIds.isEmpty()) {
            for (CycleCountCandidate c : alerts.findCountCandidates(location, null)) {
                candidates.put(c.getId(), c);
            }
        } else {
            if (!batchIds.isEmpty()) {
                for (CycleCountCandidate c : alerts.findCountCandidatesByBatchIds(
                        location, batchIds.toArray(UUID[]::new))) {
                    candidates.put(c.getId(), c);
                }
            }
            for (UUID medicineId : medicineIds) {
                for (CycleCountCandidate c : alerts.findCountCandidates(location, medicineId)) {
                    candidates.put(c.getId(), c);
                }
            }
        }

        List<CycleCountCandidate> selected = new ArrayList<>(candidates.values());

        if (selected.isEmpty()) {
            throw new BusinessRuleException(
                    "No countable batches at " + location
                            + " - a count of nothing is not a count");
        }

        CycleCount count = counts.save(CycleCount.builder()
                .id(UUID.randomUUID())
                .referenceNo(nextReferenceNo(now))
                .status(CycleCount.Status.OPEN)
                .location(location)
                .countedBy(countedBy)
                .notes(request.notes())
                .createdAt(now)
                .build());

        List<CycleCountLine> created = new ArrayList<>(selected.size());
        for (CycleCountCandidate candidate : selected) {
            created.add(CycleCountLine.builder()
                    .id(UUID.randomUUID())
                    .cycleCountId(count.getId())
                    .batchId(candidate.getId())
                    // The snapshot. See the class javadoc.
                    .systemQuantity(candidate.getQuantityAvailable() == null
                            ? 0 : candidate.getQuantityAvailable())
                    .build());
        }
        lines.saveAll(created);

        return toResponse(count, created);
    }

    @Transactional(readOnly = true)
    public Page<CycleCountResponse> list(CycleCount.Status status, Pageable pageable) {
        Page<CycleCount> page = status == null
                ? counts.findAllByOrderByCreatedAtDesc(pageable)
                : counts.findByStatusOrderByCreatedAtDesc(status, pageable);
        return page.map(c -> toResponse(c, lines.findByCycleCountIdOrderById(c.getId())));
    }

    @Transactional(readOnly = true)
    public CycleCountResponse get(UUID id) {
        CycleCount count = require(id);
        return toResponse(count, lines.findByCycleCountIdOrderById(id));
    }

    /**
     * Records a counted figure on one line.
     *
     * <p>PATCH, because setting the same figure twice must not read as two counts.
     * Only {@code countedQuantity} is writable: {@code systemQuantity} is the
     * snapshot the variance is measured against, and letting a client edit it
     * would let any variance be manufactured from nothing.
     */
    @Transactional
    public CycleCountLineResponse countLine(UUID countId, UUID lineId, CountLineRequest request) {
        CycleCount count = require(countId);
        requireOpen(count, "COUNTED");
        CycleCountLine line = lines.findByIdAndCycleCountId(lineId, countId)
                .orElseThrow(() -> new ResourceNotFoundException("Cycle count line", lineId));

        line.setCountedQuantity(request.countedQuantity());
        lines.save(line);
        return toLineResponses(List.of(line), count.getReferenceNo()).getFirst();
    }

    /**
     * Posts variances to the ledger and closes the session.
     *
     * <p>One transaction for the whole session, so a failure on the tenth shelf
     * leaves the first nine uncounted-in-the-ledger rather than half-applied. A
     * partially posted count is the worst outcome available here: the books would
     * show some variances applied, the count would still read OPEN, and repeating
     * it would double them.
     */
    @Transactional
    public PostCycleCountResponse post(UUID countId) {
        CycleCount count = require(countId);
        requireOpen(count, "POSTED");

        List<CycleCountLine> all = lines.findByCycleCountIdOrderById(countId);
        long uncounted = all.stream().filter(l -> !l.isCounted()).count();
        if (uncounted > 0) {
            // Explicitly refused rather than skipping the blanks. Skipping would
            // let a count covering 200 batches post after counting 3, and the
            // other 197 would be recorded as "correct" without being looked at.
            throw new BusinessRuleException(
                    uncounted + " of " + all.size()
                            + " batches have not been counted yet - finish the count before posting");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        int applied = 0;
        int shortages = 0;
        int surpluses = 0;
        int netUnits = 0;

        for (CycleCountLine line : all) {
            int variance = line.getCountedQuantity() - line.getSystemQuantity();
            line.setVariance(variance);
            if (variance == 0) {
                // Recorded, not written. The count happened; the books were right.
                continue;
            }
            inventory.applyCycleCountVariance(line.getBatchId(), variance, countId,
                    "Cycle count " + count.getReferenceNo());
            applied += 1;
            netUnits += variance;
            if (variance < 0) {
                shortages += 1;
            } else {
                surpluses += 1;
            }
        }

        lines.saveAll(all);

        count.setStatus(CycleCount.Status.POSTED);
        count.setPostedAt(now);
        count.setPostedBy(currentUser.require().id());
        counts.save(count);

        return new PostCycleCountResponse(
                count.getReferenceNo(),
                all.size(),
                applied,
                shortages,
                surpluses,
                netUnits);
    }

    // ==================== helpers ====================

    private CycleCount require(UUID id) {
        return counts.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Cycle count", id));
    }

    /**
     * Guards a write against a closed count.
     *
     * <p>{@code InvalidStateTransitionException} rather than a business rule,
     * because OPEN → POSTED is the session's state machine and reporting it as a
     * rule failure would hide a genuine lifecycle bug behind a message about
     * business logic.
     *
     * <p>The transition is named explicitly rather than derived from the caller's
     * wording. The first version built it from the action string and produced
     * "Cannot transition CycleCount from POSTED to POSTED ON POSTED", which reads
     * like a bug report rather than a diagnosis.
     */
    private void requireOpen(CycleCount count, String attempted) {
        if (count.isPosted()) {
            throw new InvalidStateTransitionException(
                    "CycleCount", CycleCount.Status.POSTED.name(), attempted);
        }
    }

    /**
     * {@code CC-2026-000001}, sequential within the year.
     *
     * <p>Uniqueness is enforced by the index, not here — two concurrent creations
     * can read the same maximum, and the loser gets a constraint violation. Which
     * is the correct outcome: a duplicate reference number would make a
     * discrepancy report cite two different events as the same one.
     */
    private String nextReferenceNo(LocalDateTime now) {
        String prefix = String.format("CC-%d-", now.getYear());
        int next = counts.maxSequence(prefix, prefix.length()) + 1;
        return prefix + String.format("%06d", next);
    }

    private CycleCountResponse toResponse(CycleCount count, List<CycleCountLine> lineList) {
        long variances = lineList.stream()
                .filter(l -> l.getCountedQuantity() != null)
                .filter(l -> l.getCountedQuantity() - l.getSystemQuantity() != 0)
                .count();
        long uncounted = lineList.stream().filter(l -> !l.isCounted()).count();
        boolean posted = count.isPosted();

        List<CycleCountLineResponse> lines = toLineResponses(lineList, count.getReferenceNo());

        return new CycleCountResponse(
                count.getId(),
                count.getReferenceNo(),
                count.getStatus().name(),
                count.getLocation(),
                count.getCountedBy(),
                count.getNotes(),
                count.getCreatedAt(),
                count.getPostedAt(),
                count.getPostedBy(),
                lineList.size(),
                (int) uncounted,
                (int) variances,
                // The lines that will produce a ledger row, not the lines in the
                // session: a 200-batch count with two discrepancies adjusts two
                // batches, and the button should say so.
                posted ? 0 : (int) variances,
                lines);
    }

    /**
     * Enriches a session's lines in two batched lookups.
     *
     * <p>A 200-batch count must not issue 400 queries, so the batches and the
     * medicines behind them are fetched once each and joined in memory.
     *
     * <p>The live quantity is read here, at render time, rather than stored — that
     * is the whole reason the line carries both {@code systemQuantity} and
     * {@code liveQuantity}. When a sale has landed since the session opened, the
     * counter sees why their variance does not reconcile with the shelf, instead of
     * being handed a number that appears to be arithmetic error.
     */
    private List<CycleCountLineResponse> toLineResponses(List<CycleCountLine> lineList,
                                                         String referenceNo) {
        if (lineList.isEmpty()) {
            return List.of();
        }
        List<UUID> batchIds = lineList.stream().map(CycleCountLine::getBatchId).distinct().toList();
        Map<UUID, InventoryBatch> byId = batches.findAllById(batchIds).stream()
                .collect(Collectors.toMap(InventoryBatch::getId, b -> b));

        List<UUID> medicineIds = byId.values().stream()
                .map(InventoryBatch::getMedicineId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, Medicine> medicineById = medicineIds.isEmpty()
                ? Map.of()
                : medicines.findAllById(medicineIds).stream()
                        .collect(Collectors.toMap(Medicine::getId, m -> m, (a, b) -> a));

        return lineList.stream()
                .map(line -> {
                    InventoryBatch batch = byId.get(line.getBatchId());
                    Medicine medicine = batch == null || batch.getMedicineId() == null
                            ? null
                            : medicineById.get(batch.getMedicineId());
                    return new CycleCountLineResponse(
                            line.getId().toString(),
                            line.getBatchId().toString(),
                            referenceNo,
                            batch == null ? null : batch.getBatchNumber(),
                            batch == null || batch.getMedicineId() == null
                                    ? null : batch.getMedicineId().toString(),
                            medicine == null ? "(non-medicine)" : medicine.getGenericName(),
                            medicine == null ? null : medicine.getDosageForm().name(),
                            batch == null ? null : batch.getStatus().name(),
                            line.getSystemQuantity(),
                            line.getCountedQuantity(),
                            batch == null ? null : batch.getQuantityAvailable(),
                            line.getCountedQuantity() == null
                                    ? null
                                    : line.getCountedQuantity() - line.getSystemQuantity(),
                            line.getVariance());
                })
                .toList();
    }
}
