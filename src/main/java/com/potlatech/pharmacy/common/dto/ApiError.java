package com.potlatech.pharmacy.common.dto;

import java.time.Instant;
import java.util.Map;

/**
 * The single error shape the API ever returns.
 *
 * <p>A record, not a Lombok class, despite {@code code-standards.md} §2.5. The
 * convention exists for request and response DTOs built with a builder from
 * entities. An error envelope is an immutable value carrier with no entity
 * behind it and no optional fields, so a record is the honest shape. Do not
 * "fix" this to match the DTO convention.
 *
 * <p><strong>{@code code} is the contract; {@code message} is for humans.</strong>
 * The frontend branches on {@code code} and displays {@code message}. Branching
 * on message text means an edit to a human-readable string silently breaks error
 * handling in the UI.
 *
 * @param timestamp   when the error was produced, ISO-8601 UTC
 * @param status      HTTP status code, repeated in the body so a log line is
 *                    self-contained
 * @param code        stable machine-readable identifier, e.g. {@code NOT_FOUND}
 * @param message     human-readable explanation. Safe to show a user. Never a
 *                    stack trace, SQL fragment, or internal class name
 * @param path        the request path that failed
 * @param fieldErrors field name to validation message. Null - and therefore
 *                    omitted from the JSON - when the error is not a validation
 *                    failure. An empty object is noise on every other error
 */
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        Map<String, String> fieldErrors) {

    public static ApiError of(int status, String code, String message, String path) {
        return new ApiError(Instant.now(), status, code, message, path, null);
    }

    public static ApiError of(int status, String code, String message, String path,
                              Map<String, String> fieldErrors) {
        return new ApiError(Instant.now(), status, code, message, path,
                fieldErrors == null || fieldErrors.isEmpty() ? null : fieldErrors);
    }
}
