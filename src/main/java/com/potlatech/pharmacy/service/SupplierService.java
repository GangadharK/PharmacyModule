package com.potlatech.pharmacy.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.potlatech.pharmacy.common.Specifications;
import com.potlatech.pharmacy.common.dto.PageResponse;
import com.potlatech.pharmacy.common.exception.BusinessRuleException;
import com.potlatech.pharmacy.common.exception.ResourceNotFoundException;
import com.potlatech.pharmacy.dto.catalogue.CreateSupplierRequest;
import com.potlatech.pharmacy.dto.catalogue.SupplierResponse;
import com.potlatech.pharmacy.dto.catalogue.UpdateSupplierRequest;
import com.potlatech.pharmacy.entity.Supplier;
import com.potlatech.pharmacy.repository.SupplierRepository;

import java.util.UUID;

/**
 * Suppliers — CRUD for the counterparties purchase orders are raised against.
 *
 * <p>No delete endpoint. A supplier is deactivated instead, because purchase
 * orders and inventory batches reference it and those are financial records;
 * a hard delete would either fail on the FK at the worst moment or, with a
 * cascade, take a purchase history with it. Deactivation keeps the supplier
 * readable on every historical row and out of new-order dropdowns.
 */
@Service
@Transactional(readOnly = true)
public class SupplierService {

    private final SupplierRepository suppliers;

    public SupplierService(SupplierRepository suppliers) {
        this.suppliers = suppliers;
    }

    /**
     * Filtered, paginated list.
     *
     * @param search    case-insensitive substring of the name, or {@code null} for all
     * @param activeOnly {@code true} returns active suppliers only — the set a
     *                   purchase order may be raised against; {@code false} returns
     *                   deactivated ones; {@code null} applies no filter and
     *                   returns both.
     */
    @Transactional(readOnly = true)
    public PageResponse<SupplierResponse> list(String search, Boolean activeOnly, Pageable pageable) {
        Page<Supplier> page = suppliers.findAll(
                Specifications.allOf(
                        Specifications.likeLower("name", search),
                        Specifications.eq("isActive", activeOnly)),
                pageable);
        return PageResponse.from(page.map(SupplierResponse::from));
    }

    @Transactional(readOnly = true)
    public SupplierResponse get(UUID id) {
        return SupplierResponse.from(require(id));
    }

    @Transactional
    public SupplierResponse create(CreateSupplierRequest request) {
        String name = request.normalisedName();
        if (suppliers.existsByName(name)) {
            throw new BusinessRuleException("A supplier named '%s' already exists", name);
        }

        Supplier supplier = Supplier.builder()
                .id(UUID.randomUUID())
                .name(name)
                .drugLicenseNo(blankToNull(request.drugLicenseNo()))
                .contactPerson(blankToNull(request.contactPerson()))
                .phone(blankToNull(request.phone()))
                .email(blankToNull(request.email()))
                .address(blankToNull(request.address()))
                .gstNumber(blankToNull(request.gstNumber()))
                .build();

        return SupplierResponse.from(suppliers.save(supplier));
    }

    @Transactional
    public SupplierResponse update(UUID id, UpdateSupplierRequest request) {
        Supplier supplier = require(id);
        String newName = request.normalisedName();

        if (newName != null && !newName.equalsIgnoreCase(supplier.getName())
                && suppliers.existsByName(newName)) {
            throw new BusinessRuleException("A supplier named '%s' already exists", newName);
        }

        if (newName != null) {
            supplier.setName(newName);
        }
        if (request.drugLicenseNo() != null) {
            supplier.setDrugLicenseNo(blankToNull(request.drugLicenseNo()));
        }
        if (request.contactPerson() != null) {
            supplier.setContactPerson(blankToNull(request.contactPerson()));
        }
        if (request.phone() != null) {
            supplier.setPhone(blankToNull(request.phone()));
        }
        if (request.email() != null) {
            supplier.setEmail(blankToNull(request.email()));
        }
        if (request.address() != null) {
            supplier.setAddress(blankToNull(request.address()));
        }
        if (request.gstNumber() != null) {
            supplier.setGstNumber(blankToNull(request.gstNumber()));
        }
        // updatedAt is set here rather than by a database trigger: see the V4
        // header. A test that injects a fixed clock must see the same timestamp
        // the service wrote, not a second writer's idea of now.
        supplier.setUpdatedAt(java.time.LocalDateTime.now());

        return SupplierResponse.from(suppliers.save(supplier));
    }

    /**
     * Sets {@code isActive = false} so the supplier leaves new-order dropdowns
     * while remaining readable on every historical purchase order and batch.
     */
    @Transactional
    public SupplierResponse deactivate(UUID id) {
        Supplier supplier = require(id);
        if (Boolean.FALSE.equals(supplier.getIsActive())) {
            return SupplierResponse.from(supplier);
        }
        supplier.setIsActive(false);
        supplier.setUpdatedAt(java.time.LocalDateTime.now());
        return SupplierResponse.from(suppliers.save(supplier));
    }

    @Transactional
    public SupplierResponse reactivate(UUID id) {
        Supplier supplier = require(id);
        if (Boolean.TRUE.equals(supplier.getIsActive())) {
            return SupplierResponse.from(supplier);
        }
        supplier.setIsActive(true);
        supplier.setUpdatedAt(java.time.LocalDateTime.now());
        return SupplierResponse.from(supplier);
    }

    private Supplier require(UUID id) {
        return suppliers.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier", id));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
