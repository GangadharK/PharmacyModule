package com.potlatech.pharmacy.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import com.potlatech.pharmacy.common.exception.BusinessRuleException;
import com.potlatech.pharmacy.common.exception.InsufficientStockException;
import com.potlatech.pharmacy.common.exception.InvalidStateTransitionException;
import com.potlatech.pharmacy.common.exception.ResourceNotFoundException;
import com.potlatech.pharmacy.dto.cyclecount.CountLineRequest;
import com.potlatech.pharmacy.dto.cyclecount.CreateCycleCountRequest;
import com.potlatech.pharmacy.dto.cyclecount.CycleCountResponse;
import com.potlatech.pharmacy.dto.cyclecount.PostCycleCountResponse;
import com.potlatech.pharmacy.dto.inventory.CreateBatchRequest;
import com.potlatech.pharmacy.dto.inventory.FefoRequest;
import com.potlatech.pharmacy.entity.CycleCount;
import com.potlatech.pharmacy.entity.InventoryBatch;
import com.potlatech.pharmacy.entity.Medicine;
import com.potlatech.pharmacy.entity.StockTransaction;
import com.potlatech.pharmacy.entity.User;
import com.potlatech.pharmacy.repository.CycleCountLineRepository;
import com.potlatech.pharmacy.repository.CycleCountRepository;
import com.potlatech.pharmacy.repository.InventoryAlertRepository;
import com.potlatech.pharmacy.repository.InventoryBatchRepository;
import com.potlatech.pharmacy.repository.MedicineRepository;
import com.potlatech.pharmacy.repository.NonMedicalItemRepository;
import com.potlatech.pharmacy.repository.StockTransactionRepository;
import com.potlatech.pharmacy.repository.SupplierRepository;
import com.potlatech.pharmacy.repository.UserRepository;
import com.potlatech.pharmacy.security.AuthenticatedUser;
import com.potlatech.pharmacy.security.CurrentUserContext;
import com.potlatech.pharmacy.service.CatalogueSearchText;
import com.potlatech.pharmacy.service.CycleCountService;
import com.potlatech.pharmacy.service.InventoryBatchService;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cycle counts, against a real PostgreSQL database and the real services.
 *
 * <p>Real database for the same reason as {@code InventoryLedgerTest}: the guard
 * that matters most here — a variance may never drive a balance below zero — is a
 * predicate inside a bulk {@code UPDATE}, and a mock accepts whatever it is handed.
 * The test that matters most is {@link #saleDuringAnOpenCountIsNotAVariance()},
 * which a mock cannot express at all.
 *
 * <p>Flat rather than {@code @Nested}: every nested class re-creates a Spring
 * context, so grouping these four ways would have cost four context loads to gain
 * nothing but a heading.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
// Only TestConfig: it supplies both services as @Beans with an injected clock.
// Importing the @Service classes as well registers a second instance of each,
// and Spring then fails with NoUniqueBeanDefinitionException.
@Import(CycleCountServiceTest.TestConfig.class)
class CycleCountServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");
    private static final LocalDate FAR_FUTURE = LocalDate.of(2027, 10, 20);

    @Autowired CycleCountService counts;
    @Autowired InventoryBatchService inventory;
    @Autowired CycleCountRepository countRepo;
    @Autowired CycleCountLineRepository lineRepo;
    @Autowired InventoryBatchRepository batches;
    @Autowired StockTransactionRepository ledger;
    @Autowired MedicineRepository medicines;
    @Autowired StubUserContext userContext;

    @TestConfiguration
    static class TestConfig {

        @Bean StubUserContext stubUserContext() {
            return new StubUserContext();
        }

        /**
         * The clock is injected rather than defaulted so the reference number's year
         * is assertable, and so the suite asserts the same thing on every date it
         * runs rather than green by accident.
         */
        @Bean CycleCountService cycleCountService(
                CycleCountRepository counts,
                CycleCountLineRepository lines,
                InventoryAlertRepository alerts,
                InventoryBatchRepository batches,
                MedicineRepository medicines,
                InventoryBatchService inventory,
                StubUserContext userContext) {
            return new CycleCountService(counts, lines, alerts, batches, medicines, inventory,
                    userContext, Clock.fixed(NOW, ZoneOffset.UTC));
        }

        @Bean InventoryBatchService inventoryBatchService(
                InventoryBatchRepository batches,
                StockTransactionRepository ledger,
                MedicineRepository medicines,
                NonMedicalItemRepository items,
                SupplierRepository suppliers,
                UserRepository users,
                StubUserContext userContext) {
            return new InventoryBatchService(batches, ledger, medicines, items, suppliers, users,
                    userContext, Clock.fixed(NOW, ZoneOffset.UTC));
        }
    }

    static class StubUserContext implements CurrentUserContext {
        private AuthenticatedUser user = new AuthenticatedUser(
                UUID.fromString("3d9a4fc8-d93e-430f-a735-2bd51ff9c1de"),
                "manager", "Test Manager", User.Role.PHARMACY_MANAGER);

        @Override public AuthenticatedUser require() { return user; }
        @Override public AuthenticatedUser find() { return user; }
        @Override public UUID idOrNull() { return user == null ? null : user.id(); }
    }

    @BeforeEach
    void setUp() {
        userContext.user = new AuthenticatedUser(
                UUID.fromString("3d9a4fc8-d93e-430f-a735-2bd51ff9c1de"),
                "manager", "Test Manager", User.Role.PHARMACY_MANAGER);
    }

    // ================================================================ fixtures

    private Medicine newMedicine() {
        String name = "CCMed-" + UUID.randomUUID().toString().substring(0, 8);
        return medicines.save(Medicine.builder()
                .id(UUID.randomUUID())
                .genericName(name)
                .dosageForm(Medicine.DosageForm.TABLET)
                .scheduleClass(Medicine.ScheduleClass.H)
                .reorderLevel(10)
                .isActive(true)
                .searchBlob(CatalogueSearchText.forMedicine(name, null, null, null))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }

    private InventoryBatch receive(Medicine medicine, int quantity) {
        return receive(medicine, quantity, FAR_FUTURE);
    }

    private InventoryBatch receive(Medicine medicine, int quantity, LocalDate expiry) {
        var created = inventory.create(new CreateBatchRequest(
                medicine.getId(), null, null, "LOT-" + UUID.randomUUID().toString().substring(0, 6),
                null, expiry, quantity, new BigDecimal("10.00"), new BigDecimal("15.00"),
                InventoryBatch.Location.MAIN_PHARMACY, "delivery"));
        return batches.findById(created.id()).orElseThrow();
    }

    private CycleCountResponse openCount(Medicine... medicines) {
        return counts.create(new CreateCycleCountRequest(
                InventoryBatch.Location.MAIN_PHARMACY,
                null,
                medicines.length == 0
                        ? null
                        : Arrays.stream(medicines).map(Medicine::getId).toList(),
                "test count"));
    }

    private void count(CycleCountResponse count, InventoryBatch batch, int counted) {
        String lineId = count.lines().stream()
                .filter(l -> l.batchId().equals(batch.getId().toString()))
                .findFirst().orElseThrow().id();
        counts.countLine(count.id(), UUID.fromString(lineId), new CountLineRequest(counted));
    }

    private FefoRequest sell(Medicine medicine, int quantity) {
        return new FefoRequest(medicine.getId(), quantity,
                InventoryBatch.Location.MAIN_PHARMACY, null, null);
    }

    private int balanceOf(UUID batchId) {
        return batches.findById(batchId).orElseThrow().getQuantityAvailable();
    }

    /** The numeric tail of a {@code CC-2026-000123} reference. */
    private static int sequenceOf(String referenceNo) {
        return Integer.parseInt(referenceNo.substring(referenceNo.lastIndexOf('-') + 1));
    }

    /** Ledger rows of one type for a batch; the repo has no by-type finder. */
    private List<StockTransaction> txnsOfType(
            UUID batchId, StockTransaction.TransactionType type) {
        return ledger.findByBatchIdOrderByCreatedAtAsc(batchId).stream()
                .filter(t -> t.getTransactionType() == type)
                .toList();
    }

    /** The invariant, stated once. */
    private void assertLedgerAgreesWithBalance(UUID batchId) {
        assertThat(balanceOf(batchId))
                .as("balance must equal the ledger sum for batch %s", batchId)
                .isEqualTo((int) ledger.sumForBatch(batchId));
    }

    // ================================================================ opening

    @Test
    @DisplayName("opening a count snapshots the balance, and the snapshot is a number")
    void snapshotsSystemQuantity() {
        Medicine m = newMedicine();
        receive(m, 50);

        CycleCountResponse count = openCount(m);

        assertThat(count.referenceNo())
                .as("CC-<year>-<sequence>")
                .matches("CC-\\d{4}-\\d{6}");
        assertThat(count.status()).isEqualTo("OPEN");
        assertThat(count.lineCount()).isEqualTo(1);
        assertThat(count.uncountedLines()).isEqualTo(1);

        var stored = lineRepo.findById(
                UUID.fromString(count.lines().get(0).id())).orElseThrow();
        assertThat(stored.getSystemQuantity()).isEqualTo(50);
        assertThat(stored.getCountedQuantity())
                .as("nothing is counted until a human says so")
                .isNull();
    }

    @Test
    @DisplayName("counts a single named batch, without dragging in its siblings")
    void countsSpecificBatches() {
        Medicine m = newMedicine();
        InventoryBatch wanted = receive(m, 50);
        InventoryBatch sibling = receive(m, 50);

        CycleCountResponse count = counts.create(new CreateCycleCountRequest(
                InventoryBatch.Location.MAIN_PHARMACY,
                List.of(wanted.getId()),
                null,
                "recall check"));

        assertThat(count.lineCount())
                .as("a batch-level request is a spot check of that batch, so a "
                    + "sibling lot on the same shelf is not dragged in")
                .isEqualTo(1);
        assertThat(count.lines().getFirst().batchId()).isEqualTo(wanted.getId().toString());
        assertThat(sibling.getId().toString())
                .as("the sibling must not appear on the count at all")
                .isNotEqualTo(wanted.getId().toString());
    }

    @Test
    @DisplayName("a batch named twice, directly and through its medicine, is counted once")
    void deduplicatesOverlappingSelection() {
        Medicine m = newMedicine();
        InventoryBatch batch = receive(m, 50);

        CycleCountResponse count = counts.create(new CreateCycleCountRequest(
                InventoryBatch.Location.MAIN_PHARMACY,
                List.of(batch.getId()),
                List.of(m.getId()),
                "both ways"));

        assertThat(count.lineCount())
                .as("naming a batch and its medicine together is a reasonable request; "
                    + "without deduplication it produced two lines and a unique "
                    + "constraint violation, which a pharmacist cannot act on")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a count of nothing is refused")
    void refusesEmptyCount() {
        assertThatThrownBy(() -> counts.create(new CreateCycleCountRequest(
                InventoryBatch.Location.ICU, null, null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("No countable batches");
    }

    @Test
    @DisplayName("covers ACTIVE and QUARANTINED stock, not written-off or empty batches")
    void onlyCountsLiveStock() {
        Medicine m = newMedicine();
        // Expiries ordered so FEFO drains the one we want emptied: an earlier
        // expiry is taken first, and "depleted" has to actually be depleted rather
        // than merely holding a small quantity.
        InventoryBatch live = receive(m, 50, FAR_FUTURE);
        InventoryBatch depleted = receive(m, 5, FAR_FUTURE.minusDays(60));
        InventoryBatch quarantined = receive(m, 5, FAR_FUTURE.minusDays(30));

        inventory.issueStock(sell(m, 5));
        assertThat(balanceOf(depleted.getId()))
                .as("fixture sanity: this batch must really be empty")
                .isZero();

        // Written straight to the table, not received. The service refuses a past
        // expiry date — correctly, since nobody can receive stock that expired
        // before it arrived — and a batch becomes expired by time passing, so the
        // only honest way to stage one is to insert the state directly.
        // saveAndFlush, not save: with an assigned id Spring Data merges rather
        // than persists, so the INSERT stays queued and expireOne's findById — a
        // separate query — would not see the row.
        InventoryBatch expired = batches.saveAndFlush(InventoryBatch.builder()
                .id(UUID.randomUUID())
                .medicineId(m.getId())
                .batchNumber("LOT-EXPIRED")
                .expiryDate(LocalDate.of(2026, 1, 1))
                .quantityReceived(5)
                .quantityAvailable(0)
                .costPrice(new BigDecimal("10.00"))
                .sellingPrice(new BigDecimal("15.00"))
                .location(InventoryBatch.Location.MAIN_PHARMACY)
                .status(InventoryBatch.Status.EXPIRED)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());

        inventory.quarantine(quarantined.getId(), "recalled");

        List<String> covered = openCount(m).lines().stream().map(l -> l.batchId()).toList();

        assertThat(covered).contains(live.getId().toString());
        assertThat(covered).contains(quarantined.getId().toString());
        assertThat(covered)
                .as("written-off stock cannot be recounted into existence")
                .doesNotContain(expired.getId().toString());
        assertThat(covered).doesNotContain(depleted.getId().toString());
    }

    // ================================================================ the snapshot decision

    @Test
    @DisplayName("a sale during an open count is not reported as a miscount")
    void saleDuringAnOpenCountIsNotAVariance() {
        Medicine m = newMedicine();
        InventoryBatch batch = receive(m, 50);
        CycleCountResponse count = openCount(m);

        // The shelf is counted correctly, then a sale goes through before the post.
        count(count, batch, 50);
        inventory.issueStock(sell(m, 10));

        PostCycleCountResponse result = counts.post(count.id());

        assertThat(result.variancesApplied())
                .as("the shelf was right when counted; the sale explains the other 10")
                .isZero();
        assertThat(result.netUnitChange()).isZero();
        assertThat(balanceOf(batch.getId()))
                .as("posting must not re-add the 10 the sale legitimately took")
                .isEqualTo(40);
        assertThat(txnsOfType(batch.getId(), StockTransaction.TransactionType.CYCLE_COUNT_ADJUST))
                .as("a confirmed count writes no ledger row")
                .isEmpty();
        assertLedgerAgreesWithBalance(batch.getId());
    }

    @Test
    @DisplayName("shows the live balance beside the snapshot, so the gap is explainable")
    void exposesLiveQuantityNextToSnapshot() {
        Medicine m = newMedicine();
        InventoryBatch batch = receive(m, 50);
        CycleCountResponse count = openCount(m);

        count(count, batch, 50);
        inventory.issueStock(sell(m, 10));

        var line = counts.get(count.id()).lines().get(0);

        assertThat(line.systemQuantity()).isEqualTo(50);
        assertThat(line.liveQuantity())
                .as("the counter must be able to see why the numbers differ")
                .isEqualTo(40);
        assertThat(line.variance()).as("measured against the snapshot").isZero();
    }

    // ================================================================ posting

    @Test
    @DisplayName("posting moves stock, writes one row per variance, and points it at the count")
    void appliesVarianceWithTraceableReference() {
        Medicine m = newMedicine();
        InventoryBatch a = receive(m, 50);
        InventoryBatch b = receive(m, 50);

        CycleCountResponse count = openCount(m);
        count(count, a, 45);
        count(count, b, 55);

        PostCycleCountResponse result = counts.post(count.id());

        assertThat(result.variancesApplied()).isEqualTo(2);
        assertThat(result.shortages()).isEqualTo(1);
        assertThat(result.surpluses()).isEqualTo(1);
        assertThat(balanceOf(a.getId())).isEqualTo(45);
        assertThat(balanceOf(b.getId())).isEqualTo(55);

        var row = txnsOfType(a.getId(),
                StockTransaction.TransactionType.CYCLE_COUNT_ADJUST).getFirst();
        assertThat(row.getQuantityChanged()).isEqualTo(-5);
        assertThat(row.getReferenceType())
                .isEqualTo(StockTransaction.ReferenceType.CYCLE_COUNT);
        assertThat(row.getReferenceId())
                .as("the reference_id column finally has something to point at")
                .isEqualTo(count.id());
        assertThat(row.getNote()).contains(count.referenceNo());
        assertLedgerAgreesWithBalance(a.getId());
        assertLedgerAgreesWithBalance(b.getId());
    }

    @Test
    @DisplayName("types a shortage CYCLE_COUNT_ADJUST, never DAMAGE_WRITE_OFF")
    void shortageIsNotFiledAsDamage() {
        Medicine m = newMedicine();
        InventoryBatch batch = receive(m, 50);

        CycleCountResponse count = openCount(m);
        count(count, batch, 40);
        counts.post(count.id());

        assertThat(txnsOfType(batch.getId(), StockTransaction.TransactionType.DAMAGE_WRITE_OFF))
                .as("a stocktaking discrepancy is not breakage; filing it as damage would "
                    + "move a bookkeeping error into the damage report")
                .isEmpty();
        assertThat(txnsOfType(batch.getId(), StockTransaction.TransactionType.CYCLE_COUNT_ADJUST))
                .hasSize(1);
    }

    @Test
    @DisplayName("reports shortages and surpluses gross, never netted to zero")
    void doesNotNetAwayTheSignal() {
        Medicine m = newMedicine();
        InventoryBatch a = receive(m, 50);
        InventoryBatch b = receive(m, 50);

        CycleCountResponse count = openCount(m);
        count(count, a, 45);
        count(count, b, 55);

        PostCycleCountResponse result = counts.post(count.id());

        assertThat(result.netUnitChange())
                .as("zero net hides that two shelves were each wrong")
                .isZero();
        assertThat(result.shortages()).isEqualTo(1);
        assertThat(result.surpluses()).isEqualTo(1);
    }

    @Test
    @DisplayName("counts pending variances, not lines, so the post button can be labelled")
    void pendingVariancesCountsAdjustmentsNotLines() {
        Medicine m = newMedicine();
        InventoryBatch a = receive(m, 50);
        InventoryBatch b = receive(m, 50);
        InventoryBatch c = receive(m, 50);
        InventoryBatch d = receive(m, 50);

        CycleCountResponse count = openCount(m);
        count(count, a, 45);   // -5, a variance
        count(count, b, 50);   // matches, no variance
        count(count, c, 55);   // +5, a variance
        // d stays uncounted

        CycleCountResponse session = counts.get(count.id());

        assertThat(session.lineCount()).isEqualTo(4);
        assertThat(session.varianceLines())
                .as("counted lines differing from the snapshot")
                .isEqualTo(2);
        assertThat(session.uncountedLines()).isEqualTo(1);
        assertThat(session.pendingVariances())
                .as("posting adjusts the two discrepant batches, not all four lines — "
                    + "a label of '4 variances' on a two-adjustment post is a lie the "
                    + "pharmacist reads before confirming")
                .isEqualTo(2);

        // Finish the count. Posting while d is uncounted is refused, correctly, so
        // the "zero once written" half needs the session complete.
        count(count, d, 50);
        PostCycleCountResponse result = counts.post(count.id());
        assertThat(result.variancesApplied()).isEqualTo(2);
        assertThat(counts.get(count.id()).pendingVariances())
                .as("and zero afterwards, because they have been written to the ledger")
                .isZero();
    }
    @Test
    @DisplayName("refuses while any line is uncounted, and says how many")
    void refusesUncountedLines() {
        Medicine m = newMedicine();
        InventoryBatch a = receive(m, 50);
        receive(m, 50);

        CycleCountResponse count = openCount(m);
        count(count, a, 50);

        assertThatThrownBy(() -> counts.post(count.id()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("1 of 2")
                .hasMessageContaining("not been counted");

        assertThat(balanceOf(a.getId()))
                .as("a half-finished count must not post what it did manage to count")
                .isEqualTo(50);
    }

    /**
     * The failure is atomic, and only half of that is assertable here.
     *
     * <p>Assertable: the exception, and the session never reaching POSTED — the
     * status is set after the loop, so a line 4 of 10 failing means it is never
     * written at all.
     *
     * <p>Not assertable in this test: the balances afterwards. The service's
     * transaction joins the test's, so the failure marks it rollback-only — and a
     * read after that still sees the <em>uncommitted</em> writes from the lines
     * that did succeed. The database rolls back correctly when the test ends; the
     * assertion is simply looking at the wrong moment. {@code InventoryLedgerTest}
     * can assert a balance after a refusal only because nothing was written
     * before the failure, so there is no intermediate state to misread.
     *
     * <p>The real rollback was verified against the running application, where the
     * post failed mid-session and the balance, the ledger and the status were all
     * re-read afterwards from a committed transaction.
     */
    @Test
    @DisplayName("stops at the failing line and never marks the session posted")
    void isAtomicAcrossLines() {
        Medicine m = newMedicine();
        // Distinct expiries, so FEFO takes from b and the scenario is deterministic.
        // With both sharing an expiry the sale came off whichever batch sorted
        // first, and the impossible variance was quietly affordable.
        InventoryBatch a = receive(m, 50, FAR_FUTURE);
        InventoryBatch b = receive(m, 10, FAR_FUTURE.minusDays(30));

        // Order matters. The snapshot has to be taken *before* the sale, so b is
        // recorded as 10 while its live balance falls to 5. A sale before the count
        // would be captured in the snapshot too, the variance would be exactly
        // affordable, and nothing would fail.
        CycleCountResponse count = openCount(m);
        inventory.issueStock(sell(m, 5));

        count(count, a, 40);   // valid, -10 against a live 50
        count(count, b, 0);    // -10 against a live 5: impossible

        assertThatThrownBy(() -> counts.post(count.id()))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(countRepo.findById(count.id()).orElseThrow().getStatus())
                .as("the session stays open for a recount")
                .isEqualTo(CycleCount.Status.OPEN);
    }

    @Test
    @DisplayName("a confirmed count with no variance posts nothing but still closes")
    void zeroVarianceStillClosesTheSession() {
        Medicine m = newMedicine();
        InventoryBatch batch = receive(m, 50);

        CycleCountResponse count = openCount(m);
        count(count, batch, 50);

        PostCycleCountResponse result = counts.post(count.id());

        assertThat(result.variancesApplied()).isZero();
        assertThat(countRepo.findById(count.id()).orElseThrow().getStatus())
                .as("'we counted and the books were right' is a reportable outcome")
                .isEqualTo(CycleCount.Status.POSTED);
        assertLedgerAgreesWithBalance(batch.getId());
    }

    // ================================================================ lifecycle

    @Test
    @DisplayName("a posted session is terminal for both counting and re-posting")
    void postedIsTerminal() {
        Medicine m = newMedicine();
        InventoryBatch batch = receive(m, 50);
        CycleCountResponse count = openCount(m);
        count(count, batch, 50);
        counts.post(count.id());

        String lineId = count.lines().get(0).id();

        assertThatThrownBy(() -> counts.post(count.id()))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("POSTED");
        assertThatThrownBy(() -> counts.countLine(
                count.id(), UUID.fromString(lineId), new CountLineRequest(3)))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThat(balanceOf(batch.getId()))
                .as("and the closure is real, not cosmetic")
                .isEqualTo(50);
    }

    @Test
    @DisplayName("a zero variance is refused as a no-op, not recorded as an event")
    void rejectsZeroVariance() {
        InventoryBatch batch = receive(newMedicine(), 50);

        assertThatThrownBy(() -> inventory.applyCycleCountVariance(
                batch.getId(), 0, UUID.randomUUID(), "unreachable from the service"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("must not be zero");
    }

    @Test
    @DisplayName("404s an unknown count, and an unknown line on a real one")
    void unknownIds() {
        assertThatThrownBy(() -> counts.get(UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);

        Medicine m = newMedicine();
        receive(m, 10);
        CycleCountResponse count = openCount(m);

        assertThatThrownBy(() -> counts.countLine(
                count.id(), UUID.randomUUID(), new CountLineRequest(1)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("numbers sequentially, and never reuses a number")
    void referenceNumbersAreSequential() {
        Medicine m = newMedicine();
        receive(m, 10);

        // Relative, not absolute. The dev database carries counts from live runs,
        // and a hardcoded first number would be a claim about the machine rather
        // than about the code — it would pass on a clean checkout and fail on
        // every developer's own database.
        String first = openCount(m).referenceNo();
        String second = openCount(m).referenceNo();
        String third = openCount(m).referenceNo();

        assertThat(first).matches("CC-\\d{4}-\\d{6}");
        assertThat(sequenceOf(second)).isEqualTo(sequenceOf(first) + 1);
        assertThat(sequenceOf(third)).isEqualTo(sequenceOf(first) + 2);
        assertThat(countRepo.findByReferenceNo(second))
                .as("a gap is acceptable; a duplicate is not, because a discrepancy "
                    + "report would cite two events as the same one")
                .isPresent();
    }
}
