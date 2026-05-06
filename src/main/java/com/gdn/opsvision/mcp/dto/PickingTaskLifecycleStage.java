package com.gdn.opsvision.mcp.dto;

import java.util.Map;

/**
 * Lifecycle stage for the movement-DB {@code picking_task.status} (the WCS-side state
 * machine, distinct from stockholm's {@code picking_task} table).
 *
 * <p>Source: {@code com.gdn.warehouse.enums.warehouse.stock.movement.MovementTaskStatus}
 * (a published artifact, not in this repo's source tree). Values discovered via
 * grep across the {@code warehouse-stock-movement} command-impl module:
 * {@code OPEN}, {@code IN_PROGRESS}, {@code RESERVED}, {@code TASK_LIST_GENERATION_IN_PROGRESS},
 * {@code PENDING_CLOSED}, {@code CLOSED}, {@code STUCK}.
 *
 * <p>Pattern B (WCS phantom-close) signal source: a task that goes
 * {@code PENDING_CLOSED → CLOSED} without producing {@code DECREASE_BIN_*} events on its
 * stock_trace_id. The {@link #PENDING_CLOSED} stage flags the in-flight closure.
 */
public enum PickingTaskLifecycleStage {
    /** Task awaiting picker / WCS work. */
    AWAITING,
    /** Bin has been reserved for the task; waiting to start picking. */
    RESERVED,
    /** Task is being actively picked. */
    IN_PROGRESS,
    /** Task list is still being generated; transient setup state. */
    SETUP,
    /** Closing in progress — the {@code PENDING_CLOSED → CLOSED} transition is the Pattern B trigger. */
    CLOSING,
    /** Terminal: task closed normally. */
    CLOSED,
    /** Explicit failure: task got stuck. */
    STUCK,
    /** Unmapped value — surfaces enum extensions. */
    OTHER;

    public static PickingTaskLifecycleStage forStatus(String status) {
        if (status == null) {
            return OTHER;
        }
        PickingTaskLifecycleStage stage = STATUS_TO_STAGE.get(status);
        return stage == null ? OTHER : stage;
    }

    private static final Map<String, PickingTaskLifecycleStage> STATUS_TO_STAGE = Map.of(
            "OPEN",                              AWAITING,
            "RESERVED",                          RESERVED,
            "IN_PROGRESS",                       IN_PROGRESS,
            "TASK_LIST_GENERATION_IN_PROGRESS",  SETUP,
            "PENDING_CLOSED",                    CLOSING,
            "CLOSED",                            CLOSED,
            "STUCK",                             STUCK);
}
