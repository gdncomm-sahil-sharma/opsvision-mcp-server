package com.gdn.opsvision.mcp.repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.gdn.opsvision.mcp.dto.PickListReadinessEvidence.RuleEvaluation;

/**
 * Evaluates the 13-rule "truly stuck" checklist for a single pick package.
 *
 * <p>Rules are taken from the {@code stuck-pick-packages} skill (located at
 * {@code ~/work/scps/.claude/skills/stuck-pick-packages/SKILL.md}). They were designed by
 * the support team for production use; we run them as-is and surface the inputs that
 * drove each pass/fail. We do NOT decide what failures mean — many failures are valid
 * operational states (excel picker, wave/batch picking, in problem-solve). The agent
 * inspects the evidence and concludes.
 *
 * <p>Two DataSources are used:
 * <ul>
 *   <li>{@code stockholm_marunda_restore} (DB 413) — pp + so + plds + problem solve + picking_item</li>
 *   <li>{@code warehouse_stock_movement_marunda} (DB 416) — picking_task_request + picking_task</li>
 * </ul>
 * Inventory DB is not touched by these rules.
 */
@Repository
@Transactional(readOnly = true)
public class StuckRulesRepository {

    private static final String STOCKHOLM = "stockholm";
    private static final String MOVEMENT = "movement";

    private static final List<String> ELIGIBLE_PICKING_STATUSES =
            List.of("PRIORITY_CAL_DONE", "PARTIAL_PACKAGE", "READY_FOR_MANUAL_PICKING");

    private final JdbcClient stockholm;
    private final JdbcClient movement;

    public StuckRulesRepository(
            @Qualifier("stockholmJdbcClient") JdbcClient stockholm,
            @Qualifier("movementJdbcClient") JdbcClient movement) {
        this.stockholm = stockholm;
        this.movement = movement;
    }

    /** Run all 13 rules against this PP. Caller is expected to have already resolved PP id. */
    public List<RuleEvaluation> evaluateAll(long ppId) {
        Optional<PpFacts> ppOpt = findPpFacts(ppId);
        if (ppOpt.isEmpty()) {
            return List.of();
        }
        PpFacts pp = ppOpt.get();
        SoFacts so = findSoFacts(ppId);

        return List.of(
                ppStatusOpen(pp),
                soNeedsPicking(so),
                soNotStuck(so),
                notShortPick(pp),
                pickingStatusEligible(pp),
                noActivePlds(ppId),
                noOpenTaskRequests(ppId),
                noNonClosedTasks(ppId),
                notExcelPicker(pp),
                notWave(pp),
                notBatch(pp),
                notInProblemSolve(ppId),
                picksRemaining(ppId));
    }

    // ─── shared fact records (single query each) ────────────────────────────

    private record PpFacts(
            int status,
            Boolean canceled,
            Boolean shortPick,
            String pickingStatus,
            Instant firstExportXlsTime,
            Long waveNumber,
            Long batchId) {
    }

    private record SoFacts(
            int needsPicking,
            int stuck) {
    }

    private Optional<PpFacts> findPpFacts(long ppId) {
        return stockholm.sql("""
                        SELECT status, canceled, short_pick, picking_status,
                               first_export_xls_time, wave_number, batch_id
                        FROM pick_package
                        WHERE id = :pp
                        """)
                .param("pp", ppId)
                .query(PpFacts.class)
                .optional();
    }

    private SoFacts findSoFacts(long ppId) {
        return stockholm.sql("""
                        SELECT
                          count(*) FILTER (WHERE last_status = 14)::int AS needs_picking,
                          count(*) FILTER (WHERE picking_stuck = true)::int AS stuck
                        FROM sales_order
                        WHERE pick_package_id = :pp
                        """)
                .param("pp", ppId)
                .query(SoFacts.class)
                .single();
    }

    // ─── rules 1, 4, 5, 9, 10, 11 (read from PpFacts) ───────────────────────

    private RuleEvaluation ppStatusOpen(PpFacts pp) {
        boolean canceled = Boolean.TRUE.equals(pp.canceled());
        boolean pass = pp.status() == 0 && !canceled;
        return new RuleEvaluation(
                "pp-status-open",
                "pick_package.status = 0 (OPEN) AND canceled = false",
                STOCKHOLM, pass,
                evidence("status", pp.status(), "canceled", canceled));
    }

    private RuleEvaluation notShortPick(PpFacts pp) {
        boolean shortPick = Boolean.TRUE.equals(pp.shortPick());
        return new RuleEvaluation(
                "not-short-pick",
                "pick_package.short_pick = false",
                STOCKHOLM, !shortPick,
                evidence("short_pick", shortPick));
    }

    private RuleEvaluation pickingStatusEligible(PpFacts pp) {
        String s = pp.pickingStatus();
        boolean pass = s != null && ELIGIBLE_PICKING_STATUSES.contains(s);
        return new RuleEvaluation(
                "picking-status-eligible",
                "pick_package.picking_status IN (PRIORITY_CAL_DONE, PARTIAL_PACKAGE, READY_FOR_MANUAL_PICKING)",
                STOCKHOLM, pass,
                evidence("picking_status", s, "eligible_values", ELIGIBLE_PICKING_STATUSES));
    }

    private RuleEvaluation notExcelPicker(PpFacts pp) {
        return new RuleEvaluation(
                "not-excel-picker",
                "pick_package.first_export_xls_time IS NULL",
                STOCKHOLM, pp.firstExportXlsTime() == null,
                evidence("first_export_xls_time", pp.firstExportXlsTime()));
    }

    private RuleEvaluation notWave(PpFacts pp) {
        return new RuleEvaluation(
                "not-wave",
                "pick_package.wave_number IS NULL",
                STOCKHOLM, pp.waveNumber() == null,
                evidence("wave_number", pp.waveNumber()));
    }

    private RuleEvaluation notBatch(PpFacts pp) {
        return new RuleEvaluation(
                "not-batch",
                "pick_package.batch_id IS NULL",
                STOCKHOLM, pp.batchId() == null,
                evidence("batch_id", pp.batchId()));
    }

    // ─── rules 2, 3 (read from SoFacts) ──────────────────────────────────────

    private RuleEvaluation soNeedsPicking(SoFacts so) {
        return new RuleEvaluation(
                "so-needs-picking",
                "at least one sales_order with last_status = 14 (PICK_PACKAGE_CREATED)",
                STOCKHOLM, so.needsPicking() > 0,
                evidence("count_at_status_14", so.needsPicking()));
    }

    private RuleEvaluation soNotStuck(SoFacts so) {
        return new RuleEvaluation(
                "so-not-stuck",
                "no sales_order has picking_stuck = true",
                STOCKHOLM, so.stuck() == 0,
                evidence("stuck_count", so.stuck()));
    }

    // ─── rules 6, 7, 8, 12, 13 (own queries) ─────────────────────────────────

    private RuleEvaluation noActivePlds(long ppId) {
        Integer count = stockholm.sql("""
                        SELECT count(*)::int
                        FROM pick_list_details pld
                        LEFT JOIN pick_list pl ON pl.id = pld.pick_list_id
                        WHERE pld.pick_package_id = :pp
                          AND ( pld.status = 'ADDED'
                             OR (pld.status = 'UPDATED' AND (pl.status IS NULL OR pl.status <> 'CLOSED')) )
                        """)
                .param("pp", ppId)
                .query(Integer.class)
                .single();
        return new RuleEvaluation(
                "no-active-plds",
                "no pick_list_details with status=ADDED or status=UPDATED in a non-CLOSED pick_list",
                STOCKHOLM, count == 0,
                evidence("active_pld_count", count));
    }

    private RuleEvaluation noOpenTaskRequests(long ppId) {
        Integer count = movement.sql("""
                        SELECT count(*)::int FROM picking_task_request
                        WHERE reference_id = :pp AND status <> 'CLOSED'
                        """)
                .param("pp", ppId)
                .query(Integer.class)
                .single();
        return new RuleEvaluation(
                "no-open-task-requests",
                "no picking_task_request rows with status<>CLOSED for this pp",
                MOVEMENT, count == 0,
                evidence("non_closed_request_count", count));
    }

    private RuleEvaluation noNonClosedTasks(long ppId) {
        Integer count = movement.sql("""
                        SELECT count(*)::int FROM picking_task
                        WHERE pick_package_id = :pp AND status <> 'CLOSED'
                        """)
                .param("pp", ppId)
                .query(Integer.class)
                .single();
        return new RuleEvaluation(
                "no-non-closed-tasks",
                "no picking_task rows with status<>CLOSED for this pp",
                MOVEMENT, count == 0,
                evidence("non_closed_task_count", count));
    }

    private RuleEvaluation notInProblemSolve(long ppId) {
        Integer count = stockholm.sql("""
                        SELECT count(*)::int
                        FROM problem_solve_task_details pstd
                        JOIN problem_solve_tasks pst ON pst.id = pstd.problem_solve_task
                        WHERE pstd.pick_package_id = :pp
                          AND pst.status IN ('OPEN','IN_PROGRESS')
                        """)
                .param("pp", ppId)
                .query(Integer.class)
                .single();
        return new RuleEvaluation(
                "not-in-problem-solve",
                "no open/in-progress problem_solve_task references this pp",
                STOCKHOLM, count == 0,
                evidence("open_problem_solve_count", count));
    }

    private record PicksRemainingFacts(int remaining, int total) {
    }

    private RuleEvaluation picksRemaining(long ppId) {
        PicksRemainingFacts f = stockholm.sql("""
                        SELECT
                          count(*) FILTER (WHERE status NOT IN ('COMPLETE','CANCELLED'))::int AS remaining,
                          count(*)::int AS total
                        FROM picking_item
                        WHERE sales_order IN (SELECT id FROM sales_order WHERE pick_package_id = :pp)
                        """)
                .param("pp", ppId)
                .query(PicksRemainingFacts.class)
                .single();
        return new RuleEvaluation(
                "picks-remaining",
                "at least one picking_item is NOT in (COMPLETE, CANCELLED)",
                STOCKHOLM, f.remaining() > 0,
                evidence("remaining", f.remaining(), "total", f.total()));
    }

    // ─── small helper ────────────────────────────────────────────────────────

    /**
     * Build an ordered evidence map from alternating key/value varargs. Preserves insertion
     * order (LinkedHashMap) so the JSON output is stable across runs.
     */
    private static Map<String, Object> evidence(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("evidence() requires alternating key/value pairs");
        }
        Map<String, Object> m = new LinkedHashMap<>(kv.length / 2);
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
