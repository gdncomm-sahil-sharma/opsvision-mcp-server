package com.gdn.opsvision.mcp.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PickingTaskRequestLifecycleStageTest {

    /** 6 confirmed values for MovementTaskRequestStatus. */
    private static final List<String> ALL_DOCUMENTED_STATUSES = List.of(
            "CREATED", "RELEASED", "IN_PROGRESS", "BATCH_CAL_PENDING", "HOLD", "CLOSED");

    @Test
    void allDocumentedStatusesMapToKnownStage() {
        for (String s : ALL_DOCUMENTED_STATUSES) {
            assertThat(PickingTaskRequestLifecycleStage.forStatus(s))
                    .as("status %s must not fall to OTHER", s)
                    .isNotEqualTo(PickingTaskRequestLifecycleStage.OTHER);
        }
    }

    @Test
    void patternA_holdMapsToBlockedHold() {
        // Pattern A — request stuck in HOLD.
        assertThat(PickingTaskRequestLifecycleStage.forStatus("HOLD"))
                .isEqualTo(PickingTaskRequestLifecycleStage.BLOCKED_HOLD);
    }

    @Test
    void unknownAndNullFallToOther() {
        assertThat(PickingTaskRequestLifecycleStage.forStatus("BOGUS"))
                .isEqualTo(PickingTaskRequestLifecycleStage.OTHER);
        assertThat(PickingTaskRequestLifecycleStage.forStatus(null))
                .isEqualTo(PickingTaskRequestLifecycleStage.OTHER);
    }
}
