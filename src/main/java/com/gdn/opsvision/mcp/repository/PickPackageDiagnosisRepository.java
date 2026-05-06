package com.gdn.opsvision.mcp.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single-PP joins for {@code diagnosePickPackage} that don't fit cleanly elsewhere — PP
 * state with assigned-picker join, priority with picking_priority_level join, pick_list
 * allocations with zone + picker join, distinct source areas, and remaining demand.
 *
 * <p>Site code resolution and picking-item-without-cancel filtering reuse the same idioms
 * as {@code ReconciliationRepository}: {@code pick_package → sales_order.warehouse →
 * warehouse.code} for siteCode; {@code status NOT IN ('COMPLETE','CANCELLED')} for demand.
 */
@Repository
@Transactional(readOnly = true)
public class PickPackageDiagnosisRepository {

    private final JdbcClient stockholm;

    public PickPackageDiagnosisRepository(@Qualifier("stockholmJdbcClient") JdbcClient stockholm) {
        this.stockholm = stockholm;
    }

    /**
     * Slim PP-id lookup. Independent of {@code PickPackageRepository.findHeaderByCode} so we
     * don't depend on that repo's full-header DTO (which maps {@code batch_id}/{@code wave_number}
     * as bigint though the actual columns are varchar — works when the column is null but
     * fails on PPs that have those values populated).
     */
    public Optional<Long> findIdByCode(String code) {
        return stockholm.sql("SELECT id FROM pick_package WHERE code = :code")
                .param("code", code)
                .query(Long.class)
                .optional();
    }

    public boolean idExists(long ppId) {
        Long n = stockholm.sql("SELECT count(*) FROM pick_package WHERE id = :id")
                .param("id", ppId)
                .query(Long.class)
                .single();
        return n != null && n > 0;
    }

    /** PP header + assigned-picker join + siteCode + batch/wave (one row, or empty if PP gone). */
    public Optional<PpStateRow> findStateById(long ppId) {
        return stockholm.sql("""
                        SELECT pp.id                  AS pp_id,
                               pp.code                AS pp_code,
                               pp.status,
                               pp.picking_status,
                               pp.canceled,
                               pp.in_progress,
                               pp.short_pick,
                               pp.deprioritized,
                               pp.rejected,
                               pp.priority_boosted,
                               pp.picker              AS assigned_picker_id,
                               picker.code            AS assigned_picker_code,
                               pp.assigned_picker_date,
                               pp.distribution_zone_code,
                               pp.batch_id,
                               pp.batch_type,
                               pp.wave_number,
                               pp.created_date,
                               pp.updated_date,
                               pp.auto_cancel_date,
                               (SELECT DISTINCT w.code
                                  FROM sales_order so
                                  JOIN warehouse w ON w.id = so.warehouse
                                 WHERE so.pick_package_id = pp.id
                                 LIMIT 1)            AS site_code
                          FROM pick_package pp
                          LEFT JOIN picker ON picker.id = pp.picker
                         WHERE pp.id = :ppId
                        """)
                .param("ppId", ppId)
                .query(PpStateRow.class)
                .optional();
    }

    /**
     * Sibling PPs sharing the same {@code batch_id} or {@code wave_number}. Excludes the
     * current PP. Returns {@code (picking_status, status)} pairs so the caller can build
     * frequency breakdowns. Bound at 1000 rows; if a batch is bigger than that, we surface
     * what we got — the breakdown is still informative.
     */
    public List<BatchSiblingRow> findBatchSiblings(String batchId, String waveNumber, long ownPpId) {
        boolean hasBatch = batchId != null && !batchId.isBlank();
        boolean hasWave = waveNumber != null && !waveNumber.isBlank();
        if (!hasBatch && !hasWave) {
            return List.of();
        }
        return stockholm.sql("""
                        SELECT pp.picking_status, pp.status
                          FROM pick_package pp
                         WHERE pp.id <> :ownId
                           AND (
                                 (CAST(:batchId AS text) IS NOT NULL AND pp.batch_id = :batchId)
                              OR (CAST(:waveNo  AS text) IS NOT NULL AND pp.wave_number = :waveNo)
                           )
                         LIMIT 1000
                        """)
                .param("ownId", ownPpId)
                .param("batchId", hasBatch ? batchId : null)
                .param("waveNo", hasWave ? waveNumber : null)
                .query(BatchSiblingRow.class)
                .list();
    }

    /** Priority columns + picking_priority_level details. */
    public Optional<PpPriorityRow> findPriorityById(long ppId) {
        return stockholm.sql("""
                        SELECT pp.top_priority,
                               pp.top_priority_code,
                               pp.priority,
                               pp.sub_level_priority,
                               pp.priority_boosted,
                               pp.picking_priority_level   AS picking_priority_level_id,
                               ppl.level_name              AS picking_priority_level_name,
                               ppl.precedence              AS picking_priority_precedence
                          FROM pick_package pp
                          LEFT JOIN picking_priority_level ppl ON ppl.id = pp.picking_priority_level
                         WHERE pp.id = :ppId
                        """)
                .param("ppId", ppId)
                .query(PpPriorityRow.class)
                .optional();
    }

    /**
     * One row per pick_list this PP belongs to. Joins zone (via allotted_zone) and picker
     * (via picker_id). DISTINCT to avoid duplicate rows when one pick_list owns multiple
     * pick_list_details for the same PP.
     */
    public List<PickListAllocationRow> findPickListAllocationsByPpId(long ppId) {
        return stockholm.sql("""
                        SELECT DISTINCT
                               pl.id                   AS pick_list_id,
                               pl.status               AS pick_list_status,
                               pl.allotted_zone        AS allotted_zone_id,
                               z.zone_code             AS allotted_zone_code,
                               pl.picker_id,
                               picker.code             AS picker_code,
                               picker.status           AS picker_status,
                               pl.priority,
                               pl.picking_priority_level AS picking_priority_level_id,
                               ppl.precedence          AS picking_priority_precedence,
                               pl.sub_level_priority,
                               pl.created_date
                          FROM pick_list_details pld
                          JOIN pick_list pl                ON pl.id = pld.pick_list_id
                          LEFT JOIN zone z                 ON z.id = pl.allotted_zone
                          LEFT JOIN picker                  ON picker.id = pl.picker_id
                          LEFT JOIN picking_priority_level ppl ON ppl.id = pl.picking_priority_level
                         WHERE pld.pick_package_id = :ppId
                         ORDER BY pl.id
                        """)
                .param("ppId", ppId)
                .query(PickListAllocationRow.class)
                .list();
    }

    /** Source area codes covered by this pick_list (used to fan source_area into each PL row). */
    public List<String> findSourceAreasByPickListAndPp(long pickListId, long ppId) {
        return stockholm.sql("""
                        SELECT DISTINCT pld.source_area_code
                          FROM pick_list_details pld
                         WHERE pld.pick_list_id    = :plId
                           AND pld.pick_package_id = :ppId
                           AND pld.source_area_code IS NOT NULL
                         ORDER BY pld.source_area_code
                        """)
                .param("plId", pickListId)
                .param("ppId", ppId)
                .query(String.class)
                .list();
    }

    /** Distinct source areas across the whole PP (for §4 SourceAreaCoverage). */
    public List<String> findDistinctSourceAreasByPpId(long ppId) {
        return stockholm.sql("""
                        SELECT DISTINCT source_area_code
                          FROM pick_list_details
                         WHERE pick_package_id = :ppId
                           AND source_area_code IS NOT NULL
                         ORDER BY source_area_code
                        """)
                .param("ppId", ppId)
                .query(String.class)
                .list();
    }

    /**
     * Remaining demand per SKU — one row per still-open picking_item, for §5 replenishment
     * deficit calculations. Mirrors the {@code WHERE status NOT IN ('COMPLETE','CANCELLED')}
     * idiom from {@code ReconciliationRepository.reconcile}.
     */
    public List<DemandRow> findRemainingDemandByPpId(long ppId) {
        return stockholm.sql("""
                        SELECT i.code                                 AS sku_code,
                               (pi.quantity - COALESCE(pi.current_picked_quantity, 0)) AS remaining_demand
                          FROM picking_item pi
                          JOIN sales_order so       ON so.id = pi.sales_order
                          LEFT JOIN warehouse_item wi ON wi.id = pi.warehouse_item
                          LEFT JOIN item            i  ON i.id  = wi.item
                         WHERE so.pick_package_id = :ppId
                           AND pi.status NOT IN ('COMPLETE','CANCELLED')
                           AND i.code IS NOT NULL
                        """)
                .param("ppId", ppId)
                .query(DemandRow.class)
                .list();
    }

    // ─── row records ─────────────────────────────────────────────────────────

    public record PpStateRow(
            long ppId,
            String ppCode,
            int status,
            String pickingStatus,
            boolean canceled,
            Boolean inProgress,
            Boolean shortPick,
            Boolean deprioritized,
            Boolean rejected,
            Boolean priorityBoosted,
            Long assignedPickerId,
            String assignedPickerCode,
            Instant assignedPickerDate,
            String distributionZoneCode,
            String batchId,
            String batchType,
            String waveNumber,
            Instant createdDate,
            Instant updatedDate,
            Instant autoCancelDate,
            String siteCode) {
    }

    public record BatchSiblingRow(String pickingStatus, int status) {
    }

    public record PpPriorityRow(
            Integer topPriority,
            String topPriorityCode,
            Long priority,
            Long subLevelPriority,
            Boolean priorityBoosted,
            Long pickingPriorityLevelId,
            String pickingPriorityLevelName,
            Integer pickingPriorityPrecedence) {
    }

    public record PickListAllocationRow(
            long pickListId,
            String pickListStatus,
            Long allottedZoneId,
            String allottedZoneCode,
            Long pickerId,
            String pickerCode,
            String pickerStatus,
            Long priority,
            Long pickingPriorityLevelId,
            Integer pickingPriorityPrecedence,
            Long subLevelPriority,
            Instant createdDate) {
    }

    public record DemandRow(
            String skuCode,
            int remainingDemand) {
    }
}
