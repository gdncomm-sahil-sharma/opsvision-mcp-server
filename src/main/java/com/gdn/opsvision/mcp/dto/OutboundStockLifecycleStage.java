package com.gdn.opsvision.mcp.dto;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Coarse 4-stage outbound-stock lifecycle bucket. Maps each {@code stock_history.stock_action_type}
 * value to the stage of the outbound progression it represents.
 *
 * <p>The full outbound stock chain is:
 * <ol>
 *   <li>{@link #WAREHOUSE_RESERVATION} — PP creation reserves stock at the aggregate level
 *       ({@code INCREASE_WAREHOUSE_PHYSICAL_RESERVED_STOCK})</li>
 *   <li>{@link #BIN_RESERVATION} — bin allocated; stock reserved at bin level
 *       ({@code INCREASE_BIN_RESERVED_STOCK})</li>
 *   <li>{@link #BIN_DECREASE} — picker picks; both bin counters drop
 *       ({@code DECREASE_BIN_ORIGINAL_STOCK}, {@code DECREASE_BIN_RESERVED_STOCK})</li>
 *   <li>{@link #WAREHOUSE_DECREASE} — GIN issued; aggregate counters drop
 *       ({@code DECREASE_WAREHOUSE_PHYSICAL_*})</li>
 * </ol>
 *
 * <p>Pattern A (half-applied reservation) = trace reached {@code WAREHOUSE_RESERVATION} but not
 * {@code BIN_RESERVATION}. Pattern B (WCS phantom-close) = trace reached {@code BIN_RESERVATION}
 * but not {@code BIN_DECREASE}. Healthy trace progresses through all four stages.
 *
 * <p>{@link #INBOUND_OR_VIRTUAL} buckets event types that aren't part of the outbound chain
 * (inbound putaway, virtual-stock accounting). {@link #OTHER} is the safe default for unmapped
 * values — adding new {@code stock_action_type} values upstream lands in OTHER, surfaced to the
 * caller, rather than silently dropping into a wrong stage.
 *
 * <p>Source: {@code stock_history.stock_action_type} domain (warehouse-inventory schema; 14
 * documented values per memory note {@code qa2_schema_gotchas.md}).
 */
public enum OutboundStockLifecycleStage {
    WAREHOUSE_RESERVATION,
    BIN_RESERVATION,
    BIN_DECREASE,
    WAREHOUSE_DECREASE,
    INBOUND_OR_VIRTUAL,
    OTHER;

    /**
     * Stage of an outbound chain that an action type represents. Returns {@link #OTHER}
     * for unknown values — caller can detect this and flag enum-additions for review.
     */
    public static OutboundStockLifecycleStage forActionType(String actionType) {
        if (actionType == null) {
            return OTHER;
        }
        OutboundStockLifecycleStage stage = ACTION_TYPE_TO_STAGE.get(actionType);
        return stage == null ? OTHER : stage;
    }

    /** The 4 outbound stages, in chronological order. */
    public static final List<OutboundStockLifecycleStage> OUTBOUND_CHAIN = List.of(
            WAREHOUSE_RESERVATION, BIN_RESERVATION, BIN_DECREASE, WAREHOUSE_DECREASE);

    /**
     * Compute a progression view over a list of action types observed in a window or trace.
     * Returns a record carrying reached / missing / furthest stages plus event-bucket counts.
     */
    public static StockTraceEvidence.OutboundLifecycleProgression computeProgression(
            List<String> actionTypes) {
        if (actionTypes == null) {
            actionTypes = List.of();
        }
        Set<OutboundStockLifecycleStage> reached = EnumSet.noneOf(OutboundStockLifecycleStage.class);
        int outboundCount = 0;
        int inboundOrVirtual = 0;
        int unmapped = 0;
        for (String at : actionTypes) {
            OutboundStockLifecycleStage stage = forActionType(at);
            switch (stage) {
                case WAREHOUSE_RESERVATION, BIN_RESERVATION, BIN_DECREASE, WAREHOUSE_DECREASE -> {
                    reached.add(stage);
                    outboundCount++;
                }
                case INBOUND_OR_VIRTUAL -> inboundOrVirtual++;
                case OTHER -> unmapped++;
            }
        }
        // Reached / missing stages reported in chain order, not insertion order.
        List<OutboundStockLifecycleStage> reachedOrdered = new ArrayList<>();
        List<OutboundStockLifecycleStage> missingOrdered = new ArrayList<>();
        for (OutboundStockLifecycleStage s : OUTBOUND_CHAIN) {
            if (reached.contains(s)) {
                reachedOrdered.add(s);
            } else {
                missingOrdered.add(s);
            }
        }
        OutboundStockLifecycleStage furthest = reachedOrdered.isEmpty() ? null
                : reachedOrdered.get(reachedOrdered.size() - 1);
        boolean complete = reachedOrdered.size() == OUTBOUND_CHAIN.size();
        String note = "Considered " + actionTypes.size() + " event(s); "
                + outboundCount + " outbound + " + inboundOrVirtual + " inbound/virtual + "
                + unmapped + " unmapped. Reached " + reachedOrdered.size() + "/4 outbound stage(s); "
                + (complete ? "outbound chain complete." : "missing " + missingOrdered + ".");
        return new StockTraceEvidence.OutboundLifecycleProgression(
                reachedOrdered, missingOrdered, furthest, complete,
                outboundCount, inboundOrVirtual, unmapped, note);
    }

    private static final Map<String, OutboundStockLifecycleStage> ACTION_TYPE_TO_STAGE = Map.ofEntries(
            // Outbound chain
            Map.entry("INCREASE_WAREHOUSE_PHYSICAL_RESERVED_STOCK", WAREHOUSE_RESERVATION),
            Map.entry("INCREASE_BIN_RESERVED_STOCK",                BIN_RESERVATION),
            Map.entry("DECREASE_BIN_ORIGINAL_STOCK",                BIN_DECREASE),
            Map.entry("DECREASE_BIN_RESERVED_STOCK",                BIN_DECREASE),
            Map.entry("DECREASE_WAREHOUSE_PHYSICAL_ORIGINAL_STOCK", WAREHOUSE_DECREASE),
            Map.entry("DECREASE_WAREHOUSE_PHYSICAL_RESERVED_STOCK", WAREHOUSE_DECREASE),
            // Inbound chain (putaway / receive)
            Map.entry("INCREASE_BIN_ORIGINAL_STOCK",                INBOUND_OR_VIRTUAL),
            Map.entry("INCREASE_WAREHOUSE_PHYSICAL_ORIGINAL_STOCK", INBOUND_OR_VIRTUAL),
            // Virtual / reserved-physical accounting (not part of outbound progression)
            Map.entry("INCREASE_WAREHOUSE_VIRTUAL_ORIGINAL_STOCK",          INBOUND_OR_VIRTUAL),
            Map.entry("INCREASE_WAREHOUSE_VIRTUAL_RESERVED_STOCK",          INBOUND_OR_VIRTUAL),
            Map.entry("INCREASE_WAREHOUSE_VIRTUAL_RESERVED_PHYSICAL_STOCK", INBOUND_OR_VIRTUAL),
            Map.entry("DECREASE_WAREHOUSE_VIRTUAL_ORIGINAL_STOCK",          INBOUND_OR_VIRTUAL),
            Map.entry("DECREASE_WAREHOUSE_VIRTUAL_RESERVED_STOCK",          INBOUND_OR_VIRTUAL),
            Map.entry("DECREASE_WAREHOUSE_VIRTUAL_RESERVED_PHYSICAL_STOCK", INBOUND_OR_VIRTUAL));
}
