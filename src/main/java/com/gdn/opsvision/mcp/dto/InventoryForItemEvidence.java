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
 * <h2>Stock indicator semantics</h2>
 *
 * <p>Warehouse-inventory stores stock under the parent (distribution) warehouse code,
 * tagged with a {@code stock_indicator}: {@code UNRESTRICTED} for stock physically in
 * the distribution warehouse, {@code RESTRICTED} for stock physically held in the
 * paired non-distribution / damage warehouse. The pairing lives in stockholm's
 * {@code warehouse_defect_map} table; the translation is performed by
 * {@code stockholm/.../WarehouseService.getStockIndicatorForWarehouse} (lines 634–644)
 * before any {@code UpdateStockWebRequest} is sent to warehouse-inventory.
 *
 * <p>For Marunda the pairing is {@code MAR-0000000001 ("BKI N - Marunda")} ↔
 * {@code MAN-0000000002 ("Marunda Non Dist")}. So a row with
 * {@code stock_indicator=RESTRICTED} under {@code siteCode=MAR-0000000001} represents
 * stock physically at {@code MAN-0000000002}; the {@link WarehouseItemMaster#physicalWarehouseCode}
 * field on each row resolves this for the agent.
 *
 * <p>Picking always filters {@code stock_indicator=UNRESTRICTED} (see
 * {@code PickingBinSelectionHelper.java:136}), so RESTRICTED stock is surfaced for
 * completeness but never participates in pick-list allocation.
 *
 * <p>If the SKU isn't onboarded at the site, returns an evidence pack with an empty list.
 */
public record InventoryForItemEvidence(
        String skuCode,
        String siteCode,
        List<WarehouseItemMaster> warehouseItemMasters) {

    /**
     * One {@code warehouse_item_master} row + its aggregate quantities + a capped bin
     * snapshot list.
     *
     * <p>{@code physicalWarehouseCode} is the actual stockholm warehouse where the stock
     * sits: equal to the input {@code siteCode} for UNRESTRICTED rows, equal to the paired
     * defect warehouse code (per {@code stockholm.warehouse_defect_map}) for RESTRICTED
     * rows. Falls back to {@code siteCode} if a RESTRICTED row exists but no defect
     * mapping is configured (unexpected — surfaces the row rather than dropping it).
     */
    public record WarehouseItemMaster(
            long wimId,
            String stockIndicator,
            String physicalWarehouseCode,
            String stockType,
            Long supplierId,
            String supplierCode,
            String supplierName,
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
