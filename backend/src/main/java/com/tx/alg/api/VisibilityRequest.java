package com.tx.alg.api;

import com.tx.alg.OrbitCalculator;
import java.util.ArrayList;
import java.util.List;

/**
 * 轨道可见性搜索请求 DTO。
 *
 * <p>所有字段均为可选，未提供时使用默认值。卫星列表为空时使用内置2星LEO星座。</p>
 */
public final class VisibilityRequest {

    /** 弹道文件路径，支持CSV和Excel，默认 "trajectory-0.1s-standard.csv" */
    private String ballisticCsvPath = "trajectory-0.1s-standard.csv";

    /** 基准发射UTC时间字符串（yyyy/MM/dd HH:mm:ss 或 yyyy-MM-dd HH:mm:ss），为空则使用弹道文件参考历元 */
    private String baseLaunchUtc;

    /** 多目标弹道输入数组。为空时兼容旧的 ballisticCsvPath/baseLaunchUtc 单目标输入。 */
    private List<BallisticDef> ballistics = new ArrayList<>();

    /** 发射偏移起点（秒），默认 -500 */
    private long startOffsetSec = -500L;

    /** 发射偏移步长（秒），默认 10 */
    private long stepSizeSec = 10L;

    /** 计算次数，默认 101（覆盖 T-500 到 T+500） */
    private long numCalculations = 101L;

    /** 传感器视场全锥角（度），默认 140 */
    private double sensorFovDeg = 140.0;

    /**
     * 载荷分类：对地监视、对空监视、对地跟踪、对空跟踪。
     * 旧版单载荷兼容字段，仅当 satellites[].payloads 为空时生效；
     * 平台内嵌载荷后，每颗卫星可携带多台不同类型载荷，分别计算可见窗口。
     */
    private String payloadType = "对地监视";

    /** 最远探测距离，单位km；小于等于0表示不限制 */
    private double maxDetectRangeKm = 0.0;

    /** 视线方向与太阳方向最小夹角，单位度；默认45，小于该值判为不可见 */
    private double sunExclusionAngleDeg = OrbitCalculator.DEFAULT_SUN_EXCLUSION_ANGLE_DEG;

    /** 目标轨迹可见性采样步长（秒），默认 1；小于等于0时使用算法自动步长 */
    private double targetSampleStepSec = 1.0;

    /** 1 输出窗口内轨迹点明细；0 仅输出窗口摘要。 */
    private int trajectoryPointOutput = 1;

    /** 轨道模型：TWO_BODY / J2_SECULAR / J2_NUMERICAL，默认 J2_NUMERICAL */
    private String orbitModel = "J2_NUMERICAL";

    /** 卫星列表，为空时使用内置2星LEO星座 */
    private List<SatelliteDef> satellites = new ArrayList<>();

    // ========== getter/setter ==========

    public String getBallisticCsvPath() { return ballisticCsvPath; }
    public void setBallisticCsvPath(String v) { this.ballisticCsvPath = v; }

    public String getBaseLaunchUtc() { return baseLaunchUtc; }
    public void setBaseLaunchUtc(String v) { this.baseLaunchUtc = v; }

    public List<BallisticDef> getBallistics() { return ballistics; }
    public void setBallistics(List<BallisticDef> v) { this.ballistics = v == null ? new ArrayList<BallisticDef>() : v; }

    public long getStartOffsetSec() { return startOffsetSec; }
    public void setStartOffsetSec(long v) { this.startOffsetSec = v; }

    public long getStepSizeSec() { return stepSizeSec; }
    public void setStepSizeSec(long v) { this.stepSizeSec = v; }

    public long getNumCalculations() { return numCalculations; }
    public void setNumCalculations(long v) { this.numCalculations = v; }

    public double getSensorFovDeg() { return sensorFovDeg; }
    public void setSensorFovDeg(double v) { this.sensorFovDeg = v; }

    public String getPayloadType() { return payloadType; }
    public void setPayloadType(String v) { this.payloadType = v; }

    public double getMaxDetectRangeKm() { return maxDetectRangeKm; }
    public void setMaxDetectRangeKm(double v) { this.maxDetectRangeKm = v; }

    public double getSunExclusionAngleDeg() { return sunExclusionAngleDeg; }
    public void setSunExclusionAngleDeg(double v) { this.sunExclusionAngleDeg = v; }

    public double getTargetSampleStepSec() { return targetSampleStepSec; }
    public void setTargetSampleStepSec(double v) { this.targetSampleStepSec = v; }

    public int getTrajectoryPointOutput() { return trajectoryPointOutput; }
    public void setTrajectoryPointOutput(int v) { this.trajectoryPointOutput = v; }

    public String getOrbitModel() { return orbitModel; }
    public void setOrbitModel(String v) { this.orbitModel = v; }

    public List<SatelliteDef> getSatellites() { return satellites; }
    public void setSatellites(List<SatelliteDef> v) { this.satellites = v; }

    // ========== 内部类 ==========

    /**
     * 单颗卫星六根数定义。
     * epochUtc 为历元时刻的UTC字符串，优先级高于 epochSec。
     */
    public static final class BallisticDef {
        private String csvPath;
        private String baseLaunchUTC;
        private String baseLaunchUtc;
        private String baselaunchUTC;
        private String targetId;
        private String targetName;

        public String getCsvPath() { return csvPath; }
        public void setCsvPath(String v) { this.csvPath = v; }

        public String getBaseLaunchUTC() { return baseLaunchUTC; }
        public void setBaseLaunchUTC(String v) { this.baseLaunchUTC = v; }

        public String getBaseLaunchUtc() { return baseLaunchUtc; }
        public void setBaseLaunchUtc(String v) { this.baseLaunchUtc = v; }

        public String getBaselaunchUTC() { return baselaunchUTC; }
        public void setBaselaunchUTC(String v) { this.baselaunchUTC = v; }

        public String getTargetId() { return targetId; }
        public void setTargetId(String v) { this.targetId = v; }

        public String getTargetName() { return targetName; }
        public void setTargetName(String v) { this.targetName = v; }

        OrbitCalculator.BallisticTargetInput toInput(int index) {
            String baseUtc = baseLaunchUTC != null && !baseLaunchUTC.trim().isEmpty()
                    ? baseLaunchUTC
                    : (baseLaunchUtc != null && !baseLaunchUtc.trim().isEmpty()
                    ? baseLaunchUtc
                    : baselaunchUTC);
            return new OrbitCalculator.BallisticTargetInput(
                    csvPath,
                    baseUtc,
                    targetId,
                    targetName);
        }
    }

    /**
     * 单颗卫星六根数定义。
     * epochUtc 为历元时刻的UTC字符串，优先级高于 epochSec。
     */
    public static final class SatelliteDef {
        private String satelliteId;
        private String name;
        private double semiMajorAxisM = 7530612.0;
        private double eccentricity = 0.00085;
        private double inclinationDeg = 64.8;
        private double raanDeg = 120.0;
        private double argumentOfPerigeeDeg = 270.0;
        private double meanAnomalyDeg;
        private String epochUtc;
        /** 卫星平台内嵌载荷；为空时使用顶层旧版单载荷参数。 */
        private List<PayloadDef> payloads = new ArrayList<PayloadDef>();

        public String getSatelliteId() { return satelliteId; }
        public void setSatelliteId(String v) { this.satelliteId = v; }

        public String getName() { return name; }
        public void setName(String v) { this.name = v; }

        public double getSemiMajorAxisM() { return semiMajorAxisM; }
        public void setSemiMajorAxisM(double v) { this.semiMajorAxisM = v; }

        public double getEccentricity() { return eccentricity; }
        public void setEccentricity(double v) { this.eccentricity = v; }

        public double getInclinationDeg() { return inclinationDeg; }
        public void setInclinationDeg(double v) { this.inclinationDeg = v; }

        public double getRaanDeg() { return raanDeg; }
        public void setRaanDeg(double v) { this.raanDeg = v; }

        public double getArgumentOfPerigeeDeg() { return argumentOfPerigeeDeg; }
        public void setArgumentOfPerigeeDeg(double v) { this.argumentOfPerigeeDeg = v; }

        public double getMeanAnomalyDeg() { return meanAnomalyDeg; }
        public void setMeanAnomalyDeg(double v) { this.meanAnomalyDeg = v; }

        public String getEpochUtc() { return epochUtc; }
        public void setEpochUtc(String v) { this.epochUtc = v; }

        public List<PayloadDef> getPayloads() { return payloads; }
        public void setPayloads(List<PayloadDef> v) {
            this.payloads = v == null ? new ArrayList<PayloadDef>() : v;
        }

        OrbitCalculator.OrbitElements toOrbitElements(int index) {
            OrbitCalculator.OrbitElements e = new OrbitCalculator.OrbitElements();
            e.satelliteId = (satelliteId != null && !satelliteId.trim().isEmpty())
                    ? satelliteId.trim()
                    : ((name != null && !name.trim().isEmpty()) ? name.trim() : "SAT-" + (index + 1));
            e.name = (name != null && !name.trim().isEmpty()) ? name.trim() : e.satelliteId;
            e.semiMajorAxisM = semiMajorAxisM;
            e.eccentricity = eccentricity;
            e.inclinationDeg = inclinationDeg;
            e.raanDeg = raanDeg;
            e.argumentOfPerigeeDeg = argumentOfPerigeeDeg;
            e.meanAnomalyDeg = meanAnomalyDeg;
            if (epochUtc != null && !epochUtc.trim().isEmpty()) {
                e.epochSec = OrbitCalculator.utcToJ2000Sec(epochUtc.trim());
            } else {
                e.epochSec = OrbitCalculator.getDefaultSatelliteEpochSec();
            }
            for (int payloadIndex = 0; payloadIndex < payloads.size(); payloadIndex++) {
                PayloadDef payload = payloads.get(payloadIndex);
                if (payload == null) {
                    throw new IllegalArgumentException("卫星" + e.satelliteId
                            + "的payloads[" + payloadIndex + "]不能为空。");
                }
                e.payloads.add(payload.toPayloadConfig(e.satelliteId, payloadIndex));
            }
            return e;
        }
    }

    /** 卫星平台内嵌的载荷定义。 */
    public static final class PayloadDef {
        private String payloadId;
        private String payloadName;
        private String payloadType = "对地监视";
        private double maxDetectRangeKm = 0.0;
        private double sunExclusionAngleDeg = OrbitCalculator.DEFAULT_SUN_EXCLUSION_ANGLE_DEG;

        public String getPayloadId() { return payloadId; }
        public void setPayloadId(String v) { this.payloadId = v; }

        public String getPayloadName() { return payloadName; }
        public void setPayloadName(String v) { this.payloadName = v; }

        public String getPayloadType() { return payloadType; }
        public void setPayloadType(String v) { this.payloadType = v; }

        public double getMaxDetectRangeKm() { return maxDetectRangeKm; }
        public void setMaxDetectRangeKm(double v) { this.maxDetectRangeKm = v; }

        public double getSunExclusionAngleDeg() { return sunExclusionAngleDeg; }
        public void setSunExclusionAngleDeg(double v) { this.sunExclusionAngleDeg = v; }

        OrbitCalculator.SatellitePayloadConfig toPayloadConfig(String satelliteId, int index) {
            OrbitCalculator.SatellitePayloadConfig payload = new OrbitCalculator.SatellitePayloadConfig();
            payload.payloadId = payloadId == null || payloadId.trim().isEmpty()
                    ? satelliteId + "-PAYLOAD-" + (index + 1)
                    : payloadId.trim();
            payload.payloadName = payloadName == null || payloadName.trim().isEmpty()
                    ? payloadIdOrDefault(payload.payloadId, payloadType)
                    : payloadName.trim();
            payload.sensorConfig = OrbitCalculator.SensorVisibilityConfig.forPayload(
                    payloadType,
                    maxDetectRangeKm,
                    sunExclusionAngleDeg);
            return payload;
        }

        private static String payloadIdOrDefault(String payloadId, String payloadType) {
            return payloadId == null || payloadId.trim().isEmpty()
                    ? (payloadType == null || payloadType.trim().isEmpty() ? "对地监视" : payloadType.trim())
                    : payloadId;
        }
    }

    // ========== 转换方法 ==========

    /** 按原列表顺序转换卫星与载荷，保持身份默认值和 epochUtc 优先逻辑。 */
    public List<OrbitCalculator.OrbitElements> toSatellites() {
        List<OrbitCalculator.OrbitElements> list = new ArrayList<>();
        for (int i = 0; i < satellites.size(); i++) {
            list.add(satellites.get(i).toOrbitElements(i));
        }
        return list;
    }

    /** 按原优先级解析发射时间别名，保留输入路径及目标首次出现顺序。 */
    public List<OrbitCalculator.BallisticTargetInput> toBallisticInputs() {
        List<OrbitCalculator.BallisticTargetInput> list = new ArrayList<>();
        for (int i = 0; i < ballistics.size(); i++) {
            list.add(ballistics.get(i).toInput(i));
        }
        return list;
    }

    /** 保持模型缺省与空字符串回退规则，不统一三种原传播模型。 */
    public OrbitCalculator.OrbitModel toOrbitModel() {
        if (orbitModel == null || orbitModel.trim().isEmpty()) {
            return OrbitCalculator.OrbitModel.TWO_BODY;
        }
        return OrbitCalculator.OrbitModel.fromDisplayName(orbitModel.trim());
    }

    /** 转换原载荷物理口径：距离为公里、角度为度，限制规则由原算法处理。 */
    public OrbitCalculator.SensorVisibilityConfig toSensorVisibilityConfig() {
        return OrbitCalculator.SensorVisibilityConfig.forPayload(
                payloadType,
                maxDetectRangeKm,
                sunExclusionAngleDeg);
    }
}
