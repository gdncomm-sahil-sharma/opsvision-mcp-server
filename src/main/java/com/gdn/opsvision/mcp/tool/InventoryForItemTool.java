package com.gdn.opsvision.mcp.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import com.gdn.opsvision.mcp.dto.InventoryForItemEvidence;
import com.gdn.opsvision.mcp.repository.InventoryRepository;
import com.gdn.opsvision.mcp.repository.WarehouseDefectMapRepository;

@Service
public class InventoryForItemTool {

    private final InventoryRepository repo;
    private final WarehouseDefectMapRepository defectMapRepo;

    public InventoryForItemTool(InventoryRepository repo, WarehouseDefectMapRepository defectMapRepo) {
        this.repo = repo;
        this.defectMapRepo = defectMapRepo;
    }

    @Tool(description = """
            Fetch warehouse-inventory state for a single SKU at a single site. Returns one row per \
            warehouse_item_master (typically 1 UNRESTRICTED row, optionally a RESTRICTED row), each \
            with: aggregate physical original / reserved / available quantities, total bin count, \
            sum of per-bin original / reserved (so the agent can spot aggregate-vs-bin divergence), \
            and a capped list of per-bin snapshots (binId, binCode, originalQty, reservedQty). The \
            bin list is truncated to 50 entries — binsTruncated=true signals there are more.

            ID format: skuCode is the universal item.code (e.g. '10851_WINGS', 'MTA-51795792-00001'); \
            siteCode is the warehouse code (e.g. 'MAR-0000000001'). The same SKU has independent \
            stock per site, so siteCode is required.

            Returns FACTS, not VERDICTS. stock_indicator semantics: warehouse-inventory persists \
            stock under the parent (distribution) warehouse code with a stock_indicator tag — \
            UNRESTRICTED = stock physically in the distribution warehouse (the sellable, \
            pickable side); RESTRICTED = stock physically held in the paired non-distribution / \
            damage sibling warehouse (per stockholm's warehouse_defect_map). For Marunda the \
            pairing is MAR-0000000001 ('BKI N - Marunda') ↔ MAN-0000000002 ('Marunda Non Dist'). \
            Each returned row carries a physicalWarehouseCode field that resolves to the actual \
            stockholm warehouse where the stock sits — equal to siteCode for UNRESTRICTED, equal \
            to the defect sibling for RESTRICTED. Picking always filters UNRESTRICTED only \
            (PickingBinSelectionHelper.java:136), so RESTRICTED rows never participate in \
            pick-list allocation. If the SKU isn't onboarded at the site, returns an evidence \
            pack with an empty warehouseItemMasters list.
            """)
    public InventoryForItemEvidence getInventoryForItem(
            @ToolParam(description = "SKU code (item.code in the inventory DB)") String skuCode,
            @ToolParam(description = "Site / warehouse code (warehouse.code, e.g. 'MAR-0000000001')") String siteCode) {
        String restrictedSibling = defectMapRepo.defectCodeFor(siteCode).orElse(null);
        return new InventoryForItemEvidence(
                skuCode,
                siteCode,
                repo.findStockForItem(skuCode, siteCode, restrictedSibling));
    }
}
