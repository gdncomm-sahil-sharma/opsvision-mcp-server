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

    /**
     * One {@code pick_package_handling_units} row plus structured lifecycle context.
     *
     * <p>{@code lifecycleStage} maps {@code status} to a {@link HandlingUnitLifecycleStage}
     * for triage. {@code wcsConsolidationStuck} is the structural Pattern C fingerprint:
     * {@code automation='WCS' AND status='PICKING_COMPLETE' AND
     * (selectedForPacking=null||false)} — HU stuck at consolidation without packing
     * handoff. It's a derived boolean; the values feeding it are also surfaced so the
     * agent can verify.
     */
    public record HandlingUnit(
            long id,
            long pickPackage,
            String handlingUnitCode,
            String handlingUnitTypeCode,
            String status,
            HandlingUnitLifecycleStage lifecycleStage,
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
            boolean wcsConsolidationStuck,
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
