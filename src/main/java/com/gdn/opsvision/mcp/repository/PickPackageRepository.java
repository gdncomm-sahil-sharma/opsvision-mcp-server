package com.gdn.opsvision.mcp.repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.gdn.opsvision.mcp.dto.PickPackageEvidence;

@Repository
@Transactional(readOnly = true)
public class PickPackageRepository {

    private final JdbcClient stockholm;

    public PickPackageRepository(@Qualifier("stockholmJdbcClient") JdbcClient stockholm) {
        this.stockholm = stockholm;
    }

    private static final String HEADER_SELECT = """
            SELECT id, code, status, picking_status, canceled, short_pick, in_progress,
                   created_date, updated_date, auto_cancel_date, first_export_xls_time,
                   batch_id, wave_number, business_channel, channel, client_code,
                   picking_type, packing_spec_code, target_area_code, target_section_code,
                   partial_fulfillment_allowed, manual_target_area
            FROM pick_package
            """;

    public Optional<PickPackageEvidence.Header> findHeaderById(long id) {
        return stockholm.sql(HEADER_SELECT + " WHERE id = :id")
                .param("id", id)
                .query(PickPackageEvidence.Header.class)
                .optional();
    }

    public Optional<PickPackageEvidence.Header> findHeaderByCode(String code) {
        return stockholm.sql(HEADER_SELECT + " WHERE code = :code")
                .param("code", code)
                .query(PickPackageEvidence.Header.class)
                .optional();
    }

    public List<PickPackageEvidence.HandlingUnit> findHandlingUnits(long pickPackageId) {
        List<HandlingUnitRow> raws = stockholm.sql("""
                        SELECT id, pick_package, handling_unit_code, handling_unit_type_code,
                               status, target_area_code, target_section_code,
                               drop_point_code, drop_point_area,
                               transit_drop_point_code, transit_drop_point_area,
                               automation, ptl_consolidation_status, consolidation_required,
                               selected_for_packing, handling_unit_group,
                               last_modified_by, last_modified_date
                        FROM pick_package_handling_units
                        WHERE pick_package = :pp
                        ORDER BY id
                        """)
                .param("pp", pickPackageId)
                .query(HandlingUnitRow.class)
                .list();
        List<PickPackageEvidence.HandlingUnit> out = new java.util.ArrayList<>(raws.size());
        for (HandlingUnitRow r : raws) {
            com.gdn.opsvision.mcp.dto.HandlingUnitLifecycleStage stage =
                    com.gdn.opsvision.mcp.dto.HandlingUnitLifecycleStage.forStatus(r.status());
            boolean wcsStuck = "WCS".equalsIgnoreCase(r.automation())
                    && "PICKING_COMPLETE".equals(r.status())
                    && !Boolean.TRUE.equals(r.selectedForPacking());
            out.add(new PickPackageEvidence.HandlingUnit(
                    r.id(), r.pickPackage(), r.handlingUnitCode(), r.handlingUnitTypeCode(),
                    r.status(), stage,
                    r.targetAreaCode(), r.targetSectionCode(),
                    r.dropPointCode(), r.dropPointArea(),
                    r.transitDropPointCode(), r.transitDropPointArea(),
                    r.automation(), r.ptlConsolidationStatus(), r.consolidationRequired(),
                    r.selectedForPacking(), wcsStuck,
                    r.handlingUnitGroup(), r.lastModifiedBy(), r.lastModifiedDate()));
        }
        return out;
    }

    /** Raw row shape; tool layer would also work but folding into the repo keeps the
     *  HandlingUnit DTO consistent across all callers. */
    public record HandlingUnitRow(
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
            java.time.Instant lastModifiedDate) {
    }

    /**
     * Batch lookup of {@code pick_package.code} for a set of ids — used by find-tools to
     * resolve PP codes for movement-DB search results without fanning out N+1 queries.
     * Returns an empty map for an empty input. Order is insertion order of the input ids
     * for ids that exist; missing ids are simply absent from the map.
     */
    public Map<Long, String> findCodesByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        List<IdCode> rows = stockholm.sql("""
                        SELECT id, code FROM pick_package WHERE id IN (:ids)
                        """)
                .param("ids", ids)
                .query(IdCode.class)
                .list();
        Map<Long, String> out = new LinkedHashMap<>(rows.size());
        for (IdCode r : rows) {
            out.put(r.id(), r.code());
        }
        return out;
    }

    private record IdCode(long id, String code) {
    }

    public List<PickPackageEvidence.SalesOrder> findSalesOrders(long pickPackageId) {
        return stockholm.sql("""
                        SELECT id, sales_order_number, pick_package_id, last_status,
                               picking_status, picking_stuck, order_stuck_reason,
                               current_picked_quantity, quantity_picking,
                               order_id, order_item_id, order_reference_code
                        FROM sales_order
                        WHERE pick_package_id = :pp
                        ORDER BY id
                        """)
                .param("pp", pickPackageId)
                .query(PickPackageEvidence.SalesOrder.class)
                .list();
    }
}
