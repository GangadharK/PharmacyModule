package com.potlatech.pharmacy.common.exception;

/**
 * Thrown when a stock movement is requested that the batches cannot satisfy.
 * Maps to HTTP 400, {@code code: INSUFFICIENT_STOCK}.
 *
 * <p>Its own type rather than a generic {@link BusinessRuleException} because
 * the UI has to say something specific - "only 12 of 20 available" - rather than
 * a generic rule message. That requires {@code requested} and {@code available}
 * as fields, which a message string cannot carry.
 *
 * <p><strong>Where it is thrown from.</strong> FEFO does not throw this itself:
 * {@link com.potlatech.pharmacy.service.FefoAllocator} <em>returns</em> a
 * shortfall in its {@code AllocationResult}, because a caller may legitimately
 * want "give me whatever you can". The throw comes from
 * {@code AllocationResult.requireComplete(medicineName)}, which the service
 * calls when the request must be met in full. The name is passed in at that
 * point because the allocator never had it - it only ever sees batch numbers.
 */
public class InsufficientStockException extends RuntimeException {

    private final String medicineName;
    private final int requested;
    private final int available;

    public InsufficientStockException(String medicineName, int requested, int available) {
        super("Insufficient stock for %s: requested %d, available %d"
                .formatted(medicineName, requested, available));
        this.medicineName = medicineName;
        this.requested = requested;
        this.available = available;
    }

    public String getMedicineName() {
        return medicineName;
    }

    public int getRequested() {
        return requested;
    }

    public int getAvailable() {
        return available;
    }
}
