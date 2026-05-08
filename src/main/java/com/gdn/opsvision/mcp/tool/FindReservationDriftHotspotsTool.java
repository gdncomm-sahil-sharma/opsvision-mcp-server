package com.gdn.opsvision.mcp.tool;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import com.gdn.opsvision.mcp.dto.ReservationDriftHotspotsEvidence;
import com.gdn.opsvision.mcp.dto.ReservationDriftHotspotsEvidence.DriftHotspot;
import com.gdn.opsvision.mcp.dto.ReservationDriftHotspotsEvidence.DriftSummary;
import com.gdn.opsvision.mcp.repository.StockHistoryRepository;
import com.gdn.opsvision.mcp.repository.StockHistoryRepository.BinConditionRow;
import com.gdn.opsvision.mcp.repository.StockHistoryRepository.DriftHotspotRow;
import com.gdn.opsvision.mcp.repository.StockHistoryRepository.DriftSummaryRow;
import com.gdn.opsvision.mcp.repository.WarehouseDefectMapRepository;
import com.gdn.opsvision.mcp.tool.util.IsoBound;
import com.gdn.opsvision.mcp.tool.util.Pagination;

/**
 * Site-wide scanner for {@code warehouse_item_master} rows whose aggregate-vs-bins
 * snapshot is drifted on either ORIGINAL or RESERVED stock. Surfaces the
 * "phantom-reservation" leak that causes downstream PPs to short-pick: when a pick
 * decrements the bin-side reservation but never closes back to the WIM-aggregate, the
 * aggregate keeps lying about availability and future PP creations allocate against a
 * bin that no longer holds the stock.
 *
 * <p>Pairs with the per-trace forensic tool: {@code findStockDiscrepancyOrigin}
 * resolves WHICH events caused the leak for a single SKU; this scanner tells the
 * agent WHICH SKUs to investigate first across the whole site.
 */
@Service
public class FindReservationDriftHotspotsTool {

    private static final int DEFAULT_MIN_DRIFT = 1;

    private final StockHistoryRepository stockHistoryRepo;
    private final WarehouseDefectMapRepository defectMapRepo;

    public FindReservationDriftHotspotsTool(
            StockHistoryRepository stockHistoryRepo,
            WarehouseDefectMapRepository defectMapRepo) {
        this.stockHistoryRepo = stockHistoryRepo;
        this.defectMapRepo = defectMapRepo;
    }

    @Tool(description = """
            Site-wide aggregate-vs-bins drift scanner. For every \
            warehouse_item_master at the given site, computes signed divergence \
            on ORIGINAL and RESERVED stock and returns WIMs where \
            absoluteTotalDrift = |originalDivergence| + |reservedDivergence| \
            meets the threshold. Sorted by absoluteTotalDrift DESC so the worst \
            offenders lead.

            DISAMBIGUATION vs findStockDiscrepancyOrigin: that tool is per-SKU \
            forensic that catches per-trace asymmetry on ORIGINAL stock only — use \
            it once you know which SKU to investigate, to identify WHICH events \
            caused the leak. THIS tool is site-wide, scans all WIMs in one call, \
            and is the only way to enumerate the larger RESERVED-side drift — use \
            it to identify WHICH SKUs to investigate first.

            Use this for "is anything wrong at site X?" triage and for surfacing \
            SKUs with the classic SCPS phantom-reservation leak — pick events \
            decremented the bin-side reservation but the WIM-aggregate never \
            closed back, causing future PP creations to allocate against bins \
            that no longer hold the stock and short-pick.

            Divergence sign convention (WIM aggregate − sum of bins):
              +N  WIM aggregate is HIGHER than bin sum by N (typical leak: WIM \
                  thinks more is reserved than bins actually hold).
              -N  WIM aggregate is LOWER than bin sum by N (less common, usually \
                  bin-side increases that didn't reach the rollup).

            Optional sinceDate filters to WIMs that had at least one stock_history \
            event at or after that timestamp — useful for scoping to recently- \
            active SKUs and excluding long-stale legacy drift. Without sinceDate, \
            all WIMs at the site are scanned regardless of activity.

            minAbsoluteDrift defaults to 1 (any non-zero drift). Bump higher to \
            ignore single-unit noise.

            limit defaults to 50, capped at 200; truncated=true on the response \
            when the cap is hit. The DriftSummary block reports stats across ALL \
            drifted WIMs at threshold, not just the capped hotspots list, so the \
            agent can gauge severity even when the list is truncated.

            Each hotspot also carries a binConditionBreakdown map — counts of bins \
            for the WIM grouped by warehouse_item_bin_master.blocked_type ('OK' for \
            unflagged bins, otherwise the verbatim blocked_type string like \
            'DAMAGED_CONDITION', 'ITEM_NOT_FOUND', 'Expired'). Counts sum to \
            binCount. Use this to attribute drift: 'OK: 4' = drift is on healthy \
            bins (likely an aggregate-vs-bins accounting leak); 'DAMAGED_CONDITION: \
            3' = drift coincides with damaged inventory (different escalation).

            Returns FACTS, not VERDICTS — drift may be in-flight (legitimate \
            transient state from an open reservation) or systemic (real bug). \
            Drill into individual WIMs with findStockDiscrepancyOrigin / \
            getStockHistoryForItem to diagnose root cause per SKU.
            """)
    public ReservationDriftHotspotsEvidence findReservationDriftHotspots(
            @ToolParam(description = "Site / warehouse code (e.g. 'MAR-0000000001')") String siteCode,
            @ToolParam(description = "Optional ISO bound on stock_history.created_date (YYYY-MM-DD or YYYY-MM-DDTHH:MM:SS) — restricts to WIMs with activity in [sinceDate, now)", required = false) String sinceDate,
            @ToolParam(description = "Minimum absoluteTotalDrift for a WIM to be included (default 1). Set higher to ignore single-unit noise.", required = false) Integer minAbsoluteDrift,
            @ToolParam(description = "Max hotspots to return (default 50, capped at 200)", required = false) Integer limit,
            @ToolParam(description = "Optional supplier.code to narrow the scan to a single supplier's WIMs. Useful for CONSIGNMENT_TRADING-heavy investigations.", required = false) String supplierCode) {

        int effectiveLimit = Pagination.clampLimit(limit);
        int effectiveMinDrift = clampMinDrift(minAbsoluteDrift);
        LocalDateTime since = IsoBound.parseSince(sinceDate);
        String defectCode = defectMapRepo.defectCodeFor(siteCode).orElse(null);

        List<DriftHotspotRow> rows = stockHistoryRepo.findReservationDriftHotspotsAtSite(
                siteCode, since, effectiveMinDrift, effectiveLimit + 1, supplierCode);
        boolean truncated = rows.size() > effectiveLimit;
        if (truncated) {
            rows = rows.subList(0, effectiveLimit);
        }

        DriftSummaryRow summaryRow = stockHistoryRepo.findReservationDriftSummaryAtSite(
                siteCode, since, effectiveMinDrift, supplierCode);

        // Bin-condition breakdown: one batch query for all returned hotspots' WIM ids.
        List<Long> wimIds = rows.stream().map(DriftHotspotRow::wimId).toList();
        Map<Long, Map<String, Long>> conditionsByWim = new LinkedHashMap<>();
        for (BinConditionRow bc : stockHistoryRepo.findBinConditionBreakdownByWimIds(wimIds)) {
            conditionsByWim.computeIfAbsent(bc.wimId(), k -> new LinkedHashMap<>())
                    .put(bc.condition(), bc.binCount());
        }

        List<DriftHotspot> hotspots = new ArrayList<>(rows.size());
        for (DriftHotspotRow r : rows) {
            String physicalWarehouseCode = "RESTRICTED".equals(r.stockIndicator())
                    ? (defectCode != null ? defectCode : siteCode)
                    : siteCode;
            Map<String, Long> breakdown = conditionsByWim.getOrDefault(r.wimId(), Map.of());
            hotspots.add(new DriftHotspot(
                    r.wimId(),
                    r.skuCode(),
                    r.stockIndicator(),
                    physicalWarehouseCode,
                    r.stockType(),
                    r.supplierId(),
                    r.supplierCode(),
                    r.supplierName(),
                    r.aggregateOriginalQty(),
                    r.binSumOriginalQty(),
                    r.originalDivergence(),
                    r.aggregateReservedQty(),
                    r.binSumReservedQty(),
                    r.reservedDivergence(),
                    r.absoluteTotalDrift(),
                    r.binCount(),
                    breakdown,
                    r.lastActivityAt()));
        }

        DriftSummary summary = new DriftSummary(
                summaryRow.wimsAtOrAboveThreshold(),
                summaryRow.totalAbsoluteOriginalDrift(),
                summaryRow.totalAbsoluteReservedDrift(),
                summaryRow.worstAbsoluteDrift());

        return new ReservationDriftHotspotsEvidence(
                siteCode,
                summaryRow.totalScanned(),
                hotspots.size(),
                truncated,
                effectiveLimit,
                effectiveMinDrift,
                sinceDate,
                summary,
                hotspots);
    }

    private static int clampMinDrift(Integer minDrift) {
        if (minDrift == null || minDrift <= 0) {
            return DEFAULT_MIN_DRIFT;
        }
        return minDrift;
    }
}
