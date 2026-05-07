package com.gdn.opsvision.mcp.dto;

import java.time.Instant;
import java.util.List;

/**
 * Evidence pack for {@code findReservationDriftHotspots} — site-wide scan that surfaces
 * every {@code warehouse_item_master} where the WIM-aggregate quantity disagrees with the
 * sum of per-bin quantities, on either ORIGINAL or RESERVED stock. The classic SCPS leak
 * signature: pick events decremented the bin-side reservation but never closed back to
 * the WIM-aggregate, leaving phantom reservations that mislead future PP creations.
 *
 * <p>Hotspots are sorted by {@code absoluteTotalDrift} (= {@code |originalDivergence| +
 * |reservedDivergence|}) DESC so the worst offenders lead.
 *
 * <p>Returns FACTS, not VERDICTS — drift may be in-flight (legitimate transient state from
 * an open reservation) or systemic (real bug). Caller drills into individual WIMs via
 * {@code findStockDiscrepancyOrigin} / {@code getStockHistoryForItem} for per-trace
 * forensics.
 */
public record ReservationDriftHotspotsEvidence(
        String siteCode,
        int totalScanned,
        int returnedCount,
        boolean truncated,
        int limit,
        int minAbsoluteDrift,
        String sinceDate,
        DriftSummary summary,
        List<DriftHotspot> hotspots) {

    /**
     * Aggregate stats across ALL WIMs at the site that meet the drift threshold (not just
     * the {@code limit}-capped hotspots list). Use these to gauge severity without
     * having to walk every row.
     */
    public record DriftSummary(
            int wimsAtOrAboveThreshold,
            long totalAbsoluteOriginalDrift,
            long totalAbsoluteReservedDrift,
            int worstAbsoluteDrift) {
    }

    /**
     * One drifted WIM. Both divergences are signed:
     * <ul>
     *   <li>{@code +N} = WIM aggregate is HIGHER than bin sum by N (typical
     *       reservation-leak pattern: WIM thinks more is reserved than bins actually
     *       hold)</li>
     *   <li>{@code -N} = WIM aggregate is LOWER than bin sum by N (less common — usually
     *       indicates bin-side increases that didn't reach the WIM rollup)</li>
     * </ul>
     *
     * <p>{@code lastActivityAt} is the most recent {@code stock_history.created_date} for
     * this WIM in the {@code [sinceDate, now)} window when {@code sinceDate} is set, or
     * lifetime-most-recent otherwise. {@code null} if the WIM has no stock_history rows.
     */
    public record DriftHotspot(
            long wimId,
            String skuCode,
            String stockIndicator,
            String physicalWarehouseCode,
            Long supplierId,
            String supplierCode,
            int aggregateOriginalQty,
            int binSumOriginalQty,
            int originalDivergence,
            int aggregateReservedQty,
            int binSumReservedQty,
            int reservedDivergence,
            int absoluteTotalDrift,
            int binCount,
            Instant lastActivityAt) {
    }
}
