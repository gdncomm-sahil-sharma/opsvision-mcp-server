package com.gdn.opsvision.mcp.repository;

import com.gdn.opsvision.mcp.repository.support.RecordRowMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stockholm-side reads keyed by {@code sales_order.order_item_id} (confirmed unique by the
 * domain owner). Returns the SO header + denormalized parent {@code pick_package.code} +
 * resolved {@code siteCode} via the SO's {@code warehouse} FK.
 *
 * <p>The picking_item list is a separate query — {@code picking_item} is 1-to-many with
 * {@code sales_order} (e.g. partial picks across waves), and joining inline would either
 * fan-out the header columns or force GROUP BY gymnastics. Two queries keep the SQL clean.
 */
@Repository
@Transactional(readOnly = true)
public class SalesOrderRepository {

    private final JdbcClient stockholm;

    public SalesOrderRepository(@Qualifier("stockholmJdbcClient") JdbcClient stockholm) {
        this.stockholm = stockholm;
    }

    public Optional<SalesOrderHeader> findHeaderByOrderItemId(String orderItemId) {
        return stockholm.sql("""
                        SELECT so.id              AS so_id,
                               so.sales_order_number AS so_number,
                               so.last_status,
                               so.picking_stuck,
                               so.order_stuck_reason,
                               so.last_process_date AT TIME ZONE 'UTC' AS last_process_date,
                               so.pick_package_id,
                               pp.code            AS pp_code,
                               w.code             AS site_code
                          FROM sales_order so
                          LEFT JOIN pick_package pp ON pp.id = so.pick_package_id
                          LEFT JOIN warehouse    w  ON w.id  = so.warehouse
                         WHERE so.order_item_id = :orderItemId
                        """)
                .param("orderItemId", orderItemId)
                .query(RecordRowMapper.of(SalesOrderHeader.class))
                .optional();
    }

    public List<PickingItemRow> findPickingItemsBySoId(long soId) {
        return stockholm.sql("""
                        SELECT pi.id                       AS picking_item_id,
                               i.code                      AS sku_code,
                               pi.status,
                               pi.quantity,
                               pi.current_picked_quantity,
                               pi.stock_trace_id
                          FROM picking_item pi
                          LEFT JOIN warehouse_item wi ON wi.id = pi.warehouse_item
                          LEFT JOIN item            i  ON i.id  = wi.item
                         WHERE pi.sales_order = :soId
                         ORDER BY pi.id
                        """)
                .param("soId", soId)
                .query(RecordRowMapper.of(PickingItemRow.class))
                .list();
    }

    // ─── row records (mapped from SELECT columns) ────────────────────────────

    public record SalesOrderHeader(
            long soId,
            String soNumber,
            int lastStatus,
            Boolean pickingStuck,
            String orderStuckReason,
            Instant lastProcessDate,
            Long pickPackageId,
            String ppCode,
            String siteCode) {
    }

    public record PickingItemRow(
            long pickingItemId,
            String skuCode,
            String status,
            Integer quantity,
            Integer currentPickedQuantity,
            String stockTraceId) {
    }

    // ─── aggregateSalesOrdersByStatus: status histogram for ops dashboards ───────

    /**
     * Histogram of sales_order rows grouped by {@code last_status}, optionally scoped to a
     * site (warehouse code), a {@code last_process_date} window, and/or a single
     * {@code last_status} ordinal. All three filters are optional and AND-combine.
     *
     * <p>{@code last_process_date} is the SO-level update timestamp ({@code timestamp without
     * time zone} in postgres). Bind {@link LocalDateTime} for the window bounds, same as
     * {@code stock_history} queries.
     */
    public List<StatusCountRow> aggregateByStatus(
            String siteCode, LocalDateTime since, LocalDateTime until, Integer lastStatus) {
        return stockholm.sql("""
                        SELECT so.last_status,
                               count(*) AS so_count
                          FROM sales_order so
                          LEFT JOIN warehouse w ON w.id = so.warehouse
                         WHERE (CAST(:site AS varchar) IS NULL OR w.code = :site)
                           AND (CAST(:since AS timestamp) IS NULL OR so.last_process_date >= :since)
                           AND (CAST(:until AS timestamp) IS NULL OR so.last_process_date <  :until)
                           AND (CAST(:status AS int) IS NULL OR so.last_status = :status)
                         GROUP BY so.last_status
                         ORDER BY so_count DESC, so.last_status
                        """)
                .param("site", siteCode)
                .param("since", since)
                .param("until", until)
                .param("status", lastStatus)
                .query(RecordRowMapper.of(StatusCountRow.class))
                .list();
    }

    public record StatusCountRow(int lastStatus, long soCount) {
    }

    // ─── findOrdersByLastProcessDate: dashboard view (active snapshot + terminal-in-window)

    /**
     * SOs currently in non-terminal statuses at a site, grouped by {@code last_status},
     * with up to {@code sampleSize} most-recent {@code order_item_id}s per bucket. No date
     * filter — this is a live snapshot of pending / in-flight orders. Terminal statuses
     * ({@code last_status IN (7, 9, 16, 17)} = CANCELLED / ITEM_ISSUED / REJECTED /
     * OUT_OF_STOCK_CANCEL) are excluded from this query.
     */
    public List<StatusBucketWithSamplesRow> findActiveSnapshotByStatus(
            String siteCode, int sampleSize) {
        return stockholm.sql("""
                        WITH ranked AS (
                          SELECT so.last_status,
                                 so.order_item_id,
                                 row_number() OVER (
                                   PARTITION BY so.last_status
                                   ORDER BY so.last_process_date DESC, so.id DESC
                                 ) AS rn
                            FROM sales_order so
                            JOIN warehouse w ON w.id = so.warehouse
                           WHERE w.code = :site
                             AND so.last_status NOT IN (7, 9, 16, 17)
                        )
                        SELECT last_status,
                               count(*)::bigint AS so_count,
                               COALESCE(
                                 array_agg(order_item_id ORDER BY rn ASC)
                                   FILTER (WHERE rn <= :sampleSize),
                                 ARRAY[]::varchar[]
                               ) AS sample_order_item_ids
                          FROM ranked
                         GROUP BY last_status
                         ORDER BY so_count DESC, last_status
                        """)
                .param("site", siteCode)
                .param("sampleSize", sampleSize)
                .query(RecordRowMapper.of(StatusBucketWithSamplesRow.class))
                .list();
    }

    /**
     * SOs that reached a terminal status inside {@code [since, until)}, grouped by
     * {@code last_status}, with up to {@code sampleSize} most-recent {@code order_item_id}s
     * per bucket. Terminal status set is fixed:
     * <ul>
     *   <li>7 = CANCELLED</li>
     *   <li>9 = ITEM_ISSUED (GIN issued — order shipped)</li>
     *   <li>16 = REJECTED</li>
     *   <li>17 = OUT_OF_STOCK_CANCEL</li>
     * </ul>
     * Anchored on {@code last_process_date} (Hibernate {@code @UpdateTimestamp}) — this
     * tracks the moment of the last update on the SO, not specifically the entry into the
     * terminal state, but for terminal SOs the two coincide in practice (no further
     * updates expected after the terminal transition).
     */
    public List<StatusBucketWithSamplesRow> findTerminalInWindowByStatus(
            String siteCode, LocalDateTime since, LocalDateTime until, int sampleSize) {
        return stockholm.sql("""
                        WITH ranked AS (
                          SELECT so.last_status,
                                 so.order_item_id,
                                 row_number() OVER (
                                   PARTITION BY so.last_status
                                   ORDER BY so.last_process_date DESC, so.id DESC
                                 ) AS rn
                            FROM sales_order so
                            JOIN warehouse w ON w.id = so.warehouse
                           WHERE w.code = :site
                             AND so.last_status IN (7, 9, 16, 17)
                             AND so.last_process_date >= :since
                             AND so.last_process_date <  :until
                        )
                        SELECT last_status,
                               count(*)::bigint AS so_count,
                               COALESCE(
                                 array_agg(order_item_id ORDER BY rn ASC)
                                   FILTER (WHERE rn <= :sampleSize),
                                 ARRAY[]::varchar[]
                               ) AS sample_order_item_ids
                          FROM ranked
                         GROUP BY last_status
                         ORDER BY so_count DESC, last_status
                        """)
                .param("site", siteCode)
                .param("since", since)
                .param("until", until)
                .param("sampleSize", sampleSize)
                .query(RecordRowMapper.of(StatusBucketWithSamplesRow.class))
                .list();
    }

    public record StatusBucketWithSamplesRow(
            int lastStatus,
            long soCount,
            String[] sampleOrderItemIds) {
    }
}
