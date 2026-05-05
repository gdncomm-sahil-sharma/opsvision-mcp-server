package com.gdn.opsvision.mcp.tool;

import java.util.List;
import java.util.Optional;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import com.gdn.opsvision.mcp.dto.PickListEvidence;
import com.gdn.opsvision.mcp.repository.PickListRepository;

@Service
public class PickListTool {

    private final PickListRepository repo;

    public PickListTool(PickListRepository repo) {
        this.repo = repo;
    }

    @Tool(description = """
            Fetch a pick list and its line items (PLDs) by numeric id from the Stockholm WMS \
            (Marunda warehouse). Use to inspect a specific pick list — its assigned picker, \
            allotted zone, priority, and the line items it contains (one per pick_list_details \
            row, with quantity, quantity_picked, source area, handling unit, stock_trace_id).

            Input is the numeric pick_list.id (typically 5-6 digits, e.g. 106641). Do NOT pass \
            the trailing numeric segment of a 'PK/MAR-01/V-2026/SEQ' pick package code — those \
            7-digit tails identify pick packages, not pick lists. For pick packages use \
            getPickPackage / evaluatePickListReadiness / reconcileInventoryVsReservation; those \
            accept the full PK/... code. To go from a pick package to its pick list(s), call \
            evaluatePickListReadiness — its rule 6 evidence (`active_plds.pickListId`) hands \
            you the right id to feed back here.

            Returns FACTS, not VERDICTS — header state + raw line item rows. The caller decides \
            what the combination means. If the pick list does not exist, returns an evidence pack \
            with {pickList: null, lineItems: []}.
            """)
    public PickListEvidence getPickList(
            @ToolParam(description = "Pick list numeric id (pick_list.id)") long pickListId) {
        Optional<PickListEvidence.Header> header = repo.findHeader(pickListId);
        if (header.isEmpty()) {
            return new PickListEvidence(null, List.of());
        }
        return new PickListEvidence(header.get(), repo.findLineItems(pickListId));
    }
}
