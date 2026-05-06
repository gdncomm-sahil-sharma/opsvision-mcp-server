package com.gdn.opsvision.mcp.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.PickerStatusBreakdown;

/**
 * Evidence pack for {@code diagnosePickerQueue} — picker-rooted twin of
 * {@code diagnosePickPackage}. Single picker in, structured signals out for
 * "why is THIS picker's queue empty?" investigations.
 *
 * <p>Returns FACTS, not VERDICTS. The {@link PickerWorkflowSignals} block is a flat set of
 * pre-computed booleans the agent can scan to compose its answer (no zone groups, no
 * eligible zones, no open pick_lists in zones, picker offline, picker inactive, etc.).
 */
public record PickerQueueDiagnosisEvidence(
        String pickerCodeOrId,
        boolean found,
        PickerState state,
        List<ZoneGroupMembership> zoneGroupMemberships,
        AvailableWork availableWork,
        List<ZoneActivity> zoneBreakdown,
        SiblingPickerSummary siblingPickers,
        PickerWorkflowSignals signals,
        InterpretiveHints hints) {

    /** Section 1 — picker row state. */
    public record PickerState(
            long pickerId,
            String pickerCode,
            String pickerName,
            Long warehouseId,
            String warehouseCode,
            String status,
            Instant lastLoginTime,
            boolean active,
            boolean deleted,
            String type) {
    }

    /**
     * Section 2 — one entry per zone_group the picker belongs to, with the zones inside
     * that group. The zones list is capped per group (50 by default); {@code zonesTruncated}
     * flags overflow.
     */
    public record ZoneGroupMembership(
            long zoneGroupId,
            String zoneGroupName,
            int zoneCount,
            boolean zonesTruncated,
            List<ZoneRef> zones) {
    }

    public record ZoneRef(long zoneId, String zoneCode, String zoneName) {
    }

    /**
     * Section 3 — across ALL zones the picker has access to: how many OPEN unassigned
     * pick_lists exist, plus a capped sample showing the queue head with priority context.
     */
    public record AvailableWork(
            int openUnassignedPickListsAcrossPickerZones,
            boolean openPickListsTruncated,
            List<PickListSummary> openPickListsSample) {
    }

    public record PickListSummary(
            long pickListId,
            String pickListStatus,
            Long allottedZoneId,
            String allottedZoneCode,
            Long priority,
            Long pickingPriorityLevelId,
            Integer pickingPriorityPrecedence,
            Long subLevelPriority,
            Instant createdDate,
            String pickPackageCodeSample) {
    }

    /**
     * Section 4 — one row per zone the picker has access to, with how busy that zone's
     * queue is. Useful for "all my zones have 0 open pick_lists" vs "all my zones have
     * thousands but other pickers are claiming them faster than me".
     */
    public record ZoneActivity(
            long zoneId,
            String zoneCode,
            int openUnassignedPickLists,
            int totalOpenPickLists,
            int totalPickListsAnyStatus) {
    }

    /**
     * Section 5 — sibling pickers in the same zone groups (excluding this picker). Shows
     * the picker pool the user is competing with. Status breakdown surfaces how many are
     * AVAILABLE / BUSY / OFFLINE etc.
     */
    public record SiblingPickerSummary(
            int countInSameZoneGroups,
            PickerStatusBreakdown statusBreakdown) {
    }

    /** Section 6 — pre-computed boolean signals derived from sections 1–5. */
    public record PickerWorkflowSignals(
            boolean pickerExists,
            boolean isInactive,
            boolean isDeleted,
            boolean hasNoZoneGroupMemberships,
            boolean hasZoneGroupsButNoZones,
            boolean noOpenUnassignedPickListsInPickerZones,
            boolean openPickListsExistInPickerZones,
            boolean pickerStatusAvailable,
            boolean pickerStatusBusy,
            boolean pickerStatusOffline,
            boolean pickerStatusOnBreak,
            boolean isOnlyPickerForOwnZoneGroups,
            boolean siblingPickersAllNonAvailable) {
    }

    /** Section 7 — plain-English meanings + applicable hints. */
    public record InterpretiveHints(
            String pickerStatusMeaning,
            List<String> applicableHints) {
    }

    /** Convenience holder for tool-side aggregation. */
    public record PerZoneStats(Map<Long, ZoneActivity> byZoneId) {
    }
}
