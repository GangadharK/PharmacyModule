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
@Table(name = "medicine_catalogue")
public class Medicine {

    @Id
    private UUID id;

    @Column(name = "generic_name", nullable = false, length = 255)
    private String genericName;

    @Column(name = "brand_name", length = 255)
    private String brandName;

    @Column(name = "strength", length = 100)
    private String strength;

    @Enumerated(EnumType.STRING)
    @Column(name = "dosage_form", nullable = false, length = 50)
    private DosageForm dosageForm;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "schedule_class", nullable = false, length = 10)
    private ScheduleClass scheduleClass = ScheduleClass.H;

    @Builder.Default
    @Column(name = "requires_prescription", nullable = false)
    private Boolean requiresPrescription = false;

    @Column(name = "manufacturer_id")
    private UUID manufacturerId;


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


    public enum DosageForm {
        TABLET,
        SYRUP,
        INJECTION,
        CAPSULE,
        DROPS,
        CREAM,
        OINTMENT,
        INHALER
    }

    public enum ScheduleClass {
        OTC,
        H,
        H1
    }
}
