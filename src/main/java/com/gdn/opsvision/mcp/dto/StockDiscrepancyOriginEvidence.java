package com.gdn.opsvision.mcp.dto;

import java.time.Instant;
import java.util.List;

/**
 * Evidence pack for {@code findStockDiscrepancyOrigin} — forensic per-SKU breakdown of
 * which stock_history events caused a {@code warehouse_physical_*_stock} aggregate to
 * drift from the sum of {@code warehouse_bin_physical_*_stock}.
 *
 * <p>Two layered checks per WIM (one per stock_indicator the SKU has at the site):
 * <ol>
 *   <li><b>Per-trace symmetry</b> — for each {@code stock_trace_id}, sum bin-level vs
 *       WIM-level ORIGINAL deltas. Traces where they disagree are surfaced with their
 *       full event list (capped) and best-effort upstream resolution.</li>
 *   <li><b>Aggregate-vs-bins</b> — current snapshot of the rollup vs the per-bin sum.
 *       Sanity-checks Layer 1: the cumulative divergence should match the sum of
 *       per-trace divergences (modulo NULL-trace events, which this codebase doesn't
 *       have at present).</li>
 * </ol>
 *
 * <p>Returns FACTS, not VERDICTS. The tool diagnoses SOURCE of an existing discrepancy
 * but does NOT reconcile, fix, or judge whether the originating action was authorised.
 */
public record StockDiscrepancyOriginEvidence(
        String skuCode,
        String siteCode,
        boolean found,
        List<WimDiscrepancy> warehouseItemMasters) {

    /** One result per matching WIM (typically 1 UNRESTRICTED + optionally 1 RESTRICTED). */
    public record WimDiscrepancy(
            long wimId,
            String stockIndicator,
            String physicalWarehouseCode,
            Long supplierId,
            String supplierCode,
            AggregateVsBinsCheck aggregateVsBins,
            int divergentTraceCount,
            boolean divergentTracesTruncated,
            int eventsPerTraceCap,
            List<DivergentTrace> divergentTraces) {
    }

    /** Layer 2 — current rollup vs sum-of-bins for ORIGINAL and RESERVED stock. */
    public record AggregateVsBinsCheck(
            Integer aggregateOriginalQty,
            Integer binSumOriginalQty,
            Integer originalDivergence,
            Integer aggregateReservedQty,
            Integer binSumReservedQty,
            Integer reservedDivergence,
            int binCount) {
    }

    /**
     * Layer 1 — one stock_trace_id where the bin-net delta doesn't equal the WIM-net
     * delta for ORIGINAL stock. The events list captures the full audit trail for the
     * trace (capped at {@code eventsPerTraceCap}); {@code resolvedUpstream} attempts
     * cross-DB lookup based on the first event's reference_type.
     */
    public record DivergentTrace(
            String stockTraceId,
            int binNetOriginalDelta,
            int wimNetOriginalDelta,
            int originalDivergence,
            int eventCount,
            Instant firstEventTime,
            Instant lastEventTime,
            boolean eventsTruncated,
            List<TraceEvent> events,
            ResolvedUpstream resolvedUpstream) {
    }

    public record TraceEvent(
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

    /**
     * Best-effort cross-DB resolution of the FIRST event's
     * ({@code referenceType}, {@code referenceId}) for a divergent trace. Each field
     * is populated only when the lookup succeeded; the rest stay {@code null}.
     * {@code resolutionNote} explains why nothing was resolved (e.g. unknown
     * reference_type, lookup returned no row).
     */
    public record ResolvedUpstream(
            String referenceType,
            String referenceId,
            Long pickingItemId,
            Long salesOrderId,
            String salesOrderNumber,
            String orderItemId,
            Long pickPackageId,
            String pickPackageCode,
            String adjustmentReasonId,
            String resolutionNote) {
    }
}
