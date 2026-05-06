package com.gdn.opsvision.mcp.dto;

import java.util.Map;

/**
 * Coarse lifecycle bucket for {@code sales_order.last_status} (the customer-facing SO state
 * machine). Maps the 18 ordinals from {@code SOStatus} to 7 operational stages — agents can
 * compare stages without knowing the full ordinal mapping.
 *
 * <p>Source: {@code stockholm/InventoryWebModel/src/main/java/com/gdn/inventory/web/type/SOStatus.java}
 * (18 values with explicit ordinals 0–17, verified against QA2 distribution).
 *
 * <p>QA2 distribution at writing (last_status: count): 0=3,272 (CREATED), 4=148, 5=3,475
 * (OUT_OF_STOCK), 6=31,736 (ITEM_PICKED), 7=13,457 (CANCELLED), 8=27,991 (ITEM_PACKED),
 * 9=29,759 (ITEM_ISSUED), 12=7, 14=131,726 (PICK_PACKAGE_CREATED), 17=473 (OUT_OF_STOCK_CANCEL),
 * 18=134 (unknown — flagged as OTHER pending source review).
 */
public enum SalesOrderLifecycleStage {
    /** Pre-fulfillment: order created, warehouse assignment / pick package creation in progress. */
    PRE_FULFILLMENT,
    /** Pre-fulfillment with an error: ITEM_ERROR_LISTED — manual ops review required. */
    PRE_FULFILLMENT_ERROR,
    /** Stock unavailable for fulfillment (OUT_OF_STOCK). */
    STOCK_BLOCKED,
    /** Active fulfillment: stock reserved or item picked, on its way through the pipeline. */
    IN_FULFILLMENT,
    /** Past picking: item packed, GIN issued. */
    POST_PICK,
    /** CPO (consolidated picking order) flow — waiting / created / approved / rejected. */
    CPO_FLOW,
    /** Move-to-area sub-flow (ORDER_MTA). */
    MTA,
    /** Terminal state: cancelled, rejected, or out-of-stock-and-cancelled. */
    TERMINATED,
    /** Unmapped ordinal — surfaces enum extensions for review. */
    OTHER;

    /**
     * Resolve stage from the {@code sales_order.last_status} integer ordinal.
     */
    public static SalesOrderLifecycleStage forOrdinal(int ordinal) {
        SalesOrderLifecycleStage stage = ORDINAL_TO_STAGE.get(ordinal);
        return stage == null ? OTHER : stage;
    }

    /** Convenience: also resolves via the SOStatus name when caller has the label. */
    public static SalesOrderLifecycleStage forLabel(String label) {
        if (label == null) {
            return OTHER;
        }
        SalesOrderLifecycleStage stage = LABEL_TO_STAGE.get(label);
        return stage == null ? OTHER : stage;
    }

    /** Ordinal → stage. 18 known ordinals from SOStatus.java. */
    private static final Map<Integer, SalesOrderLifecycleStage> ORDINAL_TO_STAGE = Map.ofEntries(
            Map.entry(0,  PRE_FULFILLMENT),         // CREATED
            Map.entry(1,  PRE_FULFILLMENT_ERROR),   // ITEM_ERROR_LISTED
            Map.entry(2,  PRE_FULFILLMENT),         // WAREHOUSE_UNKNOWN
            Map.entry(3,  PRE_FULFILLMENT),         // WAREHOUSE_ASSIGNED
            Map.entry(4,  IN_FULFILLMENT),          // STOCK_RESERVED
            Map.entry(5,  STOCK_BLOCKED),           // OUT_OF_STOCK
            Map.entry(6,  IN_FULFILLMENT),          // ITEM_PICKED
            Map.entry(7,  TERMINATED),              // CANCELLED
            Map.entry(8,  POST_PICK),               // ITEM_PACKED
            Map.entry(9,  POST_PICK),               // ITEM_ISSUED
            Map.entry(10, CPO_FLOW),                // WAITING_CPO
            Map.entry(11, CPO_FLOW),                // CPO_CREATED
            Map.entry(12, CPO_FLOW),                // CPO_APPROVED
            Map.entry(13, CPO_FLOW),                // CPO_REJECTED
            Map.entry(14, PRE_FULFILLMENT),         // PICK_PACKAGE_CREATED
            Map.entry(15, MTA),                     // ORDER_MTA
            Map.entry(16, TERMINATED),              // REJECTED
            Map.entry(17, TERMINATED));             // OUT_OF_STOCK_CANCEL

    private static final Map<String, SalesOrderLifecycleStage> LABEL_TO_STAGE = Map.ofEntries(
            Map.entry("CREATED",                PRE_FULFILLMENT),
            Map.entry("ITEM_ERROR_LISTED",      PRE_FULFILLMENT_ERROR),
            Map.entry("WAREHOUSE_UNKNOWN",      PRE_FULFILLMENT),
            Map.entry("WAREHOUSE_ASSIGNED",     PRE_FULFILLMENT),
            Map.entry("STOCK_RESERVED",         IN_FULFILLMENT),
            Map.entry("OUT_OF_STOCK",           STOCK_BLOCKED),
            Map.entry("ITEM_PICKED",            IN_FULFILLMENT),
            Map.entry("CANCELLED",              TERMINATED),
            Map.entry("ITEM_PACKED",            POST_PICK),
            Map.entry("ITEM_ISSUED",            POST_PICK),
            Map.entry("WAITING_CPO",            CPO_FLOW),
            Map.entry("CPO_CREATED",            CPO_FLOW),
            Map.entry("CPO_APPROVED",           CPO_FLOW),
            Map.entry("CPO_REJECTED",           CPO_FLOW),
            Map.entry("PICK_PACKAGE_CREATED",   PRE_FULFILLMENT),
            Map.entry("ORDER_MTA",              MTA),
            Map.entry("REJECTED",               TERMINATED),
            Map.entry("OUT_OF_STOCK_CANCEL",    TERMINATED));

    /** Ordinal → label, exposed so the tool layer can map the int to a friendly name. */
    public static String labelForOrdinal(int ordinal) {
        return ORDINAL_TO_LABEL.getOrDefault(ordinal, "STATUS_" + ordinal);
    }

    private static final Map<Integer, String> ORDINAL_TO_LABEL = Map.ofEntries(
            Map.entry(0, "CREATED"),
            Map.entry(1, "ITEM_ERROR_LISTED"),
            Map.entry(2, "WAREHOUSE_UNKNOWN"),
            Map.entry(3, "WAREHOUSE_ASSIGNED"),
            Map.entry(4, "STOCK_RESERVED"),
            Map.entry(5, "OUT_OF_STOCK"),
            Map.entry(6, "ITEM_PICKED"),
            Map.entry(7, "CANCELLED"),
            Map.entry(8, "ITEM_PACKED"),
            Map.entry(9, "ITEM_ISSUED"),
            Map.entry(10, "WAITING_CPO"),
            Map.entry(11, "CPO_CREATED"),
            Map.entry(12, "CPO_APPROVED"),
            Map.entry(13, "CPO_REJECTED"),
            Map.entry(14, "PICK_PACKAGE_CREATED"),
            Map.entry(15, "ORDER_MTA"),
            Map.entry(16, "REJECTED"),
            Map.entry(17, "OUT_OF_STOCK_CANCEL"));
}
