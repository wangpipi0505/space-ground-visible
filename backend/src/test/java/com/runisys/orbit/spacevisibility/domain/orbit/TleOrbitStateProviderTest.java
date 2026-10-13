package com.runisys.orbit.spacevisibility.domain.orbit;

import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TleOrbitStateProviderTest {

    @Test
    void propagatesStandardTleToFinitePosition() {
        TleOrbitStateProvider provider = new TleOrbitStateProvider(
                "1 25544U 98067A   24100.51782528  .00014321  00000+0  25813-3 0  9997",
                "2 25544  51.6396  46.5178 0004400  75.0248  50.3079 15.50175300448688");

        Vector3 position = provider.positionAt(Instant.parse("2024-04-10T12:30:00Z"));

        assertThat(position.norm()).isBetween(6_500_000.0, 7_500_000.0);
    }
}
