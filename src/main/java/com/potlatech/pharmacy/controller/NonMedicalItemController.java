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
import com.potlatech.pharmacy.dto.catalogue.CreateNonMedicalItemRequest;
import com.potlatech.pharmacy.dto.catalogue.NonMedicalItemResponse;
import com.potlatech.pharmacy.dto.catalogue.UpdateNonMedicalItemRequest;
import com.potlatech.pharmacy.entity.NonMedicalItem;
import com.potlatech.pharmacy.service.NonMedicalItemService;

import java.util.UUID;

/**
 * {@code /api/v1/items} — consumables and equipment.
 *
 * <p>Plural resource named {@code items} rather than {@code non-medical-items}:
 * the path is what a client hardcodes once, and a shorter, unsurprising segment
 * is worth more than mirroring the table name. The class and response type both
 * keep the full {@code NonMedicalItem} name, so the distinction from a medicine
 * is never lost in the code.
 *
 * <p>{@code DELETE} is a deactivation; batches reference items.
 */
@RestController
@RequestMapping("/api/v1/items")
@Tag(name = "Catalogue", description = "Manufacturer, medicine, item and supplier reference data")
public class NonMedicalItemController {

    private static final int MAX_PAGE_SIZE = 100;

    private final NonMedicalItemService itemService;

    public NonMedicalItemController(NonMedicalItemService itemService) {
        this.itemService = itemService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PHARMACY_MANAGER','PHARMACIST','CASHIER')")
    @Operation(summary = "List non-medical items, optionally filtered by text and category")
    public PageResponse<NonMedicalItemResponse> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean activeOnly,
            @RequestParam(required = false) NonMedicalItem.Category category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return itemService.list(
                blankToNull(search), activeOnly, category,
                PageRequest.of(Math.max(page, 0), safeSize, Sort.by("name").ascending()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PHARMACY_MANAGER','PHARMACIST','CASHIER')")
    @Operation(summary = "Fetch one non-medical item")
    public NonMedicalItemResponse get(@PathVariable UUID id) {
        return itemService.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PHARMACY_MANAGER')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a non-medical item")
    public NonMedicalItemResponse create(@Valid @RequestBody CreateNonMedicalItemRequest request) {
        return itemService.create(request);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PHARMACY_MANAGER')")
    @Operation(summary = "Update a non-medical item. Category and unit are immutable")
    public NonMedicalItemResponse update(@PathVariable UUID id,
                                         @Valid @RequestBody UpdateNonMedicalItemRequest request) {
        return itemService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PHARMACY_MANAGER')")
    @Operation(summary = "Deactivate a non-medical item. Batches are retained")
    public NonMedicalItemResponse deactivate(@PathVariable UUID id) {
        return itemService.deactivate(id);
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PHARMACY_MANAGER')")
    @Operation(summary = "Reactivate a deactivated item")
    public NonMedicalItemResponse reactivate(@PathVariable UUID id) {
        return itemService.reactivate(id);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
