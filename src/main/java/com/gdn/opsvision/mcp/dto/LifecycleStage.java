package com.gdn.opsvision.mcp.dto;

/**
 * Coarse lifecycle bucket for pick_package state values. Replaces the multi-sentence
 * {@code applicableHints} prose with a structured enum the agent can compare without
 * NL parsing. Agent can ask "is this PRE_PICK or POST_PICK?" via a single field instead
 * of reading English.
 *
 * <p>Maps both {@code PriorityCalStatus} (picking_status) and {@code PickPackageStatus}
 * (pp.status) into the same stage taxonomy. Two parallel fields are emitted on the
 * evidence pack: {@code pickingStatusStage} and {@code ppStatusStage}.
 *
 * <p>Sources of truth:
 * <ul>
 *   <li>{@code stockholm/InventoryModel/.../PriorityCalStatus.java} (13 picking_status values)</li>
 *   <li>{@code stockholm/InventoryUtilities/.../PickPackageStatus.java} (11 pp.status ordinals)</li>
 * </ul>
 *
 * <p>{@code OTHER} is the safe default for unmapped enum values — the tool falls back to
 * it rather than asserting a stage on unknown values, so adding new enum values upstream
 * doesn't silently miscategorize the PP.
 */
public enum LifecycleStage {
    /** Priority-cal pending; pick list not generated yet. */
    PRE_PICK_LIST,
    /** Pre-pick state blocked by external dependency (replenishment, putaway, config). */
    PRE_PICK_LIST_BLOCKED,
    /** Pick list generated; eligible for picker queue. */
    PICK_LIST_GENERATED,
    /** Pick list created and picking in progress (or pending picker claim). */
    IN_FLIGHT,
    /** Picking finished; pre-packing. */
    POST_PICK,
    /** PP at packing station (weight capture / GIN sub-states). */
    POST_PICK_PACKING,
    /** PP attached to outbound shipment pipeline (AWB / shipment-request). */
    POST_PICK_SHIPMENT_PIPELINE,
    /** Shipment pipeline blocked by third-party (e.g. SHIPMENT_BOOKING_FAILED). */
    SHIPMENT_BLOCKED,
    /** PP routed to manual problem-solve queue. */
    MANUAL_INTERVENTION,
    /** PP-level cancellation initiated, awaiting confirmation. */
    TERMINATING,
    /** Default state on pp.status; the picking_status stage carries the active sub-state. */
    DEFAULT,
    /** Unknown / unmapped enum value — agent should not infer stage. */
    OTHER
}
