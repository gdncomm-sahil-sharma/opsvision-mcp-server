package com.gdn.opsvision.mcp.dto;

import java.util.Map;

/**
 * Lifecycle stage for {@code pick_list} rows. Splits the {@code OPEN} status into
 * {@link #OPEN_UNCLAIMED} vs {@link #OPEN_CLAIMED} based on {@code picker_id}, since the
 * two states have very different operational meaning (still in the queue vs. picker has
 * taken it but not yet started).
 *
 * <p>Source: {@code stockholm/InventoryModel/.../entity/PickListStatus.java} (4 values:
 * IN_PROGRESS, COMPLETE, CLOSED, OPEN). QA2 distribution at writing: CLOSED 89k,
 * IN_PROGRESS 2.8k, COMPLETE 934, OPEN 855.
 */
public enum PickListLifecycleStage {
    OPEN_UNCLAIMED,
    OPEN_CLAIMED,
    IN_PROGRESS,
    COMPLETE,
    CLOSED,
    OTHER;

    /**
     * Resolve stage from status + pickerId. {@code pickerId} is checked when status is
     * {@code OPEN}; otherwise it's ignored (the other states are status-only).
     */
    public static PickListLifecycleStage forStatusAndPicker(String status, Long pickerId) {
        if (status == null) {
            return OTHER;
        }
        return switch (status) {
            case "OPEN" -> pickerId == null ? OPEN_UNCLAIMED : OPEN_CLAIMED;
            case "IN_PROGRESS" -> IN_PROGRESS;
            case "COMPLETE" -> COMPLETE;
            case "CLOSED" -> CLOSED;
            default -> {
                PickListLifecycleStage stage = STATUS_TO_STAGE.get(status);
                yield stage == null ? OTHER : stage;
            }
        };
    }

    /** Reserved for the rare cases where pickerId isn't available — falls back to status only. */
    public static PickListLifecycleStage forStatusOnly(String status) {
        return forStatusAndPicker(status, null);
    }

    private static final Map<String, PickListLifecycleStage> STATUS_TO_STAGE = Map.of(
            "IN_PROGRESS", IN_PROGRESS,
            "COMPLETE",    COMPLETE,
            "CLOSED",      CLOSED);
}
