package com.gdn.opsvision.mcp.tool;

import java.util.List;
import java.util.Optional;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import com.gdn.opsvision.mcp.dto.PickListReadinessEvidence;
import com.gdn.opsvision.mcp.dto.PickListReadinessEvidence.RuleEvaluation;
import com.gdn.opsvision.mcp.dto.PickListReadinessEvidence.Summary;
import com.gdn.opsvision.mcp.dto.PickPackageEvidence;
import com.gdn.opsvision.mcp.repository.PickPackageRepository;
import com.gdn.opsvision.mcp.repository.StuckRulesRepository;

@Service
public class PickListReadinessTool {

    private final PickPackageRepository pickPackageRepo;
    private final StuckRulesRepository rulesRepo;

    public PickListReadinessTool(
            PickPackageRepository pickPackageRepo,
            StuckRulesRepository rulesRepo) {
        this.pickPackageRepo = pickPackageRepo;
        this.rulesRepo = rulesRepo;
    }

    @Tool(description = """
            Run the 13-rule "is this pick package truly stuck" checklist for a given PP. The \
            rules come from the SCPS support team's stuck-pick-packages playbook and look at \
            pp/so state, active pick_list_details, picking_task and picking_task_request, \
            problem_solve_tasks, and remaining picking_items. Each rule returns pass/fail plus \
            the inputs that drove it (counts, status values, IDs).

            Returns FACTS, not VERDICTS. A failed rule does NOT mean "bug" — many failures are \
            valid operational states (excel picker, wave picking, batch picking, in problem-solve, \
            short pick). The agent reads the rules array, sees what failed, and decides what the \
            combination means. ALL 13 rules passing = a candidate "truly stuck" PP worth deeper \
            investigation (and likely a STOC ticket).

            ID format: pick package code like 'PK/MAR-01/V-2026/7747838', or numeric pick_package.id.
            """)
    public PickListReadinessEvidence evaluatePickListReadiness(
            @ToolParam(description = "Pick package code (PK/MAR-...) or numeric id") String idOrCode) {

        Optional<PickPackageEvidence.Header> header = lookupHeader(idOrCode);
        if (header.isEmpty()) {
            return new PickListReadinessEvidence(null, List.of(), new Summary(0, 0, 0));
        }
        long ppId = header.get().id();
        List<RuleEvaluation> rules = rulesRepo.evaluateAll(ppId);
        int passed = (int) rules.stream().filter(RuleEvaluation::pass).count();
        Summary summary = new Summary(rules.size(), passed, rules.size() - passed);
        return new PickListReadinessEvidence(header.get(), rules, summary);
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
