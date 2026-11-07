package com.potlatech.pharmacy.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata and the bearer security scheme.
 *
 * <p>This document is the contract the frontend is built against. A screen is
 * built <em>after</em> its endpoint is verified, never against an imagined
 * response shape - see {@code AGENTS.md} "Context-Switching Protocol".
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI pharmacyOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Pharmacy Management System API")
                        .version("v1")
                        .description("""
                                Standalone pharmacy management: master catalogue, \
                                batch inventory with FEFO, and purchase entry with OCR.

                                **Money is `BigDecimal` and serialises as a JSON number.** \
                                `cost_price` is present only for callers whose role is \
                                entitled to see it - see FDD 2.

                                **All IDs are UUIDs serialised as strings.** They are stored \
                                as native `uuid` columns - not `BINARY(16)`, which was a \
                                MySQL leftover and has no meaning in PostgreSQL 16 - and \
                                must never be treated as numbers.

                                **Errors** always use the `ApiError` envelope. Branch on \
                                `code`, never on `message`.""")

                        .contact(new Contact().name("Pharmacy Team"))
                        .license(new License().name("Proprietary")))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Access token from POST /api/v1/auth/login")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
