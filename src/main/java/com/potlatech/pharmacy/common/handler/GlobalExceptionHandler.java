package com.potlatech.pharmacy.common.handler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.potlatech.pharmacy.common.dto.ApiError;
import com.potlatech.pharmacy.common.exception.AccessForbiddenException;
import com.potlatech.pharmacy.common.exception.BusinessRuleException;
import com.potlatech.pharmacy.common.exception.InsufficientStockException;
import com.potlatech.pharmacy.common.exception.InvalidStateTransitionException;
import com.potlatech.pharmacy.common.exception.ResourceNotFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns every exception into the {@link ApiError} envelope.
 *
 * <p>One shape for every failure. A client that has to handle four different
 * error formats depending on where the failure occurred is a client full of
 * untested branches.
 *
 * <p><strong>Sharp edge.</strong> Spring Security's filter chain runs
 * <em>before</em> the dispatcher servlet, so a 401 or 403 raised by
 * {@code @PreAuthorize} or by an authentication failure never reaches this
 * advice. Those responses are written by {@code JwtAuthenticationEntryPoint}
 * and {@code JwtAccessDeniedHandler} in unit 02, which produce the same
 * envelope. If a 403 arrives with an empty body, that pair is the thing to
 * check - not this class.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ==================== Domain exceptions ====================

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException ex,
                                                   HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ApiError> handleInsufficientStock(InsufficientStockException ex,
                                                             HttpServletRequest request) {
        // WARN, not ERROR: running out of a medicine is an operational fact, not
        // a system failure. ERROR-level paging on a stockout trains everyone to
        // ignore the log.
        log.warn("Insufficient stock on {}: requested={} available={}",
                ex.getMedicineName(), ex.getRequested(), ex.getAvailable());
        return build(HttpStatus.BAD_REQUEST, "INSUFFICIENT_STOCK", ex.getMessage(), request);
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ApiError> handleBusinessRule(BusinessRuleException ex,
                                                       HttpServletRequest request) {
        log.info("Business rule rejected {} {}: {}",
                request.getMethod(), request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<ApiError> handleInvalidTransition(InvalidStateTransitionException ex,
                                                            HttpServletRequest request) {
        log.info("Rejected transition on {}: {}",
                request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", ex.getMessage(), request);
    }

    @ExceptionHandler(AccessForbiddenException.class)
    public ResponseEntity<ApiError> handleForbidden(AccessForbiddenException ex,
                                                     HttpServletRequest request) {
        log.info("Forbidden {} {}: {}",
                request.getMethod(), request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", ex.getMessage(), request);
    }

    // ==================== Validation ====================

    /**
     * Body validation on a {@code @RequestBody}. Requires
     * {@code @Valid} on the parameter - the starter is declared in pom.xml, so
     * without the annotation this handler simply never fires.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBodyValidation(MethodArgumentNotValidException ex,
                                                         HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        ex.getBindingResult().getGlobalErrors().forEach(error ->
                fieldErrors.putIfAbsent(error.getObjectName(), error.getDefaultMessage()));

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED",
                        "Request validation failed", request.getRequestURI(), fieldErrors));
    }

    /**
     * Validation on a path variable, query parameter or request parameter -
     * {@code @Validated} on the controller class. Separate from body validation
     * because it arrives as a different exception type.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleParameterValidation(ConstraintViolationException ex,
                                                              HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            String path = violation.getPropertyPath().toString();
            // Strip the method prefix, e.g. "findById.id" -> "id"
            String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
            fieldErrors.putIfAbsent(field, violation.getMessage());
        }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED",
                        "Request validation failed", request.getRequestURI(), fieldErrors));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            HttpRequestMethodNotSupportedException.class})
    public ResponseEntity<ApiError> handleMalformedRequest(Exception ex,
                                                           HttpServletRequest request) {
        // Logged at DEBUG with the detail, returned as a generic message. A
        // Jackson parse error names the internal DTO field that failed, which is
        // an implementation detail the client has no use for.
        log.debug("Malformed request on {} {}: {}",
                request.getMethod(), request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                "The request could not be understood", request);
    }

    // ==================== Security ====================

    /**
     * Reached only when an {@code AccessDeniedException} propagates through the
     * dispatcher rather than being handled by the security filter chain. Both
     * paths are configured in unit 02; this one is the safety net.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex,
                                                       HttpServletRequest request) {
        log.info("Access denied on {} {}: {}",
                request.getMethod(), request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "You do not have permission to perform this action", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex,
                                                          HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Authentication required", request);
    }

    // ==================== Routing ====================

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiError> handleNoHandler(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "No endpoint %s %s".formatted(request.getMethod(), request.getRequestURI()),
                request);
    }

    // ==================== Client disconnects ====================

    /**
     * A client that hangs up mid-response is not a server error.
     *
     * <p>Closing a tab, navigating away, or a Vite hot reload all abort the
     * request while the response is still streaming. Tomcat reports that as
     * {@code ClientAbortException} (broken pipe), which Spring wraps in
     * {@link HttpMessageNotWritableException}. Without this handler that lands in
     * {@link #handleUnexpected} and is logged at ERROR with a 200-frame stack
     * trace — so routine developer navigation buries the errors an operator
     * actually needs to see, and trains people to ignore the ERROR log.
     *
     * <p>Returns {@code null} on purpose: the response is already committed, so
     * there is no envelope left to write and nothing to tell the client. Returning
     * {@code null} tells Spring the exchange is handled, which stops it trying
     * again and failing a second time on the same dead socket.
     *
     * <p>Detected by walking the cause chain rather than by catching
     * {@code ClientAbortException} directly, because the exception Spring hands
     * this advice is the wrapper, and the wrapper is not specific to client
     * aborts — a genuine serialisation failure must still reach the fallback.
     */
    @ExceptionHandler(HttpMessageNotWritableException.class)
    public ResponseEntity<ApiError> handleClientAbort(HttpMessageNotWritableException ex,
                                                      HttpServletRequest request) {
        if (isClientAbort(ex)) {
            log.debug("Client disconnected during {} {}: {}",
                    request.getMethod(), request.getRequestURI(), rootCause(ex).getMessage());
            return null;
        }
        log.error("Response could not be serialised for {} {}",
                request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred", request);
    }

    /**
     * True only for a genuine disconnect, not for every write failure.
     */
    private boolean isClientAbort(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof ClientAbortException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    // ==================== Fallback ====================

    /**
     * Last resort. Logs everything, returns nothing sensitive.
     *
     * <p>The client gets a fixed string. Echoing {@code ex.getMessage()} here
     * would leak SQL fragments, connection strings and internal class names to
     * anyone who can trigger an error.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred", request);
    }

    // ==================== Helpers ====================

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message,
                                           HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(ApiError.of(status.value(), code, message, request.getRequestURI()));
    }
}
