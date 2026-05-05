package com.gdn.opsvision.mcp.tool;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import com.gdn.opsvision.mcp.dto.PickingTaskSearchEvidence;
import com.gdn.opsvision.mcp.dto.PickingTaskSearchEvidence.TaskMatch;
import com.gdn.opsvision.mcp.repository.MovementSearchRepository;
import com.gdn.opsvision.mcp.repository.MovementSearchRepository.TaskRow;
import com.gdn.opsvision.mcp.repository.PickPackageRepository;
import com.gdn.opsvision.mcp.repository.StockHistoryRepository;

@Service
public class FindPickingTasksTool {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final MovementSearchRepository movementSearch;
    private final PickPackageRepository pickPackageRepo;
    private final StockHistoryRepository stockHistoryRepo;

    public FindPickingTasksTool(
            MovementSearchRepository movementSearch,
            PickPackageRepository pickPackageRepo,
            StockHistoryRepository stockHistoryRepo) {
        this.movementSearch = movementSearch;
        this.pickPackageRepo = pickPackageRepo;
        this.stockHistoryRepo = stockHistoryRepo;
    }

    @Tool(description = """
            Bulk search across warehouse-movement picking_task rows. All filters are AND-combined; \
            leaving a filter null/empty means "any value." Returns up to `limit` matching rows \
            (default 50, max 200) with truncated=true when the cap is hit. Each match is resolved \
            to its parent pick package code via a batch lookup against stockholm.

            Use this for blast-radius / cross-PP investigations after a single-PP investigation \
            has identified a fingerprint. Examples:
              - automation='WCS' + previousStatus='PENDING_CLOSED' + sinceDate/untilDate \
                → candidate WCS phantom-close population.
              - status='CLOSED' + lastModifiedBy='SYSTEM' + previousStatus='PENDING_CLOSED' \
                → SYSTEM-shortened closures regardless of automation.
              - skuCode='MTA-50783154-00001' → narrow population to a single SKU.
              - sourceAreaCode='M4-STOR' → narrow population to a single source aisle.

            Date / time inputs accept either an ISO date ('2026-05-04') or a full ISO datetime \
            ('2026-05-04T03:00:00'). When a date-only string is passed, sinceDate is the start of \
            that day (00:00) and untilDate is interpreted as inclusive of the named day (rows \
            with created_date < midnight of the next day) — pass the same date for both to scope \
            to a single calendar day. When a datetime is passed, the bound is exact: sinceDate \
            is inclusive (>=) and untilDate is exclusive (<). The ISO 'Z' suffix is tolerated \
            but timestamps are interpreted in the database's local zone, since picking_task.created_date \
            is timestamp without time zone. To chunk a busy day, pass datetimes like \
            '2026-05-04T00:00:00' to '2026-05-04T03:00:00'.

            phantomClose=true adds a cross-DB post-filter: only keeps tasks whose stock_trace_id \
            has zero DECREASE_* events in stock_history. That's the canonical "WCS phantom-close" \
            fingerprint — task closed without producing a real pick. Each surviving match carries \
            decrementEventCount=0 for transparency. The flag costs one extra batch query against \
            warehouse-inventory; cheap relative to the search itself.

            Returns FACTS, not VERDICTS. A non-empty result is a *candidate list*; spot-check 1-2 \
            entries with getMovementHistory + getStockTrace before drawing population-level \
            conclusions.
            """)
    public PickingTaskSearchEvidence findPickingTasks(
            @ToolParam(description = "Warehouse / site code (warehouse.code), e.g. 'MAR-0000000001'", required = false) String siteCode,
            @ToolParam(description = "Lower bound: ISO date 'YYYY-MM-DD' (inclusive day) or datetime 'YYYY-MM-DDTHH:MM:SS' (inclusive moment)", required = false) String sinceDate,
            @ToolParam(description = "Upper bound: ISO date 'YYYY-MM-DD' (inclusive day) or datetime (exclusive moment)", required = false) String untilDate,
            @ToolParam(description = "picking_task.status, e.g. 'CLOSED'", required = false) String status,
            @ToolParam(description = "picking_task.previous_status, e.g. 'PENDING_CLOSED'", required = false) String previousStatus,
            @ToolParam(description = "picking_task.automation; case-insensitive (e.g. 'WCS')", required = false) String automation,
            @ToolParam(description = "picking_task.last_modified_by, e.g. 'SYSTEM'", required = false) String lastModifiedBy,
            @ToolParam(description = "picking_task.type", required = false) String type,
            @ToolParam(description = "picking_task.sku_code (item.code), e.g. 'MTA-50783154-00001'", required = false) String skuCode,
            @ToolParam(description = "picking_task.source_area_code, e.g. 'M4-STOR' or 'M2-STOR'", required = false) String sourceAreaCode,
            @ToolParam(description = "Max rows (default 50, capped at 200)", required = false) Integer limit,
            @ToolParam(description = "Cross-DB filter: only keep tasks whose stock_trace_id has zero DECREASE_* events in stock_history", required = false) Boolean phantomClose) {

        int effectiveLimit = clampLimit(limit);
        boolean phantom = Boolean.TRUE.equals(phantomClose);

        List<TaskRow> rows = movementSearch.searchTasks(
                siteCode,
                parseSinceBound(sinceDate),
                parseUntilBound(untilDate),
                status,
                previousStatus,
                automation,
                lastModifiedBy,
                type,
                skuCode,
                sourceAreaCode,
                effectiveLimit + 1);

        boolean truncated = rows.size() > effectiveLimit;
        if (truncated) {
            rows = rows.subList(0, effectiveLimit);
        }

        Set<Long> ppIds = new HashSet<>();
        for (TaskRow r : rows) {
            if (r.pickPackageId() != null) {
                ppIds.add(r.pickPackageId());
            }
        }
        Map<Long, String> ppCodes = pickPackageRepo.findCodesByIds(ppIds);

        Map<String, Long> decrementCounts = Map.of();
        if (phantom) {
            Set<String> traceIds = new HashSet<>();
            for (TaskRow r : rows) {
                if (r.stockTraceId() != null && !r.stockTraceId().isBlank()) {
                    traceIds.add(r.stockTraceId());
                }
            }
            decrementCounts = stockHistoryRepo.findDecrementCountsByTraceIds(traceIds);
        }

        List<TaskMatch> matches = new ArrayList<>(rows.size());
        for (TaskRow r : rows) {
            Long decrCount = null;
            if (phantom) {
                if (r.stockTraceId() == null || r.stockTraceId().isBlank()) {
                    // Tasks without a trace can't be evaluated under phantomClose; drop them.
                    continue;
                }
                decrCount = decrementCounts.getOrDefault(r.stockTraceId(), 0L);
                if (decrCount > 0) {
                    continue;
                }
            }
            matches.add(new TaskMatch(
                    r.id(),
                    r.pickPackageId(),
                    r.pickPackageId() == null ? null : ppCodes.get(r.pickPackageId()),
                    r.status(),
                    r.previousStatus(),
                    r.createdDate(),
                    r.lastModifiedDate(),
                    r.lastModifiedBy(),
                    r.automation(),
                    r.sourceAreaCode(),
                    r.skuCode(),
                    r.quantity(),
                    r.stockTraceId(),
                    r.pickingTaskList(),
                    r.type(),
                    r.retryCount(),
                    r.failureReason(),
                    r.reason(),
                    decrCount));
        }

        return new PickingTaskSearchEvidence(matches.size(), truncated, phantom, matches);
    }

    private static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * Parse the lower bound. Date-only ('2026-05-04') becomes 00:00 of that day.
     * A full datetime ('2026-05-04T03:15:00') is used as-is. Trailing 'Z' is tolerated.
     */
    private static LocalDateTime parseSinceBound(String iso) {
        return parseBound(iso, /*untilSemantics=*/false);
    }

    /**
     * Parse the upper bound. Date-only becomes start of next day (inclusive day semantics).
     * A full datetime is used as-is (exclusive moment).
     */
    private static LocalDateTime parseUntilBound(String iso) {
        return parseBound(iso, /*untilSemantics=*/true);
    }

    private static LocalDateTime parseBound(String iso, boolean untilSemantics) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        String s = iso.trim();
        if (s.endsWith("Z")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.contains("T")) {
            return LocalDateTime.parse(s);
        }
        LocalDate d = LocalDate.parse(s);
        return untilSemantics ? d.plusDays(1).atStartOfDay() : d.atStartOfDay();
    }
}
