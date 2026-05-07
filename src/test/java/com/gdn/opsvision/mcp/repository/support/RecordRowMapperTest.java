package com.gdn.opsvision.mcp.repository.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * Regression test for the pgjdbc 42.7.x {@code java.time.Instant} bug.
 *
 * <p>pgjdbc deliberately doesn't support {@code rs.getObject(idx, Instant.class)} —
 * the call throws {@code "conversion to class java.time.Instant from timestamp[tz]
 * not supported"} regardless of the column type. Spring's stock
 * {@code SimplePropertyRowMapper} hits exactly this code path when mapping a record
 * with an {@code Instant} component, which broke {@code diagnosePickPackage} for any PP
 * with non-null timestamps in QA2.
 *
 * <p>{@link RecordRowMapper} routes around this by reading {@code Instant} via
 * {@code OffsetDateTime} (for {@code timestamptz}) or {@code Timestamp} with a UTC
 * calendar (for {@code timestamp without time zone}). These tests exercise both paths
 * so a future refactor that re-introduces the {@code rs.getObject(idx, Instant.class)}
 * call would fail fast.
 */
class RecordRowMapperTest {

    /** All-object-typed components — keeps the test focused on Instant handling. */
    public record Row(String name, Instant createdDate, Instant updatedDate) {
    }

    public record SingleRow(Instant createdDate) {
    }

    @Test
    void readsInstantFromTimestamptzViaOffsetDateTime() throws SQLException {
        // pgjdbc supports getObject(idx, OffsetDateTime.class) for timestamptz columns —
        // the mapper must take that path when column type is timestamptz.
        Instant t = Instant.parse("2026-05-07T01:30:21Z");
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(md);

        when(rs.findColumn("name")).thenReturn(1);
        when(rs.findColumn("created_date")).thenReturn(2);
        when(rs.findColumn("updated_date")).thenReturn(3);
        when(rs.findColumn("createdDate")).thenThrow(new SQLException("absent"));
        when(rs.findColumn("updatedDate")).thenThrow(new SQLException("absent"));

        when(rs.getString(1)).thenReturn("hello");
        when(md.getColumnTypeName(2)).thenReturn("timestamptz");
        when(md.getColumnTypeName(3)).thenReturn("timestamptz");
        doReturn(t.atOffset(ZoneOffset.UTC)).when(rs).getObject(2, OffsetDateTime.class);
        doReturn(t.atOffset(ZoneOffset.UTC)).when(rs).getObject(3, OffsetDateTime.class);

        // The mapper must NOT call rs.getObject(idx, Instant.class) — that's the bug we
        // fix. Make any such call throw to prove we never go down that path.
        doThrow(new SQLException("getObject(idx, Instant.class) is forbidden by pgjdbc"))
                .when(rs).getObject(eq(2), eq(Instant.class));
        doThrow(new SQLException("getObject(idx, Instant.class) is forbidden by pgjdbc"))
                .when(rs).getObject(eq(3), eq(Instant.class));

        Row row = RecordRowMapper.of(Row.class).mapRow(rs, 1);
        assertThat(row.name()).isEqualTo("hello");
        assertThat(row.createdDate()).isEqualTo(t);
        assertThat(row.updatedDate()).isEqualTo(t);
    }

    @Test
    void readsInstantFromTzlessTimestampViaUtcCalendar() throws SQLException {
        // For timestamp-without-tz columns the mapper falls back to Timestamp with an
        // explicit UTC Calendar — Hibernate writes UTC into these columns, so reading
        // them back with the UTC calendar produces the correct millis-since-epoch.
        Instant t = Instant.parse("2026-05-07T01:30:21Z");
        Timestamp ts = Timestamp.from(t);
        AtomicReference<Calendar> calSeen = new AtomicReference<>();

        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(md);
        when(rs.findColumn("name")).thenReturn(1);
        when(rs.findColumn("created_date")).thenReturn(2);
        when(rs.findColumn("updated_date")).thenReturn(3);
        when(rs.findColumn("createdDate")).thenThrow(new SQLException("absent"));
        when(rs.findColumn("updatedDate")).thenThrow(new SQLException("absent"));

        when(rs.getString(1)).thenReturn("x");
        when(md.getColumnTypeName(2)).thenReturn("timestamp");
        when(md.getColumnTypeName(3)).thenReturn("timestamp");
        when(rs.getTimestamp(eq(2), any(Calendar.class)))
                .thenAnswer(inv -> {
                    calSeen.compareAndSet(null, inv.getArgument(1));
                    return ts;
                });
        when(rs.getTimestamp(eq(3), any(Calendar.class))).thenReturn(ts);

        Row row = RecordRowMapper.of(Row.class).mapRow(rs, 1);
        assertThat(row.createdDate()).isEqualTo(t);
        assertThat(row.updatedDate()).isEqualTo(t);
        assertThat(calSeen.get()).isNotNull();
        assertThat(calSeen.get().getTimeZone())
                .as("Timestamp must be read with a UTC calendar so the resulting Instant matches Hibernate-written UTC")
                .isEqualTo(TimeZone.getTimeZone("UTC"));
    }

    @Test
    void nullInstantColumnReturnsNull() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(md);
        when(rs.findColumn("created_date")).thenReturn(1);
        when(rs.findColumn("createdDate")).thenThrow(new SQLException("absent"));
        when(md.getColumnTypeName(1)).thenReturn("timestamptz");
        doReturn(null).when(rs).getObject(1, OffsetDateTime.class);

        SingleRow row = RecordRowMapper.of(SingleRow.class).mapRow(rs, 1);
        assertThat(row.createdDate()).isNull();
    }

    @Test
    void resolvesSnakeCaseColumnFromCamelCaseComponent() throws SQLException {
        // Record component is `createdDate`; column is `created_date`. findColumn tries
        // the literal name first, falls back to camelToSnake.
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(md);
        when(rs.findColumn("createdDate")).thenThrow(new SQLException("no such column"));
        when(rs.findColumn("created_date")).thenReturn(7);
        when(md.getColumnTypeName(7)).thenReturn("timestamptz");
        doReturn(null).when(rs).getObject(7, OffsetDateTime.class);

        SingleRow row = RecordRowMapper.of(SingleRow.class).mapRow(rs, 1);
        assertThat(row.createdDate()).isNull();
    }

    @Test
    void rejectsNonRecordClass() {
        assertThatThrownBy(() -> RecordRowMapper.of(String.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a record");
    }

    @Test
    void camelToSnakeConvertsCorrectly() {
        assertThat(RecordRowMapper.camelToSnake("createdDate")).isEqualTo("created_date");
        assertThat(RecordRowMapper.camelToSnake("ppId")).isEqualTo("pp_id");
        assertThat(RecordRowMapper.camelToSnake("siteCode")).isEqualTo("site_code");
        assertThat(RecordRowMapper.camelToSnake("id")).isEqualTo("id");
        assertThat(RecordRowMapper.camelToSnake("warehouseItemMasterId"))
                .isEqualTo("warehouse_item_master_id");
    }
}
