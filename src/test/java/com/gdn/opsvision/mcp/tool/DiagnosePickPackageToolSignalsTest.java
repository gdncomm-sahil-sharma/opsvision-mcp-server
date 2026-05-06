package com.gdn.opsvision.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gdn.opsvision.mcp.dto.PackingOrderLifecycleStage;
import com.gdn.opsvision.mcp.dto.PickListLifecycleStage;
import com.gdn.opsvision.mcp.dto.SignalKind;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.BatchConsolidation;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.DemandShortage;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.PackingOrderInfo;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.PickListAllocation;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.PickerStatusBreakdown;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.ReplenishmentSignal;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.WorkflowSignals;
import com.gdn.opsvision.mcp.repository.PickPackageDiagnosisRepository.PpStateRow;

/**
 * Fixture-based unit tests for {@link DiagnosePickPackageTool#computeSignals}. No DB.
 * Covers vacuous-true suppression and the derivation-notes contract.
 *
 * <p>Each test hand-builds the inputs computeSignals consumes, calls it, and asserts
 * which derived booleans fire. The notes map is asserted to contain a non-empty entry
 * for each derived signal so the derivation contract holds.
 */
class DiagnosePickPackageToolSignalsTest {

    private static final List<String> DERIVED_KEYS = List.of(
            "hasAnyOpenPickList",
            "hasNoEligiblePickerForAnyOpenPickList",
            "hasEligiblePickersButNoneAvailable",
            "hasReplenishmentDeficit",
            "hasMultipleSourceAreas",
            "isInBatchOrWave",
            "packingOrderMissing");

    @Test
    void emptyInputs_noDerivedFlagsFire_andNotesPresentForAll() {
        // PP exists but: no allocations, no shortages, no source areas, no batch.
        PpStateRow s = ppOpen("READY_FOR_MANUAL_PICKING");
        ReplenishmentSignal repl = new ReplenishmentSignal(false, List.of());
        BatchConsolidation batch = batchAbsent();

        WorkflowSignals out = DiagnosePickPackageTool.computeSignals(
                s, List.of(), repl, List.of(), batch, packingOrderAbsent());

        assertThat(out.hasAnyOpenPickList()).isFalse();
        // Vacuous-true suppression on derived signals over empty inputs:
        assertThat(out.hasNoEligiblePickerForAnyOpenPickList()).isFalse();
        assertThat(out.hasEligiblePickersButNoneAvailable()).isFalse();
        assertThat(out.hasReplenishmentDeficit()).isFalse();
        assertThat(out.hasMultipleSourceAreas()).isFalse();
        assertThat(out.isInBatchOrWave()).isFalse();
        // Enum-equality booleans:
        assertThat(out.isReadyForManualPicking()).isTrue();
        assertThat(out.isAwbPending()).isFalse();
        // Derivation notes present for every derived signal:
        assertNotesPresentForAllDerived(out.derivationNotes());
    }

    @Test
    void singleSourceArea_doesNotFireMultipleSourceAreas() {
        // Vacuous-suppression-adjacent: one area should NOT trip hasMultipleSourceAreas.
        PpStateRow s = ppOpen("PARTIAL_PACKAGE");
        WorkflowSignals out = DiagnosePickPackageTool.computeSignals(
                s, List.of(), new ReplenishmentSignal(false, List.of()),
                List.of("M4-STOR"), batchAbsent(), packingOrderAbsent());

        assertThat(out.hasMultipleSourceAreas()).isFalse();
        assertThat(out.derivationNotes().get("hasMultipleSourceAreas"))
                .contains("size>1")
                .contains("size=1");
    }

    @Test
    void multipleSourceAreas_firesMultipleSourceAreas() {
        PpStateRow s = ppOpen("PARTIAL_PACKAGE");
        WorkflowSignals out = DiagnosePickPackageTool.computeSignals(
                s, List.of(), new ReplenishmentSignal(false, List.of()),
                List.of("M4-STOR", "GF-STOR"), batchAbsent(), packingOrderAbsent());

        assertThat(out.hasMultipleSourceAreas()).isTrue();
    }

    @Test
    void openUnassignedPlWithZeroEligiblePickers_firesNoEligibleForAnyOpen() {
        // Real production-pattern hit case (sample PP M from validation set).
        PpStateRow s = ppOpen("PARTIAL_PACKAGE");
        PickListAllocation pl = openUnassignedPl(/*eligibleCount=*/0, /*available=*/0);

        WorkflowSignals out = DiagnosePickPackageTool.computeSignals(
                s, List.of(pl), new ReplenishmentSignal(false, List.of()),
                List.of("M4-STOR"), batchAbsent(), packingOrderAbsent());

        assertThat(out.hasAnyOpenPickList()).isTrue();
        assertThat(out.hasNoEligiblePickerForAnyOpenPickList()).isTrue();
        assertThat(out.hasEligiblePickersButNoneAvailable()).isFalse();
        assertThat(out.derivationNotes().get("hasNoEligiblePickerForAnyOpenPickList"))
                .contains("1 OPEN+unassigned PL")
                .contains("0 had any eligible picker");
    }

    @Test
    void eligiblePickersExistButAllNonAvailable_firesNoneAvailable() {
        PpStateRow s = ppOpen("PARTIAL_PACKAGE");
        PickListAllocation pl = openUnassignedPl(/*eligibleCount=*/5, /*available=*/0);

        WorkflowSignals out = DiagnosePickPackageTool.computeSignals(
                s, List.of(pl), new ReplenishmentSignal(false, List.of()),
                List.of("M4-STOR"), batchAbsent(), packingOrderAbsent());

        assertThat(out.hasAnyOpenPickList()).isTrue();
        assertThat(out.hasNoEligiblePickerForAnyOpenPickList()).isFalse();
        assertThat(out.hasEligiblePickersButNoneAvailable()).isTrue();
    }

    @Test
    void closedPlsOnly_doesNotFireOpenPlSignals() {
        PpStateRow s = ppOpen("REACHED_TO_QC");
        PickListAllocation pl = closedPl();

        WorkflowSignals out = DiagnosePickPackageTool.computeSignals(
                s, List.of(pl), new ReplenishmentSignal(false, List.of()),
                List.of("M4-STOR"), batchAbsent(), packingOrderAbsent());

        assertThat(out.hasAnyOpenPickList()).isFalse();
        assertThat(out.hasNoEligiblePickerForAnyOpenPickList()).isFalse();
        assertThat(out.hasEligiblePickersButNoneAvailable()).isFalse();
        assertThat(out.derivationNotes().get("hasNoEligiblePickerForAnyOpenPickList"))
                .contains("0 OPEN+unassigned PL");
    }

    @Test
    void replenishmentDeficitFiresOnlyWhenDeficitGtZero() {
        PpStateRow s = ppOpen("STORAGE_NOT_AVAILABLE");
        ReplenishmentSignal noShort = new ReplenishmentSignal(true, List.of(
                new DemandShortage("SKU-A", 5, 5, 0)));
        ReplenishmentSignal yesShort = new ReplenishmentSignal(true, List.of(
                new DemandShortage("SKU-A", 5, 2, 3)));

        WorkflowSignals okOut = DiagnosePickPackageTool.computeSignals(
                s, List.of(), noShort, List.of(), batchAbsent(), packingOrderAbsent());
        WorkflowSignals defOut = DiagnosePickPackageTool.computeSignals(
                s, List.of(), yesShort, List.of(), batchAbsent(), packingOrderAbsent());

        assertThat(okOut.hasReplenishmentDeficit()).isFalse();
        assertThat(defOut.hasReplenishmentDeficit()).isTrue();
        assertThat(defOut.derivationNotes().get("hasReplenishmentDeficit"))
                .contains("1 had deficit>0");
    }

    @Test
    void packingOrderMissing_firesOnPostPickWithNoPackingOrder() {
        // Pattern C confirmation: PP at REACHED_TO_QC but no packing_order present.
        PpStateRow s = ppOpen("REACHED_TO_QC");
        WorkflowSignals out = DiagnosePickPackageTool.computeSignals(
                s, List.of(), new ReplenishmentSignal(false, List.of()),
                List.of(), batchAbsent(), packingOrderAbsent());
        assertThat(out.packingOrderMissing()).isTrue();
        assertThat(out.derivationNotes().get("packingOrderMissing"))
                .contains("REACHED_TO_QC")
                .contains("packingOrder.present=false");
    }

    @Test
    void packingOrderMissing_doesNotFireOnPrePickPp() {
        // Pre-pick PP: packing_order legitimately absent — signal must not fire.
        PpStateRow s = ppOpen("PARTIAL_PACKAGE");
        WorkflowSignals out = DiagnosePickPackageTool.computeSignals(
                s, List.of(), new ReplenishmentSignal(false, List.of()),
                List.of(), batchAbsent(), packingOrderAbsent());
        assertThat(out.packingOrderMissing()).isFalse();
    }

    @Test
    void packingOrderMissing_doesNotFireWhenPackingOrderPresent() {
        // Healthy: PP at REACHED_TO_QC with a CLAIMED packing_order.
        PpStateRow s = ppOpen("REACHED_TO_QC");
        WorkflowSignals out = DiagnosePickPackageTool.computeSignals(
                s, List.of(), new ReplenishmentSignal(false, List.of()),
                List.of(), batchAbsent(), packingOrderClaimed());
        assertThat(out.packingOrderMissing()).isFalse();
    }

    @Test
    void q23_rejectedOperatorOverride_outranksPostPickStage() {
        // Q23 from the validation set: PP rejected=true, pp.status=1 (WEIGHT_CAPTURE_PENDING),
        // picking_status=REACHED_TO_QC. Three signals fire. The agent should lead with
        // the operator override, not the post-pick stage. signalKinds proves the
        // ordering is detectable structurally.
        PpStateRow s = ppRejectedAtWeightCapture();

        WorkflowSignals out = DiagnosePickPackageTool.computeSignals(
                s, List.of(), new ReplenishmentSignal(false, List.of()),
                List.of(), batchAbsent(), packingOrderAbsent());

        // Three booleans true:
        assertThat(out.isRejected()).isTrue();
        assertThat(out.isWeightCapturePending()).isTrue();
        assertThat(out.isReachedToQc()).isTrue();

        // signalKinds map carries the structural classification:
        Map<String, SignalKind> kinds = out.signalKinds();
        assertThat(kinds).containsEntry("isRejected", SignalKind.OPERATOR_OVERRIDE);
        assertThat(kinds).containsEntry("isWeightCapturePending", SignalKind.STAGE);
        assertThat(kinds).containsEntry("isReachedToQc", SignalKind.STAGE);

        // Agent's heuristic: scan for OPERATOR_OVERRIDE first.
        boolean hasOverride = kinds.entrySet().stream()
                .anyMatch(e -> e.getValue() == SignalKind.OPERATOR_OVERRIDE);
        assertThat(hasOverride).isTrue();
    }

    @Test
    void signalKinds_onlyContainsTrueSignals() {
        // Healthy READY_FOR_MANUAL_PICKING PP with no pick lists, no overrides:
        // signalKinds should ONLY have entries for the true signals.
        PpStateRow s = ppOpen("READY_FOR_MANUAL_PICKING");
        WorkflowSignals out = DiagnosePickPackageTool.computeSignals(
                s, List.of(), new ReplenishmentSignal(false, List.of()),
                List.of(), batchAbsent(), packingOrderAbsent());

        // Only isReadyForManualPicking should be in kinds.
        assertThat(out.signalKinds()).containsOnlyKeys("isReadyForManualPicking");
        assertThat(out.signalKinds().get("isReadyForManualPicking")).isEqualTo(SignalKind.STAGE);
    }

    @Test
    void allKnownSignalsClassified() {
        // Every WorkflowSignals boolean field name must have an entry in
        // DiagnosePickPackageTool.SIGNAL_KINDS. CI fails if a new signal field is
        // added without classification.
        java.util.Set<String> classifiedNames = DiagnosePickPackageTool.SIGNAL_KINDS.keySet();
        java.util.List<String> declaredFields = java.util.Arrays.stream(
                        WorkflowSignals.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .filter(n -> !"derivationNotes".equals(n) && !"signalKinds".equals(n))
                .toList();
        for (String field : declaredFields) {
            assertThat(classifiedNames)
                    .as("WorkflowSignals field '%s' must have an entry in SIGNAL_KINDS", field)
                    .contains(field);
        }
    }

    @Test
    void inBatchTrueOnlyWhenBatchConsolidationFlagSet() {
        PpStateRow s = ppOpen("PARTIAL_PACKAGE");
        BatchConsolidation absent = batchAbsent();
        BatchConsolidation present = new BatchConsolidation(
                true, "uuid-123", null, null, 2, Map.of("PARTIAL_PACKAGE", 2), Map.of("OPEN", 2));

        WorkflowSignals outAbsent = DiagnosePickPackageTool.computeSignals(
                s, List.of(), new ReplenishmentSignal(false, List.of()), List.of(), absent, packingOrderAbsent());
        WorkflowSignals outPresent = DiagnosePickPackageTool.computeSignals(
                s, List.of(), new ReplenishmentSignal(false, List.of()), List.of(), present, packingOrderAbsent());

        assertThat(outAbsent.isInBatchOrWave()).isFalse();
        assertThat(outPresent.isInBatchOrWave()).isTrue();
    }

    // ─── fixtures ────────────────────────────────────────────────────────────

    private static PpStateRow ppOpen(String pickingStatus) {
        return new PpStateRow(
                /*ppId*/ 1L, /*ppCode*/ "PK/MAR-01/I-2026/1", /*status*/ 0,
                /*pickingStatus*/ pickingStatus,
                /*canceled*/ false,
                /*inProgress*/ Boolean.TRUE,
                /*shortPick*/ Boolean.FALSE,
                /*deprioritized*/ Boolean.FALSE,
                /*rejected*/ Boolean.FALSE,
                /*priorityBoosted*/ Boolean.FALSE,
                /*assignedPickerId*/ null, /*assignedPickerCode*/ null,
                /*assignedPickerDate*/ null,
                /*distributionZoneCode*/ null,
                /*batchId*/ null, /*batchType*/ null, /*waveNumber*/ null,
                /*createdDate*/ Instant.now(),
                /*updatedDate*/ Instant.now(),
                /*autoCancelDate*/ null,
                /*siteCode*/ "MAR-0000000001");
    }

    /**
     * Fixture mirroring Q23: pp.status=1 (WEIGHT_CAPTURE_PENDING),
     * picking_status=REACHED_TO_QC, rejected=true.
     */
    private static PpStateRow ppRejectedAtWeightCapture() {
        return new PpStateRow(
                /*ppId*/ 1L, /*ppCode*/ "PK/MAR-01/III-2025/32868", /*status*/ 1,
                /*pickingStatus*/ "REACHED_TO_QC",
                /*canceled*/ false,
                /*inProgress*/ Boolean.TRUE,
                /*shortPick*/ Boolean.FALSE,
                /*deprioritized*/ Boolean.FALSE,
                /*rejected*/ Boolean.TRUE,
                /*priorityBoosted*/ Boolean.FALSE,
                /*assignedPickerId*/ null, /*assignedPickerCode*/ null,
                /*assignedPickerDate*/ null,
                /*distributionZoneCode*/ null,
                /*batchId*/ null, /*batchType*/ null, /*waveNumber*/ null,
                /*createdDate*/ Instant.now(),
                /*updatedDate*/ Instant.now(),
                /*autoCancelDate*/ null,
                /*siteCode*/ "MAR-0000000001");
    }

    private static PickListAllocation openUnassignedPl(int eligibleCount, int available) {
        return new PickListAllocation(
                /*pickListId*/ 1L, /*pickListStatus*/ "OPEN",
                /*pickListLifecycleStage*/ PickListLifecycleStage.OPEN_UNCLAIMED,
                /*allottedZoneId*/ 100L, /*allottedZoneCode*/ "M4-STO-1W",
                /*pickerId*/ null, /*pickerCode*/ null, /*pickerStatus*/ null,
                /*priority*/ 0L, /*pickingPriorityLevelId*/ 1L, /*pickingPriorityPrecedence*/ 0,
                /*subLevelPriority*/ 0L, /*createdDate*/ Instant.now(),
                /*sourceAreaCodes*/ List.of("M4-STOR"),
                eligibleCount,
                new PickerStatusBreakdown(
                        available,
                        Math.max(0, eligibleCount - available), 0, 0, 0, 0, 0),
                /*queueRankAmongOpen*/ 1, /*openPickListsInZone*/ 1);
    }

    private static PickListAllocation closedPl() {
        return new PickListAllocation(
                1L, "CLOSED", PickListLifecycleStage.CLOSED,
                100L, "M4-STO-1W",
                23L, "PIC-23", "OFFLINE",
                0L, 1L, 0, 0L, Instant.now(),
                List.of("M4-STOR"),
                100, new PickerStatusBreakdown(5, 50, 45, 0, 0, 0, 0),
                null, null);
    }

    private static BatchConsolidation batchAbsent() {
        return new BatchConsolidation(false, null, null, null, 0, Map.of(), Map.of());
    }

    private static PackingOrderInfo packingOrderAbsent() {
        return new PackingOrderInfo(
                false, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static PackingOrderInfo packingOrderClaimed() {
        return new PackingOrderInfo(
                true, 100L, "PCK-001", PackingOrderLifecycleStage.CLAIMED,
                true, true, false, "PCK-1", Instant.now(),
                null, null, "GF-PSU", Instant.now(), Instant.now());
    }

    private static void assertNotesPresentForAllDerived(Map<String, String> notes) {
        assertThat(notes).isNotNull();
        for (String key : DERIVED_KEYS) {
            assertThat(notes).containsKey(key);
            assertThat(notes.get(key)).isNotBlank();
        }
    }
}
