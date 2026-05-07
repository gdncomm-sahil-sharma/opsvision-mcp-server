package com.gdn.opsvision.mcp.tool;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import com.gdn.opsvision.mcp.dto.StockDiscrepancyOriginEvidence;
import com.gdn.opsvision.mcp.dto.StockDiscrepancyOriginEvidence.AggregateVsBinsCheck;
import com.gdn.opsvision.mcp.dto.StockDiscrepancyOriginEvidence.DivergentTrace;
import com.gdn.opsvision.mcp.dto.StockDiscrepancyOriginEvidence.ResolvedUpstream;
import com.gdn.opsvision.mcp.dto.StockDiscrepancyOriginEvidence.TraceEvent;
import com.gdn.opsvision.mcp.dto.StockDiscrepancyOriginEvidence.WimDiscrepancy;
import com.gdn.opsvision.mcp.repository.DiscrepancyUpstreamRepository;
import com.gdn.opsvision.mcp.repository.DiscrepancyUpstreamRepository.UpstreamRow;
import com.gdn.opsvision.mcp.repository.InventoryRepository;
import com.gdn.opsvision.mcp.repository.InventoryRepository.WimRef;
import com.gdn.opsvision.mcp.repository.StockHistoryRepository;
import com.gdn.opsvision.mcp.repository.StockHistoryRepository.AggregateVsBinsRow;
import com.gdn.opsvision.mcp.repository.StockHistoryRepository.DivergentTraceRow;
import com.gdn.opsvision.mcp.repository.StockHistoryRepository.TraceEventRow;
import com.gdn.opsvision.mcp.repository.WarehouseDefectMapRepository;
import com.gdn.opsvision.mcp.tool.util.IsoBound;

/**
 * Forensic per-SKU discrepancy diagnostic. For (skuCode, siteCode), surfaces every
 * {@code stock_trace_id} where the bin-level net delta on ORIGINAL stock doesn't
 * equal the WIM-level net delta — the per-trace symmetry check the agent uses to
 * pinpoint the offending event(s) and resolve them upstream to a picking_item /
 * sales_order / pick_package / adjustment-reason record.
 */
@Service
public class FindStockDiscrepancyOriginTool {

    private static final int DEFAULT_TRACE_LIMIT = 20;
    private static final int MAX_TRACE_LIMIT = 100;
    private static final int EVENTS_PER_TRACE = 10;

    private final InventoryRepository inventoryRepo;
    private final StockHistoryRepository stockHistoryRepo;
    private final DiscrepancyUpstreamRepository upstreamRepo;
    private final WarehouseDefectMapRepository defectMapRepo;

    public FindStockDiscrepancyOriginTool(
            InventoryRepository inventoryRepo,
            StockHistoryRepository stockHistoryRepo,
            DiscrepancyUpstreamRepository upstreamRepo,
            WarehouseDefectMapRepository defectMapRepo) {
        this.inventoryRepo = inventoryRepo;
        this.stockHistoryRepo = stockHistoryRepo;
        this.upstreamRepo = upstreamRepo;
        this.defectMapRepo = defectMapRepo;
    }

    @Tool(description = """
            Forensic discrepancy diagnostic: for a (skuCode, siteCode), find every \
            stock_trace_id where the bin-level net delta doesn't equal the WIM-level \
            net delta on ORIGINAL stock — the events that caused the \
            warehouse_physical_original_stock aggregate to drift from the sum of \
            warehouse_bin_physical_original_stock across bins.

            Two layered checks per WIM (returned for every stock_indicator the SKU has \
            at the site, typically 1 UNRESTRICTED + optionally 1 RESTRICTED):

            1. Per-trace symmetry (Layer 1) — for each stock_trace_id of the WIM, \
               compare bin-level vs WIM-level ORIGINAL net deltas. Each divergent trace \
               returns its full event list (capped at 10) plus best-effort upstream \
               resolution: PICKING_ITEM / PICK_LIST_DETAILS / PICK_PACKAGE refs are \
               resolved to (sales_order_id, sales_order_number, order_item_id, pp.code); \
               STOCK_ADJUST_REASON refs surface the UUID for an out-of-band lookup.

            2. Aggregate-vs-bins (Layer 2) — current snapshot of \
               warehouse_physical_*_stock vs sum of warehouse_bin_physical_*_stock for \
               the WIM. Sanity-checks Layer 1: the cumulative divergence should match \
               the sum of per-trace divergences.

            Returns FACTS, not VERDICTS. The tool diagnoses the SOURCE of an existing \
            discrepancy but does NOT reconcile, fix, or judge whether the originating \
            action was authorised.

            Limit defaults to 20 divergent traces (max 100); divergentTracesTruncated=\
            true on the response when the cap is hit. Per-trace event list is capped \
            at 10; eventsTruncated=true per trace when more events exist. sinceDate \
            (optional ISO date / datetime) bounds stock_history.created_date — useful \
            when the SKU has years of history and you only care about recent leaks.
            """)
    public StockDiscrepancyOriginEvidence findStockDiscrepancyOrigin(
            @ToolParam(description = "SKU code (item.code)") String skuCode,
            @ToolParam(description = "Site / warehouse code (e.g. 'MAR-0000000001')") String siteCode,
            @ToolParam(description = "Optional ISO bound on stock_history.created_date (YYYY-MM-DD or YYYY-MM-DDTHH:MM:SS)", required = false) String sinceDate,
            @ToolParam(description = "Max divergent traces to return per WIM (default 20, capped at 100)", required = false) Integer limit,
            @ToolParam(description = "Optional supplier.code to narrow CONSIGNMENT_TRADING SKUs to a single supplier's WIM. No-op for TRADING.", required = false) String supplierCode) {

        int effectiveLimit = clampLimit(limit);
        LocalDateTime since = IsoBound.parseSince(sinceDate);
        String defectCode = defectMapRepo.defectCodeFor(siteCode).orElse(null);

        List<WimRef> wims = inventoryRepo.findWimsBySkuAndSite(skuCode, siteCode, supplierCode);
        if (wims.isEmpty()) {
            return new StockDiscrepancyOriginEvidence(skuCode, siteCode, false, List.of());
        }

        List<WimDiscrepancy> results = new ArrayList<>(wims.size());
        for (WimRef w : wims) {
            results.add(diagnoseWim(w, siteCode, defectCode, since, effectiveLimit));
        }
        return new StockDiscrepancyOriginEvidence(skuCode, siteCode, true, results);
    }

    private WimDiscrepancy diagnoseWim(WimRef w, String siteCode, String defectCode,
            LocalDateTime since, int limit) {

        // Layer 2 — aggregate-vs-bins snapshot.
        AggregateVsBinsRow agg = stockHistoryRepo.findAggregateVsBinsForWim(w.wimId());
        AggregateVsBinsCheck aggCheck = new AggregateVsBinsCheck(
                agg.aggregateOriginalQty(), agg.binSumOriginalQty(), agg.originalDivergence(),
                agg.aggregateReservedQty(), agg.binSumReservedQty(), agg.reservedDivergence(),
                agg.binCount());

        // Layer 1 — per-trace symmetry. limit+1 sentinel for truncation detection.
        List<DivergentTraceRow> traceRows =
                stockHistoryRepo.findDivergentTracesForWim(w.wimId(), since, limit + 1);
        boolean truncated = traceRows.size() > limit;
        if (truncated) {
            traceRows = traceRows.subList(0, limit);
        }

        List<DivergentTrace> traces = new ArrayList<>(traceRows.size());
        for (DivergentTraceRow r : traceRows) {
            traces.add(buildDivergentTrace(r, w.wimId()));
        }

        String physicalWarehouseCode = "RESTRICTED".equals(w.stockIndicator())
                ? (defectCode != null ? defectCode : siteCode)
                : siteCode;

        return new WimDiscrepancy(
                w.wimId(), w.stockIndicator(), physicalWarehouseCode,
                w.stockType(), w.supplierId(), w.supplierCode(), w.supplierName(),
                aggCheck, traceRows.size(), truncated, EVENTS_PER_TRACE, traces);
    }

    private DivergentTrace buildDivergentTrace(DivergentTraceRow r, long wimId) {
        // Pull capped event list for this trace.
        List<TraceEventRow> rows = stockHistoryRepo.findEventsForTrace(
                r.stockTraceId(), wimId, EVENTS_PER_TRACE + 1);
        boolean truncated = rows.size() > EVENTS_PER_TRACE;
        if (truncated) {
            rows = rows.subList(0, EVENTS_PER_TRACE);
        }
        List<TraceEvent> events = new ArrayList<>(rows.size());
        for (TraceEventRow e : rows) {
            events.add(new TraceEvent(
                    e.createdDate(), e.stockActionType(), e.processType(),
                    e.transactionQuantity(), e.oldQuantity(), e.newQuantity(),
                    e.binCode(), e.referenceType(), e.referenceId()));
        }
        // Resolve upstream from the FIRST event's reference (most informative).
        ResolvedUpstream upstream = events.isEmpty()
                ? new ResolvedUpstream(null, null, null, null, null, null, null, null, null,
                        "no events found for trace")
                : resolveUpstream(events.get(0));
        return new DivergentTrace(
                r.stockTraceId(),
                r.binNetOriginalDelta(), r.wimNetOriginalDelta(), r.originalDivergence(),
                r.eventCount(),
                r.firstEventTime(), r.lastEventTime(),
                truncated, events, upstream);
    }

    /**
     * Best-effort resolution of a divergent trace's first event by reference_type. Each
     * branch is fail-safe: if the lookup returns no row (legacy hard-deletion) or the
     * reference_id isn't a parseable long, we surface the raw refType+refId plus a
     * resolution note rather than failing the whole tool call.
     */
    private ResolvedUpstream resolveUpstream(TraceEvent e) {
        String refType = e.referenceType();
        String refId = e.referenceId();
        if (refType == null || refId == null || refId.isBlank()) {
            return new ResolvedUpstream(refType, refId, null, null, null, null, null, null, null,
                    "no reference on event");
        }
        switch (refType) {
            case "PICKING_ITEM" -> {
                Optional<Long> id = parseLong(refId);
                if (id.isEmpty()) {
                    return surfaceRawRef(refType, refId, "non-numeric reference_id");
                }
                return upstreamRepo.findByPickingItem(id.get())
                        .map(u -> toResolved(refType, refId, u, null))
                        .orElseGet(() -> surfaceRawRef(refType, refId,
                                "picking_item id not found in stockholm"));
            }
            case "PICK_LIST_DETAILS" -> {
                Optional<Long> id = parseLong(refId);
                if (id.isEmpty()) {
                    return surfaceRawRef(refType, refId, "non-numeric reference_id");
                }
                return upstreamRepo.findByPickListDetails(id.get())
                        .map(u -> toResolved(refType, refId, u, null))
                        .orElseGet(() -> surfaceRawRef(refType, refId,
                                "pick_list_details id not found in stockholm"));
            }
            case "PICK_PACKAGE" -> {
                Optional<Long> id = parseLong(refId);
                if (id.isEmpty()) {
                    return surfaceRawRef(refType, refId, "non-numeric reference_id");
                }
                return upstreamRepo.findByPickPackage(id.get())
                        .map(u -> toResolved(refType, refId, u, null))
                        .orElseGet(() -> surfaceRawRef(refType, refId,
                                "pick_package id not found in stockholm"));
            }
            case "STOCK_ADJUST_REASON" -> {
                // No DB lookup — adjustment_reason table not exposed to opsvision today.
                // Surface the UUID for out-of-band investigation.
                return new ResolvedUpstream(refType, refId,
                        null, null, null, null, null, null,
                        /*adjustmentReasonId*/ refId,
                        "operator-initiated stock adjustment; UUID is the stock_adjust_reason record");
            }
            default -> {
                return surfaceRawRef(refType, refId,
                        "reference_type not currently resolved by this tool ("
                                + refType + "); inspect raw refId out of band");
            }
        }
    }

    private static ResolvedUpstream toResolved(String refType, String refId, UpstreamRow u,
            String note) {
        return new ResolvedUpstream(
                refType, refId,
                u.pickingItemId(), u.salesOrderId(), u.salesOrderNumber(), u.orderItemId(),
                u.pickPackageId(), u.pickPackageCode(),
                /*adjustmentReasonId*/ null,
                note);
    }

    private static ResolvedUpstream surfaceRawRef(String refType, String refId, String note) {
        return new ResolvedUpstream(refType, refId,
                null, null, null, null, null, null, null, note);
    }

    private static Optional<Long> parseLong(String s) {
        try {
            return Optional.of(Long.parseLong(s.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_TRACE_LIMIT;
        }
        return Math.min(limit, MAX_TRACE_LIMIT);
    }
}
