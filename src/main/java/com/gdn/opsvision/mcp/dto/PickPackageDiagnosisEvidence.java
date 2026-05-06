package com.gdn.opsvision.mcp.dto;

import java.time.Instant;
import java.util.List;

/**
 * Evidence pack for {@code diagnosePickPackage} — single PP in, structured signals out
 * for "why isn't this PP being picked / progressing?" investigations.
 *
 * <p>Returns FACTS, not VERDICTS. The {@link WorkflowSignals} block is a flat set of
 * pre-computed booleans the agent can scan quickly; the agent composes the explanation
 * from those signals + the structured sections, not from any English label this tool
 * emits. The {@link InterpretiveHints} block surfaces plain-English meanings for the raw
 * picking_status enum so the agent can quote them when explaining to the user.
 *
 * <p>Not-found ({@code found:false}, every other field null) and an empty
 * {@code pickListAllocations} / {@code sourceAreas} list are real states, not errors.
 */
public record PickPackageDiagnosisEvidence(
        String pickPackageCode,
        boolean found,
        PickPackageState state,
        PickPackagePriority priority,
        List<PickListAllocation> pickListAllocations,
        List<SourceAreaCoverage> sourceAreas,
        ReplenishmentSignal replenishment,
        BatchConsolidation batchConsolidation,
        WorkflowSignals signals,
        InterpretiveHints hints) {

    /**
     * Section 1 — current PP state + assignment + batch/wave context.
     *
     * <p>{@code statusLabel} maps the {@code status} integer to its {@code PickPackageStatus}
     * enum name (Hibernate persists ordinal): 0=OPEN, 1=WEIGHT_CAPTURE_PENDING,
     * 2=WEIGHT_CAPTURE_DONE, 3=GIN_COMPLETE, 4=AWB_PENDING, 5=AWB_RECEIVED,
     * 6=SHIPMENT_BOOKING_FAILED, 7=ADDED_TO_SHIPMENT_REQUEST, 8=PARTIAL_GIN_COMPLETE,
     * 9=WAITING_FOR_SHIPMENT_REQUEST, 10=CANCELLATION_PENDING.
     */
    public record PickPackageState(
            long ppId,
            String ppCode,
            int status,
            String statusLabel,
            String pickingStatus,
            boolean canceled,
            Boolean inProgress,
            Boolean shortPick,
            Boolean deprioritized,
            Boolean rejected,
            Boolean priorityBoosted,
            Long assignedPickerId,
            String assignedPickerCode,
            Instant assignedPickerDate,
            String distributionZoneCode,
            String batchId,
            String batchType,
            String waveNumber,
            Instant createdDate,
            Instant updatedDate,
            Instant autoCancelDate,
            String siteCode) {
    }

    /** Section 2 — priority numbers. */
    public record PickPackagePriority(
            Integer topPriority,
            String topPriorityCode,
            Long priority,
            Long subLevelPriority,
            Boolean priorityBoosted,
            Long pickingPriorityLevelId,
            String pickingPriorityLevelName,
            Integer pickingPriorityPrecedence) {
    }

    /** Section 3 — per-pick_list allocation, eligible-picker count, queue rank. */
    public record PickListAllocation(
            long pickListId,
            String pickListStatus,
            Long allottedZoneId,
            String allottedZoneCode,
            Long pickerId,
            String pickerCode,
            String pickerStatus,
            Long priority,
            Long pickingPriorityLevelId,
            Integer pickingPriorityPrecedence,
            Long subLevelPriority,
            Instant createdDate,
            List<String> sourceAreaCodes,
            int eligiblePickerCount,
            PickerStatusBreakdown eligiblePickerStatus,
            Integer queueRankAmongOpen,
            Integer openPickListsInZone) {
    }

    /** Section 4 — source-area to zones bridge + picker pool covering those zones. */
    public record SourceAreaCoverage(
            String sourceAreaCode,
            int distinctZoneCount,
            List<String> resolvedZoneCodes,
            boolean resolvedZonesTruncated,
            int eligiblePickerCount,
            PickerStatusBreakdown eligiblePickerStatus) {
    }

    /** Section 5 — replenishment / SNA. Storage check + per-SKU deficit. */
    public record ReplenishmentSignal(
            boolean storageNotAvailable,
            List<DemandShortage> shortages) {
    }

    public record DemandShortage(
            String skuCode,
            int remainingDemand,
            Integer aggregateUnrestrictedAvailable,
            int deficit) {
    }

    /**
     * Section — batch / wave consolidation context. Surfaces sibling PPs in the same batch
     * (or wave) along with breakdowns of their {@code picking_status} and {@code status}
     * (PickPackageStatus enum). Lets the agent reason about "PP waiting for batch siblings
     * to catch up" or "this PP is the last one ahead of an otherwise complete batch".
     *
     * <p>{@code inBatch} is true when {@code pp.batch_id} or {@code pp.wave_number} is
     * non-blank. {@code siblingCount} excludes the current PP. Breakdowns are
     * insertion-order maps keyed by enum value.
     */
    public record BatchConsolidation(
            boolean inBatch,
            String batchId,
            String batchType,
            String waveNumber,
            int siblingCount,
            java.util.Map<String, Integer> siblingPickingStatusBreakdown,
            java.util.Map<String, Integer> siblingPpStatusBreakdown) {
    }

    /** Section 6 — pre-computed boolean signals derived from sections 1–5 plus batch. */
    public record WorkflowSignals(
            // picking_status booleans (pre/in/post-pick)
            boolean isCanceled,
            boolean isDeprioritized,
            boolean isRejected,
            boolean isPriorityBoosted,
            boolean isAlreadyAssigned,
            boolean isInProblemSolve,
            boolean isStorageNotAvailable,
            boolean isAwaitingPutaway,
            boolean isPriorityCalPending,
            boolean isPartialPackage,
            boolean isReadyForManualPicking,
            boolean isReachedToQc,
            boolean isPickingComplete,
            // pp.status (PickPackageStatus enum) booleans — post-pick / shipment pipeline
            boolean isWeightCapturePending,
            boolean isWeightCaptureDone,
            boolean isGinComplete,
            boolean isPartialGinComplete,
            boolean isAwbPending,
            boolean isAwbReceived,
            boolean isShipmentBookingFailed,
            boolean isAddedToShipmentRequest,
            boolean isWaitingForShipmentRequest,
            boolean isCancellationPending,
            // derived signals
            boolean hasAnyOpenPickList,
            boolean hasNoEligiblePickerForAnyOpenPickList,
            boolean hasEligiblePickersButNoneAvailable,
            boolean hasReplenishmentDeficit,
            boolean hasMultipleSourceAreas,
            boolean isInBatchOrWave) {
    }

    /** Section 7 — plain-English meaning for the current picking_status + applicable hints. */
    public record InterpretiveHints(
            String pickingStatusMeaning,
            List<String> applicableHints) {
    }

    public record PickerStatusBreakdown(
            int available,
            int busy,
            int offline,
            int breakInitiated,
            int breakRejectPicklist,
            int occupied,
            int other) {
    }
}
