package com.gdn.opsvision.mcp.dto;

import java.util.List;
import java.util.Map;

/**
 * Evidence pack for the "is this PP truly stuck" 13-rule checklist.
 * <p>
 * Each rule reports {@code pass / fail} plus the input fields that drove the evaluation.
 * Returns EVIDENCE, not VERDICTS — failure of a rule does not mean "bug". Many rules failing
 * are valid operational states (excel picker, wave/batch picking, in problem-solve, etc.).
 * The agent reads the rules array and decides what the combination means.
 */
public record PickListReadinessEvidence(
        PickPackageEvidence.Header pickPackage,
        List<RuleEvaluation> rules,
        Summary summary) {

    /**
     * Single rule evaluation. {@code evidence} carries the inputs (column values, counts,
     * sample ids) that drove pass/fail — agent inspects this when a rule fails.
     */
    public record RuleEvaluation(
            String ruleId,
            String description,
            String targetDb,
            boolean pass,
            Map<String, Object> evidence) {
    }

    public record Summary(
            int rulesEvaluated,
            int rulesPassed,
            int rulesFailed) {
    }
}
