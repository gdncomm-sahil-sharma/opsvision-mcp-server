package com.gdn.opsvision.mcp.tool.util;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Shared parser for ISO date / datetime tool inputs that bind to
 * {@code timestamp without time zone} columns (movement DB picking_task /
 * picking_task_request, inventory DB stock_history).
 *
 * <p>Accepts:
 * <ul>
 *   <li>{@code "2026-05-04"} — date-only; lower bound = start of day,
 *       upper bound = start of next day (inclusive-day semantics).</li>
 *   <li>{@code "2026-05-04T03:00:00"} — datetime; used as-is. Lower bound is
 *       inclusive ({@code >=}); upper bound is exclusive ({@code <}).</li>
 * </ul>
 *
 * <p>Trailing {@code 'Z'} is tolerated but timestamps are interpreted in the
 * database's local zone (the source columns have no timezone).
 *
 * <p>Returns {@code null} for null / blank input — callers translate that into
 * "no bound" via the {@code (CAST(:p AS timestamp) IS NULL OR …)} idiom.
 */
public final class IsoBound {

    private IsoBound() {
    }

    /** Parse a lower bound (inclusive moment for datetime; start of day for date-only). */
    public static LocalDateTime parseSince(String iso) {
        return parse(iso, /*untilSemantics=*/false);
    }

    /** Parse an upper bound (exclusive moment for datetime; start of next day for date-only). */
    public static LocalDateTime parseUntil(String iso) {
        return parse(iso, /*untilSemantics=*/true);
    }

    private static LocalDateTime parse(String iso, boolean untilSemantics) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        String s = iso.trim();
        if (s.endsWith("Z")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.contains("T")) {
            return LocalDateTime.parse(s);
        }
        LocalDate d = LocalDate.parse(s);
        return untilSemantics ? d.plusDays(1).atStartOfDay() : d.atStartOfDay();
    }
}
