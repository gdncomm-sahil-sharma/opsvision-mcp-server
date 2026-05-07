package com.gdn.opsvision.mcp.config;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.gdn.opsvision.mcp.tool.DiagnosePickPackageTool;
import com.gdn.opsvision.mcp.tool.DiagnosePickerQueueTool;
import com.gdn.opsvision.mcp.tool.FindPickPackagesTool;
import com.gdn.opsvision.mcp.tool.FindPickingTaskRequestsTool;
import com.gdn.opsvision.mcp.tool.FindPickingTasksTool;
import com.gdn.opsvision.mcp.tool.FindPpsBlockedByPickerAvailabilityTool;
import com.gdn.opsvision.mcp.tool.FindStockDiscrepancyOriginTool;
import com.gdn.opsvision.mcp.tool.GetSalesOrderTool;
import com.gdn.opsvision.mcp.tool.GetStockHistoryForItemTool;
import com.gdn.opsvision.mcp.tool.InventoryForItemTool;
import com.gdn.opsvision.mcp.tool.MovementHistoryTool;
import com.gdn.opsvision.mcp.tool.PickListReadinessTool;
import com.gdn.opsvision.mcp.tool.PickListTool;
import com.gdn.opsvision.mcp.tool.PickPackageTool;
import com.gdn.opsvision.mcp.tool.ReconciliationTool;
import com.gdn.opsvision.mcp.tool.StockTraceTool;

/**
 * Registers @Tool-annotated methods as MCP tool callbacks. Add new tool services here as
 * we implement them — the MCP server then exposes each method as a separate tool.
 */
@Configuration
public class McpToolsConfig {

    @Bean
    ToolCallbackProvider opsvisionTools(
            PickPackageTool pickPackageTool,
            PickListTool pickListTool,
            PickListReadinessTool pickListReadinessTool,
            InventoryForItemTool inventoryForItemTool,
            ReconciliationTool reconciliationTool,
            StockTraceTool stockTraceTool,
            MovementHistoryTool movementHistoryTool,
            FindPickingTasksTool findPickingTasksTool,
            FindPickingTaskRequestsTool findPickingTaskRequestsTool,
            FindPickPackagesTool findPickPackagesTool,
            FindPpsBlockedByPickerAvailabilityTool findPpsBlockedByPickerAvailabilityTool,
            FindStockDiscrepancyOriginTool findStockDiscrepancyOriginTool,
            GetSalesOrderTool getSalesOrderTool,
            GetStockHistoryForItemTool getStockHistoryForItemTool,
            DiagnosePickPackageTool diagnosePickPackageTool,
            DiagnosePickerQueueTool diagnosePickerQueueTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(pickPackageTool, pickListTool, pickListReadinessTool,
                        inventoryForItemTool, reconciliationTool, stockTraceTool,
                        movementHistoryTool, findPickingTasksTool,
                        findPickingTaskRequestsTool, findPickPackagesTool,
                        findPpsBlockedByPickerAvailabilityTool,
                        findStockDiscrepancyOriginTool,
                        getSalesOrderTool, getStockHistoryForItemTool,
                        diagnosePickPackageTool, diagnosePickerQueueTool)
                .build();
    }
}
