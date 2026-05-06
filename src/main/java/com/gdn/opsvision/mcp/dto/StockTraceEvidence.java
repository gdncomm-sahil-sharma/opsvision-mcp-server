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
 * convenience rollups so the agent doesn't have to re-aggregate.
 *
 * <p>{@link OutboundLifecycleProgression} surfaces a structural view of how far the trace
 * progressed through the outbound chain — Pattern A (half-applied reservation) and Pattern
 * B (WCS phantom-close) become one-look checks: {@code furthestStage=WAREHOUSE_RESERVATION
 * && !isOutboundComplete} for Pattern A, {@code furthestStage=BIN_RESERVATION &&
 * !isOutboundComplete} for Pattern B. Each {@link Mutation} also carries its
 * {@code lifecycleStage} so the agent can scan stage-by-stage rather than parsing raw
 * action_type strings.
 */
public record StockTraceEvidence(
        String traceId,
        int eventCount,
        boolean truncated,
        Map<String, Long> byActionType,
        Map<String, Long> byReferenceType,
        OutboundLifecycleProgression outboundLifecycle,
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
            OutboundStockLifecycleStage lifecycleStage,
            Integer transactionQuantity,
            Integer oldQuantity,
            Integer newQuantity) {
    }

    /**
     * Coarse view of how far the trace progressed through the 4-stage outbound chain
     * ({@code WAREHOUSE_RESERVATION → BIN_RESERVATION → BIN_DECREASE → WAREHOUSE_DECREASE}).
     *
     * <p>{@code reachedStages} lists the stages observed in the trace (deduped, in stage
     * order — not event order). {@code missingStages} lists outbound stages that did NOT
     * fire. {@code furthestStage} is the latest outbound stage reached.
     * {@code isOutboundComplete} is true iff all four outbound stages fired.
     *
     * <p>Inbound and virtual events (from {@link OutboundStockLifecycleStage#INBOUND_OR_VIRTUAL})
     * are counted separately in {@code inboundOrVirtualEventCount} but don't affect the
     * outbound progression. {@code unmappedActionTypeCount} counts events with stage
     * {@code OTHER} — surfaces any new action_type values the mapping doesn't cover yet.
     *
     * <p>{@code derivationNote} explains the rule and inputs.
     */
    public record OutboundLifecycleProgression(
            List<OutboundStockLifecycleStage> reachedStages,
            List<OutboundStockLifecycleStage> missingStages,
            OutboundStockLifecycleStage furthestStage,
            boolean isOutboundComplete,
            int outboundEventCount,
            int inboundOrVirtualEventCount,
            int unmappedActionTypeCount,
            String derivationNote) {
    }
}
