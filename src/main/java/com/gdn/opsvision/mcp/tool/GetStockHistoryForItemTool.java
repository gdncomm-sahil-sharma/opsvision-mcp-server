package com.gdn.opsvision.mcp.tool;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import com.gdn.opsvision.mcp.dto.OutboundStockLifecycleStage;
import com.gdn.opsvision.mcp.dto.StockHistoryEvidence;
import com.gdn.opsvision.mcp.dto.StockHistoryEvidence.ActionGroup;
import com.gdn.opsvision.mcp.dto.StockHistoryEvidence.DecrementEvent;
import com.gdn.opsvision.mcp.dto.StockHistoryEvidence.WimEvidence;
import com.gdn.opsvision.mcp.dto.StockTraceEvidence.OutboundLifecycleProgression;
import com.gdn.opsvision.mcp.repository.InventoryRepository;
import com.gdn.opsvision.mcp.repository.InventoryRepository.WimRef;
import com.gdn.opsvision.mcp.repository.StockHistoryRepository;
import com.gdn.opsvision.mcp.repository.StockHistoryRepository.ActionGroupRow;
import com.gdn.opsvision.mcp.repository.StockHistoryRepository.DecrementEventRow;
import com.gdn.opsvision.mcp.tool.util.IsoBound;

@Service
public class GetStockHistoryForItemTool {

    private static final int DEFAULT_DECREMENT_LIMIT = 5;
    private static final int MAX_DECREMENT_LIMIT = 50;

    private final InventoryRepository inventoryRepo;
    private final StockHistoryRepository stockHistoryRepo;

    public GetStockHistoryForItemTool(
            InventoryRepository inventoryRepo,
            StockHistoryRepository stockHistoryRepo) {
        this.inventoryRepo = inventoryRepo;
        this.stockHistoryRepo = stockHistoryRepo;
    }

    @Tool(description = """
            Windowed view of stock_history for one (skuCode, siteCode) pair. Returns one \
            entry per matching warehouse_item_master (typically 1 UNRESTRICTED + optionally \
            1 RESTRICTED), each containing:
              - groupedByAction: histogram over (process_type, stock_action_type) with row \
                counts and total transaction_quantity per group. Tells the agent what kinds \
                of activity happened in the window.
              - recentDecrements: top-N most recent DECREASE_* rows with full reference \
                fields (referenceId, referenceType, stockTraceId) — each row's stockTraceId \
                chains directly into getStockTrace.

            Use this as the windowed lens on stock dynamics for a SKU. Pairs naturally with \
            getSalesOrder (anchor `since` / `until` on the SO's lastModifiedDate) but also \
            stands alone for replenishment audits, adjustment hunts, cycle-count diffs.

            ID format: skuCode = item.code (e.g. 'MTA-50783154-00001'); siteCode = \
            warehouse.code (e.g. 'MAR-0000000001').

            Date / time inputs (since, until, both REQUIRED) accept either an ISO date \
            ('2026-05-04') or a full ISO datetime ('2026-05-04T03:00:00'). Date-only: \
            `since` is start of that day (00:00); `until` is interpreted inclusive of the \
            named day (rows with created_date < midnight of the next day) — pass the same \
            date for both to scope to a single calendar day. Datetime: `since` is inclusive \
            (>=); `until` is exclusive (<). Trailing 'Z' is tolerated but timestamps are \
            interpreted in the database's local zone (stock_history.created_date is \
            timestamp without time zone). The tool deliberately has no `now()` default — \
            callers must pass an explicit window so the same call works against the QA2 \
            snapshot and against prod.

            decrementLimit: per-WIM cap on recentDecrements rows (default 5, max 50). \
            truncated=true on a WimEvidence means more DECREASE_* rows existed in the \
            window than the limit returned.

            Returns FACTS, not VERDICTS. UNRESTRICTED (sellable) and RESTRICTED (blocked / \
            quarantined) are surfaced as separate WimEvidence entries; the agent decides \
            whether to merge. If the SKU isn't onboarded at the site, warehouseItems is \
            empty.
            """)
    public StockHistoryEvidence getStockHistoryForItem(
            @ToolParam(description = "SKU code (item.code)") String skuCode,
            @ToolParam(description = "Site / warehouse code (warehouse.code, e.g. 'MAR-0000000001')") String siteCode,
            @ToolParam(description = "Lower bound: ISO date 'YYYY-MM-DD' (inclusive day) or datetime 'YYYY-MM-DDTHH:MM:SS' (inclusive moment). Required.") String since,
            @ToolParam(description = "Upper bound: ISO date 'YYYY-MM-DD' (inclusive day) or datetime (exclusive moment). Required.") String until,
            @ToolParam(description = "Per-WIM cap on recentDecrements rows (default 5, max 50)", required = false) Integer decrementLimit) {

        LocalDateTime windowStart = IsoBound.parseSince(since);
        LocalDateTime windowEnd = IsoBound.parseUntil(until);
        if (windowStart == null || windowEnd == null) {
            // Required inputs — return an empty evidence pack with the inputs echoed back.
            return new StockHistoryEvidence(skuCode, siteCode, windowStart, windowEnd, List.of());
        }

        int effectiveLimit = clampLimit(decrementLimit);

        List<WimRef> wims = inventoryRepo.findWimsBySkuAndSite(skuCode, siteCode);
        List<WimEvidence> out = new ArrayList<>(wims.size());
        for (WimRef w : wims) {
            List<ActionGroupRow> rawGroups = stockHistoryRepo.findGroupedByActionForWim(
                    w.wimId(), windowStart, windowEnd);
            long totalEvents = stockHistoryRepo.countEventsForWim(
                    w.wimId(), windowStart, windowEnd);
            List<DecrementEventRow> rawDecrements = stockHistoryRepo.findRecentDecrementsForWim(
                    w.wimId(), windowStart, windowEnd, effectiveLimit + 1);
            boolean truncated = rawDecrements.size() > effectiveLimit;
            if (truncated) {
                rawDecrements = rawDecrements.subList(0, effectiveLimit);
            }
            // Attach lifecycle stage + build progression. ActionGroup carries the count
            // per (process_type, stock_action_type), so a stage's contribution to the
            // progression is the SUM of eventCounts for all groups that map to that stage.
            List<ActionGroup> grouped = new ArrayList<>(rawGroups.size());
            List<String> windowActionTypes = new ArrayList<>();
            for (ActionGroupRow g : rawGroups) {
                OutboundStockLifecycleStage stage =
                        OutboundStockLifecycleStage.forActionType(g.stockActionType());
                grouped.add(new ActionGroup(
                        g.processType(), g.stockActionType(), stage,
                        g.eventCount(), g.totalTransactionQuantity()));
                // Repeat the action type once per event so the progression's event-count
                // tallies match the histogram's eventCount sum.
                long n = g.eventCount();
                for (long i = 0; i < n; i++) {
                    windowActionTypes.add(g.stockActionType());
                }
            }
            List<DecrementEvent> recent = new ArrayList<>(rawDecrements.size());
            for (DecrementEventRow d : rawDecrements) {
                recent.add(new DecrementEvent(
                        d.createdDate(), d.processType(), d.stockActionType(),
                        OutboundStockLifecycleStage.forActionType(d.stockActionType()),
                        d.transactionQuantity(), d.oldQuantity(), d.newQuantity(),
                        d.binCode(), d.referenceId(), d.referenceType(), d.stockTraceId()));
            }
            OutboundLifecycleProgression progression =
                    OutboundStockLifecycleStage.computeProgression(windowActionTypes);
            out.add(new WimEvidence(
                    w.wimId(),
                    w.stockIndicator(),
                    totalEvents,
                    truncated,
                    grouped,
                    recent,
                    progression));
        }
        return new StockHistoryEvidence(skuCode, siteCode, windowStart, windowEnd, out);
    }

    private static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_DECREMENT_LIMIT;
        }
        return Math.min(limit, MAX_DECREMENT_LIMIT);
    }
}
