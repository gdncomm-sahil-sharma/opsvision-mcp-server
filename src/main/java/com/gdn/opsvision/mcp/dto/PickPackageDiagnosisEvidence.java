package com.gdn.opsvision.mcp.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Evidence pack for {@code diagnosePickPackage} — single PP in, structured signals out
 * for "why isn't this PP being picked / progressing?" investigations.
 *
 * <p>Returns FACTS, not VERDICTS. The {@link WorkflowSignals} block is a flat set of
 * pre-computed booleans the agent can scan quickly; the agent composes the explanation
 * from those signals + the structured sections, not from any English label this tool
 * emits.
 *
 * <p>Vacuous-true suppression: derived booleans whose preconditions evaluate over
 * empty inputs (e.g. {@code hasMultipleSourceAreas} when no source areas exist) are
 * forced to {@code false} so the agent doesn't read a misleading "true" on a
 * universal-quantifier-over-empty-set. {@link WorkflowSignals#derivationNotes()} carries
 * a one-liner per derived signal explaining the inputs it considered, so a buggy
 * derivation is detectable from the evidence pack.
 *
 * <p>{@link StatusInterpretation} replaces the previous multi-sentence
 * {@code applicableHints} list with two structured fields per status:
 * {@code lifecycleStage} (enum) and {@code meaning} (one short sentence with a
 * source-file ref). The lifecycle stage is the primary triage field; meaning text is
 * for quoting when explaining to a user.
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
        PackingOrderInfo packingOrder,
        // Movement-DB state — non-CLOSED rows only, sorted most-recent first.
        // Pattern A (request stuck in HOLD) and Pattern B precursors (PENDING_CLOSED tasks)
        // are visible here directly without chaining to getMovementHistory.
        List<MovementHistoryEvidence.TaskRequest> movementTaskRequests,
        List<MovementHistoryEvidence.Task> movementTasks,
        WorkflowSignals signals,
        StatusInterpretation pickingStatusInterpretation,
        StatusInterpretation ppStatusInterpretation) {

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

    /**
     * Section 3 — per-pick_list allocation, eligible-picker count, queue rank.
     *
     * <p>{@code eligiblePickerFreshness} buckets the OFFLINE eligible pickers by
     * {@code last_login_time} so the agent can tell "stale pool, no one coming back"
     * from "everyone just stepped away, will return soon". {@code recentlyOnlinePickers}
     * is a small sample (≤5) of the most-recently-seen OFFLINE pickers — concrete names
     * the agent can quote when explaining a picker-availability bottleneck.
     */
    public record PickListAllocation(
            long pickListId,
            String pickListStatus,
            PickListLifecycleStage pickListLifecycleStage,
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
            PickerStatusFreshness eligiblePickerFreshness,
            List<PickerSnapshot> recentlyOnlinePickers,
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
            PickerStatusBreakdown eligiblePickerStatus,
            PickerStatusFreshness eligiblePickerFreshness,
            List<PickerSnapshot> recentlyOnlinePickers) {
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

    /**
     * Section 6 — pre-computed boolean signals.
     *
     * <p>Two flavors: <b>enum-equality booleans</b> (cheap, deterministic; e.g.
     * {@code isAwbPending} is just {@code pp.status==4}) and <b>derived booleans</b>
     * computed from multiple inputs. {@link #derivationNotes()} carries a one-liner per
     * derived signal explaining the inputs and the rule, so the agent can sanity-check.
     *
     * <p>Vacuous-true suppression: when a derived boolean's precondition evaluates
     * over an empty input (no zone groups → no PLs in zones → trivially "no open PLs"),
     * the boolean is forced to {@code false}. The dominant fact (e.g. "no zone groups")
     * carries the meaning instead. This avoids the universal-quantifier-over-empty-set
     * confusion where {@code isOnlyPickerForOwnZoneGroups=true} read as "they're the
     * only one" when the picker actually has no groups at all.
     */
    public record WorkflowSignals(
            // picking_status enum-equality booleans (pre/in/post-pick)
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
            // pp.status enum-equality booleans — post-pick / shipment pipeline
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
            // derived booleans — see derivationNotes for the rule each was computed under
            boolean hasAnyOpenPickList,
            boolean hasNoEligiblePickerForAnyOpenPickList,
            boolean hasEligiblePickersButNoneAvailable,
            boolean hasReplenishmentDeficit,
            boolean hasMultipleSourceAreas,
            boolean isInBatchOrWave,
            // Pattern C downstream confirmation: PP picking finished but no packing_order
            // ever created (or it was hard-deleted). True iff picking_status is post-pick
            // (REACHED_TO_QC / PICKING_COMPLETE) AND packingOrder.present is false.
            boolean packingOrderMissing,
            // Movement-DB existence checks. The agent reads movementTaskRequests /
            // movementTasks lists for state-level detail (lifecycleStage); these signals
            // are convenience flags so the agent can quickly tell whether the WCS layer
            // has any open work for this PP at all. Pattern A is detectable via the
            // combination of hasOpenTaskRequest=true AND hasOpenMovementTask=false (a
            // request was created but never spawned children) — the agent composes that.
            boolean hasOpenTaskRequest,
            boolean hasOpenMovementTask,
            boolean hasFailedMovementTask,
            // per-derived-signal one-liner: input fields considered + rule applied
            Map<String, String> derivationNotes,
            // Per-signal classification — only entries for signals that are TRUE in this
            // response are emitted. Encodes the dominance hierarchy structurally so the
            // agent can lead with OPERATOR_OVERRIDE before BLOCKER_* before STAGE before
            // CONTEXT, instead of guessing from a flat boolean dict.
            Map<String, SignalKind> signalKinds) {
    }

    /**
     * Replaces the previous multi-sentence {@code applicableHints} list. One short
     * meaning + a structured stage + a source-file reference per status. Two
     * StatusInterpretation fields are emitted on the evidence pack: one for
     * {@code picking_status} (PriorityCalStatus) and one for {@code pp.status}
     * (PickPackageStatus).
     */
    public record StatusInterpretation(
            String label,
            LifecycleStage lifecycleStage,
            String meaning,
            String sourceRef) {
    }

    /**
     * Active {@code packing_order} for the PP, or {@code present:false} if no row exists.
     *
     * <p>Pattern C confirmation: a PP whose {@code picking_status} is in the post-pick
     * stages (REACHED_TO_QC, PICKING_COMPLETE) but with {@code present:false} here is
     * stuck — picking finished but the packing-handoff service never ran. The
     * {@code wcsConsolidationStuck} boolean on the related {@link PickPackageEvidence.HandlingUnit}
     * is the proximate cause; this {@code packingOrder.present:false} is the downstream
     * confirmation.
     *
     * <p>{@link PackingOrderLifecycleStage} is derived from
     * {@code (active, claimed_date, good_issued_note)} per PackingService.java transitions.
     */
    public record PackingOrderInfo(
            boolean present,
            Long packingOrderId,
            String packingOrderCode,
            PackingOrderLifecycleStage lifecycleStage,
            Boolean active,
            Boolean hasAwbInfo,
            Boolean hasGoodIssuedNote,
            String claimedBy,
            Instant claimedDate,
            String reClaimedBy,
            Instant reClaimedDate,
            String workZoneCode,
            Instant createdDate,
            Instant lastModifiedDate) {
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

    /**
     * OFFLINE-eligible-picker freshness, bucketed by {@code now − last_login_time}. Lets
     * the agent estimate near-term recovery: fresh buckets imply pickers on a break or
     * brief log-out who'll likely return soon; older buckets imply a stale pool.
     *
     * <p>Buckets are non-overlapping and sum to the OFFLINE count in
     * {@link PickerStatusBreakdown#offline()}. Pickers with no recorded
     * {@code last_login_time} fall into {@code unknown}.
     */
    public record PickerStatusFreshness(
            int offlineWithin15Min,
            int offlineWithin1Hr,
            int offlineWithin1Day,
            int offlineOlder,
            int offlineUnknown) {
    }

    /**
     * One picker entry for the {@code recentlyOnlinePickers} sample. Pre-computed
     * {@code minutesSinceLastLogin} saves the agent a clock-arithmetic step when
     * generating prose.
     */
    public record PickerSnapshot(
            String pickerCode,
            String status,
            Instant lastLoginTime,
            Long minutesSinceLastLogin) {
    }
}
