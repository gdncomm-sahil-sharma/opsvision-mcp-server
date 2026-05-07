package com.gdn.opsvision.mcp.repository;

import com.gdn.opsvision.mcp.repository.support.RecordRowMapper;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.gdn.opsvision.mcp.dto.PickListEvidence;
import com.gdn.opsvision.mcp.dto.PickListLifecycleStage;

@Repository
@Transactional(readOnly = true)
public class PickListRepository {

    private final JdbcClient stockholm;

    public PickListRepository(@Qualifier("stockholmJdbcClient") JdbcClient stockholm) {
        this.stockholm = stockholm;
    }

    public Optional<PickListEvidence.Header> findHeader(long pickListId) {
        Optional<HeaderRow> raw = stockholm.sql("""
                        SELECT id, name, warehouse_id, picker_id, status, allotted_zone,
                               priority, picking_priority_level, sub_level_priority,
                               picking_task_list_id, created_date, updated_date
                        FROM pick_list
                        WHERE id = :id
                        """)
                .param("id", pickListId)
                .query(RecordRowMapper.of(HeaderRow.class))
                .optional();
        return raw.map(r -> new PickListEvidence.Header(
                r.id(), r.name(), r.warehouseId(), r.pickerId(), r.status(),
                PickListLifecycleStage.forStatusAndPicker(r.status(), r.pickerId()),
                r.allottedZone(), r.priority(), r.pickingPriorityLevel(), r.subLevelPriority(),
                r.pickingTaskListId(), r.createdDate(), r.updatedDate()));
    }

    /** Raw row shape; tool layer derives lifecycleStage from (status, pickerId). */
    public record HeaderRow(
            long id,
            String name,
            long warehouseId,
            Long pickerId,
            String status,
            Long allottedZone,
            Long priority,
            Long pickingPriorityLevel,
            Long subLevelPriority,
            Long pickingTaskListId,
            java.time.Instant createdDate,
            java.time.Instant updatedDate) {
    }

    public List<PickListEvidence.LineItem> findLineItems(long pickListId) {
        return stockholm.sql("""
                        SELECT id, pick_list_id, pick_package_id, sales_order_id,
                               warehouse_item_id, quantity, quantity_picked, problem_quantity,
                               status, handling_unit_code, pick_package_handling_unit,
                               picking_task_id, source_area_code, stock_trace_id,
                               batch_id, wave_number, created_date
                        FROM pick_list_details
                        WHERE pick_list_id = :id
                        ORDER BY id
                        """)
                .param("id", pickListId)
                .query(PickListEvidence.LineItem.class)
                .list();
    }
}
