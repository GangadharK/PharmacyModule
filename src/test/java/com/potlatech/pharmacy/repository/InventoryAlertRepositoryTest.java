package com.potlatech.pharmacy.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.TestPropertySource;

import com.potlatech.pharmacy.dto.alert.StockAlertResponse;
import com.potlatech.pharmacy.entity.InventoryBatch;
import com.potlatech.pharmacy.entity.Medicine;
import com.potlatech.pharmacy.entity.NonMedicalItem;
import com.potlatech.pharmacy.repository.InventoryAlertRepository;
import com.potlatech.pharmacy.repository.InventoryBatchRepository;
import com.potlatech.pharmacy.repository.MedicineRepository;
import com.potlatech.pharmacy.repository.NonMedicalItemRepository;
import com.potlatech.pharmacy.repository.projection.ExpiryAlertRow;
import com.potlatech.pharmacy.repository.projection.LowStockRow;
import com.potlatech.pharmacy.service.CatalogueSearchText;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two alert queries, against a real PostgreSQL.
 *
 * <p>These are the queries most likely to be quietly wrong in a way that still
 * returns rows: a low-stock report that flags everything looks like an alert
 * screen rather than a bug, and an expiry screen that omits a batch looks like
 * good news. So the cases pinned here are the ones a naive query gets wrong while
 * appearing to work — summing across batches, ignoring quarantined stock, the
 * arithmetic boundary of the severity threshold.
 *
 * <p><b>Every assertion is scoped to this test's own fixtures.</b> The dev
 * database is shared and already holds rows committed by other suites, so a
 * count over the whole result set is an assertion about the machine rather than
 * about the query. Each fixture name is unique to a single test for that reason.
 *
 * <p>{@code replace = NONE} on purpose: these queries use {@code ::int} casts and
 * a {@code UNION ALL} over a subquery, none of which an in-memory database
 * implements the same way.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=none")
class InventoryAlertRepositoryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 26);

    @Autowired
    private InventoryAlertRepository alerts;

    @Autowired
    private InventoryBatchRepository batches;

    @Autowired
    private MedicineRepository medicines;

    @Autowired
    private NonMedicalItemRepository items;

    // ==================== Low stock ====================

    @Nested
    @DisplayName("low stock")
    class LowStock {

        @Test
        @DisplayName("sums across batches, because the reorder level is a claim about the medicine")
        void sumsAcrossBatches() {
            // Three batches of 4 against a level of 10: 12 held, so not low. Testing
            // batches individually flags all three, which is the specific mistake
            // this query exists to avoid.
            Medicine m = medicine("LS-Sum", 10);
            batch(m, 4, TODAY.plusYears(2));
            batch(m, 4, TODAY.plusYears(3));
            batch(m, 4, TODAY.plusYears(4));

            assertThat(rowsFor(m))
                    .as("12 held against a level of 10 is not low, even though no single "
                        + "batch reaches the level on its own")
                    .isEmpty();

            m.setReorderLevel(12);
            medicines.save(m);
            medicines.flush();

            assertThat(rowsFor(m))
                    .as("and 12 against a level of 12 is: the comparison is <=, so a "
                        + "medicine sitting exactly on its reorder level is flagged")
                    .hasSize(1);
        }

        @Test
        @DisplayName("counts quarantined stock as absent, because it cannot be sold")
        void excludesQuarantinedStock() {
            Medicine m = medicine("LS-Quar", 10);
            InventoryBatch quarantined = batch(m, 4, TODAY.plusYears(2));
            setStatus(quarantined, InventoryBatch.Status.QUARANTINED);

            assertThat(rowsFor(m))
                    .as("4 units of quarantined stock is not 4 units on the shelf")
                    .singleElement()
                    .satisfies(r -> assertThat(alert(r).severity())
                            .isEqualTo(StockAlertResponse.Severity.OUT_OF_STOCK));
        }

        @Test
        @DisplayName("ignores a reorder level of zero, rather than flagging every stocked line")
        void ignoresZeroReorderLevel() {
            Medicine stocked = medicine("LS-Zero", 0);
            batch(stocked, 500, TODAY.plusYears(2));
            Medicine empty = medicine("LS-Zero-Empty", 0);

            assertThat(rowsFor(stocked))
                    .as("zero means 'do not reorder', not 'reorder when you reach zero' — "
                        + "flagging every stocked line forever is how an alert screen "
                        + "becomes wallpaper and stops being read")
                    .isEmpty();
            assertThat(rowsFor(empty))
                    .as("including one holding nothing at all, which would be flagged by "
                        + "the missing-reorder-level case on every medicine in the shop")
                    .isEmpty();
        }

        @Test
        @DisplayName("grades severity, and CRITICAL is a quarter of the reorder level")
        void gradesSeverity() {
            Medicine critical = medicine("LS-Critical", 10);
            Medicine low = medicine("LS-Low", 10);
            Medicine out = medicine("LS-Out", 10);

            // 2 of 10: exactly a quarter, so the <= boundary puts it in CRITICAL.
            batch(critical, 2, TODAY.plusYears(2));
            batch(low, 9, TODAY.plusYears(2));

            assertThat(rowsFor(critical))
                    .singleElement()
                    .satisfies(r -> assertThat(alert(r).severity())
                            .isEqualTo(StockAlertResponse.Severity.CRITICAL));
            assertThat(rowsFor(low))
                    .singleElement()
                    .satisfies(r -> assertThat(alert(r).severity())
                            .isEqualTo(StockAlertResponse.Severity.LOW));
            assertThat(rowsFor(out))
                    .singleElement()
                    .satisfies(r -> assertThat(alert(r).severity())
                            .isEqualTo(StockAlertResponse.Severity.OUT_OF_STOCK));
        }

        @Test
        @DisplayName("reports the shortfall and the batch count, which is what the row renders")
        void reportsShortfallAndBatchCount() {
            Medicine m = medicine("LS-Shape", 10);
            batch(m, 3, TODAY.plusYears(2));
            batch(m, 4, TODAY.plusYears(3));

            assertThat(rowsFor(m))
                    .singleElement()
                    .satisfies(r -> {
                        assertThat(r.getTotalAvailable()).isEqualTo(7);
                        assertThat(r.getActiveBatchCount()).isEqualTo(2);
                        assertThat(alert(r).shortfall())
                                .as("the number a purchase order needs, so the screen can "
                                    + "offer a quantity instead of making the buyer work it out")
                                .isEqualTo(3);
                    });
        }

        @Test
        @DisplayName("leaves out an inactive medicine: a discontinued line is not an alert")
        void excludesInactiveMedicines() {
            Medicine m = medicine("LS-Dead", 10);
            m.setIsActive(false);
            medicines.save(m);
            medicines.flush();

            assertThat(rowsFor(m))
                    .as("flagging discontinued stock forever is how an alert screen "
                        + "becomes wallpaper")
                    .isEmpty();
        }

        @Test
        @DisplayName("reports non-medical stock on the same screen as medicines")
        void includesNonMedicalItems() {
            NonMedicalItem item = item("LS-Syringe", 20);
            itemBatch(item, 5);

            assertThat(describe(alerts.findLowStock()))
                    .as("a shelf of syringes is stock, and a reorder level on it is a "
                        + "reorder level like any other; expected a row starting "
                        + "[" + item.getName() + "]")
                    .anyMatch(d -> d.startsWith(item.getName()) && d.contains("NON_MEDICAL")
                            && d.contains("held=5 level=20"));
        }
    }

    // ==================== Near expiry ====================

    @Nested
    @DisplayName("near expiry")
    class NearExpiry {

        @Test
        @DisplayName("counts days from the window, and orders the soonest first")
        void ordersSoonestFirst() {
            Medicine m = medicine("NE-Window", 0);
            batch(m, 5, TODAY.plusDays(5));
            batch(m, 5, TODAY.plusDays(40));
            batch(m, 5, TODAY.plusDays(200));

            List<ExpiryAlertRow> mine = expiringFor(m);

            assertThat(mine)
                    .as("a 200-day expiry is not a 90-day expiry, whatever the stock level")
                    .hasSize(2);
            assertThat(mine.getFirst())
                    .as("soonest first: the list is worked top to bottom by whoever is on "
                        + "the counter, and burying the urgent row is the same as hiding it")
                    .satisfies(r -> assertThat(r.getDaysRemaining()).isEqualTo(5));
        }

        @Test
        @DisplayName("excludes depleted batches but keeps quarantined ones")
        void excludesDepletedAndKeepsQuarantined() {
            Medicine m = medicine("NE-Status", 0);
            InventoryBatch soon = batch(m, 5, TODAY.plusDays(10));
            InventoryBatch quarantined = batch(m, 5, TODAY.plusDays(12));
            InventoryBatch depleted = batch(m, 5, TODAY.plusDays(11));

            setStatus(quarantined, InventoryBatch.Status.QUARANTINED);
            setStatus(depleted, InventoryBatch.Status.DEPLETED);

            assertThat(expiringFor(m))
                    .as("two batches: the live one and the quarantined one. A depleted "
                        + "batch has nothing left to destroy, and a quarantined one still "
                        + "has to be physically destroyed on its date — it is not sellable, "
                        + "but it is not gone either, and dropping it would hide a pile of "
                        + "stock someone has to deal with")
                    .extracting(ExpiryAlertRow::getBatchId)
                    .containsExactlyInAnyOrder(soon.getId(), quarantined.getId());
        }

        @Test
        @DisplayName("still lists a batch that is past its date but not yet written off")
        void listsUnwrittenOffExpiry() {
            Medicine m = medicine("NE-Past", 0);
            InventoryBatch stale = batch(m, 5, TODAY.minusDays(1));
            InventoryBatch writtenOff = batch(m, 5, TODAY.minusDays(2));
            setStatus(writtenOff, InventoryBatch.Status.EXPIRED);

            assertThat(expiringFor(m))
                    .as("a batch past its expiry date that is still ACTIVE is still on the "
                        + "shelf and still needs writing off — hiding it would be the "
                        + "opposite of helpful. The one already marked EXPIRED is done with")
                    .extracting(ExpiryAlertRow::getBatchId)
                    .containsExactly(stale.getId());
        }
    }

    // ==================== Helpers ====================

    /** Low-stock rows for one medicine only. */
    private List<LowStockRow> rowsFor(Medicine medicine) {
        Predicate<LowStockRow> mine = r -> r.getId().equals(medicine.getId());
        return alerts.findLowStock().stream().filter(mine).toList();
    }

    /** Expiry rows for one medicine's batches only. */
    private List<ExpiryAlertRow> expiringFor(Medicine medicine) {
        return alerts.findExpiringSoon(TODAY, 90).stream()
                .filter(r -> medicine.getId().equals(r.getMedicineId()))
                .toList();
    }

    /** The rows as readable strings, so a failure names the row it got. */
    private static List<String> describe(List<LowStockRow> rows) {
        return rows.stream()
                .map(r -> r.getItemName() + " [" + r.getItemType() + "] "
                        + "held=" + r.getTotalAvailable() + " level=" + r.getReorderLevel())
                .toList();
    }

    /**
     * The row as the screen will see it.
     *
     * <p>Shortfall and severity are derived in {@link StockAlertResponse#of} rather
     * than in SQL — the thresholds are policy and belong somewhere readable — so
     * they are asserted through the factory instead of the projection.
     */
    private static StockAlertResponse alert(LowStockRow row) {
        return StockAlertResponse.of(row.getId(), row.getItemName(), row.getItemType(),
                row.getForm(), row.getStrength(), row.getTotalAvailable(),
                row.getReorderLevel(), row.getActiveBatchCount());
    }

    private Medicine medicine(String prefix, int reorderLevel) {
        String name = prefix + "-" + UUID.randomUUID().toString().substring(0, 6);
        return medicines.save(Medicine.builder()
                .id(UUID.randomUUID())
                .genericName(name)
                .dosageForm(Medicine.DosageForm.TABLET)
                .scheduleClass(Medicine.ScheduleClass.H)
                .reorderLevel(reorderLevel)
                .isActive(true)
                .searchBlob(CatalogueSearchText.forMedicine(name, null, null, null))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }

    private NonMedicalItem item(String prefix, int reorderLevel) {
        String name = prefix + "-" + UUID.randomUUID().toString().substring(0, 6);
        // Setters rather than the builder, to match the readable style of the
        // fixture above; the entity has both.
        NonMedicalItem item = new NonMedicalItem();
        item.setId(UUID.randomUUID());
        item.setName(name);
        item.setCategory(NonMedicalItem.Category.CONSUMABLE);
        item.setUnit(NonMedicalItem.Unit.PIECE);
        item.setReorderLevel(reorderLevel);
        item.setIsActive(true);
        item.setSearchBlob(CatalogueSearchText.forItem(name, "Consumable", "Piece"));
        item.setCreatedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        return items.save(item);
    }

    private InventoryBatch batch(Medicine medicine, int quantity, LocalDate expiry) {
        return batches.saveAndFlush(InventoryBatch.builder()
                .id(UUID.randomUUID())
                .medicineId(medicine.getId())
                .batchNumber("LOT-" + UUID.randomUUID().toString().substring(0, 6))
                .expiryDate(expiry)
                .quantityReceived(quantity)
                .quantityAvailable(quantity)
                .costPrice(new BigDecimal("10.00"))
                .sellingPrice(new BigDecimal("15.00"))
                .location(InventoryBatch.Location.MAIN_PHARMACY)
                .status(InventoryBatch.Status.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }

    private InventoryBatch itemBatch(NonMedicalItem item, int quantity) {
        return batches.saveAndFlush(InventoryBatch.builder()
                .id(UUID.randomUUID())
                .itemId(item.getId())
                .batchNumber("LOT-" + UUID.randomUUID().toString().substring(0, 6))
                .expiryDate(TODAY.plusYears(3))
                .quantityReceived(quantity)
                .quantityAvailable(quantity)
                .costPrice(new BigDecimal("1.00"))
                .sellingPrice(new BigDecimal("2.00"))
                .location(InventoryBatch.Location.MAIN_PHARMACY)
                .status(InventoryBatch.Status.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }

    private void setStatus(InventoryBatch batch, InventoryBatch.Status status) {
        InventoryBatch managed = batches.findById(batch.getId()).orElseThrow();
        managed.setStatus(status);
        batches.save(managed);
        batches.flush();
    }
}
