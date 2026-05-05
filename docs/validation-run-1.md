# Validation run 1 — opsvision-mcp tools vs. a fresh agent

**Date:** 2026-05-05
**Server commit:** `3218348` (main)
**Tools under test:** `getPickPackage`, `getPickList`, `evaluatePickListReadiness`
**Backend:** QA2 read-only (`stockholm_marunda_restore`, `warehouse_inventory_marunda`, `warehouse_movement_marunda`)

## Goal

Drive a fresh agent through 4 scenarios. Observe whether the tool descriptions, input shapes, and evidence packs are agent-readable. Capture friction. Tune in run 1 — not adding new tools yet.

## Prerequisites (already done in the parent session)

- Stack migrated to Spring Boot 4.0.6 + Spring AI 2.0.0-M5 (MCP protocol `2025-11-25` requires Streamable HTTP transport, which requires the newer SDK + Jackson 3 + Spring Boot 4).
- Server running on `localhost:8081` (`mvn spring-boot:run`, env vars sourced). Endpoint: `/mcp` (Streamable HTTP), not `/sse`.
- MCP registered in Claude Code config: `claude mcp add --transport http opsvision-mcp http://localhost:8081/mcp`.
- `claude mcp list` shows `opsvision-mcp: ✓ Connected`.
- New CC session needed — the previous session caches the old MCP registration.

## How to run

1. Open a **new** Claude Code session in this directory.
2. In that session, type `/mcp` — confirm three tools appear under `opsvision-mcp`:
   - `getPickPackage`
   - `getPickList`
   - `evaluatePickListReadiness`
3. Paste each scenario's prompt one at a time. For each, capture:
   - The tool calls the agent made (which tool, what args, in what order).
   - The agent's interpretation of the evidence.
   - Anything the agent **invented** that wasn't in the JSON (the cardinal sin).
   - Any field the agent asked about that wasn't in the response.
   - Any time the agent rerolled an argument (e.g. retried with a different `idOrCode`).
4. Paste those notes back into the parent session for analysis.

## Scenarios

### S1 — Candidate truly-stuck (all 13 rules pass)

**Prompt:**
> Investigate why pick package `PK/MAR-01/V-2026/223799` isn't being picked.

**Expected behavior:**
- Agent calls `evaluatePickListReadiness` with `{"idOrCode":"PK/MAR-01/V-2026/223799"}`.
- Sees all 13 rules PASS — `summary.rulesPassed=13, rulesFailed=0`.
- Recognizes "all rules pass" as a **candidate truly-stuck** PP per the tool description, worthy of deeper investigation / STOC ticket.
- Likely follows up with `getPickPackage` for context (picker, timestamps, HUs).

**Anti-patterns to flag:**
- Agent says "everything looks fine, no problem here" — that misreads the tool's purpose. All-rules-pass is the *suspicious* case.
- Agent invents a `root_cause` field that doesn't exist.
- Agent treats the rules array as a verdict ladder rather than evidence facts.

### S2 — HOLD task request (rule 7 should fail)

**Prompt:**
> Investigate `PK/MAR-01/V-2026/224143`. Why isn't picking moving?

**Expected behavior:**
- Agent calls `evaluatePickListReadiness`.
- Rule 7 (`no-open-task-requests`) fails — evidence shows a HOLD-status `picking_task_request`.
- Agent recognizes operator hold (legitimate pause).
- Bonus: agent calls `getPickPackage` for picker / timestamps context.

**Anti-patterns:**
- Agent calls HOLD a "stuck" or "broken" state.
- Agent doesn't notice the HOLD record at all.

### S3 — PARTIAL_PACKAGE with active PLDs (rule 6 should fail)

**Prompt:**
> Tell me about `PK/MAR-01/V-2026/223506`.

**Expected behavior:**
- Agent calls `getPickPackage` first OR `evaluatePickListReadiness` — either is fine for an open-ended "tell me about" prompt.
- If readiness is run: rule 6 (`no-active-plds`) fails with `active_pld_count=2`. Agent describes the PP as "in PARTIAL_PACKAGE with 2 active pick-list-details" — work is in flight, not stuck.
- Doesn't over-diagnose.

**Anti-patterns:**
- Agent reads a status field and renames it ("status=0 means CREATED" — fine; "status=0 means broken" — not fine).
- Agent calls a tool twice with the same args.
- Agent treats the rule-6 failure as a bug rather than "work is in progress."

### S4 — Primitive fallback (no PP involvement)

**Prompt:**
> What is pick list 106641?

**Expected behavior:**
- Agent calls `getPickList` directly with `{"pickListId": 106641}`.
- Does **not** detour through `evaluatePickListReadiness` (that's a PP-level checklist, not a pick-list lookup).

**Anti-patterns:**
- Agent picks `evaluatePickListReadiness` because the name contains "PickList."
- Agent invents a `code`-style identifier when none is needed.

## Recording template

Copy this section per scenario into your Phase 2 notes:

```
### S<N> — <name>

Tool calls (in order):
  1. <tool>(<args>) → <one-line shape of response>
  2. ...

Agent's interpretation:
  <quote or paraphrase>

Hallucinations (info not in evidence):
  - <list, or "none">

Missing-field gaps (asked-for, not provided):
  - <list, or "none">

Description ambiguities:
  - <list, or "none">

Verdict: PASS / PARTIAL / FAIL
```

## Phase 4 outputs (filled after Phase 3 analysis)

- Description edits in `tool/PickPackageTool.java`, `tool/PickListTool.java`, `tool/PickListReadinessTool.java`: <list>
- DTO field changes: <list>
- Evidence-map key tweaks in `repository/StuckRulesRepository.java`: <list>

## Done criteria

- 4 scenarios run with notes captured.
- At least one of: fewer tool calls / less over-diagnosis / fewer missing-field gaps after Phase 4 edits.
- Smoke test still passes (`python3 scripts/dev-setup/mcp_smoke.py`).
- DML guard still passes (`mvn test -Dtest=ReadOnlyEnforcementTest`).
