package com.gdn.opsvision.mcp.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PickingTaskLifecycleStageTest {

    /** 7 confirmed values for MovementTaskStatus (movement DB picking_task.status). */
    private static final List<String> ALL_DOCUMENTED_STATUSES = List.of(
            "OPEN", "RESERVED", "IN_PROGRESS",
            "TASK_LIST_GENERATION_IN_PROGRESS", "PENDING_CLOSED", "CLOSED", "STUCK");

    @Test
    void allDocumentedStatusesMapToKnownStage() {
        for (String s : ALL_DOCUMENTED_STATUSES) {
            assertThat(PickingTaskLifecycleStage.forStatus(s))
                    .as("status %s must not fall to OTHER", s)
                    .isNotEqualTo(PickingTaskLifecycleStage.OTHER);
        }
    }

    @Test
    void patternBPrecursor_mapsToClosing() {
        // PENDING_CLOSED → CLOSED transition is the Pattern B trigger window.
        assertThat(PickingTaskLifecycleStage.forStatus("PENDING_CLOSED"))
                .isEqualTo(PickingTaskLifecycleStage.CLOSING);
        assertThat(PickingTaskLifecycleStage.forStatus("CLOSED"))
                .isEqualTo(PickingTaskLifecycleStage.CLOSED);
    }

    @Test
    void unknownAndNullFallToOther() {
        assertThat(PickingTaskLifecycleStage.forStatus("BOGUS"))
                .isEqualTo(PickingTaskLifecycleStage.OTHER);
        assertThat(PickingTaskLifecycleStage.forStatus(null))
                .isEqualTo(PickingTaskLifecycleStage.OTHER);
    }
}
