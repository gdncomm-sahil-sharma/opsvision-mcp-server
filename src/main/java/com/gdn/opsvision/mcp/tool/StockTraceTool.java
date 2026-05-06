package com.gdn.opsvision.mcp.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import com.gdn.opsvision.mcp.dto.OutboundStockLifecycleStage;
import com.gdn.opsvision.mcp.dto.StockTraceEvidence;
import com.gdn.opsvision.mcp.dto.StockTraceEvidence.Mutation;
import com.gdn.opsvision.mcp.dto.StockTraceEvidence.OutboundLifecycleProgression;
import com.gdn.opsvision.mcp.repository.StockHistoryRepository;
import com.gdn.opsvision.mcp.repository.StockHistoryRepository.MutationRow;

@Service
public class StockTraceTool {

    private final StockHistoryRepository repo;

    public StockTraceTool(StockHistoryRepository repo) {
        this.repo = repo;
    }

    @Tool(description = """
            Fetch every stock_history row in warehouse-inventory that shares a single \
            stock_trace_id (UUID). The trace stitches a logical stock movement together end \
            to end: PP creation reservation → bin-level reservation → pick-list-details \
            decrement → GIN decrement → adjustments. Each event row carries warehouse_item_master, \
            bin_code, reference_id + reference_type (PICK_PACKAGE, PICKING_TASK_REQUEST_DETAIL, \
            PICK_LIST_DETAILS, GIN_NO, etc.), stock_action_type (one of 14 enum values like \
            DECREASE_BIN_RESERVED_STOCK), and the old/new/transaction quantities.

            Use this whenever you need the audit chain behind a stock movement — e.g. after \
            reconcileInventoryVsReservation flags an aggregate-vs-bin divergence and you want \
            to walk the events that caused it; or to confirm a GIN partial submit fully \
            decremented reserved stock; or to check that a PP creation correctly reserved \
            against the right WIM. The same UUID is also stored on \
            stockholm.picking_item.stock_trace_id and stockholm.pick_list_details.stock_trace_id, \
            so you can bridge from picking-side primitives back into stock history.

            ID format: UUID string (e.g. '74a1dbb6-a2e3-406b-b514-f15f4a4e8419').

            Returns FACTS, not VERDICTS. The mutations list is ordered by created_date, then id. \
            byActionType / byReferenceType are convenience rollups so you don't have to \
            re-aggregate. Some traces have hundreds of events (largest in the QA2 restore is 442); \
            the result is capped at 200 events and {truncated: true} signals there's more — if \
            that's the case, the truncated head is still the most useful chronological prefix. \
            If the trace doesn't exist, returns an evidence pack with eventCount=0 and an empty \
            mutations list.
            """)
    public StockTraceEvidence getStockTrace(
            @ToolParam(description = "stock_trace_id UUID") String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return new StockTraceEvidence(traceId, 0, false, Map.of(), Map.of(),
                    OutboundStockLifecycleStage.computeProgression(List.of()), List.of());
        }
        List<MutationRow> rawRows = repo.findByTrace(traceId.trim());
        boolean truncated = rawRows.size() > repo.hardCap();
        if (truncated) {
            rawRows = rawRows.subList(0, repo.hardCap());
        }
        Map<String, Long> byAction = new LinkedHashMap<>();
        Map<String, Long> byRefType = new LinkedHashMap<>();
        List<String> actionTypes = new ArrayList<>(rawRows.size());
        List<Mutation> mutations = new ArrayList<>(rawRows.size());
        for (MutationRow m : rawRows) {
            if (m.stockActionType() != null) {
                byAction.merge(m.stockActionType(), 1L, Long::sum);
                actionTypes.add(m.stockActionType());
            }
            if (m.referenceType() != null) {
                byRefType.merge(m.referenceType(), 1L, Long::sum);
            }
            OutboundStockLifecycleStage stage = OutboundStockLifecycleStage.forActionType(
                    m.stockActionType());
            mutations.add(new Mutation(
                    m.id(), m.createdDate(), m.createdBy(),
                    m.warehouseItemMaster(), m.binCode(),
                    m.externalReferenceId(), m.parentReferenceId(), m.parentReferenceType(),
                    m.referenceId(), m.referenceType(),
                    m.processType(), m.stockActionType(), stage,
                    m.transactionQuantity(), m.oldQuantity(), m.newQuantity()));
        }
        OutboundLifecycleProgression progression =
                OutboundStockLifecycleStage.computeProgression(actionTypes);
        return new StockTraceEvidence(
                traceId.trim(), mutations.size(), truncated, byAction, byRefType,
                progression, mutations);
    }
}
