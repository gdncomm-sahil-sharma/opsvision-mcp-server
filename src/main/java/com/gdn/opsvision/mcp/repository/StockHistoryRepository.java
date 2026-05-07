package com.gdn.opsvision.mcp.repository;

import com.gdn.opsvision.mcp.repository.support.RecordRowMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

// Repos return DB-shaped rows; tools layer attaches lifecycle stage via mapping helpers.

/**
 * Reads {@code stock_history} rows for a {@code stock_trace_id}. Some traces have hundreds
 * of events (the largest in the QA2 restore has 442); the tool layer caps + flags
 * truncation, but the SQL itself is bounded by ORDER BY + LIMIT to keep latency stable.
 */
@Repository
@Transactional(readOnly = true)
public class StockHistoryRepository {

    /**
     * Hard cap on rows pulled per trace. Tool layer surfaces a {@code truncated=true} flag
     * when the limit is hit, so the agent knows there's more.
     */
    private static final int MAX_EVENTS = 200;

    private final JdbcClient inventory;

    public StockHistoryRepository(@Qualifier("inventoryJdbcClient") JdbcClient inventory) {
        this.inventory = inventory;
    }

    public List<MutationRow> findByTrace(String traceId) {
        return inventory.sql("""
                        SELECT id,
                               created_date AT TIME ZONE 'UTC' AS created_date,
                               created_by,
                               warehouse_item_master,
                               bin_code,
                               external_reference_id,
                               parent_reference_id,
                               parent_reference_type,
                               reference_id,
                               reference_type,
                               process_type,
                               stock_action_type,
                               transaction_quantity,
                               old_quantity,
                               new_quantity
                        FROM stock_history
                        WHERE stock_trace_id = :trace
                        ORDER BY created_date, id
                        LIMIT :cap
                        """)
                .param("trace", traceId)
                .param("cap", MAX_EVENTS + 1)
                .query(RecordRowMapper.of(MutationRow.class))
                .list();
    }

    /** Raw row shape; tool layer maps to {@code StockTraceEvidence.Mutation} + lifecycleStage. */
    public record MutationRow(
            long id,
            Instant createdDate,
            String createdBy,
            Long warehouseItemMaster,
            String binCode,
            String externalReferenceId,
            String parentReferenceId,
            String parentReferenceType,
            String referenceId,
            String referenceType,
            String processType,
            String stockActionType,
            Integer transactionQuantity,
            Integer oldQuantity,
            Integer newQuantity) {
    }

    public int hardCap() {
        return MAX_EVENTS;
    }

    /**
     * Batch count of {@code DECREASE_*} events per {@code stock_trace_id}. Used by the
     * {@code findPickingTasks(phantomClose=true)} cross-DB filter to identify traces whose
     * full reservation chain ran but never produced a stock decrement (the Pattern B
     * fingerprint). Returns 0 for traces with no matching rows; missing keys mean the
     * trace had no events at all (caller treats as decrementCount=0 too).
     */
    public Map<String, Long> findDecrementCountsByTraceIds(Collection<String> traceIds) {
        if (traceIds == null || traceIds.isEmpty()) {
            return Map.of();
        }
        List<TraceCount> rows = inventory.sql("""
                        SELECT stock_trace_id, count(*) FILTER (WHERE stock_action_type LIKE 'DECREASE\\_%') AS decrement_count
                        FROM stock_history
                        WHERE stock_trace_id IN (:traceIds)
                        GROUP BY stock_trace_id
                        """)
                .param("traceIds", traceIds)
                .query(RecordRowMapper.of(TraceCount.class))
                .list();
        Map<String, Long> out = new LinkedHashMap<>(rows.size());
        for (TraceCount r : rows) {
            out.put(r.stockTraceId(), r.decrementCount());
        }
        return out;
    }

    private record TraceCount(String stockTraceId, long decrementCount) {
    }

    /**
     * Per-WIM grouped breakdown of stock_history rows in the {@code [since, until)} window.
     * One row per distinct {@code (process_type, stock_action_type)} pair, with
     * {@code count(*)} and {@code sum(transaction_quantity)}. Used by
     * {@code getStockHistoryForItem} to give the agent a histogram of what kinds of activity
     * happened, without re-aggregating raw rows.
     *
     * <p>Note: {@code stock_history.created_date} is {@code timestamp without time zone};
     * we bind {@link LocalDateTime}.
     */
    public List<ActionGroupRow> findGroupedByActionForWim(
            long wimId, LocalDateTime since, LocalDateTime until) {
        return inventory.sql("""
                        SELECT process_type,
                               stock_action_type,
                               count(*)                  AS event_count,
                               sum(transaction_quantity) AS total_transaction_quantity
                          FROM stock_history
                         WHERE warehouse_item_master = :wimId
                           AND created_date >= :since
                           AND created_date <  :until
                         GROUP BY process_type, stock_action_type
                         ORDER BY process_type, stock_action_type
                        """)
                .param("wimId", wimId)
                .param("since", since)
                .param("until", until)
                .query(RecordRowMapper.of(ActionGroupRow.class))
                .list();
    }

    /** Raw row shape for grouped histogram; tool layer attaches lifecycleStage. */
    public record ActionGroupRow(
            String processType,
            String stockActionType,
            long eventCount,
            Long totalTransactionQuantity) {
    }

    /**
     * Total stock_history event count for a WIM in {@code [since, until)} — used to set the
     * {@code totalEvents} field on the evidence (independent of any LIMIT applied to the
     * decrement-rows query).
     */
    public long countEventsForWim(long wimId, LocalDateTime since, LocalDateTime until) {
        Long n = inventory.sql("""
                        SELECT count(*)
                          FROM stock_history
                         WHERE warehouse_item_master = :wimId
                           AND created_date >= :since
                           AND created_date <  :until
                        """)
                .param("wimId", wimId)
                .param("since", since)
                .param("until", until)
                .query(Long.class)
                .single();
        return n == null ? 0L : n;
    }

    /**
     * Top-N most recent {@code DECREASE_*} rows for a WIM in {@code [since, until)}, ordered
     * by {@code created_date DESC, id DESC}. Caller passes {@code limit + 1} to detect
     * truncation. Backslash-underscore escape on the LIKE pattern is required because
     * {@code _} is a wildcard.
     */
    public List<DecrementEventRow> findRecentDecrementsForWim(
            long wimId, LocalDateTime since, LocalDateTime until, int limit) {
        return inventory.sql("""
                        SELECT created_date AT TIME ZONE 'UTC' AS created_date,
                               process_type,
                               stock_action_type,
                               transaction_quantity,
                               old_quantity,
                               new_quantity,
                               bin_code,
                               reference_id,
                               reference_type,
                               stock_trace_id
                          FROM stock_history
                         WHERE warehouse_item_master = :wimId
                           AND created_date >= :since
                           AND created_date <  :until
                           AND stock_action_type LIKE 'DECREASE\\_%'
                         ORDER BY created_date DESC, id DESC
                         LIMIT :lim
                        """)
                .param("wimId", wimId)
                .param("since", since)
                .param("until", until)
                .param("lim", limit)
                .query(RecordRowMapper.of(DecrementEventRow.class))
                .list();
    }

    /** Raw row shape for recent decrements; tool layer attaches lifecycleStage. */
    public record DecrementEventRow(
            Instant createdDate,
            String processType,
            String stockActionType,
            Integer transactionQuantity,
            Integer oldQuantity,
            Integer newQuantity,
            String binCode,
            String referenceId,
            String referenceType,
            String stockTraceId) {
    }

    // ─── findStockDiscrepancyOrigin: per-trace symmetry forensics ──────────────────

    /**
     * Find {@code stock_trace_id}s for a WIM where the bin-level net delta on ORIGINAL
     * stock doesn't equal the WIM-level net delta. These are the per-trace forensic
     * cases that {@code findStockDiscrepancyOrigin} surfaces.
     *
     * <p>Sorted by absolute divergence DESC so the worst leak leads. Caller passes
     * {@code limit + 1} to detect truncation.
     */
    public List<DivergentTraceRow> findDivergentTracesForWim(
            long wimId, LocalDateTime since, int limit) {
        return inventory.sql("""
                        WITH events AS (
                          SELECT stock_trace_id,
                                 CASE
                                   WHEN stock_action_type='INCREASE_BIN_ORIGINAL_STOCK' THEN transaction_quantity
                                   WHEN stock_action_type='DECREASE_BIN_ORIGINAL_STOCK' THEN -transaction_quantity
                                   ELSE 0
                                 END AS bin_orig,
                                 CASE
                                   WHEN stock_action_type='INCREASE_WAREHOUSE_PHYSICAL_ORIGINAL_STOCK' THEN transaction_quantity
                                   WHEN stock_action_type='DECREASE_WAREHOUSE_PHYSICAL_ORIGINAL_STOCK' THEN -transaction_quantity
                                   ELSE 0
                                 END AS wim_orig,
                                 created_date
                            FROM stock_history
                           WHERE warehouse_item_master = :wimId
                             AND stock_trace_id IS NOT NULL
                             AND (CAST(:since AS timestamp) IS NULL OR created_date >= :since)
                        )
                        SELECT stock_trace_id,
                               sum(bin_orig)::int                                  AS bin_net_original_delta,
                               sum(wim_orig)::int                                  AS wim_net_original_delta,
                               (sum(bin_orig) - sum(wim_orig))::int                AS original_divergence,
                               count(*)::int                                       AS event_count,
                               (min(created_date) AT TIME ZONE 'UTC')              AS first_event_time,
                               (max(created_date) AT TIME ZONE 'UTC')              AS last_event_time
                          FROM events
                         GROUP BY stock_trace_id
                        HAVING sum(bin_orig) <> sum(wim_orig)
                         ORDER BY abs(sum(bin_orig) - sum(wim_orig)) DESC, stock_trace_id
                         LIMIT :lim
                        """)
                .param("wimId", wimId)
                .param("since", since)
                .param("lim", limit)
                .query(RecordRowMapper.of(DivergentTraceRow.class))
                .list();
    }

    /** Full event list for one trace, capped at {@code limit + 1} for truncation detection. */
    public List<TraceEventRow> findEventsForTrace(String stockTraceId, long wimId, int limit) {
        return inventory.sql("""
                        SELECT created_date AT TIME ZONE 'UTC' AS created_date,
                               stock_action_type,
                               process_type,
                               transaction_quantity,
                               old_quantity,
                               new_quantity,
                               bin_code,
                               reference_type,
                               reference_id
                          FROM stock_history
                         WHERE stock_trace_id = :trace
                           AND warehouse_item_master = :wimId
                         ORDER BY created_date, id
                         LIMIT :lim
                        """)
                .param("trace", stockTraceId)
                .param("wimId", wimId)
                .param("lim", limit)
                .query(RecordRowMapper.of(TraceEventRow.class))
                .list();
    }

    /**
     * Snapshot of WIM-level rollup vs sum of bin-level rows. Single row by WIM id.
     * Tool layer compares the two sides.
     */
    public AggregateVsBinsRow findAggregateVsBinsForWim(long wimId) {
        return inventory.sql("""
                        WITH agg AS (
                          SELECT pos.quantity AS aggregate_original_qty,
                                 prs.quantity AS aggregate_reserved_qty
                            FROM warehouse_item_master wim
                            LEFT JOIN warehouse_physical_original_stock pos ON pos.warehouse_item_master = wim.id
                            LEFT JOIN warehouse_physical_reserved_stock  prs ON prs.warehouse_item_master = wim.id
                           WHERE wim.id = :wimId
                        ),
                        bins AS (
                          SELECT sum(COALESCE(bos.quantity, 0))::int AS bin_sum_original_qty,
                                 sum(COALESCE(brs.quantity, 0))::int AS bin_sum_reserved_qty,
                                 count(*)::int                       AS bin_count
                            FROM warehouse_item_bin_master wibm
                            LEFT JOIN warehouse_bin_physical_original_stock bos ON bos.warehouse_item_bin_master = wibm.id
                            LEFT JOIN warehouse_bin_physical_reserved_stock  brs ON brs.warehouse_item_bin_master = wibm.id
                           WHERE wibm.warehouse_item_master = :wimId
                        )
                        SELECT a.aggregate_original_qty,
                               COALESCE(b.bin_sum_original_qty, 0)               AS bin_sum_original_qty,
                               (COALESCE(a.aggregate_original_qty, 0)
                                  - COALESCE(b.bin_sum_original_qty, 0))::int    AS original_divergence,
                               a.aggregate_reserved_qty,
                               COALESCE(b.bin_sum_reserved_qty, 0)               AS bin_sum_reserved_qty,
                               (COALESCE(a.aggregate_reserved_qty, 0)
                                  - COALESCE(b.bin_sum_reserved_qty, 0))::int    AS reserved_divergence,
                               COALESCE(b.bin_count, 0)                          AS bin_count
                          FROM agg a, bins b
                        """)
                .param("wimId", wimId)
                .query(RecordRowMapper.of(AggregateVsBinsRow.class))
                .single();
    }

    public record DivergentTraceRow(
            String stockTraceId,
            int binNetOriginalDelta,
            int wimNetOriginalDelta,
            int originalDivergence,
            int eventCount,
            Instant firstEventTime,
            Instant lastEventTime) {
    }

    public record TraceEventRow(
            Instant createdDate,
            String stockActionType,
            String processType,
            int transactionQuantity,
            Integer oldQuantity,
            Integer newQuantity,
            String binCode,
            String referenceType,
            String referenceId) {
    }

    public record AggregateVsBinsRow(
            Integer aggregateOriginalQty,
            Integer binSumOriginalQty,
            Integer originalDivergence,
            Integer aggregateReservedQty,
            Integer binSumReservedQty,
            Integer reservedDivergence,
            int binCount) {
    }
}
