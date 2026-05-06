package com.gdn.opsvision.mcp.dto;

import java.time.Instant;
import java.util.List;

/**
 * Evidence pack for {@code getSalesOrder} — a single {@code sales_order} row keyed by
 * {@code order_item_id}, with denormalized parent {@code pick_package} context and a list
 * of {@code picking_item} demand rows.
 *
 * <p>Returns FACTS, not VERDICTS. {@code lastStatus.code} 5 = {@code OUT_OF_STOCK} and
 * 17 = {@code OUT_OF_STOCK_CANCEL}; other codes are passed through with a raw-int label.
 * The agent decides what the combination means.
 *
 * <p>{@code lastProcessDate} maps to {@code sales_order.last_process_date} — the
 * Hibernate {@code @UpdateTimestamp} on the SO. Use it as the window anchor for
 * downstream {@code getStockHistoryForItem} calls.
 *
 * <p>Not-found ({@code found:false}, every other field null) and an empty
 * {@code pickingItems} list are real states, not errors.
 */
public record SalesOrderEvidence(
        String orderItemId,
        boolean found,
        Long soId,
        String soNumber,
        LastStatus lastStatus,
        Boolean pickingStuck,
        String orderStuckReason,
        Instant lastProcessDate,
        String siteCode,
        ParentPickPackage parentPickPackage,
        List<PickingItem> pickingItems) {

    /**
     * Sales-order status with both the raw integer code, the resolved enum label, and a
     * coarse {@link SalesOrderLifecycleStage} for triage. Mapping table covers all 18
     * documented {@code SOStatus} ordinals.
     */
    public record LastStatus(int code, String label, SalesOrderLifecycleStage lifecycleStage) {
    }

    public record ParentPickPackage(Long id, String code) {
    }

    public record PickingItem(
            long pickingItemId,
            String skuCode,
            String status,
            Integer quantity,
            Integer currentPickedQuantity,
            String stockTraceId) {
    }
}
