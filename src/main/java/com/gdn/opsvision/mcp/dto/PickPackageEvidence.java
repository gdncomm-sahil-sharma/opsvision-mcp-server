package com.gdn.opsvision.mcp.dto;

import java.time.Instant;
import java.util.List;

/**
 * Evidence pack for a single pick package — header + handling units + sales orders.
 * <p>
 * Returns FACTS, not VERDICTS. The agent draws conclusions; this tool just hands over
 * what's true in the database. No fields like {@code rootCause} or {@code stuckAtStage}.
 */
public record PickPackageEvidence(
        Header pickPackage,
        List<HandlingUnit> handlingUnits,
        List<SalesOrder> salesOrders) {

    public record Header(
            long id,
            String code,
            int status,
            String pickingStatus,
            boolean canceled,
            Boolean shortPick,
            Boolean inProgress,
            Instant createdDate,
            Instant updatedDate,
            Instant autoCancelDate,
            Instant firstExportXlsTime,
            Long batchId,
            Long waveNumber,
            String businessChannel,
            String channel,
            String clientCode,
            String pickingType,
            String packingSpecCode,
            String targetAreaCode,
            String targetSectionCode,
            Boolean partialFulfillmentAllowed,
            Boolean manualTargetArea) {
    }

    public record HandlingUnit(
            long id,
            long pickPackage,
            String handlingUnitCode,
            String handlingUnitTypeCode,
            String status,
            String targetAreaCode,
            String targetSectionCode,
            String dropPointCode,
            String dropPointArea,
            String transitDropPointCode,
            String transitDropPointArea,
            String automation,
            String ptlConsolidationStatus,
            Boolean consolidationRequired,
            Boolean selectedForPacking,
            String handlingUnitGroup,
            String lastModifiedBy,
            Instant lastModifiedDate) {
    }

    public record SalesOrder(
            long id,
            String salesOrderNumber,
            Long pickPackageId,
            int lastStatus,
            String pickingStatus,
            Boolean pickingStuck,
            String orderStuckReason,
            Long currentPickedQuantity,
            Long quantityPicking,
            String orderId,
            String orderItemId,
            String orderReferenceCode) {
    }
}
