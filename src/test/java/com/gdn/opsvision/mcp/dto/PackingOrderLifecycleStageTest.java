package com.gdn.opsvision.mcp.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class PackingOrderLifecycleStageTest {

    @Test
    void inactiveTakesPrecedence() {
        // active=false → INACTIVE regardless of other fields.
        assertThat(PackingOrderLifecycleStage.forPackingOrder(false, null, null))
                .isEqualTo(PackingOrderLifecycleStage.INACTIVE);
        assertThat(PackingOrderLifecycleStage.forPackingOrder(false, Instant.now(), 99L))
                .isEqualTo(PackingOrderLifecycleStage.INACTIVE);
    }

    @Test
    void ginPresenceMapsToGinIssued() {
        // good_issued_note set → GIN_ISSUED, even if also claimed.
        assertThat(PackingOrderLifecycleStage.forPackingOrder(true, Instant.now(), 99L))
                .isEqualTo(PackingOrderLifecycleStage.GIN_ISSUED);
        assertThat(PackingOrderLifecycleStage.forPackingOrder(true, null, 99L))
                .isEqualTo(PackingOrderLifecycleStage.GIN_ISSUED);
    }

    @Test
    void claimedDateMapsToClaimed() {
        // active + claimed_date set + no GIN → CLAIMED.
        assertThat(PackingOrderLifecycleStage.forPackingOrder(true, Instant.now(), null))
                .isEqualTo(PackingOrderLifecycleStage.CLAIMED);
    }

    @Test
    void freshActivePackingOrder_mapsToCreated() {
        assertThat(PackingOrderLifecycleStage.forPackingOrder(true, null, null))
                .isEqualTo(PackingOrderLifecycleStage.CREATED);
    }
}
