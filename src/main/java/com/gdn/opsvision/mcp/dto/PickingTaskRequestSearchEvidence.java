package com.gdn.opsvision.mcp.dto;

import java.time.Instant;
import java.util.List;

/**
 * Evidence pack for {@code findPickingTaskRequests} — a paginated list of
 * {@code picking_task_request} rows matching the requested filters, with each match resolved
 * to its parent pick package code (via {@code reference_id}).
 *
 * <p>The most useful Pattern A fingerprint is {@code status='HOLD'} + a date range. The
 * Pattern B trigger signal is {@code hasMultiSkuBatchFailedReason=true}, which surfaces
 * even on CLOSED requests because {@code multi_sku_batch_failed_reason} is preserved.
 */
public record PickingTaskRequestSearchEvidence(
        int totalReturned,
        boolean truncated,
        List<RequestMatch> matches) {

    public record RequestMatch(
            long requestId,
            Long referenceId,
            String pickPackageCode,
            String status,
            PickingTaskRequestLifecycleStage lifecycleStage,
            String previousStatus,
            PickingTaskRequestLifecycleStage previousLifecycleStage,
            Instant createdDate,
            Instant lastModifiedDate,
            String lastModifiedBy,
            String referenceType,
            String pickingType,
            String type,
            String targetAreaCode,
            String multiSkuBatchFailedReason) {
    }
}
