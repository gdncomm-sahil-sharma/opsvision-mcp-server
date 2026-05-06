package com.gdn.opsvision.mcp.dto;

import java.util.Map;

/**
 * Lifecycle stage for a {@code pick_package_handling_units} row. Maps the underlying
 * {@code PickPackageHandlingUnitStatus} enum (5 values) directly — no coalescing — since
 * each value is operationally distinct.
 *
 * <p>Stages, in normal flow order:
 * <ol>
 *   <li>{@link #OPEN} — HU created, picking not yet started</li>
 *   <li>{@link #SCANNED} — HU scanned by picker but no items yet picked into it</li>
 *   <li>{@link #PARTIALLY_PICKED} — picker has placed some items; more remain</li>
 *   <li>{@link #PICKING_COMPLETE} — all items picked; awaiting downstream packing/QC</li>
 *   <li>{@link #CLOSED} — packing handoff complete; HU closed for the PP</li>
 * </ol>
 *
 * <p>Pattern C fingerprint (WCS-consolidation packing-handoff failure): HU stays at
 * {@link #PICKING_COMPLETE} indefinitely without advancing to {@link #CLOSED}, with
 * {@code selected_for_packing} NULL/false and {@code automation='WCS'}.
 *
 * <p>Source: {@code stockholm/InventoryUtilities/.../type/PickPackageHandlingUnitStatus.java}.
 */
public enum HandlingUnitLifecycleStage {
    OPEN,
    SCANNED,
    PARTIALLY_PICKED,
    PICKING_COMPLETE,
    CLOSED,
    OTHER;

    public static HandlingUnitLifecycleStage forStatus(String status) {
        if (status == null) {
            return OTHER;
        }
        HandlingUnitLifecycleStage stage = STATUS_TO_STAGE.get(status);
        return stage == null ? OTHER : stage;
    }

    private static final Map<String, HandlingUnitLifecycleStage> STATUS_TO_STAGE = Map.of(
            "OPEN",             OPEN,
            "SCANNED",          SCANNED,
            "PARTIALLY_PICKED", PARTIALLY_PICKED,
            "PICKING_COMPLETE", PICKING_COMPLETE,
            "CLOSED",           CLOSED);
}
