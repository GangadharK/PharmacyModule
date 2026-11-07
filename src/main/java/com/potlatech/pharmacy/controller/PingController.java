package com.potlatech.pharmacy.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.potlatech.pharmacy.common.exception.BusinessRuleException;

import java.util.Map;

/**
 * Liveness probe. Deliberately the only unauthenticated API endpoint in unit 01.
 *
 * <p>It exists for two reasons, both temporary:
 *
 * <ol>
 *   <li>The frontend's {@code ApiClient} needs a real endpoint to call during
 *       unit 01 so the wiring can be verified before authentication exists.</li>
 *   <li>{@code ?fail=true} throws, which is how the {@code ApiError} envelope
 *       and {@code GlobalExceptionHandler} are verified end to end over real
 *       HTTP rather than only in a unit test.</li>
 * </ol>
 *
 * <p>Unit 02 closes it. A permanently-open endpoint that touches no data is
 * harmless, but a permanently-open endpoint that is easy to mistake for a health
 * check in a different shape is not worth carrying.
 */
@RestController
@RequestMapping("/api/v1/system")
@Tag(name = "System", description = "System diagnostics")
public class PingController {

    @GetMapping("/ping")
    @Operation(
            summary = "Liveness probe",
            description = "Returns PONG. Pass ?fail=true to exercise the error envelope. "
                    + "Removed as an open endpoint once authentication is in place.")
    public ResponseEntity<Map<String, String>> ping(
            @RequestParam(defaultValue = "false") boolean fail) {

        if (fail) {
            throw new BusinessRuleException(
                    "Deliberate failure requested via ?fail=true, for error-envelope verification");
        }

        return ResponseEntity.ok(Map.of(
                "status", "PONG",
                "service", "pharmacy"));
    }
}
