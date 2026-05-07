package com.gdn.opsvision.mcp.tool;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import com.gdn.opsvision.mcp.dto.SalesOrderLifecycleStage;
import com.gdn.opsvision.mcp.dto.SalesOrderStatusAggregateEvidence;
import com.gdn.opsvision.mcp.dto.SalesOrderStatusAggregateEvidence.StageBucket;
import com.gdn.opsvision.mcp.dto.SalesOrderStatusAggregateEvidence.StatusBucket;
import com.gdn.opsvision.mcp.repository.SalesOrderRepository;
import com.gdn.opsvision.mcp.repository.SalesOrderRepository.StatusCountRow;
import com.gdn.opsvision.mcp.tool.util.IsoBound;

/**
 * Histogram of sales_order rows grouped by {@code last_status}, with both the raw
 * {@code SOStatus} ordinal AND the human-readable label so the agent never has to
 * translate codes itself. Useful for ops dashboards: "how many orders are OUT_OF_STOCK at
 * MAR right now?", "how many got ITEM_PICKED today?", "what's the breakdown of
 * still-active vs terminated SOs in the last hour?".
 */
@Service
public class AggregateSalesOrdersByStatusTool {

    private final SalesOrderRepository repo;

    public AggregateSalesOrdersByStatusTool(SalesOrderRepository repo) {
        this.repo = repo;
    }

    @Tool(description = """
            Aggregate sales_order rows by current last_status. Returns a histogram with \
            both the raw SOStatus ordinal and the human-readable label (e.g. \
            ITEM_PICKED, OUT_OF_STOCK, PICK_PACKAGE_CREATED), plus a coarse \
            roll-up by SalesOrderLifecycleStage (PRE_FULFILLMENT / IN_FULFILLMENT / \
            POST_PICK / TERMINATED / etc.). Buckets are sorted by count DESC.

            Use for ops triage: "how many orders are OUT_OF_STOCK at MAR right \
            now?", "how many were ITEM_PICKED today?", "what's the active vs \
            terminated breakdown over the last hour?".

            For an operator-dashboard view that splits SOs into active-snapshot \
            (currently-pending) vs terminal-in-window (shipped/cancelled in window) \
            sections, with up to N drill-down orderItemIds per bucket, use \
            findOrdersByLastProcessDate. This tool is the simpler raw-histogram \
            interface — same date filter applies to ALL buckets uniformly.

            All filters are optional and AND-combine:
              siteCode      - warehouse.code (e.g. 'MAR-0000000001'). Omit to \
                              count across all sites.
              sinceDate     - lower bound on sales_order.last_process_date. ISO \
                              date 'YYYY-MM-DD' (start of day) or datetime \
                              'YYYY-MM-DDTHH:MM:SS' (inclusive moment).
              untilDate     - upper bound on last_process_date. ISO date \
                              (exclusive next-day) or datetime (exclusive moment). \
                              For "today only", pass the same date for both.
              lastStatus    - filter to a single SOStatus ordinal (e.g. 5 for \
                              OUT_OF_STOCK, 9 for ITEM_ISSUED). Useful for "how \
                              many ITEM_PICKED today?" without pulling other \
                              buckets.

            Date semantics: last_process_date is the SO-level update timestamp \
            (Hibernate @UpdateTimestamp). A windowed query counts SOs that had \
            ANY status change inside the window — it does NOT distinguish \
            between SOs that ENTERED a status vs ones merely TOUCHED while \
            already in it. The SO state machine doesn't track per-transition \
            timestamps.

            Returns FACTS, not VERDICTS. The agent reads the buckets and decides \
            what the distribution means (healthy / drifted / spiking).
            """)
    public SalesOrderStatusAggregateEvidence aggregateSalesOrdersByStatus(
            @ToolParam(description = "Optional site / warehouse code (warehouse.code, e.g. 'MAR-0000000001'). Omit to count across all sites.", required = false) String siteCode,
            @ToolParam(description = "Optional ISO bound on sales_order.last_process_date (YYYY-MM-DD or YYYY-MM-DDTHH:MM:SS). Inclusive lower bound.", required = false) String sinceDate,
            @ToolParam(description = "Optional ISO upper bound (YYYY-MM-DD interpreted as exclusive next-day; datetime as exclusive moment). For 'today only', pass the same date as sinceDate.", required = false) String untilDate,
            @ToolParam(description = "Optional filter to a single SOStatus ordinal (e.g. 5 = OUT_OF_STOCK, 9 = ITEM_ISSUED). Omit to return all status buckets.", required = false) Integer lastStatus) {

        LocalDateTime since = IsoBound.parseSince(sinceDate);
        LocalDateTime until = IsoBound.parseUntil(untilDate);

        List<StatusCountRow> rows = repo.aggregateByStatus(siteCode, since, until, lastStatus);

        long total = 0L;
        List<StatusBucket> byLastStatus = new ArrayList<>(rows.size());
        Map<String, Long> stageTotals = new LinkedHashMap<>();
        for (StatusCountRow r : rows) {
            total += r.soCount();
            String label = SalesOrderLifecycleStage.labelForOrdinal(r.lastStatus());
            SalesOrderLifecycleStage stage = SalesOrderLifecycleStage.forOrdinal(r.lastStatus());
            byLastStatus.add(new StatusBucket(r.lastStatus(), label, stage.name(), r.soCount()));
            stageTotals.merge(stage.name(), r.soCount(), Long::sum);
        }

        List<StageBucket> byLifecycleStage = stageTotals.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(e -> new StageBucket(e.getKey(), e.getValue()))
                .toList();

        return new SalesOrderStatusAggregateEvidence(
                siteCode,
                sinceDate,
                untilDate,
                lastStatus,
                total,
                byLastStatus,
                byLifecycleStage);
    }
}
