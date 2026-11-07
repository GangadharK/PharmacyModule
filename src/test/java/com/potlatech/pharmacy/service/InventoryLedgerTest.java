package com.potlatech.pharmacy.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.potlatech.pharmacy.dto.inventory.AdjustStockRequest;
import com.potlatech.pharmacy.dto.inventory.CreateBatchRequest;
import com.potlatech.pharmacy.dto.inventory.InventoryBatchResponse;
import com.potlatech.pharmacy.dto.inventory.ReconciliationResponse;
import com.potlatech.pharmacy.entity.InventoryBatch;
import com.potlatech.pharmacy.entity.Medicine;
import com.potlatech.pharmacy.entity.StockTransaction;
import com.potlatech.pharmacy.entity.User;
import com.potlatech.pharmacy.repository.InventoryBatchRepository;
import com.potlatech.pharmacy.repository.MedicineRepository;
import com.potlatech.pharmacy.repository.StockTransactionRepository;
import com.potlatech.pharmacy.security.AuthenticatedUser;
import com.potlatech.pharmacy.security.CurrentUserContext;
import com.potlatech.pharmacy.service.CatalogueSearchText;
import com.potlatech.pharmacy.service.InventoryBatchService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The stock ledger, tested against a real PostgreSQL database through the real
 * service.
 *
 * <p><strong>Why a real database and not mocks.</strong> The property under test
 * is {@code SUM(quantity_changed) == quantity_available}, and half of it lives in
 * the {@code UPDATE}'s {@code WHERE} clause. A mocked repository accepts whatever
 * it is handed, so a mocked test asserts only that the service called a method — it
 * cannot tell a correct post-condition from an inverted one. The guard that
 * actually prevents negative stock is a single predicate the database evaluates;
 * testing it without a database tests nothing.
 *
 * <p>Each test asserts the ledger sum, not just the balance. A balance that is
 * right for the wrong reason — written directly, with the ledger row missing — is
 * exactly the corruption this whole design exists to make detectable, so asserting
 * the balance alone would pass on the broken implementation.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional
@Import(InventoryLedgerTest.TestConfig.class)
class InventoryLedgerTest {

    /**
     * "Now" for the whole suite, pinned so the expiry cases mean the same thing on
     * every run. {@link TestConfig} feeds it to the service.
     */
    private static final Instant NOW = Instant.parse("2026-06-01T09:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 1);

    /**
     * The same instant as a {@link LocalDateTime}, for fixture rows.
     *
     * <p>Fixture timestamps must come from the pinned clock, not
     * {@code LocalDateTime.now()}. The service stamps rows with its injected
     * clock, so a fixture using the wall clock writes a <em>later</em>
     * {@code createdAt} than the sweep does, the history comes back in the wrong
     * order, and {@code get(1)} is the receipt rather than the write-off. The
     * failure reads like a missing ledger row when nothing is missing at all -
     * which is why this is pinned rather than left to the system clock.
     */
    private static final LocalDateTime NOW_DT = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    /** The actor id used for hand-built ledger rows, independent of the caller. */
    private static final UUID ACTOR_ID = UUID.fromString("3d9a4fc8-d93e-430f-a735-2bd51ff9c1de");

    @Autowired
    private InventoryBatchService service;

    @Autowired
    private InventoryBatchRepository batches;

    @Autowired
    private StockTransactionRepository ledger;

    @Autowired
    private MedicineRepository medicines;

    @Autowired
    private StubUserContext userContext;

    @PersistenceContext
    private EntityManager em;

    /**
     * Re-reads a batch from the database, bypassing the first-level cache.
     *
     * <p><strong>This exists because asserting a balance or a status through
     * {@code findById} in a {@code @DataJpaTest} can be a lie.</strong> The test
     * runs inside one transaction with one persistence context, so
     * {@code findById} may hand back the very instance the service mutated in
     * memory — whether or not anything was ever flushed. An assertion on that
     * object passes even when the database was never written, which is precisely
     * how {@code DEPLETED} and {@code EXPIRED} came to be set in code and never
     * reach the table.
     *
     * <p>{@code flush()} pushes pending changes, {@code clear()} drops the cache,
     * and only then does {@code findById} issue real SQL. Every assertion about
     * what was <em>persisted</em> goes through here.
     */
    private InventoryBatch reloaded(UUID id) {
        // Only flush when a transaction is actually active. The expiry tests run
        // NOT_SUPPORTED — the service has already committed in its own REQUIRES_NEW
        // transaction — and flush() without one is an error. There is nothing
        // pending to push in that case, so skipping it is correct rather than
        // merely convenient.
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()) {
            em.flush();
        }
        em.clear();
        return batches.findById(id).orElseThrow();
    }

    // ================================================================ fixtures

    /**
     * Supplies the service with a <em>fixed</em> clock, and a caller the test can
     * retarget.
     *
     * <p>The clock is not a convenience here. {@code @DataJpaTest} would otherwise
     * wire the service through its {@code @Autowired} constructor and take
     * {@code Clock.systemDefaultZone()}, so "today" would be whenever the suite
     * happened to run. The expiry tests then fail on a real Tuesday and pass on
     * the fixtures' dates, which is the worst kind of test: it is green for a
     * reason that has nothing to do with the code. Building the bean here pins
     * "today" to a constant, so the expiry cases assert the same thing every run.
     */
    @TestConfiguration
    static class TestConfig {

        @Bean
        StubUserContext stubUserContext() {
            return new StubUserContext();
        }

        @Bean
        InventoryBatchService inventoryBatchService(
                InventoryBatchRepository batches,
                StockTransactionRepository ledger,
                MedicineRepository medicines,
                com.potlatech.pharmacy.repository.NonMedicalItemRepository items,
                com.potlatech.pharmacy.repository.SupplierRepository suppliers,
                com.potlatech.pharmacy.repository.UserRepository users,
                StubUserContext userContext) {
            return new InventoryBatchService(batches, ledger, medicines, items, suppliers, users,
                    userContext, Clock.fixed(NOW, ZoneOffset.UTC));
        }
    }

    /**
     * A {@link CurrentUserContext} the test can retarget.
     *
     * <p>Not a mock: a mock's {@code idOrNull()} returns {@code null} by default,
     * which is exactly the value the sweep needs, so a test using one could pass
     * for the wrong reason — it would look like a system action when it was simply
     * an unstubbed call.
     */
    static class StubUserContext implements CurrentUserContext {
        private AuthenticatedUser user = new AuthenticatedUser(
                UUID.fromString("3d9a4fc8-d93e-430f-a735-2bd51ff9c1de"),
                "pharmacist", "Test Pharmacist", User.Role.PHARMACIST);

        /**
         * @param role the role to switch to. Reads the id and name from the current
         *             user when there is one, so a previous test having called
         *             {@link #clear()} cannot make the reset itself throw - which
         *             would fail every later test for a reason that has nothing to
         *             do with what they assert.
         */
        void setRole(User.Role role) {
            user = new AuthenticatedUser(
                    user == null ? UUID.fromString("3d9a4fc8-d93e-430f-a735-2bd51ff9c1de") : user.id(),
                    "pharmacist", "Test Pharmacist", role);
        }

        void clear() {
            user = null;
        }

        @Override
        public AuthenticatedUser require() {
            if (user == null) {
                throw new IllegalStateException("no user");
            }
            return user;
        }

        @Override
        public AuthenticatedUser find() {
            return user;
        }

        @Override
        public UUID idOrNull() {
            return user == null ? null : user.id();
        }
    }

    @BeforeEach
    void setUp() {
        userContext.setRole(User.Role.PHARMACIST);
    }

    private Medicine newMedicine() {
        String name = "TestMed-" + UUID.randomUUID().toString().substring(0, 8);
        return medicines.save(Medicine.builder()
                .id(UUID.randomUUID())
                .genericName(name)
                .dosageForm(Medicine.DosageForm.TABLET)
                .scheduleClass(Medicine.ScheduleClass.H)
                .isActive(true)
                // search_blob is NOT NULL and is derived, so the fixture computes
                // it with the same helper the service uses rather than inventing
                // a value. A fixture that skipped it would fail on a schema
                // constraint and tell us nothing about the ledger.
                .searchBlob(CatalogueSearchText.forMedicine(name, null, null, null))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }

    private CreateBatchRequest receive(Medicine medicine, int quantity, LocalDate expiry) {
        return new CreateBatchRequest(
                medicine.getId(), null, null, "LOT-" + UUID.randomUUID().toString().substring(0, 6),
                null, expiry, quantity, new BigDecimal("10.00"), new BigDecimal("15.00"),
                InventoryBatch.Location.MAIN_PHARMACY, "delivery");
    }

    /** The invariant, stated once. */
    private void assertLedgerAgreesWithBalance(UUID batchId) {
        long fromLedger = ledger.sumForBatch(batchId);
        int balance = batches.findById(batchId).orElseThrow().getQuantityAvailable();
        assertThat(balance)
                .as("balance must equal the ledger sum for batch %s", batchId)
                .isEqualTo((int) fromLedger);
    }

    // ================================================================ receipt

    @Nested
    @DisplayName("receiving a delivery")
    class Receiving {

        @Test
        @DisplayName("writes the opening ledger row and agrees with the balance from the start")
        void receiptIsRecorded() {
            Medicine medicine = newMedicine();
            InventoryBatchResponse created = service.create(receive(medicine, 50, TODAY.plusYears(1)));

            assertThat(created.quantityAvailable()).isEqualTo(50);
            assertThat(created.quantityReceived()).isEqualTo(50);
            assertThat(created.status()).isEqualTo(InventoryBatch.Status.ACTIVE);

            List<StockTransaction> rows = ledger.findByBatchIdOrderByCreatedAtAsc(created.id());
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getTransactionType())
                    .isEqualTo(StockTransaction.TransactionType.PURCHASE_IN);
            assertThat(rows.get(0).getQuantityChanged()).isEqualTo(50);
            assertThat(rows.get(0).getPerformedBy())
                    .as("a human receipt must name its actor")
                    .isEqualTo(userContext.find().id());

            assertLedgerAgreesWithBalance(created.id());
            assertThat(service.reconcile().mismatches()).isEmpty();
        }

        @Test
        @DisplayName("refuses a zero-quantity receipt rather than creating a paper batch")
        void zeroQuantityIsRefused() {
            Medicine medicine = newMedicine();
            CreateBatchRequest request =
                    new CreateBatchRequest(medicine.getId(), null, null, "LOT-EMPTY", null,
                            TODAY.plusYears(1), 0, BigDecimal.ONE, BigDecimal.TEN,
                            InventoryBatch.Location.MAIN_PHARMACY, null);

            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOf(com.potlatech.pharmacy.common.exception.BusinessRuleException.class)
                    .hasMessageContaining("quantityReceived");
        }

        @Test
        @DisplayName("refuses a batch for a deactivated medicine")
        void deactivatedMedicineIsRefused() {
            Medicine medicine = newMedicine();
            medicine.setIsActive(false);
            medicines.save(medicine);

            assertThatThrownBy(() -> service.create(receive(medicine, 10, TODAY.plusYears(1))))
                    .isInstanceOf(com.potlatech.pharmacy.common.exception.BusinessRuleException.class)
                    .hasMessageContaining("deactivated");
        }

        @Test
        @DisplayName("refuses an expiry date in the past")
        void pastExpiryIsRefused() {
            Medicine medicine = newMedicine();

            assertThatThrownBy(() -> service.create(receive(medicine, 10, TODAY.minusDays(1))))
                    .isInstanceOf(com.potlatech.pharmacy.common.exception.BusinessRuleException.class)
                    .hasMessageContaining("past");
        }

        @Test
        @DisplayName("refuses to attach a batch to two products at once")
        void bothParentsIsRefused() {
            Medicine medicine = newMedicine();
            CreateBatchRequest request = new CreateBatchRequest(
                    medicine.getId(), UUID.randomUUID(), null, "LOT-BOTH", null,
                    TODAY.plusYears(1), 5, BigDecimal.ONE, BigDecimal.TEN,
                    InventoryBatch.Location.MAIN_PHARMACY, null);

            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOf(com.potlatech.pharmacy.common.exception.BusinessRuleException.class)
                    .hasMessageContaining("mutually exclusive");
        }
    }

    // ================================================================ deduction

    @Nested
    @DisplayName("deducting stock")
    class Deduction {

        @Test
        @DisplayName("a deduction that would go negative is refused, and leaves no trace")
        void overDeductionLeavesNoTrace() {
            Medicine medicine = newMedicine();
            UUID id = service.create(receive(medicine, 50, TODAY.plusYears(1))).id();

            assertThatThrownBy(() -> service.adjust(id, new AdjustStockRequest(
                    -999, "breakage", null, null)))
                    .isInstanceOf(com.potlatech.pharmacy.common.exception.InsufficientStockException.class)
                    .hasMessageContaining("available 50");

            assertThat(batches.findById(id).orElseThrow().getQuantityAvailable())
                    .as("the balance must be untouched after a refused deduction")
                    .isEqualTo(50);
            assertThat(ledger.findByBatchIdOrderByCreatedAtAsc(id))
                    .as("a refused deduction must not leave a ledger row behind")
                    .hasSize(1);
        }

        /**
         * The database is the guard, tested without the service in the way.
         *
         * <p>Deliberately bypasses {@code adjust}'s Java-side check to prove the
         * post-condition is a real constraint and not a redundant second copy. If
         * this only worked because the service checked first, removing the
         * {@code WHERE} clause would be invisible to every other test here — which
         * is the failure mode that makes concurrency bugs ship.
         */
        @Test
        @DisplayName("the non-negative post-condition holds in SQL, not only in Java")
        void postConditionLivesInTheDatabase() {
            Medicine medicine = newMedicine();
            UUID id = service.create(receive(medicine, 10, TODAY.plusYears(1))).id();

            int applied = batches.applyDelta(id, -10, LocalDateTime.now());
            assertThat(applied).as("exactly -10 of 10 is allowed").isEqualTo(1);

            int refused = batches.applyDelta(id, -1, LocalDateTime.now());
            assertThat(refused)
                    .as("one more unit from a drained batch must match zero rows")
                    .isEqualTo(0);

            assertThat(batches.findById(id).orElseThrow().getQuantityAvailable()).isZero();
        }

        @Test
        @DisplayName("a zero adjustment is refused: a no-op is not an event")
        void zeroDeltaIsRefused() {
            Medicine medicine = newMedicine();
            UUID id = service.create(receive(medicine, 10, TODAY.plusYears(1))).id();

            assertThatThrownBy(() -> service.adjust(id,
                    new AdjustStockRequest(0, "nothing happened", null, null)))
                    .isInstanceOf(com.potlatech.pharmacy.common.exception.BusinessRuleException.class)
                    .hasMessageContaining("must not be zero");
        }

        @Test
        @DisplayName("a receipt, a write-off and a surplus each leave the ledger in step")
        void mixedSequenceReconciles() {
            Medicine medicine = newMedicine();
            UUID id = service.create(receive(medicine, 100, TODAY.plusYears(1))).id();

            service.adjust(id, new AdjustStockRequest(-12, "damaged", null, null));
            service.adjust(id, new AdjustStockRequest(3, "found in another box", null, null));
            service.adjust(id, new AdjustStockRequest(-40, "dispensed", null, null));

            assertThat(batches.findById(id).orElseThrow().getQuantityAvailable()).isEqualTo(51);
            assertLedgerAgreesWithBalance(id);
            assertThat(service.reconcile().mismatches()).isEmpty();
        }

        @Test
        @DisplayName("draining a batch marks it DEPLETED, and it stops being a FEFO candidate")
        void drainingMarksDepleted() {
            Medicine medicine = newMedicine();
            UUID id = service.create(receive(medicine, 5, TODAY.plusYears(1))).id();

            InventoryBatchResponse after = service.adjust(id, new AdjustStockRequest(-5, "sold", null, null));

            assertThat(after.quantityAvailable()).isZero();
            assertThat(after.status())
                    .as("a drained batch must not keep reading as dispensable")
                    .isEqualTo(InventoryBatch.Status.DEPLETED);
            assertThat(reloaded(id).getStatus())
                    .as("DEPLETED must be persisted, not just set on a detached object")
                    .isEqualTo(InventoryBatch.Status.DEPLETED);
            // The old derived FEFO read was tied to Unit 05's derived query;
            // Unit 06 introduces a locked, native FEFO query. The assertion stays
            // the same intent: the batch is no longer a dispensable ACTIVE candidate.
            assertThat(batches.findFefoCandidates(medicine.getId(), "MAIN_PHARMACY", TODAY))
                    .as("a DEPLETED batch must not appear in the FEFO candidate set")
                    .isEmpty();
        }
    }

    // ================================================================ state

    @Nested
    @DisplayName("quarantine and release")
    class QuarantineAndRelease {

        @Test
        @DisplayName("both are zero-delta, recorded, and keep the ledger reconciling")
        void quarantineAndReleaseAreZeroDelta() {
            Medicine medicine = newMedicine();
            UUID id = service.create(receive(medicine, 20, TODAY.plusYears(1))).id();

            InventoryBatchResponse quarantined = service.quarantine(id, "Recall notice 2026-11");
            assertThat(quarantined.status()).isEqualTo(InventoryBatch.Status.QUARANTINED);
            assertThat(quarantined.quantityAvailable())
                    .as("quarantine must not move stock")
                    .isEqualTo(20);

            InventoryBatchResponse released = service.release(id, "Recall cleared");
            assertThat(released.status()).isEqualTo(InventoryBatch.Status.ACTIVE);

            var history = service.history(id);
            assertThat(history).hasSize(3);
            assertThat(history.get(1).transactionType())
                    .isEqualTo(StockTransaction.TransactionType.QUARANTINE);
            assertThat(history.get(1).quantityChanged()).isZero();
            assertThat(history.get(1).note()).isEqualTo("Recall notice 2026-11");
            assertThat(history.get(2).transactionType())
                    .isEqualTo(StockTransaction.TransactionType.RELEASE);
            assertThat(history.get(2).quantityChanged()).isZero();

            assertLedgerAgreesWithBalance(id);
            assertThat(service.reconcile().mismatches()).isEmpty();
        }

        @Test
        @DisplayName("a quarantined batch cannot be adjusted, and a live one cannot be released")
        void invalidTransitionsAreRefused() {
            Medicine medicine = newMedicine();
            UUID id = service.create(receive(medicine, 20, TODAY.plusYears(1))).id();

            assertThatThrownBy(() -> service.release(id, "premature"))
                    .isInstanceOf(com.potlatech.pharmacy.common.exception.InvalidStateTransitionException.class);

            service.quarantine(id, "held");

            assertThatThrownBy(() -> service.adjust(id, new AdjustStockRequest(-1, "nope", null, null)))
                    .isInstanceOf(com.potlatech.pharmacy.common.exception.InvalidStateTransitionException.class);
        }

        @Test
        @DisplayName("a quarantine without a reason is refused")
        void quarantineNeedsAReason() {
            Medicine medicine = newMedicine();
            UUID id = service.create(receive(medicine, 20, TODAY.plusYears(1))).id();

            assertThatThrownBy(() -> service.quarantine(id, "  "))
                    .isInstanceOf(com.potlatech.pharmacy.common.exception.BusinessRuleException.class)
                    .hasMessageContaining("reason");
        }
    }

    // ================================================================ reconciliation

    @Nested
    @DisplayName("reconciliation")
    class Reconciliation {

        @Test
        @DisplayName("is clean for a ledger written only through the service")
        void soundLedgerIsClean() {
            Medicine medicine = newMedicine();
            UUID id = service.create(receive(medicine, 30, TODAY.plusYears(1))).id();
            service.adjust(id, new AdjustStockRequest(-7, "dispensed", null, null));

            ReconciliationResponse result = service.reconcile();

            assertThat(result.mismatches()).isEmpty();
            assertThat(result.checkedBatches()).isPositive();
        }

        @Test
        @DisplayName("catches a balance edited behind the service's back")
        void catchesHandEditedBalance() {
            Medicine medicine = newMedicine();
            UUID id = service.create(receive(medicine, 30, TODAY.plusYears(1))).id();

            // Exactly what a nervous user does in a SQL console to "fix" a count.
            InventoryBatch corrupted = batches.findById(id).orElseThrow();
            corrupted.setQuantityAvailable(999);
            batches.save(corrupted);
            batches.flush();

            ReconciliationResponse result = service.reconcile();

            assertThat(result.mismatches()).hasSize(1);
            ReconciliationResponse.Mismatch mismatch = result.mismatches().get(0);
            assertThat(mismatch.batchId()).isEqualTo(id);
            assertThat(mismatch.expectedFromLedger()).isEqualTo(30);
            assertThat(mismatch.actualBalance()).isEqualTo(999);
            assertThat(mismatch.difference()).isEqualTo(969);
        }

        /**
         * A batch with no ledger rows has no group to disagree, so a naive
         * "which sums differ" query reports nothing and the corruption is
         * invisible. This is the case the {@code LEFT JOIN} exists for.
         */
        @Test
        @DisplayName("catches a batch that has a balance but no ledger at all")
        void catchesBatchWithNoLedger() {
            Medicine medicine = newMedicine();
            UUID orphan = UUID.randomUUID();
            batches.save(InventoryBatch.builder()
                    .id(orphan)
                    .medicineId(medicine.getId())
                    .batchNumber("ORPHAN-" + UUID.randomUUID().toString().substring(0, 6))
                    .expiryDate(TODAY.plusYears(1))
                    .quantityReceived(7)
                    .quantityAvailable(7)
                    .costPrice(BigDecimal.ONE)
                    .sellingPrice(BigDecimal.TEN)
                    .location(InventoryBatch.Location.MAIN_PHARMACY)
                    .status(InventoryBatch.Status.ACTIVE)
                    .createdAt(NOW_DT)
                    .updatedAt(NOW_DT)
                    .build());
            batches.flush();

            ReconciliationResponse result = service.reconcile();

            assertThat(result.mismatches())
                    .as("stock with no ledger history is corruption too")
                    .hasSize(1);
            assertThat(result.mismatches().get(0).expectedFromLedger()).isZero();
            assertThat(result.mismatches().get(0).actualBalance()).isEqualTo(7);
        }
    }

    // ================================================================ expiry

    /**
     * Runs outside a wrapping transaction, which every other group here relies on.
     *
     * <p>{@code expireOne} is {@code REQUIRES_NEW} so the sweep can isolate a
     * poison batch from its neighbours. That suspends any ambient transaction, so
     * under the class-level {@code @Transactional} it would open a second
     * connection that cannot see the fixtures the first one has not committed - and
     * every test here would fail with "batch not found" against a batch it had just
     * saved. That is the production behaviour working correctly, so the tests adapt
     * rather than the annotation being weakened to suit them.
     *
     * <p>Consequence: these rows really are committed, so each test deletes its own
     * in {@code @AfterEach}. They are tracked by id, and ledger rows are removed
     * before batches because {@code fk_txn_batch} is {@code ON DELETE RESTRICT} -
     * the same constraint that stops history being deleted in production.
     */
    @Nested
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("expiry write-off")
    class Expiry {

        private final List<UUID> committed = new ArrayList<>();

        @AfterEach
        void cleanUp() {
            if (committed.isEmpty()) {
                return;
            }
            for (UUID id : committed) {
                ledger.deleteAll(ledger.findByBatchIdOrderByCreatedAtAsc(id));
            }
            batches.deleteAllById(committed);
            committed.clear();
        }

        /**
         * A batch whose date has passed.
         *
         * <p>Inserted through the repository rather than {@code create}, which
         * refuses a past date — as it should. A sweep test needs stock that time
         * has moved past, and the alternative is editing the clock in a fixture,
         * which makes the test about the fixture.
         */
        private UUID expiredBatch(Medicine medicine, int quantity) {
            UUID id = UUID.randomUUID();
            batches.save(InventoryBatch.builder()
                    .id(id)
                    .medicineId(medicine.getId())
                    .batchNumber("OLD-" + UUID.randomUUID().toString().substring(0, 6))
                    .expiryDate(TODAY.minusDays(3))
                    .quantityReceived(quantity)
                    .quantityAvailable(quantity)
                    .costPrice(BigDecimal.ONE)
                    .sellingPrice(BigDecimal.TEN)
                    .location(InventoryBatch.Location.MAIN_PHARMACY)
                    .status(InventoryBatch.Status.ACTIVE)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build());
            // A matching opening row, so the batch is sound before the sweep.
            ledger.save(StockTransaction.builder()
                    .id(UUID.randomUUID())
                    .batchId(id)
                    .transactionType(StockTransaction.TransactionType.PURCHASE_IN)
                    .quantityChanged(quantity)
                    .performedBy(ACTOR_ID)
                    .createdAt(NOW_DT)
                    .build());
            committed.add(id);
            return id;
        }

        @Test
        @DisplayName("writes off the remaining balance and keeps the ledger in step")
        void expiryWritesOffRemaining() {
            Medicine medicine = newMedicine();
            UUID id = expiredBatch(medicine, 25);

            assertThat(service.expireOne(id)).containsExactly(id);

            InventoryBatch after = reloaded(id);
            assertThat(after.getStatus())
                    .as("EXPIRED must be persisted: an expired batch left ACTIVE stays a FEFO candidate")
                    .isEqualTo(InventoryBatch.Status.EXPIRED);
            assertThat(after.getQuantityAvailable()).isZero();

            StockTransaction writeOff = ledger.findByBatchIdOrderByCreatedAtAsc(id).get(1);
            assertThat(writeOff.getTransactionType())
                    .isEqualTo(StockTransaction.TransactionType.EXPIRY_WRITE_OFF);
            assertThat(writeOff.getQuantityChanged())
                    .as("a zero delta here would break the ledger invariant")
                    .isEqualTo(-25);

            assertLedgerAgreesWithBalance(id);
            assertThat(service.reconcile().mismatches())
                    .as("the sweep must not be the thing reconciliation reports")
                    .isEmpty();
        }

        @Test
        @DisplayName("names no actor, because no human performed it")
        void expiryHasNoActor() {
            userContext.clear();
            Medicine medicine = newMedicine();
            UUID id = expiredBatch(medicine, 10);

            service.expireOne(id);

            StockTransaction writeOff = ledger.findByBatchIdOrderByCreatedAtAsc(id).get(1);
            assertThat(writeOff.getPerformedBy())
                    .as("a system action must be visibly anonymous, not falsely attributed")
                    .isNull();
        }

        @Test
        @DisplayName("only the remaining balance is written off, not the original receipt")
        void writesOffRemainingNotReceived() {
            Medicine medicine = newMedicine();
            UUID id = expiredBatch(medicine, 100);
            // 60 already dispensed, so 40 are left to expire.
            service.adjust(id, new AdjustStockRequest(-60, "dispensed", null, null));

            service.expireOne(id);

            StockTransaction writeOff = ledger.findByBatchIdOrderByCreatedAtAsc(id).get(2);
            assertThat(writeOff.getQuantityChanged()).isEqualTo(-40);
            assertThat(batches.findById(id).orElseThrow().getQuantityReceived())
                    .as("the original receipt is history and must not be rewritten")
                    .isEqualTo(100);
            assertLedgerAgreesWithBalance(id);
        }

        @Test
        @DisplayName("is idempotent, so a second sweep changes nothing")
        void secondSweepIsANoOp() {
            Medicine medicine = newMedicine();
            UUID id = expiredBatch(medicine, 10);

            service.expireOne(id);
            assertThat(service.expireOne(id)).isEmpty();

            assertThat(ledger.findByBatchIdOrderByCreatedAtAsc(id))
                    .as("a repeat sweep must not append a second write-off")
                    .hasSize(2);
        }

        @Test
        @DisplayName("leaves a batch alone until its date has actually passed")
        void doesNotExpireEarly() {
            Medicine medicine = newMedicine();
            InventoryBatchResponse fresh = service.create(
                    receive(medicine, 10, TODAY.plusDays(1)));
            committed.add(fresh.id());

            assertThat(service.expireOne(fresh.id()))
                    .as("expiring tomorrow is not expiring today")
                    .isEmpty();
            assertThat(batches.findById(fresh.id()).orElseThrow().getStatus())
                    .isEqualTo(InventoryBatch.Status.ACTIVE);
        }

        @Test
        @DisplayName("finds a quarantined batch as well as an active one")
        void findsQuarantinedStockToo() {
            Medicine medicine = newMedicine();
            UUID id = expiredBatch(medicine, 10);
            InventoryBatch batch = batches.findById(id).orElseThrow();
            batch.setStatus(InventoryBatch.Status.QUARANTINED);
            batches.save(batch);
            batches.flush();

            assertThat(service.findExpirable())
                    .as("expired stock is still reachable in a quarantine location")
                    .contains(id);
        }
    }

    // ================================================================ redaction

    @Nested
    @DisplayName("cost price redaction")
    class Redaction {

        @Test
        @DisplayName("a CASHIER receives no cost field at all")
        void cashierNeverSeesCost() {
            userContext.setRole(User.Role.CASHIER);
            Medicine medicine = newMedicine();

            InventoryBatchResponse created = service.create(receive(medicine, 10, TODAY.plusYears(1)));

            assertThat(created.costPrice())
                    .as("AGENTS rule 7: absence, not null")
                    .isNull();
            assertThat(created.sellingPrice())
                    .as("a cashier must still be able to quote a price")
                    .isNotNull();
        }

        @Test
        @DisplayName("a pharmacist receives the cost")
        void pharmacistSeesCost() {
            userContext.setRole(User.Role.PHARMACIST);
            Medicine medicine = newMedicine();

            assertThat(service.create(receive(medicine, 10, TODAY.plusYears(1))).costPrice())
                    .isEqualByComparingTo("10.00");
        }

        @Test
        @DisplayName("the whole list is redacted, not just the single-item read")
        void listIsRedactedToo() {
            userContext.setRole(User.Role.CASHIER);
            Medicine medicine = newMedicine();
            service.create(receive(medicine, 10, TODAY.plusYears(1)));
            batches.flush();

            assertThat(service.list(null, medicine.getId(), null, null, null, null, null,
                    org.springframework.data.domain.PageRequest.of(0, 20)).content())
                    .allSatisfy(r -> assertThat(r.costPrice()).isNull());
        }
    }
}
