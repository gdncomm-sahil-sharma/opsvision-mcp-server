package com.gdn.opsvision.mcp.dto;

/**
 * Coarse bucket for picker.status values. Replaces multi-sentence prose with a
 * structured enum the agent can scan without NL parsing.
 *
 * <p>Source: {@code stockholm/InventoryUtilities/.../PickerStatus.java} (6 values:
 * AVAILABLE, BUSY, OFFLINE, BREAK_INITIATED, BREAK_REJECT_PICKLIST, OCCUPIED).
 */
public enum PickerOperationalStage {
    /** Logged in, accepting work. */
    READY,
    /** Already engaged on a pick_list. */
    ENGAGED,
    /** Not logged in. */
    NOT_LOGGED_IN,
    /** On a break (BREAK_INITIATED or BREAK_REJECT_PICKLIST). */
    ON_BREAK,
    /** Engaged on a non-picking activity (OCCUPIED). */
    ENGAGED_NON_PICKING,
    /** Unknown / unmapped value. */
    OTHER
}
