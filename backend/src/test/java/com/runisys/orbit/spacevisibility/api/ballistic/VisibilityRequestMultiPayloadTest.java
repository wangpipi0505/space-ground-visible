package com.runisys.orbit.spacevisibility.api.ballistic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runisys.orbit.spacevisibility.domain.ballistic.visibility.OrbitCalculator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 冻结原算法回归，断言及容差保持原版；参考样例位于独立test-data目录。 */
class VisibilityRequestMultiPayloadTest {

    @Test
    void 应将嵌套载荷配置映射为卫星平台内的算法载荷() throws Exception {
        String requestJson = "{\n"
                + "  \"satellites\": [{\n"
                + "    \"satelliteId\": \"SAT-A\",\n"
                + "    \"name\": \"卫星A\",\n"
                + "    \"epochUtc\": \"2026/07/01 10:00:00.000\",\n"
                + "    \"payloads\": [{\n"
                + "      \"payloadId\": \"PAYLOAD-A-01\",\n"
                + "      \"payloadName\": \"对空跟踪载荷\",\n"
                + "      \"payloadType\": \"对空跟踪\",\n"
                + "      \"maxDetectRangeKm\": 1200.0,\n"
                + "      \"sunExclusionAngleDeg\": 40.0\n"
                + "    }]\n"
                + "  }]\n"
                + "}";

        VisibilityRequest request = new ObjectMapper().readValue(requestJson, VisibilityRequest.class);
        List<OrbitCalculator.OrbitElements> satellites = request.toSatellites();

        assertEquals(1, satellites.size());
        assertEquals("SAT-A", satellites.get(0).satelliteId);
        assertEquals("卫星A", satellites.get(0).name);
        assertEquals(1, satellites.get(0).payloads.size());
        OrbitCalculator.SatellitePayloadConfig payload = satellites.get(0).payloads.get(0);
        assertEquals("PAYLOAD-A-01", payload.payloadId);
        assertEquals("对空跟踪载荷", payload.payloadName);
        assertEquals(OrbitCalculator.PayloadType.AIR_TRACKING, payload.sensorConfig.payloadType);
        assertEquals(1_200_000.0, payload.sensorConfig.maxDetectRangeM, 1e-9);
        assertEquals(40.0, payload.sensorConfig.sunExclusionAngleDeg, 1e-9);
    }

    @Test
    void 未传嵌套载荷时应保留旧版单载荷调用入口() throws Exception {
        String requestJson = "{\n"
                + "  \"payloadType\": \"对地跟踪\",\n"
                + "  \"maxDetectRangeKm\": 800.0,\n"
                + "  \"sunExclusionAngleDeg\": 30.0,\n"
                + "  \"satellites\": [{\"name\": \"SAT-A\"}]\n"
                + "}";

        VisibilityRequest request = new ObjectMapper().readValue(requestJson, VisibilityRequest.class);
        List<OrbitCalculator.OrbitElements> satellites = request.toSatellites();
        OrbitCalculator.SensorVisibilityConfig legacyConfig = request.toSensorVisibilityConfig();

        assertTrue(satellites.get(0).payloads.isEmpty());
        assertEquals(OrbitCalculator.PayloadType.GROUND_TRACKING, legacyConfig.payloadType);
        assertEquals(800_000.0, legacyConfig.maxDetectRangeM, 1e-9);
        assertEquals(30.0, legacyConfig.sunExclusionAngleDeg, 1e-9);
    }
}
