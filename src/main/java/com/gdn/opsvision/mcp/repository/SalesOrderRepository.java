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
}
