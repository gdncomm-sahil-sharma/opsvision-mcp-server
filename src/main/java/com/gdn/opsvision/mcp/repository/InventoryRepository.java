package com.gdn.opsvision.mcp.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.gdn.opsvision.mcp.dto.InventoryForItemEvidence.BinSnapshot;
import com.gdn.opsvision.mcp.dto.InventoryForItemEvidence.WarehouseItemMaster;

/**
 * Reads warehouse-inventory state for a (skuCode, siteCode) pair.
 *
 * <p>The query is split in two — one for {@code warehouse_item_master} + aggregate stock
 * scalars (1–2 rows per WIM), then one for bin-level snapshots (potentially fan-out to
 * dozens of bins). Stitched in Java to keep each SQL clean and the bin list explicitly
 * truncatable.
 */
@Repository
@Transactional(readOnly = true)
public class InventoryRepository {

    /**
     * Cap per WIM. A few SKUs at MAR have 100+ bins; surface a {@code binsTruncated=true} flag
     * rather than returning a huge JSON the agent has to wade through.
     */
    private static final int MAX_BINS_PER_WIM = 50;

    private final JdbcClient inventory;

    public InventoryRepository(@Qualifier("inventoryJdbcClient") JdbcClient inventory) {
        this.inventory = inventory;
    }

    /**
     * Fetch all WIM rows + aggregate quantities + capped bin breakdown for a SKU at a site.
     * Returns an empty list if the SKU isn't onboarded at the site (no WIM row).
     */
    public List<WarehouseItemMaster> findStockForItem(String skuCode, String siteCode) {
        List<WimRollup> rollups = inventory.sql("""
                        SELECT wim.id AS wim_id,
                               wim.stock_indicator,
                               wim.stock_type,
                               pos.quantity AS aggregate_original_qty,
                               prs.quantity AS aggregate_reserved_qty
                        FROM warehouse_item_master wim
                        JOIN warehouse w ON w.id = wim.warehouse
                        JOIN item i ON i.id = wim.item
                        LEFT JOIN warehouse_physical_original_stock pos ON pos.warehouse_item_master = wim.id
                        LEFT JOIN warehouse_physical_reserved_stock  prs ON prs.warehouse_item_master = wim.id
                        WHERE w.code = :site AND i.code = :sku
                        ORDER BY wim.id
                        """)
                .param("site", siteCode)
                .param("sku", skuCode)
                .query(WimRollup.class)
                .list();

        if (rollups.isEmpty()) {
            return List.of();
        }

        List<Long> wimIds = rollups.stream().map(WimRollup::wimId).toList();
        Map<Long, List<BinRow>> binsByWim = fetchBinsForWims(wimIds);

        List<WarehouseItemMaster> out = new ArrayList<>(rollups.size());
        for (WimRollup r : rollups) {
            List<BinRow> allBins = binsByWim.getOrDefault(r.wimId(), List.of());
            int binCount = allBins.size();
            int binSumOrig = sumNullable(allBins, BinRow::originalQty);
            int binSumResv = sumNullable(allBins, BinRow::reservedQty);
            boolean truncated = binCount > MAX_BINS_PER_WIM;
            List<BinRow> capped = truncated ? allBins.subList(0, MAX_BINS_PER_WIM) : allBins;
            List<BinSnapshot> bins = capped.stream()
                    .map(b -> new BinSnapshot(b.binMasterId(), b.binId(), b.binCode(),
                            b.originalQty(), b.reservedQty()))
                    .toList();
            Integer aggOrig = r.aggregateOriginalQty();
            Integer aggResv = r.aggregateReservedQty();
            Integer aggAvailable = aggOrig == null ? null
                    : aggOrig - (aggResv == null ? 0 : aggResv);
            out.add(new WarehouseItemMaster(
                    r.wimId(),
                    r.stockIndicator(),
                    r.stockType(),
                    aggOrig,
                    aggResv,
                    aggAvailable,
                    binCount,
                    binSumOrig,
                    binSumResv,
                    truncated,
                    bins));
        }
        return out;
    }

    private static int sumNullable(List<BinRow> rows,
            java.util.function.Function<BinRow, Integer> getter) {
        int s = 0;
        for (BinRow r : rows) {
            Integer v = getter.apply(r);
            if (v != null) {
                s += v;
            }
        }
        return s;
    }

    private Map<Long, List<BinRow>> fetchBinsForWims(List<Long> wimIds) {
        if (wimIds.isEmpty()) {
            return Map.of();
        }
        List<BinRow> rows = inventory.sql("""
                        SELECT wibm.warehouse_item_master AS wim_id,
                               wibm.id AS bin_master_id,
                               b.id AS bin_id,
                               b.code AS bin_code,
                               bos.quantity AS original_qty,
                               brs.quantity AS reserved_qty
                        FROM warehouse_item_bin_master wibm
                        JOIN bin b ON b.id = wibm.bin
                        LEFT JOIN warehouse_bin_physical_original_stock bos ON bos.warehouse_item_bin_master = wibm.id
                        LEFT JOIN warehouse_bin_physical_reserved_stock  brs ON brs.warehouse_item_bin_master = wibm.id
                        WHERE wibm.warehouse_item_master IN (:wimIds)
                        ORDER BY wibm.warehouse_item_master, wibm.id
                        """)
                .param("wimIds", wimIds)
                .query(BinRow.class)
                .list();
        Map<Long, List<BinRow>> byWim = new LinkedHashMap<>();
        for (BinRow r : rows) {
            byWim.computeIfAbsent(r.wimId(), k -> new ArrayList<>()).add(r);
        }
        return byWim;
    }

    // ─── private row records (JdbcClient row-class binding) ──────────────────

    private record WimRollup(
            long wimId,
            String stockIndicator,
            String stockType,
            Integer aggregateOriginalQty,
            Integer aggregateReservedQty) {
    }

    private record BinRow(
            long wimId,
            long binMasterId,
            Long binId,
            String binCode,
            Integer originalQty,
            Integer reservedQty) {
    }
}
