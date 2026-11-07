package com.potlatech.pharmacy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One quantity of one product, from one supplier, with one expiry date.
 *
 * <p>AGENTS rule 1: money is {@link BigDecimal}. Never {@code Double}.
 *
 * <p>AGENTS rule: no {@code @ManyToOne}. The three parents are plain {@code UUID}
 * columns. That is partly the project rule and partly the right call — a batch's
 * medicine is a display name, not a traversal, and a {@code @ManyToOne} would pull
 * {@code Medicine} (with its {@code searchBlob} and generated {@code soundex_code})
 * into the session during a stock query. It also means the {@code StockTransaction}
 * written in the same transaction can reference this row's id before it is flushed.
 *
 * <p>The id is assigned by the application. AGENTS forbids
 * {@code GenerationType.UUID} for exactly that reason.
 *
 * <p><strong>Two quantities, not one.</strong> {@code quantityReceived} is
 * cumulative and never decreases; {@code quantityAvailable} is the balance FEFO
 * reads. Conflating them makes a write-off look like a return, and shrinkage
 * reporting cannot be built at all.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "inventory_batch")
public class InventoryBatch {

    @Id
    private UUID id;

    /** Exactly one of this and {@link #itemId} is set — enforced by {@code chk_batch_polymorphic}. */
    @Column(name = "medicine_id")
    private UUID medicineId;

    /** Exactly one of this and {@link #medicineId} is set. */
    @Column(name = "item_id")
    private UUID itemId;

    /** Nullable: stock may be received before a supplier is recorded. */
    @Column(name = "supplier_id")
    private UUID supplierId;

    /**
     * Not unique, and deliberately so. The same batch number from two suppliers
     * is not a conflict, and the same number for two different products is not
     * either. A unique index here would reject legitimate deliveries.
     */
    @Column(name = "batch_number", nullable = false, length = 100)
    private String batchNumber;

    @Column(name = "manufacturing_date")
    private LocalDate manufacturingDate;

    /** The column FEFO orders on. */
    @Column(name = "expiry_date", nullable = false)
    private LocalDate expiryDate;

    @Builder.Default
    @Column(name = "quantity_received", nullable = false)
    private Integer quantityReceived = 0;

    @Builder.Default
    @Column(name = "quantity_available", nullable = false)
    private Integer quantityAvailable = 0;

    @Column(name = "cost_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal costPrice;

    @Column(name = "selling_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal sellingPrice;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "location", nullable = false, length = 30)
    private Location location = Location.MAIN_PHARMACY;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    /**
     * Stock movement state.
     *
     * <p>{@link #DEPLETED} is <strong>derived</strong>: the service sets it when
     * {@code quantityAvailable} reaches 0, and no client may request it. It exists
     * so alert and valuation queries do not have to scan zero-quantity rows.
     */
    public enum Status {
        /** Dispensable. The only status FEFO will allocate from. */
        ACTIVE,
        /** Held back — suspect delivery, failed inspection. Not allocatable. */
        QUARANTINED,
        /** Past {@code expiryDate}. Set by the sweep or manually. */
        EXPIRED,
        /** Balance reached 0. */
        DEPLETED
    }

    /** Physical storage. A pharmacist's first question about a shortage is "where is it". */
    public enum Location {
        MAIN_PHARMACY,
        OPD_COUNTER,
        EMERGENCY,
        ICU
    }
}
