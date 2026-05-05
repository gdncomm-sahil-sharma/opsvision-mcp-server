package com.gdn.opsvision.mcp.repository;

import java.util.List;

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
}
