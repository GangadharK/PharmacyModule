package com.potlatech.pharmacy.common.exception;

/**
 * Thrown when an authenticated user lacks the required authority. Maps to HTTP
 * 403, {@code code: FORBIDDEN}.
 *
 * <p>Usually raised by {@code @PreAuthorize} rather than thrown by hand. It
 * exists for the cases method security cannot express - notably the
 * {@code cost_price} redaction rule in unit 03, where the caller may read a
 * medicine but must not receive its cost price.
 */
public class AccessForbiddenException extends RuntimeException {

    public AccessForbiddenException(String message) {
        super(message);
    }
}
