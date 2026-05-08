package com.gdn.opsvision.mcp.tool.util;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.PickerSnapshot;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.PickerStatusBreakdown;
import com.gdn.opsvision.mcp.dto.PickPackageDiagnosisEvidence.PickerStatusFreshness;
import com.gdn.opsvision.mcp.repository.PickerAccessRepository.PickerRow;

/**
 * Pure-function summaries over a picker pool: status breakdown, OFFLINE-freshness
 * histogram, and a top-N recently-online sample.
 *
 * <p>Used by both {@code DiagnosePickPackageTool} (per-PP eligible-pickers signal) and
 * {@code FindPpsBlockedByPickerAvailabilityTool} (bulk per-zone view). The helpers are
 * stateless and side-effect-free; results are evidence-shaped DTOs.
 */
public final class PickerStatusSummaries {

    private static final int RECENTLY_ONLINE_SAMPLE_SIZE = 5;

    private PickerStatusSummaries() {
    }

    public static PickerStatusBreakdown breakdown(List<PickerRow> pickers) {
        int avail = 0, busy = 0, off = 0, brkInit = 0, brkRej = 0, occ = 0, other = 0;
        for (PickerRow p : pickers) {
            String s = p.status() == null ? "" : p.status();
            switch (s) {
                case "AVAILABLE" -> avail++;
                case "BUSY" -> busy++;
                case "OFFLINE" -> off++;
                case "BREAK_INITIATED" -> brkInit++;
                case "BREAK_REJECT_PICKLIST" -> brkRej++;
                case "OCCUPIED" -> occ++;
                default -> other++;
            }
        }
        return new PickerStatusBreakdown(avail, busy, off, brkInit, brkRej, occ, other);
    }

    /**
     * Bucket OFFLINE pickers by how recently they were last seen. Bucket boundaries
     * (15 min, 1 hr, 1 day) are operational rules of thumb for "still on a break",
     * "still on shift", "same workday".
     */
    public static PickerStatusFreshness freshness(List<PickerRow> pickers, Instant now) {
        int w15 = 0, w1h = 0, w1d = 0, older = 0, unknown = 0;
        for (PickerRow p : pickers) {
            if (!"OFFLINE".equals(p.status())) {
                continue;
            }
            Instant t = p.lastLoginTime();
            if (t == null) {
                unknown++;
                continue;
            }
            long sec = Duration.between(t, now).getSeconds();
            if (sec < 0) {
                // future timestamp — treat as fresh-ish but not within-15
                w1h++;
            } else if (sec <= 15 * 60) {
                w15++;
            } else if (sec <= 60 * 60) {
                w1h++;
            } else if (sec <= 24 * 60 * 60) {
                w1d++;
            } else {
                older++;
            }
        }
        return new PickerStatusFreshness(w15, w1h, w1d, older, unknown);
    }

    /**
     * Top-N OFFLINE pickers by {@code last_login_time} DESC. The agent quotes these
     * specific names when explaining a "no available picker" bottleneck — concrete
     * actionable detail rather than a bare counter.
     */
    public static List<PickerSnapshot> recentlyOnlineSample(List<PickerRow> pickers, Instant now) {
        return pickers.stream()
                .filter(p -> "OFFLINE".equals(p.status()) && p.lastLoginTime() != null)
                .sorted((a, b) -> b.lastLoginTime().compareTo(a.lastLoginTime()))
                .limit(RECENTLY_ONLINE_SAMPLE_SIZE)
                .map(p -> new PickerSnapshot(
                        p.code(),
                        p.status(),
                        p.lastLoginTime(),
                        Duration.between(p.lastLoginTime(), now).toMinutes()))
                .toList();
    }
}
