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
import com.potlatech.pharmacy.dto.catalogue.CreateSupplierRequest;
import com.potlatech.pharmacy.dto.catalogue.SupplierResponse;
import com.potlatech.pharmacy.dto.catalogue.UpdateSupplierRequest;
import com.potlatech.pharmacy.service.SupplierService;

import java.util.UUID;

/**
 * {@code /api/v1/suppliers} — purchase counterparties.
 *
 * <p>Reads require {@code PHARMACIST} and above; writes require
 * {@code PHARMACY_MANAGER} and above. Method-level rather than class-level
 * because the two differ here: a pharmacist creating a purchase order needs to
 * read a supplier's name and licence, but adding a new counterparty is a
 * management decision.
 *
 * <p>{@code DELETE} is a deactivation. There is no hard delete endpoint, because
 * purchase orders and stock batches reference suppliers and those are financial
 * records.
 */
@RestController
@RequestMapping("/api/v1/suppliers")
@Tag(name = "Catalogue", description = "Manufacturer, medicine, item and supplier reference data")
public class SupplierController {

    private static final int MAX_PAGE_SIZE = 100;

    private final SupplierService supplierService;

    public SupplierController(SupplierService supplierService) {
        this.supplierService = supplierService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PHARMACY_MANAGER','PHARMACIST')")
    @Operation(summary = "List suppliers, optionally filtered by name and active flag")
    public PageResponse<SupplierResponse> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean activeOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return supplierService.list(
                blankToNull(search), activeOnly,
                PageRequest.of(Math.max(page, 0), safeSize, Sort.by("name").ascending()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PHARMACY_MANAGER','PHARMACIST')")
    @Operation(summary = "Fetch one supplier")
    public SupplierResponse get(@PathVariable UUID id) {
        return supplierService.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PHARMACY_MANAGER')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a supplier")
    public SupplierResponse create(@Valid @RequestBody CreateSupplierRequest request) {
        return supplierService.create(request);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PHARMACY_MANAGER')")
    @Operation(summary = "Update a supplier")
    public SupplierResponse update(@PathVariable UUID id,
                                   @Valid @RequestBody UpdateSupplierRequest request) {
        return supplierService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PHARMACY_MANAGER')")
    @Operation(summary = "Deactivate a supplier. History is retained")
    public SupplierResponse deactivate(@PathVariable UUID id) {
        return supplierService.deactivate(id);
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','PHARMACY_MANAGER')")
    @Operation(summary = "Reactivate a deactivated supplier")
    public SupplierResponse reactivate(@PathVariable UUID id) {
        return supplierService.reactivate(id);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
