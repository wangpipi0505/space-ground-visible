package com.runisys.orbit.spacevisibility.domain.window;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WindowNormalizerTest {

    @Test
    void mergesAllowedGapThenAppliesGuards() {
        List<TimeInterval> windows = WindowNormalizer.normalize(
                Arrays.asList(
                        TimeInterval.of(0.0, 400.0),
                        TimeInterval.of(420.0, 800.0)),
                300.0,
                0.0,
                30.0);

        assertThat(windows).containsExactly(TimeInterval.of(300.0, 800.0));
    }
}
