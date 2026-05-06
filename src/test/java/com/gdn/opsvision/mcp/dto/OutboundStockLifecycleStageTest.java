package com.gdn.opsvision.mcp.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.gdn.opsvision.mcp.dto.StockTraceEvidence.OutboundLifecycleProgression;

/**
 * Mapping coverage + progression-computation tests for {@link OutboundStockLifecycleStage}.
 *
 * <p>Asserts that every documented {@code stock_action_type} value maps to a known stage
 * (no silent OTHER), and that {@link OutboundStockLifecycleStage#computeProgression(List)}
 * correctly identifies Pattern A and Pattern B fingerprints.
 */
class OutboundStockLifecycleStageTest {

    /** All 14 documented stock_action_type values per memory note qa2_schema_gotchas.md. */
    private static final List<String> ALL_DOCUMENTED_ACTION_TYPES = List.of(
            "INCREASE_WAREHOUSE_PHYSICAL_ORIGINAL_STOCK",
            "INCREASE_WAREHOUSE_PHYSICAL_RESERVED_STOCK",
            "INCREASE_WAREHOUSE_VIRTUAL_ORIGINAL_STOCK",
            "INCREASE_WAREHOUSE_VIRTUAL_RESERVED_STOCK",
            "INCREASE_WAREHOUSE_VIRTUAL_RESERVED_PHYSICAL_STOCK",
            "INCREASE_BIN_ORIGINAL_STOCK",
            "INCREASE_BIN_RESERVED_STOCK",
            "DECREASE_WAREHOUSE_PHYSICAL_ORIGINAL_STOCK",
            "DECREASE_WAREHOUSE_PHYSICAL_RESERVED_STOCK",
            "DECREASE_WAREHOUSE_VIRTUAL_ORIGINAL_STOCK",
            "DECREASE_WAREHOUSE_VIRTUAL_RESERVED_STOCK",
            "DECREASE_WAREHOUSE_VIRTUAL_RESERVED_PHYSICAL_STOCK",
            "DECREASE_BIN_ORIGINAL_STOCK",
            "DECREASE_BIN_RESERVED_STOCK");

    @Test
    void allDocumentedActionTypesMapToKnownStage() {
        for (String actionType : ALL_DOCUMENTED_ACTION_TYPES) {
            OutboundStockLifecycleStage stage = OutboundStockLifecycleStage.forActionType(actionType);
            assertThat(stage)
                    .as("action_type %s must map to a known stage (not OTHER)", actionType)
                    .isNotEqualTo(OutboundStockLifecycleStage.OTHER);
        }
    }

    @Test
    void outboundChainFiresCorrectStages() {
        assertThat(OutboundStockLifecycleStage.forActionType("INCREASE_WAREHOUSE_PHYSICAL_RESERVED_STOCK"))
                .isEqualTo(OutboundStockLifecycleStage.WAREHOUSE_RESERVATION);
        assertThat(OutboundStockLifecycleStage.forActionType("INCREASE_BIN_RESERVED_STOCK"))
                .isEqualTo(OutboundStockLifecycleStage.BIN_RESERVATION);
        assertThat(OutboundStockLifecycleStage.forActionType("DECREASE_BIN_ORIGINAL_STOCK"))
                .isEqualTo(OutboundStockLifecycleStage.BIN_DECREASE);
        assertThat(OutboundStockLifecycleStage.forActionType("DECREASE_BIN_RESERVED_STOCK"))
                .isEqualTo(OutboundStockLifecycleStage.BIN_DECREASE);
        assertThat(OutboundStockLifecycleStage.forActionType("DECREASE_WAREHOUSE_PHYSICAL_ORIGINAL_STOCK"))
                .isEqualTo(OutboundStockLifecycleStage.WAREHOUSE_DECREASE);
    }

    @Test
    void unmappedActionTypeFallsToOther() {
        assertThat(OutboundStockLifecycleStage.forActionType("BOGUS_NEW_TYPE"))
                .isEqualTo(OutboundStockLifecycleStage.OTHER);
        assertThat(OutboundStockLifecycleStage.forActionType(null))
                .isEqualTo(OutboundStockLifecycleStage.OTHER);
    }

    @Test
    void emptyTraceProgression_isComplete_false_furthestStage_null() {
        OutboundLifecycleProgression p = OutboundStockLifecycleStage.computeProgression(List.of());
        assertThat(p.reachedStages()).isEmpty();
        assertThat(p.missingStages()).containsExactlyElementsOf(OutboundStockLifecycleStage.OUTBOUND_CHAIN);
        assertThat(p.furthestStage()).isNull();
        assertThat(p.isOutboundComplete()).isFalse();
        assertThat(p.outboundEventCount()).isZero();
    }

    @Test
    void healthyTraceProgression_completesAllFourStages() {
        OutboundLifecycleProgression p = OutboundStockLifecycleStage.computeProgression(List.of(
                "INCREASE_WAREHOUSE_PHYSICAL_RESERVED_STOCK", // 1
                "INCREASE_BIN_RESERVED_STOCK",                 // 2
                "DECREASE_BIN_ORIGINAL_STOCK",                 // 3
                "DECREASE_BIN_RESERVED_STOCK",                 // (also 3)
                "DECREASE_WAREHOUSE_PHYSICAL_ORIGINAL_STOCK",  // 4
                "DECREASE_WAREHOUSE_PHYSICAL_RESERVED_STOCK")); // (also 4)
        assertThat(p.isOutboundComplete()).isTrue();
        assertThat(p.furthestStage()).isEqualTo(OutboundStockLifecycleStage.WAREHOUSE_DECREASE);
        assertThat(p.missingStages()).isEmpty();
        assertThat(p.reachedStages()).containsExactly(
                OutboundStockLifecycleStage.WAREHOUSE_RESERVATION,
                OutboundStockLifecycleStage.BIN_RESERVATION,
                OutboundStockLifecycleStage.BIN_DECREASE,
                OutboundStockLifecycleStage.WAREHOUSE_DECREASE);
    }

    @Test
    void patternA_halfAppliedReservation_furthestStage_isWarehouseReservation() {
        // Aggregate reservation fired but bin reservation never did.
        OutboundLifecycleProgression p = OutboundStockLifecycleStage.computeProgression(List.of(
                "INCREASE_WAREHOUSE_PHYSICAL_RESERVED_STOCK"));
        assertThat(p.furthestStage()).isEqualTo(OutboundStockLifecycleStage.WAREHOUSE_RESERVATION);
        assertThat(p.isOutboundComplete()).isFalse();
        assertThat(p.missingStages()).containsExactly(
                OutboundStockLifecycleStage.BIN_RESERVATION,
                OutboundStockLifecycleStage.BIN_DECREASE,
                OutboundStockLifecycleStage.WAREHOUSE_DECREASE);
    }

    @Test
    void patternB_phantomClose_furthestStage_isBinReservation() {
        // Full chain reached BIN_RESERVATION but no DECREASE_BIN_* events fired.
        OutboundLifecycleProgression p = OutboundStockLifecycleStage.computeProgression(List.of(
                "INCREASE_WAREHOUSE_PHYSICAL_RESERVED_STOCK",
                "INCREASE_BIN_RESERVED_STOCK"));
        assertThat(p.furthestStage()).isEqualTo(OutboundStockLifecycleStage.BIN_RESERVATION);
        assertThat(p.isOutboundComplete()).isFalse();
        assertThat(p.missingStages()).containsExactly(
                OutboundStockLifecycleStage.BIN_DECREASE,
                OutboundStockLifecycleStage.WAREHOUSE_DECREASE);
        assertThat(p.derivationNote()).contains("missing");
    }

    @Test
    void inboundOrVirtualEventsTrackedSeparately() {
        OutboundLifecycleProgression p = OutboundStockLifecycleStage.computeProgression(List.of(
                "INCREASE_BIN_ORIGINAL_STOCK",                  // inbound putaway
                "INCREASE_WAREHOUSE_PHYSICAL_ORIGINAL_STOCK",   // inbound aggregate
                "INCREASE_WAREHOUSE_VIRTUAL_RESERVED_STOCK",    // virtual
                "BOGUS_FUTURE_TYPE"));                           // unmapped
        assertThat(p.outboundEventCount()).isZero();
        assertThat(p.inboundOrVirtualEventCount()).isEqualTo(3);
        assertThat(p.unmappedActionTypeCount()).isEqualTo(1);
        assertThat(p.furthestStage()).isNull();
    }
}
