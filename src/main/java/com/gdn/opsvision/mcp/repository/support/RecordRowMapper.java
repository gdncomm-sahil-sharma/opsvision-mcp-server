package com.gdn.opsvision.mcp.repository.support;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.JdbcUtils;

/**
 * RowMapper for Java records that fixes the {@code Instant}-binding gap in pgjdbc 42.7.x.
 *
 * <p>Spring's stock {@code SimplePropertyRowMapper} calls {@code rs.getObject(idx, Instant.class)}
 * which pgjdbc 42.7.x rejects with "conversion to class java.time.Instant from timestamp[tz]
 * not supported" — the driver supports {@code OffsetDateTime} / {@code LocalDateTime} but
 * not {@code Instant}. This mapper detects {@code Instant} record components and reads them
 * through the supported path: {@code OffsetDateTime} for tz-aware columns,
 * {@code Timestamp} interpreted as UTC for tz-less columns. All other types delegate to
 * {@link JdbcUtils#getResultSetValue}.
 *
 * <p>Column-name resolution mirrors {@code SimplePropertyRowMapper}: snake_case column
 * names map to camelCase record components (e.g. {@code created_date} → {@code createdDate}).
 * The mapper looks up by exact match first, then by snake-case-from-camelCase.
 *
 * <p>Stockholm and warehouse-stock-movement persist UTC values into
 * {@code timestamp without time zone} columns (Hibernate's default with
 * {@code hibernate.jdbc.time_zone=UTC}). When this mapper falls back to {@code Timestamp}
 * for tz-less columns, it forces a UTC calendar so the produced {@code Instant} matches
 * what was written.
 */
public final class RecordRowMapper<T> implements RowMapper<T> {

    private static final Calendar UTC = Calendar.getInstance(TimeZone.getTimeZone("UTC"));

    private final Class<T> recordClass;
    private final RecordComponent[] components;
    private final Constructor<T> canonicalConstructor;

    private RecordRowMapper(Class<T> recordClass) {
        if (!recordClass.isRecord()) {
            throw new IllegalArgumentException(recordClass.getName() + " is not a record");
        }
        this.recordClass = recordClass;
        this.components = recordClass.getRecordComponents();
        Class<?>[] paramTypes = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            paramTypes[i] = components[i].getType();
        }
        try {
            Constructor<T> c = recordClass.getDeclaredConstructor(paramTypes);
            c.setAccessible(true);
            this.canonicalConstructor = c;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "Canonical constructor not found for " + recordClass.getName(), e);
        }
    }

    public static <T> RecordRowMapper<T> of(Class<T> recordClass) {
        return new RecordRowMapper<>(recordClass);
    }

    @Override
    public T mapRow(ResultSet rs, int rowNumber) throws SQLException {
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent rc = components[i];
            int colIdx = resolveColumnIndex(rs, rc.getName());
            args[i] = readColumn(rs, colIdx, rc.getType());
        }
        try {
            return canonicalConstructor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new SQLException(
                    "Failed to instantiate " + recordClass.getName() + " from row " + rowNumber, e);
        }
    }

    private static Object readColumn(ResultSet rs, int idx, Class<?> type) throws SQLException {
        if (type == Instant.class) {
            // pgjdbc 42.7.x: getObject(idx, Instant.class) is unsupported. Use the supported
            // path. timestamptz → OffsetDateTime (preserves the offset); timestamp (no tz) →
            // Timestamp read with UTC Calendar (assumes UTC stored values per Hibernate config).
            String colTypeName = rs.getMetaData().getColumnTypeName(idx);
            if (colTypeName != null && colTypeName.contains("timestamptz")) {
                OffsetDateTime odt = rs.getObject(idx, OffsetDateTime.class);
                return odt == null ? null : odt.toInstant();
            }
            Timestamp ts = rs.getTimestamp(idx, UTC);
            return ts == null ? null : ts.toInstant();
        }
        return JdbcUtils.getResultSetValue(rs, idx, type);
    }

    /**
     * Find a result-set column for a record-component name. Tries the exact name first,
     * then snake-case (camelCase → snake_case). Mirrors SimplePropertyRowMapper's tolerance.
     */
    private static int resolveColumnIndex(ResultSet rs, String recordComponentName)
            throws SQLException {
        try {
            return rs.findColumn(recordComponentName);
        } catch (SQLException ignore) {
            // fall through to snake-case
        }
        String snake = camelToSnake(recordComponentName);
        return rs.findColumn(snake);
    }

    static String camelToSnake(String camel) {
        StringBuilder sb = new StringBuilder(camel.length() + 4);
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
