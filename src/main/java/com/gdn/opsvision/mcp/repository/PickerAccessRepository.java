package com.gdn.opsvision.mcp.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

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
                        SELECT DISTINCT p.id, p.code, p.name, p.status, p.last_login_time
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
                .query(PickerRow.class)
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
                .query(ZoneRow.class)
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
                .param("created", createdDate)
                .query(Long.class)
                .single();
        if (aheadCount == null) {
            return 1;
        }
        return aheadCount.intValue() + 1;
    }

    // ─── row records ─────────────────────────────────────────────────────────

    public record PickerRow(
            long id,
            String code,
            String name,
            String status,
            Instant lastLoginTime) {
    }

    public record ZoneRow(
            long id,
            String zoneCode,
            String zoneName) {
    }
}
