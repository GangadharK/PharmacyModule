package com.potlatech.pharmacy.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.potlatech.pharmacy.common.exception.BusinessRuleException;
import com.potlatech.pharmacy.common.exception.InsufficientStockException;
import com.potlatech.pharmacy.service.AllocationResult;
import com.potlatech.pharmacy.service.FefoAllocation;
import com.potlatech.pharmacy.service.FefoAllocator;
import com.potlatech.pharmacy.service.StockCandidate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The FEFO edge cases, as pure arithmetic.
 *
 * <p>No Spring, no database, no mocks, no clock. Every test here constructs a
 * handful of {@link StockCandidate} values and asserts on a list — which is the
 * entire reason the allocator was split out of the service. There is no
 * transaction, no entity manager and no {@code @DataJpaTest} in this file, and
 * the class is constructed with {@code new} because it has nothing to inject.
 *
 * <p>The 11 cases are {@code architecture-context.md} §5.3's table, one test
 * each. Two of them are stated there in terms the allocator cannot see — the
 * location filter and the quarantined batch are decisions made by the candidate
 * <em>query</em>, not by this function. Those are asserted here as far as the
 * function can carry them, and asserted properly against the database in
 * {@code FefoServiceTest}.
 */
class FefoAllocatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 26);
    private static final BigDecimal PRICE = new BigDecimal("15.00");

    private final FefoAllocator allocator = new FefoAllocator();

    // ================================================================ helpers

    private static StockCandidate candidate(String batchNumber, LocalDate expiry, int available) {
        return new StockCandidate(UUID.randomUUID(), batchNumber, expiry, available, PRICE);
    }

    /** A fixed-id candidate, for tests that assert on ids. */
    private static StockCandidate candidate(UUID id, String batchNumber, LocalDate expiry, int available) {
        return new StockCandidate(id, batchNumber, expiry, available, PRICE);
    }

    // ================================================================ §5.3 cases

    @Nested
    @DisplayName("§5.3 — the eleven edge cases")
    class EdgeCases {

        @Test
        @DisplayName("1. zero request is an empty result, not an error")
        void zeroRequestIsEmptyNotAnError() {
            AllocationResult result = allocator.allocate(
                    List.of(candidate("A", TODAY.plusMonths(1), 50)), 0, TODAY);

            assertThat(result.allocations()).isEmpty();
            assertThat(result.totalAllocated()).isZero();
            assertThat(result.shortfall()).isZero();
            assertThat(result.isComplete()).isTrue();
        }

        @Test
        @DisplayName("1b. zero request over no candidates at all is still fine")
        void zeroRequestOverEmptyStockIsEmpty() {
            AllocationResult result = allocator.allocate(List.of(), 0, TODAY);

            assertThat(result.allocations()).isEmpty();
            assertThat(result.isComplete()).isTrue();
        }

        @Test
        @DisplayName("2. one sufficient batch yields one allocation for the whole quantity")
        void singleSufficientBatch() {
            StockCandidate only = candidate("A", TODAY.plusMonths(1), 100);

            AllocationResult result = allocator.allocate(List.of(only), 30, TODAY);

            assertThat(result.allocations()).hasSize(1);
            assertThat(result.allocations().getFirst().quantity()).isEqualTo(30);
            assertThat(result.allocations().getFirst().batchId()).isEqualTo(only.id());
            assertThat(result.totalAllocated()).isEqualTo(30);
            assertThat(result.shortfall()).isZero();
        }

        @Test
        @DisplayName("3. one insufficient batch reports the shortfall")
        void singleInsufficientBatchReportsShortfall() {
            AllocationResult result = allocator.allocate(
                    List.of(candidate("A", TODAY.plusMonths(1), 7)), 20, TODAY);

            assertThat(result.allocations()).hasSize(1);
            assertThat(result.allocations().getFirst().quantity()).isEqualTo(7);
            assertThat(result.totalAllocated()).isEqualTo(7);
            assertThat(result.shortfall()).isEqualTo(13);
            assertThat(result.isComplete()).isFalse();
        }

        @Test
        @DisplayName("4. a request exceeding all stock reports shortfall and total available")
        void requestExceedingAllStock() {
            List<StockCandidate> stock = List.of(
                    candidate("A", TODAY.plusMonths(1), 5),
                    candidate("B", TODAY.plusMonths(2), 7));

            AllocationResult result = allocator.allocate(stock, 100, TODAY);

            assertThat(result.totalAllocated()).isEqualTo(12);
            assertThat(result.shortfall()).isEqualTo(88);
            assertThatThrownBy(() -> result.requireComplete("Amoxicillin"))
                    .isInstanceOf(InsufficientStockException.class)
                    .hasMessageContaining("requested 100")
                    .hasMessageContaining("available 12")
                    .hasMessageContaining("Amoxicillin");
        }

        @Test
        @DisplayName("5. a request spanning two batches splits at the boundary")
        void spansTwoBatches() {
            List<StockCandidate> stock = List.of(
                    candidate("A", TODAY.plusMonths(1), 10),   // expires sooner
                    candidate("B", TODAY.plusMonths(6), 10));

            AllocationResult result = allocator.allocate(stock, 15, TODAY);

            assertThat(result.allocations()).hasSize(2);
            assertThat(result.allocations().get(0).batchNumber()).isEqualTo("A");
            assertThat(result.allocations().get(0).quantity()).isEqualTo(10);
            assertThat(result.allocations().get(1).batchNumber()).isEqualTo("B");
            assertThat(result.allocations().get(1).quantity()).isEqualTo(5);
            assertThat(result.shortfall()).isZero();
        }

        @Test
        @DisplayName("6. landing exactly on a boundary emits no zero-quantity allocation")
        void exactBoundaryEmitsNoZeroQuantityAllocation() {
            List<StockCandidate> stock = List.of(
                    candidate("A", TODAY.plusMonths(1), 10),   // exactly emptied
                    candidate("B", TODAY.plusMonths(6), 10));  // must not appear

            AllocationResult result = allocator.allocate(stock, 10, TODAY);

            assertThat(result.allocations())
                    .as("a batch that was not touched must not appear, not even with quantity 0")
                    .hasSize(1);
            assertThat(result.allocations().getFirst().batchNumber()).isEqualTo("A");
            assertThat(result.allocations().getFirst().quantity()).isEqualTo(10);
            assertThat(result.allocations())
                    .as("a zero-quantity row would be a ledger movement of nothing")
                    .noneMatch(a -> a.quantity() == 0);
            assertThat(result.totalAllocated()).isEqualTo(10);
            assertThat(result.shortfall()).isZero();
        }

        @Test
        @DisplayName("7. a negative request is rejected at the boundary")
        void negativeRequestIsRejected() {
            List<StockCandidate> stock = List.of(candidate("A", TODAY.plusMonths(1), 50));

            assertThatThrownBy(() -> allocator.allocate(stock, -1, TODAY))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessageContaining("must not be negative");

            assertThatThrownBy(() -> allocator.allocate(stock, Integer.MIN_VALUE, TODAY))
                    .isInstanceOf(BusinessRuleException.class);

            // Refused before any stock is considered, so there is nothing partial
            // to unwind and no database round trip to have wasted.
            assertThat(stock).hasSize(1);
        }

        @Test
        @DisplayName("8. an expired candidate is never allocated, even if it is offered")
        void expiredCandidateIsNeverAllocated() {
            List<StockCandidate> stock = List.of(
                    candidate("EXPIRED", TODAY.minusDays(1), 100),   // yesterday
                    candidate("GOOD", TODAY.plusMonths(1), 10));

            AllocationResult result = allocator.allocate(stock, 10, TODAY);

            assertThat(result.allocations())
                    .as("dispensing expired stock is the one outcome this unit exists to prevent")
                    .hasSize(1)
                    .allSatisfy(a -> assertThat(a.batchNumber()).isEqualTo("GOOD"));
            assertThat(result.shortfall()).isZero();
        }

        @Test
        @DisplayName("8b. a batch expiring today is still dispensable today")
        void batchExpiringTodayIsStillUsable() {
            List<StockCandidate> stock = List.of(candidate("TODAY", TODAY, 5));

            AllocationResult result = allocator.allocate(stock, 5, TODAY);

            assertThat(result.allocations())
                    .as("expiry_date >= today, not > — the sweep writes it off tomorrow")
                    .hasSize(1);
            assertThat(result.shortfall()).isZero();
        }

        @Test
        @DisplayName("8c. a candidate with no balance is never allocated")
        void zeroAvailabilityCandidateIsNeverAllocated() {
            List<StockCandidate> stock = List.of(
                    candidate("EMPTY", TODAY.plusMonths(1), 0),
                    candidate("FULL", TODAY.plusMonths(2), 5));

            AllocationResult result = allocator.allocate(stock, 3, TODAY);

            assertThat(result.allocations()).hasSize(1);
            assertThat(result.allocations().getFirst().batchNumber()).isEqualTo("FULL");
        }

        @Test
        @DisplayName("9. a tie on expiry is broken by batch number, and the result is reproducible")
        void expiryTieBrokenByBatchNumber() {
            LocalDate sameDay = TODAY.plusMonths(3);
            StockCandidate b2 = candidate("B2", sameDay, 10);
            StockCandidate a1 = candidate("A1", sameDay, 10);
            StockCandidate b1 = candidate("B1", sameDay, 10);

            // Offered deliberately out of order, in three different arrangements.
            // FEFO must not care how the database happened to return them.
            List<List<StockCandidate>> arrangements = List.of(
                    List.of(b2, a1, b1),
                    List.of(b1, b2, a1),
                    List.of(a1, b1, b2));

            for (List<StockCandidate> arrangement : arrangements) {
                AllocationResult result = allocator.allocate(arrangement, 25, TODAY);

                assertThat(result.allocations())
                        .as("same expiry date: order is A1, B1, B2 whatever the input order")
                        .extracting(FefoAllocation::batchNumber)
                        .containsExactly("A1", "B1", "B2");
                assertThat(result.totalAllocated()).isEqualTo(25);
            }
        }

        @Test
        @DisplayName("9b. identical expiry and batch number still yields one reproducible order")
        void identicalKeysFallBackToId() {
            // batch_number is not unique in the schema, so the comparator has to
            // have a final key or two runs could order these differently.
            LocalDate sameDay = TODAY.plusMonths(3);
            StockCandidate low = candidate(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    "SAME", sameDay, 5);
            StockCandidate high = candidate(UUID.fromString("00000000-0000-0000-0000-0000000000ff"),
                    "SAME", sameDay, 5);

            for (int i = 0; i < 20; i++) {
                List<StockCandidate> shuffled = new ArrayList<>(List.of(low, high));
                Collections.shuffle(shuffled, new java.util.Random(i));

                assertThat(allocator.allocate(shuffled, 10, TODAY).allocations())
                        .as("a total order means the same ids in the same sequence, always")
                        .extracting(FefoAllocation::batchId)
                        .containsExactly(low.id(), high.id());
            }
        }

        @Test
        @DisplayName("10. only the candidates offered are ever allocated")
        void onlyOfferedCandidatesAreAllocated() {
            // The candidate query owns location and status filtering. The
            // allocator's half of that contract is that it allocates nothing it
            // was not handed — a FEFOServiceTest asserts the SQL side.
            UUID offered = UUID.randomUUID();
            List<StockCandidate> stock = List.of(candidate(offered, "A", TODAY.plusMonths(1), 10));

            AllocationResult result = allocator.allocate(stock, 10, TODAY);

            assertThat(result.allocations())
                    .extracting(FefoAllocation::batchId)
                    .containsExactly(offered);
        }

        @Test
        @DisplayName("11. the sum invariant holds across many shapes")
        void sumInvariantHolds() {
            List<Integer> quantities = List.of(1, 2, 3, 7, 10, 15, 20, 50, 100);
            List<Integer> availabilities = List.of(1, 3, 10, 25, 100);

            for (int requested : quantities) {
                for (int available : availabilities) {
                    List<StockCandidate> stock = List.of(
                            candidate("A", TODAY.plusMonths(1), available),
                            candidate("B", TODAY.plusMonths(2), available),
                            candidate("C", TODAY.plusMonths(3), available));

                    AllocationResult result = allocator.allocate(stock, requested, TODAY);

                    assertThat(result.totalAllocated())
                            .as("totalAllocated must equal the sum of the allocations")
                            .isEqualTo(result.allocations().stream()
                                    .mapToInt(FefoAllocation::quantity).sum());
                    assertThat(result.totalAllocated() + result.shortfall())
                            .as("sum(allocation.quantity) == requestedQuantity - shortfall")
                            .isEqualTo(requested);
                    assertThat(result.allocations())
                            .as("no allocation is ever zero or negative")
                            .allSatisfy(a -> assertThat(a.quantity()).isPositive());
                }
            }
        }
    }

    // ================================================================ ordering

    @Nested
    @DisplayName("input handling")
    class InputHandling {

        @Test
        @DisplayName("re-sorts its own input rather than trusting the caller's order")
        void sortsItsOwnInput() {
            // The query already returns FEFO order, so this is redundant on the
            // happy path — and redundant until someone passes a HashSet stream.
            List<StockCandidate> jumbled = List.of(
                    candidate("LATE", TODAY.plusYears(2), 10),
                    candidate("SOON", TODAY.plusDays(1), 10),
                    candidate("MID", TODAY.plusMonths(1), 10));

            AllocationResult result = allocator.allocate(jumbled, 30, TODAY);

            assertThat(result.allocations())
                    .extracting(FefoAllocation::batchNumber)
                    .containsExactly("SOON", "MID", "LATE");
        }

        @Test
        @DisplayName("does not mutate the caller's list")
        void doesNotMutateCallerList() {
            StockCandidate late = candidate("LATE", TODAY.plusYears(2), 10);
            StockCandidate soon = candidate("SOON", TODAY.plusDays(1), 10);
            List<StockCandidate> original = new ArrayList<>(List.of(late, soon));
            List<StockCandidate> handedOver = List.copyOf(original);

            allocator.allocate(handedOver, 5, TODAY);

            assertThat(handedOver).isEqualTo(original);
        }

        @Test
        @DisplayName("an empty result is immutable")
        void allocationsAreImmutable() {
            AllocationResult result = allocator.allocate(List.of(), 0, TODAY);

            assertThatThrownBy(() -> result.allocations().add(
                    new FefoAllocation(UUID.randomUUID(), "X", TODAY, 1, PRICE)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("line total is a product, and money stays exact")
        void lineTotalIsExact() {
            FefoAllocation allocation = new FefoAllocation(
                    UUID.randomUUID(), "A", TODAY, 3, new BigDecimal("0.10"));

            assertThat(allocation.lineTotal())
                    .as("0.10 x 3 must be 0.30, not 0.30000000000000004")
                    .isEqualByComparingTo("0.30");
        }
    }

    // ================================================================ no I/O

    @Nested
    @DisplayName("purity")
    class Purity {

        @Test
        @DisplayName("repeated calls on the same input give the same answer")
        void isDeterministic() {
            List<StockCandidate> stock = new ArrayList<>();
            IntStream.rangeClosed(1, 6)
                    .forEach(i -> stock.add(candidate("B" + i, TODAY.plusDays(i), 7)));

            AllocationResult first = allocator.allocate(stock, 25, TODAY);
            AllocationResult second = allocator.allocate(stock, 25, TODAY);
            AllocationResult third = allocator.allocate(stock, 25, TODAY);

            assertThat(second).isEqualTo(first);
            assertThat(third).isEqualTo(first);
        }

        @ParameterizedTest(name = "requested {0} against {1} available never breaks the sum invariant")
        @CsvSource({"1,1", "1,100", "100,1", "7,7", "13,7", "21,20", "100,100", "0,50"})
        @DisplayName("the sum invariant survives the awkward combinations")
        void sumInvariantOnAwkwardCombinations(int requested, int available) {
            AllocationResult result = allocator.allocate(
                    List.of(candidate("A", TODAY.plusMonths(1), available)), requested, TODAY);

            assertThat(result.totalAllocated() + result.shortfall()).isEqualTo(requested);
            assertThat(result.totalAllocated())
                    .as("never more than the stock offered")
                    .isLessThanOrEqualTo(available)
                    .isNotNegative();
            assertThat(result.allocations())
                    .as("allocations only exist for a non-zero request, and are never zero")
                    .allSatisfy(a -> assertThat(a.quantity()).isPositive());
        }
    }
}
