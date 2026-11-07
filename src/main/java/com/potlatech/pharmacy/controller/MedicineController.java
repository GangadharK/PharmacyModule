package com.potlatech.pharmacy.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.potlatech.pharmacy.common.dto.PageResponse;
import com.potlatech.pharmacy.dto.catalogue.CreateMedicineRequest;
import com.potlatech.pharmacy.dto.catalogue.MedicineResponse;
import com.potlatech.pharmacy.dto.catalogue.UpdateMedicineRequest;
import com.potlatech.pharmacy.entity.Medicine;
import com.potlatech.pharmacy.service.MedicineService;

import java.util.UUID;

/**
 * {@code /api/v1/medicines} — the medicine catalogue.
 *
 * <p>Readable by {@code CASHIER} and above: a cashier must read a medicine's
 * name, strength and form to dispense it, and none of that is a cost figure.
 * Writes are restricted to {@code PHARMACY_MANAGER} and above.
 *
 * <p>{@code DELETE} is a deactivation — a discontinued medicine keeps its
 * batches and its dispensing history.
 */
@RestController
@RequestMapping("/api/v1/medicines")
@Tag(name = "Catalogue", description = "Manufacturer, medicine, item and supplier reference data")
public class MedicineController {

    private static final int MAX_PAGE_SIZE = 100;

    private final MedicineService medicineService;

    public MedicineController(MedicineService medicineService) {
        this.medicineService = medicineService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PHARMACY_MANAGER','PHARMACIST','CASHIER')")
    @Operation(summary = "Search the medicine catalogue")
    public PageResponse<MedicineResponse> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean activeOnly,
            @RequestParam(required = false) Medicine.DosageForm dosageForm,
            @RequestParam(required = false) Medicine.ScheduleClass scheduleClass,
            @RequestParam(required = false) UUID manufacturerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return medicineService.list(
                blankToNull(search), activeOnly, dosageForm, scheduleClass, manufacturerId,
                PageRequest.of(Math.max(page, 0), safeSize, Sort.by("genericName").ascending()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PHARMACY_MANAGER','PHARMACIST','CASHIER')")
    @Operation(summary = "Fetch one medicine")
    public MedicineResponse get(@PathVariable UUID id) {
        return medicineService.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PHARMACY_MANAGER')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a medicine")
    public MedicineResponse create(@Valid @RequestBody CreateMedicineRequest request) {
        return medicineService.create(request);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PHARMACY_MANAGER')")
    @Operation(summary = "Update a medicine")
    public MedicineResponse update(@PathVariable UUID id,
                                   @Valid @RequestBody UpdateMedicineRequest request) {
        return medicineService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PHARMACY_MANAGER')")
    @Operation(summary = "Deactivate a medicine. Batches and history are retained")
    public MedicineResponse deactivate(@PathVariable UUID id) {
        return medicineService.deactivate(id);
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PHARMACY_MANAGER')")
    @Operation(summary = "Reactivate a deactivated medicine")
    public MedicineResponse reactivate(@PathVariable UUID id) {
        return medicineService.reactivate(id);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
