package com.gdn.opsvision.mcp.repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.gdn.opsvision.mcp.repository.support.RecordRowMapper;

/**
 * Generic {@code pick_package} micro-API search.
 *
 * <p>Mirrors the {@code MovementSearchRepository} pattern: every filter is optional;
 * SQL uses the {@code (CAST(:p AS X) IS NULL OR …)} idiom so the same parsed statement
 * services any filter combination. Caller passes {@code limit + 1} to detect truncation.
 *
 * <p>Joins kept light: {@code picker} (one-row lookup for the assigned picker code),
 * {@code sales_order → warehouse} (lateral subquery for {@code site_code}). Pick-list
 * filters use {@code EXISTS} subqueries so they don't multiply the row count.
 *
 * <p>Sort options are typed (not free-form strings) — caller passes a {@link SortBy}
 * enum value, repository inlines a hand-whitelisted ORDER BY clause. No SQL injection
 * surface.
 */
@Repository
@Transactional(readOnly = true)
public class PickPackageSearchRepository {

    /** Sentinel used in {@code assignedPickerCode} to mean "PP.picker IS NULL". */
    public static final String UNASSIGNED_SENTINEL = "__UNASSIGNED__";

    public enum SortBy {
        CREATED_ASC,
        CREATED_DESC,
        UPDATED_DESC
    }

    private final JdbcClient stockholm;

    public PickPackageSearchRepository(@Qualifier("stockholmJdbcClient") JdbcClient stockholm) {
        this.stockholm = stockholm;
    }

    public List<PpSearchRow> search(SearchFilters f, SortBy sort, int limit) {
        String orderBy = switch (sort) {
            case CREATED_ASC  -> "pp.created_date ASC, pp.id ASC";
            case CREATED_DESC -> "pp.created_date DESC, pp.id DESC";
            case UPDATED_DESC -> "pp.updated_date DESC, pp.id DESC";
        };
        String sql = """
                SELECT pp.id                  AS pp_id,
                       pp.code                AS pp_code,
                       pp.status,
                       pp.picking_status,
                       pp.canceled,
                       pp.in_progress,
                       pp.short_pick,
                       pp.rejected,
                       pp.picker              AS assigned_picker_id,
                       picker.code            AS assigned_picker_code,
                       pp.batch_id,
                       pp.wave_number,
                       pp.created_date AT TIME ZONE 'UTC' AS created_date,
                       pp.updated_date AT TIME ZONE 'UTC' AS updated_date,
                       (SELECT DISTINCT w.code
                          FROM sales_order so
                          JOIN warehouse w ON w.id = so.warehouse
                         WHERE so.pick_package_id = pp.id
                         LIMIT 1)            AS site_code
                  FROM pick_package pp
                  LEFT JOIN picker ON picker.id = pp.picker
                 WHERE (CAST(:siteCode AS text) IS NULL OR EXISTS (
                            SELECT 1 FROM sales_order so JOIN warehouse w ON w.id = so.warehouse
                             WHERE so.pick_package_id = pp.id AND w.code = :siteCode))
                   AND (CAST(:ppStatus AS int) IS NULL OR pp.status = :ppStatus)
                   AND (CAST(:pickingStatus AS text) IS NULL OR pp.picking_status = :pickingStatus)
                   AND (CAST(:isCanceled AS boolean) IS NULL OR pp.canceled = :isCanceled)
                   AND (CAST(:isShortPick AS boolean) IS NULL OR pp.short_pick = :isShortPick)
                   AND (CAST(:isRejected AS boolean) IS NULL OR pp.rejected = :isRejected)
                   AND (CAST(:isInProgress AS boolean) IS NULL OR pp.in_progress = :isInProgress)
                   AND (CAST(:assignedPickerCode AS text) IS NULL
                        OR (:assignedPickerCode = '__UNASSIGNED__' AND pp.picker IS NULL)
                        OR picker.code = :assignedPickerCode)
                   AND (CAST(:batchId AS text) IS NULL OR pp.batch_id = :batchId)
                   AND (CAST(:waveNumber AS text) IS NULL OR pp.wave_number = :waveNumber)
                   AND (CAST(:since AS timestamp) IS NULL OR pp.created_date >= :since)
                   AND (CAST(:until AS timestamp) IS NULL OR pp.created_date < :until)
                   AND (CAST(:hasOpenPickList AS boolean) IS NULL OR
                        (:hasOpenPickList = TRUE AND EXISTS (
                            SELECT 1 FROM pick_list_details pld
                              JOIN pick_list pl ON pl.id = pld.pick_list_id
                             WHERE pld.pick_package_id = pp.id AND pl.status <> 'CLOSED'))
                        OR (:hasOpenPickList = FALSE AND NOT EXISTS (
                            SELECT 1 FROM pick_list_details pld
                              JOIN pick_list pl ON pl.id = pld.pick_list_id
                             WHERE pld.pick_package_id = pp.id AND pl.status <> 'CLOSED')))
                   AND (CAST(:openZoneCode AS text) IS NULL OR EXISTS (
                            SELECT 1 FROM pick_list_details pld
                              JOIN pick_list pl ON pl.id = pld.pick_list_id
                              JOIN zone z ON z.id = pl.allotted_zone
                             WHERE pld.pick_package_id = pp.id
                               AND pl.status <> 'CLOSED'
                               AND z.zone_code = :openZoneCode))
                   AND (CAST(:sourceArea AS text) IS NULL OR EXISTS (
                            SELECT 1 FROM pick_list_details pld
                              JOIN warehouse_item wi ON wi.id = pld.warehouse_item_id
                              JOIN storage st ON st.id = pld.assigned_storage_id
                             WHERE pld.pick_package_id = pp.id
                               AND st.area_code = :sourceArea))
                 ORDER BY %s
                 LIMIT :lim
                """.formatted(orderBy);

        return stockholm.sql(sql)
                .param("siteCode", f.siteCode())
                .param("ppStatus", f.ppStatusOrdinal())
                .param("pickingStatus", f.pickingStatus())
                .param("isCanceled", f.isCanceled())
                .param("isShortPick", f.isShortPick())
                .param("isRejected", f.isRejected())
                .param("isInProgress", f.isInProgress())
                .param("assignedPickerCode", f.assignedPickerCode())
                .param("batchId", f.batchId())
                .param("waveNumber", f.waveNumber())
                .param("since", f.since())
                .param("until", f.until())
                .param("hasOpenPickList", f.hasOpenPickList())
                .param("openZoneCode", f.openZoneCode())
                .param("sourceArea", f.sourceArea())
                .param("lim", limit)
                .query(RecordRowMapper.of(PpSearchRow.class))
                .list();
    }

    /**
     * Filter bag for {@link #search}. All fields are nullable; null = no filter on that column.
     */
    public record SearchFilters(
            String siteCode,
            Integer ppStatusOrdinal,
            String pickingStatus,
            Boolean isCanceled,
            Boolean isShortPick,
            Boolean isRejected,
            Boolean isInProgress,
            String assignedPickerCode,
            String batchId,
            String waveNumber,
            LocalDateTime since,
            LocalDateTime until,
            Boolean hasOpenPickList,
            String openZoneCode,
            String sourceArea) {
    }

    /** Raw search row — tool layer attaches the human-readable {@code statusLabel}. */
    public record PpSearchRow(
            long ppId,
            String ppCode,
            int status,
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
