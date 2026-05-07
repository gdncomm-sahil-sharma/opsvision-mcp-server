package com.gdn.opsvision.mcp.repository;

import com.gdn.opsvision.mcp.repository.support.RecordRowMapper;
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
 *       {@link InventoryRepository#findStockForItem(String, String, String)} to fetch the
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
    private final WarehouseDefectMapRepository defectMapRepo;

    public ReconciliationRepository(
            @Qualifier("stockholmJdbcClient") JdbcClient stockholm,
            InventoryRepository inventoryRepo,
            WarehouseDefectMapRepository defectMapRepo) {
        this.stockholm = stockholm;
        this.inventoryRepo = inventoryRepo;
        this.defectMapRepo = defectMapRepo;
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

    /**
     * Run the full reconciliation. Demand rows are grouped by ({@code skuCode},
     * {@code supplierCode}) — for {@code CONSIGNMENT_TRADING} SKUs the same SKU at the
     * same site can have multiple supplier-distinct WIMs in inventory and multiple
     * supplier-distinct picking_items in stockholm; lumping them by SKU only would
     * mismatch demand against inventory. {@code TRADING} demand rows have a null
     * stockholm warehouse_item.supplier and reconcile against every WIM for the SKU
     * (no supplier filter).
     */
    public List<ItemReconciliation> reconcile(long ppId, String siteCode) {
        List<DemandRow> rows = stockholm.sql("""
                        SELECT pi.id AS picking_item_id,
                               pi.sales_order AS sales_order_id,
                               pi.stock_trace_id,
                               pi.quantity AS required_qty,
                               COALESCE(pi.current_picked_quantity, 0) AS picked_qty,
                               i.code AS sku_code,
                               wi.supplier AS supplier_id,
                               s.code      AS supplier_code
                        FROM picking_item pi
                        JOIN sales_order so ON so.id = pi.sales_order
                        LEFT JOIN warehouse_item wi ON wi.id = pi.warehouse_item
                        LEFT JOIN item i ON i.id = wi.item
                        LEFT JOIN supplier s ON s.id = wi.supplier
                        WHERE so.pick_package_id = :pp
                        ORDER BY pi.id
                        """)
                .param("pp", ppId)
                .query(RecordRowMapper.of(DemandRow.class))
                .list();

        // Group key = (skuCode, supplierCode). Empty string used as null sentinel for the
        // map key (LinkedHashMap can't handle null keys with the helpers used below).
        Map<GroupKey, List<DemandRow>> byKey = new LinkedHashMap<>();
        for (DemandRow r : rows) {
            String sku = r.skuCode() == null ? "" : r.skuCode();
            String supplier = r.supplierCode();   // null is fine here, just used in lookup, not as map key
            byKey.computeIfAbsent(new GroupKey(sku, supplier == null ? "" : supplier),
                    k -> new ArrayList<>()).add(r);
        }

        // Defect sibling is stable per site; resolve once and reuse across SKUs.
        String restrictedSibling = defectMapRepo.defectCodeFor(siteCode).orElse(null);
        List<ItemReconciliation> out = new ArrayList<>(byKey.size());
        for (Map.Entry<GroupKey, List<DemandRow>> e : byKey.entrySet()) {
            String sku = e.getKey().sku();
            String supplierCode = e.getKey().supplierCode().isEmpty() ? null : e.getKey().supplierCode();
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
            // First non-null supplierId in the group (all rows in a group share a supplier).
            Long supplierId = group.stream()
                    .map(DemandRow::supplierId).filter(id -> id != null).findFirst().orElse(null);

            List<WarehouseItemMaster> inventory = sku.isEmpty()
                    ? List.of()
                    : inventoryRepo.findStockForItem(sku, siteCode, restrictedSibling, supplierCode);

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
                    supplierId,
                    supplierCode,
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
            String skuCode,
            Long supplierId,
            String supplierCode) {
    }

    private record GroupKey(String sku, String supplierCode) {
    }
}
