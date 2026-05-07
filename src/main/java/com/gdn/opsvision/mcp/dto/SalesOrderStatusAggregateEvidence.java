package com.gdn.opsvision.mcp.dto;

import java.util.List;

/**
 * Evidence pack for {@code aggregateSalesOrdersByStatus} — histogram of sales_order rows
 * grouped by {@code last_status}, with both the raw ordinal and the human-readable
 * {@code SOStatus} label so the agent never has to translate codes itself. A second
 * roll-up by coarse {@link SalesOrderLifecycleStage} surfaces the same data at the bucket
 * level (PRE_FULFILLMENT / IN_FULFILLMENT / POST_PICK / TERMINATED / etc.) for quick
 * "where are orders today?" reads.
 *
 * <p>Date filter is anchored on {@code sales_order.last_process_date} (Hibernate
 * {@code @UpdateTimestamp}), so a windowed query counts SOs that had ANY status change
 * inside the window — useful for "how many orders moved through today?" but does not
 * distinguish between SOs that ENTERED a status vs ones merely TOUCHED while already in
 * it. The SO state machine doesn't track per-transition timestamps.
 */
public record SalesOrderStatusAggregateEvidence(
        String siteCode,
        String sinceDate,
        String untilDate,
        Integer lastStatusFilter,
        long totalCount,
        List<StatusBucket> byLastStatus,
        List<StageBucket> byLifecycleStage) {

    /** One bucket per distinct {@code last_status} ordinal present in the window. */
    public record StatusBucket(
            int code,
            String label,
            String lifecycleStage,
            long count) {
    }

    /** Coarse roll-up by {@link SalesOrderLifecycleStage}. */
    public record StageBucket(
            String lifecycleStage,
            long count) {
    }
}
