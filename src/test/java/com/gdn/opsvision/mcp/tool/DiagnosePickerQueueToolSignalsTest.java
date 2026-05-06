package com.gdn.opsvision.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.PickerStatusBreakdown;
import com.gdn.opsvision.mcp.dto.PickerQueueDiagnosisEvidence.AvailableWork;
import com.gdn.opsvision.mcp.dto.PickerQueueDiagnosisEvidence.PickerWorkflowSignals;
import com.gdn.opsvision.mcp.dto.PickerQueueDiagnosisEvidence.SiblingPickerSummary;
import com.gdn.opsvision.mcp.dto.PickerQueueDiagnosisEvidence.ZoneGroupMembership;
import com.gdn.opsvision.mcp.repository.PickerAccessRepository.PickerStateRow;

/**
 * Fixture-based unit tests for {@link DiagnosePickerQueueTool#computeSignals}. Focuses
 * on vacuous-true suppression — the original motivation for restructuring this signal
 * block.
 */
class DiagnosePickerQueueToolSignalsTest {

    private static final List<String> DERIVED_KEYS = List.of(
            "noOpenUnassignedPickListsInPickerZones",
            "openPickListsExistInPickerZones",
            "isOnlyPickerForOwnZoneGroups",
            "siblingPickersAllNonAvailable");

    @Test
    void noZoneGroups_suppressesAllVacuousTrues() {
        // Picker with zero zone_groups: hasNoZoneGroupMemberships=true is dominant.
        // Secondary signals about queue and "only picker" must NOT fire even though
        // the underlying counts are zero.
        PickerStateRow p = picker("OFFLINE", true, false);
        PickerWorkflowSignals out = DiagnosePickerQueueTool.computeSignals(
                p, List.of(), Set.of(),
                new AvailableWork(0, false, List.of()),
                new SiblingPickerSummary(0, emptyBreakdown()));

        assertThat(out.hasNoZoneGroupMemberships()).isTrue();
        assertThat(out.hasZoneGroupsButNoZones()).isFalse();
        // Vacuous-true suppression — these would have been true under naive logic:
        assertThat(out.noOpenUnassignedPickListsInPickerZones()).isFalse();
        assertThat(out.openPickListsExistInPickerZones()).isFalse();
        assertThat(out.isOnlyPickerForOwnZoneGroups()).isFalse();
        // Notes still present and explain the suppression:
        assertThat(out.derivationNotes().get("isOnlyPickerForOwnZoneGroups"))
                .contains("Suppressed-vacuous")
                .contains("picker zone access=false");
        assertThat(out.derivationNotes().get("noOpenUnassignedPickListsInPickerZones"))
                .contains("Suppressed-vacuous");
    }

    @Test
    void zoneGroupsButNoActiveZones_suppressesQueueClaims() {
        PickerStateRow p = picker("AVAILABLE", true, false);
        ZoneGroupMembership emptyGroup = new ZoneGroupMembership(
                10L, "EmptyGroup", 0, false, List.of());
        PickerWorkflowSignals out = DiagnosePickerQueueTool.computeSignals(
                p, List.of(emptyGroup), Set.of(),
                new AvailableWork(0, false, List.of()),
                new SiblingPickerSummary(0, emptyBreakdown()));

        assertThat(out.hasNoZoneGroupMemberships()).isFalse();
        assertThat(out.hasZoneGroupsButNoZones()).isTrue();
        assertThat(out.noOpenUnassignedPickListsInPickerZones()).isFalse();
        assertThat(out.openPickListsExistInPickerZones()).isFalse();
        assertThat(out.isOnlyPickerForOwnZoneGroups()).isFalse();
    }

    @Test
    void zonesAccessAndEmptyQueue_firesNoOpen_butNotOpenExist() {
        PickerStateRow p = picker("AVAILABLE", true, false);
        Set<Long> zones = new LinkedHashSet<>(List.of(100L, 101L));
        PickerWorkflowSignals out = DiagnosePickerQueueTool.computeSignals(
                p, List.of(membershipWithZones(zones)), zones,
                new AvailableWork(0, false, List.of()),
                new SiblingPickerSummary(5, breakdown(2, 3, 0, 0, 0, 0, 0)));

        assertThat(out.noOpenUnassignedPickListsInPickerZones()).isTrue();
        assertThat(out.openPickListsExistInPickerZones()).isFalse();
        assertThat(out.isOnlyPickerForOwnZoneGroups()).isFalse();
        assertThat(out.siblingPickersAllNonAvailable()).isFalse();
    }

    @Test
    void zonesAccessAndQueueHasWork_firesOpenExist() {
        PickerStateRow p = picker("AVAILABLE", true, false);
        Set<Long> zones = new LinkedHashSet<>(List.of(100L));
        PickerWorkflowSignals out = DiagnosePickerQueueTool.computeSignals(
                p, List.of(membershipWithZones(zones)), zones,
                new AvailableWork(7, false, List.of()),
                new SiblingPickerSummary(0, emptyBreakdown()));

        assertThat(out.noOpenUnassignedPickListsInPickerZones()).isFalse();
        assertThat(out.openPickListsExistInPickerZones()).isTrue();
        assertThat(out.isOnlyPickerForOwnZoneGroups()).isTrue();
    }

    @Test
    void siblingsAllBusy_firesSiblingsAllNonAvailable() {
        PickerStateRow p = picker("AVAILABLE", true, false);
        Set<Long> zones = new LinkedHashSet<>(List.of(100L));
        // 4 siblings, 0 AVAILABLE
        PickerWorkflowSignals out = DiagnosePickerQueueTool.computeSignals(
                p, List.of(membershipWithZones(zones)), zones,
                new AvailableWork(2, false, List.of()),
                new SiblingPickerSummary(4, breakdown(0, 3, 1, 0, 0, 0, 0)));

        assertThat(out.siblingPickersAllNonAvailable()).isTrue();
    }

    @Test
    void inactivePickerDeletedPicker_signalsFlow() {
        PickerStateRow p = picker("OFFLINE", false, true);
        PickerWorkflowSignals out = DiagnosePickerQueueTool.computeSignals(
                p, List.of(), Set.of(),
                new AvailableWork(0, false, List.of()),
                new SiblingPickerSummary(0, emptyBreakdown()));

        assertThat(out.isInactive()).isTrue();
        assertThat(out.isDeleted()).isTrue();
        assertThat(out.pickerStatusOffline()).isTrue();
    }

    @Test
    void derivationNotesPresentForAllDerivedSignals() {
        PickerStateRow p = picker("AVAILABLE", true, false);
        PickerWorkflowSignals out = DiagnosePickerQueueTool.computeSignals(
                p, List.of(), Set.of(),
                new AvailableWork(0, false, List.of()),
                new SiblingPickerSummary(0, emptyBreakdown()));

        assertThat(out.derivationNotes()).isNotNull();
        for (String key : DERIVED_KEYS) {
            assertThat(out.derivationNotes()).containsKey(key);
            assertThat(out.derivationNotes().get(key)).isNotBlank();
        }
    }

    // ─── fixtures ────────────────────────────────────────────────────────────

    private static PickerStateRow picker(String status, boolean active, boolean deleted) {
        return new PickerStateRow(
                /*id*/ 1L, /*code*/ "PIC-1", /*name*/ "Test Picker",
                /*warehouseId*/ 10L, /*warehouseCode*/ "MAR-0000000001",
                status, /*lastLoginTime*/ Instant.now(), active, deleted,
                /*type*/ "MANUAL_APP");
    }

    private static ZoneGroupMembership membershipWithZones(Set<Long> zoneIds) {
        return new ZoneGroupMembership(
                /*zoneGroupId*/ 1L, /*zoneGroupName*/ "TestGroup",
                /*zoneCount*/ zoneIds.size(), /*zonesTruncated*/ false,
                /*zones*/ List.of()); // zones list shape doesn't affect computeSignals
    }

    private static PickerStatusBreakdown emptyBreakdown() {
        return new PickerStatusBreakdown(0, 0, 0, 0, 0, 0, 0);
    }

    private static PickerStatusBreakdown breakdown(int avail, int busy, int off, int brkInit,
            int brkRej, int occ, int other) {
        return new PickerStatusBreakdown(avail, busy, off, brkInit, brkRej, occ, other);
    }

    @SuppressWarnings("unused")
    private static Map<String, String> empty() {
        return Map.of();
    }
}
