package com.gdn.opsvision.mcp.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import com.gdn.opsvision.mcp.dto.InventoryForItemEvidence;
import com.gdn.opsvision.mcp.repository.InventoryRepository;

@Service
public class InventoryForItemTool {

    private final InventoryRepository repo;

    public InventoryForItemTool(InventoryRepository repo) {
        this.repo = repo;
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

            Returns FACTS, not VERDICTS. Only the UNRESTRICTED stock_indicator is sellable; \
            RESTRICTED stock exists for blocked / quarantined / quality-hold inventory and is \
            surfaced for completeness. If the SKU isn't onboarded at the site, returns an evidence \
            pack with an empty warehouseItemMasters list.
            """)
    public InventoryForItemEvidence getInventoryForItem(
            @ToolParam(description = "SKU code (item.code in the inventory DB)") String skuCode,
            @ToolParam(description = "Site / warehouse code (warehouse.code, e.g. 'MAR-0000000001')") String siteCode) {
        return new InventoryForItemEvidence(
                skuCode,
                siteCode,
                repo.findStockForItem(skuCode, siteCode));
    }
}
