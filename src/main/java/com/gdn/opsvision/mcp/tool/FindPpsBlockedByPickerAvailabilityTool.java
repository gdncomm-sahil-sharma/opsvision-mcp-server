package com.gdn.opsvision.mcp.tool;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.PickerSnapshot;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.PickerStatusBreakdown;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.PickerStatusFreshness;
import com.gdn.opsvision.mcp.dto.PpsBlockedByPickerAvailabilityEvidence;
import com.gdn.opsvision.mcp.dto.PpsBlockedByPickerAvailabilityEvidence.BlockedPp;
import com.gdn.opsvision.mcp.dto.PpsBlockedByPickerAvailabilityEvidence.ZoneGroup;
import com.gdn.opsvision.mcp.repository.PickerAccessRepository;
import com.gdn.opsvision.mcp.repository.PickerAccessRepository.BlockedPpRow;
import com.gdn.opsvision.mcp.repository.PickerAccessRepository.PickerRow;

/**
 * Bulk view of the {@code hasEligiblePickersButNoneAvailable} signal — finds every PP
 * at a site whose open unclaimed pick_list is in a zone where eligible pickers exist
 * but none are currently {@code AVAILABLE}.
 *
 * <p>Why this is composite (not just a {@code findPickPackages} filter): the predicate
 * "zone has eligible pickers but 0 AVAILABLE" requires aggregating the picker pool
 * via {@code zone_zone_group → picker_zone_group → picker} — that's an aggregation, not
 * a filter. The natural result shape is also "grouped by zone with per-zone summary",
 * not "flat list of PPs". Both arguments push it out of the generic micro-API.
 */
@Service
public class FindPpsBlockedByPickerAvailabilityTool {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final PickerAccessRepository pickerAccessRepo;

    public FindPpsBlockedByPickerAvailabilityTool(PickerAccessRepository pickerAccessRepo) {
        this.pickerAccessRepo = pickerAccessRepo;
    }

    @Tool(description = """
            Find pick packages whose open unclaimed pick_list is in a zone where \
            eligible pickers exist but ZERO are currently AVAILABLE (all are BUSY / \
            OFFLINE / on break). This is the bulk view of the \
            'hasEligiblePickersButNoneAvailable' signal that diagnosePickPackage \
            computes per-PP.

            Result is grouped by allotted zone. Each zone-group carries the picker \
            pool stats (status breakdown, OFFLINE-freshness histogram, top-5 \
            recently-online picker sample) so an operator can spot which zones are \
            the worst offenders, plus the list of stuck PPs in that zone. Zone-groups \
            are sorted by blockedPpCount DESC.

            Use this when the question is 'which PPs are waiting for a free picker?' \
            and the agent wants a per-zone view. For other shapes — e.g. 'find PPs at \
            site X with picking_status=Y and an open PL' — use findPickPackages.

            Excludes zones with NO eligible pickers (that's a different operational \
            problem — zone-coverage gap, not shift-availability — and would otherwise \
            dwarf the response with paper-only zones).

            limit defaults to 50, capped at 200; truncated=true on the response when \
            the cap is hit. Per-zone summaries are computed from the full picker pool \
            (not capped); only the blocked-PP list is truncated.
            """)
    public PpsBlockedByPickerAvailabilityEvidence findPpsBlockedByPickerAvailability(
            @ToolParam(description = "Site / warehouse code (e.g. 'MAR-0000000001')") String siteCode,
            @ToolParam(description = "Max blocked PPs to return (default 50, capped at 200)", required = false) Integer limit) {

        int effective = clampLimit(limit);

        // limit + 1 sentinel for truncation detection.
        List<BlockedPpRow> rows = pickerAccessRepo.findPpsBlockedByPickerAvailability(
                siteCode, effective + 1);
        boolean truncated = rows.size() > effective;
        if (truncated) {
            rows = rows.subList(0, effective);
        }

        // Group rows by zone_id, preserving insertion order (which is zone_code, pp_created).
        Map<Long, List<BlockedPpRow>> byZone = new LinkedHashMap<>();
        for (BlockedPpRow r : rows) {
            if (r.zoneId() == null) {
                continue;
            }
            byZone.computeIfAbsent(r.zoneId(), k -> new ArrayList<>()).add(r);
        }

        // Build one ZoneGroup per zone — fetch the picker pool, compute breakdown +
        // freshness + sample using the same helpers diagnosePickPackage uses.
        Instant now = Instant.now();
        List<ZoneGroup> groups = new ArrayList<>(byZone.size());
        for (Map.Entry<Long, List<BlockedPpRow>> e : byZone.entrySet()) {
            Long zoneId = e.getKey();
            List<BlockedPpRow> zoneRows = e.getValue();
            String zoneCode = zoneRows.get(0).zoneCode();
            int eligibleCount = zoneRows.get(0).eligiblePickerCount();

            List<PickerRow> pool = pickerAccessRepo.findEligiblePickersForZones(
                    List.of(zoneId), siteCode);
            PickerStatusBreakdown breakdown = DiagnosePickPackageTool.breakdown(pool);
            PickerStatusFreshness freshness = DiagnosePickPackageTool.freshness(pool, now);
            List<PickerSnapshot> sample = DiagnosePickPackageTool.recentlyOnlineSample(pool, now);

            List<BlockedPp> blockedPackages = new ArrayList<>(zoneRows.size());
            for (BlockedPpRow r : zoneRows) {
                blockedPackages.add(new BlockedPp(
                        r.ppId(), r.ppCode(), r.pickingStatus(),
                        r.pickListId(), r.pickListStatus(),
                        r.ppCreatedDate(), r.pickListCreatedDate()));
            }

            groups.add(new ZoneGroup(
                    zoneId, zoneCode, eligibleCount,
                    breakdown, freshness, sample,
                    blockedPackages.size(), blockedPackages));
        }

        // Re-sort zone groups by blockedPpCount DESC (worst-affected zone leads).
        groups.sort((a, b) -> Integer.compare(b.blockedPpCount(), a.blockedPpCount()));

        return new PpsBlockedByPickerAvailabilityEvidence(
                siteCode, rows.size(), groups.size(), truncated, effective, groups);
    }

    private static int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
