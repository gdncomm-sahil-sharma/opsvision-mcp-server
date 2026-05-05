package com.gdn.opsvision.mcp;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Belt-and-suspenders check that DML against any of the three DataSources is rejected.
 * <p>
 * Three layers of read-only enforcement should make this fail every time:
 * <ol>
 *   <li>Hikari {@code read-only: true} (Connection.setReadOnly)</li>
 *   <li>{@code SET default_transaction_read_only = on} via connection-init-sql</li>
 *   <li>{@code @Transactional(readOnly = true)} on repositories — N/A here, we hit JdbcClient directly</li>
 * </ol>
 * <p>
 * Postgres returns SQLState {@code 25006} ({@code read_only_sql_transaction}) with message
 * "cannot execute UPDATE in a read-only transaction". On DBs where the role lacks UPDATE
 * grants entirely, we'd see "permission denied for table …" — also acceptable proof that no
 * write can happen. The regex matches both cases.
 * <p>
 * Skipped in CI/local when the env vars aren't set — running this test requires real DB
 * connections (no embedded H2 will reproduce Postgres's read-only behavior faithfully).
 * <p>
 * The UPDATEs target id = -1 (which never exists) so even in the impossible case where the
 * read-only enforcement fails, no real row gets touched.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "STOCKHOLM_DB_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "INVENTORY_DB_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "MOVEMENT_DB_PASSWORD", matches = ".+")
class ReadOnlyEnforcementTest {

    private static final String READONLY_PATTERN =
            "(?si).*(read.only|read_only_sql_transaction|cannot execute|permission denied).*";

    @Autowired
    @Qualifier("stockholmJdbcClient")
    JdbcClient stockholmJdbc;

    @Autowired
    @Qualifier("inventoryJdbcClient")
    JdbcClient inventoryJdbc;

    @Autowired
    @Qualifier("movementJdbcClient")
    JdbcClient movementJdbc;

    @Test
    void stockholmRejectsDml() {
        assertThatThrownBy(() ->
                stockholmJdbc.sql("UPDATE pick_package SET label = 'dml-guard-test' WHERE id = -1").update())
                .hasStackTraceContaining("read")
                .hasMessageMatching(READONLY_PATTERN);
    }

    @Test
    void inventoryRejectsDml() {
        assertThatThrownBy(() ->
                inventoryJdbc.sql("UPDATE warehouse SET name = 'dml-guard-test' WHERE id = -1").update())
                .hasMessageMatching(READONLY_PATTERN);
    }

    @Test
    void movementRejectsDml() {
        assertThatThrownBy(() ->
                movementJdbc.sql("UPDATE picking_task SET status = 'X' WHERE id = -1").update())
                .hasMessageMatching(READONLY_PATTERN);
    }
}
