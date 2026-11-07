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

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "non_medical_item")
public class NonMedicalItem {

    @Id
    private UUID id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 50)
    private Category category;

   @Enumerated(EnumType.STRING)
    @Column(name = "unit", nullable = false, length = 30)
    private Unit unit;

    @Builder.Default
    @Column(name = "reorder_level", nullable = false)
    private Integer reorderLevel = 10;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "search_blob", nullable = false, length = 510)
    private String searchBlob;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    /** Constrained by {@code chk_item_category}. */
    public enum Category {
        /** Single-use: gloves, syringes, gauze. Tracked per batch, depletes. */
        CONSUMABLE,
        /** Durable: a sphygmomanometer. Tracked, rarely written down. */
        EQUIPMENT
    }

    /**
     * Selling unit. Constrained by {@code chk_item_unit} in {@code V4}.
     *
     * <p>An enum because {@code inventory_batch.quantity} is denominated in
     * whatever this says, and a typo would make "2" mean two boxes on one
     * screen and two pieces on another.
     */
    public enum Unit {
        PIECE,
        BOX,
        PACK,
        PAIR,
        SET
    }
}
