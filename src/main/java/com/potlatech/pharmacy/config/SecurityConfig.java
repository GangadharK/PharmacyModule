package com.potlatech.pharmacy.config;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecuritySchemes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.potlatech.pharmacy.client.HmsClient;
import com.potlatech.pharmacy.client.MockHmsClient;
import com.potlatech.pharmacy.security.JsonAccessDeniedHandler;
import com.potlatech.pharmacy.security.JsonAuthenticationEntryPoint;
import com.potlatech.pharmacy.security.JwtAuthenticationFilter;

import java.util.List;

/**
 * Security configuration.
 *
 * <p>Stateless bearer-token chain. Every request is authenticated by
 * {@link com.potlatech.pharmacy.security.JwtAuthenticationFilter} except the
 * small public list below, and authorisation is decided per-endpoint by
 * {@code @PreAuthorize}.
 *
 * <p><strong>Why the filter does not itself reject unauthenticated requests.</strong>
 * If it did, it would also reject {@code /auth/login} and {@code /auth/refresh},
 * which cannot carry a token. Leaving the request anonymous and letting the
 * authorisation rules decide keeps the "is this public?" question in exactly
 * one place — the {@code permitAll()} list below — where it can be read.
 *
 * <p><strong>Why CSRF is disabled.</strong> There is no cookie-authenticated
 * state for a CSRF token to protect: the access token is a header the browser
 * will not attach cross-origin on its own, and the refresh cookie is scoped to
 * {@code /api/v1/auth} and marked SameSite. Leaving CSRF on would reject every
 * request from the Vite dev server on the cross-origin port, which looks like a
 * broken filter rather than a correct one.
 *
 * <p><strong>Why the public list is short.</strong> Actuator health is public so
 * a load balancer can probe it. Nothing else is: no Swagger UI, no
 * {@code /api/v1/system} endpoints, no H2 console. Swagger being reachable on a
 * deployed instance is how an unauthenticated attacker reads the entire API
 * surface, so it is bound behind a role here even though it is convenient in
 * development.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@SecuritySchemes({
        @io.swagger.v3.oas.annotations.security.SecurityScheme(
                name = "bearerAuth",
                type = SecuritySchemeType.HTTP,
                scheme = "bearer",
                bearerFormat = "JWT")
})
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JsonAuthenticationEntryPoint authenticationEntryPoint;
    private final JsonAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          JsonAuthenticationEntryPoint authenticationEntryPoint,
                          JsonAccessDeniedHandler accessDeniedHandler) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        log.info("Security chain active: stateless JWT, {} public endpoint(s)",
                PUBLIC_ENDPOINTS.length);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**")
                            .hasRole("SUPER_ADMIN")
                        .anyRequest().authenticated())
                // A bearer token in a header, not a session cookie, so there is
                // nothing for the browser to resend automatically. Leaving
                // httpBasic on would let a browser cache a password and replay
                // it on every request.
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // Both handlers exist so the security layer emits the same
                // ApiError envelope as GlobalExceptionHandler. Without them a
                // 401/403 arrives as an empty body that the frontend cannot
                // parse, and the usual workaround - logging out on any 403 -
                // turns a token expiry into a lost session.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtAuthenticationFilter,
                        org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Endpoints reachable without a token.
     *
     * <p>Kept as a constant so the list is reviewable in one place and so a test
     * can assert on it. Adding to it is a security decision, not a convenience.
     */
    static final String[] PUBLIC_ENDPOINTS = {
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/actuator/health",
            // Unit 01 placeholder. Remove with PingController once login is
            // verified end to end.
            "/api/v1/system/ping"
    };

    /**
     * The password encoder. Cost 10 is Spring's default and is a deliberate
     * choice: 12 halves throughput, and a pharmacy's login rate does not justify
     * a slower login. The brute-force cost is on the attacker, who is trying
     * stolen hashes offline anyway — where the cost factor that is recorded with
     * each hash is what matters, not this one.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * CORS source, restricted to the configured origins.
     *
     * <p>Never {@code allowedOrigins("*")}. With a bearer token in an
     * {@code Authorization} header, a permissive origin would let any site issue
     * requests as the logged-in user.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.getAllowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("Location"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    /**
     * The HMS integration seam.
     *
     * <p>{@link MockHmsClient} is active whenever {@code app.hms.mode} is
     * {@code mock}, which is the default, because {@code ../practi} exposes no
     * prescription endpoint. Unit 10 adds the real Feign client under a
     * {@code live} profile so flipping the mode changes the implementation with
     * no change to any calling code. See {@code MDD.md} §4.3.
     */
    @Bean
    @Profile("!hms-live")
    public HmsClient mockHmsClient() {
        log.info("HMS integration in MOCK mode - fixtures only, no upstream calls");
        return new MockHmsClient();
    }
}
