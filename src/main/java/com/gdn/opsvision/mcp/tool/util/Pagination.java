package com.gdn.opsvision.mcp.tool.util;

/**
 * Shared pagination defaults for bulk-search tools (Find* family).
 *
 * <p>The convention across these tools: clients pass an optional {@code limit}; tools
 * cap it at {@link #MAX_LIMIT} and substitute {@link #DEFAULT_LIMIT} when null/zero.
 * The repository layer queries for {@code limit + 1} rows so a {@code truncated} flag
 * can be set when the cap is hit.
 */
public final class Pagination {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    private Pagination() {
    }

    /** Clamp a caller-supplied limit to {@code [1, MAX_LIMIT]}, defaulting null/&lt;=0 to {@link #DEFAULT_LIMIT}. */
    public static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
