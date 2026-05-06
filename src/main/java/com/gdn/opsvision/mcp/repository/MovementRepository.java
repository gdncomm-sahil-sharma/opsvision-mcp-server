package com.gdn.opsvision.mcp.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads warehouse-movement state for a pick package. Both {@code picking_task} and
 * {@code picking_task_request} are queried directly — they hold one row per artifact in
 * its current (latest) status, with {@code previous_status} carrying the most recent
 * transition source. The status-partitioned shadow tables (picking_task_open,
 * picking_task_closed, etc.) are intentionally not used; the main tables are authoritative.
 */
@Repository
@Transactional(readOnly = true)
public class MovementRepository {

    private final JdbcClient movement;

    public MovementRepository(@Qualifier("movementJdbcClient") JdbcClient movement) {
        this.movement = movement;
    }

    public List<TaskRequestRow> findRequestsForPp(long ppId) {
        return movement.sql("""
                        SELECT id,
                               status,
                               previous_status,
                               created_date,
                               last_modified_date,
                               last_modified_by,
                               reference_type,
                               target_area_code,
                               picking_type,
                               type,
                               multi_sku_batch_failed_reason
                        FROM picking_task_request
                        WHERE reference_id = :pp
                        ORDER BY created_date, id
                        """)
                .param("pp", ppId)
                .query(TaskRequestRow.class)
                .list();
    }

    public List<TaskRow> findTasksForPp(long ppId) {
        return movement.sql("""
                        SELECT id,
                               status,
                               previous_status,
                               created_date,
                               last_modified_date,
                               last_modified_by,
                               picking_task_request_detail,
                               picking_task_list,
                               source_area_code,
                               sku_code,
                               quantity,
                               automation,
                               stock_trace_id,
                               type,
                               retry_count,
                               failure_reason,
                               reason
                        FROM picking_task
                        WHERE pick_package_id = :pp
                        ORDER BY created_date, id
                        """)
                .param("pp", ppId)
                .query(TaskRow.class)
                .list();
    }

    /** Raw row shape; tool layer attaches lifecycleStage + previousLifecycleStage. */
    public record TaskRequestRow(
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

    /** Raw row shape for picking_task. */
    public record TaskRow(
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
