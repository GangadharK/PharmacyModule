package com.potlatech.pharmacy.common.exception;

/**
 * Thrown when an operation violates a domain rule. Maps to HTTP 400,
 * {@code code: BUSINESS_RULE_VIOLATION}.
 *
 * <p>The catch-all for "the request is well-formed but not allowed right now".
 * Deliberately distinct from {@link InvalidStateTransitionException}, which is a
 * 409: a rule violation is about the input, a state transition is about a
 * conflict with the current state of a record.
 */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }

    public BusinessRuleException(String format, Object... args) {
        super(format.formatted(args));
    }
}
