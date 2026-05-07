package com.gdn.opsvision.mcp.repository;

import com.gdn.opsvision.mcp.repository.support.RecordRowMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lookup for the stockholm {@code warehouse_defect_map} table — pairs each distribution
 * warehouse with its non-distribution / damage sibling.
 *
 * <p>The mapping is what stockholm uses in
 * {@code WarehouseService.getStockIndicatorForWarehouse} (see lines 634–644 of
 * {@code stockholm/InventoryService/.../WarehouseService.java}) to translate a stockholm
 * warehouse code into the {@code (parent_warehouse_code, StockIndicator)} tuple that
 * warehouse-inventory persists. So a {@code warehouse_item_master} row with
 * {@code (warehouse=MAR-0000000001, stock_indicator=RESTRICTED)} represents stock
 * physically held at the defect sibling — for Marunda, that's {@code MAN-0000000002}
 * ("Marunda Non Dist").
 *
 * <p>Loaded lazily on first access and cached for the lifetime of the process. The map
 * is small (~tens of rows in QA2) and only changes when warehouses are added or
 * decommissioned, so a cold restart picks up changes.
 */
@Repository
@Transactional(readOnly = true)
public class WarehouseDefectMapRepository {

    private final JdbcClient stockholm;

    /**
     * Snapshot of the mapping. {@code null} until the first call materializes it; access
     * goes through {@link #snapshot()} which double-checks under a lock.
     */
    private volatile MapSnapshot cache;

    public WarehouseDefectMapRepository(@Qualifier("stockholmJdbcClient") JdbcClient stockholm) {
        this.stockholm = stockholm;
    }

    /**
     * Defect-side code for a distribution warehouse, if one is mapped. Empty if the input
     * is not a parent code (either it's the defect side itself, or it has no defect pairing).
     */
    public Optional<String> defectCodeFor(String parentWarehouseCode) {
        if (parentWarehouseCode == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot().parentToDefect.get(parentWarehouseCode));
    }

    /**
     * Parent (distribution) code for a defect warehouse, if one is mapped. Empty if the
     * input is itself a parent or has no entry at all.
     */
    public Optional<String> parentCodeFor(String defectWarehouseCode) {
        if (defectWarehouseCode == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot().defectToParent.get(defectWarehouseCode));
    }

    /** Force a reload on next access. Intended for tests only. */
    void invalidate() {
        cache = null;
    }

    private MapSnapshot snapshot() {
        MapSnapshot s = cache;
        if (s != null) {
            return s;
        }
        synchronized (this) {
            if (cache == null) {
                cache = load();
            }
            return cache;
        }
    }

    private MapSnapshot load() {
        List<Row> rows = stockholm.sql(
                """
                SELECT warehouse_code, warehouse_defect_code
                  FROM warehouse_defect_map
                """)
                .query(RecordRowMapper.of(Row.class))
                .list();
        Map<String, String> p2d = new HashMap<>(rows.size() * 2);
        Map<String, String> d2p = new HashMap<>(rows.size() * 2);
        for (Row r : rows) {
            if (r.warehouseCode() != null && r.warehouseDefectCode() != null) {
                p2d.put(r.warehouseCode(), r.warehouseDefectCode());
                d2p.put(r.warehouseDefectCode(), r.warehouseCode());
            }
        }
        return new MapSnapshot(Map.copyOf(p2d), Map.copyOf(d2p));
    }

    private record MapSnapshot(Map<String, String> parentToDefect, Map<String, String> defectToParent) {
    }

    public record Row(String warehouseCode, String warehouseDefectCode) {
    }
}
