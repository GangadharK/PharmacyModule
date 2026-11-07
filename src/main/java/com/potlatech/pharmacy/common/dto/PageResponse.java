package com.potlatech.pharmacy.common.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * A stable pagination envelope.
 *
 * <p>Spring's {@code Page} serialises with an internal shape that is not part of
 * its documented API and has changed between versions. Returning it directly
 * from a controller makes that shape part of the frontend's contract, so a
 * Spring upgrade becomes a breaking API change nobody anticipated.
 *
 * <p>A record, for the same reason as {@link ApiError} - see that class's Javadoc
 * for why this deviates from the Lombok-DTO convention.
 *
 * @param content       the page of results, never null
 * @param page          zero-based page index
 * @param size          requested page size
 * @param totalElements total rows matching the query, ignoring pagination
 * @param totalPages    total page count at this size
 * @param first         true when this is page 0
 * @param last          true when this is the final page
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }

    /** Convenience for services that filter or map an already-fetched page. */
    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new PageResponse<>(
                content,
                page,
                size,
                totalElements,
                totalPages,
                page == 0,
                totalPages > 0 && page >= totalPages - 1);
    }

    public static <T> PageResponse<T> empty(int page, int size) {
        return new PageResponse<>(List.of(), page, size, 0L, 0, page == 0, true);
    }
}
