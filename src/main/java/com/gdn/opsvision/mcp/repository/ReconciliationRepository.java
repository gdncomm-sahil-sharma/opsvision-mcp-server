package com.gdn.opsvision.mcp.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.gdn.opsvision.mcp.dto.InventoryForItemEvidence.WarehouseItemMaster;
import com.gdn.opsvision.mcp.dto.InventoryReservationReconciliation.Divergences;
import com.gdn.opsvision.mcp.dto.InventoryReservationReconciliation.ItemReconciliation;
import com.gdn.opsvision.mcp.dto.InventoryReservationReconciliation.StockholmDemand;

/**
 * Cross-DB orchestrator for {@code reconcileInventoryVsReservation}.
 *
 * <p>For a given pick package:
 * <ol>
 *   <li>Resolves the package's {@code siteCode} via {@code sales_order.warehouse → warehouse.code}
 *       (stockholm). {@code pick_package} itself has no warehouse FK.</li>
 *   <li>Pulls every {@code picking_item} in the package's SOs, joined to
 *       {@code warehouse_item → item} so each row carries its SKU code, required qty,
 *       picked qty, and the picking_item / sales_order id (stockholm).</li>
 *   <li>Groups picking_items by SKU and, per SKU, calls
 *       {@link InventoryRepository#findStockForItem(String, String)} to fetch the
 *       inventory-DB state. Per-SKU divergences are computed in Java.</li>
 * </ol>
 *
 * <p>Returns FACTS, not VERDICTS. Negative {@code availableMinusRemaining} is just an
 * arithmetic difference — the agent decides what it means.
 */
@Repository
@Transactional(readOnly = true)
public class ReconciliationRepository {

    private final JdbcClient stockholm;
    private final InventoryRepository inventoryRepo;

    public ReconciliationRepository(
            @Qualifier("stockholmJdbcClient") JdbcClient stockholm,
            InventoryRepository inventoryRepo) {
        this.stockholm = stockholm;
        this.inventoryRepo = inventoryRepo;
    }

    /** Resolve siteCode from PP via the SO's warehouse FK. Returns empty if PP has no SOs. */
    public Optional<String> findSiteCode(long ppId) {
        return stockholm.sql("""
                        SELECT DISTINCT w.code
                        FROM sales_order so
                        JOIN warehouse w ON w.id = so.warehouse
                        WHERE so.pick_package_id = :pp
                        """)
                .param("pp", ppId)
                .query(String.class)
                .optional();
    }

    /** Run the full reconciliation. Caller has already resolved {@code ppId}. */
    public List<ItemReconciliation> reconcile(long ppId, String siteCode) {
        List<DemandRow> rows = stockholm.sql("""
                        SELECT pi.id AS picking_item_id,
                               pi.sales_order AS sales_order_id,
                               pi.stock_trace_id,
                               pi.quantity AS required_qty,
                               COALESCE(pi.current_picked_quantity, 0) AS picked_qty,
                               i.code AS sku_code
                        FROM picking_item pi
                        JOIN sales_order so ON so.id = pi.sales_order
                        LEFT JOIN warehouse_item wi ON wi.id = pi.warehouse_item
                        LEFT JOIN item i ON i.id = wi.item
                        WHERE so.pick_package_id = :pp
                        ORDER BY pi.id
                        """)
                .param("pp", ppId)
                .query(DemandRow.class)
                .list();

        Map<String, List<DemandRow>> bySku = new LinkedHashMap<>();
        for (DemandRow r : rows) {
            String key = r.skuCode() == null ? "" : r.skuCode();
            bySku.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }

        List<ItemReconciliation> out = new ArrayList<>(bySku.size());
        for (Map.Entry<String, List<DemandRow>> e : bySku.entrySet()) {
            String sku = e.getKey();
            List<DemandRow> group = e.getValue();
            List<Long> pickingItemIds = group.stream().map(DemandRow::pickingItemId).toList();
            List<Long> salesOrderIds = group.stream()
                    .map(DemandRow::salesOrderId).distinct().toList();
            List<String> stockTraceIds = group.stream()
                    .map(DemandRow::stockTraceId)
                    .filter(s -> s != null && !s.isBlank())
                    .distinct()
                    .toList();
            int required = group.stream().mapToInt(DemandRow::requiredQty).sum();
            int picked = group.stream().mapToInt(DemandRow::pickedQty).sum();
            int remaining = required - picked;

            List<WarehouseItemMaster> inventory = sku.isEmpty()
                    ? List.of()
                    : inventoryRepo.findStockForItem(sku, siteCode);

            int unrestrictedAvailable = inventory.stream()
                    .filter(w -> "UNRESTRICTED".equalsIgnoreCase(w.stockIndicator()))
                    .mapToInt(w -> w.aggregateAvailableQty() == null ? 0 : w.aggregateAvailableQty())
                    .sum();
            int aggOrig = inventory.stream()
                    .mapToInt(w -> w.aggregateOriginalQty() == null ? 0 : w.aggregateOriginalQty())
                    .sum();
            int aggResv = inventory.stream()
                    .mapToInt(w -> w.aggregateReservedQty() == null ? 0 : w.aggregateReservedQty())
                    .sum();
            int binSumOrig = inventory.stream()
                    .mapToInt(w -> w.binSumOriginalQty() == null ? 0 : w.binSumOriginalQty())
                    .sum();
            int binSumResv = inventory.stream()
                    .mapToInt(w -> w.binSumReservedQty() == null ? 0 : w.binSumReservedQty())
                    .sum();

            Divergences div = new Divergences(
                    unrestrictedAvailable,
                    unrestrictedAvailable - remaining,
                    aggOrig - binSumOrig,
                    aggResv - binSumResv);

            out.add(new ItemReconciliation(
                    sku.isEmpty() ? null : sku,
                    new StockholmDemand(pickingItemIds, salesOrderIds, stockTraceIds,
                            required, picked, remaining),
                    inventory,
                    div));
        }
        return out;
    }

    private record DemandRow(
            long pickingItemId,
            long salesOrderId,
            String stockTraceId,
            int requiredQty,
            int pickedQty,
            String skuCode) {
    }
}
