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
import com.potlatech.pharmacy.dto.catalogue.CreateManufacturerRequest;
import com.potlatech.pharmacy.dto.catalogue.ManufacturerResponse;
import com.potlatech.pharmacy.dto.catalogue.UpdateManufacturerRequest;
import com.potlatech.pharmacy.service.ManufacturerService;

import java.util.UUID;

/**
 * {@code /api/v1/manufacturers} — drug manufacturer reference data.
 *
 * <p>Class-level {@code @PreAuthorize}, so an endpoint added later without its
 * own annotation is denied by default. Readable by {@code CASHIER} and above:
 * a manufacturer name is printed on the pack and carries no cost or margin, so
 * withholding it from a cashier buys nothing.
 */
@RestController
@RequestMapping("/api/v1/manufacturers")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','PHARMACY_MANAGER','PHARMACIST','CASHIER')")
@Tag(name = "Catalogue", description = "Manufacturer, medicine, item and supplier reference data")
public class ManufacturerController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ManufacturerService manufacturerService;

    public ManufacturerController(ManufacturerService manufacturerService) {
        this.manufacturerService = manufacturerService;
    }

    @GetMapping
    @Operation(summary = "List manufacturers, optionally filtered by name")
    public PageResponse<ManufacturerResponse> list(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return manufacturerService.list(
                blankToNull(search),
                PageRequest.of(Math.max(page, 0), safeSize, Sort.by("name").ascending()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one manufacturer")
    public ManufacturerResponse get(@PathVariable UUID id) {
        return manufacturerService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a manufacturer")
    public ManufacturerResponse create(@Valid @RequestBody CreateManufacturerRequest request) {
        return manufacturerService.create(request);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update a manufacturer")
    public ManufacturerResponse update(@PathVariable UUID id,
                                       @Valid @RequestBody UpdateManufacturerRequest request) {
        return manufacturerService.update(id, request);
    }

    /**
     * Hard delete, unlike every other catalogue resource here.
     *
     * <p>A manufacturer holds no stock and no financial history, so it can be
     * removed outright — but only while nothing references it, which the service
     * enforces. Suppliers and medicines are deactivated instead.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a manufacturer. Refused while any medicine references it")
    public void delete(@PathVariable UUID id) {
        manufacturerService.delete(id);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
