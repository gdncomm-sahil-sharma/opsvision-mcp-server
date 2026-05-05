package com.gdn.opsvision.mcp.dto;

import java.util.List;

/**
 * Evidence pack for {@code reconcileInventoryVsReservation} — per-SKU comparison of stockholm
 * picking demand against warehouse-inventory stock + reservation, for every item in a pick package.
 *
 * <p>Returns FACTS, not VERDICTS. The {@code divergences} block exposes signed differences
 * (e.g. {@code availableMinusRemaining < 0} means unrestricted available stock is below the
 * remaining picking demand) — but the agent decides whether a divergence is a bug, an
 * in-flight reservation, a snapshot/live drift, or expected. Multiple WIMs per SKU are
 * possible (one per {@code stock_indicator}); only {@code UNRESTRICTED} contributes to
 * {@code aggregateUnrestrictedAvailable}.
 */
public record InventoryReservationReconciliation(
        PickPackageEvidence.Header pickPackage,
        String siteCode,
        List<ItemReconciliation> items) {

    public record ItemReconciliation(
            String skuCode,
            StockholmDemand stockholm,
            List<InventoryForItemEvidence.WarehouseItemMaster> inventory,
            Divergences divergences) {
    }

    public record StockholmDemand(
            List<Long> pickingItemIds,
            List<Long> salesOrderIds,
            int requiredQty,
            int pickedQty,
            int remainingQty) {
    }

    public record Divergences(
            int aggregateUnrestrictedAvailable,
            int availableMinusRemaining,
            int aggregateVsBinSumOriginal,
            int aggregateVsBinSumReserved) {
    }
}
