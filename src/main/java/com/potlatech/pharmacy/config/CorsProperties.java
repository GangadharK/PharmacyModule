package com.potlatech.pharmacy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CORS for the SPA dev server.
 *
 * <p>Bound from {@code app.cors.allowed-origins} rather than scattered
 * {@code @Value} reads, per {@code code-standards.md} §4.
 *
 * <p>In development the Vite proxy in {@code vite.config.ts} forwards
 * {@code /api} to the backend, so the browser sees a same-origin request and CORS
 * never applies. This configuration exists for the case where the frontend is
 * served from a different origin - a deployed frontend, or a proxy that is not
 * in place.
 */
@Component
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

    /**
     * Allowed origins. Defaults to the Vite dev server. Never {@code *} - that
     * would permit any site to issue authenticated requests with a token it
     * should not have.
     *
     * <p>Must stay in step with {@code server.port} in {@code frontend/vite.config.ts}
     * (5163). In practice the dev proxy makes browser requests same-origin, so
     * this list is rarely exercised locally - but it is the setting that governs
     * a direct browser-to-8081 call, and a mismatch fails as an opaque CORS error
     * with a 200-shaped console message.
     */
    private List<String> allowedOrigins = List.of("http://localhost:5163");

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }
}
