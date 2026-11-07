package com.potlatech.pharmacy.common.exception;

/**
 * Thrown when a record is asked to move to a state it cannot reach from its
 * current one. Maps to HTTP 409, {@code code: INVALID_STATE_TRANSITION}.
 *
 * <p>409 rather than 400 because the request is not malformed - it is in
 * conflict with the state of the world. A purchase order that is already
 * {@code APPROVED} cannot be approved again, and the client's correct response
 * is to refresh, not to change the payload.
 */
public class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(String entity, String from, String to) {
        super("Cannot transition %s from %s to %s".formatted(entity, from, to));
    }
}
