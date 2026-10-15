package com.runisys.orbit.spacevisibility.application.space;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runisys.orbit.spacevisibility.domain.payload.PayloadConstraint;
import com.runisys.orbit.spacevisibility.domain.payload.PayloadType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayloadConstraintFactoryTest {

    /**
     * 下传必须固定取对地 profile，不能跟随当前活动的对空 profile。
     * 文档中的 A/B/C 都同时保存两套配置，而活动类型为 SPACE；若这里仍拿到 SPACE，
     * 下传窗口就会完全忽略 WinTool 给出的 GROUND 距离、俯仰和跟踪参数。
     */
    @Test
    void readsGroundProfileEvenWhenSpaceProfileIsActive() {
        PayloadConstraintFactory factory = new PayloadConstraintFactory(new ObjectMapper());
        String profiles = "{\"activePayloadType\":\"SPACE\",\"payloadProfiles\":{"
                + "\"SPACE\":{\"payloadType\":\"SPACE\",\"distance\":[1000000,4000000],"
                + "\"distanceUnit\":\"M\",\"angleRate\":1,\"sunAvoidHalfAngle\":45},"
                + "\"GROUND\":{\"payloadType\":\"GROUND\",\"distance\":[800000,3500000],"
                + "\"distanceUnit\":\"M\",\"elevation\":[-91,0],\"angleRate\":1,"
                + "\"sunAvoidHalfAngle\":45,\"startupAngleRate\":1}}}";

        PayloadConstraint ground = factory.parseProfile(profiles, PayloadType.GROUND);

        assertThat(ground.getPayloadType()).isEqualTo(PayloadType.GROUND);
        assertThat(ground.getMinDetectRangeM()).isEqualTo(800_000.0);
        assertThat(ground.getMaxDetectRangeM()).isEqualTo(3_500_000.0);
        assertThat(ground.getStartupAngleRateDegPerSec()).isEqualTo(1.0);
    }

    /** 验证缺少标准距离范围的对空载荷配置会被拒绝。 */
    @Test
    void rejectsSpacePayloadWithoutStandardDistanceRange() {
        PayloadConstraintFactory factory = new PayloadConstraintFactory(new ObjectMapper());

        assertThatThrownBy(() -> factory.parse("{\"payloadType\":\"SPACE\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("探测距离范围必须为[最小, 最大]二元数组");
    }

    @Test
    void parsesStandardJsonStringReturnedByJsonDatabaseColumn() {
        PayloadConstraintFactory factory = new PayloadConstraintFactory(new ObjectMapper());

        assertThat(factory.parse("\"{\\\"payloadType\\\":\\\"SPACE\\\","
                + "\\\"distance\\\":[1000000,4000000],\\\"distanceUnit\\\":\\\"M\\\",\\\"angleRate\\\":0.3,"
                + "\\\"sunAvoidHalfAngle\\\":0}\"").getMaxDetectRangeM()).isEqualTo(4_000_000.0);
    }

    /**
     * 历史交付版将 UI 中的公里数误写入未标注单位的 distance 字段。
     *
     * <p>运行 Flyway 数据迁移前，应用也必须按公里解释这类遗留数据，避免升级窗口内
     * 重新把 1,000 km 误当为 1,000 m。新写入的数据会显式携带 {@code distanceUnit=M}。</p>
     */
    @Test
    void treatsUnmarkedLegacySpaceDistanceAsKilometres() {
        PayloadConstraintFactory factory = new PayloadConstraintFactory(new ObjectMapper());

        PayloadConstraint constraint = factory.parse("{\"payloadType\":\"SPACE\","
                + "\"distance\":[1000,4000],\"angleRate\":0.3,\"sunAvoidHalfAngle\":0}");

        assertThat(constraint.getMinDetectRangeM()).isEqualTo(1_000_000.0);
        assertThat(constraint.getMaxDetectRangeM()).isEqualTo(4_000_000.0);
    }

    /** 验证标准对空载荷 JSON 的距离、方位、俯仰、角速度、太阳回避半角和启动角速度全部解析。 */
    @Test
    void parsesNewSpacePayloadDefinitionWithAttitudeWindowAndStartupRate() {
        PayloadConstraintFactory factory = new PayloadConstraintFactory(new ObjectMapper());

        PayloadConstraint constraint = factory.parse("{\"payloadType\":\"SPACE\","
                + "\"distance\":[1000,4000],\"distanceUnit\":\"M\",\"azimuth\":[90,270],\"elevation\":[-4,60],"
                + "\"angleRate\":0.3,\"sunAvoidHalfAngle\":90,\"startupAngleRate\":0.07}");

        assertThat(constraint.getMinDetectRangeM()).isEqualTo(1000.0);
        assertThat(constraint.getMaxDetectRangeM()).isEqualTo(4000.0);
        assertThat(constraint.getAzimuthMinDeg()).isEqualTo(90.0);
        assertThat(constraint.getAzimuthMaxDeg()).isEqualTo(270.0);
        assertThat(constraint.getElevationMinDeg()).isEqualTo(-4.0);
        assertThat(constraint.getElevationMaxDeg()).isEqualTo(60.0);
        assertThat(constraint.getAngleRateDegPerSec()).isEqualTo(0.3);
        assertThat(constraint.getSunAvoidHalfAngleDeg()).isEqualTo(90.0);
        assertThat(constraint.getStartupAngleRateDegPerSec()).isEqualTo(0.07);
    }

    /** 验证标准对空载荷缺少启动角速度时按不启用起点捕获约束处理。 */
    @Test
    void parsesNewSpacePayloadDefinitionWithoutStartupAngleRate() {
        PayloadConstraintFactory factory = new PayloadConstraintFactory(new ObjectMapper());

        PayloadConstraint constraint = factory.parse("{\"payloadType\":\"SPACE\","
                + "\"distance\":[1000,4000],\"distanceUnit\":\"M\",\"azimuth\":[90,270],\"elevation\":[-4,60],"
                + "\"angleRate\":0.3,\"sunAvoidHalfAngle\":90}");

        assertThat(constraint.getStartupAngleRateDegPerSec()).isNull();
    }

    /**
     * WinTool 的对空载荷参数使用 0 或 -1 表示不施加跟踪角速度上限，并非“每秒只能转 0 度”。
     * 原始标记必须留在 JSON 中供配置界面回显，计算层则转换为不限制的有效值。
     */
    @Test
    void interpretsWinToolZeroAndMinusOneAngleRatesAsUnrestricted() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        PayloadConstraintFactory factory = new PayloadConstraintFactory(objectMapper);

        String zeroRateJson = factory.createSpacePayloadJson(1_000_000.0, 4_000_000.0,
                90.0, 270.0, -4.0, 60.0, 0.0, 90.0, 0.07);
        String minusOneRateJson = factory.createSpacePayloadJson(1_000_000.0, 4_000_000.0,
                null, null, -28.5, 60.0, -1.0, 60.0, -1.0);

        assertThat(objectMapper.readTree(zeroRateJson).get("angleRate").asDouble()).isZero();
        assertThat(factory.parse(zeroRateJson).getAngleRateDegPerSec()).isEqualTo(Double.MAX_VALUE);
        assertThat(objectMapper.readTree(minusOneRateJson).get("angleRate").asDouble()).isEqualTo(-1.0);
        assertThat(objectMapper.readTree(minusOneRateJson).get("startupAngleRate").asDouble()).isEqualTo(-1.0);
        assertThat(factory.parse(minusOneRateJson).getAngleRateDegPerSec()).isEqualTo(Double.MAX_VALUE);
        assertThat(factory.parse(minusOneRateJson).getStartupAngleRateDegPerSec()).isNull();
    }

    /** 验证方位角上限允许取 360 度，用于表达覆盖全向的方位窗口。 */
    @Test
    void acceptsFullCircleAzimuthWindowWithUpperBound360() {
        PayloadConstraintFactory factory = new PayloadConstraintFactory(new ObjectMapper());

        PayloadConstraint constraint = factory.parse("{\"payloadType\":\"SPACE\","
                + "\"distance\":[1000,4000],\"distanceUnit\":\"M\",\"azimuth\":[0,360],\"elevation\":[-90,90],"
                + "\"angleRate\":0.3,\"sunAvoidHalfAngle\":90}");

        assertThat(constraint.getAzimuthMinDeg()).isEqualTo(0.0);
        assertThat(constraint.getAzimuthMaxDeg()).isEqualTo(360.0);
    }

    /** 验证生成的对空载荷 JSON 使用标准字段，且可被解析还原为相同约束。 */
    @Test
    void createsNewFormatSpacePayloadJsonThatRoundTrips() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        PayloadConstraintFactory factory = new PayloadConstraintFactory(objectMapper);

        String payloadJson = factory.createSpacePayloadJson(1000.0, 4000.0, 90.0, 270.0,
                -4.0, 60.0, 0.3, 90.0, 0.07);

        JsonNode node = objectMapper.readTree(payloadJson);
        assertThat(node.get("payloadType").asText()).isEqualTo("SPACE");
        assertThat(node.get("distance").get(0).asDouble()).isEqualTo(1000.0);
        assertThat(node.get("distance").get(1).asDouble()).isEqualTo(4000.0);
        assertThat(node.get("distanceUnit").asText()).isEqualTo("M");
        assertThat(node.get("azimuth").get(0).asDouble()).isEqualTo(90.0);
        assertThat(node.get("azimuth").get(1).asDouble()).isEqualTo(270.0);
        assertThat(node.get("elevation").get(0).asDouble()).isEqualTo(-4.0);
        assertThat(node.get("elevation").get(1).asDouble()).isEqualTo(60.0);
        assertThat(node.get("angleRate").asDouble()).isEqualTo(0.3);
        assertThat(node.get("sunAvoidHalfAngle").asDouble()).isEqualTo(90.0);
        assertThat(node.get("startupAngleRate").asDouble()).isEqualTo(0.07);
        assertThat(node.has("maxDetectRangeKm")).isFalse();

        PayloadConstraint constraint = factory.parse(payloadJson);
        assertThat(constraint.getMinDetectRangeM()).isEqualTo(1000.0);
        assertThat(constraint.getStartupAngleRateDegPerSec()).isEqualTo(0.07);
    }

    /**
     * 交付文档中 C 型卫星的对地参数必须与对空格式逐字段一致。-1 表示禁用启动角速度，
     * JSON 保留该标记供界面回显，领域计算再归一为空值。
     */
    @Test
    void createsAndParsesDocumentGroundPayloadUsingUnifiedFields() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        PayloadConstraintFactory factory = new PayloadConstraintFactory(objectMapper);

        String payloadJson = factory.createGroundPayloadJson(1_000_000.0, 4_000_000.0,
                null, null, -91.0, -10.0, 1.0, 40.0, -1.0);

        JsonNode node = objectMapper.readTree(payloadJson);
        assertThat(node.get("payloadType").asText()).isEqualTo("GROUND");
        assertThat(node.get("distance").get(0).asDouble()).isEqualTo(1_000_000.0);
        assertThat(node.get("distance").get(1).asDouble()).isEqualTo(4_000_000.0);
        assertThat(node.get("distanceUnit").asText()).isEqualTo("M");
        assertThat(node.get("azimuth")).isNull();
        assertThat(node.get("elevation").get(0).asDouble()).isEqualTo(-91.0);
        assertThat(node.get("elevation").get(1).asDouble()).isEqualTo(-10.0);
        assertThat(node.get("angleRate").asDouble()).isEqualTo(1.0);
        assertThat(node.get("sunAvoidHalfAngle").asDouble()).isEqualTo(40.0);
        assertThat(node.get("startupAngleRate").asDouble()).isEqualTo(-1.0);

        PayloadConstraint constraint = factory.parse(payloadJson);
        assertThat(constraint.getPayloadType().name()).isEqualTo("GROUND");
        assertThat(constraint.isLegacyGroundCone()).isFalse();
        assertThat(constraint.getMinDetectRangeM()).isEqualTo(1_000_000.0);
        assertThat(constraint.getMaxDetectRangeM()).isEqualTo(4_000_000.0);
        assertThat(constraint.getElevationMinDeg()).isEqualTo(-91.0);
        assertThat(constraint.getElevationMaxDeg()).isEqualTo(-10.0);
        assertThat(constraint.getStartupAngleRateDegPerSec()).isNull();
    }
}
