package com.potlatech.pharmacy.common.handler;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.potlatech.pharmacy.common.dto.ApiError;
import com.potlatech.pharmacy.common.exception.AccessForbiddenException;
import com.potlatech.pharmacy.common.exception.BusinessRuleException;
import com.potlatech.pharmacy.common.exception.InsufficientStockException;
import com.potlatech.pharmacy.common.exception.InvalidStateTransitionException;
import com.potlatech.pharmacy.common.exception.ResourceNotFoundException;
import com.potlatech.pharmacy.common.handler.GlobalExceptionHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;

/**
 * Every row of the code registry in {@code 01b-error-handling.md}, asserted
 * individually.
 *
 * <p>A sample is not coverage here. The registry exists so the frontend can
 * branch on {@code code}; a handler that silently returns the wrong status for
 * one exception type produces a response the UI cannot handle, and that failure
 * would not show up in any other test.
 */
@DisplayName("GlobalExceptionHandler - exception to status and code")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    // Not a constructor with narrowed access: JUnit 5 silently skips a test
    // class it cannot instantiate, which shows up as "Tests run: 0" with no
    // failure anywhere. Field initialisers keep the default constructor.
    {
        when(request.getRequestURI()).thenReturn("/api/v1/test");
        when(request.getMethod()).thenReturn("GET");
    }

    // ==================== The registry ====================

    @ParameterizedTest(name = "{0} -> HTTP {1}, code {2}")
    @CsvSource({
            "NOT_FOUND,                 404, NOT_FOUND",
            "INSUFFICIENT_STOCK,        400, INSUFFICIENT_STOCK",
            "BUSINESS_RULE_VIOLATION,   400, BUSINESS_RULE_VIOLATION",
            "INVALID_STATE_TRANSITION,  409, INVALID_STATE_TRANSITION",
            "FORBIDDEN,                 403, FORBIDDEN",
            "VALIDATION_FAILED,         400, VALIDATION_FAILED",
            "UNAUTHENTICATED,           401, UNAUTHENTICATED",
            "NOT_FOUND_ROUTING,         404, NOT_FOUND",
            "BAD_REQUEST,               400, BAD_REQUEST",
            "INTERNAL_ERROR,            500, INTERNAL_ERROR"
    })
    @DisplayName("the status/code registry is complete and correct")
    void registryIsCorrect(String label, int expectedStatus, String expectedCode) {
        ResponseEntity<ApiError> response = switch (label) {
            case "NOT_FOUND" ->
                    handler.handleNotFound(new ResourceNotFoundException("Batch", "abc"), request);
            case "INSUFFICIENT_STOCK" ->
                    handler.handleInsufficientStock(new InsufficientStockException("Panadol", 20, 12), request);
            case "BUSINESS_RULE_VIOLATION" ->
                    handler.handleBusinessRule(new BusinessRuleException("rule"), request);
            case "INVALID_STATE_TRANSITION" ->
                    handler.handleInvalidTransition(
                            new InvalidStateTransitionException("PurchaseOrder", "DRAFT", "APPROVED"), request);
            case "FORBIDDEN" ->
                    handler.handleForbidden(new AccessForbiddenException("nope"), request);
            case "VALIDATION_FAILED" ->
                    handler.handleBodyValidation(validationException(
                            new FieldError("body", "quantity", "must be greater than or equal to 1")), request);
            case "UNAUTHENTICATED" ->
                    handler.handleAuthentication(
                            new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException(
                                    "no auth"), request);
            case "NOT_FOUND_ROUTING" ->
                    handler.handleNoHandler(
                            new NoResourceFoundException(
                                    org.springframework.http.HttpMethod.GET, "/nope", "nope"), request);
            case "BAD_REQUEST" ->
                    handler.handleMalformedRequest(
                            new HttpRequestMethodNotSupportedException("DELETE"), request);
            case "INTERNAL_ERROR" ->
                    handler.handleUnexpected(new IllegalStateException("boom"), request);
            default -> throw new IllegalArgumentException("Unhandled label: " + label);
        };

        assertThat(response.getStatusCode().value())
                .as("%s status", label)
                .isEqualTo(expectedStatus);
        assertThat(response.getBody())
                .as("%s body", label)
                .isNotNull();
        assertThat(response.getBody().code())
                .as("%s code", label)
                .isEqualTo(expectedCode);
    }

    // ==================== Envelope shape ====================

    @Nested
    @DisplayName("envelope shape")
    class EnvelopeShape {

        @Test
        @DisplayName("carries the request path and a UTC timestamp")
        void includesPathAndTimestamp() {
            ApiError body = handler
                    .handleNotFound(new ResourceNotFoundException("Batch", "abc"), request)
                    .getBody();

            assertThat(body).isNotNull();
            assertThat(body.path()).isEqualTo("/api/v1/test");
            assertThat(body.timestamp()).isNotNull();
            assertThat(body.status()).isEqualTo(404);
        }

        @Test
        @DisplayName("omits fieldErrors entirely when the error is not a validation failure")
        void omitsFieldErrorsWhenAbsent() {
            ApiError body = handler
                    .handleBusinessRule(new BusinessRuleException("rule"), request)
                    .getBody();

            // Null, not empty. An empty object on every non-validation error is
            // noise the frontend has to filter out.
            assertThat(body).isNotNull();
            assertThat(body.fieldErrors()).isNull();
        }

        @Test
        @DisplayName("no message or code is null, ever")
        void neverNullsCodeOrMessage() {
            ApiError body = handler
                    .handleForbidden(new AccessForbiddenException("nope"), request)
                    .getBody();

            assertThat(body).isNotNull();
            assertThat(body.code()).isNotBlank();
            assertThat(body.message()).isNotBlank();
        }
    }

    // ==================== The 500 must not leak ====================

    @Nested
    @DisplayName("the 500 handler")
    class FiveHundredHandler {

        @Test
        @DisplayName("masks the exception message")
        void masksMessage() {
            String leaky = "Connection to jdbc:postgresql://db:5432/pharmacy failed: "
                    + "Access denied for user 'pharmacy'";

            ApiError body = handler
                    .handleUnexpected(new IllegalStateException(leaky), request)
                    .getBody();

            assertThat(body).isNotNull();
            assertThat(body.message())
                    .isEqualTo("An unexpected error occurred");
            assertThat(body.message())
                    .doesNotContain("jdbc")
                    .doesNotContain("pharmacy@")
                    .doesNotContain("Access denied")
                    .doesNotContain(leaky);
        }

        @Test
        @DisplayName("returns 500 with INTERNAL_ERROR")
        void returnsFiveHundred() {
            ResponseEntity<ApiError> response = handler
                    .handleUnexpected(new IllegalStateException("boom"), request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
        }

        @Test
        @DisplayName("does not leak a stack trace into the body")
        void doesNotLeakStackTrace() {
            ApiError body = handler
                    .handleUnexpected(new IllegalArgumentException("internal detail"), request)
                    .getBody();

            assertThat(body).isNotNull();
            assertThat(body.message()).doesNotContain("internal detail");
            assertThat(body.message()).doesNotContain("at com.greedit");
        }
    }

    // ==================== Validation ====================

    @Nested
    @DisplayName("a client that hangs up")
    class ClientAbort {

        @Test
        @DisplayName("is not reported as an error, and no envelope is attempted")
        void clientAbortIsNotAnError() {
            // The exact chain Spring produces: a broken pipe surfaces as a
            // ClientAbortException several causes deep, not at the top, so a
            // check on the outer type alone would miss it.
            IOException brokenPipe = new IOException("Broken pipe");
            ClientAbortException abort = new ClientAbortException("Broken pipe", brokenPipe);
            AsyncRequestNotUsableException notUsable =
                    new AsyncRequestNotUsableException("Response not usable after response errors.");
            notUsable.initCause(abort);
            HttpMessageNotWritableException ex = new HttpMessageNotWritableException("Could not write JSON", notUsable);

            ResponseEntity<ApiError> response = handler.handleClientAbort(ex, request);

            // null, because the response is already committed: there is no
            // envelope left to write and no client left to read it.
            assertThat(response).isNull();
        }

        @Test
        @DisplayName("does not swallow a serialisation failure that is not a disconnect")
        void genuineWriteFailureStillErrors() {
            // The wrapper is not specific to disconnects. A real serialisation bug
            // must still reach the 500 path, or this handler hides defects.
            HttpMessageNotWritableException ex =
                    new HttpMessageNotWritableException("Could not write JSON");

            ResponseEntity<ApiError> response = handler.handleClientAbort(ex, request);

            assertThat(response).isNotNull();
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("fieldErrors is populated from the binding result")
        void populatesFieldErrors() {
            ResponseEntity<ApiError> response = handler.handleBodyValidation(
                    validationException(
                            new FieldError("body", "quantity", "must be greater than or equal to 1")),
                    request);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo("VALIDATION_FAILED");
            assertThat(response.getBody().fieldErrors())
                    .containsExactly(entry("quantity", "must be greater than or equal to 1"));
        }

        @Test
        @DisplayName("every invalid field appears, not just the first")
        void capturesAllFields() {
            ResponseEntity<ApiError> response = handler.handleBodyValidation(
                    validationException(
                            new FieldError("body", "username", "must not be blank"),
                            new FieldError("body", "password", "must not be blank"),
                            new FieldError("body", "role", "must be one of CASHIER, PHARMACIST")),
                    request);

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().fieldErrors())
                    .hasSize(3)
                    .containsKeys("username", "password", "role");
        }

        @Test
        @DisplayName("an empty binding result yields no fieldErrors rather than an empty object")
        void emptyFieldErrorsAreOmitted() {
            ResponseEntity<ApiError> response =
                    handler.handleBodyValidation(validationException(), request);

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().fieldErrors()).isNull();
        }

        @Test
        @DisplayName("a global (non-field) error is keyed by the object name")
        void globalErrorsAreCaptured() {
            ResponseEntity<ApiError> response = handler.handleBodyValidation(
                    validationException(new org.springframework.validation.ObjectError(
                            "purchaseOrderRequest", "the order must have at least one line")),
                    request);

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().fieldErrors())
                    .containsEntry("purchaseOrderRequest", "the order must have at least one line");
        }
    }

    // ==================== Helpers ====================

    /**
     * Builds a real {@link MethodArgumentNotValidException} over a stubbed
     * {@code BindingResult}. Testing the handler against a reimplementation of
     * its own mapping would assert nothing at all.
     */
    private MethodArgumentNotValidException validationException(ObjectError... errors) {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(null, "body");
        for (ObjectError error : errors) {
            bindingResult.addError(error);
        }

        MethodArgumentNotValidException exception = mock(MethodArgumentNotValidException.class);
        when(exception.getBindingResult()).thenReturn(bindingResult);
        return exception;
    }
}
