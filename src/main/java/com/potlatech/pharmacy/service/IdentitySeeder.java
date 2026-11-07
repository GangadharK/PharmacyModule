package com.potlatech.pharmacy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.potlatech.pharmacy.entity.User;
import com.potlatech.pharmacy.repository.UserRepository;

import java.util.List;
import java.util.UUID;

/**
 * Creates one development user per role so a fresh database is usable
 * immediately.
 *
 * <p><strong>Disabled unless {@code app.seed.enabled=true},</strong> which is set
 * only in the {@code dev} profile. A Flyway migration would have been the wrong
 * place for this: migrations run against production exactly as they do against
 * development, so a seeded {@code SUPER_ADMIN} with a published password would
 * appear in the customer's database on every deploy, invisibly. See
 * {@code progress-tracker.md} decision 20 and {@code AGENTS.md} rule 16.
 *
 * <p><strong>Idempotent, and deliberately not upserting.</strong> If a user
 * already exists this logs and moves on — it does not reset the password. A
 * seeder that re-asserts a known password on every boot would silently undo a
 * real password change, and it would do so the next time anyone restarted the
 * service.
 *
 * <p>Hashes are generated at run time through the injected encoder rather than
 * pasted in as constants. A hash committed to the repository is a hash that
 * cannot be rotated, and a single leaked source file would otherwise disclose
 * every dev account at once.
 */
@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
public class IdentitySeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IdentitySeeder.class);

    /**
     * The development password. Published in the README and in the MDD, which is
     * exactly why this class never runs outside {@code dev}.
     */
    static final String DEV_PASSWORD = "Pharmacy@Dev123";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public IdentitySeeder(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<SeedUser> seeds = List.of(
                new SeedUser("superadmin", "System Administrator", User.Role.SUPER_ADMIN),
                new SeedUser("manager", "Pharmacy Manager", User.Role.PHARMACY_MANAGER),
                new SeedUser("pharmacist", "Duty Pharmacist", User.Role.PHARMACIST),
                new SeedUser("cashier", "Counter Cashier", User.Role.CASHIER));

        int created = 0;
        int skipped = 0;
        for (SeedUser seed : seeds) {
            if (users.existsByUsername(seed.username())) {
                skipped++;
                continue;
            }
            users.save(User.builder()
                    .id(UUID.randomUUID())
                    .username(seed.username())
                    // Encoded here, per run, not stored as a constant.
                    .passwordHash(passwordEncoder.encode(DEV_PASSWORD))
                    .fullName(seed.fullName())
                    .role(seed.role())
                    .isActive(true)
                    .build());
            created++;
        }

        if (created > 0) {
            log.warn("""

                    ================================================================
                    Seeded {} development user(s) because app.seed.enabled=true.
                    One per role, all with the password: {}
                    This must never be enabled outside a developer machine.
                    ================================================================
                    """, created, DEV_PASSWORD);
        }
        if (skipped > 0) {
            log.info("Skipped {} existing user(s); passwords were not reset.", skipped);
        }
    }

    private record SeedUser(String username, String fullName, User.Role role) {
    }
}
