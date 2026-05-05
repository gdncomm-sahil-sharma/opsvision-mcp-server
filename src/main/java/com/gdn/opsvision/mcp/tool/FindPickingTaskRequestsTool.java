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

import com.gdn.opsvision.mcp.dto.PickingTaskRequestSearchEvidence;
import com.gdn.opsvision.mcp.dto.PickingTaskRequestSearchEvidence.RequestMatch;
import com.gdn.opsvision.mcp.repository.MovementSearchRepository;
import com.gdn.opsvision.mcp.repository.MovementSearchRepository.RequestRow;
import com.gdn.opsvision.mcp.repository.PickPackageRepository;

@Service
public class FindPickingTaskRequestsTool {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final MovementSearchRepository movementSearch;
    private final PickPackageRepository pickPackageRepo;

    public FindPickingTaskRequestsTool(
            MovementSearchRepository movementSearch,
            PickPackageRepository pickPackageRepo) {
        this.movementSearch = movementSearch;
        this.pickPackageRepo = pickPackageRepo;
    }

    @Tool(description = """
            Bulk search across warehouse-movement picking_task_request rows. All filters are \
            AND-combined; leaving a filter null/empty means "any value." Returns up to `limit` \
            matching rows (default 50, max 200) with truncated=true when the cap is hit. Each \
            match is resolved to its parent pick package code via batch lookup against stockholm \
            (request.reference_id = pick_package.id; reference_type is the order channel, NOT \
            an entity discriminator — don't filter by reference_type='PICK_PACKAGE').

            Use this for blast-radius investigations on the request layer. Examples:
              - status='HOLD' + sinceDate/untilDate → candidate Pattern A "stuck-in-HOLD" \
                population (request can't anchor to a bin → no tasks ever spawn).
              - hasMultiSkuBatchFailedReason=true + sinceDate/untilDate → candidate Pattern B \
                trigger population (WCS routing decided a multi-SKU batch wasn't viable; this \
                signal is preserved even on CLOSED requests).
              - referenceType='B2C_ONLINE' + status<>'CLOSED' → live B2C requests.

            Date inputs are ISO date strings ('2026-05-04'). sinceDate is inclusive (>= midnight). \
            untilDate is interpreted as inclusive of the named day (matches rows with \
            created_date < midnight of the next day) — pass the same date for sinceDate and \
            untilDate to scope to a single calendar day.

            Returns FACTS, not VERDICTS. A list of HOLD requests is a *candidate list* — the \
            agent still has to chain into getStockTrace per result to confirm the half-applied \
            reservation signature; a list of has-msbfr requests is a *trigger list*, not yet a \
            phantom-close confirmation (use findPickingTasks(phantomClose=true) for that).
            """)
    public PickingTaskRequestSearchEvidence findPickingTaskRequests(
            @ToolParam(description = "Warehouse / site code, e.g. 'MAR-0000000001'", required = false) String siteCode,
            @ToolParam(description = "ISO date 'YYYY-MM-DD'; created_date >= sinceDate-00:00", required = false) String sinceDate,
            @ToolParam(description = "ISO date 'YYYY-MM-DD'; inclusive of named day", required = false) String untilDate,
            @ToolParam(description = "picking_task_request.status, e.g. 'HOLD' or 'CLOSED'", required = false) String status,
            @ToolParam(description = "picking_task_request.previous_status", required = false) String previousStatus,
            @ToolParam(description = "Order channel: B2C_ONLINE / B2B_ORDER / STANDARD / TRANSFER_WAREHOUSE / CONVERT_SKU_ASSEMBLY", required = false) String referenceType,
            @ToolParam(description = "picking_task_request.picking_type, e.g. 'REGULAR_PICKING'", required = false) String pickingType,
            @ToolParam(description = "picking_task_request.last_modified_by, e.g. 'SYSTEM'", required = false) String lastModifiedBy,
            @ToolParam(description = "true = only requests with multi_sku_batch_failed_reason populated; false = only those without", required = false) Boolean hasMultiSkuBatchFailedReason,
            @ToolParam(description = "Max rows (default 50, capped at 200)", required = false) Integer limit) {

        int effectiveLimit = clampLimit(limit);

        List<RequestRow> rows = movementSearch.searchRequests(
                siteCode,
                parseStartOfDay(sinceDate),
                parseExclusiveEndOfDay(untilDate),
                status,
                previousStatus,
                referenceType,
                pickingType,
                lastModifiedBy,
                hasMultiSkuBatchFailedReason,
                effectiveLimit + 1);

        boolean truncated = rows.size() > effectiveLimit;
        if (truncated) {
            rows = rows.subList(0, effectiveLimit);
        }

        Set<Long> ppIds = new HashSet<>();
        for (RequestRow r : rows) {
            if (r.referenceId() != null) {
                ppIds.add(r.referenceId());
            }
        }
        Map<Long, String> ppCodes = pickPackageRepo.findCodesByIds(ppIds);

        List<RequestMatch> matches = new ArrayList<>(rows.size());
        for (RequestRow r : rows) {
            matches.add(new RequestMatch(
                    r.id(),
                    r.referenceId(),
                    r.referenceId() == null ? null : ppCodes.get(r.referenceId()),
                    r.status(),
                    r.previousStatus(),
                    r.createdDate(),
                    r.lastModifiedDate(),
                    r.lastModifiedBy(),
                    r.referenceType(),
                    r.pickingType(),
                    r.type(),
                    r.targetAreaCode(),
                    r.multiSkuBatchFailedReason()));
        }

        return new PickingTaskRequestSearchEvidence(matches.size(), truncated, matches);
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
