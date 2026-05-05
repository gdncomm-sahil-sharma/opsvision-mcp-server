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
              - sourceArea filtering isn't supported here; use 'siteCode' (warehouse code, e.g. \
                'MAR-0000000001') for the warehouse-level scope.

            Date inputs are ISO date strings ('2026-05-04'). sinceDate is inclusive (>= midnight). \
            untilDate is interpreted as inclusive of the named day (matches rows with \
            created_date < midnight of the next day) — pass the same date for sinceDate and \
            untilDate to scope to a single calendar day.

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
            @ToolParam(description = "ISO date 'YYYY-MM-DD'; created_date >= sinceDate-00:00", required = false) String sinceDate,
            @ToolParam(description = "ISO date 'YYYY-MM-DD'; inclusive of named day (created_date < day after)", required = false) String untilDate,
            @ToolParam(description = "picking_task.status, e.g. 'CLOSED'", required = false) String status,
            @ToolParam(description = "picking_task.previous_status, e.g. 'PENDING_CLOSED'", required = false) String previousStatus,
            @ToolParam(description = "picking_task.automation; case-insensitive (e.g. 'WCS')", required = false) String automation,
            @ToolParam(description = "picking_task.last_modified_by, e.g. 'SYSTEM'", required = false) String lastModifiedBy,
            @ToolParam(description = "picking_task.type", required = false) String type,
            @ToolParam(description = "Max rows (default 50, capped at 200)", required = false) Integer limit,
            @ToolParam(description = "Cross-DB filter: only keep tasks whose stock_trace_id has zero DECREASE_* events in stock_history", required = false) Boolean phantomClose) {

        int effectiveLimit = clampLimit(limit);
        boolean phantom = Boolean.TRUE.equals(phantomClose);

        List<TaskRow> rows = movementSearch.searchTasks(
                siteCode,
                parseStartOfDay(sinceDate),
                parseExclusiveEndOfDay(untilDate),
                status,
                previousStatus,
                automation,
                lastModifiedBy,
                type,
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

    private static LocalDateTime parseStartOfDay(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        return LocalDate.parse(iso.trim()).atStartOfDay();
    }

    private static LocalDateTime parseExclusiveEndOfDay(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        return LocalDate.parse(iso.trim()).plusDays(1).atStartOfDay();
    }
}
