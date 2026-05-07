package com.gdn.opsvision.mcp.dto;

import java.util.List;

/**
 * Evidence pack for {@code findOrdersByLastProcessDate} — operational dashboard view of
 * sales orders at a site, split into two sections with distinct counting semantics:
 *
 * <ul>
 *   <li>{@link #activeSnapshot} — SOs <i>currently</i> in any non-terminal status. Counts
 *       are a live snapshot regardless of {@code last_process_date}; the date window does
 *       NOT filter this section. Answers "how many orders are pending right now?".</li>
 *   <li>{@link #terminalInWindow} — SOs that reached a terminal status (CANCELLED,
 *       REJECTED, OUT_OF_STOCK_CANCEL, ITEM_ISSUED) inside the {@code [from, to)} window,
 *       judged by {@code last_process_date}. Answers "how many shipped today?",
 *       "how many cancelled this week?".</li>
 * </ul>
 *
 * <p>Each bucket carries up to {@code sampleSize} {@code orderItemId}s for drill-down —
 * pass them to {@code getSalesOrder} to walk individual investigations.
 *
 * <p>Returns FACTS, not VERDICTS. The agent reads the buckets and decides what the
 * distribution means (healthy, drifted, spiking).
 */
public record OrdersByLastProcessDateEvidence(
        String siteCode,
        String sinceDate,
        String untilDate,
        String bucketBy,
        int sampleSize,
        Section activeSnapshot,
        Section terminalInWindow) {

    /** One section of the response. */
    public record Section(long total, List<Bucket> buckets) {
    }

    /**
     * One bucket within a section. {@code bucket} is the human-readable identifier:
     * <ul>
     *   <li>For {@code bucketBy=status}: the SOStatus label (e.g. "ITEM_PICKED",
     *       "OUT_OF_STOCK") — unmapped ordinals surface as "STATUS_n".</li>
     *   <li>For {@code bucketBy=lifecycleStage}: the SalesOrderLifecycleStage name
     *       (e.g. "IN_FULFILLMENT", "TERMINATED").</li>
     * </ul>
     */
    public record Bucket(
            String bucket,
            long count,
            List<String> sampleOrderItemIds) {
    }
}
