package com.potlatech.pharmacy.security;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.potlatech.pharmacy.common.dto.ApiError;

import java.io.IOException;

/**
 * Returns {@code 401} when a request carries no usable credentials.
 *
 * <p>Without this, Spring Security's default behaviour for a stateless chain is
 * to return {@code 403 Forbidden} for an anonymous request, because the request
 * reached the authorisation manager unauthenticated and was refused. That is
 * wrong for an API: {@code 401} means "authenticate and retry", {@code 403}
 * means "you are known and still not allowed". A client that receives 403 for a
 * missing token cannot tell whether it should show a login screen or a
 * permission error, and the usual frontend work-around is to log the user out
 * again on any 403 — which turns a transient expiry into a lost session.
 *
 * <p>Writes the same {@link ApiError} envelope as
 * {@code GlobalExceptionHandler}, so a client parses one error shape
 * everywhere rather than two. That is the point of the class: the
 * authorisation filter sits outside the {@code @RestControllerAdvice}, so
 * without this the security layer would emit a bare HTML or empty body that no
 * frontend error path can read.
 *
 * <p>Never includes the reason in the body. {@code 401} here can mean an absent
 * header, a malformed header, an expired token or a bad signature, and
 * distinguishing them for an unauthenticated caller is handing an attacker a
 * tool for testing tokens.
 */
@Component
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(JsonAuthenticationEntryPoint.class);

    private final ObjectMapper objectMapper;

    public JsonAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        log.debug("401 on {} {}: {}", request.getMethod(), request.getRequestURI(),
                authException.getMessage());

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        ApiError body = ApiError.of(
                HttpStatus.UNAUTHORIZED.value(),
                "UNAUTHENTICATED",
                "Authentication is required. Provide a valid bearer token in the "
                        + "Authorization header.",
                request.getRequestURI());

        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
