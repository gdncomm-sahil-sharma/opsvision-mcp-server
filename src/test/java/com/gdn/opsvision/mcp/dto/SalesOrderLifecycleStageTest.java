package com.gdn.opsvision.mcp.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SalesOrderLifecycleStageTest {

    /** All 18 documented SOStatus ordinals. */
    private static final int[] ALL_DOCUMENTED_ORDINALS = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17};

    @Test
    void allDocumentedOrdinalsMapToKnownStage() {
        for (int o : ALL_DOCUMENTED_ORDINALS) {
            SalesOrderLifecycleStage stage = SalesOrderLifecycleStage.forOrdinal(o);
            assertThat(stage)
                    .as("ordinal %d must map to a known stage (not OTHER)", o)
                    .isNotEqualTo(SalesOrderLifecycleStage.OTHER);
        }
    }

    @Test
    void allDocumentedOrdinalsHaveLabels() {
        for (int o : ALL_DOCUMENTED_ORDINALS) {
            String label = SalesOrderLifecycleStage.labelForOrdinal(o);
            assertThat(label)
                    .as("ordinal %d must have a non-fallback label", o)
                    .doesNotStartWith("STATUS_");
        }
    }

    @Test
    void specificMappings() {
        // Customer-facing core states
        assertThat(SalesOrderLifecycleStage.forOrdinal(5))
                .isEqualTo(SalesOrderLifecycleStage.STOCK_BLOCKED);   // OUT_OF_STOCK
        assertThat(SalesOrderLifecycleStage.forOrdinal(6))
                .isEqualTo(SalesOrderLifecycleStage.IN_FULFILLMENT);  // ITEM_PICKED
        assertThat(SalesOrderLifecycleStage.forOrdinal(8))
                .isEqualTo(SalesOrderLifecycleStage.POST_PICK);       // ITEM_PACKED
        assertThat(SalesOrderLifecycleStage.forOrdinal(9))
                .isEqualTo(SalesOrderLifecycleStage.POST_PICK);       // ITEM_ISSUED
        assertThat(SalesOrderLifecycleStage.forOrdinal(7))
                .isEqualTo(SalesOrderLifecycleStage.TERMINATED);      // CANCELLED
        assertThat(SalesOrderLifecycleStage.forOrdinal(17))
                .isEqualTo(SalesOrderLifecycleStage.TERMINATED);      // OUT_OF_STOCK_CANCEL
        assertThat(SalesOrderLifecycleStage.forOrdinal(14))
                .isEqualTo(SalesOrderLifecycleStage.PRE_FULFILLMENT); // PICK_PACKAGE_CREATED
    }

    @Test
    void labelMappings() {
        assertThat(SalesOrderLifecycleStage.labelForOrdinal(5)).isEqualTo("OUT_OF_STOCK");
        assertThat(SalesOrderLifecycleStage.labelForOrdinal(17)).isEqualTo("OUT_OF_STOCK_CANCEL");
        assertThat(SalesOrderLifecycleStage.labelForOrdinal(8)).isEqualTo("ITEM_PACKED");
    }

    @Test
    void unknownOrdinalFallsToOther() {
        assertThat(SalesOrderLifecycleStage.forOrdinal(999))
                .isEqualTo(SalesOrderLifecycleStage.OTHER);
        assertThat(SalesOrderLifecycleStage.labelForOrdinal(999))
                .isEqualTo("STATUS_999");
    }

    @Test
    void labelLookupAlsoWorks() {
        assertThat(SalesOrderLifecycleStage.forLabel("OUT_OF_STOCK"))
                .isEqualTo(SalesOrderLifecycleStage.STOCK_BLOCKED);
        assertThat(SalesOrderLifecycleStage.forLabel("ITEM_PACKED"))
                .isEqualTo(SalesOrderLifecycleStage.POST_PICK);
        assertThat(SalesOrderLifecycleStage.forLabel(null))
                .isEqualTo(SalesOrderLifecycleStage.OTHER);
        assertThat(SalesOrderLifecycleStage.forLabel("BOGUS"))
                .isEqualTo(SalesOrderLifecycleStage.OTHER);
    }
}
