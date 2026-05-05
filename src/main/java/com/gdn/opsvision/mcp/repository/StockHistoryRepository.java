package com.gdn.opsvision.mcp.repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.gdn.opsvision.mcp.dto.StockTraceEvidence.Mutation;

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

    public List<Mutation> findByTrace(String traceId) {
        return inventory.sql("""
                        SELECT id,
                               created_date,
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
                .query(Mutation.class)
                .list();
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
                .query(TraceCount.class)
                .list();
        Map<String, Long> out = new LinkedHashMap<>(rows.size());
        for (TraceCount r : rows) {
            out.put(r.stockTraceId(), r.decrementCount());
        }
        return out;
    }

    private record TraceCount(String stockTraceId, long decrementCount) {
    }
}
