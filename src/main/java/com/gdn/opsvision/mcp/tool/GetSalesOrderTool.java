package com.gdn.opsvision.mcp.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import com.gdn.opsvision.mcp.dto.SalesOrderEvidence;
import com.gdn.opsvision.mcp.dto.SalesOrderEvidence.LastStatus;
import com.gdn.opsvision.mcp.dto.SalesOrderEvidence.ParentPickPackage;
import com.gdn.opsvision.mcp.dto.SalesOrderEvidence.PickingItem;
import com.gdn.opsvision.mcp.repository.SalesOrderRepository;
import com.gdn.opsvision.mcp.repository.SalesOrderRepository.PickingItemRow;
import com.gdn.opsvision.mcp.repository.SalesOrderRepository.SalesOrderHeader;

@Service
public class GetSalesOrderTool {

    private final SalesOrderRepository repo;

    public GetSalesOrderTool(SalesOrderRepository repo) {
        this.repo = repo;
    }

    @Tool(description = """
            Fetch a single sales_order keyed by order_item_id (the upstream e-commerce \
            line-item identifier; sales_order.order_item_id is unique). Returns the SO row \
            with denormalized parent pick_package + warehouse context, plus a list of \
            picking_item demand rows for that SO.

            Use this as the entry point when an investigation starts from an order_item_id \
            (typical for OOS triage from a customer ticket or dashboard). The response \
            surfaces every chain hop the agent needs:
              - parentPickPackage.code → getPickPackage / evaluatePickListReadiness / \
                reconcileInventoryVsReservation / getMovementHistory.
              - pickingItems[].skuCode + siteCode → getInventoryForItem / \
                getStockHistoryForItem.
              - pickingItems[].stockTraceId → getStockTrace.

            ID format: order_item_id is the upstream cross-reference string. Not the same \
            as sales_order.id or sales_order_number. If the order_item_id doesn't match, \
            returns found=false with every other field null (errors-as-data, not an \
            exception).

            lastStatus.code mapping: 5 = OUT_OF_STOCK, 17 = OUT_OF_STOCK_CANCEL. Other \
            integer codes are passed through with label="STATUS_<n>".

            lastProcessDate is sales_order.last_process_date — the SO-level update \
            timestamp (Hibernate @UpdateTimestamp). Use it as the natural anchor for \
            getStockHistoryForItem windows when investigating OOS or other state \
            transitions on this SO.

            picking_item is 1-to-many with sales_order, so pickingItems may have 0..N \
            entries. An empty list is a real state (e.g. SO stuck before picking spawned), \
            not an error.

            Returns FACTS, not VERDICTS. The agent reads lastStatus / pickingStuck / \
            orderStuckReason / pickingItems[].status and decides what the combination \
            means.
            """)
    public SalesOrderEvidence getSalesOrder(
            @ToolParam(description = "sales_order.order_item_id (upstream e-commerce line-item identifier; unique)") String orderItemId) {

        if (orderItemId == null || orderItemId.isBlank()) {
            return notFound(orderItemId);
        }
        Optional<SalesOrderHeader> headerOpt = repo.findHeaderByOrderItemId(orderItemId.trim());
        if (headerOpt.isEmpty()) {
            return notFound(orderItemId.trim());
        }
        SalesOrderHeader h = headerOpt.get();

        List<PickingItemRow> piRows = repo.findPickingItemsBySoId(h.soId());
        List<PickingItem> pickingItems = new ArrayList<>(piRows.size());
        for (PickingItemRow r : piRows) {
            pickingItems.add(new PickingItem(
                    r.pickingItemId(),
                    r.skuCode(),
                    r.status(),
                    r.quantity(),
                    r.currentPickedQuantity(),
                    r.stockTraceId()));
        }

        return new SalesOrderEvidence(
                orderItemId.trim(),
                /*found=*/true,
                h.soId(),
                h.soNumber(),
                mapLastStatus(h.lastStatus()),
                h.pickingStuck(),
                h.orderStuckReason(),
                h.lastProcessDate(),
                h.siteCode(),
                new ParentPickPackage(h.pickPackageId(), h.ppCode()),
                pickingItems);
    }

    private static SalesOrderEvidence notFound(String orderItemId) {
        return new SalesOrderEvidence(
                orderItemId,
                /*found=*/false,
                null, null, null, null, null, null, null, null, List.of());
    }

    private static LastStatus mapLastStatus(int code) {
        String label = switch (code) {
            case 5 -> "OUT_OF_STOCK";
            case 17 -> "OUT_OF_STOCK_CANCEL";
            default -> "STATUS_" + code;
        };
        return new LastStatus(code, label);
    }
}
