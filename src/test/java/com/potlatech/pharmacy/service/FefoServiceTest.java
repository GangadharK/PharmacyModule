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

import com.potlatech.pharmacy.common.exception.BusinessRuleException;
import com.potlatech.pharmacy.common.exception.InsufficientStockException;
import com.potlatech.pharmacy.dto.inventory.CreateBatchRequest;
import com.potlatech.pharmacy.dto.inventory.FefoPreviewResponse;
import com.potlatech.pharmacy.dto.inventory.FefoRequest;
import com.potlatech.pharmacy.dto.inventory.InventoryBatchResponse;
import com.potlatech.pharmacy.entity.InventoryBatch;
import com.potlatech.pharmacy.entity.Medicine;
import com.potlatech.pharmacy.entity.StockTransaction;
import com.potlatech.pharmacy.entity.User;
import com.potlatech.pharmacy.repository.InventoryBatchRepository;
import com.potlatech.pharmacy.repository.MedicineRepository;
import com.potlatech.pharmacy.repository.NonMedicalItemRepository;
import com.potlatech.pharmacy.repository.StockTransactionRepository;
import com.potlatech.pharmacy.repository.SupplierRepository;
import com.potlatech.pharmacy.repository.UserRepository;
import com.potlatech.pharmacy.security.AuthenticatedUser;
import com.potlatech.pharmacy.security.CurrentUserContext;
import com.potlatech.pharmacy.service.CatalogueSearchText;
import com.potlatech.pharmacy.service.FefoAllocation;
import com.potlatech.pharmacy.service.InventoryBatchService;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FEFO through the real service, against a real PostgreSQL database.
 *
 * <p>{@link FefoAllocatorTest} covers the arithmetic with no database at all.
 * This file covers the half the arithmetic cannot see: that the candidate query
 * really excludes quarantined, expired and wrong-location stock; that the
 * deduction really writes a negative {@code SALE_OUT}; and that two concurrent
 * dispenses really cannot both take the same units.
 *
 * <p><strong>Why not mocks.</strong> The FEFO guarantee is a race between two
 * transactions, and a mock repository has no second transaction, no row lock and
 * no {@code WHERE} clause to lose. A mocked concurrency test asserts that the
 * service called a method; only a real one can assert that the second caller was
 * refused.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional
@Import(FefoServiceTest.TestConfig.class)
class FefoServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-01T09:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 1);
    private static final BigDecimal PRICE = new BigDecimal("15.00");

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
                NonMedicalItemRepository items,
                SupplierRepository suppliers,
                UserRepository users,
                StubUserContext userContext) {
            return new InventoryBatchService(batches, ledger, medicines, items, suppliers, users,
                    userContext, Clock.fixed(NOW, ZoneOffset.UTC));
        }
    }

    /** A caller the test can retarget, so role-dependent behaviour is testable. */
    static class StubUserContext implements CurrentUserContext {
        private AuthenticatedUser user = new AuthenticatedUser(
                UUID.fromString("3d9a4fc8-d93e-430f-a735-2bd51ff9c1de"),
                "pharmacist", "Test Pharmacist", User.Role.PHARMACIST);

        void setRole(User.Role role) {
            user = new AuthenticatedUser(
                    user == null ? UUID.fromString("3d9a4fc8-d93e-430f-a735-2bd51ff9c1de") : user.id(),
                    "cashier", "Test Cashier", role);
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

    // ================================================================ fixtures

    private Medicine newMedicine() {
        String name = "FefoMed-" + UUID.randomUUID().toString().substring(0, 8);
        return medicines.save(Medicine.builder()
                .id(UUID.randomUUID())
                .genericName(name)
                .dosageForm(Medicine.DosageForm.TABLET)
                .scheduleClass(Medicine.ScheduleClass.H)
                .isActive(true)
                .searchBlob(CatalogueSearchText.forMedicine(name, null, null, null))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }

    private InventoryBatchResponse receive(Medicine medicine, int quantity, LocalDate expiry) {
        return receive(medicine, quantity, expiry, InventoryBatch.Location.MAIN_PHARMACY);
    }

    private InventoryBatchResponse receive(Medicine medicine, int quantity, LocalDate expiry,
                                           InventoryBatch.Location location) {
        return service.create(new CreateBatchRequest(
                medicine.getId(), null, null,
                "LOT-" + UUID.randomUUID().toString().substring(0, 6),
                null, expiry, quantity, new BigDecimal("10.00"), PRICE, location, "delivery"));
    }

    private FefoRequest request(UUID medicineId, int quantity) {
        return new FefoRequest(medicineId, quantity, InventoryBatch.Location.MAIN_PHARMACY,
                null, null);
    }

    private FefoRequest request(UUID medicineId, int quantity, InventoryBatch.Location location) {
        return new FefoRequest(medicineId, quantity, location, null, null);
    }

    /** A stock row placed directly, for states the service will not create. */
    private UUID rawBatch(Medicine medicine, int quantity, LocalDate expiry,
                          InventoryBatch.Status status, InventoryBatch.Location location) {
        UUID id = UUID.randomUUID();
        batches.save(InventoryBatch.builder()
                .id(id)
                .medicineId(medicine.getId())
                .batchNumber("RAW-" + UUID.randomUUID().toString().substring(0, 6))
                .expiryDate(expiry)
                .quantityReceived(quantity)
                .quantityAvailable(quantity)
                .costPrice(new BigDecimal("10.00"))
                .sellingPrice(PRICE)
                .location(location)
                .status(status)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
        return id;
    }

    private void assertLedgerAgreesWithBalance(UUID batchId) {
        long fromLedger = ledger.sumForBatch(batchId);
        int balance = batches.findById(batchId).orElseThrow().getQuantityAvailable();
        assertThat(balance).isEqualTo((int) fromLedger);
    }

    // ================================================================ ordering

    @Nested
    @DisplayName("ordering")
    class Ordering {

        @Test
        @DisplayName("takes from the earliest expiry first")
        void earliestExpiryFirst() {
            Medicine medicine = newMedicine();
            receive(medicine, 50, TODAY.plusYears(2));   // latest
            receive(medicine, 50, TODAY.plusMonths(1));  // soonest
            receive(medicine, 50, TODAY.plusMonths(6));  // middle

            FefoPreviewResponse result = service.issueStock(request(medicine.getId(), 10));

            assertThat(result.allocations()).hasSize(1);
            assertThat(result.allocations().getFirst().expiryDate())
                    .isEqualTo(TODAY.plusMonths(1));
        }

        @Test
        @DisplayName("a tie on expiry is broken by batch number, and both are drained in that order")
        void tieBrokenByBatchNumber() {
            Medicine medicine = newMedicine();
            LocalDate sameDay = TODAY.plusMonths(4);
            // Created in deliberately unhelpful order.
            receive(medicine, 5, sameDay);
            receive(medicine, 5, sameDay);
            receive(medicine, 5, sameDay);

            FefoPreviewResponse result = service.issueStock(request(medicine.getId(), 15));

            List<String> order = result.allocations().stream()
                    .map(FefoAllocation::batchNumber).toList();

            assertThat(order)
                    .as("a dispensed order that changes between identical runs makes the audit a guess")
                    .isSorted();
            assertThat(result.totalAllocated()).isEqualTo(15);
            assertThat(result.shortfall()).isZero();
        }
    }

    // ================================================================ the ledger

    @Nested
    @DisplayName("the deduction")
    class Deduction {

        @Test
        @DisplayName("spanning two batches writes one SALE_OUT per batch, both negative")
        void spanningTwoBatchesWritesOneSaleOutEach() {
            Medicine medicine = newMedicine();
            InventoryBatchResponse soon = receive(medicine, 10, TODAY.plusMonths(1));
            InventoryBatchResponse later = receive(medicine, 10, TODAY.plusMonths(6));

            FefoPreviewResponse result = service.issueStock(request(medicine.getId(), 15));

            assertThat(result.allocations()).hasSize(2);
            assertThat(result.allocations().get(0).batchId()).isEqualTo(soon.id());
            assertThat(result.allocations().get(0).quantity()).isEqualTo(10);
            assertThat(result.allocations().get(1).batchId()).isEqualTo(later.id());
            assertThat(result.allocations().get(1).quantity()).isEqualTo(5);

            for (InventoryBatchResponse batch : List.of(soon, later)) {
                List<StockTransaction> rows = ledger.findByBatchIdOrderByCreatedAtAsc(batch.id());
                assertThat(rows).hasSize(2);   // PURCHASE_IN, then SALE_OUT
                StockTransaction sale = rows.getLast();
                assertThat(sale.getTransactionType())
                        .isEqualTo(StockTransaction.TransactionType.SALE_OUT);
                assertThat(sale.getQuantityChanged()).isNegative();
                assertThat(sale.getPerformedBy()).isEqualTo(userContext.find().id());
                assertLedgerAgreesWithBalance(batch.id());
            }

            assertThat(batches.findById(soon.id()).orElseThrow().getQuantityAvailable()).isZero();
            assertThat(batches.findById(later.id()).orElseThrow().getQuantityAvailable()).isEqualTo(5);
            assertThat(service.reconcile().mismatches()).isEmpty();
        }

        @Test
        @DisplayName("a sale is SALE_OUT, never DAMAGE_WRITE_OFF")
        void saleIsNotDamageWriteOff() {
            // The sign-based inference is right for a manual adjustment and wrong
            // here. If this ever regresses, every sale reads as breakage in the
            // stock report and the damage figure becomes fiction.
            Medicine medicine = newMedicine();
            InventoryBatchResponse batch = receive(medicine, 20, TODAY.plusMonths(1));

            service.issueStock(request(medicine.getId(), 5));

            assertThat(ledger.findByBatchIdOrderByCreatedAtAsc(batch.id()).getLast().getTransactionType())
                    .isEqualTo(StockTransaction.TransactionType.SALE_OUT)
                    .isNotEqualTo(StockTransaction.TransactionType.DAMAGE_WRITE_OFF);
        }

        @Test
        @DisplayName("a manual loss is still DAMAGE_WRITE_OFF, so the two are not conflated")
        void manualLossIsStillDamageWriteOff() {
            // The counterpart to the test above: the sign-based path that the
            // FEFO path deliberately bypasses must keep working.
            Medicine medicine = newMedicine();
            InventoryBatchResponse batch = receive(medicine, 20, TODAY.plusMonths(1));

            service.adjust(batch.id(), new com.potlatech.pharmacy.dto.inventory.AdjustStockRequest(
                    -4, "broken in transit", null, null));

            assertThat(ledger.findByBatchIdOrderByCreatedAtAsc(batch.id()).getLast().getTransactionType())
                    .isEqualTo(StockTransaction.TransactionType.DAMAGE_WRITE_OFF);
        }

        @Test
        @DisplayName("each allocation carries its own batch's selling price")
        void allocationCarriesUnitPrice() {
            Medicine medicine = newMedicine();
            receive(medicine, 10, TODAY.plusMonths(1));   // PRICE, 15.00

            UUID dearId = rawBatch(medicine, 10, TODAY.plusMonths(6),
                    InventoryBatch.Status.ACTIVE, InventoryBatch.Location.MAIN_PHARMACY);
            batches.findById(dearId).orElseThrow()
                    .setSellingPrice(new BigDecimal("99.99"));
            batches.save(batches.findById(dearId).orElseThrow());

            FefoPreviewResponse result = service.issueStock(request(medicine.getId(), 20));

            assertThat(result.allocations().get(0).unitPrice()).isEqualByComparingTo("15.00");
            assertThat(result.allocations().get(1).unitPrice()).isEqualByComparingTo("99.99");
            assertThat(result.allocations().get(0).lineTotal()).isEqualByComparingTo("150.00");
            assertThat(result.allocations().get(1).lineTotal()).isEqualByComparingTo("999.90");
        }

        @Test
        @DisplayName("a dispense records no reference, because there is no sale to point at yet")
        void dispenseRecordsNoReference() {
            // reference_type is constrained to PURCHASE_ORDER / INVOICE / RETURN /
            // CYCLE_COUNT, and none of those can mean "the sale this came from".
            // Billing is out of scope for v1, so there is no sale entity to
            // reference and inventing a value would be a lie the audit trail
            // repeats. Recorded as an open question for when billing lands.
            Medicine medicine = newMedicine();
            InventoryBatchResponse batch = receive(medicine, 20, TODAY.plusMonths(1));

            service.issueStock(request(medicine.getId(), 5));

            StockTransaction sale = ledger.findByBatchIdOrderByCreatedAtAsc(batch.id()).getLast();
            assertThat(sale.getReferenceType()).isNull();
            assertThat(sale.getReferenceId()).isNull();
            assertThat(sale.getNote())
                    .as("the note still says which batch and how many, so the movement is traceable")
                    .contains("FEFO dispense")
                    .contains("5")
                    .contains(batch.batchNumber());
        }

        @Test
        @DisplayName("a half-supplied reference pair is refused")
        void halfSuppliedReferenceIsRefused() {
            // The pair rule from Unit 05 still applies to dispensing: a reference
            // that cannot be followed to anything is not a reference.
            Medicine medicine = newMedicine();
            InventoryBatchResponse batch = receive(medicine, 20, TODAY.plusMonths(1));

            assertThatThrownBy(() -> service.issueStock(new FefoRequest(
                    medicine.getId(), 5, InventoryBatch.Location.MAIN_PHARMACY,
                    StockTransaction.ReferenceType.INVOICE, null)))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("referenceId");

            assertThat(batches.findById(batch.id()).orElseThrow().getQuantityAvailable())
                    .isEqualTo(20);
        }

        @Test
        @DisplayName("a supplied reference pair is recorded on the sale-out")
        void referencePairIsRecorded() {
            Medicine medicine = newMedicine();
            InventoryBatchResponse batch = receive(medicine, 20, TODAY.plusMonths(1));
            UUID invoiceId = UUID.randomUUID();

            service.issueStock(new FefoRequest(medicine.getId(), 5,
                    InventoryBatch.Location.MAIN_PHARMACY,
                    StockTransaction.ReferenceType.INVOICE, invoiceId));

            StockTransaction sale = ledger.findByBatchIdOrderByCreatedAtAsc(batch.id()).getLast();
            assertThat(sale.getReferenceType()).isEqualTo(StockTransaction.ReferenceType.INVOICE);
            assertThat(sale.getReferenceId()).isEqualTo(invoiceId);
        }

        @Test
        @DisplayName("draining a batch marks it DEPLETED and it stops being a candidate")
        void drainingMarksDepleted() {
            Medicine medicine = newMedicine();
            InventoryBatchResponse batch = receive(medicine, 5, TODAY.plusMonths(1));

            service.issueStock(request(medicine.getId(), 5));

            assertThat(batches.findById(batch.id()).orElseThrow().getStatus())
                    .isEqualTo(InventoryBatch.Status.DEPLETED);
            assertThat(batches.findFefoCandidates(medicine.getId(), "MAIN_PHARMACY", TODAY))
                    .isEmpty();
            assertLedgerAgreesWithBalance(batch.id());
        }
    }

    // ================================================================ refusals

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("an unmeetable request writes nothing at all")
        void unmeetableRequestWritesNothing() {
            Medicine medicine = newMedicine();
            InventoryBatchResponse batch = receive(medicine, 7, TODAY.plusMonths(1));

            assertThatThrownBy(() -> service.issueStock(request(medicine.getId(), 20)))
                    .isInstanceOf(InsufficientStockException.class)
                    .hasMessageContaining("requested 20")
                    .hasMessageContaining("available 7");

            // The whole point of refusing before writing: no partial dispense.
            assertThat(batches.findById(batch.id()).orElseThrow().getQuantityAvailable())
                    .isEqualTo(7);
            assertThat(ledger.findByBatchIdOrderByCreatedAtAsc(batch.id()))
                    .as("only the original receipt, no partial sale")
                    .hasSize(1);
            assertThat(service.reconcile().mismatches()).isEmpty();
        }

        @Test
        @DisplayName("names the medicine, so the counter can say which product is short")
        void namesTheMedicine() {
            Medicine medicine = newMedicine();
            receive(medicine, 2, TODAY.plusMonths(1));

            assertThatThrownBy(() -> service.issueStock(request(medicine.getId(), 20)))
                    .isInstanceOf(InsufficientStockException.class)
                    .hasMessageContaining(medicine.getGenericName());
        }

        @Test
        @DisplayName("a quarantined batch is never dispensed")
        void quarantinedBatchIsNotDispensed() {
            Medicine medicine = newMedicine();
            InventoryBatchResponse batch = receive(medicine, 50, TODAY.plusMonths(1));
            service.quarantine(batch.id(), "Recall notice 2026-06");

            assertThatThrownBy(() -> service.issueStock(request(medicine.getId(), 10)))
                    .as("a recall is not a suggestion")
                    .isInstanceOf(InsufficientStockException.class);

            assertThat(batches.findById(batch.id()).orElseThrow().getQuantityAvailable())
                    .isEqualTo(50);
            assertThat(batches.findById(batch.id()).orElseThrow().getStatus())
                    .isEqualTo(InventoryBatch.Status.QUARANTINED);
        }

        @Test
        @DisplayName("a batch past its date is never dispensed, even while still ACTIVE")
        void expiredBatchIsNotDispensed() {
            // status = ACTIVE with a past date: the state the hourly sweep is
            // supposed to resolve. Dispensing it would be the bug the date
            // predicate in the candidate query exists to prevent.
            Medicine medicine = newMedicine();
            rawBatch(medicine, 50, TODAY.minusDays(1),
                    InventoryBatch.Status.ACTIVE, InventoryBatch.Location.MAIN_PHARMACY);

            assertThatThrownBy(() -> service.issueStock(request(medicine.getId(), 10)))
                    .isInstanceOf(InsufficientStockException.class);
        }

        @Test
        @DisplayName("a batch expiring today is still dispensable today")
        void batchExpiringTodayIsUsable() {
            // expiry_date >= today, not > — the sweep writes it off tomorrow.
            Medicine medicine = newMedicine();
            receive(medicine, 10, TODAY);

            FefoPreviewResponse result = service.issueStock(request(medicine.getId(), 10));

            assertThat(result.allocations()).hasSize(1);
            assertThat(result.shortfall()).isZero();
        }

        @Test
        @DisplayName("stock in another location is invisible to this location's request")
        void locationFilterIsApplied() {
            Medicine medicine = newMedicine();
            receive(medicine, 50, TODAY.plusMonths(1), InventoryBatch.Location.MAIN_PHARMACY);
            receive(medicine, 50, TODAY.plusMonths(2), InventoryBatch.Location.OPD_COUNTER);

            FefoPreviewResponse main = service.issueStock(
                    request(medicine.getId(), 10, InventoryBatch.Location.MAIN_PHARMACY));
            assertThat(main.allocations())
                    .as("a request for the counter must not quietly draw from the back shelf")
                    .hasSize(1);
            assertThat(main.allocations().getFirst().expiryDate())
                    .isEqualTo(TODAY.plusMonths(1));

            FefoPreviewResponse opd = service.issueStock(
                    request(medicine.getId(), 10, InventoryBatch.Location.OPD_COUNTER));
            assertThat(opd.allocations().getFirst().expiryDate())
                    .isEqualTo(TODAY.plusMonths(2));
        }

        @Test
        @DisplayName("a negative request is refused before the database is touched")
        void negativeRequestIsRefused() {
            Medicine medicine = newMedicine();
            InventoryBatchResponse batch = receive(medicine, 10, TODAY.plusMonths(1));

            assertThatThrownBy(() -> service.issueStock(request(medicine.getId(), -5)))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("must not be negative");

            assertThat(batches.findById(batch.id()).orElseThrow().getQuantityAvailable())
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("a zero request is a no-op, not an error")
        void zeroRequestIsANoOp() {
            Medicine medicine = newMedicine();
            InventoryBatchResponse batch = receive(medicine, 10, TODAY.plusMonths(1));

            FefoPreviewResponse result = service.issueStock(request(medicine.getId(), 0));

            assertThat(result.allocations()).isEmpty();
            assertThat(result.isComplete()).isTrue();
            assertThat(batches.findById(batch.id()).orElseThrow().getQuantityAvailable())
                    .isEqualTo(10);
            assertThat(ledger.findByBatchIdOrderByCreatedAtAsc(batch.id())).hasSize(1);
        }
    }

    // ================================================================ boundary

    @Nested
    @DisplayName("the exact-boundary case")
    class ExactBoundary {

        @Test
        @DisplayName("empties the first batch and touches the second not at all")
        void emptiesFirstAndLeavesSecondUntouched() {
            Medicine medicine = newMedicine();
            InventoryBatchResponse first = receive(medicine, 10, TODAY.plusMonths(1));
            InventoryBatchResponse second = receive(medicine, 10, TODAY.plusMonths(2));

            FefoPreviewResponse result = service.issueStock(request(medicine.getId(), 10));

            assertThat(result.allocations())
                    .as("a zero-quantity allocation would be a ledger row for a movement of nothing")
                    .hasSize(1);
            assertThat(result.allocations().getFirst().batchId()).isEqualTo(first.id());

            assertThat(batches.findById(first.id()).orElseThrow().getQuantityAvailable()).isZero();
            assertThat(batches.findById(second.id()).orElseThrow().getQuantityAvailable())
                    .as("the second batch must be completely untouched")
                    .isEqualTo(10);
            assertThat(ledger.findByBatchIdOrderByCreatedAtAsc(second.id()))
                    .as("no ledger row for a batch nothing was taken from")
                    .hasSize(1);
        }
    }

    // ================================================================ preview

    @Nested
    @DisplayName("preview")
    class Preview {

        @Test
        @DisplayName("writes nothing")
        void previewWritesNothing() {
            Medicine medicine = newMedicine();
            InventoryBatchResponse batch = receive(medicine, 50, TODAY.plusMonths(1));

            FefoPreviewResponse preview = service.previewFefo(request(medicine.getId(), 10));

            assertThat(preview.allocations()).hasSize(1);
            assertThat(preview.totalAllocated()).isEqualTo(10);
            assertThat(batches.findById(batch.id()).orElseThrow().getQuantityAvailable())
                    .isEqualTo(50);
            assertThat(ledger.findByBatchIdOrderByCreatedAtAsc(batch.id())).hasSize(1);
        }

        @Test
        @DisplayName("agrees with the eventual dispense about which batches are used")
        void previewAgreesWithDispense() {
            // Both paths run the same allocator, so a preview cannot send the
            // pharmacist to a different shelf than the one that is drawn from.
            Medicine medicine = newMedicine();
            receive(medicine, 10, TODAY.plusMonths(1));
            receive(medicine, 10, TODAY.plusMonths(2));
            receive(medicine, 10, TODAY.plusMonths(3));

            List<UUID> previewed = service.previewFefo(request(medicine.getId(), 25))
                    .allocations().stream().map(a -> a.batchId()).toList();
            List<UUID> dispensed = service.issueStock(request(medicine.getId(), 25))
                    .allocations().stream().map(a -> a.batchId()).toList();

            assertThat(dispensed).isEqualTo(previewed);
        }

        @Test
        @DisplayName("reports a shortfall without failing")
        void previewReportsShortfallWithoutFailing() {
            // "You asked for 20, 12 are available" is a useful answer to a screen,
            // not a failure. A preview that 400'd could not render that.
            Medicine medicine = newMedicine();
            receive(medicine, 12, TODAY.plusMonths(1));

            FefoPreviewResponse preview = service.previewFefo(request(medicine.getId(), 20));

            assertThat(preview.totalAllocated()).isEqualTo(12);
            assertThat(preview.shortfall()).isEqualTo(8);
            assertThat(preview.isComplete()).isFalse();
        }

        @Test
        @DisplayName("carries no cost price, for any role")
        void previewCarriesNoCostPrice() {
            Medicine medicine = newMedicine();
            receive(medicine, 50, TODAY.plusMonths(1));

            userContext.setRole(User.Role.CASHIER);
            FefoPreviewResponse preview = service.previewFefo(request(medicine.getId(), 10));

            // Structural, not a redaction pass: the allocator never received a
            // cost price, so there is nothing to strip.
            assertThat(preview.allocations())
                    .allSatisfy(a -> assertThat(a.unitPrice()).isNotNull());
            assertThat(FefoPreviewResponse.class.getRecordComponents())
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .doesNotContain("costPrice");
        }
    }

    // ================================================================ concurrency

    @Nested
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("concurrent dispensing")
    class Concurrency {

        private final List<UUID> committed = new ArrayList<>();

        @AfterEach
        void cleanUp() {
            for (UUID id : committed) {
                // Ledger first: inventory_batch has no cascade and must not.
                ledger.deleteAll(ledger.findByBatchIdOrderByCreatedAtAsc(id));
            }
            batches.deleteAllById(committed);
            committed.clear();
        }

        @Test
        @DisplayName("two threads cannot both take the same units")
        void twoThreadsCannotBothWin() throws Exception {
            Medicine medicine = newMedicine();
            // One batch of 10. Both threads ask for 10. At most one can succeed.
            InventoryBatchResponse batch = receive(medicine, 10, TODAY.plusMonths(1));
            committed.add(batch.id());

            CountDownLatch startTogether = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);

            Callable<Object> dispense = () -> {
                startTogether.await(5, TimeUnit.SECONDS);
                try {
                    return service.issueStock(request(medicine.getId(), 10));
                } catch (InsufficientStockException e) {
                    return e;
                }
            };

            try {
                Future<Object> first = pool.submit(dispense);
                Future<Object> second = pool.submit(dispense);
                startTogether.countDown();

                Object a = first.get(20, TimeUnit.SECONDS);
                Object b = second.get(20, TimeUnit.SECONDS);

                long successes = java.util.stream.Stream.of(a, b)
                        .filter(FefoPreviewResponse.class::isInstance).count();
                long refusals = java.util.stream.Stream.of(a, b)
                        .filter(InsufficientStockException.class::isInstance).count();

                assertThat(successes + refusals)
                        .as("both threads must have finished one way or the other")
                        .isEqualTo(2);
                assertThat(successes)
                        .as("FOR UPDATE serialises the two, so the loser sees the stock already gone")
                        .isEqualTo(1);
                assertThat(refusals).isEqualTo(1);

                assertThat(batches.findById(batch.id()).orElseThrow().getQuantityAvailable())
                        .as("the batch is drained exactly once, never below zero")
                        .isZero();
                assertThat(ledger.sumForBatch(batch.id()))
                        .as("one receipt in, one sale out")
                        .isEqualTo(0);
                assertThat(service.reconcile().mismatches())
                        .as("a lost update here would show up as a mismatch")
                        .isEmpty();
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("two threads splitting one batch cannot over-deduct it")
        void twoThreadsSplittingOneBatchStayWithinStock() throws Exception {
            Medicine medicine = newMedicine();
            InventoryBatchResponse batch = receive(medicine, 10, TODAY.plusMonths(1));
            committed.add(batch.id());

            CountDownLatch startTogether = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);

            Callable<Object> dispense = () -> {
                startTogether.await(5, TimeUnit.SECONDS);
                try {
                    return service.issueStock(request(medicine.getId(), 6));
                } catch (InsufficientStockException e) {
                    return e;
                }
            };

            try {
                Future<Object> first = pool.submit(dispense);
                Future<Object> second = pool.submit(dispense);
                startTogether.countDown();
                first.get(20, TimeUnit.SECONDS);
                second.get(20, TimeUnit.SECONDS);

                // 6 + 6 > 10, so one must have been refused. The point is not which.
                assertThat(batches.findById(batch.id()).orElseThrow().getQuantityAvailable())
                        .as("6 + 6 against 10 units: the balance may not go negative")
                        .isNotNegative()
                        .isGreaterThanOrEqualTo(4);
                assertLedgerAgreesWithBalance(batch.id());
                assertThat(service.reconcile().mismatches()).isEmpty();
            } finally {
                pool.shutdownNow();
            }
        }
    }

    // ================================================================ reconciliation

    @Nested
    @DisplayName("the audit invariant")
    class AuditInvariant {

        @Test
        @DisplayName("holds after a long run of dispenses across many batches")
        void reconciliationHoldsAfterManyDispenses() {
            Medicine medicine = newMedicine();
            for (int i = 1; i <= 5; i++) {
                receive(medicine, 10, TODAY.plusMonths(i));
            }

            for (int i = 0; i < 12; i++) {
                service.issueStock(request(medicine.getId(), 3));
            }

            assertThat(batches.findAll())
                    .filteredOn(b -> medicine.getId().equals(b.getMedicineId()))
                    .allSatisfy(b -> assertLedgerAgreesWithBalance(b.getId()));
            assertThat(service.reconcile().mismatches()).isEmpty();
        }
    }
}
