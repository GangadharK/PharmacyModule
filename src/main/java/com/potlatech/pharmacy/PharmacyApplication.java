package com.potlatech.pharmacy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Pharmacy Management System.
 *
 * <p>Standalone service. One application, one PostgreSQL schema, no modular-monolith
 * boundaries and no multi-tenancy - see {@code context/MDD.md} §2 and
 * {@code context/architecture-context.md} §2 for why the reference project in
 * {@code ../SCMS} is used for entity and frontend idiom but not for structure.
 *
 * <p>{@link EnableScheduling} is required for the expiry sweep in unit 05. It is
 * declared here rather than on the job class so the scheduling decision is
 * visible in one place.
 *
 * <p><strong>Why {@code UserDetailsServiceAutoConfiguration} is excluded.</strong>
 * Spring Boot's security starter creates an in-memory user with a random password
 * on startup whenever no {@code UserDetailsService} bean exists - printing it to
 * the log as "Using generated security password". This application has no such
 * bean because it does not use {@code UserDetailsService}: authentication is
 * stateless JWT ({@code JwtAuthenticationFilter}) against the {@code app_user}
 * table, and both {@code formLogin} and {@code httpBasic} are disabled.
 *
 * <p>So the bean is unreachable today, but it is not harmless. It installs itself
 * as the global {@code AuthenticationManager}, which means any future code that
 * autowires one — or any endpoint that turns form or basic auth on — authenticates
 * against a user whose password is in the log. A credential that appears in a log
 * is a published credential: logs get shipped, indexed and retained. Excluding the
 * autoconfiguration makes the omission fail loudly rather than leaving a loaded
 * default in place.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class PharmacyApplication {

    public static void main(String[] args) {
        SpringApplication.run(PharmacyApplication.class, args);
    }
}
