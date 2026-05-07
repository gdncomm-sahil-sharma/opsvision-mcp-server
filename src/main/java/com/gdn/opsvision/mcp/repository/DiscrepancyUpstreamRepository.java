package com.gdn.opsvision.mcp.repository;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stockholm-side cross-DB resolver used by {@code findStockDiscrepancyOrigin} to
 * walk a divergent trace's first-event {@code (reference_type, reference_id)} pair
 * to its upstream entity (picking_item · sales_order · pick_package · order_item_id).
 *
 * <p>Best-effort: each method returns {@link Optional#empty()} if the lookup row is
 * gone (legacy hard-deleted FK target) or the reference_id isn't a parseable long.
 * Tool layer turns empty results into a {@code resolutionNote} on the response.
 */
@Repository
@Transactional(readOnly = true)
public class DiscrepancyUpstreamRepository {

    private final JdbcClient stockholm;

    public DiscrepancyUpstreamRepository(@Qualifier("stockholmJdbcClient") JdbcClient stockholm) {
        this.stockholm = stockholm;
    }

    /**
     * Resolve a {@code picking_item.id} → SO + PP. Used when a stock_history row's
     * reference_type is {@code PICKING_ITEM}.
     */
    public Optional<UpstreamRow> findByPickingItem(long pickingItemId) {
        return stockholm.sql("""
                        SELECT pi.id                AS picking_item_id,
                               pi.sales_order       AS sales_order_id,
                               so.sales_order_number,
                               so.order_item_id,
                               so.pick_package_id   AS pick_package_id,
                               pp.code              AS pick_package_code
                          FROM picking_item pi
                          LEFT JOIN sales_order so ON so.id = pi.sales_order
                          LEFT JOIN pick_package pp ON pp.id = so.pick_package_id
                         WHERE pi.id = :id
                        """)
                .param("id", pickingItemId)
                .query(com.gdn.opsvision.mcp.repository.support.RecordRowMapper.of(UpstreamRow.class))
                .optional();
    }

    /**
     * Resolve a {@code pick_list_details.id} → PP / SO / picking_item / pp.code. Used
     * when a stock_history row's reference_type is {@code PICK_LIST_DETAILS}.
     */
    public Optional<UpstreamRow> findByPickListDetails(long pldId) {
        return stockholm.sql("""
                        SELECT pld.picking_item_id              AS picking_item_id,
                               pld.sales_order_id               AS sales_order_id,
                               so.sales_order_number,
                               so.order_item_id,
                               pld.pick_package_id              AS pick_package_id,
                               pp.code                          AS pick_package_code
                          FROM pick_list_details pld
                          LEFT JOIN sales_order so ON so.id = pld.sales_order_id
                          LEFT JOIN pick_package pp ON pp.id = pld.pick_package_id
                         WHERE pld.id = :id
                        """)
                .param("id", pldId)
                .query(com.gdn.opsvision.mcp.repository.support.RecordRowMapper.of(UpstreamRow.class))
                .optional();
    }

    /**
     * Resolve a {@code pick_package.id} directly to its code. Used when a stock_history
     * row's reference_type is {@code PICK_PACKAGE}.
     */
    public Optional<UpstreamRow> findByPickPackage(long ppId) {
        return stockholm.sql("""
                        SELECT NULL::bigint  AS picking_item_id,
                               NULL::bigint  AS sales_order_id,
                               NULL::text    AS sales_order_number,
                               NULL::text    AS order_item_id,
                               pp.id         AS pick_package_id,
                               pp.code       AS pick_package_code
                          FROM pick_package pp
                         WHERE pp.id = :id
                        """)
                .param("id", ppId)
                .query(com.gdn.opsvision.mcp.repository.support.RecordRowMapper.of(UpstreamRow.class))
                .optional();
    }

    /**
     * Best-effort resolution shape. Each field is non-null only when the underlying
     * row had it (e.g. {@code orderItemId} stays null for B2B / consolidation SOs that
     * legitimately don't carry an upstream e-commerce id).
     */
    public record UpstreamRow(
            Long pickingItemId,
            Long salesOrderId,
            String salesOrderNumber,
            String orderItemId,
            Long pickPackageId,
            String pickPackageCode) {
    }
}
