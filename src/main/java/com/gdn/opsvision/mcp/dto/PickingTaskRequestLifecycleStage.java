package com.gdn.opsvision.mcp.dto;

import java.util.Map;

/**
 * Lifecycle stage for {@code picking_task_request.status} (movement DB).
 *
 * <p>Source: {@code MovementTaskRequestStatus.java} (warehouse-stock-movement-enum module).
 * 6 documented values: {@code CREATED, HOLD, RELEASED, IN_PROGRESS, CLOSED, BATCH_CAL_PENDING}.
 *
 * <p>Pattern A (half-applied reservation) fingerprint: request stuck at
 * {@link #BLOCKED_HOLD} indefinitely — aggregate reservation fired but bin reservation
 * never did, so the request can't anchor and stays in HOLD.
 */
public enum PickingTaskRequestLifecycleStage {
    /** Request created; waiting for release. */
    AWAITING_RELEASE,
    /** Released to WCS; ready for batch calculation / task generation. */
    READY,
    /** Active: tasks have been generated and are being picked. */
    IN_PROGRESS,
    /** Transient: batch-calculation pending (multi-SKU batch grouping in flight). */
    BATCH_CAL_PENDING,
    /** Stuck on HOLD — Pattern A signal source. */
    BLOCKED_HOLD,
    /** Terminal: request closed. */
    CLOSED,
    /** Unmapped value — surfaces enum extensions. */
    OTHER;

    public static PickingTaskRequestLifecycleStage forStatus(String status) {
        if (status == null) {
            return OTHER;
        }
        PickingTaskRequestLifecycleStage stage = STATUS_TO_STAGE.get(status);
        return stage == null ? OTHER : stage;
    }

    private static final Map<String, PickingTaskRequestLifecycleStage> STATUS_TO_STAGE = Map.of(
            "CREATED",            AWAITING_RELEASE,
            "RELEASED",           READY,
            "IN_PROGRESS",        IN_PROGRESS,
            "BATCH_CAL_PENDING",  BATCH_CAL_PENDING,
            "HOLD",               BLOCKED_HOLD,
            "CLOSED",             CLOSED);
}
