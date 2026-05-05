package com.gdn.opsvision.mcp.dto;

import java.time.Instant;
import java.util.List;

/**
 * Evidence pack for {@code getMovementHistory} — the warehouse-movement-DB lifecycle for one
 * pick package: every {@code picking_task_request} row plus every {@code picking_task} row,
 * chronologically by {@code created_date}.
 *
 * <p>The movement DB doesn't keep a row-level audit table; instead each row carries
 * {@code previous_status} + {@code last_modified_date} + {@code last_modified_by}, so the
 * latest single transition is recoverable from the row itself. For the typical PP lifecycle
 * (request CREATED → IN_PROGRESS → CLOSED, tasks PENDING_CLOSED → CLOSED, etc.) one row per
 * artifact is sufficient. Some {@code picking_task_*} status-partitioned tables exist but
 * are kept intentionally out of scope for this tool — the main {@code picking_task} /
 * {@code picking_task_request} tables already hold the row in whatever its current status is.
 *
 * <p>Returns FACTS, not VERDICTS. Suspicious patterns the agent may look for: a task closing
 * with {@code previous_status='PENDING_CLOSED'} and no {@code DECREASE_BIN_*} stock_history
 * event for the SKU's stock_trace_id (i.e. the task-layer closed without a real pick); or a
 * request stuck in HOLD with no children tasks; or a {@code failure_reason}/{@code reason}
 * populated on a CLOSED task. The tool surfaces these fields without flagging them.
 */
public record MovementHistoryEvidence(
        PickPackageEvidence.Header pickPackage,
        List<TaskRequest> taskRequests,
        List<Task> tasks) {

    public record TaskRequest(
            long id,
            String status,
            String previousStatus,
            Instant createdDate,
            Instant lastModifiedDate,
            String lastModifiedBy,
            String referenceType,
            String targetAreaCode,
            String pickingType,
            String type,
            String multiSkuBatchFailedReason) {
    }

    public record Task(
            long id,
            String status,
            String previousStatus,
            Instant createdDate,
            Instant lastModifiedDate,
            String lastModifiedBy,
            Long pickingTaskRequestDetail,
            Long pickingTaskList,
            String sourceAreaCode,
            String skuCode,
            Integer quantity,
            String automation,
            String stockTraceId,
            String type,
            Integer retryCount,
            String failureReason,
            String reason) {
    }
}
