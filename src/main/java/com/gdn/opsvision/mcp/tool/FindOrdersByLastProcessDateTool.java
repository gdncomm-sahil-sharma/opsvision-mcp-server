package com.gdn.opsvision.mcp.tool;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import com.gdn.opsvision.mcp.dto.OrdersByLastProcessDateEvidence;
import com.gdn.opsvision.mcp.dto.OrdersByLastProcessDateEvidence.Bucket;
import com.gdn.opsvision.mcp.dto.OrdersByLastProcessDateEvidence.Section;
import com.gdn.opsvision.mcp.dto.SalesOrderLifecycleStage;
import com.gdn.opsvision.mcp.repository.SalesOrderRepository;
import com.gdn.opsvision.mcp.repository.SalesOrderRepository.StatusBucketWithSamplesRow;
import com.gdn.opsvision.mcp.tool.util.IsoBound;

/**
 * Operational dashboard view of sales orders at a site: "what's currently pending?"
 * answered as a snapshot of active statuses, alongside "what reached a terminal state in
 * the window?" answered as a date-windowed count of CANCELLED / ITEM_ISSUED / REJECTED /
 * OUT_OF_STOCK_CANCEL. Each bucket carries up to {@code sampleSize} {@code orderItemId}s
 * for drill-down via {@code getSalesOrder}.
 */
@Service
public class FindOrdersByLastProcessDateTool {

    private static final int DEFAULT_SAMPLE_SIZE = 3;
    private static final int MAX_SAMPLE_SIZE = 50;
    private static final String DEFAULT_BUCKET_BY = "status";

    private final SalesOrderRepository repo;

    public FindOrdersByLastProcessDateTool(SalesOrderRepository repo) {
        this.repo = repo;
    }

    @Tool(description = """
            Operational dashboard view of sales orders at a site, with two sections of \
            distinct counting semantics:

              activeSnapshot   - SOs currently in any non-terminal status (snapshot, \
                                 ignores the date window). Answers "what's pending right \
                                 now?", "how many are STOCK_RESERVED at MAR?".
              terminalInWindow - SOs that reached a terminal status (CANCELLED, \
                                 ITEM_ISSUED, REJECTED, OUT_OF_STOCK_CANCEL) inside \
                                 [sinceDate, untilDate) judged by \
                                 sales_order.last_process_date. Answers "what shipped \
                                 today?", "how many cancelled this week?".

            Each bucket carries up to sampleSize most-recent orderItemIds for drill-down \
            via getSalesOrder.

            For a flat histogram across ALL statuses without the active/terminal split \
            and without drill-down samples, use aggregateSalesOrdersByStatus.

            Inputs:
              siteCode    required, warehouse.code (e.g. 'MAR-0000000001').
              sinceDate   required for the terminal-in-window section. ISO date \
                          ('YYYY-MM-DD' = start of day) or datetime \
                          ('YYYY-MM-DDTHH:MM:SS' = inclusive moment).
              untilDate   optional upper bound. ISO date (= exclusive next-day) or \
                          datetime (= exclusive moment). For "today only", pass the \
                          same date as sinceDate. Omit to leave the window open-ended \
                          on the upper side.
              bucketBy    'status' (default) — one bucket per SOStatus label (e.g. \
                          ITEM_PICKED, OUT_OF_STOCK, PICK_PACKAGE_CREATED).
                          'lifecycleStage' — coarse roll-up by SalesOrderLifecycleStage \
                          (PRE_FULFILLMENT, IN_FULFILLMENT, POST_PICK, TERMINATED, \
                          etc.). The bucket label maps directly to the stage name.
              sampleSize  default 3, max 50. Per-bucket cap on the drill-down \
                          orderItemIds list. Samples are ordered most-recent first by \
                          last_process_date.

            Date semantics: the window applies ONLY to terminalInWindow. activeSnapshot \
            is always live. untilDate defaults to "no upper bound" when omitted.

            Returns FACTS, not VERDICTS. The agent reads each section and decides what \
            the distribution means.
            """)
    public OrdersByLastProcessDateEvidence findOrdersByLastProcessDate(
            @ToolParam(description = "Site / warehouse code (e.g. 'MAR-0000000001')") String siteCode,
            @ToolParam(description = "Lower bound on last_process_date for the terminalInWindow section. ISO date (YYYY-MM-DD = start of day) or datetime (YYYY-MM-DDTHH:MM:SS = inclusive moment).") String sinceDate,
            @ToolParam(description = "Optional upper bound on last_process_date. ISO date (exclusive next-day) or datetime (exclusive moment). Omit for no upper bound.", required = false) String untilDate,
            @ToolParam(description = "Bucketing dimension: 'status' (default) for one bucket per SOStatus label, or 'lifecycleStage' for coarse SalesOrderLifecycleStage roll-up.", required = false) String bucketBy,
            @ToolParam(description = "Per-bucket cap on drill-down orderItemIds (default 3, max 50).", required = false) Integer sampleSize) {

        String effectiveBucketBy = resolveBucketBy(bucketBy);
        int effectiveSampleSize = clampSampleSize(sampleSize);
        LocalDateTime since = IsoBound.parseSince(sinceDate);
        LocalDateTime until = untilDate == null || untilDate.isBlank()
                ? LocalDateTime.of(9999, 12, 31, 0, 0)
                : IsoBound.parseUntil(untilDate);

        List<StatusBucketWithSamplesRow> activeRows = repo.findActiveSnapshotByStatus(
                siteCode, effectiveSampleSize);
        List<StatusBucketWithSamplesRow> terminalRows = repo.findTerminalInWindowByStatus(
                siteCode, since, until, effectiveSampleSize);

        Section activeSnapshot = buildSection(activeRows, effectiveBucketBy, effectiveSampleSize);
        Section terminalInWindow = buildSection(terminalRows, effectiveBucketBy, effectiveSampleSize);

        return new OrdersByLastProcessDateEvidence(
                siteCode,
                sinceDate,
                untilDate,
                effectiveBucketBy,
                effectiveSampleSize,
                activeSnapshot,
                terminalInWindow);
    }

    private static Section buildSection(
            List<StatusBucketWithSamplesRow> rows, String bucketBy, int sampleSize) {

        long total = 0;
        for (StatusBucketWithSamplesRow r : rows) {
            total += r.soCount();
        }

        if ("lifecycleStage".equals(bucketBy)) {
            return rollupByLifecycleStage(rows, sampleSize, total);
        }
        return rollupByStatus(rows, total);
    }

    private static Section rollupByStatus(List<StatusBucketWithSamplesRow> rows, long total) {
        List<Bucket> buckets = new ArrayList<>(rows.size());
        for (StatusBucketWithSamplesRow r : rows) {
            String label = SalesOrderLifecycleStage.labelForOrdinal(r.lastStatus());
            List<String> samples = r.sampleOrderItemIds() == null
                    ? List.of()
                    : Arrays.stream(r.sampleOrderItemIds()).filter(s -> s != null).toList();
            buckets.add(new Bucket(label, r.soCount(), samples));
        }
        return new Section(total, buckets);
    }

    /**
     * Roll up status-level rows into lifecycle-stage buckets. Counts sum across statuses
     * within a stage; sample order_item_ids are merged across statuses and truncated to
     * {@code sampleSize}. Stage order: by total count DESC.
     */
    private static Section rollupByLifecycleStage(
            List<StatusBucketWithSamplesRow> rows, int sampleSize, long total) {

        Map<String, long[]> stageCounts = new LinkedHashMap<>();
        Map<String, List<String>> stageSamples = new LinkedHashMap<>();
        for (StatusBucketWithSamplesRow r : rows) {
            String stage = SalesOrderLifecycleStage.forOrdinal(r.lastStatus()).name();
            stageCounts.computeIfAbsent(stage, k -> new long[]{0})[0] += r.soCount();
            List<String> stageList = stageSamples.computeIfAbsent(stage, k -> new ArrayList<>());
            if (r.sampleOrderItemIds() != null) {
                for (String id : r.sampleOrderItemIds()) {
                    if (id != null && stageList.size() < sampleSize) {
                        stageList.add(id);
                    }
                }
            }
        }

        List<Bucket> buckets = new ArrayList<>(stageCounts.size());
        for (Map.Entry<String, long[]> e : stageCounts.entrySet()) {
            buckets.add(new Bucket(
                    e.getKey(),
                    e.getValue()[0],
                    stageSamples.getOrDefault(e.getKey(), List.of())));
        }
        buckets.sort((a, b) -> Long.compare(b.count(), a.count()));
        return new Section(total, buckets);
    }

    private static String resolveBucketBy(String bucketBy) {
        if (bucketBy == null || bucketBy.isBlank()) {
            return DEFAULT_BUCKET_BY;
        }
        String normalized = bucketBy.trim();
        if ("status".equalsIgnoreCase(normalized)) {
            return "status";
        }
        if ("lifecycleStage".equalsIgnoreCase(normalized)
                || "lifecycle_stage".equalsIgnoreCase(normalized)) {
            return "lifecycleStage";
        }
        return DEFAULT_BUCKET_BY;
    }

    private static int clampSampleSize(Integer s) {
        if (s == null || s <= 0) {
            return DEFAULT_SAMPLE_SIZE;
        }
        return Math.min(s, MAX_SAMPLE_SIZE);
    }
}
