package com.runisys.orbit.spacevisibility.api.ballistic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runisys.orbit.spacevisibility.domain.ballistic.OrbitCalculator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 新协议先验证真实JSON入口，期望由公里单位和非圆锥摘要协议给出。 */
class StandardPayloadRequestTest {
    @Test
    void 标准字段应进入真实约束且不伪造圆锥() throws Exception {
        String json = "{\"payloadType\":\"space\",\"minDistanceKm\":1,\"maxDistanceKm\":2,"
                + "\"angleRateDegPerSec\":-1,\"sunAvoidHalfAngleDeg\":0,\"startupAngleRateDegPerSec\":-1}";
        VisibilityRequest.PayloadDef definition = assertDoesNotThrow(() ->
                new ObjectMapper().readValue(json, VisibilityRequest.PayloadDef.class));
        OrbitCalculator.SatellitePayloadConfig config = definition.toPayloadConfig("S", 0);
        OrbitCalculator.VisibilityConstraintSummary summary = OrbitCalculator.VisibilityConstraintSummary.fromConfig(config.sensorConfig);
        assertEquals("SPACE", summary.payloadType);
        assertEquals(2.0, summary.maxDetectRangeKm);
        assertEquals(0.0, summary.sunExclusionAngleDeg);
        assertTrue(Double.isNaN(summary.halfConeAngleDeg));
        assertTrue(Double.isNaN(summary.simpleConeHalfAngleDeg));
        assertTrue(Double.isNaN(summary.innerConeAngleDeg));
        assertTrue(Double.isNaN(summary.outerConeAngleDeg));
    }
}
