package com.gdn.opsvision.mcp.repository;

import com.gdn.opsvision.mcp.repository.support.RecordRowMapper;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stockholm-side picker eligibility + queue-rank queries for {@code diagnosePickPackage}.
 *
 * <p>Wraps the picker-zone-access chain that the production code uses in
 * {@code PickListRepo.java:78–92}: {@code picker_zone_group → zone_zone_group → zone}.
 * Also resolves the {@code source_area_code → zone} bridge via the {@code storage} table.
 *
 * <p>All queries are read-only and bounded — the eligible-picker list and zone list both
 * cap at fixed sizes with truncation flags, so the tool can never explode under a busy
 * warehouse.
 */
@Repository
@Transactional(readOnly = true)
public class PickerAccessRepository {

    /** Bound on resolved-zones-per-area. M4-STOR has 15 zones at MAR; GF-STOR has 51. */
    private static final int MAX_ZONES_PER_AREA = 50;

    /** Bound on the eligible-picker list returned per query. We only need counts + status histogram. */
    private static final int MAX_PICKERS_PER_QUERY = 500;

    private final JdbcClient stockholm;

    public PickerAccessRepository(@Qualifier("stockholmJdbcClient") JdbcClient stockholm) {
        this.stockholm = stockholm;
    }

    /**
     * Eligible pickers for a set of zones at a warehouse — {@code picker_zone_group →
     * zone_zone_group → zone}, filtered to active+undeleted pickers.
     *
     * <p>Empty {@code zoneIds} returns an empty list; the IN-clause would be invalid otherwise.
     */
    public List<PickerRow> findEligiblePickersForZones(Collection<Long> zoneIds, String warehouseCode) {
        if (zoneIds == null || zoneIds.isEmpty() || warehouseCode == null) {
            return List.of();
        }
        return stockholm.sql("""
                        SELECT DISTINCT p.id, p.code, p.name, p.status, p.last_login_time AT TIME ZONE 'UTC' AS last_login_time
                          FROM picker p
                          JOIN warehouse w  ON w.id = p.warehouse
                          JOIN picker_zone_group pzg ON pzg.picker_id = p.id
                          JOIN zone_zone_group   zzg ON zzg.zone_group_id = pzg.zone_group_id
                         WHERE w.code = :wh
                           AND p.active AND NOT p.deleted
                           AND zzg.zone_id IN (:zoneIds)
                         ORDER BY p.id
                         LIMIT :cap
                        """)
                .param("wh", warehouseCode)
                .param("zoneIds", zoneIds)
                .param("cap", MAX_PICKERS_PER_QUERY)
                .query(RecordRowMapper.of(PickerRow.class))
                .list();
    }

    /**
     * Zones that {@code source_area_code} maps to via the {@code storage} bridge.
     * Many-to-many in practice — M4-STOR has 15 zones at MAR. Capped at
     * {@link #MAX_ZONES_PER_AREA}; caller checks the returned list size to detect truncation.
     */
    public List<ZoneRow> findZonesForSourceArea(String areaCode, String warehouseCode) {
        if (areaCode == null || warehouseCode == null) {
            return List.of();
        }
        return stockholm.sql("""
                        SELECT DISTINCT z.id, z.zone_code, z.zone_name
                          FROM storage s
                          JOIN zone    z ON z.id = s.zone_id
                         WHERE s.area_code = :area
                           AND s.warehouse_code = :wh
                           AND NOT s.deleted
                           AND NOT z.deleted
                         ORDER BY z.id
                         LIMIT :cap
                        """)
                .param("area", areaCode)
                .param("wh", warehouseCode)
                .param("cap", MAX_ZONES_PER_AREA + 1)
                .query(RecordRowMapper.of(ZoneRow.class))
                .list();
    }

    public int maxZonesPerArea() {
        return MAX_ZONES_PER_AREA;
    }

    /**
     * Total OPEN unassigned pick_lists in a zone. Used as the denominator for
     * {@code queueRankAmongOpen} so the agent can quote "rank N of M".
     */
    public int countOpenPickListsInZone(Long zoneId) {
        if (zoneId == null) {
            return 0;
        }
        Long n = stockholm.sql("""
                        SELECT count(*) FROM pick_list pl
                         WHERE pl.allotted_zone = :zone
                           AND pl.status = 'OPEN'
                           AND pl.picker_id IS NULL
                        """)
                .param("zone", zoneId)
                .query(Long.class)
                .single();
        return n == null ? 0 : n.intValue();
    }

    /**
     * Queue rank for one OPEN unassigned pick_list within its allotted zone, mirroring the
     * production picker-queue ordering from {@code PickListRepo.java:85–92}:
     * {@code ORDER BY ppl.precedence NULLS LAST, pl.priority DESC, pl.sub_level_priority DESC,
     * pl.created_date ASC}. Returns the 1-based rank (1 = head of queue).
     *
     * <p>Returns {@code null} if the pick_list isn't OPEN-and-unassigned (rank doesn't apply).
     */
    public Integer rankOpenPickListInZone(
            long pickListId,
            Long allottedZoneId,
            Integer precedence,
            Long priority,
            Long subLevelPriority,
            Instant createdDate) {
        if (allottedZoneId == null || createdDate == null) {
            return null;
        }
        // INT_MAX sentinel for null precedence so NULLs sort LAST in ASC ordering.
        int precedenceCmp = precedence == null ? Integer.MAX_VALUE : precedence;
        long priorityCmp = priority == null ? Long.MIN_VALUE : priority;
        long subLevelCmp = subLevelPriority == null ? Long.MIN_VALUE : subLevelPriority;
        Long aheadCount = stockholm.sql("""
                        SELECT count(*) FROM pick_list pl2
                          LEFT JOIN picking_priority_level ppl2 ON ppl2.id = pl2.picking_priority_level
                         WHERE pl2.allotted_zone = :zone
                           AND pl2.status        = 'OPEN'
                           AND pl2.picker_id IS NULL
                           AND pl2.id            <> :plId
                           AND (
                                 COALESCE(ppl2.precedence, 2147483647) <  :prec
                              OR (COALESCE(ppl2.precedence, 2147483647) = :prec AND COALESCE(pl2.priority, -9223372036854775808) >  :pri)
                              OR (COALESCE(ppl2.precedence, 2147483647) = :prec AND COALESCE(pl2.priority, -9223372036854775808) =  :pri AND COALESCE(pl2.sub_level_priority, -9223372036854775808) >  :sub)
                              OR (COALESCE(ppl2.precedence, 2147483647) = :prec AND COALESCE(pl2.priority, -9223372036854775808) =  :pri AND COALESCE(pl2.sub_level_priority, -9223372036854775808) =  :sub AND pl2.created_date <  :created)
                               )
                        """)
                .param("zone", allottedZoneId)
                .param("plId", pickListId)
                .param("prec", precedenceCmp)
                .param("pri", priorityCmp)
                .param("sub", subLevelCmp)
                // pgjdbc 42.7.x rejects setObject(Instant); bind via OffsetDateTime in UTC
                // (pl.created_date is timestamptz, so the explicit offset round-trips cleanly).
                .param("created", createdDate.atOffset(java.time.ZoneOffset.UTC))
                .query(Long.class)
                .single();
        if (aheadCount == null) {
            return 1;
        }
        return aheadCount.intValue() + 1;
    }

    /** Slim picker lookup by code (e.g. 'PIC-0000023391') or numeric id. */
    public Optional<PickerStateRow> findPickerByCode(String code) {
        return stockholm.sql("""
                        SELECT p.id, p.code, p.name, p.warehouse AS warehouse_id,
                               w.code AS warehouse_code, p.status, p.last_login_time AT TIME ZONE 'UTC' AS last_login_time,
                               p.active, p.deleted, p.type
                          FROM picker p
                          LEFT JOIN warehouse w ON w.id = p.warehouse
                         WHERE p.code = :code
                        """)
                .param("code", code)
                .query(RecordRowMapper.of(PickerStateRow.class))
                .optional();
    }

    public Optional<PickerStateRow> findPickerById(long id) {
        return stockholm.sql("""
                        SELECT p.id, p.code, p.name, p.warehouse AS warehouse_id,
                               w.code AS warehouse_code, p.status, p.last_login_time AT TIME ZONE 'UTC' AS last_login_time,
                               p.active, p.deleted, p.type
                          FROM picker p
                          LEFT JOIN warehouse w ON w.id = p.warehouse
                         WHERE p.id = :id
                        """)
                .param("id", id)
                .query(RecordRowMapper.of(PickerStateRow.class))
                .optional();
    }

    /** Zone groups a picker belongs to, with name. */
    public List<ZoneGroupRow> findZoneGroupsByPicker(long pickerId) {
        return stockholm.sql("""
                        SELECT zg.id, zg.zone_group_name AS zone_group_name
                          FROM picker_zone_group pzg
                          JOIN zone_group zg ON zg.id = pzg.zone_group_id
                         WHERE pzg.picker_id = :pickerId
                           AND zg.active AND NOT zg.deleted
                         ORDER BY zg.id
                        """)
                .param("pickerId", pickerId)
                .query(RecordRowMapper.of(ZoneGroupRow.class))
                .list();
    }

    /** Zones inside a zone_group. Caller caps + flags truncation. */
    public List<ZoneRow> findZonesInZoneGroup(long zoneGroupId, int limit) {
        return stockholm.sql("""
                        SELECT z.id, z.zone_code, z.zone_name
                          FROM zone_zone_group zzg
                          JOIN zone z ON z.id = zzg.zone_id
                         WHERE zzg.zone_group_id = :zgId
                           AND z.active AND NOT z.deleted
                         ORDER BY z.id
                         LIMIT :lim
                        """)
                .param("zgId", zoneGroupId)
                .param("lim", limit)
                .query(RecordRowMapper.of(ZoneRow.class))
                .list();
    }

    public int countZonesInZoneGroup(long zoneGroupId) {
        Long n = stockholm.sql("""
                        SELECT count(*)
                          FROM zone_zone_group zzg
                          JOIN zone z ON z.id = zzg.zone_id
                         WHERE zzg.zone_group_id = :zgId
                           AND z.active AND NOT z.deleted
                        """)
                .param("zgId", zoneGroupId)
                .query(Long.class)
                .single();
        return n == null ? 0 : n.intValue();
    }

    /**
     * Open + unassigned pick_lists across the given zones, ordered by the operational
     * picker-queue ordering (ppl.precedence asc nulls last → priority desc → sub_level
     * desc → created_date asc). Joins one PP code as a sample for the agent's chain hop.
     * Caller passes {@code limit + 1} to detect truncation.
     */
    public List<OpenPickListRow> findOpenPickListsInZones(Collection<Long> zoneIds, int limit) {
        if (zoneIds == null || zoneIds.isEmpty()) {
            return List.of();
        }
        return stockholm.sql("""
                        SELECT pl.id              AS pick_list_id,
                               pl.status          AS pick_list_status,
                               pl.allotted_zone   AS allotted_zone_id,
                               z.zone_code        AS allotted_zone_code,
                               pl.priority,
                               pl.picking_priority_level AS picking_priority_level_id,
                               ppl.precedence     AS picking_priority_precedence,
                               pl.sub_level_priority,
                               pl.created_date,
                               (SELECT pp.code FROM pick_list_details pld
                                  JOIN pick_package pp ON pp.id = pld.pick_package_id
                                 WHERE pld.pick_list_id = pl.id
                                 ORDER BY pld.id LIMIT 1)  AS pick_package_code_sample
                          FROM pick_list pl
                          LEFT JOIN zone z ON z.id = pl.allotted_zone
                          LEFT JOIN picking_priority_level ppl ON ppl.id = pl.picking_priority_level
                         WHERE pl.allotted_zone IN (:zoneIds)
                           AND pl.status = 'OPEN'
                           AND pl.picker_id IS NULL
                         ORDER BY COALESCE(ppl.precedence, 2147483647),
                                  pl.priority DESC,
                                  pl.sub_level_priority DESC,
                                  pl.created_date,
                                  pl.id
                         LIMIT :lim
                        """)
                .param("zoneIds", zoneIds)
                .param("lim", limit)
                .query(RecordRowMapper.of(OpenPickListRow.class))
                .list();
    }

    /** Per-zone pick_list activity counts: open+unassigned, open total, all statuses. */
    public List<ZoneActivityRow> countPickListActivityPerZone(Collection<Long> zoneIds) {
        if (zoneIds == null || zoneIds.isEmpty()) {
            return List.of();
        }
        return stockholm.sql("""
                        SELECT z.id                                            AS zone_id,
                               z.zone_code                                     AS zone_code,
                               count(*) FILTER (WHERE pl.status = 'OPEN' AND pl.picker_id IS NULL) AS open_unassigned,
                               count(*) FILTER (WHERE pl.status = 'OPEN')      AS open_total,
                               count(*)                                        AS total_any_status
                          FROM zone z
                          LEFT JOIN pick_list pl ON pl.allotted_zone = z.id
                         WHERE z.id IN (:zoneIds)
                         GROUP BY z.id, z.zone_code
                         ORDER BY z.id
                        """)
                .param("zoneIds", zoneIds)
                .query(RecordRowMapper.of(ZoneActivityRow.class))
                .list();
    }

    /**
     * Sibling pickers — pickers other than {@code excludePickerId} who share at least one
     * zone_group with the given list. Returns the same shape as {@link #findEligiblePickersForZones}
     * so the caller can reuse the status-breakdown helper.
     */
    public List<PickerRow> findSiblingPickers(Collection<Long> zoneGroupIds, long excludePickerId) {
        if (zoneGroupIds == null || zoneGroupIds.isEmpty()) {
            return List.of();
        }
        return stockholm.sql("""
                        SELECT DISTINCT p.id, p.code, p.name, p.status, p.last_login_time AT TIME ZONE 'UTC' AS last_login_time
                          FROM picker p
                          JOIN picker_zone_group pzg ON pzg.picker_id = p.id
                         WHERE pzg.zone_group_id IN (:zoneGroupIds)
                           AND p.id <> :excludeId
                           AND p.active AND NOT p.deleted
                         ORDER BY p.id
                         LIMIT :cap
                        """)
                .param("zoneGroupIds", zoneGroupIds)
                .param("excludeId", excludePickerId)
                .param("cap", MAX_PICKERS_PER_QUERY)
                .query(RecordRowMapper.of(PickerRow.class))
                .list();
    }

    /**
     * Find candidate (PP, pick_list, zone) tuples for the picker-availability bottleneck:
     * open unclaimed pick_lists at the given site whose allotted_zone has eligible
     * pickers but ZERO are AVAILABLE (i.e. all are BUSY / OFFLINE / on break).
     *
     * <p>One row per (pp, pick_list) — the same zone can appear multiple times if
     * multiple PPs share it. Caller groups by {@code zone_id} in Java.
     *
     * <p>Caller passes {@code limit + 1} to detect truncation.
     */
    public List<BlockedPpRow> findPpsBlockedByPickerAvailability(String siteCode, int limit) {
        if (siteCode == null || siteCode.isBlank()) {
            return List.of();
        }
        return stockholm.sql("""
                        WITH zone_picker_counts AS (
                          SELECT zzg.zone_id,
                                 count(DISTINCT p.id) FILTER (WHERE p.active AND NOT p.deleted)
                                                                                              AS eligible,
                                 count(DISTINCT p.id) FILTER (WHERE p.active AND NOT p.deleted
                                                                  AND p.status = 'AVAILABLE') AS available
                            FROM zone_zone_group zzg
                            JOIN picker_zone_group pzg ON pzg.zone_group_id = zzg.zone_group_id
                            JOIN picker p ON p.id = pzg.picker_id
                            JOIN warehouse w ON w.id = p.warehouse
                           WHERE w.code = :siteCode
                           GROUP BY zzg.zone_id
                        )
                        SELECT pp.id                            AS pp_id,
                               pp.code                          AS pp_code,
                               pp.picking_status,
                               pl.id                            AS pick_list_id,
                               pl.status                        AS pick_list_status,
                               pl.allotted_zone                 AS zone_id,
                               z.zone_code,
                               zpc.eligible                     AS eligible_picker_count,
                               pp.created_date AT TIME ZONE 'UTC' AS pp_created_date,
                               pl.created_date                  AS pick_list_created_date
                          FROM pick_package pp
                          JOIN pick_list_details pld ON pld.pick_package_id = pp.id
                          JOIN pick_list pl ON pl.id = pld.pick_list_id
                          JOIN zone z ON z.id = pl.allotted_zone
                          JOIN warehouse w ON w.id = pl.warehouse_id
                          JOIN zone_picker_counts zpc ON zpc.zone_id = pl.allotted_zone
                         WHERE w.code = :siteCode
                           AND pl.status = 'OPEN'
                           AND pl.picker_id IS NULL
                           AND NOT pp.canceled
                           AND zpc.eligible > 0
                           AND zpc.available = 0
                         GROUP BY pp.id, pp.code, pp.picking_status,
                                  pl.id, pl.status, pl.allotted_zone, z.zone_code,
                                  zpc.eligible, pp.created_date, pl.created_date
                         ORDER BY z.zone_code, pp.created_date, pp.id
                         LIMIT :lim
                        """)
                .param("siteCode", siteCode)
                .param("lim", limit)
                .query(RecordRowMapper.of(BlockedPpRow.class))
                .list();
    }

    /** Slim row produced by {@link #findPpsBlockedByPickerAvailability}. */
    public record BlockedPpRow(
            long ppId,
            String ppCode,
            String pickingStatus,
            long pickListId,
            String pickListStatus,
            Long zoneId,
            String zoneCode,
            int eligiblePickerCount,
            java.time.Instant ppCreatedDate,
            java.time.Instant pickListCreatedDate) {
    }

    public int maxOpenPickListsSample() {
        return 20;
    }

    public int maxZonesPerZoneGroup() {
        return MAX_ZONES_PER_AREA;
    }

    // ─── row records ─────────────────────────────────────────────────────────

    public record PickerRow(
            long id,
            String code,
            String name,
            String status,
            Instant lastLoginTime) {
    }

    public record PickerStateRow(
            long id,
            String code,
            String name,
            Long warehouseId,
            String warehouseCode,
            String status,
            Instant lastLoginTime,
            boolean active,
            boolean deleted,
            String type) {
    }

    public record ZoneRow(
            long id,
            String zoneCode,
            String zoneName) {
    }

    public record ZoneGroupRow(long id, String zoneGroupName) {
    }

    public record OpenPickListRow(
            long pickListId,
            String pickListStatus,
            Long allottedZoneId,
            String allottedZoneCode,
            Long priority,
            Long pickingPriorityLevelId,
            Integer pickingPriorityPrecedence,
            Long subLevelPriority,
            Instant createdDate,
            String pickPackageCodeSample) {
    }

    public record ZoneActivityRow(
            long zoneId,
            String zoneCode,
            long openUnassigned,
            long openTotal,
            long totalAnyStatus) {
    }
}
