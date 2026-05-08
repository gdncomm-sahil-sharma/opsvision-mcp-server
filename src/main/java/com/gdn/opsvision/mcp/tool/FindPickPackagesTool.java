package com.gdn.opsvision.mcp.tool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import com.gdn.opsvision.mcp.dto.PickPackageSearchEvidence;
import com.gdn.opsvision.mcp.dto.PickPackageSearchEvidence.PickPackageRow;
import com.gdn.opsvision.mcp.repository.PickPackageSearchRepository;
import com.gdn.opsvision.mcp.repository.PickPackageSearchRepository.PpSearchRow;
import com.gdn.opsvision.mcp.repository.PickPackageSearchRepository.SearchFilters;
import com.gdn.opsvision.mcp.repository.PickPackageSearchRepository.SortBy;
import com.gdn.opsvision.mcp.tool.util.IsoBound;
import com.gdn.opsvision.mcp.tool.util.Pagination;

@Service
public class FindPickPackagesTool {

    /**
     * {@code PickPackageStatus} enum names indexed by ordinal — matches the persistence
     * order in stockholm. Used for both the {@code statusLabel} on each row and to
     * translate the {@code ppStatus} filter (string name) into the integer column value.
     */
    private static final String[] PP_STATUS_LABELS = {
            "OPEN",                          // 0
            "WEIGHT_CAPTURE_PENDING",        // 1
            "WEIGHT_CAPTURE_DONE",           // 2
            "GIN_COMPLETE",                  // 3
            "AWB_PENDING",                   // 4
            "AWB_RECEIVED",                  // 5
            "SHIPMENT_BOOKING_FAILED",       // 6
            "ADDED_TO_SHIPMENT_REQUEST",     // 7
            "PARTIAL_GIN_COMPLETE",          // 8
            "WAITING_FOR_SHIPMENT_REQUEST",  // 9
            "CANCELLATION_PENDING"           // 10
    };

    /** Reverse lookup: status name → ordinal. Built once, immutable. */
    private static final Map<String, Integer> PP_STATUS_ORDINAL_BY_NAME = buildStatusOrdinalMap();

    private static Map<String, Integer> buildStatusOrdinalMap() {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < PP_STATUS_LABELS.length; i++) {
            m.put(PP_STATUS_LABELS[i], i);
        }
        return Map.copyOf(m);
    }

    private final PickPackageSearchRepository repo;

    public FindPickPackagesTool(PickPackageSearchRepository repo) {
        this.repo = repo;
    }

    @Tool(description = """
            Generic micro-API search across pick_package — combine any subset of filters
            to find PPs matching a shape (e.g. "unassigned PARTIAL_PACKAGE PPs at MAR \
            with an open pick_list", "all PPs in batch B-123", "PPs created today \
            whose open pick_list is in zone AT_WZ_GF_46001"). All filters AND-combine; \
            null filters are ignored.

            Returns FACTS, not VERDICTS — slim PP headers (1 row per match) with the \
            assigned-picker code and resolved siteCode. Caller is expected to follow \
            up on individual matches with diagnosePickPackage / getPickPackage for \
            full evidence packs.

            Filter notes:
            - ppStatus accepts the PickPackageStatus enum name (e.g. "OPEN", "AWB_PENDING"); \
              translated to ordinal internally. Invalid name returns an empty list.
            - assignedPicker accepts a picker code (e.g. "PIC-0000024721") or the special \
              value "UNASSIGNED" for pp.picker IS NULL.
            - hasOpenPickList=true matches PPs with at least one pick_list whose status <> \
              CLOSED; hasOpenPickList=false matches PPs whose pick_lists are all CLOSED \
              (or have no pick_lists at all).
            - openZoneCode filters to PPs whose currently-open pick_list is in the given \
              zone code (zone.zone_code, e.g. "AT_WZ_GF_46001").
            - sourceArea filters via pick_list_details → warehouse_item.storage.area_code \
              (e.g. "GF-STOR").
            - sinceDate / untilDate bound pp.created_date; ISO date "YYYY-MM-DD" or \
              datetime "YYYY-MM-DDTHH:MM:SS". since is inclusive, until is exclusive.
            - sortBy accepts "CREATED_ASC" / "CREATED_DESC" / "UPDATED_DESC" \
              (default CREATED_ASC).
            - limit defaults to 50, capped at 200; truncated=true on the response \
              when the cap is hit.
            """)
    public PickPackageSearchEvidence findPickPackages(
            @ToolParam(description = "Site / warehouse code (e.g. 'MAR-0000000001')", required = false) String siteCode,
            @ToolParam(description = "PickPackageStatus enum name, e.g. 'OPEN' / 'AWB_PENDING'", required = false) String ppStatus,
            @ToolParam(description = "PriorityCalStatus value, e.g. 'PARTIAL_PACKAGE' / 'STORAGE_STOCK_RESERVED'", required = false) String pickingStatus,
            @ToolParam(description = "Match pp.canceled", required = false) Boolean isCanceled,
            @ToolParam(description = "Match pp.short_pick", required = false) Boolean isShortPick,
            @ToolParam(description = "Match pp.rejected", required = false) Boolean isRejected,
            @ToolParam(description = "Match pp.in_progress", required = false) Boolean isInProgress,
            @ToolParam(description = "Picker code (e.g. 'PIC-0000024721') or 'UNASSIGNED' for pp.picker IS NULL", required = false) String assignedPicker,
            @ToolParam(description = "Match pp.batch_id exactly", required = false) String batchId,
            @ToolParam(description = "Match pp.wave_number exactly", required = false) String waveNumber,
            @ToolParam(description = "Lower bound on pp.created_date: 'YYYY-MM-DD' or 'YYYY-MM-DDTHH:MM:SS' (inclusive)", required = false) String sinceDate,
            @ToolParam(description = "Upper bound on pp.created_date: ISO date / datetime (exclusive)", required = false) String untilDate,
            @ToolParam(description = "true = at least one pick_list status<>CLOSED; false = no open pick_lists", required = false) Boolean hasOpenPickList,
            @ToolParam(description = "Zone code of the open pick_list (zone.zone_code, e.g. 'AT_WZ_GF_46001')", required = false) String openZoneCode,
            @ToolParam(description = "Source area code (storage.area_code, e.g. 'GF-STOR')", required = false) String sourceArea,
            @ToolParam(description = "Sort: CREATED_ASC (default) / CREATED_DESC / UPDATED_DESC", required = false) String sortBy,
            @ToolParam(description = "Max rows (default 50, capped at 200)", required = false) Integer limit) {

        int effective = Pagination.clampLimit(limit);
        SortBy sort = parseSort(sortBy);
        // Unresolvable ppStatus (provided but not a known enum name) must return 0 matches —
        // not silently become "no filter". Short-circuit before hitting the DB.
        if (ppStatus != null && !ppStatus.isBlank() && resolvePpStatus(ppStatus) == null) {
            return new PickPackageSearchEvidence(0, false, effective, List.of());
        }
        SearchFilters filters = new SearchFilters(
                blankToNull(siteCode),
                resolvePpStatus(ppStatus),
                blankToNull(pickingStatus),
                isCanceled,
                isShortPick,
                isRejected,
                isInProgress,
                resolveAssignedPicker(assignedPicker),
                blankToNull(batchId),
                blankToNull(waveNumber),
                IsoBound.parseSince(sinceDate),
                IsoBound.parseUntil(untilDate),
                hasOpenPickList,
                blankToNull(openZoneCode),
                blankToNull(sourceArea));

        // limit + 1 trick: query for one extra row; if returned, the result was truncated.
        List<PpSearchRow> rows = repo.search(filters, sort, effective + 1);
        boolean truncated = rows.size() > effective;
        if (truncated) {
            rows = rows.subList(0, effective);
        }
        List<PickPackageRow> matches = new ArrayList<>(rows.size());
        for (PpSearchRow r : rows) {
            matches.add(toRow(r));
        }
        return new PickPackageSearchEvidence(matches.size(), truncated, effective, matches);
    }

    private static PickPackageRow toRow(PpSearchRow r) {
        return new PickPackageRow(
                r.ppId(), r.ppCode(),
                r.status(), statusLabelFor(r.status()),
                r.pickingStatus(), r.canceled(),
                r.inProgress(), r.shortPick(), r.rejected(),
                r.assignedPickerId(), r.assignedPickerCode(),
                r.batchId(), r.waveNumber(),
                r.createdDate(), r.updatedDate(),
                r.siteCode());
    }

    private static String statusLabelFor(int status) {
        if (status >= 0 && status < PP_STATUS_LABELS.length) {
            return PP_STATUS_LABELS[status];
        }
        return "UNKNOWN_STATUS_" + status;
    }

    private static Integer resolvePpStatus(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return PP_STATUS_ORDINAL_BY_NAME.get(name.trim().toUpperCase());
    }

    private static String resolveAssignedPicker(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String trimmed = input.trim();
        if (trimmed.equalsIgnoreCase("UNASSIGNED")) {
            return PickPackageSearchRepository.UNASSIGNED_SENTINEL;
        }
        return trimmed;
    }

    private static SortBy parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return SortBy.CREATED_ASC;
        }
        try {
            return SortBy.valueOf(sort.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return SortBy.CREATED_ASC;
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
