package com.gdn.opsvision.mcp.tool;

import java.util.List;
import java.util.Optional;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import com.gdn.opsvision.mcp.dto.MovementHistoryEvidence;
import com.gdn.opsvision.mcp.dto.PickPackageEvidence;
import com.gdn.opsvision.mcp.repository.MovementRepository;
import com.gdn.opsvision.mcp.repository.PickPackageRepository;

@Service
public class MovementHistoryTool {

    private final PickPackageRepository pickPackageRepo;
    private final MovementRepository movementRepo;

    public MovementHistoryTool(
            PickPackageRepository pickPackageRepo,
            MovementRepository movementRepo) {
        this.pickPackageRepo = pickPackageRepo;
        this.movementRepo = movementRepo;
    }

    @Tool(description = """
            Fetch the warehouse-movement-DB lifecycle for one pick package: every \
            picking_task_request row (cross-PP-flow demand) plus every picking_task row \
            (per-SKU pick assignments), each with its current status, previous_status (the \
            most recent transition source), created_date, last_modified_date, \
            last_modified_by, type, and any failure_reason / reason / \
            multi_sku_batch_failed_reason fields. Tasks also carry source_area_code, sku_code, \
            quantity, picking_task_list (= the pick list this task fed into), \
            stock_trace_id (= the same UUID surfaced in stockholm.picking_item.stock_trace_id \
            and as input to getStockTrace), and retry_count.

            Use this when investigating: tasks that closed too fast (compare created_date / \
            last_modified_date deltas — a few seconds typically means SYSTEM closure, often \
            via PENDING_CLOSED → CLOSED, which can leave PLDs in stockholm un-decremented; \
            cross-check with getStockTrace on the task's stock_trace_id to confirm whether a \
            real DECREASE_BIN_* event occurred); requests stuck in HOLD / CREATED with no \
            corresponding tasks (the request never spawned work); tasks with a populated \
            failure_reason or non-zero retry_count; or to bridge from a movement task back \
            into the stock audit chain via stock_trace_id.

            ID format: pick package code like 'PK/MAR-01/V-2026/7747838', or numeric \
            pick_package.id. Resolves the PP via stockholm first; if the PP doesn't exist the \
            evidence pack is {pickPackage: null, taskRequests: [], tasks: []}.

            Returns FACTS, not VERDICTS. A CLOSED task is NOT necessarily a successful pick — \
            the agent has to cross-reference stock_trace_id with getStockTrace, or PLD status \
            via evaluatePickListReadiness rule 6, to confirm the stock side actually moved.
            """)
    public MovementHistoryEvidence getMovementHistory(
            @ToolParam(description = "Pick package code (PK/MAR-...) or numeric id") String idOrCode) {
        Optional<PickPackageEvidence.Header> header = lookupHeader(idOrCode);
        if (header.isEmpty()) {
            return new MovementHistoryEvidence(null, List.of(), List.of());
        }
        long ppId = header.get().id();
        return new MovementHistoryEvidence(
                header.get(),
                movementRepo.findRequestsForPp(ppId),
                movementRepo.findTasksForPp(ppId));
    }

    private Optional<PickPackageEvidence.Header> lookupHeader(String idOrCode) {
        if (idOrCode == null || idOrCode.isBlank()) {
            return Optional.empty();
        }
        String trimmed = idOrCode.trim();
        if (trimmed.chars().allMatch(Character::isDigit)) {
            return pickPackageRepo.findHeaderById(Long.parseLong(trimmed));
        }
        return pickPackageRepo.findHeaderByCode(trimmed);
    }
}
