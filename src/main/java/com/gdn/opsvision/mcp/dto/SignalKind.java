package com.gdn.opsvision.mcp.dto;

/**
 * Classification of a workflow signal by what kind of fact it represents. Encodes the
 * dominance hierarchy structurally — the agent reads {@link #OPERATOR_OVERRIDE} signals
 * before {@link #BLOCKER_EXTERNAL} / {@link #BLOCKER_INTERNAL}, before {@link #STAGE},
 * before {@link #CONTEXT}.
 *
 * <p>Solves the "rejected=true buried under post-pick state" problem (validation Q23):
 * an agent scanning a flat boolean dict has no way to know which of N true signals is
 * the headline. With kinds, the heuristic becomes deterministic: lead with operator
 * overrides if any are true, otherwise the most specific blocker, otherwise the
 * current stage, otherwise context.
 *
 * <p>Kinds are <b>structural</b>, not opinion. {@code rejected=true} means an operator
 * pressed a button to remove the PP from the queue — that's an operator-controlled
 * fact regardless of audience. Same for {@code canceled} and {@code deprioritized}.
 *
 * <p>Per-signal kind classification lives in static maps in
 * {@code DiagnosePickPackageTool} and {@code DiagnosePickerQueueTool}. Tests assert
 * every known signal field has a kind, so adding a new signal without classifying it
 * fails CI.
 */
public enum SignalKind {
    /**
     * Explicit human / operator-set state that overrides normal flow.
     * Examples: {@code isCanceled}, {@code isRejected}, {@code isDeprioritized},
     * {@code isAlreadyAssigned}, {@code isInactive} (picker disabled),
     * {@code isDeleted} (picker soft-deleted), {@code isCancellationPending}.
     * <p>Highest priority — if any OPERATOR_OVERRIDE signal is true, that's the headline.
     */
    OPERATOR_OVERRIDE,

    /**
     * Active impediment to progress originating outside the warehouse system.
     * Examples: {@code isStorageNotAvailable} (replenishment pending),
     * {@code isAwbPending} (logistics provider), {@code isShipmentBookingFailed},
     * {@code isAwaitingPutaway}.
     * <p>Second priority — if no OPERATOR_OVERRIDE, an EXTERNAL blocker is usually
     * the next-most-informative thing to lead with.
     */
    BLOCKER_EXTERNAL,

    /**
     * Active impediment originating inside the warehouse system or its config.
     * Examples: {@code isInProblemSolve}, {@code hasReplenishmentDeficit},
     * {@code hasNoEligiblePickerForAnyOpenPickList} (config gap),
     * {@code packingOrderMissing} (Pattern C downstream),
     * {@code hasZoneGroupsButNoZones} (picker config gap).
     */
    BLOCKER_INTERNAL,

    /**
     * Current lifecycle position. Useful for narrating where the PP / picker is
     * but not a verdict by itself.
     * Examples: {@code isReachedToQc}, {@code isPickingComplete},
     * {@code isWeightCapturePending}, {@code pickerStatusAvailable},
     * {@code pickerStatusBusy}, {@code pickerStatusOnBreak}.
     */
    STAGE,

    /**
     * Supplementary information; doesn't drive a verdict on its own.
     * Examples: {@code hasMultipleSourceAreas}, {@code isInBatchOrWave},
     * {@code hasAnyOpenPickList}, {@code openPickListsExistInPickerZones},
     * {@code siblingPickersAllNonAvailable}.
     */
    CONTEXT
}
