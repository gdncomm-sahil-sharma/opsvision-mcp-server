package com.gdn.opsvision.mcp.repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Movement-DB bulk searches over {@code picking_task} and {@code picking_task_request}.
 *
 * <p>All filters are AND-combined; null/blank filters use the {@code (:p IS NULL OR …)}
 * idiom so a single parameterized SQL covers every combination. Date columns on these
 * tables are {@code timestamp without time zone}, so we bind {@link LocalDateTime}.
 *
 * <p>Single-DB only: the cross-DB pieces ({@code stock_history} decrement counts, PP code
 * resolution against stockholm) are stitched in the tool layer using
 * {@link StockHistoryRepository#findDecrementCountsByTraceIds} and
 * {@link PickPackageRepository#findCodesByIds}.
 */
@Repository
@Transactional(readOnly = true)
public class MovementSearchRepository {

    private final JdbcClient movement;

    public MovementSearchRepository(@Qualifier("movementJdbcClient") JdbcClient movement) {
        this.movement = movement;
    }

    public List<TaskRow> searchTasks(
            String siteCode,
            LocalDateTime since,
            LocalDateTime until,
            String status,
            String previousStatus,
            String automation,
            String lastModifiedBy,
            String type,
            String skuCode,
            String sourceAreaCode,
            int limit) {
        return movement.sql("""
                        SELECT id,
                               pick_package_id,
                               status,
                               previous_status,
                               created_date,
                               last_modified_date,
                               last_modified_by,
                               automation,
                               source_area_code,
                               sku_code,
                               quantity,
                               stock_trace_id,
                               picking_task_list,
                               type,
                               retry_count,
                               failure_reason,
                               reason
                        FROM picking_task
                        WHERE (CAST(:siteCode AS text) IS NULL OR warehouse_code = :siteCode)
                          AND (CAST(:since AS timestamp) IS NULL OR created_date >= :since)
                          AND (CAST(:until AS timestamp) IS NULL OR created_date < :until)
                          AND (CAST(:status AS text) IS NULL OR status = :status)
                          AND (CAST(:previousStatus AS text) IS NULL OR previous_status = :previousStatus)
                          AND (CAST(:automation AS text) IS NULL OR LOWER(automation) = LOWER(:automation))
                          AND (CAST(:lastModifiedBy AS text) IS NULL OR last_modified_by = :lastModifiedBy)
                          AND (CAST(:type AS text) IS NULL OR type = :type)
                          AND (CAST(:skuCode AS text) IS NULL OR sku_code = :skuCode)
                          AND (CAST(:sourceAreaCode AS text) IS NULL OR source_area_code = :sourceAreaCode)
                        ORDER BY created_date DESC, id DESC
                        LIMIT :lim
                        """)
                .param("siteCode", blankToNull(siteCode))
                .param("since", since)
                .param("until", until)
                .param("status", blankToNull(status))
                .param("previousStatus", blankToNull(previousStatus))
                .param("automation", blankToNull(automation))
                .param("lastModifiedBy", blankToNull(lastModifiedBy))
                .param("type", blankToNull(type))
                .param("skuCode", blankToNull(skuCode))
                .param("sourceAreaCode", blankToNull(sourceAreaCode))
                .param("lim", limit)
                .query(TaskRow.class)
                .list();
    }

    public List<RequestRow> searchRequests(
            String siteCode,
            LocalDateTime since,
            LocalDateTime until,
            String status,
            String previousStatus,
            String referenceType,
            String pickingType,
            String lastModifiedBy,
            Boolean hasMultiSkuBatchFailedReason,
            int limit) {
        return movement.sql("""
                        SELECT id,
                               reference_id,
                               status,
                               previous_status,
                               created_date,
                               last_modified_date,
                               last_modified_by,
                               reference_type,
                               picking_type,
                               type,
                               target_area_code,
                               multi_sku_batch_failed_reason
                        FROM picking_task_request
                        WHERE (CAST(:siteCode AS text) IS NULL OR warehouse_code = :siteCode)
                          AND (CAST(:since AS timestamp) IS NULL OR created_date >= :since)
                          AND (CAST(:until AS timestamp) IS NULL OR created_date < :until)
                          AND (CAST(:status AS text) IS NULL OR status = :status)
                          AND (CAST(:previousStatus AS text) IS NULL OR previous_status = :previousStatus)
                          AND (CAST(:referenceType AS text) IS NULL OR reference_type = :referenceType)
                          AND (CAST(:pickingType AS text) IS NULL OR picking_type = :pickingType)
                          AND (CAST(:lastModifiedBy AS text) IS NULL OR last_modified_by = :lastModifiedBy)
                          AND (
                                CAST(:hasMsbfr AS boolean) IS NULL
                                OR (:hasMsbfr = TRUE  AND multi_sku_batch_failed_reason IS NOT NULL AND multi_sku_batch_failed_reason <> '')
                                OR (:hasMsbfr = FALSE AND (multi_sku_batch_failed_reason IS NULL OR multi_sku_batch_failed_reason = ''))
                              )
                        ORDER BY created_date DESC, id DESC
                        LIMIT :lim
                        """)
                .param("siteCode", blankToNull(siteCode))
                .param("since", since)
                .param("until", until)
                .param("status", blankToNull(status))
                .param("previousStatus", blankToNull(previousStatus))
                .param("referenceType", blankToNull(referenceType))
                .param("pickingType", blankToNull(pickingType))
                .param("lastModifiedBy", blankToNull(lastModifiedBy))
                .param("hasMsbfr", hasMultiSkuBatchFailedReason)
                .param("lim", limit)
                .query(RequestRow.class)
                .list();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    // ─── row records (mapped from SELECT columns) ────────────────────────────

    public record TaskRow(
            long id,
            Long pickPackageId,
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
            String reason) {
    }

    public record RequestRow(
            long id,
            Long referenceId,
            String status,
            String previousStatus,
            Instant createdDate,
            Instant lastModifiedDate,
            String lastModifiedBy,
            String referenceType,
            String pickingType,
            String type,
            String targetAreaCode,
            String multiSkuBatchFailedReason) {
    }
}
