package com.gdn.opsvision.mcp.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PickListLifecycleStageTest {

    /** Source: PickListStatus.java (4 values). */
    private static final List<String> ALL_DOCUMENTED_STATUSES = List.of(
            "OPEN", "IN_PROGRESS", "COMPLETE", "CLOSED");

    @Test
    void allDocumentedStatusesMapToKnownStage() {
        for (String status : ALL_DOCUMENTED_STATUSES) {
            PickListLifecycleStage stage = PickListLifecycleStage.forStatusOnly(status);
            assertThat(stage)
                    .as("status %s must map to a known stage (not OTHER)", status)
                    .isNotEqualTo(PickListLifecycleStage.OTHER);
        }
    }

    @Test
    void openSplitsByPickerId() {
        assertThat(PickListLifecycleStage.forStatusAndPicker("OPEN", null))
                .isEqualTo(PickListLifecycleStage.OPEN_UNCLAIMED);
        assertThat(PickListLifecycleStage.forStatusAndPicker("OPEN", 23391L))
                .isEqualTo(PickListLifecycleStage.OPEN_CLAIMED);
    }

    @Test
    void nonOpenStatusesIgnorePickerId() {
        // pickerId is irrelevant for non-OPEN statuses.
        assertThat(PickListLifecycleStage.forStatusAndPicker("CLOSED", 23391L))
                .isEqualTo(PickListLifecycleStage.CLOSED);
        assertThat(PickListLifecycleStage.forStatusAndPicker("CLOSED", null))
                .isEqualTo(PickListLifecycleStage.CLOSED);
        assertThat(PickListLifecycleStage.forStatusAndPicker("IN_PROGRESS", 1L))
                .isEqualTo(PickListLifecycleStage.IN_PROGRESS);
        assertThat(PickListLifecycleStage.forStatusAndPicker("COMPLETE", null))
                .isEqualTo(PickListLifecycleStage.COMPLETE);
    }

    @Test
    void unknownAndNullFallToOther() {
        assertThat(PickListLifecycleStage.forStatusAndPicker("BOGUS", null))
                .isEqualTo(PickListLifecycleStage.OTHER);
        assertThat(PickListLifecycleStage.forStatusAndPicker(null, null))
                .isEqualTo(PickListLifecycleStage.OTHER);
    }
}
