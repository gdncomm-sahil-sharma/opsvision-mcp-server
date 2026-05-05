package com.gdn.opsvision.mcp.dto;

import java.time.Instant;
import java.util.List;

/**
 * Evidence pack for a single pick list — header + line items (PLDs).
 * <p>
 * Returns FACTS, not VERDICTS.
 */
public record PickListEvidence(
        Header pickList,
        List<LineItem> lineItems) {

    public record Header(
            long id,
            String name,
            long warehouseId,
            Long pickerId,
            String status,
            Long allottedZone,
            Long priority,
            Long pickingPriorityLevel,
            Long subLevelPriority,
            Long pickingTaskListId,
            Instant createdDate,
            Instant updatedDate) {
    }

    public record LineItem(
            long id,
            Long pickListId,
            Long pickPackageId,
            Long salesOrderId,
            Long warehouseItemId,
            Integer quantity,
            Integer quantityPicked,
            Integer problemQuantity,
            String status,
            String handlingUnitCode,
            Long pickPackageHandlingUnit,
            Long pickingTaskId,
            String sourceAreaCode,
            String stockTraceId,
            String batchId,
            String waveNumber,
            Instant createdDate) {
    }
}
