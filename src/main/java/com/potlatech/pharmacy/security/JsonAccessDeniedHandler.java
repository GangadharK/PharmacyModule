package com.potlatech.pharmacy.security;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.potlatech.pharmacy.common.dto.ApiError;

import java.io.IOException;

/**
 * Returns {@code 403} with the standard envelope when the caller is known but
 * not permitted.
 *
 * <p>The counterpart to {@link JsonAuthenticationEntryPoint}, and for the same
 * reason: the security filter chain runs outside
 * {@code @RestControllerAdvice}, so without this a {@code @PreAuthorize}
 * rejection would produce an empty body that no client error handler can parse.
 *
 * <p>Does not name the role or authority that was missing. Telling a
 * {@code CASHIER} that {@code POST /api/v1/users} needs {@code SUPER_ADMIN}
 * invites them to ask for it; the frontend already hides what the user cannot
 * do, and the server is the boundary that matters.
 */
@Component
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(JsonAccessDeniedHandler.class);

    private final ObjectMapper objectMapper;

    public JsonAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        log.debug("403 on {} {}", request.getMethod(), request.getRequestURI());

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        objectMapper.writeValue(response.getOutputStream(), ApiError.of(
                HttpStatus.FORBIDDEN.value(),
                "ACCESS_DENIED",
                "You do not have permission to perform this action.",
                request.getRequestURI()));
    }
}
