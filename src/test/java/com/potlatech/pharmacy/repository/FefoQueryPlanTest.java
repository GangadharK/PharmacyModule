package com.potlatech.pharmacy.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import com.potlatech.pharmacy.repository.InventoryBatchRepository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the FEFO query's access path.
 *
 * <p>This is not a functional test — the dispensing behaviour is covered in
 * {@code FefoServiceTest} and {@code FefoAllocatorTest}. This one exists because
 * of a measured, quiet, and entirely invisible failure mode.
 *
 * <p><strong>The problem.</strong> {@code idx_fefo_search} is a <em>partial</em>
 * index, holding only rows where {@code status = 'ACTIVE' AND
 * quantity_available > 0}. PostgreSQL will only use a partial index when it can
 * prove that predicate holds for the query's rows. Write {@code status = :status}
 * with a bound parameter and the value is unknown at planning time, so the proof
 * fails — and once PostgreSQL stops building custom plans and switches to a
 * generic one, the FEFO query abandons the index:
 *
 * <pre>
 *   Bitmap Index Scan on idx_near_expiry
 *     Filter: medicine_id = $1 AND quantity_available &gt; 0
 * </pre>
 *
 * <p>That is a scan of every ACTIVE batch in the system for every dispense,
 * discarding other medicines' stock as a residual filter. Nothing throws, nothing
 * in the response is wrong, and every functional test passes.
 *
 * <p>So the query keeps {@code status = 'ACTIVE'} as a literal, and this file
 * asserts the resulting plan. It is the only test in the project that reads
 * {@code EXPLAIN}, and it is here on purpose: a reviewer who finds an odd literal
 * in a hand-written query should be able to find their way to this.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(FefoQueryPlanTest.PlanConfig.class)
class FefoQueryPlanTest {

    private static final int SEED_MEDICINES = 400;
    private static final int SEED_BATCHES_PER_MEDICINE = 8;

    /** The literal-status form, as written in the repository. */
    private static final String FEFO_SQL_LITERAL = """
            SELECT * FROM inventory_batch
             WHERE medicine_id = $1::uuid
               AND location = $2
               AND status = 'ACTIVE'
               AND quantity_available > 0
               AND expiry_date >= $3::date
             ORDER BY expiry_date ASC, batch_number ASC, id ASC
            """;

    /** The bind-parameter form — the mistake this file exists to catch. */
    private static final String FEFO_SQL_BIND = """
            SELECT * FROM inventory_batch
             WHERE medicine_id = $1::uuid
               AND location = $2
               AND status = $3::varchar
               AND quantity_available > 0
               AND expiry_date >= $4::date
             ORDER BY expiry_date ASC, batch_number ASC, id ASC
            """;

    @Autowired
    private JdbcTemplate jdbc;

    /** One of the seeded medicines, so the plan has realistic selectivity. */
    private UUID probeMedicineId;

    @TestConfiguration
    static class PlanConfig {
        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }
    }

    // ================================================================ fixtures

    @BeforeEach
    void seed() {
        // Realistic shape: many medicines, a handful of batches each. Seeding a
        // single medicine with every row would make all the indexes look equally
        // good, and the assertion would be measuring the fixture instead of the
        // query. A pharmacy holds thousands of products, so medicine_id is highly
        // selective — which is the case idx_fefo_search exists for.
        jdbc.update("INSERT INTO medicine_catalogue"
                        + " (id, generic_name, dosage_form, schedule_class, reorder_level,"
                        + " is_active, search_blob, created_at, updated_at)"
                        + " SELECT gen_random_uuid(), 'Plan Probe ' || n, 'TABLET', 'H', 0,"
                        + " TRUE, 'plan probe', now(), now()"
                        + " FROM generate_series(1, " + SEED_MEDICINES + ") n");

        // A realistic mix: some drained, some quarantined, spread across locations
        // and expiry dates. If every row were ACTIVE and in stock the partial index
        // would cover the whole table and the assertion would prove nothing.
        jdbc.update("INSERT INTO inventory_batch"
                + " (id, medicine_id, batch_number, expiry_date, quantity_received,"
                + " quantity_available, cost_price, selling_price, location, status,"
                + " created_at, updated_at)"
                + " SELECT gen_random_uuid(), m.id, 'B' || n,"
                + " current_date + (n % 400), 100,"
                + " CASE WHEN n % 5 = 0 THEN 0 ELSE 100 END,"
                + " 10.00, 15.00,"
                + " (ARRAY['MAIN_PHARMACY','OPD_COUNTER','ICU'])[1 + (n % 3)],"
                + " (ARRAY['ACTIVE','QUARANTINED','DEPLETED'])[1 + (n % 3)],"
                + " now(), now()"
                + " FROM medicine_catalogue m, generate_series(1, "
                + SEED_BATCHES_PER_MEDICINE + ") n"
                + " WHERE m.generic_name LIKE 'Plan Probe%'");

        jdbc.execute("ANALYZE inventory_batch");

        probeMedicineId = jdbc.queryForObject(
                "SELECT id FROM medicine_catalogue WHERE generic_name LIKE 'Plan Probe%' LIMIT 1",
                UUID.class);
    }

    // ================================================================ the plan

    @Test
    @DisplayName("the FEFO query uses the partial FEFO index, even on a generic plan")
    void fefoQueryUsesThePartialIndex() {
        String plan = planForGeneric(FEFO_SQL_LITERAL, quote(probeMedicineId), "'MAIN_PHARMACY'", "'2026-06-01'");

        assertThat(plan)
                .as("the FEFO read must stay on idx_fefo_search")
                .contains("idx_fefo_search");
        assertThat(plan)
                .as("medicine_id must be an index condition, not a residual filter — otherwise the"
                        + " index is decorative and the whole table gets scanned")
                .containsPattern("Index Cond:.*medicine_id");
    }

    @Test
    @DisplayName("a bind-parameter status would lose the index, which is why the literal is there")
    void bindParameterStatusLosesTheIndex() {
        String withLiteral = planForGeneric(FEFO_SQL_LITERAL, quote(probeMedicineId), "'MAIN_PHARMACY'", "'2026-06-01'");
        String withBind = planForGeneric(FEFO_SQL_BIND, quote(probeMedicineId), "'MAIN_PHARMACY'", "'ACTIVE'", "'2026-06-01'");

        assertThat(withLiteral).contains("idx_fefo_search");
        assertThat(withBind)
                .as("a bound status cannot prove the partial-index predicate, so PostgreSQL drops the"
                        + " index. If this assertion ever starts failing, the literal may be safe to"
                        + " parameterise — re-measure before believing that.")
                .doesNotContain("idx_fefo_search");
    }

    // ================================================================ the SQL

    @Test
    @DisplayName("the locked read declares FOR UPDATE and the preview does not")
    void lockedReadLocksAndPreviewDoesNot() {
        // Spring Data rejects @Lock on a native query, so the lock is written into
        // the SQL. That makes it worth asserting it is still there: a query that
        // compiles, returns the right rows, and silently lacks the lock fails only
        // under real contention.
        String locked = fefoSqlOf("findFefoCandidatesForUpdate");
        String preview = fefoSqlOf("findFefoCandidates");

        assertThat(locked)
                .as("the dispensing read must take row locks; this is the FEFO concurrency guarantee")
                .contains("FOR UPDATE");
        assertThat(preview)
                .as("the preview read must NOT lock, or every screen open blocks real dispensing")
                .doesNotContain("FOR UPDATE");
    }

    @Test
    @DisplayName("both FEFO reads filter on the status literal that keeps the index usable")
    void bothReadsUseTheStatusLiteral() {
        for (String method : List.of("findFefoCandidates", "findFefoCandidatesForUpdate")) {
            String sql = fefoSqlOf(method);
            assertThat(sql)
                    .as("%s must keep status as a literal, not a bind parameter", method)
                    .contains("status = 'ACTIVE'")
                    .doesNotContain("status = :");
        }
    }

    @Test
    @DisplayName("both FEFO reads order deterministically")
    void bothReadsOrderDeterministically() {
        for (String method : List.of("findFefoCandidates", "findFefoCandidatesForUpdate")) {
            assertThat(fefoSqlOf(method))
                    .as("%s needs a total order or two runs may dispense differently", method)
                    .contains("ORDER BY expiry_date ASC, batch_number ASC, id ASC");
        }
    }

    /** A UUID as a SQL literal. */
    private static String quote(UUID id) {
        return "'" + id + "'";
    }

    /** The {@code @Query} SQL of a repository method, or a failure naming it. */
    private static String fefoSqlOf(String methodName) {
        return Arrays.stream(InventoryBatchRepository.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .map(m -> m.getAnnotation(Query.class))
                .filter(Objects::nonNull)
                .map(Query::value)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no @Query on " + methodName));
    }

    // ================================================================ plumbing

    /**
     * Explains a query under forced generic planning, using server-side
     * {@code PREPARE} rather than a JDBC {@code PreparedStatement}.
     *
     * <p><strong>Three things have to be got right here, and each was wrong in an
     * earlier version of this test.</strong>
     *
     * <ol>
     *   <li>The {@code SET} and the {@code EXPLAIN} must share one connection.
     *       {@code JdbcTemplate} borrows a connection per operation, so a
     *       {@code SET} in one call and an explain in the next land on different
     *       sessions and the setting is silently discarded.
     *   <li>It must be a real {@code PREPARE}. pgjdbc sends an unnamed statement
     *       for the first executions of a {@code PreparedStatement}, and an unnamed
     *       statement has no plan cache for {@code plan_cache_mode} to act on —
     *       PostgreSQL just plans it with whatever values it was handed, every
     *       time. Crossing the driver's {@code prepareThreshold} is not enough
     *       either, because the driver only then starts caching, and the generic
     *       plan is chosen per cached statement. Driving {@code PREPARE} and
     *       {@code EXPLAIN EXECUTE} as plain statements is the only route that
     *       reliably reaches the plan this test is about.
     *   <li>Values are inlined into the {@code EXECUTE} call. They are literals
     *       this test controls, so there is no injection surface — and inlining
     *       them is what makes the prepared statement generic rather than
     *       custom-planned.
     * </ol>
     *
     * @param params the {@code $n} placeholders of {@code sql}, in order.
     */
    private String planForGeneric(String sql, String... params) {
        List<String> lines = jdbc.execute((ConnectionCallback<List<String>>) conn -> {
            String name = "fefo_plan_probe";
            try (Statement st = conn.createStatement()) {
                st.execute("SET plan_cache_mode = force_generic_plan");
                st.execute("DEALLOCATE ALL");
                st.execute("PREPARE " + name + " AS " + sql);
                StringBuilder exec = new StringBuilder("EXPLAIN (COSTS OFF) EXECUTE ")
                        .append(name).append('(');
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) {
                        exec.append(',');
                    }
                    exec.append(params[i]);
                }
                exec.append(')');
                try (ResultSet rs = st.executeQuery(exec.toString())) {
                    List<String> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                    return out;
                }
            } finally {
                try (Statement st = conn.createStatement()) {
                    st.execute("RESET plan_cache_mode");
                    st.execute("DEALLOCATE ALL");
                }
            }
        });
        return String.join("\n", lines);
    }
}
