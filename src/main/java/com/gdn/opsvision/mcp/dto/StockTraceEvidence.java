package com.gdn.opsvision.mcp.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Evidence pack for {@code getStockTrace} — the chronologically-ordered stock_history rows
 * that share a single {@code stock_trace_id} UUID.
 *
 * <p>{@code stock_trace_id} is the universal correlator inside warehouse-inventory: it stitches
 * every mutation for one logical stock movement (PP creation reservation → bin reservation
 * → PLD pick decrement → GIN decrement → adjustments) into one chain. The same UUID also
 * lives on {@code stockholm.picking_item.stock_trace_id} and
 * {@code stockholm.pick_list_details.stock_trace_id}, so it bridges from picking-side
 * primitives back into stock history.
 *
 * <p>Returns FACTS, not VERDICTS. {@code byActionType} and {@code byReferenceType} are
 * convenience rollups so the agent doesn't have to re-aggregate; they don't change what
 * the {@code mutations} list says.
 */
public record StockTraceEvidence(
        String traceId,
        int eventCount,
        boolean truncated,
        Map<String, Long> byActionType,
        Map<String, Long> byReferenceType,
        List<Mutation> mutations) {

    public record Mutation(
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
}
