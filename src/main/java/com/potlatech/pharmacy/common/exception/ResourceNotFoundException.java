package com.potlatech.pharmacy.common.exception;

/**
 * Thrown when a requested resource does not exist. Maps to HTTP 404,
 * {@code code: NOT_FOUND}.
 *
 * <p>Exists because the reference project in {@code ../SCMS} throws
 * {@code IllegalArgumentException} for missing rows, which is a 400. A client
 * cannot tell "you asked for something that isn't there" from "you sent
 * something invalid", and the two need different responses from the user.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resource, Object id) {
        super("%s not found: %s".formatted(resource, id));
    }

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
