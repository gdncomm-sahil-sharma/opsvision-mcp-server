package com.gdn.opsvision.mcp.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class HandlingUnitLifecycleStageTest {

    /** Source: PickPackageHandlingUnitStatus.java (5 values). */
    private static final List<String> ALL_DOCUMENTED_STATUSES = List.of(
            "OPEN", "SCANNED", "PARTIALLY_PICKED", "PICKING_COMPLETE", "CLOSED");

    @Test
    void allDocumentedStatusesMapToKnownStage() {
        for (String status : ALL_DOCUMENTED_STATUSES) {
            HandlingUnitLifecycleStage stage = HandlingUnitLifecycleStage.forStatus(status);
            assertThat(stage)
                    .as("status %s must map to a known stage (not OTHER)", status)
                    .isNotEqualTo(HandlingUnitLifecycleStage.OTHER);
        }
    }

    @Test
    void specificMappings() {
        assertThat(HandlingUnitLifecycleStage.forStatus("OPEN"))
                .isEqualTo(HandlingUnitLifecycleStage.OPEN);
        assertThat(HandlingUnitLifecycleStage.forStatus("PICKING_COMPLETE"))
                .isEqualTo(HandlingUnitLifecycleStage.PICKING_COMPLETE);
        assertThat(HandlingUnitLifecycleStage.forStatus("CLOSED"))
                .isEqualTo(HandlingUnitLifecycleStage.CLOSED);
    }

    @Test
    void unknownAndNullFallToOther() {
        assertThat(HandlingUnitLifecycleStage.forStatus("BOGUS"))
                .isEqualTo(HandlingUnitLifecycleStage.OTHER);
        assertThat(HandlingUnitLifecycleStage.forStatus(null))
                .isEqualTo(HandlingUnitLifecycleStage.OTHER);
    }
}
