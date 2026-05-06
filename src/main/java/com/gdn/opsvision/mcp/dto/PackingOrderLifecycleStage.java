package com.gdn.opsvision.mcp.dto;

import java.time.Instant;

/**
 * Derived lifecycle stage for {@code packing_order}. There's no formal status enum on
 * the entity — the stage is computed from {@code (active, claimed_date, good_issued_note)}
 * per the transitions in {@code stockholm/InventoryService/.../PackingService.java}.
 *
 * <p>Verified transitions:
 * <ol>
 *   <li>{@link #CREATED} — {@code active=true}, no {@code claimed_date}, no {@code good_issued_note}.
 *       Set when {@code PackingService.checkAndCreateQCListForSO/IR()} runs.</li>
 *   <li>{@link #CLAIMED} — {@code active=true}, {@code claimed_date} populated, no GIN.
 *       Set when a packer claims the order.</li>
 *   <li>{@link #GIN_ISSUED} — {@code active=true}, {@code good_issued_note} populated.
 *       Set when GIN is issued (final outbound document).</li>
 *   <li>{@link #INACTIVE} — {@code active=false}. Soft-delete state (rare in practice;
 *       packing_orders are usually hard-deleted via {@code repo.delete()} rather than
 *       deactivated).</li>
 * </ol>
 *
 * <p>QA2 distribution at MAR (active rows only, snapshot writing): CREATED=14,367,
 * CLAIMED=13,973, GIN_ISSUED=3,066.
 *
 * <p>{@link #OTHER} for unmappable input (e.g. malformed combinations). The derivation
 * order is GIN_ISSUED &gt; CLAIMED &gt; CREATED — a row that has both claimed_date and
 * good_issued_note resolves to GIN_ISSUED.
 */
public enum PackingOrderLifecycleStage {
    CREATED,
    CLAIMED,
    GIN_ISSUED,
    INACTIVE,
    OTHER;

    public static PackingOrderLifecycleStage forPackingOrder(
            boolean active, Instant claimedDate, Long goodIssuedNoteId) {
        if (!active) {
            return INACTIVE;
        }
        if (goodIssuedNoteId != null) {
            return GIN_ISSUED;
        }
        if (claimedDate != null) {
            return CLAIMED;
        }
        return CREATED;
    }
}
