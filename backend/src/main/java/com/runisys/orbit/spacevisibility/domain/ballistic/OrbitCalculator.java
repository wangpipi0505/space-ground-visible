package com.runisys.orbit.spacevisibility.domain.ballistic;

import com.runisys.orbit.spacevisibility.domain.geometry.GeometryMath;
import com.runisys.orbit.spacevisibility.domain.geometry.LegacyJ2000Frames;
import com.runisys.orbit.spacevisibility.domain.orbit.BallisticOrbitPropagation;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitDefinition;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitTime;
import com.runisys.orbit.spacevisibility.domain.payload.PayloadConstraint;
import com.runisys.orbit.spacevisibility.domain.time.LegacyJ2000Time;
import com.runisys.orbit.spacevisibility.domain.window.WindowIntervals;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.runisys.orbit.spacevisibility.domain.ballistic.BallisticArcSupport.arcDurationSec;
import static com.runisys.orbit.spacevisibility.domain.ballistic.BallisticArcSupport.arcEndJ2000Sec;
import static com.runisys.orbit.spacevisibility.domain.ballistic.BallisticArcSupport.arcPointCount;
import static com.runisys.orbit.spacevisibility.domain.ballistic.BallisticArcSupport.arcStartJ2000Sec;
import static com.runisys.orbit.spacevisibility.domain.ballistic.BallisticArcSupport.finalVisibleWindow;
import static com.runisys.orbit.spacevisibility.domain.ballistic.BallisticArcSupport.firstVisibilityConstraint;
import static com.runisys.orbit.spacevisibility.domain.ballistic.BallisticArcSupport.hasVisiblePoints;
import static com.runisys.orbit.spacevisibility.domain.ballistic.BallisticSampledWindows.interpolateAt;
import static com.runisys.orbit.spacevisibility.domain.ballistic.BallisticSampledWindows.sortedBallistic;
import static com.runisys.orbit.spacevisibility.domain.ballistic.BallisticVisibilityGeometry.isNonNegativeFinite;
import static com.runisys.orbit.spacevisibility.domain.ballistic.BallisticVisibilityGeometry.isPositiveFinite;
import static com.runisys.orbit.spacevisibility.domain.ballistic.BallisticVisibilityGeometry.normalizeSensorConfig;
import static com.runisys.orbit.spacevisibility.domain.ballistic.BallisticVisibilityGeometry.sunDirectionEci;

/**
 * 弹道计算的公开入口与数据类型，保留计算数据结构和文件/结果格式；地址加载由文件设施提供，多目标搜索显式传入加载端口。
 * 发射搜索、轨迹读取、可见性几何、弧段摘要及输出分别委托同包内部实现；轨道传播使用domain/orbit工具。
 * Web窗口搜索通过每目标独立历元执行；referenceEpochSec和utcOffsetSec仅为既有Java接口保留。
 * 直接Java调用若改写这些公有静态字段，仍需由调用方串行管理，不能与Web请求混用可变状态。
 */
public final class OrbitCalculator {
    public static final double EARTH_EQUATORIAL_RADIUS_M = 6378137.0;
    public static final double EARTH_MEAN_RADIUS_M = 6371008.8;
    public static final double EARTH_GRAVITATIONAL_PARAMETER = 3.986004418e14;
    // 该值相对 IERS 标称值 7.2921151467e-5 有截断，长时间积分的 Ω×r 项会引入微小系统误差。
    public static final double EARTH_ROTATION_RATE_RAD_S = 7.2921150e-5;
    public static final double EARTH_J2 = 1.08262668e-3;

    /**
     * 弹道文件的参考历元（J2000绝对秒），由 parseBallisticCsv() 自动从首行 YYYY/MM/dd/HH/mm/ss.SSS 解析。
     * 用于将弹道相对时间(tSec)映射到绝对时间轴，以及为卫星轨道传播提供时间基准。
     * 警告：这是全局可变状态，parseBallisticCsv() 解析文件时会副作用改写它；
     * 旧版（非V2）可见性路径直接读取该全局量叠加绝对时间，连续解析多个弹道文件或
     * 多目标计算时存在历元串扰风险；V2 路径（多目标扫描）已改为每个目标单独保存
     * sourceReferenceEpochSec 传入 analyzeOffset，不受此影响。
     */
    public static double referenceEpochSec = 0.0;

    /**
     * 弹道文件中时间字段相对UTC+0的时区偏移（秒）。
     * 默认0表示文件中 YYYY/MM/dd/HH/mm/ss.SSS 为真正UTC时间。
     * 如果历史文件中标注为UTC的时间实为北京时间(UTC+8)，可在界面中设置为-28800。
     * utcToJ2000Sec/j2000ToUtc 在转换时会自动叠加此偏移。
     * 警告：同为全局可变状态；api/OrbitVisibilityController 每个 HTTP 请求都会强制写 0，
     * Main.java/StkValidationExportTool 也会改写，同一 JVM 内多入口/并发请求会互相影响。
     */
    public static double utcOffsetSec = 0.0;

    /**
     * 卫星轨道六根数的默认历元UTC字符串。
     * 此值固定不变，独立于弹道文件的参考历元。
     * 卫星轨道外推始终从此历元出发，预测不同发射时刻的卫星位置。
     */
    public static final String DEFAULT_SATELLITE_EPOCH_UTC = "2026/07/01 10:00:00";

    /** 卫星默认历元时刻的J2000秒值，由 DEFAULT_SATELLITE_EPOCH_UTC 经 utcToJ2000Sec 计算。 */
    public static double getDefaultSatelliteEpochSec() {
        return utcToJ2000Sec(DEFAULT_SATELLITE_EPOCH_UTC);
    }

    private static final double EARTH_FLATTENING = 1.0 / 298.257223563;
    private static final double EARTH_E2 = EARTH_FLATTENING * (2.0 - EARTH_FLATTENING);

    public static final double DEFAULT_SUN_EXCLUSION_ANGLE_DEG = 45.0;
    public static final double DEFAULT_GROUND_SURVEILLANCE_HALF_ANGLE_DEG = 57.5;
    public static final double DEFAULT_GROUND_TRACKING_HALF_ANGLE_DEG = 65.0;
    public static final double DEFAULT_AIR_SURVEILLANCE_INNER_ANGLE_DEG = 59.0;
    public static final double DEFAULT_AIR_SURVEILLANCE_OUTER_ANGLE_DEG = 79.0;
    public static final double DEFAULT_AIR_TRACKING_INNER_ANGLE_DEG = 53.0;
    public static final double DEFAULT_AIR_TRACKING_OUTER_ANGLE_DEG = 150.0;

    private OrbitCalculator() {
    }

    /**
     * 卫星轨道六根数（开普勒元素），定义卫星在惯性空间中的轨道。
     * epochSec 是根数的参考历元（J2000绝对秒），固定不变；
     * 不同发射偏移量下的卫星位置通过从此历元外推得到。
     */
    public static final class OrbitElements {
        public String satelliteId;              // 卫星平台ID
        public String name;                     // 卫星代号
        public double semiMajorAxisM;           // 半长轴 a (m)，> 0
        public double eccentricity;             // 偏心率 e，0 ≤ e < 1
        public double inclinationDeg;           // 轨道倾角 i (deg)
        public double raanDeg;                  // 升交点赤经 Ω (deg)
        public double argumentOfPerigeeDeg;     // 近地点幅角 ω (deg)
        public double meanAnomalyDeg;           // 平近点角 M (deg)，历元时刻的值
        public double epochSec;                 // 历元时刻（J2000绝对秒），根数定义的时间基准
        /**
         * 卫星平台搭载的载荷列表。为空时沿用搜索入口传入的单载荷配置，
         * 以兼容既有算法调用与接口请求。
         */
        public final List<SatellitePayloadConfig> payloads = new ArrayList<SatellitePayloadConfig>();
    }

    public enum OrbitModel {
        TWO_BODY("二体Kepler"),
        J2_SECULAR("J2长期摄动"),
        J2_NUMERICAL("J2数值积分RK4");

        private final String displayName;

        OrbitModel(String displayName) {
            this.displayName = displayName;
        }

        public static String[] displayNames() {
            OrbitModel[] values = values();
            String[] names = new String[values.length];
            for (int i = 0; i < values.length; i++) {
                names[i] = values[i].displayName;
            }
            return names;
        }

        public static OrbitModel fromDisplayName(String displayName) {
            if (displayName == null) {
                return TWO_BODY;
            }
            String normalized = displayName.trim();
            for (OrbitModel model : values()) {
                if (model.displayName.equals(normalized) || model.name().equalsIgnoreCase(normalized)) {
                    return model;
                }
            }
            String upper = normalized.toUpperCase(Locale.ROOT);
            if (upper.contains("RK4") || upper.contains("NUMERICAL") || upper.contains("J2_NUM")) {
                return J2_NUMERICAL;
            }
            if (upper.contains("SECULAR") || upper.contains("J2_SEC")) {
                return J2_SECULAR;
            }
            if (upper.contains("TWO") || upper.contains("KEPLER") || upper.contains("2BODY")) {
                return TWO_BODY;
            }
            return TWO_BODY;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    /**
     * 载荷分类。四种载荷的视场模型——对地监视/对地跟踪为以天底为中心的简单圆锥
     * （半角 57.5°/65.0°），对空监视/对空跟踪为环形锥（59°~79° / 53°~150°，
     * 常量见本文件 DEFAULT_*_ANGLE_DEG）。
     * 注意：单次可见性判定只使用一种载荷类型（见 isInPayloadFieldOfView 的互斥 switch）；
     * 多载荷能力由卫星平台内嵌 payloads（SatellitePayloadConfig）实现——同一卫星平台的
     * 每个载荷持独立 SensorVisibilityConfig，对所有目标分别计算弧段，
     * 输出按 平台→payloadResults→targets→visibleArcs 分层，弧段带 payloadId/payloadType。
     * 未配置 payloads 时退化为旧版单载荷（入口传入的 SensorVisibilityConfig 对所有卫星生效）。
     */
    public enum PayloadType {
        GROUND_SURVEILLANCE("对地监视"),
        AIR_SURVEILLANCE("对空监视"),
        GROUND_TRACKING("对地跟踪"),
        AIR_TRACKING("对空跟踪");

        private final String displayName;

        PayloadType(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        public static String[] displayNames() {
            PayloadType[] values = values();
            String[] names = new String[values.length];
            for (int i = 0; i < values.length; i++) {
                names[i] = values[i].displayName;
            }
            return names;
        }

        public static PayloadType fromText(String text) {
            if (text == null || text.trim().isEmpty()) {
                return GROUND_SURVEILLANCE;
            }
            String normalized = text.trim()
                    .replace("-", "")
                    .replace("_", "")
                    .replace(" ", "")
                    .toUpperCase(Locale.ROOT);
            for (PayloadType type : values()) {
                if (normalized.equals(type.name().replace("_", ""))) {
                    return type;
                }
                if (text.trim().equals(type.displayName)) {
                    return type;
                }
            }
            if ("对地引导".equals(text.trim())) {
                return GROUND_TRACKING;
            }
            if ("对空引导".equals(text.trim())) {
                return AIR_TRACKING;
            }
            throw new IllegalArgumentException("不支持的载荷分类：" + text
                    + "。支持：对地监视、对空监视、对地跟踪、对空跟踪。");
        }
    }

    /**
     * 传感器可见性配置。只持有单个 payloadType（非载荷列表），即"一台载荷一份配置"。
     * 旧版入口只传一份 config，全任务共用；多载荷场景下由 OrbitElements.payloads
     * 为每台载荷各持一份本配置（见 SatellitePayloadConfig），搜索循环按载荷逐份取用。
     */
    public static final class SensorVisibilityConfig {
        public PayloadType payloadType = PayloadType.GROUND_SURVEILLANCE;
        /** 显式标准约束；null保留所有原圆锥路径。不得用旧枚举假装SPACE/GROUND。 */
        public PayloadConstraint standardConstraint;

        /** 标准不可变约束桥接；仅映射旧摘要真实存在的距离和太阳字段。 */
        public static SensorVisibilityConfig standard(PayloadConstraint constraint) {
            if (constraint == null || constraint.isLegacyGroundCone()) {
                throw new IllegalArgumentException("需要标准载荷约束");
            }
            SensorVisibilityConfig config = new SensorVisibilityConfig();
            config.standardConstraint = constraint;
            config.maxDetectRangeM = constraint.getMaxDetectRangeM();
            config.sunExclusionAngleDeg = constraint.getSunAvoidHalfAngleDeg();
            return config;
        }

        /** 正数生效；NaN、0或负数表示不限制探测距离。 */
        public double maxDetectRangeM = Double.NaN;
        /** 正数生效；默认45度，小于该夹角判为不可见。 */
        public double sunExclusionAngleDeg = DEFAULT_SUN_EXCLUSION_ANGLE_DEG;
        /** 兼容旧调用：若为正数，则按简单视场锥半角计算，不使用载荷分类默认视场。 */
        public double simpleConeHalfAngleDeg = Double.NaN;

        public static SensorVisibilityConfig forPayload(PayloadType payloadType) {
            SensorVisibilityConfig config = new SensorVisibilityConfig();
            config.payloadType = payloadType == null ? PayloadType.GROUND_SURVEILLANCE : payloadType;
            return config;
        }

        public static SensorVisibilityConfig forPayload(String payloadTypeText,
                                                        double maxDetectRangeKm,
                                                        double sunExclusionAngleDeg) {
            SensorVisibilityConfig config = forPayload(PayloadType.fromText(payloadTypeText));
            config.maxDetectRangeM = maxDetectRangeKm > 0.0 ? maxDetectRangeKm * 1000.0 : Double.NaN;
            config.sunExclusionAngleDeg = sunExclusionAngleDeg;
            return config;
        }

        public static SensorVisibilityConfig legacySimpleCone(double sensorFovDeg) {
            SensorVisibilityConfig config = new SensorVisibilityConfig();
            config.simpleConeHalfAngleDeg = sensorFovDeg / 2.0;
            config.sunExclusionAngleDeg = 0.0;
            return config;
        }
    }

    /**
     * 卫星平台内嵌的载荷可见性配置。
     * 卫星轨道根数属于平台；视场、探测距离和太阳规避角属于载荷。
     */
    public static final class SatellitePayloadConfig {
        public String payloadId;
        public String payloadName;
        public SensorVisibilityConfig sensorConfig = SensorVisibilityConfig.forPayload(
                PayloadType.GROUND_SURVEILLANCE);
    }

    public static final class VisibilityConstraintSummary {
        public String payloadType;
        public double maxDetectRangeKm;
        public double sunExclusionAngleDeg;
        public double simpleConeHalfAngleDeg = Double.NaN;
        public double halfConeAngleDeg = Double.NaN;
        public double innerConeAngleDeg = Double.NaN;
        public double outerConeAngleDeg = Double.NaN;

        public static VisibilityConstraintSummary fromConfig(SensorVisibilityConfig sensorConfig) {
            SensorVisibilityConfig config = normalizeSensorConfig(sensorConfig, 140.0);
            VisibilityConstraintSummary summary = new VisibilityConstraintSummary();
            if (config.standardConstraint != null) {
                summary.payloadType = config.standardConstraint.getPayloadType().name();
                summary.maxDetectRangeKm = config.standardConstraint.getMaxDetectRangeM() / 1000.0;
                summary.sunExclusionAngleDeg = config.standardConstraint.getSunAvoidHalfAngleDeg();
                // 矩形LVLH不能用圆锥表示：四个锥角维持NaN，旧JSON格式器输出null。
                return summary;
            }

            PayloadType type = config.payloadType == null ? PayloadType.GROUND_SURVEILLANCE : config.payloadType;
            summary.payloadType = type.displayName();
            summary.maxDetectRangeKm = isPositiveFinite(config.maxDetectRangeM)
                    ? config.maxDetectRangeM / 1000.0
                    : 0.0;
            summary.sunExclusionAngleDeg = isNonNegativeFinite(config.sunExclusionAngleDeg)
                    ? config.sunExclusionAngleDeg
                    : DEFAULT_SUN_EXCLUSION_ANGLE_DEG;
            if (isPositiveFinite(config.simpleConeHalfAngleDeg)) {
                summary.simpleConeHalfAngleDeg = config.simpleConeHalfAngleDeg;
                summary.halfConeAngleDeg = config.simpleConeHalfAngleDeg;
                return summary;
            }
            switch (type) {
                case GROUND_TRACKING:
                    summary.halfConeAngleDeg = DEFAULT_GROUND_TRACKING_HALF_ANGLE_DEG;
                    break;
                case AIR_SURVEILLANCE:
                    summary.innerConeAngleDeg = DEFAULT_AIR_SURVEILLANCE_INNER_ANGLE_DEG;
                    summary.outerConeAngleDeg = DEFAULT_AIR_SURVEILLANCE_OUTER_ANGLE_DEG;
                    break;
                case AIR_TRACKING:
                    summary.innerConeAngleDeg = DEFAULT_AIR_TRACKING_INNER_ANGLE_DEG;
                    summary.outerConeAngleDeg = DEFAULT_AIR_TRACKING_OUTER_ANGLE_DEG;
                    break;
                case GROUND_SURVEILLANCE:
                default:
                    summary.halfConeAngleDeg = DEFAULT_GROUND_SURVEILLANCE_HALF_ANGLE_DEG;
                    break;
            }
            return summary;
        }
    }

    public static final class GeodeticDatum {
        public static final GeodeticDatum WGS84 = new GeodeticDatum("WGS84", 6378137.0, 1.0 / 298.257223563);
        public static final GeodeticDatum CGCS2000 = new GeodeticDatum("CGCS2000", 6378137.0, 1.0 / 298.257222101);
        public static final GeodeticDatum GRS80 = new GeodeticDatum("GRS80", 6378137.0, 1.0 / 298.257222101);
        public static final GeodeticDatum SPHERE = new GeodeticDatum("球体近似", EARTH_MEAN_RADIUS_M, 0.0);

        public final String name;
        public final double equatorialRadiusM;
        public final double flattening;

        public GeodeticDatum(String name, double equatorialRadiusM, double flattening) {
            this.name = name;
            this.equatorialRadiusM = equatorialRadiusM;
            this.flattening = flattening;
        }

        public double eccentricitySquared() {
            return flattening * (2.0 - flattening);
        }

        public static GeodeticDatum fromName(String name) {
            if (name == null) {
                return WGS84;
            }
            if (name.contains("CGCS2000")) {
                return CGCS2000;
            }
            if (name.contains("GRS80")) {
                return GRS80;
            }
            if (name.contains("球体")) {
                return SPHERE;
            }
            return WGS84;
        }

        public static String[] displayNames() {
            return new String[]{"WGS84", "CGCS2000", "GRS80", "球体近似"};
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * 弹道轨迹点，由18列CSV解析而来。
     * tSec=0 对应参考发射时刻；ECI坐标为J2000惯性系下的位置/速度；
     * ECEF为地固系坐标；heightM是WGS84大地高（椭球高），由 ecefToGeodetic 计算。
     * 可见性计算直接使用eciX/Y/Z，不再从lat/lon/height重新换算。
     */
    public static final class BallisticPoint {
        public double tSec;         // 相对发射时刻的时间偏移 (s)，t=0为发射瞬间
        public double eciX, eciY, eciZ;         // ECI惯性系位置 (m)
        public double eciVx, eciVy, eciVz;      // ECI惯性系速度 (m/s)
        public double ecefX, ecefY, ecefZ;      // ECEF地固系位置 (m)
        public double ecefVx, ecefVy, ecefVz;   // ECEF地固系速度 (m/s)
        public double latDeg;       // 纬度 (deg)，由CSV中rad转换
        public double lonDeg;       // 经度 (deg)，由CSV中rad转换
        public double heightM;      // WGS84大地高（椭球高）(m)，由 ecefToGeodetic 计算
        public double speedMs;      // 合速度 (m/s)
    }

    public static final class ParsedBallisticCsv {
        public final List<BallisticPoint> points;
        public final double referenceEpochSec;
        public final String referenceUtc;

        ParsedBallisticCsv(List<BallisticPoint> points, double referenceEpochSec) {
            this.points = new ArrayList<BallisticPoint>(points);
            this.referenceEpochSec = referenceEpochSec;
            this.referenceUtc = j2000ToUtcMillis(referenceEpochSec);
        }
    }

    public static final class TrajectoryPoint {
        public double tSec;
        public double eciX, eciY, eciZ;
        public double eciVx, eciVy, eciVz;
        public double ecefX, ecefY, ecefZ;
        public double ecefVx, ecefVy, ecefVz;
        public double latDeg;
        public double lonDeg;
        public double heightM;
        public double geocentricRadiusM;
    }

    public interface ProgressCallback {
        void onProgress(long completed, long total);
    }

    /** 旧公开向量外壳：保持 public x/y/z 与全部签名，标量数学委托领域工具且不额外分配对象。 */
    public static final class Vector3 {
        public final double x;
        public final double y;
        public final double z;

        public Vector3(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public Vector3 add(Vector3 other) {
            return new Vector3(x + other.x, y + other.y, z + other.z);
        }

        public Vector3 subtract(Vector3 other) {
            return new Vector3(x - other.x, y - other.y, z - other.z);
        }

        public Vector3 scale(double factor) {
            return new Vector3(x * factor, y * factor, z * factor);
        }

        /** 同参考系向量点积，共用标量主体保留原累加顺序；单位为两个输入单位之积。 */
        public double dot(Vector3 other) {
            return GeometryMath.dot(x, y, z, other.x, other.y, other.z);
        }

        /** 共用原 sqrt(dot(v,v))，位置/差向量返回米；零向量及下溢仍返回零，不新增校验。 */
        public double norm() {
            return GeometryMath.norm(x, y, z);
        }
    }

    /** 模拟专用动力学仍使用原内部状态容器；卫星传播状态已归纯领域工具。 */
    private static final class StateVector {
        private final Vector3 position;
        private final Vector3 velocity;
        private StateVector(Vector3 position, Vector3 velocity) { this.position = position; this.velocity = velocity; }
    }

    /** 模拟原先也使用卫星J2单步，此节点继续复用同一公式，不复制算法。 */
    private static StateVector rk4StepJ2(StateVector state, double stepSec) {
        BallisticOrbitPropagation.StateVector result = BallisticOrbitPropagation.rk4StepJ2(
                new BallisticOrbitPropagation.StateVector(
                        new com.runisys.orbit.spacevisibility.domain.geometry.Vector3(state.position.x, state.position.y, state.position.z),
                        new com.runisys.orbit.spacevisibility.domain.geometry.Vector3(state.velocity.x, state.velocity.y, state.velocity.z)), stepSec);
        return new StateVector(new Vector3(result.position.getX(), result.position.getY(), result.position.getZ()),
                new Vector3(result.velocity.getX(), result.velocity.getY(), result.velocity.getZ()));
    }

    public static final class TimedPosition {
        public final double tSec;
        public final Vector3 position;

        public TimedPosition(double tSec, Vector3 position) {
            this.tSec = tSec;
            this.position = position;
        }
    }

    public static final class Track {
        public final String name;
        public final List<TimedPosition> points;

        public Track(String name, List<TimedPosition> points) {
            this.name = name;
            this.points = points;
        }
    }

    /** 旧单锥窗口，边界为原样本秒；时长沿用整秒点计数的+1，不等价于V2连续时长。 */
    public static final class VisibilityWindow {
        public final double startRelSec;
        public final double endRelSec;

        public VisibilityWindow(double startRelSec, double endRelSec) {
            this.startRelSec = startRelSec;
            this.endRelSec = endRelSec;
        }

        /** 单点仍为1秒，负时长归零；保留原加法先后及NaN行为。 */
        public double durationSec() {
            return Math.max(0.0, endRelSec - startRelSec + 1.0);
        }
    }

    /** 旧单卫星窗口，与旧总体窗口同用max(0,end-start+1)历史口径。 */
    public static final class SatelliteVisibilityWindow {
        public final String satelliteName;
        public final double startRelSec;
        public final double endRelSec;

        public SatelliteVisibilityWindow(String satelliteName, double startRelSec, double endRelSec) {
            this.satelliteName = satelliteName;
            this.startRelSec = startRelSec;
            this.endRelSec = endRelSec;
        }

        /** 原整秒计数语义，不能移除+1来迎合空间区间或V2弧段。 */
        public double durationSec() {
            return Math.max(0.0, endRelSec - startRelSec + 1.0);
        }

        public String toChineseText() {
            return satelliteName + "：T+" + Math.round(startRelSec) + "~" + Math.round(endRelSec)
                    + "s（" + Math.round(durationSec()) + "s）";
        }
    }

    public static final class LaunchOffsetResult {
        public final long offsetSecond;
        public final long observableSeconds;
        public final List<VisibilityWindow> visibleWindows;
        public final List<SatelliteVisibilityWindow> satelliteWindows;

        public LaunchOffsetResult(long offsetSecond,
                                  long observableSeconds,
                                  List<VisibilityWindow> visibleWindows,
                                  List<SatelliteVisibilityWindow> satelliteWindows) {
            this.offsetSecond = offsetSecond;
            this.observableSeconds = observableSeconds;
            this.visibleWindows = visibleWindows;
            this.satelliteWindows = satelliteWindows;
        }

        public String windowsText() {
            if (visibleWindows.isEmpty()) {
                return "无";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < visibleWindows.size(); i++) {
                VisibilityWindow window = visibleWindows.get(i);
                if (i > 0) {
                    sb.append("; ");
                }
                sb.append("T+")
                        .append(Math.round(window.startRelSec))
                        .append("~")
                        .append(Math.round(window.endRelSec))
                        .append("s");
            }
            return sb.toString();
        }

        public String satelliteWindowsText() {
            if (satelliteWindows.isEmpty()) {
                return "无";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < satelliteWindows.size(); i++) {
                if (i > 0) {
                    sb.append("; ");
                }
                sb.append(satelliteWindows.get(i).toChineseText());
            }
            return sb.toString();
        }
    }

    public static final class LaunchSearchResult {
        public final List<LaunchOffsetResult> candidates;
        public final LaunchOffsetResult best;

        public LaunchSearchResult(List<LaunchOffsetResult> candidates, LaunchOffsetResult best) {
            this.candidates = candidates;
            this.best = best;
        }

        public String toChineseText() {
            if (best == null) {
                return "未得到有效偏移量结果。\n";
            }
            return "候选偏移量数量：" + candidates.size() + "\n"
                    + "最优偏移时间：" + signedSeconds(best.offsetSecond) + "\n"
                    + "最大可见导弹时长：" + best.observableSeconds + " s\n"
                    + "可见时间窗口：" + best.windowsText() + "\n";
        }
    }

    public static final class LaunchEstimationResult {
        public long bestLaunchSecond;
        public long bestObservableSeconds;
        public long candidateCount;

        public String toChineseText() {
            return "候选偏移量数量：" + candidateCount + "\n"
                    + "最优偏移时间：" + signedSeconds(bestLaunchSecond) + "\n"
                    + "最大可见导弹时长：" + bestObservableSeconds + " s\n";
        }
    }

    // ========== V2 可见性计算数据结构 ==========

    /**
     * 可见弧段内的单个轨迹点，记录目标在某一时刻的位置和对应弹道文件中的时间。
     * absoluteTimeSec 是当前发射时间下该点的J2000绝对时间；
     * relativeTimeSec 是CSV文件中的原始point.tSec，用于追溯原始弹道数据。
     */
    public static final class VisibleTrajectoryPoint {
        public double longitudeDeg;
        public double latitudeDeg;
        public double heightM;
        public double absoluteTimeSec;
        public double relativeTimeSec;

        public VisibleTrajectoryPoint(double longitudeDeg, double latitudeDeg, double heightM,
                                       double absoluteTimeSec, double relativeTimeSec) {
            this.longitudeDeg = longitudeDeg;
            this.latitudeDeg = latitudeDeg;
            this.heightM = heightM;
            this.absoluteTimeSec = absoluteTimeSec;
            this.relativeTimeSec = relativeTimeSec;
        }
    }

    /**
     * 一颗卫星在一个发射偏移量下的一个可见弧段结果。
     * 若卫星在一次弹道飞行中有多段不连续的可见窗口，每段为一个独立的arcIndex。
     */
    public static final class SatelliteVisibilityResult {
        public String satelliteId;
        public String satelliteName;
        public int satelliteIndex;
        public int arcIndex;
        public String payloadId;
        public String payloadName;
        public String targetId;
        public String targetName;
        public String ballisticCsvPath;
        public String baseLaunchUTC;
        public String launchUTC;
        public String payloadType;
        public VisibilityConstraintSummary visibilityConstraint;
        public double launchTimeJ2000Sec;
        public double sourceReferenceEpochSec = Double.NaN;
        public double startJ2000Sec = Double.NaN;
        public double endJ2000Sec = Double.NaN;
        public double startRelativeTimeSec = Double.NaN;
        public double endRelativeTimeSec = Double.NaN;
        public int visiblePointCount;
        public final List<VisibleTrajectoryPoint> trajectoryPoints = new ArrayList<VisibleTrajectoryPoint>();
    }

    public static final class BallisticTargetInput {
        public String csvPath;
        public String baseLaunchUtc;
        public String targetId;
        public String targetName;

        public BallisticTargetInput() {
        }

        public BallisticTargetInput(String csvPath,
                                    String baseLaunchUtc,
                                    String targetId,
                                    String targetName) {
            this.csvPath = csvPath;
            this.baseLaunchUtc = baseLaunchUtc;
            this.targetId = targetId;
            this.targetName = targetName;
        }
    }

    public static final class TimeWindowSummary {
        public final double startJ2000Sec;
        public final double endJ2000Sec;
        public final String startUtc;
        public final String endUtc;
        public final double durationSeconds;

        TimeWindowSummary(double startJ2000Sec, double endJ2000Sec) {
            this.startJ2000Sec = startJ2000Sec;
            this.endJ2000Sec = endJ2000Sec;
            this.startUtc = j2000ToUtcMillis(startJ2000Sec);
            this.endUtc = j2000ToUtcMillis(endJ2000Sec);
            this.durationSeconds = Math.max(0.0, endJ2000Sec - startJ2000Sec);
        }
    }

    public static final class VisibleWindowSummary {
        public String satelliteId;
        public String satelliteName;
        public int satelliteIndex;
        public int arcIndex;
        public String payloadId;
        public String payloadName;
        public double startJ2000Sec;
        public double endJ2000Sec;
        public String startUtc;
        public String endUtc;
        public String payloadType;
        public double durationSeconds;
        public int pointCount;

        static VisibleWindowSummary fromArc(SatelliteVisibilityResult arc) {
            VisibleWindowSummary summary = new VisibleWindowSummary();
            summary.satelliteId = arc.satelliteId;
            summary.satelliteName = arc.satelliteName;
            summary.satelliteIndex = arc.satelliteIndex;
            summary.arcIndex = arc.arcIndex;
            summary.payloadId = arc.payloadId;
            summary.payloadName = arc.payloadName;
            summary.payloadType = arc.payloadType;
            summary.pointCount = arcPointCount(arc);
            if (summary.pointCount > 0) {
                double start = arcStartJ2000Sec(arc);
                double end = arcEndJ2000Sec(arc);
                summary.startJ2000Sec = start;
                summary.endJ2000Sec = end;
                summary.startUtc = j2000ToUtcMillis(start);
                summary.endUtc = j2000ToUtcMillis(end);
                summary.durationSeconds = Math.max(0.0, end - start);
            }
            return summary;
        }
    }

    public static final class TargetVisibilitySummary {
        public String targetId;
        public String targetName;
        public String csvPath;
        public String baseLaunchUTC;
        public String launchUTC;
        public String payloadType;
        public double launchTimeJ2000Sec;
        public final List<VisibleWindowSummary> visibleWindows = new ArrayList<VisibleWindowSummary>();

        static TargetVisibilitySummary fromCalc(LaunchOffsetCalcResult calc) {
            TargetVisibilitySummary target = new TargetVisibilitySummary();
            target.targetId = calc.targetId;
            target.targetName = calc.targetName;
            target.csvPath = calc.ballisticCsvPath;
            target.baseLaunchUTC = calc.baseLaunchUTC;
            target.launchUTC = calc.launchTimeUtc;
            target.payloadType = calc.payloadType;
            target.launchTimeJ2000Sec = calc.launchTimeJ2000Sec;
            for (SatelliteVisibilityResult arc : calc.visibleSatellites) {
                if (sameText(target.targetId, arc.targetId) && hasVisiblePoints(arc)) {
                    target.visibleWindows.add(VisibleWindowSummary.fromArc(arc));
                }
            }
            return target;
        }
    }

    /**
     * 单次发射偏移量的完整计算结果。
     * offsetSecond = i × stepSizeSec；launchTimeUtc 是本次发射的绝对UTC时间；
     * visibleSatellites 仅包含至少有一段可见弧段的卫星。
     */
    public static final class LaunchOffsetCalcResult {
        public String targetId;
        public String targetName;
        public String ballisticCsvPath;
        public String baseLaunchUTC;
        public String launchTimeUtc;
        public String payloadType;
        public VisibilityConstraintSummary visibilityConstraint;
        public double launchTimeJ2000Sec;
        public double sourceReferenceEpochSec = Double.NaN;
        public long offsetSecond;
        public final List<SatelliteVisibilityResult> visibleSatellites = new ArrayList<SatelliteVisibilityResult>();
        public final List<TargetVisibilitySummary> targets = new ArrayList<TargetVisibilitySummary>();
        public long totalObservableSeconds() {
            return Math.round(totalObservableDurationSec());
        }

        /** 逐原弧段顺序相加；重叠载荷不做并集，非正时长且点数正时回退点数。 */
        public double totalObservableDurationSec() {
            // 共用有序累计主体；不同载荷的重叠弧段分别相加，点数fallback仍沿用旧口径。
            WindowIntervals.Totals totals = WindowIntervals.total(visibleSatellites,
                    BallisticArcSupport::arcDurationSec, BallisticArcSupport::arcPointCount);
            long total = totals.getPointCount();
            double totalSec = totals.getDurationSec();
            if (totalSec <= 0.0 && total > 0L) {
                return total;
            }
            return totalSec;
        }

        public long totalObservablePointCount() {
            long total = 0L;
            for (SatelliteVisibilityResult svr : visibleSatellites) {
                total += arcPointCount(svr);
            }
            return total;
        }

        public String toJson() {
            return BallisticResultWriter.toJson(this);
        }

        public TimeWindowSummary finalVisibleWindow() {
            return BallisticArcSupport.finalVisibleWindow(visibleSatellites);
        }

        /**
         * 序列化为中文字段 JSON（兼容旧版输出格式）。
         */
        String toChineseJson() {
            return BallisticResultWriter.toChineseJson(this);
        }
    }

    /**
     * V2 完整搜索结果。
     * allResults 长度 = numCalculations，每个元素对应一次偏移量计算；
     * best按逐星逐载荷的连续可见时长totalObservableDurationSec()选取，相同时保留首个偏移。
     */
    public static final class LaunchSearchResultV2 {
        public final List<LaunchOffsetCalcResult> allResults;
        public final LaunchOffsetCalcResult best;
        public final String referenceUtc;
        public final VisibilityConstraintSummary visibilityConstraint;

        public LaunchSearchResultV2(List<LaunchOffsetCalcResult> allResults,
                                     LaunchOffsetCalcResult best,
                                     String referenceUtc) {
            this(allResults, best, referenceUtc, firstVisibilityConstraint(allResults, best));
        }

        public LaunchSearchResultV2(List<LaunchOffsetCalcResult> allResults,
                                     LaunchOffsetCalcResult best,
                                     String referenceUtc,
                                     VisibilityConstraintSummary visibilityConstraint) {
            this.allResults = allResults;
            this.best = best;
            this.referenceUtc = referenceUtc;
            this.visibilityConstraint = visibilityConstraint;
        }

        public String toJson() {
            return toJson(true);
        }

        /**
         * 序列化为英文字段 JSON 对象（含 referenceUtc 和 best）。
         *
         * @param includeBest true 时输出 best 字段及最优偏移量详情
         */
        public String toJson(boolean includeBest) {
            return BallisticResultWriter.toJson(this, includeBest);
        }

        public Path writeToFile(Path dir) throws IOException {
            String timestamp = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path file = dir.resolve("visibility_result_" + timestamp + ".json");
            try (java.io.BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                writeJsonTo(writer, true);
            }
            return file;
        }

        public void writeJsonTo(java.io.Writer writer, boolean includeBest) throws IOException {
            BallisticResultWriter.writeJsonTo(this, writer, includeBest);
        }

        public String toChineseText() {
            return BallisticResultWriter.toChineseText(this);
        }
    }

    static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    static boolean sameText(String a, String b) {
        return nullToEmpty(a).equals(nullToEmpty(b));
    }

    // ========== V2 可见性计算方法 ==========

    /**
     * V2 可见性搜索主入口。
     * 从 baseLaunchJ2000Sec 出发，以 stepSizeSec 为步长进行 numCalculations 次偏移量扫描。
     * 每次偏移量调用 analyzeLaunchOffsetV2 计算逐卫星可见弧段；
     * 最终按totalObservableDurationSec()累计可见时长选出最优偏移；时长相同保留先出现的结果。
     *
     * @param baseLaunchJ2000Sec 基准发射时间（J2000绝对秒），由用户输入的UTC时间转换而来
     * @param stepSizeSec        发射偏移步长 (s)，每次发射延后此秒数
     * @param numCalculations    计算次数，总范围 = [base, base + (num-1)*step]
     * @param satellites         星座卫星轨道六根数列表
     * @param ballisticPoints    弹道轨迹点（CSV原始点，不插值）
     * @param sensorFovDeg       卫星载荷视场全锥角 (deg)
     * @param orbitModel         轨道传播模型
     * @param progressCallback   进度回调，completed/total 均以计算次数计数，可为 null
     */
    public static LaunchSearchResultV2 searchLaunchOffsetsV2(double baseLaunchJ2000Sec, long stepSizeSec,
            long numCalculations, List<OrbitElements> satellites, List<BallisticPoint> ballisticPoints,
            double sensorFovDeg, OrbitModel orbitModel, ProgressCallback progressCallback) {
        return searchLaunchOffsetsV2(
                baseLaunchJ2000Sec,
                0L,
                stepSizeSec,
                numCalculations,
                satellites,
                ballisticPoints,
                sensorFovDeg,
                orbitModel,
                progressCallback
        );
    }

    public static LaunchSearchResultV2 searchLaunchOffsetsV2(double baseLaunchJ2000Sec, long startOffsetSec,
            long stepSizeSec, long numCalculations, List<OrbitElements> satellites,
            List<BallisticPoint> ballisticPoints, double sensorFovDeg, OrbitModel orbitModel,
            ProgressCallback progressCallback) {
        return searchLaunchOffsetsV2(
                baseLaunchJ2000Sec,
                startOffsetSec,
                stepSizeSec,
                numCalculations,
                satellites,
                ballisticPoints,
                sensorFovDeg,
                orbitModel,
                Double.NaN,
                progressCallback);
    }

    public static LaunchSearchResultV2 searchLaunchOffsetsV2(double baseLaunchJ2000Sec, long startOffsetSec,
            long stepSizeSec, long numCalculations, List<OrbitElements> satellites,
            List<BallisticPoint> ballisticPoints, double sensorFovDeg, OrbitModel orbitModel,
            double targetSampleStepSec, ProgressCallback progressCallback) {
        return searchLaunchOffsetsV2(
                baseLaunchJ2000Sec,
                startOffsetSec,
                stepSizeSec,
                numCalculations,
                satellites,
                ballisticPoints,
                sensorFovDeg,
                orbitModel,
                targetSampleStepSec,
                1,
                progressCallback);
    }

    public static LaunchSearchResultV2 searchLaunchOffsetsV2(double baseLaunchJ2000Sec, long startOffsetSec,
            long stepSizeSec, long numCalculations, List<OrbitElements> satellites,
            List<BallisticPoint> ballisticPoints, double sensorFovDeg, OrbitModel orbitModel,
            double targetSampleStepSec, int trajectoryPointOutput, ProgressCallback progressCallback) {
        return searchLaunchOffsetsV2(
                baseLaunchJ2000Sec,
                startOffsetSec,
                stepSizeSec,
                numCalculations,
                satellites,
                ballisticPoints,
                sensorFovDeg,
                orbitModel,
                targetSampleStepSec,
                referenceEpochSec,
                trajectoryPointOutput,
                progressCallback);
    }

    public static LaunchSearchResultV2 searchLaunchOffsetsV2(double baseLaunchJ2000Sec, long startOffsetSec,
            long stepSizeSec, long numCalculations, List<OrbitElements> satellites,
            List<BallisticPoint> ballisticPoints, double sensorFovDeg, OrbitModel orbitModel,
            double targetSampleStepSec, double sourceReferenceEpochSec, int trajectoryPointOutput,
            ProgressCallback progressCallback) {
        return searchLaunchOffsetsV2(
                baseLaunchJ2000Sec,
                startOffsetSec,
                stepSizeSec,
                numCalculations,
                satellites,
                ballisticPoints,
                sensorFovDeg,
                orbitModel,
                targetSampleStepSec,
                sourceReferenceEpochSec,
                trajectoryPointOutput,
                SensorVisibilityConfig.legacySimpleCone(sensorFovDeg),
                progressCallback);
    }

    public static LaunchSearchResultV2 searchLaunchOffsetsV2(double baseLaunchJ2000Sec, long startOffsetSec,
            long stepSizeSec, long numCalculations, List<OrbitElements> satellites,
            List<BallisticPoint> ballisticPoints, double sensorFovDeg, OrbitModel orbitModel,
            double targetSampleStepSec, double sourceReferenceEpochSec, int trajectoryPointOutput,
            SensorVisibilityConfig sensorConfig, ProgressCallback progressCallback) {
        return BallisticLaunchSearch.searchSingle(baseLaunchJ2000Sec, startOffsetSec, stepSizeSec, numCalculations,
                satellites, ballisticPoints, sensorFovDeg, orbitModel, targetSampleStepSec,
                sourceReferenceEpochSec, trajectoryPointOutput, sensorConfig, progressCallback);
    }

    /**
     * 每个目标的源加载边界：返回完整内存轨迹和该文件独立参考历元；实现须在返回前释放文件资源。
     * 调用方显式提供文件解析策略；本接口不依赖 Spring、HTTP 或下载实现。
     */
    @FunctionalInterface
    public interface BallisticCsvLoader {
        /** @param sourceLocation 原逻辑源标识，不能用临时路径覆盖输出中的 csvPath。 */
        ParsedBallisticCsv load(String sourceLocation) throws IOException;
    }

    /**
     * 在同一 V2 算法主体内替换文件加载策略；校验、加载、采样、偏移并行回填和严格最优比较保持原顺序。
     * 原目标标识传入 PreparedBallisticTarget，解析返回值只提供数值和独立源历元。
     * @param loader 源数据完整加载器，返回后算法不再读取源文件
     */
    public static LaunchSearchResultV2 searchLaunchOffsetsV2(List<BallisticTargetInput> targets,
            long startOffsetSec, long stepSizeSec, long numCalculations, List<OrbitElements> satellites,
            double sensorFovDeg, OrbitModel orbitModel, double targetSampleStepSec, int trajectoryPointOutput,
            SensorVisibilityConfig sensorConfig, ProgressCallback progressCallback, BallisticCsvLoader loader) throws IOException {
        return BallisticLaunchSearch.searchMultiple(targets, startOffsetSec, stepSizeSec, numCalculations,
                satellites, sensorFovDeg, orbitModel, targetSampleStepSec, trajectoryPointOutput, sensorConfig,
                progressCallback, loader);
    }

    public static List<Track> generateSatelliteTracks(List<OrbitElements> satellites, double durationSec,
            double stepSec) {
        return generateSatelliteTracks(satellites, 0.0, durationSec, stepSec);
    }

    public static List<Track> generateSatelliteTracks(List<OrbitElements> satellites, double startSec,
            double endSec, double stepSec) {
        return generateSatelliteTracks(satellites, startSec, endSec, stepSec, OrbitModel.TWO_BODY);
    }

    public static List<Track> generateSatelliteTracks(List<OrbitElements> satellites, double startSec,
            double endSec, double stepSec, OrbitModel orbitModel) {
        return BallisticSampledWindows.generateSatelliteTracks(satellites, startSec, endSec, stepSec, orbitModel);
    }

    public static Vector3 propagate(OrbitElements elements, double tSec) {
        return propagate(elements, tSec, OrbitModel.TWO_BODY);
    }

    /**
     * 轨道传播：计算卫星在指定时刻的ECI位置。
     * tSec 为相对时间（从参考发射时刻起的秒数），
     * 内部叠加 referenceEpochSec 转换为J2000绝对时间后再计算 dt = absoluteTSec - elements.epochSec。
     * 支持三种模型：二体Kepler、J2长期摄动、J2数值积分(RK4)。
     */
    public static Vector3 propagate(OrbitElements elements, double tSec, OrbitModel orbitModel) {
        double absoluteTSec = referenceEpochSec + tSec;
        return propagateAtAbsolute(elements, absoluteTSec, orbitModel);
    }

    /**
     * 保留旧公有入口及嵌套根数类型；每次读取可变根数和 utcOffsetSec 的当前值。
     * 工具只查询位置，原 referenceEpochSec + tSec 的运算仍由 propagate 完成。
     */
    public static Vector3 propagateAtAbsolute(OrbitElements elements, double absoluteTSec, OrbitModel orbitModel) {
        validateOrbit(elements);
        OrbitModel activeModel = orbitModel == null ? OrbitModel.TWO_BODY : orbitModel;
        OrbitDefinition.Elements definition = new OrbitDefinition.Elements(
                OrbitDefinition.Model.valueOf(activeModel.name()), elements.semiMajorAxisM,
                elements.eccentricity, elements.inclinationDeg, elements.raanDeg,
                elements.argumentOfPerigeeDeg, elements.meanAnomalyDeg,
                OrbitTime.legacyJ2000(Double.isFinite(elements.epochSec) ? elements.epochSec : 0.0,
                        Double.isFinite(utcOffsetSec) ? utcOffsetSec : 0.0), 0.0);
        // 原公有入口未校验非有限历元/时刻；仅此遗留输入不进入严格有限时间工具。
        // utcOffsetSec不参与传播数学，非有限值也不能新增异常或改变dt计算。
        if (!Double.isFinite(elements.epochSec) || !Double.isFinite(absoluteTSec) || !Double.isFinite(utcOffsetSec)) {
            com.runisys.orbit.spacevisibility.domain.geometry.Vector3 legacy =
                    BallisticOrbitPropagation.legacyNonFinitePosition(definition, absoluteTSec, elements.epochSec);
            return new Vector3(legacy.getX(), legacy.getY(), legacy.getZ());
        }
        com.runisys.orbit.spacevisibility.domain.geometry.Vector3 position = BallisticOrbitPropagation.bind(definition)
                .positionAt(OrbitTime.legacyJ2000(absoluteTSec, utcOffsetSec), OrbitDefinition.Frame.J2000);
        return new Vector3(position.getX(), position.getY(), position.getZ());
    }

    /** 原公开 J2 长期升交点变化率，实际公式集中在纯领域工具。 */
    public static double j2SecularRaanRateRadPerSec(double semiMajorAxisM, double eccentricity,
            double inclinationDeg) {
        return BallisticOrbitPropagation.j2SecularRaanRateRadPerSec(semiMajorAxisM, eccentricity, inclinationDeg);
    }

    /**
     * 解析弹道文件，自动识别CSV或Excel，并从首个轨迹点 YYYY/MM/dd/HH/mm/ss.SSS 提取参考历元到 referenceEpochSec。
     *
     * <p>对外固定输入格式为20列UTC分列格式：
     * YYYY,MM,dd,HH,mm,ss.SSS,ECEF_X,ECEF_Y,ECEF_Z,energy,dt(ms),ECI_X,ECI_Y,ECI_Z,ECI_Vx,ECI_Vy,ECI_Vz,ECEF_Vx,ECEF_Vy,ECEF_Vz。
     * Excel文件的列顺序与CSV一致，读取第一个工作表。</p>
     *
     * <p>新格式时间全部按UTC处理，不叠加北京时间或其他时区偏移；tSec由每行UTC与首行UTC的差值计算。</p>
     */
    public static List<BallisticPoint> parseBallisticCsv(Path filePath) throws IOException {
        return BallisticTrajectoryReader.parseBallisticCsv(filePath);
    }

    public static ParsedBallisticCsv parseBallisticCsvWithReference(Path filePath) throws IOException {
        return BallisticTrajectoryReader.parseBallisticCsvWithReference(filePath);
    }

    /**
     * 从UTF-8字节流解析弹道CSV。调用方负责关闭传入的InputStream；字节流入口不做Excel自动识别。
     */
    public static List<BallisticPoint> parseBallisticCsv(InputStream inputStream) throws IOException {
        return BallisticTrajectoryReader.parseBallisticCsv(inputStream);
    }

    public static ParsedBallisticCsv parseBallisticCsvWithReference(InputStream inputStream) throws IOException {
        return BallisticTrajectoryReader.parseBallisticCsvWithReference(inputStream);
    }

    /**
     * 从字符流逐行解析弹道CSV。调用方负责关闭传入的Reader。
     */
    public static List<BallisticPoint> parseBallisticCsv(Reader sourceReader) throws IOException {
        return BallisticTrajectoryReader.parseBallisticCsv(sourceReader);
    }

    public static ParsedBallisticCsv parseBallisticCsvWithReference(Reader sourceReader) throws IOException {
        return BallisticTrajectoryReader.parseBallisticCsvWithReference(sourceReader);
    }

    public static List<BallisticPoint> interpolateBallistic(List<BallisticPoint> points, double stepSec) {
        return BallisticSampledWindows.interpolateBallistic(points, stepSec);
    }

    public static double ballisticStartSec(List<BallisticPoint> ballisticPoints) {
        return BallisticSampledWindows.ballisticStartSec(ballisticPoints);
    }

    public static double ballisticEndSec(List<BallisticPoint> ballisticPoints) {
        return BallisticSampledWindows.ballisticEndSec(ballisticPoints);
    }

    public static Track generateBallisticTrack(List<BallisticPoint> ballisticPoints, double displayStepSec) {
        return generateBallisticTrack(ballisticPoints, displayStepSec, 0.0);
    }

    public static Track generateBallisticTrack(List<BallisticPoint> ballisticPoints, double displayStepSec,
            double launchOffsetSec) {
        return generateBallisticTrack(ballisticPoints, displayStepSec, launchOffsetSec, GeodeticDatum.WGS84);
    }

    public static Track generateBallisticTrack(List<BallisticPoint> ballisticPoints, double displayStepSec,
            double launchOffsetSec, GeodeticDatum datum) {
        return BallisticSampledWindows.generateBallisticTrack(ballisticPoints, displayStepSec, launchOffsetSec, datum);
    }

    public static List<Track> generateVisibleBallisticTracks(List<BallisticPoint> ballisticPoints,
            double displayStepSec, double launchOffsetSec, List<VisibilityWindow> windows) {
        return generateVisibleBallisticTracks(ballisticPoints, displayStepSec, launchOffsetSec, windows, GeodeticDatum.WGS84);
    }

    public static List<Track> generateVisibleBallisticTracks(List<BallisticPoint> ballisticPoints,
            double displayStepSec, double launchOffsetSec, List<VisibilityWindow> windows, GeodeticDatum datum) {
        return BallisticSampledWindows.generateVisibleBallisticTracks(ballisticPoints, displayStepSec,
                launchOffsetSec, windows, datum);
    }

    public static LaunchSearchResult searchLaunchOffsets(long startTimeSec, long stepSizeSec, long numCalculations,
            List<OrbitElements> satellites, List<BallisticPoint> ballisticPoints, double sensorFovDeg) {
        return searchLaunchOffsets(startTimeSec, stepSizeSec, numCalculations, satellites, ballisticPoints, sensorFovDeg, GeodeticDatum.WGS84);
    }

    public static LaunchSearchResult searchLaunchOffsets(long startTimeSec, long stepSizeSec, long numCalculations,
            List<OrbitElements> satellites, List<BallisticPoint> ballisticPoints, double sensorFovDeg,
            GeodeticDatum datum) {
        return searchLaunchOffsets(startTimeSec, stepSizeSec, numCalculations, satellites, ballisticPoints, sensorFovDeg, datum, OrbitModel.TWO_BODY, null);
    }

    /**
     * 从起始发射点时间开始，以计算步长搜索指定次数的发射偏移量，找到"卫星可见导弹总时长最大"的偏移量。
     *
     * <p>弹道文件中的 t=0 被视为基准发射时刻。从 startTimeSec 开始，每次增加 stepSizeSec，
     * 共计算 numCalculations 次。例如 startTimeSec=-1800, stepSizeSec=10, numCalculations=361
     * 表示搜索 T-1800s 到 T+1800s，步长 10s。</p>
     *
     * <p>对每一个候选偏移量，算法会把弹道按 1 秒插值，然后逐秒判断"是否至少有一颗卫星可见
     * 当前导弹位置"。可见性判断包括三部分：六根数轨道传播得到卫星 ECI 位置；经纬高按所选
     * 大地基准转换到 ECI；最后用传感器星下点视场角和地球遮挡做几何判定。</p>
     *
     * <p>一次候选偏移量分析会同时产出两个结果：所有卫星合并后的可见窗口，以及逐卫星可见窗口。
     * 这样界面既能显示总可见时长，也能展开查看"哪颗卫星、哪段时间可见"，避免重复计算。</p>
     *
     * @param progressCallback 可选进度回调，completed/total 均以候选偏移量数量计数；可为 null
     */
    public static LaunchSearchResult searchLaunchOffsets(long startTimeSec, long stepSizeSec, long numCalculations,
            List<OrbitElements> satellites, List<BallisticPoint> ballisticPoints, double sensorFovDeg,
            GeodeticDatum datum, OrbitModel orbitModel, ProgressCallback progressCallback) {
        return BallisticSampledWindows.searchLaunchOffsets(startTimeSec, stepSizeSec, numCalculations, satellites,
                ballisticPoints, sensorFovDeg, datum, orbitModel, progressCallback);
    }

    public static LaunchEstimationResult estimateBestLaunchSecond(long startTimeSec, long stepSizeSec,
            long numCalculations, List<OrbitElements> satellites, List<BallisticPoint> ballisticPoints,
            double sensorFovDeg) {
        return estimateBestLaunchSecond(startTimeSec, stepSizeSec, numCalculations, satellites, ballisticPoints, sensorFovDeg, GeodeticDatum.WGS84);
    }

    public static LaunchEstimationResult estimateBestLaunchSecond(long startTimeSec, long stepSizeSec,
            long numCalculations, List<OrbitElements> satellites, List<BallisticPoint> ballisticPoints,
            double sensorFovDeg, GeodeticDatum datum) {
        return BallisticSampledWindows.estimateBestLaunchSecond(startTimeSec, stepSizeSec, numCalculations,
                satellites, ballisticPoints, sensorFovDeg, datum);
    }

    /**
     * 旧单锥扫描按原列表逐点判定，共用段记录保留首末可见原点；段结束取末点，不插值。
     * long标记仅保存double原始位，负零也原样还原；最终尾段仍在循环结束后补入。
     */
    public static List<VisibilityWindow> visibilityWindows(double launchOffsetSec, List<OrbitElements> satellites,
            List<BallisticPoint> oneSecondSamples, double sensorFovDeg) {
        return visibilityWindows(launchOffsetSec, satellites, oneSecondSamples, sensorFovDeg, GeodeticDatum.WGS84);
    }

    public static List<VisibilityWindow> visibilityWindows(double launchOffsetSec, List<OrbitElements> satellites,
            List<BallisticPoint> oneSecondSamples, double sensorFovDeg, GeodeticDatum datum) {
        return BallisticSampledWindows.visibilityWindows(launchOffsetSec, satellites, oneSecondSamples,
                sensorFovDeg, datum);
    }

    /** 逐卫星独立记录连续段，卫星外层/原点内层顺序以及每颗卫星的尾段位置保持原样。 */
    public static List<SatelliteVisibilityWindow> satelliteVisibilityWindows(double launchOffsetSec,
            List<OrbitElements> satellites, List<BallisticPoint> oneSecondSamples, double sensorFovDeg,
            GeodeticDatum datum) {
        return BallisticSampledWindows.satelliteVisibilityWindows(launchOffsetSec, satellites, oneSecondSamples,
                sensorFovDeg, datum);
    }

    public static boolean isBallisticPointVisible(double launchOffsetSec, BallisticPoint point,
            List<OrbitElements> satellites, double sensorFovDeg) {
        return isBallisticPointVisible(launchOffsetSec, point, satellites, sensorFovDeg, GeodeticDatum.WGS84);
    }

    public static boolean isBallisticPointVisible(double launchOffsetSec, BallisticPoint point,
            List<OrbitElements> satellites, double sensorFovDeg, GeodeticDatum datum) {
        return BallisticSampledWindows.isBallisticPointVisible(launchOffsetSec, point, satellites, sensorFovDeg, datum);
    }

    public static boolean isBallisticPointVisibleBySatellite(double launchOffsetSec, BallisticPoint point,
            OrbitElements satelliteElements, double sensorFovDeg, GeodeticDatum datum) {
        return BallisticSampledWindows.isBallisticPointVisibleBySatellite(launchOffsetSec, point,
                satelliteElements, sensorFovDeg, datum);
    }

    public static Vector3 geodeticToEci(double latDeg, double lonDeg, double heightM, double tSec) {
        return geodeticToEci(latDeg, lonDeg, heightM, tSec, GeodeticDatum.WGS84);
    }

    public static Vector3 geodeticToEci(double latDeg, double lonDeg, double heightM, double tSec,
            GeodeticDatum datum) {
        GeodeticDatum activeDatum = datum == null ? GeodeticDatum.WGS84 : datum;
        double lat = radians(latDeg);
        double lon = radians(lonDeg);
        double sinLat = Math.sin(lat);
        double cosLat = Math.cos(lat);
        double e2 = activeDatum.eccentricitySquared();
        double n = activeDatum.equatorialRadiusM / Math.sqrt(1.0 - e2 * sinLat * sinLat);

        double xEcef = (n + heightM) * cosLat * Math.cos(lon);
        double yEcef = (n + heightM) * cosLat * Math.sin(lon);
        double zEcef = (n * (1.0 - e2) + heightM) * sinLat;
        return ecefToEci(xEcef, yEcef, zEcef, tSec);
    }

    public static Vector3 sphereToEci(double radiusM, double latDeg, double lonDeg, double tSec) {
        double lat = radians(latDeg);
        double lon = radians(lonDeg);
        double cosLat = Math.cos(lat);
        double xEcef = radiusM * cosLat * Math.cos(lon);
        double yEcef = radiusM * cosLat * Math.sin(lon);
        double zEcef = radiusM * Math.sin(lat);
        return ecefToEci(xEcef, yEcef, zEcef, tSec);
    }

    public static Vector3 ballisticPointToEci(BallisticPoint point, double launchOffsetSec) {
        return ballisticPointToEciAtTime(point, referenceEpochSec + launchOffsetSec + point.tSec);
    }

    /**
     * 从弹道点获取指定绝对时刻的ECI惯性系位置。
     *
     * <p>弹道文件中的ECEF坐标描述目标相对地球固连坐标系的轨迹；当发射时间发生偏移时，
     * 目标在地固系中的相对轨迹保持不变，但映射到ECI/J2000时必须使用新的绝对时刻
     * 重新旋转。这样才能与STK按不同发射时刻重算目标轨迹和可见性分析的口径一致。</p>
     */
    public static Vector3 ballisticPointToEciAtTime(BallisticPoint point, double absoluteJ2000Sec) {
        return ecefToEci(point.ecefX, point.ecefY, point.ecefZ, absoluteJ2000Sec);
    }

    public static double parseBallisticEpochSec(Path filePath) throws IOException {
        return BallisticTrajectoryReader.parseBallisticEpochSec(filePath);
    }

    /**
     * 从UTF-8字节流读取弹道CSV首个有效轨迹点的J2000绝对秒。调用方负责关闭传入的InputStream；字节流入口不做Excel自动识别。
     */
    public static double parseBallisticEpochSec(InputStream inputStream) throws IOException {
        return BallisticTrajectoryReader.parseBallisticEpochSec(inputStream);
    }

    /**
     * 从字符流读取弹道CSV首个有效轨迹点的J2000绝对秒。调用方负责关闭传入的Reader。
     */
    public static double parseBallisticEpochSec(Reader sourceReader) throws IOException {
        return BallisticTrajectoryReader.parseBallisticEpochSec(sourceReader);
    }

    /**
     * UTC时间字符串 → J2000绝对秒数。
     * 支持格式：yyyy/MM/dd HH:mm:ss.SSS 或 yyyy-MM-dd HH:mm:ss.SSS，毫秒可省略。
     * 新版接口默认按UTC直接计算；仅兼容历史数据时才通过 utcOffsetSec 手工补偿时区。
     * J2000历元 = 2000-01-01 12:00:00 UTC。
     */
    public static double utcToJ2000Sec(String utcTime) {
        return LegacyJ2000Time.utcToJ2000Sec(utcTime, utcOffsetSec);
    }

    /**
     * J2000绝对秒数 → UTC时间字符串（yyyy/MM/dd HH:mm:ss）。
     * 为兼容旧数据，会反向补偿 utcOffsetSec；新版UTC数据该值保持0。
     */
    public static String j2000ToUtc(double j2000Sec) {
        return LegacyJ2000Time.j2000ToUtc(j2000Sec, utcOffsetSec);
    }

    /** 兼容原毫秒UTC格式，显式传递旧公开偏移字段到纯领域转换主体。 */
    public static String j2000ToUtcMillis(double j2000Sec) {
        return LegacyJ2000Time.j2000ToUtcMillis(j2000Sec, utcOffsetSec);
    }

    public static BallisticPoint sampleBallisticAtAbsoluteTime(List<BallisticPoint> ballisticPoints,
            double launchOffsetSec, double absoluteTimeSec) {
        List<BallisticPoint> sorted = sortedBallistic(ballisticPoints);
        double relTime = absoluteTimeSec - launchOffsetSec;
        if (relTime < sorted.get(0).tSec - 1e-9 || relTime > sorted.get(sorted.size() - 1).tSec + 1e-9) {
            return null;
        }
        return interpolateAt(sorted, relTime);
    }

    public static String signedSeconds(long seconds) {
        if (seconds > 0L) {
            return "+" + seconds + " s";
        }
        return seconds + " s";
    }

    // ========== 轨迹传播（从ECI状态矢量出发）==========

    /**
     * J2000(ECI)→ECEF 位置转换。tSec 形参一律为 J2000 起算绝对秒（不是相对发射时刻的秒数）；
     * 转换链为 岁差(IAU1976)→章动(简化IAU1980)→绕Z轴转GAST，每个采样点按其各自绝对时刻
     * 独立重算，即惯性系(J2000)本身不随时间变化，变化的是 J2000→当日真赤道真春分点的转换关系。
     */
    public static Vector3 eciToEcefPos(Vector3 rEci, double tSec) {
        return fromDomainVector(LegacyJ2000Frames.eciToEcefPos(toDomainVector(rEci), tSec));
    }

    /**
     * J2000(ECI)→ECEF 速度转换。tSec 同 eciToEcefPos，为 J2000 起算绝对秒。
     * 委托领域主体并保留 Ω×r 修正（真旋转系速度）及原重复计算顺序。
     */
    public static Vector3 eciToEcefVel(Vector3 vEci, Vector3 rEci, double tSec) {
        return fromDomainVector(LegacyJ2000Frames.eciToEcefVel(toDomainVector(vEci), toDomainVector(rEci), tSec));
    }

    /**
     * 将J2000惯性速度转换为STK Fixed报表中的速度口径。
     * 该口径表示“惯性速度在地固坐标轴上的分量”，不扣除地球自转引起的 Ω×r 项；
     * 用于输出CSV中的ECEF_Vx/ECEF_Vy/ECEF_Vz，以便和STK Fixed坐标速度保持一致。
     * 注意与 eciToEcefVel 的口径差异——本方法不扣 Ω×r（STK Fixed 报表口径），
     * eciToEcefVel 扣 Ω×r（真地固系速度）；TargetTrajectoryFitter 输出的 ECEF_V* 列与
     * TrajectoryDesignApp 输出的 v_ecef_* 列分别对应这两种口径，列名雷同但语义不同，
     * 跨工具对比时勿混用。
     */
    public static Vector3 eciToStkFixedVelocity(Vector3 vEci, double tSec) {
        return fromDomainVector(LegacyJ2000Frames.eciToStkFixedVelocity(toDomainVector(vEci), tSec));
    }

    /** 原ECEF位置（米）转J2000位置（米）；tSec为原UTC日历J2000绝对秒。 */
    public static Vector3 ecefToEciPos(Vector3 rEcef, double tSec) {
        return fromDomainVector(LegacyJ2000Frames.ecefToEciPos(toDomainVector(rEcef), tSec));
    }

    /** 原真地固导数（米/秒）转J2000速度；先加Ω×r，不接受STK Fixed惯性分量。 */
    public static Vector3 ecefToEciVel(Vector3 vEcef, Vector3 rEcef, double tSec) {
        return fromDomainVector(LegacyJ2000Frames.ecefToEciVel(toDomainVector(vEcef), toDomainVector(rEcef), tSec));
    }

    /** 旧公开向量在兼容边界拷贝到领域类型，不执行任何数值转换。 */
    static com.runisys.orbit.spacevisibility.domain.geometry.Vector3 toDomainVector(Vector3 value) {
        return new com.runisys.orbit.spacevisibility.domain.geometry.Vector3(value.x, value.y, value.z);
    }

    /** 领域坐标结果按原double分量返回旧公开类型，保持公有签名兼容。 */
    private static Vector3 fromDomainVector(com.runisys.orbit.spacevisibility.domain.geometry.Vector3 value) {
        return new Vector3(value.getX(), value.getY(), value.getZ());
    }

    public static double[] ecefToGeodetic(Vector3 rEcef) {
        double x = rEcef.x;
        double y = rEcef.y;
        double z = rEcef.z;
        double lon = Math.atan2(y, x);
        double p = Math.sqrt(x * x + y * y);
        if (p < 1e-12) {
            double lat = z >= 0.0 ? Math.PI / 2.0 : -Math.PI / 2.0;
            double h = Math.abs(z) - EARTH_EQUATORIAL_RADIUS_M * (1.0 - EARTH_E2);
            return new double[]{Math.toDegrees(lat), Math.toDegrees(lon), h};
        }
        double lat = Math.atan2(z, p * (1.0 - EARTH_E2));
        for (int i = 0; i < 10; i++) {
            double sinLat = Math.sin(lat);
            double n = EARTH_EQUATORIAL_RADIUS_M / Math.sqrt(1.0 - EARTH_E2 * sinLat * sinLat);
            double h = p / Math.cos(lat) - n;
            double latNew = Math.atan2(z, p * (1.0 - EARTH_E2 * n / (n + h)));
            if (Math.abs(latNew - lat) < 1e-13) {
                lat = latNew;
                break;
            }
            lat = latNew;
        }
        double sinLatFinal = Math.sin(lat);
        double nFinal = EARTH_EQUATORIAL_RADIUS_M / Math.sqrt(1.0 - EARTH_E2 * sinLatFinal * sinLatFinal);
        double hFinal = p / Math.cos(lat) - nFinal;
        return new double[]{Math.toDegrees(lat), Math.toDegrees(lon), hFinal};
    }

    /**
     * 兼容旧调用的重载：等价于 launchEpochSec = 0.0（J2000 历元）。
     * 新代码应使用带 launchEpochSec 参数的版本，否则输出的 ECEF/经纬度列
     * 将按 J2000(2000-01-01 12:00 UTC) 历元转换，与真实发射时刻不符。
     */
    public static List<TrajectoryPoint> propagateFromStateVector(Vector3 r0Eci, Vector3 v0Eci,
            double totalTimeSec, double stepSec, double outputStepSec, ProgressCallback progressCallback) {
        return propagateFromStateVector(r0Eci, v0Eci, 0.0,
                totalTimeSec, stepSec, outputStepSec, progressCallback);
    }

    /**
     * 从 ECI 状态矢量出发做 RK4+J2 纯惯性积分，并按输出步长采样生成轨迹点。
     *
     * <p>launchEpochSec 为发射时刻的 J2000 绝对秒（可由 utcToJ2000Sec 从UTC字符串转换）。
     * 循环内 t 是相对发射时刻的秒数（t=0 为发射瞬间），ECI→ECEF/经纬度转换一律使用
     * 绝对时刻 launchEpochSec + t，保证改用新的发射时间重新生成弹道时，
     * ECEF 列与经纬度列随时间正确变化；ECI 列与历元无关（纯惯性积分）。</p>
     */
    public static List<TrajectoryPoint> propagateFromStateVector(Vector3 r0Eci, Vector3 v0Eci,
            double launchEpochSec, double totalTimeSec, double stepSec, double outputStepSec,
            ProgressCallback progressCallback) {
        validatePositive(stepSec, "积分步长");
        validatePositive(outputStepSec, "输出步长");
        if (totalTimeSec < 0.0) {
            throw new IllegalArgumentException("飞行时间不能为负。");
        }

        long totalSteps = (long) Math.ceil(totalTimeSec / stepSec);
        long outputInterval = Math.max(1L, Math.round(outputStepSec / stepSec));
        List<TrajectoryPoint> result = new ArrayList<TrajectoryPoint>();

        StateVector state = new StateVector(r0Eci, v0Eci);

        for (long step = 0; step <= totalSteps; step++) {
            double t = step * stepSec;
            if (t > totalTimeSec + 1e-9) {
                t = totalTimeSec;
            }

            if (step % outputInterval == 0 || step == totalSteps) {
                // t 是相对发射时刻的秒数；坐标转换必须使用绝对时刻（发射历元 + t），
                // 否则 ECEF/经纬度列会被钉在 J2000 历元，不随发射时间变化。
                double absoluteJ2000Sec = launchEpochSec + t;
                Vector3 rEcef = eciToEcefPos(state.position, absoluteJ2000Sec);
                Vector3 vEcef = eciToEcefVel(state.velocity, state.position, absoluteJ2000Sec);
                double[] geo = ecefToGeodetic(rEcef);

                TrajectoryPoint point = new TrajectoryPoint();
                point.tSec = t;
                point.eciX = state.position.x;
                point.eciY = state.position.y;
                point.eciZ = state.position.z;
                point.eciVx = state.velocity.x;
                point.eciVy = state.velocity.y;
                point.eciVz = state.velocity.z;
                point.ecefX = rEcef.x;
                point.ecefY = rEcef.y;
                point.ecefZ = rEcef.z;
                point.ecefVx = vEcef.x;
                point.ecefVy = vEcef.y;
                point.ecefVz = vEcef.z;
                point.latDeg = geo[0];
                point.lonDeg = geo[1];
                point.heightM = geo[2];
                point.geocentricRadiusM = state.position.norm();
                result.add(point);
            }

            if (step < totalSteps) {
                double actualStep = Math.min(stepSec, totalTimeSec - t);
                state = rk4StepJ2(state, actualStep);
            }

            if (progressCallback != null && step % 100 == 0) {
                progressCallback.onProgress(step, totalSteps);
            }
        }

        if (progressCallback != null) {
            progressCallback.onProgress(totalSteps, totalSteps);
        }
        return result;
    }

    private static Vector3 ecefToEci(double xEcef, double yEcef, double zEcef, double tSec) {
        return ecefToEciPos(new Vector3(xEcef, yEcef, zEcef), tSec);
    }

    // 第二套简化 GMST 实现，当前全工程无调用（死代码），且与主链 earthOrientation 的 GAST 口径不一致，
    // 保留仅为历史兼容，新代码不要使用。
    public static double greenwichSiderealAngleRad(double j2000Sec) {
        double julianDate = 2451545.0 + j2000Sec / 86400.0;
        double daysFromJ2000 = julianDate - 2451545.0;
        double centuries = daysFromJ2000 / 36525.0;
        double gmstDeg = 280.46061837
                + 360.98564736629 * daysFromJ2000
                + 0.000387933 * centuries * centuries
                - centuries * centuries * centuries / 38710000.0;
        return normalizeRadians(Math.toRadians(gmstDeg));
    }

    /**
     * 判断目标是否对卫星可见。
     * 两个条件同时满足：①目标在传感器视场锥内（天底方向与视线夹角 ≤ FOV/2）；
     * ②视线不被地球遮挡（地球临边判断）。
     */
    public static boolean isTargetObservableFromSatellite(Vector3 satellite, Vector3 target, double sensorFovDeg) {
        return BallisticVisibilityGeometry.isTargetObservableFromSatellite(satellite, target, sensorFovDeg);
    }

    public static boolean isTargetObservableFromSatellite(Vector3 satellite, Vector3 target, double sensorFovDeg,
            double absoluteJ2000Sec) {
        return BallisticVisibilityGeometry.isTargetObservableFromSatellite(satellite, target, sensorFovDeg,
                absoluteJ2000Sec);
    }

    public static boolean isTargetObservableFromSatellite(Vector3 satellite, Vector3 target,
            SensorVisibilityConfig sensorConfig, double absoluteJ2000Sec, Vector3 sunDirectionEci) {
        return BallisticVisibilityGeometry.isTargetObservableFromSatellite(satellite, target, sensorConfig,
                absoluteJ2000Sec, sunDirectionEci);
    }

    /**
     * 标准弹道载荷：原J2000解析太阳、原时刻旋转后的WGS84遮挡，与共用LVLH/距离/速率主体组合。
     * 弹道始终回避太阳，不借用空间本影豁免。相邻LOS历史由每次独立扫描提供，单位米与秒。
     */
    public static boolean isStandardTargetObservable(Vector3 satellite,
            com.runisys.orbit.spacevisibility.domain.geometry.Vector3 velocity, Vector3 target,
            PayloadConstraint constraint, double absoluteJ2000Sec,
            com.runisys.orbit.spacevisibility.domain.geometry.Vector3 previousLos, double elapsedSec) {
        return BallisticVisibilityGeometry.isStandardTargetObservable(satellite, velocity, target, constraint,
                absoluteJ2000Sec, previousLos, elapsedSec);
    }

    static void validateOrbit(OrbitElements elements) {
        if (elements == null) {
            throw new IllegalArgumentException("卫星六根数不能为空。");
        }
        validatePositive(elements.semiMajorAxisM, "半长轴");
        if (elements.eccentricity < 0.0 || elements.eccentricity >= 1.0) {
            throw new IllegalArgumentException("偏心率需要满足 0 <= e < 1。");
        }
        double perigeeRadius = elements.semiMajorAxisM * (1.0 - elements.eccentricity);
        if (perigeeRadius <= EARTH_MEAN_RADIUS_M) {
            throw new IllegalArgumentException("近地点半径不能落入地球内部，请增大半长轴或减小偏心率。");
        }
    }

    static void validatePositive(double value, String name) {
        if (value <= 0.0) {
            throw new IllegalArgumentException(name + "必须大于0。");
        }
    }

    static double radians(double degrees) {
        return Math.toRadians(degrees);
    }

    static double degrees(double radians) {
        return Math.toDegrees(radians);
    }

    private static double normalizeRadians(double value) {
        double result = value % (2.0 * Math.PI);
        return result < 0.0 ? result + 2.0 * Math.PI : result;
    }

}
