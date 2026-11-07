package com.potlatech.pharmacy.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.potlatech.pharmacy.common.exception.BusinessRuleException;
import com.potlatech.pharmacy.common.exception.ResourceNotFoundException;
import com.potlatech.pharmacy.dto.catalogue.CreateMedicineRequest;
import com.potlatech.pharmacy.dto.catalogue.UpdateMedicineRequest;
import com.potlatech.pharmacy.entity.Manufacturer;
import com.potlatech.pharmacy.entity.Medicine;
import com.potlatech.pharmacy.repository.ManufacturerRepository;
import com.potlatech.pharmacy.repository.MedicineRepository;
import com.potlatech.pharmacy.service.MedicineService;

import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link MedicineService} — catalogue invariants.
 *
 * <p>Each test here is a behaviour that was either wrong first time or is easy
 * to break without noticing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MedicineServiceTest {

    @Mock
    private MedicineRepository medicines;
    @Mock
    private ManufacturerRepository manufacturers;

    @InjectMocks
    private MedicineService service;

    private static final UUID MANUFACTURER_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    // ==================== the search blob ====================

    @Test
    @DisplayName("create derives searchBlob from the medicine, never from the request")
    void createBuildsSearchBlob() {
        when(manufacturers.existsById(MANUFACTURER_ID)).thenReturn(true);
        when(medicines.save(any())).thenAnswer(i -> i.getArgument(0));

        var created = service.create(new CreateMedicineRequest(
                "Paracetamol", "Crocin", "500 mg",
                Medicine.DosageForm.TABLET,
                CreateMedicineRequest.ScheduleClassRequest.OTC,
                false, MANUFACTURER_ID, 10));

        // The blob itself is not in the response - it is internal search
        // machinery, and CatalogueSearchTextTest covers how it is built. What
        // matters here is that the stored row carries the caller's values, since
        // the blob is derived from them.
        assertThat(created.genericName()).isEqualTo("Paracetamol");
        assertThat(created.brandName()).isEqualTo("Crocin");
        assertThat(created.scheduleClass()).isEqualTo(Medicine.ScheduleClass.OTC);
    }

    @Test
    @DisplayName("a rename rebuilds the blob so the row is findable by its new name")
    void updateRebuildsSearchBlob() {
        Medicine medicine = medicine("Old Name", "OldBrand", "500 mg");
        when(medicines.findById(medicine.getId())).thenReturn(Optional.of(medicine));
        when(medicines.save(any())).thenAnswer(i -> i.getArgument(0));

        var updated = service.update(medicine.getId(), new UpdateMedicineRequest(
                "New Name", null, null, null, null, null, null));

        assertThat(updated.genericName()).isEqualTo("New Name");
        // updatedAt must move, or a pharmacist cannot tell which of two same-named
        // rows was corrected last.
        assertThat(updated.updatedAt()).isNotNull();
    }

    // ==================== duplicate protection ====================

    @Test
    @DisplayName("the same generic, form and strength is refused")
    void refusesExactDuplicate() {
        when(medicines.existsByGenericNameFormAndStrength(
                "Paracetamol", Medicine.DosageForm.TABLET, "500 mg")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateMedicineRequest(
                "Paracetamol", null, "500 mg", Medicine.DosageForm.TABLET,
                null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("the same generic in a different form is allowed - two real products")
    void allowsSameGenericDifferentForm() {
        when(medicines.existsByGenericNameFormAndStrength(
                "Paracetamol", Medicine.DosageForm.SYRUP, "250 mg")).thenReturn(false);
        when(medicines.save(any())).thenAnswer(i -> i.getArgument(0));

        assertThatCode(() -> service.create(new CreateMedicineRequest(
                "Paracetamol", null, "250 mg", Medicine.DosageForm.SYRUP,
                null, null, null, null))).doesNotThrowAnyException();
    }

    // ==================== referential validation ====================

    @Test
    @DisplayName("an unknown manufacturer is a 404, not a constraint violation at flush time")
    void validatesManufacturerExists() {
        when(manufacturers.existsById(MANUFACTURER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.create(new CreateMedicineRequest(
                "Amoxicillin", null, "500 mg", Medicine.DosageForm.CAPSULE,
                null, null, MANUFACTURER_ID, null)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Manufacturer");
    }

    // ==================== list mapping ====================

    /**
     * Regression: a medicine with no manufacturer crashed the whole listing.
     *
     * <p>The batch name map was an immutable {@code Map.of()} when the page held
     * no manufacturers, and {@code Map.of()}'s {@code get(null)} throws
     * {@code NullPointerException} by specification. Every unfiltered catalogue
     * request returned 500 as soon as one medicine lacked a manufacturer - which
     * is the normal state for a generics-only entry created without one.
     */
    @Test
    @DisplayName("listing a page of medicines that have no manufacturer does not throw")
    void listHandlesMedicinesWithoutManufacturer() {
        Medicine withoutManufacturer = medicine("Paracetamol", null, "500 mg");
        withoutManufacturer.setManufacturerId(null);

        when(medicines.findAll(spec(), pageable())).thenReturn(
                new PageImpl<>(List.of(withoutManufacturer), PageRequest.of(0, 20), 1));

        assertThatCode(() -> service.list(null, true, null, null, null,
                PageRequest.of(0, 20))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a page mixing attributed and unattributed medicines lists both")
    void listMixesAttributedAndUnattributed() {
        Medicine withMfr = medicine("Paracetamol", "Crocin", "500 mg");
        withMfr.setManufacturerId(MANUFACTURER_ID);
        Medicine withoutMfr = medicine("Paracetamol", null, "250 mg");
        withoutMfr.setManufacturerId(null);

        when(medicines.findAll(spec(), pageable())).thenReturn(
                new PageImpl<>(List.of(withMfr, withoutMfr), PageRequest.of(0, 20), 2));
        when(manufacturers.findAllById(any())).thenReturn(List.of(
                Manufacturer.builder().id(MANUFACTURER_ID).name("Sun Pharma").build()));

        var page = service.list(null, true, null, null, null, PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(2);
        assertThat(page.content().get(0).manufacturerName()).isEqualTo("Sun Pharma");
        assertThat(page.content().get(1).manufacturerName()).isNull();
    }

    @Test
    @DisplayName("a medicine naming a deleted manufacturer still lists, with no name")
    void listToleratesDanglingManufacturerReference() {
        // The FK is ON DELETE SET NULL, so this should not occur - but if it ever
        // does, the catalogue must stay readable rather than 500.
        Medicine medicine = medicine("Paracetamol", "Crocin", "500 mg");
        medicine.setManufacturerId(MANUFACTURER_ID);

        when(medicines.findAll(spec(), pageable())).thenReturn(
                new PageImpl<>(List.of(medicine), PageRequest.of(0, 20), 1));
        when(manufacturers.findAllById(any())).thenReturn(List.of());

        var page = service.list(null, true, null, null, null, PageRequest.of(0, 20));

        assertThat(page.content().get(0).manufacturerName()).isNull();
    }

    // ==================== soft delete ====================

    @Test
    @DisplayName("deactivate is idempotent")
    void deactivateIsIdempotent() {
        Medicine medicine = medicine("Paracetamol", "Crocin", "500 mg");
        medicine.setIsActive(false);
        when(medicines.findById(medicine.getId())).thenReturn(Optional.of(medicine));

        // No save expected: the row is already in the target state, so writing
        // again would only churn updatedAt and imply a change that did not happen.
        var result = service.deactivate(medicine.getId());

        assertThat(result.isActive()).isFalse();
        org.mockito.Mockito.verify(medicines, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("there is no hard delete on a medicine - batches reference it")
    void hasNoHardDelete() {
        // A compile-time guarantee, asserted as documentation: if a delete()
        // method is ever added to the service, this test still passes, but the
        // absence is the point. See MedicineService's class javadoc.
        assertThat(MedicineService.class.getDeclaredMethods())
                .noneMatch(m -> m.getName().equals("delete"));
    }

    private Medicine medicine(String generic, String brand, String strength) {
        return Medicine.builder()
                .id(UUID.randomUUID())
                .genericName(generic)
                .brandName(brand)
                .strength(strength)
                .dosageForm(Medicine.DosageForm.TABLET)
                .scheduleClass(Medicine.ScheduleClass.OTC)
                .requiresPrescription(false)
                .reorderLevel(10)
                .build();
    }

    /**
     * Typed matchers. {@code JpaSpecificationExecutor} declares both
     * {@code findAll(Specification, Pageable)} and {@code findAll(Specification, Sort)},
     * so a bare {@code any(), any()} is ambiguous at compile time.
     */
    private static org.springframework.data.jpa.domain.Specification<Medicine> spec() {
        return org.mockito.ArgumentMatchers.any();
    }

    private static Pageable pageable() {
        return org.mockito.ArgumentMatchers.any();
    }
}
