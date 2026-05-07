package com.gdn.opsvision.mcp.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Evidence pack for {@code getStockHistoryForItem} — windowed view of {@code stock_history}
 * for one ({@code skuCode}, {@code siteCode}) pair, with one entry per matching
 * {@code warehouse_item_master} (typically 1 UNRESTRICTED + optionally 1 RESTRICTED).
 *
 * <p>Each WIM entry returns:
 * <ul>
 *   <li>{@code groupedByAction} — {@code (process_type, stock_action_type)} histogram with
 *       row counts and total {@code transaction_quantity} per group. Surfaces the *kinds* of
 *       activity that happened in the window without the agent having to re-aggregate.</li>
 *   <li>{@code recentDecrements} — top-N most recent {@code DECREASE_*} rows, with full
 *       reference fields ({@code referenceId}, {@code referenceType}, {@code stockTraceId})
 *       so the agent can chain into {@code getStockTrace}.</li>
 * </ul>
 *
 * <p>Returns FACTS, not VERDICTS. Per-WIM evidence is kept separate because UNRESTRICTED
 * (sellable) vs. RESTRICTED (blocked / quarantined) stock has different semantics; the
 * agent decides whether to merge them.
 *
 * <p>If the SKU isn't onboarded at the site, {@code warehouseItems} is empty.
 */
public record StockHistoryEvidence(
        String skuCode,
        String siteCode,
        LocalDateTime windowStart,
        LocalDateTime windowEnd,
        List<WimEvidence> warehouseItems) {

    public record WimEvidence(
            long warehouseItemMasterId,
            String stockIndicator,
            String stockType,
            Long supplierId,
            String supplierCode,
            String supplierName,
            long totalEvents,
            boolean truncated,
            List<ActionGroup> groupedByAction,
            List<DecrementEvent> recentDecrements,
            StockTraceEvidence.OutboundLifecycleProgression outboundLifecycle) {
    }

    public record ActionGroup(
            String processType,
            String stockActionType,
            OutboundStockLifecycleStage lifecycleStage,
            long eventCount,
            Long totalTransactionQuantity) {
    }

    public record DecrementEvent(
            Instant createdDate,
            String processType,
            String stockActionType,
            OutboundStockLifecycleStage lifecycleStage,
            Integer transactionQuantity,
            Integer oldQuantity,
            Integer newQuantity,
            String binCode,
            String referenceId,
            String referenceType,
            String stockTraceId) {
    }
}
