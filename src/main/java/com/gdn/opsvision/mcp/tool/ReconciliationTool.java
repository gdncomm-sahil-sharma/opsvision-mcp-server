package com.gdn.opsvision.mcp.tool;

import java.util.List;
import java.util.Optional;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import com.gdn.opsvision.mcp.dto.InventoryReservationReconciliation;
import com.gdn.opsvision.mcp.dto.InventoryReservationReconciliation.ItemReconciliation;
import com.gdn.opsvision.mcp.dto.PickPackageEvidence;
import com.gdn.opsvision.mcp.repository.PickPackageRepository;
import com.gdn.opsvision.mcp.repository.ReconciliationRepository;

@Service
public class ReconciliationTool {

    private final PickPackageRepository pickPackageRepo;
    private final ReconciliationRepository reconciliationRepo;

    public ReconciliationTool(
            PickPackageRepository pickPackageRepo,
            ReconciliationRepository reconciliationRepo) {
        this.pickPackageRepo = pickPackageRepo;
        this.reconciliationRepo = reconciliationRepo;
    }

    @Tool(description = """
            For a given pick package, walk every picking_item and emit a per-SKU evidence row \
            comparing stockholm picking demand (required vs picked vs remaining qty, with the \
            picking_item / sales_order ids) against warehouse-inventory state (one or more \
            warehouse_item_master rows split by stock_indicator, with aggregate and per-bin \
            quantities). Each row also carries a divergences block: \
            aggregateUnrestrictedAvailable, availableMinusRemaining (signed; negative = unrestricted \
            stock falls short of remaining demand), and aggregateVsBinSumOriginal / \
            aggregateVsBinSumReserved (signed; non-zero = aggregate-vs-bin-sum drift, which is the \
            classic SCPS stock-discrepancy signal).

            Use this for SNA / "stock not available" / "sales order not allotted" investigations — \
            i.e. when a PP is at picking_status STORAGE_STOCK_RESERVED, or when picking isn't \
            advancing because the warehouse-inventory side seems short of stock. Cross-DB join is \
            via SKU code (item.code), not by id (item.id values differ between stockholm and \
            warehouse-inventory). siteCode is resolved from the PP's sales_order.warehouse.

            Each StockholmDemand also carries `stockTraceIds` — the picking_item.stock_trace_id \
            UUIDs for that SKU. Pass these to getStockTrace to walk the warehouse-inventory audit \
            chain (PP creation reservation → bin reservation → PLD pick decrement → GIN). That's \
            how you investigate aggregate-vs-bin drift or any unexpected divergence — the user \
            won't know trace IDs exist; surface them yourself.

            Returns FACTS, not VERDICTS. A negative availableMinusRemaining does NOT mean a bug — \
            the divergence may be in-flight reservation, expected partial fulfilment, snapshot vs \
            live drift, or a real stock issue. The agent decides. If the PP doesn't exist, returns \
            an evidence pack with pickPackage=null and an empty items list.

            ID format: pick package code like 'PK/MAR-01/V-2026/7747838', or numeric pick_package.id.
            """)
    public InventoryReservationReconciliation reconcileInventoryVsReservation(
            @ToolParam(description = "Pick package code (PK/MAR-...) or numeric id") String idOrCode) {

        Optional<PickPackageEvidence.Header> header = lookupHeader(idOrCode);
        if (header.isEmpty()) {
            return new InventoryReservationReconciliation(null, null, List.of());
        }
        long ppId = header.get().id();
        String siteCode = reconciliationRepo.findSiteCode(ppId).orElse(null);
        List<ItemReconciliation> items = siteCode == null
                ? List.of()
                : reconciliationRepo.reconcile(ppId, siteCode);
        return new InventoryReservationReconciliation(header.get(), siteCode, items);
    }

    private Optional<PickPackageEvidence.Header> lookupHeader(String idOrCode) {
        if (idOrCode == null || idOrCode.isBlank()) {
            return Optional.empty();
        }
        String trimmed = idOrCode.trim();
        if (trimmed.chars().allMatch(Character::isDigit)) {
            return pickPackageRepo.findHeaderById(Long.parseLong(trimmed));
        }
        return pickPackageRepo.findHeaderByCode(trimmed);
    }
}
