package com.runisys.orbit.spacevisibility.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.runisys.orbit.spacevisibility.domain.payload.PayloadConstraint;
import com.runisys.orbit.spacevisibility.domain.payload.PayloadType;
import org.springframework.stereotype.Component;
import com.runisys.orbit.spacevisibility.domain.payload.StandardPayloads;
import static com.runisys.orbit.spacevisibility.domain.payload.StandardPayloads.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 持久化载荷JSON与有效领域约束的转换工厂，维护SPACE/GROUND独立profile及活动类型。
 * 新保存入口严格校验标准字段；旧JSON缺字段/圆锥兼容由原读取分支处理，不能伪映射为LVLH窗口。
 */
@Component
public class PayloadConstraintFactory {

    /** 领域计算统一使用米；任何进入 {@link PayloadConstraint} 的距离均不得再猜测单位。 */
    public static final String DISTANCE_UNIT_METRES = "M";
    /** 仅用于兼容交付版中未标注单位、但由界面以公里录入的历史标准载荷 JSON。 */
    public static final String DISTANCE_UNIT_KILOMETRES = "KM";
    private static final double METRES_PER_KILOMETRE = 1_000.0;

    private final ObjectMapper objectMapper;

    public PayloadConstraintFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析我方卫星载荷 JSON。新格式的对地、对空载荷均采用 distance、azimuth、elevation、
     * angleRate、sunAvoidHalfAngle 和 startupAngleRate 标准字段；缺少 {@code distance} 的
     * 历史对地 JSON 才采用天底全锥角兼容规则。
     *
     * @param payloadJson 数据库中保存的载荷参数 JSON
     * @return 完成参数校验后的不可变载荷约束
     */
    public PayloadConstraint parse(String payloadJson) {
        PayloadDefinition definition = readDefinition(payloadJson);
        if (definition == null) {
            throw new IllegalArgumentException("我方卫星缺少载荷参数");
        }
        return parseDefinition(definition);
    }

    /**
     * 按调用方指定的类型读取同一颗卫星保存的载荷 profile。
     *
     * <p>业务计算必须显式指定载荷类型：目标观测使用 {@code SPACE}，防止用户保存或查看
     * {@code GROUND} profile 后改变观测结果。{@link #parse(String)} 仅保留给历史兼容接口，
     * 它按 {@code activePayloadType} 读取当前 profile。</p>
     *
     * @param payloadJson 数据库中保存的全部载荷 profile
     * @param payloadType 本次计算需要的载荷类型
     * @return 完成单位与范围校验的不可变载荷约束
     */
    public PayloadConstraint parseProfile(String payloadJson, PayloadType payloadType) {
        if (payloadType == null) {
            throw new IllegalArgumentException("载荷类型不能为空");
        }
        PayloadDefinition definition = readProfiles(payloadJson).getDefinition(payloadType);
        if (definition == null) {
            throw new IllegalArgumentException("我方卫星缺少 " + payloadType.name() + " 载荷参数");
        }
        return parseDefinition(definition);
    }

    /** 将已定位的单个 profile 转换为标准约束或历史对地锥角兼容约束。 */
    private PayloadConstraint parseDefinition(PayloadDefinition definition) {
        if ("GROUND".equalsIgnoreCase(definition.getPayloadType())) {
            if (definition.getDistance() != null) {
                return parseStandardFormat(PayloadType.GROUND, definition);
            }
            return PayloadConstraint.legacyGroundCone(valueOrZero(definition.getSensorFullConeAngleDeg()),
                    valueOrZero(definition.getMaxDetectRangeKm()), valueOrZero(definition.getSunExclusionAngleDeg()));
        }
        if ("SPACE".equalsIgnoreCase(definition.getPayloadType())) {
            return parseStandardFormat(PayloadType.SPACE, definition);
        }
        throw new IllegalArgumentException("载荷类型仅支持 GROUND 或 SPACE");
    }

    /**
     * 解析标准载荷定义，距离、方位和俯仰均以 [最小, 最大] 二元数组表示。
     * 图纸中的 {@code angleRate=0/-1} 表示不限制跟踪角速度，
     * {@code startupAngleRate=-1} 表示禁用启动角速度。领域层只接收已归一的有效值，
     * 但持久化定义保留原始标记，以便配置界面与 WinTool 参数逐项一致地回显。
     */
    private PayloadConstraint parseStandardFormat(PayloadType payloadType, PayloadDefinition definition) {
        double[] distance = distanceInMetres(definition);
        double[] azimuth = definition.getAzimuth() == null ? null : requireRange(definition.getAzimuth(), "方位角范围");
        double[] elevation = definition.getElevation() == null ? null : requireRange(definition.getElevation(), "俯仰角范围");
        double angleRate = normalizeAngleRate(payloadType, definition.getAngleRate());
        Double startupAngleRate = normalizeStartupAngleRate(definition.getStartupAngleRate());
        if (payloadType == PayloadType.GROUND) {
            return PayloadConstraint.ground(distance[0], distance[1],
                    azimuth == null ? null : azimuth[0], azimuth == null ? null : azimuth[1],
                    elevation == null ? null : elevation[0], elevation == null ? null : elevation[1],
                    angleRate, valueOrZero(definition.getSunAvoidHalfAngle()), startupAngleRate);
        }
        return PayloadConstraint.space(distance[0], distance[1],
                azimuth == null ? null : azimuth[0], azimuth == null ? null : azimuth[1],
                elevation == null ? null : elevation[0], elevation == null ? null : elevation[1],
                angleRate, valueOrZero(definition.getSunAvoidHalfAngle()),
                startupAngleRate);
    }

    /**
     * 读取数据库中保存的载荷定义。空配置返回空值，使调用方可以区分“未配置”和“配置格式错误”。
     *
     * @param payloadJson 数据库中保存的载荷参数 JSON
     * @return 反序列化后的载荷定义；未配置时返回 null
     */
    public PayloadDefinition readDefinition(String payloadJson) {
        return readProfiles(payloadJson).getActiveDefinition();
    }

    /**
     * 读取卫星的全部载荷配置。旧版单一 JSON 会被视为只包含一个活动配置，
     * 因而无需一次性搬空历史数据；新保存格式才使用 {@code payloadProfiles} 容器。
     */
    public PayloadProfiles readProfiles(String payloadJson) {
        if (payloadJson == null || payloadJson.trim().isEmpty()) {
            return PayloadProfiles.empty();
        }
        try {
            JsonNode payloadNode = objectMapper.readTree(payloadJson);
            JsonNode normalizedPayload = payloadNode.isTextual() ? objectMapper.readTree(payloadNode.asText()) : payloadNode;
            JsonNode profilesNode = normalizedPayload.get("payloadProfiles");
            if (profilesNode != null && profilesNode.isObject()) {
                Map<String, PayloadDefinition> profiles = new LinkedHashMap<String, PayloadDefinition>();
                java.util.Iterator<Map.Entry<String, JsonNode>> fields = profilesNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    profiles.put(field.getKey().toUpperCase(), objectMapper.treeToValue(field.getValue(), PayloadDefinition.class));
                }
                String activeType = normalizedPayload.path("activePayloadType").asText(null);
                return new PayloadProfiles(activeType, profiles);
            }
            PayloadDefinition legacyDefinition = objectMapper.treeToValue(normalizedPayload, PayloadDefinition.class);
            if (legacyDefinition.getPayloadType() == null) {
                throw new IllegalArgumentException("载荷 JSON 缺少 payloadType");
            }
            Map<String, PayloadDefinition> profiles = new LinkedHashMap<String, PayloadDefinition>();
            profiles.put(legacyDefinition.getPayloadType().toUpperCase(), legacyDefinition);
            return new PayloadProfiles(legacyDefinition.getPayloadType(), profiles);
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("载荷 JSON 格式无效", exception);
        }
    }

    /**
     * 将一次保存的活动载荷合并进全部配置并持久化。切换类型只会替换同类型配置，
     * 已保存的另一类载荷保持不变；{@code PAYLOAD_JSON_} 同时记录当前参与规划的活动类型。
     */
    public String replaceActiveProfile(String existingPayloadJson, PayloadDefinition replacement) {
        PayloadProfiles existing = readProfiles(existingPayloadJson);
        Map<String, PayloadDefinition> profiles = new LinkedHashMap<String, PayloadDefinition>(existing.getProfiles());
        String activeType = replacement.getPayloadType().toUpperCase();
        profiles.put(activeType, replacement);
        try {
            com.fasterxml.jackson.databind.node.ObjectNode root = objectMapper.createObjectNode();
            root.put("activePayloadType", activeType);
            root.set("payloadProfiles", objectMapper.valueToTree(profiles));
            return objectMapper.writeValueAsString(root);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("载荷参数序列化失败", exception);
        }
    }

    /**
     * 构建对空观测载荷的标准持久化 JSON。目标阳照条件属于单次观测规划参数，因此不写入长期配置。
     *
     * @param minDistanceM 最近探测距离（米）
     * @param maxDistanceM 最远探测距离（米）
     * @param azimuthMinDeg LVLH 方位角下限（度）
     * @param azimuthMaxDeg LVLH 方位角上限（度）
     * @param elevationMinDeg LVLH 俯仰角下限（度）
     * @param elevationMaxDeg LVLH 俯仰角上限（度）
     * @param angleRateDegPerSec 跟踪角速度上限（度/秒）
     * @param sunAvoidHalfAngleDeg 太阳回避半角（度）
     * @param startupAngleRateDegPerSec 启动角速度元数据（度/秒）；当前仅保存和回显，可空表示禁用
     * @return 可直接保存到卫星表 PAYLOAD_JSON_ 字段的 JSON
     */
    public String createSpacePayloadJson(Double minDistanceM,
                                         Double maxDistanceM,
                                         Double azimuthMinDeg,
                                         Double azimuthMaxDeg,
                                         Double elevationMinDeg,
                                         Double elevationMaxDeg,
                                         Double angleRateDegPerSec,
                                         Double sunAvoidHalfAngleDeg,
                                         Double startupAngleRateDegPerSec) {
        return createStandardPayloadJson(PayloadType.SPACE, minDistanceM, maxDistanceM,
                azimuthMinDeg, azimuthMaxDeg, elevationMinDeg, elevationMaxDeg,
                angleRateDegPerSec, sunAvoidHalfAngleDeg, startupAngleRateDegPerSec);
    }

    /**
     * 构建标准对地观测载荷的持久化 JSON。它与对空配置使用完全相同的字段和米制距离，
     * 差异只在领域校验允许对地俯仰下限为 -91 度。
     */
    public String createGroundPayloadJson(Double minDistanceM,
                                          Double maxDistanceM,
                                          Double azimuthMinDeg,
                                          Double azimuthMaxDeg,
                                          Double elevationMinDeg,
                                          Double elevationMaxDeg,
                                          Double angleRateDegPerSec,
                                          Double sunAvoidHalfAngleDeg,
                                          Double startupAngleRateDegPerSec) {
        return createStandardPayloadJson(PayloadType.GROUND, minDistanceM, maxDistanceM,
                azimuthMinDeg, azimuthMaxDeg, elevationMinDeg, elevationMaxDeg,
                angleRateDegPerSec, sunAvoidHalfAngleDeg, startupAngleRateDegPerSec);
    }

    /**
     * 唯一的新格式序列化入口，保证两类载荷不会因不同字段或不同单位再次产生语义漂移。
     */
    private String createStandardPayloadJson(PayloadType payloadType,
                                             Double minDistanceM,
                                             Double maxDistanceM,
                                             Double azimuthMinDeg,
                                             Double azimuthMaxDeg,
                                             Double elevationMinDeg,
                                             Double elevationMaxDeg,
                                             Double angleRateDegPerSec,
                                             Double sunAvoidHalfAngleDeg,
                                             Double startupAngleRateDegPerSec) {
        StandardPayloads.create(payloadType, minDistanceM, maxDistanceM,
                azimuthMinDeg, azimuthMaxDeg, elevationMinDeg, elevationMaxDeg,
                angleRateDegPerSec, sunAvoidHalfAngleDeg, startupAngleRateDegPerSec);
        double minRange = minDistanceM;
        double maxRange = maxDistanceM;
        double[] azimuth = azimuthMinDeg == null ? null : new double[]{azimuthMinDeg, azimuthMaxDeg};
        double[] elevation = elevationMinDeg == null ? null : new double[]{elevationMinDeg, elevationMaxDeg};
        double rawAngleRate = angleRateDegPerSec;
        double sunAvoidHalfAngle = sunAvoidHalfAngleDeg;
        Double rawStartupAngleRate = startupAngleRateDegPerSec;

        PayloadDefinition definition = new PayloadDefinition();
        definition.setPayloadType(payloadType.name());
        definition.setDistance(new double[]{minRange, maxRange});
        // 新格式明确保存单位，后续版本和外部集成均不再依赖字段名或 UI 标签猜测 distance 的量纲。
        definition.setDistanceUnit(DISTANCE_UNIT_METRES);
        definition.setAzimuth(azimuth);
        definition.setElevation(elevation);
        // 对空载荷原值 0/-1 是 WinTool 的“不限制”标记；不能把内部的 Double.MAX_VALUE 回写，
        // 否则用户下次打开配置时会看不到原始参数语义。
        definition.setAngleRate(rawAngleRate);
        definition.setSunAvoidHalfAngle(sunAvoidHalfAngle);
        definition.setStartupAngleRate(rawStartupAngleRate);
        try {
            return objectMapper.writeValueAsString(definition);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("载荷参数序列化失败", exception);
        }
    }

    /**
     * 保持旧控制器调用可用的过渡入口。新保存代码必须使用完整的标准对地字段；
     * 这个入口仅表示历史锥角格式，待所有调用方完成迁移后可删除。
     */
    @Deprecated
    public String createGroundPayloadJson(Double sensorFullConeAngleDeg,
                                          Double maxDetectRangeKm,
                                          Double sunExclusionAngleDeg) {
        double fullConeAngle = requireFinite(sensorFullConeAngleDeg, "对地载荷全锥角");
        double maxRangeKm = requireFinite(maxDetectRangeKm, "对地最远探测距离");
        double sunAvoidAngle = requireFinite(sunExclusionAngleDeg, "太阳回避半角");
        PayloadConstraint.legacyGroundCone(fullConeAngle, maxRangeKm, sunAvoidAngle);

        PayloadDefinition definition = new PayloadDefinition();
        definition.setPayloadType("GROUND");
        definition.setSensorFullConeAngleDeg(fullConeAngle);
        definition.setMaxDetectRangeKm(maxRangeKm);
        definition.setSunExclusionAngleDeg(sunAvoidAngle);
        try {
            return objectMapper.writeValueAsString(definition);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("载荷参数序列化失败", exception);
        }
    }

    /** 校验范围参数必须恰好包含最小、最大两个数值。 */
    private double[] requireRange(double[] range, String parameterName) {
        if (range == null || range.length != 2) {
            throw new IllegalArgumentException(parameterName + "必须为[最小, 最大]二元数组");
        }
        return range;
    }

    /**
     * 将持久化的标准载荷距离范围转换为领域层唯一接受的米。
     *
     * <p>V6 数据迁移会把历史无单位值从公里转换为米并写入 {@code distanceUnit=M}。
     * 此处仍保留无单位即公里的读取兼容，是为了防止应用先于迁移执行、或存在历史备份
     * 恢复后短暂运行时再次把 1,000 km 误判为 1,000 m。显式 {@code KM} 也会被规范化，
     * 但新写入绝不产生该单位。</p>
     */
    private double[] distanceInMetres(PayloadDefinition definition) {
        double[] distance = requireRange(definition.getDistance(), "探测距离范围");
        String unit = definition.getDistanceUnit();
        if (unit == null || unit.trim().isEmpty() || DISTANCE_UNIT_KILOMETRES.equalsIgnoreCase(unit)) {
            return new double[]{distance[0] * METRES_PER_KILOMETRE, distance[1] * METRES_PER_KILOMETRE};
        }
        if (DISTANCE_UNIT_METRES.equalsIgnoreCase(unit)) {
            return distance;
        }
        throw new IllegalArgumentException("标准载荷距离单位仅支持 M 或 KM");
    }

    /**
     * 卫星表 PAYLOAD_JSON_ 字段的载荷定义模型，序列化时仅输出已赋值的标准字段。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PayloadDefinition {
        private String payloadType;
        private double[] distance;
        private String distanceUnit;
        private double[] azimuth;
        private double[] elevation;
        private Double angleRate;
        private Double sunAvoidHalfAngle;
        private Double startupAngleRate;
        private Double sensorFullConeAngleDeg;
        private Double maxDetectRangeKm;
        private Double sunExclusionAngleDeg;

        public String getPayloadType() { return payloadType; }
        public void setPayloadType(String payloadType) { this.payloadType = payloadType; }
        public double[] getDistance() { return distance; }
        public void setDistance(double[] distance) { this.distance = distance; }
        public String getDistanceUnit() { return distanceUnit; }
        public void setDistanceUnit(String distanceUnit) { this.distanceUnit = distanceUnit; }
        public double[] getAzimuth() { return azimuth; }
        public void setAzimuth(double[] azimuth) { this.azimuth = azimuth; }
        public double[] getElevation() { return elevation; }
        public void setElevation(double[] elevation) { this.elevation = elevation; }
        public Double getAngleRate() { return angleRate; }
        public void setAngleRate(Double angleRate) { this.angleRate = angleRate; }
        public Double getSunAvoidHalfAngle() { return sunAvoidHalfAngle; }
        public void setSunAvoidHalfAngle(Double sunAvoidHalfAngle) { this.sunAvoidHalfAngle = sunAvoidHalfAngle; }
        public Double getStartupAngleRate() { return startupAngleRate; }
        public void setStartupAngleRate(Double startupAngleRate) { this.startupAngleRate = startupAngleRate; }
        public Double getSensorFullConeAngleDeg() { return sensorFullConeAngleDeg; }
        public void setSensorFullConeAngleDeg(Double sensorFullConeAngleDeg) { this.sensorFullConeAngleDeg = sensorFullConeAngleDeg; }
        public Double getMaxDetectRangeKm() { return maxDetectRangeKm; }
        public void setMaxDetectRangeKm(Double maxDetectRangeKm) { this.maxDetectRangeKm = maxDetectRangeKm; }
        public Double getSunExclusionAngleDeg() { return sunExclusionAngleDeg; }
        public void setSunExclusionAngleDeg(Double sunExclusionAngleDeg) { this.sunExclusionAngleDeg = sunExclusionAngleDeg; }
    }

    /**
     * 一个卫星全部已保存载荷及当前参与可见性计算的活动类型。
     * 使用不可变副本，防止控制器在读取响应时意外改写持久化配置。
     */
    public static final class PayloadProfiles {
        private final String activePayloadType;
        private final Map<String, PayloadDefinition> profiles;

        private PayloadProfiles(String activePayloadType, Map<String, PayloadDefinition> profiles) {
            this.activePayloadType = activePayloadType == null ? null : activePayloadType.toUpperCase();
            this.profiles = new LinkedHashMap<String, PayloadDefinition>(profiles);
        }

        private static PayloadProfiles empty() {
            return new PayloadProfiles(null, new LinkedHashMap<String, PayloadDefinition>());
        }

        public String getActivePayloadType() { return activePayloadType; }
        public Map<String, PayloadDefinition> getProfiles() { return new LinkedHashMap<String, PayloadDefinition>(profiles); }

        /**
         * 返回指定的已保存 profile，而不受 activePayloadType 影响。
         * 业务调用方可据此绕过 {@code activePayloadType} 显式选择所需 profile，避免配置界面的
         * 当前选中状态影响计算口径。
         */
        public PayloadDefinition getDefinition(PayloadType payloadType) {
            return payloadType == null ? null : profiles.get(payloadType.name());
        }

        /** 活动类型缺失时按首次保存的配置回退，兼容极早期的手工 JSON。 */
        public PayloadDefinition getActiveDefinition() {
            if (activePayloadType != null && profiles.containsKey(activePayloadType)) {
                return profiles.get(activePayloadType);
            }
            return profiles.isEmpty() ? null : profiles.values().iterator().next();
        }
    }
}
