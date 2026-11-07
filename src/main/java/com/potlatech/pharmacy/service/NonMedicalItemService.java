package com.potlatech.pharmacy.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.potlatech.pharmacy.common.Specifications;
import com.potlatech.pharmacy.common.dto.PageResponse;
import com.potlatech.pharmacy.common.exception.BusinessRuleException;
import com.potlatech.pharmacy.common.exception.ResourceNotFoundException;
import com.potlatech.pharmacy.dto.catalogue.CreateNonMedicalItemRequest;
import com.potlatech.pharmacy.dto.catalogue.NonMedicalItemResponse;
import com.potlatech.pharmacy.dto.catalogue.UpdateNonMedicalItemRequest;
import com.potlatech.pharmacy.entity.NonMedicalItem;
import com.potlatech.pharmacy.repository.NonMedicalItemRepository;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Non-medical items — consumables and equipment, kept out of the medicine
 * catalogue on purpose. See {@link NonMedicalItem} for why they are a separate
 * table.
 *
 * <p>Deactivation only, no delete: batches reference items, and the batch
 * ledger is the one table in this system that must never lose a row.
 */
@Service
@Transactional(readOnly = true)
public class NonMedicalItemService {

    private final NonMedicalItemRepository items;

    public NonMedicalItemService(NonMedicalItemRepository items) {
        this.items = items;
    }

    @Transactional(readOnly = true)
    public PageResponse<NonMedicalItemResponse> list(String search, Boolean activeOnly,
                                                     NonMedicalItem.Category category,
                                                     Pageable pageable) {
        Page<NonMedicalItem> page = items.findAll(
                Specifications.allOf(
                        Specifications.textOr(search, "searchBlob", "name"),
                        Specifications.eq("isActive", activeOnly),
                        Specifications.eq("category", category)),
                pageable);
        return PageResponse.from(page.map(NonMedicalItemResponse::from));
    }

    @Transactional(readOnly = true)
    public NonMedicalItemResponse get(UUID id) {
        return NonMedicalItemResponse.from(require(id));
    }

    @Transactional
    public NonMedicalItemResponse create(CreateNonMedicalItemRequest request) {
        String name = request.normalisedName();
        if (items.existsByNameAndUnit(name, request.unit())) {
            throw new BusinessRuleException(
                    "An item named '%s' already exists in %s", name, request.unit());
        }

        NonMedicalItem item = NonMedicalItem.builder()
                .id(UUID.randomUUID())
                .name(name)
                .category(request.category())
                .unit(request.unit())
                .reorderLevel(request.resolvedReorderLevel())
                .build();

        item.setSearchBlob(CatalogueSearchText.forItem(
                item.getName(), item.getCategory().name(), item.getUnit().name()));

        return NonMedicalItemResponse.from(items.save(item));
    }

    @Transactional
    public NonMedicalItemResponse update(UUID id, UpdateNonMedicalItemRequest request) {
        NonMedicalItem item = require(id);
        String newName = request.normalisedName();

        // The duplicate check is scoped by unit, so a rename that lands on an
        // existing name in the same unit is refused — but only when the name
        // actually changes, or editing the reorder level would conflict with the
        // row's own name.
        if (newName != null && !newName.equalsIgnoreCase(item.getName())
                && items.existsByNameAndUnit(newName, item.getUnit())) {
            throw new BusinessRuleException(
                    "An item named '%s' already exists in %s", newName, item.getUnit());
        }

        if (newName != null) {
            item.setName(newName);
        }
        if (request.reorderLevel() != null) {
            item.setReorderLevel(request.reorderLevel());
        }

        // Rebuilt because a rename invalidates the old haystack; a row still
        // carrying the previous name is unsearchable by its current one.
        item.setSearchBlob(CatalogueSearchText.forItem(
                item.getName(), item.getCategory().name(), item.getUnit().name()));
        item.setUpdatedAt(LocalDateTime.now());

        return NonMedicalItemResponse.from(items.save(item));
    }

    @Transactional
    public NonMedicalItemResponse deactivate(UUID id) {
        NonMedicalItem item = require(id);
        if (Boolean.FALSE.equals(item.getIsActive())) {
            return NonMedicalItemResponse.from(item);
        }
        item.setIsActive(false);
        item.setUpdatedAt(LocalDateTime.now());
        return NonMedicalItemResponse.from(items.save(item));
    }

    @Transactional
    public NonMedicalItemResponse reactivate(UUID id) {
        NonMedicalItem item = require(id);
        if (Boolean.TRUE.equals(item.getIsActive())) {
            return NonMedicalItemResponse.from(item);
        }
        item.setIsActive(true);
        item.setUpdatedAt(LocalDateTime.now());
        return NonMedicalItemResponse.from(items.save(item));
    }

    private NonMedicalItem require(UUID id) {
        return items.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Non-medical item", id));
    }
}
