package com.potlatech.pharmacy.common.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the Flyway migration chain.
 *
 * <p>This replaced {@code MigrationParityTest}, which existed only to keep the
 * PostgreSQL and MySQL trees in step. Decision 22 removed MySQL, so there is one
 * tree and nothing to compare. What survived is the part that was never about
 * parity: migration versions must be contiguous, headers must document
 * dependencies, and a migration must never contain a credential.
 *
 * <p>That rule exists because of a real hazard, not a hypothetical one. A
 * Flyway migration runs against the live database, so seeding a {@code SUPER_ADMIN}
 * with a published password inside {@code V1} would have created a standing
 * remote-takeover on every deploy, invisibly. Seeding is code, gated on
 * {@code app.seed.enabled} (decision 20). This test is what stops the obvious
 * workaround — "just put the INSERT in the migration" — from quietly returning.
 *
 * <p>It was previously verified by mutation: inserting a BCrypt hash into a
 * migration makes it fail. That check is retained deliberately, because a guard
 * that cannot fail is not a guard.
 */
@DisplayName("Flyway migrations - structural and safety rules")
class MigrationGuardTest {

    private static final File MIGRATION_DIR = new File("src/main/resources/db/migration");

    @Test
    @DisplayName("migrations are found and numbered without gaps or duplicates")
    void migrationsAreContiguous() {
        Set<String> names = migrationFiles();

        assertThat(names)
                .as("no migrations found at %s — wrong working directory, or every "
                        + "migration was deleted", MIGRATION_DIR.getAbsolutePath())
                .isNotEmpty();

        // Flyway tolerates gaps in the version sequence, so a gap here usually
        // means a file was deleted rather than that a version was skipped.
        List<Integer> versions = new ArrayList<>();
        for (String name : names) {
            versions.add(versionOf(name));
        }
        assertThat(versions)
                .as("duplicate migration version — two files claim the same number")
                .doesNotHaveDuplicates();

        for (int i = 0; i < versions.size(); i++) {
            assertThat(versions.get(i))
                    .as("migration versions are not contiguous from 1: %s", names)
                    .isEqualTo(i + 1);
        }
    }

    @Test
    @DisplayName("no migration contains a literal credential")
    void noCredentialsInMigrations() {
        for (String file : migrationFiles()) {
            String sql = read(file).toLowerCase();

            assertThat(sql)
                    .as("%s contains a BCrypt hash. Flyway runs against the live database, "
                            + "so a seeded account with a published password is a standing "
                            + "remote-takeover. Seed from code behind app.seed.enabled instead.",
                            file)
                    .doesNotContain("$2a$")
                    .doesNotContain("$2b$")
                    .doesNotContain("$2y$")
                    .doesNotContain("password_hash values");

            assertThat(sql)
                    .as("%s looks like it inserts a login account. Users are seeded by "
                            + "IdentitySeeder, not by a migration.", file)
                    .doesNotMatch("(?s).*insert\\s+into\\s+users\\b.*");
        }
    }

    @Test
    @DisplayName("every migration has a header explaining what it does")
    void everyMigrationIsDocumented() {
        // code-standards.md 5 requires a header on every migration. It is the only
        // thing a future reader has when a migration misbehaves in production.
        for (String file : migrationFiles()) {
            String header = leadingCommentBlock(read(file));

            assertThat(header)
                    .as("%s has no leading comment block describing what it does", file)
                    .isNotBlank();

            assertThat(header.toLowerCase())
                    .as("%s header does not state its dependencies. A reader debugging a "
                            + "failed migration needs to know what has to be applied first.",
                            file)
                    .contains("depends on");
        }
    }

    /**
     * The run of {@code --} comment lines at the top of the file, stopping at the
     * first line of SQL.
     *
     * <p>Reads the whole block rather than the first N lines. An earlier version
     * capped it at 12 lines and failed on V2, whose {@code Depends on: V1} note
     * sits at the end of a longer explanation — the cap was testing the length of
     * the prose instead of the presence of the dependency.
     */
    private static String leadingCommentBlock(String sql) {
        StringBuilder block = new StringBuilder();
        for (String line : sql.lines().toList()) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                block.append(line).append('\n');
            } else {
                break;
            }
        }
        return block.toString();
    }

    // ---------------------------------------------------------------- helpers

    private static int versionOf(String filename) {
        Matcher m = Pattern.compile("^V(\\d+)__").matcher(filename);
        if (!m.find()) {
            throw new IllegalStateException(
                    "Migration filename does not follow Flyway's V<n>__<description>.sql "
                            + "convention: " + filename);
        }
        return Integer.parseInt(m.group(1));
    }

    private static Set<String> migrationFiles() {
        File[] files = MIGRATION_DIR.listFiles((d, name) -> name.endsWith(".sql"));
        assertThat(files)
                .as("no .sql files in %s", MIGRATION_DIR.getAbsolutePath())
                .isNotNull();
        Set<String> names = new TreeSet<>();
        for (File f : files) {
            names.add(f.getName());
        }
        return names;
    }

    private static String read(String filename) {
        try {
            return Files.readString(new File(MIGRATION_DIR, filename).toPath(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
