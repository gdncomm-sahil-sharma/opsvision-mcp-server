package com.gdn.opsvision.mcp.dto;

import java.time.Instant;
import java.util.List;

/**
 * Evidence pack for {@code findPickPackages} — a generic micro-API filter+list view of
 * the {@code pick_package} table with light joins (assigned-picker code; site code via
 * {@code sales_order.warehouse}).
 *
 * <p>Returns FACTS, not VERDICTS. Each row is a slim PP header; the agent calls
 * {@code diagnosePickPackage} or {@code getPickPackage} on individual rows for the
 * full evidence pack. {@code truncated:true} signals the cap was hit and there are
 * more matches; the caller tightens the filter or reduces {@code limit}.
 */
public record PickPackageSearchEvidence(
        int returnedCount,
        boolean truncated,
        int limit,
        List<PickPackageRow> packages) {

    public record PickPackageRow(
            long ppId,
            String ppCode,
            int status,
            String statusLabel,
            String pickingStatus,
            boolean canceled,
            Boolean inProgress,
            Boolean shortPick,
            Boolean rejected,
            Long assignedPickerId,
            String assignedPickerCode,
            String batchId,
            String waveNumber,
            Instant createdDate,
            Instant updatedDate,
            String siteCode) {
    }
}
