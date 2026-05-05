package com.gdn.opsvision.mcp.dto;

import java.time.Instant;
import java.util.List;

/**
 * Evidence pack for {@code findPickingTasks} — a paginated list of {@code picking_task} rows
 * matching the requested filters, with each match resolved to its parent pick package code.
 *
 * <p>When {@code phantomCloseApplied=true}, the result has been post-filtered to only include
 * tasks whose {@code stock_trace_id} has zero {@code DECREASE_*} events in stock_history (the
 * Pattern B / WCS phantom-close fingerprint). Each match's {@code decrementEventCount} carries
 * the cross-DB count for transparency; in this mode every match's count is 0.
 */
public record PickingTaskSearchEvidence(
        int totalReturned,
        boolean truncated,
        boolean phantomCloseApplied,
        List<TaskMatch> matches) {

    public record TaskMatch(
            long taskId,
            Long pickPackageId,
            String pickPackageCode,
            String status,
            String previousStatus,
            Instant createdDate,
            Instant lastModifiedDate,
            String lastModifiedBy,
            String automation,
            String sourceAreaCode,
            String skuCode,
            Integer quantity,
            String stockTraceId,
            Long pickingTaskList,
            String type,
            Integer retryCount,
            String failureReason,
            String reason,
            Long decrementEventCount) {
    }
}
