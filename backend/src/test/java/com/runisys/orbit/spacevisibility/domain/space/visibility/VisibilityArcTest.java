package com.runisys.orbit.spacevisibility.domain.space.visibility;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.runisys.orbit.spacevisibility.domain.space.illumination.IlluminationState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class VisibilityArcTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** 单星弧段没有可展开内容，序列化时省略 satelliteSegments 字段。 */
    @Test
    void omitsSatelliteSegmentsForSingleObserverArc() throws Exception {
        VisibilityArc arc = new VisibilityArc(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:05:00Z"),
                Collections.singletonList(new VisibilityArc.ObserverIllumination("A", "观测星A", IlluminationState.UMBRA)),
                new VisibilityArc.TargetIllumination("T", "目标T", IlluminationState.SUNLIT));

        assertThat(arc.getSatelliteSegments()).isEmpty();
        assertThat(mapper.writeValueAsString(arc)).doesNotContain("satelliteSegments");
    }

    /** 未附带原始单星窗口的旧式组合弧段，兼容性回退为按参与顺序输出父窗口范围。 */
    @Test
    void exposesPerSatelliteSegmentsForCombinationArc() throws Exception {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = start.plusSeconds(562);
        VisibilityArc arc = new VisibilityArc(start, end,
                Arrays.asList(new VisibilityArc.ObserverIllumination("A", "观测星A", IlluminationState.UMBRA),
                        new VisibilityArc.ObserverIllumination("B", "观测星B", IlluminationState.SUNLIT)),
                new VisibilityArc.TargetIllumination("T", "目标T", IlluminationState.UMBRA));

        assertThat(arc.getSatelliteSegments()).hasSize(2);
        VisibilityArc.SatelliteSegment first = arc.getSatelliteSegments().get(0);
        VisibilityArc.SatelliteSegment second = arc.getSatelliteSegments().get(1);
        assertThat(first.getSatelliteId()).isEqualTo("A");
        assertThat(first.getSatelliteName()).isEqualTo("观测星A");
        assertThat(first.getStateName()).isEqualTo("本影");
        assertThat(second.getSatelliteName()).isEqualTo("观测星B");
        assertThat(second.getStateName()).isEqualTo("阳照");
        for (VisibilityArc.SatelliteSegment segment : arc.getSatelliteSegments()) {
            assertThat(segment.getStartTime()).isEqualTo(start);
            assertThat(segment.getEndTime()).isEqualTo(end);
            assertThat(segment.getDurationSec()).isEqualTo(562.0);
        }

        String json = mapper.writeValueAsString(arc);
        assertThat(json).contains("satelliteSegments");
        assertThat(json).contains("观测星A").contains("观测星B");
        assertThat(json).contains("2026-01-01T00:00:00.000Z");
    }
}
