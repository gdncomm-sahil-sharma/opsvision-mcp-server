package com.gdn.opsvision.mcp.dto;

import java.util.List;

/**
 * Evidence pack for {@code getInventoryForItem} — warehouse-inventory state for a single
 * (skuCode, siteCode) tuple.
 *
 * <p>Returns FACTS, not VERDICTS. Each {@link WarehouseItemMaster} row corresponds to one
 * {@code warehouse_item_master} row in the inventory DB (typically there are 1–2 rows per
 * (warehouse, item) — split by {@code stock_indicator} into {@code UNRESTRICTED} (sellable)
 * and {@code RESTRICTED}). The aggregate quantities come from
 * {@code warehouse_physical_original_stock} / {@code warehouse_physical_reserved_stock};
 * the bin breakdown comes from {@code warehouse_item_bin_master} +
 * {@code warehouse_bin_physical_original_stock} / {@code warehouse_bin_physical_reserved_stock}.
 *
 * <p>If the SKU isn't onboarded at the site, returns an evidence pack with an empty list.
 */
public record InventoryForItemEvidence(
        String skuCode,
        String siteCode,
        List<WarehouseItemMaster> warehouseItemMasters) {

    public record WarehouseItemMaster(
            long wimId,
            String stockIndicator,
            String stockType,
            Integer aggregateOriginalQty,
            Integer aggregateReservedQty,
            Integer aggregateAvailableQty,
            int binCount,
            Integer binSumOriginalQty,
            Integer binSumReservedQty,
            boolean binsTruncated,
            List<BinSnapshot> bins) {
    }

    public record BinSnapshot(
            long binMasterId,
            Long binId,
            String binCode,
            Integer originalQty,
            Integer reservedQty) {
    }
}
