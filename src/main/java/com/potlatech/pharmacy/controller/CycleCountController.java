package com.potlatech.pharmacy.controller;

import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.potlatech.pharmacy.common.dto.PageResponse;
import com.potlatech.pharmacy.dto.cyclecount.CountLineRequest;
import com.potlatech.pharmacy.dto.cyclecount.CreateCycleCountRequest;
import com.potlatech.pharmacy.dto.cyclecount.CycleCountLineResponse;
import com.potlatech.pharmacy.dto.cyclecount.CycleCountResponse;
import com.potlatech.pharmacy.dto.cyclecount.PostCycleCountResponse;
import com.potlatech.pharmacy.entity.CycleCount;
import com.potlatech.pharmacy.service.CycleCountService;

import java.util.UUID;

/**
 * Cycle counts (Unit 09).
 *
 * <p><strong>Every endpoint is {@code PHARMACY_MANAGER}+, including the reads.</strong>
 * Unlike the batch list — where a cashier reads stock in order to dispense it —
 * a cashier has no reason to open a counting screen, and one of these sessions
 * ends with a manager signing off a variance against the books. Reading a count
 * means reading unreconciled discrepancies, so it sits behind the same boundary as
 * writing one.
 */
@RestController
@RequestMapping("/api/v1/inventory/cycle-counts")
public class CycleCountController {

    private static final int MAX_PAGE_SIZE = 100;

    private final CycleCountService cycleCounts;

    public CycleCountController(CycleCountService cycleCounts) {
        this.cycleCounts = cycleCounts;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('PHARMACY_MANAGER', 'SUPER_ADMIN')")
    public ResponseEntity<CycleCountResponse> create(
            @Valid @RequestBody CreateCycleCountRequest request) {
        CycleCountResponse created = cycleCounts.create(request);
        return ResponseEntity
                .created(UriComponentsBuilder.fromPath("/api/v1/inventory/cycle-counts/{id}")
                        .buildAndExpand(created.id())
                        .toUri())
                .body(created);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('PHARMACY_MANAGER', 'SUPER_ADMIN')")
    public PageResponse<CycleCountResponse> list(
            @RequestParam(required = false) CycleCount.Status status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(Math.max(0, page),
                Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PageResponse.from(cycleCounts.list(status, pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('PHARMACY_MANAGER', 'SUPER_ADMIN')")
    public CycleCountResponse get(@PathVariable UUID id) {
        return cycleCounts.get(id);
    }

    /**
     * PATCH, not POST: recording the same counted figure twice must be idempotent,
     * not two counts. Re-counting a shelf is a correction to the figure, and the
     * line is overwritten rather than appended to.
     */
    @PatchMapping("/{id}/lines/{lineId}")
    @PreAuthorize("hasAnyRole('PHARMACY_MANAGER', 'SUPER_ADMIN')")
    public CycleCountLineResponse countLine(@PathVariable UUID id,
                                            @PathVariable UUID lineId,
                                            @Valid @RequestBody CountLineRequest request) {
        return cycleCounts.countLine(id, lineId, request);
    }

    /**
     * POST rather than PATCH even though posting is idempotent in effect: it is a
     * state transition, and every other transition in this service is a POST.
     */
    @PostMapping("/{id}/post")
    @PreAuthorize("hasAnyRole('PHARMACY_MANAGER', 'SUPER_ADMIN')")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.OK)
    public PostCycleCountResponse post(@PathVariable UUID id) {
        return cycleCounts.post(id);
    }
}
