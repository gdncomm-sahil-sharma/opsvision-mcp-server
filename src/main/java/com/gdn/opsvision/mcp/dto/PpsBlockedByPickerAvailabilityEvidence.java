package com.gdn.opsvision.mcp.dto;

import java.time.Instant;
import java.util.List;

import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.PickerSnapshot;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.PickerStatusBreakdown;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.PickerStatusFreshness;

/**
 * Evidence pack for {@code findPpsBlockedByPickerAvailability} — pick packages whose
 * <i>open unclaimed</i> pick_list is in a zone where eligible pickers exist but none
 * are currently {@code AVAILABLE} (all are BUSY / OFFLINE / on break).
 *
 * <p>This is the bulk view of the {@code hasEligiblePickersButNoneAvailable} signal
 * computed per-PP by {@code diagnosePickPackage}. Result is grouped by allotted zone:
 * each {@link ZoneGroup} carries the per-zone picker-pool stats (status breakdown,
 * freshness, recently-online sample) so an operator can spot which zones are the
 * worst offenders, then iterate the {@code blockedPackages} for that zone.
 *
 * <p>Returns FACTS, not VERDICTS. Zones with <i>no</i> eligible pickers at all are
 * NOT included here — that's a different blocking pattern (zone-coverage gap, not
 * shift-availability) and is surfaced by other tools.
 *
 * <p>{@code truncated:true} signals the cap on total blocked PPs was hit; the caller
 * tightens filters or raises {@code limit}. Per-zone summaries are computed from the
 * full, un-truncated picker pool — only the {@code blockedPackages} list is capped.
 */
public record PpsBlockedByPickerAvailabilityEvidence(
        String siteCode,
        int totalBlockedPackages,
        int distinctZones,
        boolean truncated,
        int limit,
        List<ZoneGroup> zoneGroups) {

    /**
     * One affected zone with its picker-pool stats and the list of PPs whose open
     * pick_list sits in this zone. Sorted by {@code blockedPpCount} DESC so the
     * worst-affected zone leads.
     */
    public record ZoneGroup(
            Long allottedZoneId,
            String allottedZoneCode,
            int eligiblePickerCount,
            PickerStatusBreakdown pickerStatusBreakdown,
            PickerStatusFreshness pickerStatusFreshness,
            List<PickerSnapshot> recentlyOnlinePickers,
            int blockedPpCount,
            List<BlockedPp> blockedPackages) {
    }

    /** One PP whose open pick_list is in a no-AVAILABLE zone. */
    public record BlockedPp(
            long ppId,
            String ppCode,
            String pickingStatus,
            long pickListId,
            String pickListStatus,
            Instant ppCreatedDate,
            Instant pickListCreatedDate) {
    }
}
