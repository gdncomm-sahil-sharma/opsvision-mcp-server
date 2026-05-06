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
 * <p>Returns FACTS, not VERDICTS. The {@link PickerWorkflowSignals} block is a flat set
 * of pre-computed booleans the agent can scan to compose its answer.
 *
 * <p>Vacuous-true suppression: when the dominant fact "picker has no zone access at
 * all" is true, secondary signals like {@code noOpenUnassignedPickListsInPickerZones}
 * and {@code isOnlyPickerForOwnZoneGroups} are forced to {@code false} — they would
 * otherwise be vacuously true (universal-quantifier over empty set) and mislead the
 * agent into reading "they're the only picker" or "queue is empty" when the real
 * problem is "no zones configured."
 *
 * <p>{@link PickerWorkflowSignals#derivationNotes()} carries a one-liner per derived
 * boolean explaining the rule and inputs, so a buggy derivation is detectable from
 * the evidence pack itself.
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
        PickerStatusInterpretation pickerStatusInterpretation) {

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

    /**
     * Section 6 — pre-computed boolean signals.
     *
     * <p>Two flavors: <b>state-equality booleans</b> (cheap, deterministic; e.g.
     * {@code pickerStatusAvailable} is just {@code picker.status=="AVAILABLE"}) and
     * <b>derived booleans</b> computed from multiple inputs.
     * {@link #derivationNotes()} carries a one-liner per derived signal explaining
     * the rule + inputs.
     *
     * <p>Vacuous-true suppression rules applied:
     * <ul>
     *   <li>{@code noOpenUnassignedPickListsInPickerZones}, {@code openPickListsExistInPickerZones},
     *       {@code isOnlyPickerForOwnZoneGroups} are forced {@code false} when
     *       {@code hasNoZoneGroupMemberships=true} OR {@code hasZoneGroupsButNoZones=true} —
     *       picker has no zone access, so claims about "queue in picker's zones" or
     *       "only picker for groups" are vacuous.</li>
     *   <li>{@code siblingPickersAllNonAvailable} is forced {@code false} when there are
     *       zero siblings — no peers can't be "all non-available."</li>
     * </ul>
     */
    public record PickerWorkflowSignals(
            // existence + state-equality booleans
            boolean pickerExists,
            boolean isInactive,
            boolean isDeleted,
            boolean hasNoZoneGroupMemberships,
            boolean hasZoneGroupsButNoZones,
            boolean pickerStatusAvailable,
            boolean pickerStatusBusy,
            boolean pickerStatusOffline,
            boolean pickerStatusOnBreak,
            // derived booleans — see derivationNotes for the rule
            boolean noOpenUnassignedPickListsInPickerZones,
            boolean openPickListsExistInPickerZones,
            boolean isOnlyPickerForOwnZoneGroups,
            boolean siblingPickersAllNonAvailable,
            // per-derived-signal one-liner
            Map<String, String> derivationNotes) {
    }

    /**
     * Replaces {@code applicableHints} list. One short meaning + structured stage +
     * source-file ref.
     */
    public record PickerStatusInterpretation(
            String label,
            PickerOperationalStage operationalStage,
            String meaning,
            String sourceRef) {
    }

    /** Convenience holder for tool-side aggregation. */
    public record PerZoneStats(Map<Long, ZoneActivity> byZoneId) {
    }
}
