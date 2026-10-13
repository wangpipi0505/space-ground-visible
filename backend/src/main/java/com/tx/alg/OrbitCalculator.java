package com.tx.alg;

import com.runisys.orbit.spacevisibility.domain.time.LegacyJ2000Time;
import com.runisys.orbit.spacevisibility.domain.geometry.LegacyJ2000Frames;

import com.runisys.orbit.spacevisibility.domain.orbit.BallisticOrbitPropagation;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitDefinition;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitTime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * 原弹道算法兼容边界：完整保留公有方法及嵌套类型规范名、文件和结果格式。
 * 卫星传播统一委托 domain/orbit；其余可见性、文件解析和坐标算法在后续事项逐步分层。
 * referenceEpochSec 与 utcOffsetSec 仍是原公有可变量；旧相对入口由调用方串行维护全局历元。
 * 新领域工具不读取这些状态；Web请求状态隔离由后续事项完成。
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
    /** 稳定可见弧段至少需要两个采样点，避免单点抖动形成伪窗口。 */
    private static final int MIN_STABLE_VISIBLE_POINT_COUNT = 2;
    /** 稳定可见弧段最小时长，单位秒；保持很小的默认值，避免改变正常短窗口。 */
    private static final double MIN_STABLE_VISIBLE_DURATION_SEC = 1e-3;
    /** 进入视场的角度容差，单位度；只用于抵消浮点边界误差。 */
    private static final double VISIBILITY_ENTER_TOLERANCE_DEG = 1e-9;
    /** 已进入弧段后的退出滞回容差，单位度；用于减少视场边界附近的碎片弧段。 */
    private static final double VISIBILITY_EXIT_HYSTERESIS_DEG = 1e-6;
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

    private static final class BallisticParseState {
        boolean epochParsed;
        double referenceEpochSec = Double.NaN;
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

        public double dot(Vector3 other) {
            return x * other.x + y * other.y + z * other.z;
        }

        public double norm() {
            return Math.sqrt(dot(this));
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

    public static final class VisibilityWindow {
        public final double startRelSec;
        public final double endRelSec;

        public VisibilityWindow(double startRelSec, double endRelSec) {
            this.startRelSec = startRelSec;
            this.endRelSec = endRelSec;
        }

        public double durationSec() {
            return Math.max(0.0, endRelSec - startRelSec + 1.0);
        }
    }

    public static final class SatelliteVisibilityWindow {
        public final String satelliteName;
        public final double startRelSec;
        public final double endRelSec;

        public SatelliteVisibilityWindow(String satelliteName, double startRelSec, double endRelSec) {
            this.satelliteName = satelliteName;
            this.startRelSec = startRelSec;
            this.endRelSec = endRelSec;
        }

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

    private static final class PreparedBallisticTarget {
        final BallisticTargetInput input;
        final List<BallisticPoint> points;
        final double sourceReferenceEpochSec;
        final double baseLaunchJ2000Sec;
        final String baseLaunchUtc;

        PreparedBallisticTarget(BallisticTargetInput input,
                                List<BallisticPoint> points,
                                double sourceReferenceEpochSec,
                                double baseLaunchJ2000Sec,
                                String baseLaunchUtc) {
            this.input = input;
            this.points = points;
            this.sourceReferenceEpochSec = sourceReferenceEpochSec;
            this.baseLaunchJ2000Sec = baseLaunchJ2000Sec;
            this.baseLaunchUtc = baseLaunchUtc;
        }
    }

    private static final class IndexedLaunchOffsetCalc {
        final int targetIndex;
        final int offsetIndex;
        final LaunchOffsetCalcResult calc;

        IndexedLaunchOffsetCalc(int targetIndex, int offsetIndex, LaunchOffsetCalcResult calc) {
            this.targetIndex = targetIndex;
            this.offsetIndex = offsetIndex;
            this.calc = calc;
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

        public double totalObservableDurationSec() {
            long total = 0L;
            double totalSec = 0.0;
            for (SatelliteVisibilityResult svr : visibleSatellites) {
                total += arcPointCount(svr);
                totalSec += arcDurationSec(svr);
            }
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
            StringBuilder sb = new StringBuilder();
            sb.append("  {\n");
            sb.append("    \"offsetSecond\": ").append(offsetSecond).append(",\n");
            sb.append("    \"launchTimeUtc\": \"").append(jsonEscape(launchTimeUtc)).append("\",\n");
            sb.append("    \"launchTimeJ2000Sec\": ").append(formatJsonNumber(launchTimeJ2000Sec)).append(",\n");
            sb.append("    \"payloadType\": \"").append(jsonEscape(outputPayloadType())).append("\",\n");
            sb.append("    \"visibilityConstraint\": ");
            appendVisibilityConstraintJson(sb, outputVisibilityConstraint());
            sb.append(",\n");
            sb.append("    \"finalVisibleWindow\": ");
            appendTimeWindowJson(sb, finalVisibleWindow());
            sb.append(",\n");
            sb.append("    \"visibleArcCount\": ").append(visibleSatellites.size()).append(",\n");
            sb.append("    \"visiblePointCount\": ").append(totalObservablePointCount()).append(",\n");
            sb.append("    \"visibleDurationSec\": ").append(formatJsonNumber(totalObservableDurationSec())).append(",\n");
            sb.append("    \"targets\": [\n");
            List<TargetVisibilitySummary> outputTargets = outputTargets();
            for (int ti = 0; ti < outputTargets.size(); ti++) {
                if (ti > 0) sb.append(",\n");
                appendTargetJson(sb, outputTargets.get(ti), "      ");
            }
            sb.append("\n    ],\n");
            sb.append("    \"visibleSatellites\": [\n");
            List<List<SatelliteVisibilityResult>> satelliteGroups = satelliteArcGroups();
            for (int si = 0; si < satelliteGroups.size(); si++) {
                if (si > 0) sb.append(",\n");
                List<SatelliteVisibilityResult> group = satelliteGroups.get(si);
                SatelliteVisibilityResult firstArc = group.get(0);
                sb.append("      {\n");
                sb.append("        \"satelliteId\": \"").append(jsonEscape(firstArc.satelliteId)).append("\",\n");
                sb.append("        \"satelliteName\": \"").append(jsonEscape(firstArc.satelliteName)).append("\",\n");
                sb.append("        \"satelliteIndex\": ").append(firstArc.satelliteIndex).append(",\n");
                sb.append("        \"finalVisibleWindow\": ");
                appendTimeWindowJson(sb, OrbitCalculator.finalVisibleWindow(group));
                sb.append(",\n");
                sb.append("        \"payloadResults\": [\n");
                List<List<SatelliteVisibilityResult>> payloadGroups = satellitePayloadArcGroups(group);
                for (int pi = 0; pi < payloadGroups.size(); pi++) {
                    if (pi > 0) sb.append(",\n");
                    appendSatellitePayloadJson(sb, payloadGroups.get(pi), "          ");
                }
                sb.append("\n        ]\n");
                sb.append("      }");
            }
            sb.append("\n    ]\n");
            sb.append("  }");
            return sb.toString();
        }

        public TimeWindowSummary finalVisibleWindow() {
            return OrbitCalculator.finalVisibleWindow(visibleSatellites);
        }

        private String outputPayloadType() {
            if (!isBlank(payloadType)) {
                return payloadType;
            }
            if (visibilityConstraint != null && !isBlank(visibilityConstraint.payloadType)) {
                return visibilityConstraint.payloadType;
            }
            return PayloadType.GROUND_SURVEILLANCE.displayName();
        }

        private VisibilityConstraintSummary outputVisibilityConstraint() {
            if (visibilityConstraint != null) {
                return visibilityConstraint;
            }
            return VisibilityConstraintSummary.fromConfig(SensorVisibilityConfig.forPayload(outputPayloadType(), 0.0,
                    DEFAULT_SUN_EXCLUSION_ANGLE_DEG));
        }

        private String outputPayloadType(SatelliteVisibilityResult arc) {
            if (arc != null && !isBlank(arc.payloadType)) {
                return arc.payloadType;
            }
            return outputPayloadType();
        }

        private List<TargetVisibilitySummary> outputTargets() {
            if (!targets.isEmpty()) {
                return targets;
            }
            List<TargetVisibilitySummary> result = new ArrayList<TargetVisibilitySummary>();
            result.add(TargetVisibilitySummary.fromCalc(this));
            return result;
        }

        private List<List<SatelliteVisibilityResult>> satelliteArcGroups() {
            Map<String, List<SatelliteVisibilityResult>> grouped =
                    new LinkedHashMap<String, List<SatelliteVisibilityResult>>();
            for (SatelliteVisibilityResult arc : visibleSatellites) {
                String key = arc.satelliteIndex + "\u0001" + nullToEmpty(arc.satelliteId)
                        + "\u0001" + nullToEmpty(arc.satelliteName);
                List<SatelliteVisibilityResult> arcs = grouped.get(key);
                if (arcs == null) {
                    arcs = new ArrayList<SatelliteVisibilityResult>();
                    grouped.put(key, arcs);
                }
                arcs.add(arc);
            }
            return new ArrayList<List<SatelliteVisibilityResult>>(grouped.values());
        }

        private List<List<SatelliteVisibilityResult>> satelliteTargetArcGroups(List<SatelliteVisibilityResult> group) {
            Map<String, List<SatelliteVisibilityResult>> grouped =
                    new LinkedHashMap<String, List<SatelliteVisibilityResult>>();
            for (SatelliteVisibilityResult arc : group) {
                String key = nullToEmpty(arc.targetId) + "\u0001" + nullToEmpty(arc.targetName);
                List<SatelliteVisibilityResult> arcs = grouped.get(key);
                if (arcs == null) {
                    arcs = new ArrayList<SatelliteVisibilityResult>();
                    grouped.put(key, arcs);
                }
                arcs.add(arc);
            }
            return new ArrayList<List<SatelliteVisibilityResult>>(grouped.values());
        }

        private List<List<SatelliteVisibilityResult>> satellitePayloadArcGroups(List<SatelliteVisibilityResult> group) {
            Map<String, List<SatelliteVisibilityResult>> grouped =
                    new LinkedHashMap<String, List<SatelliteVisibilityResult>>();
            for (SatelliteVisibilityResult arc : group) {
                String key = nullToEmpty(arc.payloadId) + "\u0001"
                        + nullToEmpty(arc.payloadName) + "\u0001"
                        + nullToEmpty(arc.payloadType);
                List<SatelliteVisibilityResult> arcs = grouped.get(key);
                if (arcs == null) {
                    arcs = new ArrayList<SatelliteVisibilityResult>();
                    grouped.put(key, arcs);
                }
                arcs.add(arc);
            }
            return new ArrayList<List<SatelliteVisibilityResult>>(grouped.values());
        }

        /**
         * 序列化为中文字段 JSON（兼容旧版输出格式）。
         */
        String toChineseJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("  {\n");
            sb.append("    \"目标发射时间\": \"").append(launchTimeUtc).append("\",\n");
            sb.append("    \"发射偏移量(s)\": ").append(offsetSecond).append(",\n");
            sb.append("    \"可见卫星\": [\n");
            for (int si = 0; si < visibleSatellites.size(); si++) {
                if (si > 0) sb.append(",\n");
                SatelliteVisibilityResult svr = visibleSatellites.get(si);
                sb.append("      {\n");
                sb.append("        \"卫星代号\": \"").append(jsonEscape(svr.satelliteName)).append("\",\n");
                sb.append("        \"卫星编号\": ").append(svr.satelliteIndex).append(",\n");
                sb.append("        \"目标id\": \"").append(jsonEscape(svr.targetId)).append("\",\n");
                sb.append("        \"可见弧段\": [\n");
                sb.append("          {\n");
                sb.append("            \"弧段序号\": ").append(svr.arcIndex).append(",\n");
                sb.append("            \"轨迹点集合\": [\n");
                for (int pi = 0; pi < svr.trajectoryPoints.size(); pi++) {
                    if (pi > 0) sb.append(",\n");
                    VisibleTrajectoryPoint tp = svr.trajectoryPoints.get(pi);
                    String currentTime = j2000ToUtc(tp.absoluteTimeSec);
                    String sourceTime = j2000ToUtc(sourceReferenceFor(svr, this) + tp.relativeTimeSec);
                    sb.append("              {");
                    sb.append("\"经度\": ").append(tp.longitudeDeg).append(", ");
                    sb.append("\"纬度\": ").append(tp.latitudeDeg).append(", ");
                    sb.append("\"高度\": ").append(tp.heightM).append(", ");
                    sb.append("\"当前轨迹点时间\": \"").append(currentTime).append("\", ");
                    sb.append("\"对应的输入的轨迹文件的轨迹点时间\": \"").append(sourceTime).append("\"");
                    sb.append("}");
                }
                sb.append("\n            ]\n");
                sb.append("          }\n");
                sb.append("        ]\n");
                sb.append("      }");
            }
            sb.append("\n    ]\n");
            sb.append("  }");
            return sb.toString();
        }
    }

    /**
     * V2 完整搜索结果。
     * allResults 长度 = numCalculations，每个元素对应一次偏移量计算；
     * best 是 totalObservableSeconds() 最大的偏移量结果。
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
            StringBuilder sb = new StringBuilder("{\n");
            sb.append("  \"referenceUtc\": \"").append(jsonEscape(referenceUtc)).append("\",\n");
            sb.append("  \"payloadType\": \"").append(jsonEscape(outputPayloadType())).append("\",\n");
            sb.append("  \"visibilityConstraint\": ");
            appendVisibilityConstraintJson(sb, outputVisibilityConstraint());
            sb.append(",\n");
            if (includeBest) {
                if (best == null) {
                    sb.append("  \"best\": null,\n");
                } else {
                    sb.append("  \"best\":\n");
                    sb.append(best.toJson()).append(",\n");
                }
            }
            sb.append("  \"allResults\": [\n");
            for (int ci = 0; ci < allResults.size(); ci++) {
                if (ci > 0) sb.append(",\n");
                sb.append(allResults.get(ci).toJson());
            }
            sb.append("\n  ]\n");
            sb.append("}\n");
            return sb.toString();
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
            writer.write("{\n");
            writer.write("  \"referenceUtc\": \"");
            writer.write(jsonEscape(referenceUtc));
            writer.write("\",\n");
            writer.write("  \"payloadType\": \"");
            writer.write(jsonEscape(outputPayloadType()));
            writer.write("\",\n");
            writer.write("  \"visibilityConstraint\": ");
            StringBuilder constraint = new StringBuilder();
            appendVisibilityConstraintJson(constraint, outputVisibilityConstraint());
            writer.write(constraint.toString());
            writer.write(",\n");
            if (includeBest) {
                if (best == null) {
                    writer.write("  \"best\": null,\n");
                } else {
                    writer.write("  \"best\":\n");
                    writer.write(best.toJson());
                    writer.write(",\n");
                }
            }
            writer.write("  \"allResults\": [\n");
            for (int ci = 0; ci < allResults.size(); ci++) {
                if (ci > 0) {
                    writer.write(",\n");
                }
                writer.write(allResults.get(ci).toJson());
            }
            writer.write("\n  ]\n");
            writer.write("}\n");
        }

        private String outputPayloadType() {
            VisibilityConstraintSummary summary = outputVisibilityConstraint();
            return summary == null || isBlank(summary.payloadType)
                    ? PayloadType.GROUND_SURVEILLANCE.displayName()
                    : summary.payloadType;
        }

        private VisibilityConstraintSummary outputVisibilityConstraint() {
            if (visibilityConstraint != null) {
                return visibilityConstraint;
            }
            return firstVisibilityConstraint(allResults, best);
        }

        public String toChineseText() {
            if (best == null) return "未找到有效结果。\n";
            StringBuilder sb = new StringBuilder();
            sb.append("候选偏移量数量：").append(allResults.size()).append('\n');
            sb.append("第1目标CSV参考时间：").append(referenceUtc).append('\n');
            sb.append("最优偏移量：").append(signedSeconds(best.offsetSecond)).append('\n');
            if (best.targets.isEmpty()) {
                sb.append("第1目标调整后发射时间：").append(best.launchTimeUtc).append('\n');
            } else {
                sb.append("各目标基准发射时间与调整后发射时间：\n");
                for (TargetVisibilitySummary target : best.targets) {
                    sb.append("  - ")
                            .append(target.targetId == null || target.targetId.trim().isEmpty()
                                    ? "UNKNOWN" : target.targetId)
                            .append("：基准发射UTC=")
                            .append(target.baseLaunchUTC == null || target.baseLaunchUTC.trim().isEmpty()
                                    ? "未填写，使用CSV参考时间" : target.baseLaunchUTC)
                            .append("，调整后发射UTC=")
                            .append(target.launchUTC == null ? "" : target.launchUTC)
                            .append('\n');
                }
            }
            sb.append("最大可见导弹时长(逐星累计)：")
                    .append(String.format(Locale.CHINA, "%.3f", best.totalObservableDurationSec()))
                    .append(" s\n");
            sb.append("可见轨迹点数量：").append(best.totalObservablePointCount()).append('\n');
            sb.append("可见弧段数量：").append(best.visibleSatellites.size()).append('\n');
            return sb.toString();
        }
    }

    private static VisibilityConstraintSummary firstVisibilityConstraint(List<LaunchOffsetCalcResult> allResults,
                                                                         LaunchOffsetCalcResult best) {
        if (best != null && best.visibilityConstraint != null) {
            return best.visibilityConstraint;
        }
        if (allResults != null) {
            for (LaunchOffsetCalcResult result : allResults) {
                if (result != null && result.visibilityConstraint != null) {
                    return result.visibilityConstraint;
                }
            }
        }
        return VisibilityConstraintSummary.fromConfig(SensorVisibilityConfig.forPayload(
                PayloadType.GROUND_SURVEILLANCE));
    }

    private static void appendVisibilityConstraintJson(StringBuilder sb, VisibilityConstraintSummary summary) {
        VisibilityConstraintSummary active = summary == null
                ? VisibilityConstraintSummary.fromConfig(SensorVisibilityConfig.forPayload(PayloadType.GROUND_SURVEILLANCE))
                : summary;
        sb.append("{");
        sb.append("\"payloadType\": \"").append(jsonEscape(active.payloadType)).append("\", ");
        sb.append("\"maxDetectRangeKm\": ").append(formatJsonNumber(active.maxDetectRangeKm)).append(", ");
        sb.append("\"sunExclusionAngleDeg\": ").append(formatJsonNumber(active.sunExclusionAngleDeg)).append(", ");
        sb.append("\"simpleConeHalfAngleDeg\": ").append(formatJsonNumber(active.simpleConeHalfAngleDeg)).append(", ");
        sb.append("\"halfConeAngleDeg\": ").append(formatJsonNumber(active.halfConeAngleDeg)).append(", ");
        sb.append("\"innerConeAngleDeg\": ").append(formatJsonNumber(active.innerConeAngleDeg)).append(", ");
        sb.append("\"outerConeAngleDeg\": ").append(formatJsonNumber(active.outerConeAngleDeg));
        sb.append("}");
    }

    private static void appendTimeWindowJson(StringBuilder sb, TimeWindowSummary window) {
        if (window == null) {
            sb.append("null");
            return;
        }
        sb.append("{");
        sb.append("\"startTime\": \"").append(jsonEscape(window.startUtc)).append("\", ");
        sb.append("\"endTime\": \"").append(jsonEscape(window.endUtc)).append("\", ");
        sb.append("\"startJ2000Sec\": ").append(formatJsonNumber(window.startJ2000Sec)).append(", ");
        sb.append("\"endJ2000Sec\": ").append(formatJsonNumber(window.endJ2000Sec)).append(", ");
        sb.append("\"durationSeconds\": ").append(formatJsonNumber(window.durationSeconds));
        sb.append("}");
    }

    private static void appendTargetJson(StringBuilder sb, TargetVisibilitySummary target, String indent) {
        sb.append(indent).append("{\n");
        sb.append(indent).append("  \"targetId\": \"").append(jsonEscape(target.targetId)).append("\",\n");
        sb.append(indent).append("  \"targetName\": \"").append(jsonEscape(target.targetName)).append("\",\n");
        sb.append(indent).append("  \"csvPath\": \"").append(jsonEscape(target.csvPath)).append("\",\n");
        sb.append(indent).append("  \"baseLaunchUTC\": \"").append(jsonEscape(target.baseLaunchUTC)).append("\",\n");
        sb.append(indent).append("  \"launchUTC\": \"").append(jsonEscape(target.launchUTC)).append("\",\n");
        sb.append(indent).append("  \"payloadType\": \"").append(jsonEscape(target.payloadType)).append("\",\n");
        sb.append(indent).append("  \"launchTimeJ2000Sec\": ").append(formatJsonNumber(target.launchTimeJ2000Sec)).append(",\n");
        sb.append(indent).append("  \"finalVisibleWindow\": ");
        appendTimeWindowJson(sb, finalVisibleWindowFromWindows(target.visibleWindows));
        sb.append(",\n");
        sb.append(indent).append("  \"visibleWindows\": [\n");
        for (int i = 0; i < target.visibleWindows.size(); i++) {
            if (i > 0) sb.append(",\n");
            appendWindowSummaryJson(sb, target.visibleWindows.get(i), indent + "    ");
        }
        sb.append("\n").append(indent).append("  ]\n");
        sb.append(indent).append("}");
    }

    private static void appendWindowSummaryJson(StringBuilder sb, VisibleWindowSummary window, String indent) {
        sb.append(indent).append("{");
        sb.append("\"satelliteId\": \"").append(jsonEscape(window.satelliteId)).append("\", ");
        sb.append("\"satelliteName\": \"").append(jsonEscape(window.satelliteName)).append("\", ");
        sb.append("\"satelliteIndex\": ").append(window.satelliteIndex).append(", ");
        sb.append("\"arcIndex\": ").append(window.arcIndex).append(", ");
        sb.append("\"payloadId\": \"").append(jsonEscape(window.payloadId)).append("\", ");
        sb.append("\"payloadName\": \"").append(jsonEscape(window.payloadName)).append("\", ");
        sb.append("\"payloadType\": \"").append(jsonEscape(window.payloadType)).append("\", ");
        sb.append("\"startTime\": \"").append(jsonEscape(window.startUtc)).append("\", ");
        sb.append("\"endTime\": \"").append(jsonEscape(window.endUtc)).append("\", ");
        sb.append("\"startJ2000Sec\": ").append(formatJsonNumber(window.startJ2000Sec)).append(", ");
        sb.append("\"endJ2000Sec\": ").append(formatJsonNumber(window.endJ2000Sec)).append(", ");
        sb.append("\"durationSeconds\": ").append(formatJsonNumber(window.durationSeconds)).append(", ");
        sb.append("\"pointCount\": ").append(window.pointCount);
        sb.append("}");
    }

    /** 按载荷输出同一卫星平台下的全部目标可见弧段。 */
    private static void appendSatellitePayloadJson(StringBuilder sb,
                                                   List<SatelliteVisibilityResult> arcs,
                                                   String indent) {
        SatelliteVisibilityResult first = arcs.get(0);
        sb.append(indent).append("{\n");
        sb.append(indent).append("  \"payloadId\": \"").append(jsonEscape(first.payloadId)).append("\",\n");
        sb.append(indent).append("  \"payloadName\": \"").append(jsonEscape(first.payloadName)).append("\",\n");
        sb.append(indent).append("  \"payloadType\": \"").append(jsonEscape(first.payloadType)).append("\",\n");
        sb.append(indent).append("  \"visibilityConstraint\": ");
        appendVisibilityConstraintJson(sb, first.visibilityConstraint);
        sb.append(",\n");
        sb.append(indent).append("  \"finalVisibleWindow\": ");
        appendTimeWindowJson(sb, finalVisibleWindow(arcs));
        sb.append(",\n");
        sb.append(indent).append("  \"targets\": [\n");
        List<List<SatelliteVisibilityResult>> targetGroups = groupArcsByTarget(arcs);
        for (int ti = 0; ti < targetGroups.size(); ti++) {
            if (ti > 0) sb.append(",\n");
            appendSatelliteTargetJson(sb, targetGroups.get(ti), indent + "    ");
        }
        sb.append("\n").append(indent).append("  ]\n");
        sb.append(indent).append("}");
    }

    private static List<List<SatelliteVisibilityResult>> groupArcsByTarget(
            List<SatelliteVisibilityResult> arcs) {
        Map<String, List<SatelliteVisibilityResult>> grouped =
                new LinkedHashMap<String, List<SatelliteVisibilityResult>>();
        for (SatelliteVisibilityResult arc : arcs) {
            String key = nullToEmpty(arc.targetId) + "\u0001" + nullToEmpty(arc.targetName);
            List<SatelliteVisibilityResult> targetArcs = grouped.get(key);
            if (targetArcs == null) {
                targetArcs = new ArrayList<SatelliteVisibilityResult>();
                grouped.put(key, targetArcs);
            }
            targetArcs.add(arc);
        }
        return new ArrayList<List<SatelliteVisibilityResult>>(grouped.values());
    }

    private static void appendSatelliteTargetJson(StringBuilder sb,
                                                  List<SatelliteVisibilityResult> arcs,
                                                  String indent) {
        SatelliteVisibilityResult first = arcs.get(0);
        sb.append(indent).append("{\n");
        sb.append(indent).append("  \"targetId\": \"").append(jsonEscape(first.targetId)).append("\",\n");
        sb.append(indent).append("  \"targetName\": \"").append(jsonEscape(first.targetName)).append("\",\n");
        sb.append(indent).append("  \"payloadId\": \"").append(jsonEscape(first.payloadId)).append("\",\n");
        sb.append(indent).append("  \"payloadName\": \"").append(jsonEscape(first.payloadName)).append("\",\n");
        sb.append(indent).append("  \"payloadType\": \"").append(jsonEscape(first.payloadType)).append("\",\n");
        sb.append(indent).append("  \"visibleArcs\": [\n");
        for (int ai = 0; ai < arcs.size(); ai++) {
            if (ai > 0) sb.append(",\n");
            appendVisibleArcJson(sb, arcs.get(ai), indent + "    ");
        }
        sb.append("\n").append(indent).append("  ]\n");
        sb.append(indent).append("}");
    }

    private static void appendVisibleArcJson(StringBuilder sb,
                                             SatelliteVisibilityResult arc,
                                             String indent) {
        VisibleWindowSummary summary = VisibleWindowSummary.fromArc(arc);
        sb.append(indent).append("{\n");
        sb.append(indent).append("  \"arcIndex\": ").append(arc.arcIndex).append(",\n");
        sb.append(indent).append("  \"payloadId\": \"").append(jsonEscape(arc.payloadId)).append("\",\n");
        sb.append(indent).append("  \"payloadName\": \"").append(jsonEscape(arc.payloadName)).append("\",\n");
        sb.append(indent).append("  \"payloadType\": \"").append(jsonEscape(arc.payloadType)).append("\",\n");
        sb.append(indent).append("  \"startTime\": \"").append(jsonEscape(summary.startUtc)).append("\",\n");
        sb.append(indent).append("  \"endTime\": \"").append(jsonEscape(summary.endUtc)).append("\",\n");
        sb.append(indent).append("  \"startJ2000Sec\": ").append(formatJsonNumber(summary.startJ2000Sec)).append(",\n");
        sb.append(indent).append("  \"endJ2000Sec\": ").append(formatJsonNumber(summary.endJ2000Sec)).append(",\n");
        sb.append(indent).append("  \"durationSeconds\": ").append(formatJsonNumber(summary.durationSeconds)).append(",\n");
        sb.append(indent).append("  \"pointCount\": ").append(summary.pointCount).append(",\n");
        sb.append(indent).append("  \"trajectoryPoints\": [\n");
        for (int pi = 0; pi < arc.trajectoryPoints.size(); pi++) {
            if (pi > 0) sb.append(",\n");
            appendTrajectoryPointJson(sb, arc.trajectoryPoints.get(pi), arc, indent + "    ");
        }
        sb.append("\n").append(indent).append("  ]\n");
        sb.append(indent).append("}");
    }

    private static void appendTrajectoryPointJson(StringBuilder sb,
                                                  VisibleTrajectoryPoint point,
                                                  SatelliteVisibilityResult arc,
                                                  String indent) {
        sb.append(indent).append("{");
        sb.append("\"longitudeDeg\": ").append(formatJsonNumber(point.longitudeDeg)).append(", ");
        sb.append("\"latitudeDeg\": ").append(formatJsonNumber(point.latitudeDeg)).append(", ");
        sb.append("\"heightM\": ").append(formatJsonNumber(point.heightM)).append(", ");
        sb.append("\"absoluteTimeJ2000Sec\": ").append(formatJsonNumber(point.absoluteTimeSec)).append(", ");
        sb.append("\"absoluteTimeUtc\": \"").append(j2000ToUtcMillis(point.absoluteTimeSec)).append("\", ");
        sb.append("\"relativeTimeSec\": ").append(formatJsonNumber(point.relativeTimeSec)).append(", ");
        sb.append("\"sourceTrajectoryUtc\": \"")
                .append(j2000ToUtcMillis(sourceReferenceFor(arc) + point.relativeTimeSec))
                .append("\"");
        sb.append("}");
    }

    private static TimeWindowSummary finalVisibleWindow(List<SatelliteVisibilityResult> arcs) {
        double start = Double.POSITIVE_INFINITY;
        double end = Double.NEGATIVE_INFINITY;
        for (SatelliteVisibilityResult arc : arcs) {
            if (!hasVisiblePoints(arc)) {
                continue;
            }
            start = Math.min(start, arcStartJ2000Sec(arc));
            end = Math.max(end, arcEndJ2000Sec(arc));
        }
        if (Double.isInfinite(start) || Double.isInfinite(end)) {
            return null;
        }
        return new TimeWindowSummary(start, end);
    }

    private static TimeWindowSummary finalVisibleWindowFromWindows(List<VisibleWindowSummary> windows) {
        double start = Double.POSITIVE_INFINITY;
        double end = Double.NEGATIVE_INFINITY;
        for (VisibleWindowSummary window : windows) {
            if (window.pointCount <= 0) {
                continue;
            }
            start = Math.min(start, window.startJ2000Sec);
            end = Math.max(end, window.endJ2000Sec);
        }
        if (Double.isInfinite(start) || Double.isInfinite(end)) {
            return null;
        }
        return new TimeWindowSummary(start, end);
    }

    private static double sourceReferenceFor(SatelliteVisibilityResult arc) {
        if (arc != null && !Double.isNaN(arc.sourceReferenceEpochSec)) {
            return arc.sourceReferenceEpochSec;
        }
        return referenceEpochSec;
    }

    private static double sourceReferenceFor(SatelliteVisibilityResult arc, LaunchOffsetCalcResult calc) {
        if (arc != null && !Double.isNaN(arc.sourceReferenceEpochSec)) {
            return arc.sourceReferenceEpochSec;
        }
        if (calc != null && !Double.isNaN(calc.sourceReferenceEpochSec)) {
            return calc.sourceReferenceEpochSec;
        }
        return referenceEpochSec;
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static boolean sameText(String a, String b) {
        return nullToEmpty(a).equals(nullToEmpty(b));
    }

    private static boolean hasVisiblePoints(SatelliteVisibilityResult arc) {
        return arcPointCount(arc) > 0;
    }

    static List<SatelliteVisibilityResult> stableVisibleArcs(List<SatelliteVisibilityResult> arcs,
                                                             int minPointCount,
                                                             double minDurationSec) {
        List<SatelliteVisibilityResult> stable = new ArrayList<SatelliteVisibilityResult>();
        if (arcs == null || arcs.isEmpty()) {
            return stable;
        }
        int requiredPoints = Math.max(1, minPointCount);
        double requiredDuration = Math.max(0.0, minDurationSec);
        for (SatelliteVisibilityResult arc : arcs) {
            if (arc == null) {
                continue;
            }
            int pointCount = arcPointCount(arc);
            double durationSec = arcDurationSec(arc);
            // 单点或极短弧段通常来自视场边界附近的采样抖动，默认不作为稳定窗口输出。
            if (pointCount >= requiredPoints && durationSec + 1e-9 >= requiredDuration) {
                stable.add(arc);
            }
        }
        return stable;
    }

    private static int arcPointCount(SatelliteVisibilityResult arc) {
        if (arc == null) {
            return 0;
        }
        if (arc.visiblePointCount > 0) {
            return arc.visiblePointCount;
        }
        return arc.trajectoryPoints.size();
    }

    private static double arcStartJ2000Sec(SatelliteVisibilityResult arc) {
        if (arc == null) {
            return Double.NaN;
        }
        if (!Double.isNaN(arc.startJ2000Sec)) {
            return arc.startJ2000Sec;
        }
        return arc.trajectoryPoints.isEmpty() ? Double.NaN : arc.trajectoryPoints.get(0).absoluteTimeSec;
    }

    private static double arcEndJ2000Sec(SatelliteVisibilityResult arc) {
        if (arc == null) {
            return Double.NaN;
        }
        if (!Double.isNaN(arc.endJ2000Sec)) {
            return arc.endJ2000Sec;
        }
        return arc.trajectoryPoints.isEmpty() ? Double.NaN
                : arc.trajectoryPoints.get(arc.trajectoryPoints.size() - 1).absoluteTimeSec;
    }

    private static double arcStartRelativeTimeSec(SatelliteVisibilityResult arc) {
        if (arc == null) {
            return Double.NaN;
        }
        if (!Double.isNaN(arc.startRelativeTimeSec)) {
            return arc.startRelativeTimeSec;
        }
        return arc.trajectoryPoints.isEmpty() ? Double.NaN : arc.trajectoryPoints.get(0).relativeTimeSec;
    }

    private static double arcEndRelativeTimeSec(SatelliteVisibilityResult arc) {
        if (arc == null) {
            return Double.NaN;
        }
        if (!Double.isNaN(arc.endRelativeTimeSec)) {
            return arc.endRelativeTimeSec;
        }
        return arc.trajectoryPoints.isEmpty() ? Double.NaN
                : arc.trajectoryPoints.get(arc.trajectoryPoints.size() - 1).relativeTimeSec;
    }

    private static double arcDurationSec(SatelliteVisibilityResult arc) {
        if (!hasVisiblePoints(arc)) {
            return 0.0;
        }
        double start = arcStartRelativeTimeSec(arc);
        double end = arcEndRelativeTimeSec(arc);
        if (Double.isNaN(start) || Double.isNaN(end)) {
            return arcDurationSec(arc.trajectoryPoints);
        }
        return Math.max(0.0, end - start);
    }

    private static double arcDurationSec(List<VisibleTrajectoryPoint> points) {
        if (points == null || points.isEmpty()) {
            return 0.0;
        }
        if (points.size() == 1) {
            return 0.0;
        }
        return Math.max(0.0,
                points.get(points.size() - 1).relativeTimeSec - points.get(0).relativeTimeSec);
    }

    private static void recordVisiblePoint(SatelliteVisibilityResult arc,
                                           double longitudeDeg,
                                           double latitudeDeg,
                                           double heightM,
                                           double absoluteTimeSec,
                                           double relativeTimeSec,
                                           boolean includeTrajectoryPoints) {
        if (arc.visiblePointCount == 0) {
            arc.startJ2000Sec = absoluteTimeSec;
            arc.startRelativeTimeSec = relativeTimeSec;
        }
        arc.endJ2000Sec = absoluteTimeSec;
        arc.endRelativeTimeSec = relativeTimeSec;
        arc.visiblePointCount++;
        if (includeTrajectoryPoints) {
            arc.trajectoryPoints.add(new VisibleTrajectoryPoint(
                    longitudeDeg, latitudeDeg, heightM, absoluteTimeSec, relativeTimeSec));
        }
    }

    private static boolean includeTrajectoryPoints(int trajectoryPointOutput) {
        if (trajectoryPointOutput == 0) {
            return false;
        }
        if (trajectoryPointOutput == 1) {
            return true;
        }
        throw new IllegalArgumentException("trajectoryPointOutput只能为0或1。");
    }

    private interface SatellitePositionProvider {
        Vector3 position(int satelliteIndex, OrbitElements elements, double absoluteJ2000Sec, OrbitModel orbitModel);
    }

    private static final class CachedSatellitePositionProvider implements SatellitePositionProvider {
        private final List<ConcurrentHashMap<Long, Vector3>> caches;

        CachedSatellitePositionProvider(int satelliteCount) {
            caches = new ArrayList<ConcurrentHashMap<Long, Vector3>>();
            for (int i = 0; i < satelliteCount; i++) {
                caches.add(new ConcurrentHashMap<Long, Vector3>());
            }
        }

        @Override
        public Vector3 position(int satelliteIndex, OrbitElements elements, double absoluteJ2000Sec, OrbitModel orbitModel) {
            ConcurrentHashMap<Long, Vector3> cache = caches.get(satelliteIndex);
            Long key = Long.valueOf(Double.doubleToLongBits(absoluteJ2000Sec));
            Vector3 cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
            Vector3 computed = propagateAtAbsolute(elements, absoluteJ2000Sec, orbitModel);
            Vector3 previous = cache.putIfAbsent(key, computed);
            return previous == null ? computed : previous;
        }
    }

    // ========== V2 可见性计算方法 ==========

    /**
     * V2 可见性搜索主入口。
     * 从 baseLaunchJ2000Sec 出发，以 stepSizeSec 为步长进行 numCalculations 次偏移量扫描。
     * 每次偏移量调用 analyzeLaunchOffsetV2 计算逐卫星可见弧段；
     * 最终以 totalObservableSeconds（所有卫星可见轨迹点总数）为指标选出最优偏移量。
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
    public static LaunchSearchResultV2 searchLaunchOffsetsV2(
            double baseLaunchJ2000Sec,
            long stepSizeSec,
            long numCalculations,
            List<OrbitElements> satellites,
            List<BallisticPoint> ballisticPoints,
            double sensorFovDeg,
            OrbitModel orbitModel,
            ProgressCallback progressCallback) {
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

    public static LaunchSearchResultV2 searchLaunchOffsetsV2(
            double baseLaunchJ2000Sec,
            long startOffsetSec,
            long stepSizeSec,
            long numCalculations,
            List<OrbitElements> satellites,
            List<BallisticPoint> ballisticPoints,
            double sensorFovDeg,
            OrbitModel orbitModel,
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

    public static LaunchSearchResultV2 searchLaunchOffsetsV2(
            double baseLaunchJ2000Sec,
            long startOffsetSec,
            long stepSizeSec,
            long numCalculations,
            List<OrbitElements> satellites,
            List<BallisticPoint> ballisticPoints,
            double sensorFovDeg,
            OrbitModel orbitModel,
            double targetSampleStepSec,
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
                1,
                progressCallback);
    }

    public static LaunchSearchResultV2 searchLaunchOffsetsV2(
            double baseLaunchJ2000Sec,
            long startOffsetSec,
            long stepSizeSec,
            long numCalculations,
            List<OrbitElements> satellites,
            List<BallisticPoint> ballisticPoints,
            double sensorFovDeg,
            OrbitModel orbitModel,
            double targetSampleStepSec,
            int trajectoryPointOutput,
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
                referenceEpochSec,
                trajectoryPointOutput,
                progressCallback);
    }

    public static LaunchSearchResultV2 searchLaunchOffsetsV2(
            double baseLaunchJ2000Sec,
            long startOffsetSec,
            long stepSizeSec,
            long numCalculations,
            List<OrbitElements> satellites,
            List<BallisticPoint> ballisticPoints,
            double sensorFovDeg,
            OrbitModel orbitModel,
            double targetSampleStepSec,
            double sourceReferenceEpochSec,
            int trajectoryPointOutput,
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

    public static LaunchSearchResultV2 searchLaunchOffsetsV2(
            double baseLaunchJ2000Sec,
            long startOffsetSec,
            long stepSizeSec,
            long numCalculations,
            List<OrbitElements> satellites,
            List<BallisticPoint> ballisticPoints,
            double sensorFovDeg,
            OrbitModel orbitModel,
            double targetSampleStepSec,
            double sourceReferenceEpochSec,
            int trajectoryPointOutput,
            SensorVisibilityConfig sensorConfig,
            ProgressCallback progressCallback) {
        validateSearchInputsV2(stepSizeSec, numCalculations, satellites, ballisticPoints, sensorFovDeg);
        return searchLaunchOffsetsV2Internal(
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
                normalizeSensorConfig(sensorConfig, sensorFovDeg),
                includeTrajectoryPoints(trajectoryPointOutput),
                new CachedSatellitePositionProvider(satellites.size()),
                progressCallback);
    }

    private static LaunchSearchResultV2 searchLaunchOffsetsV2Internal(
            double baseLaunchJ2000Sec,
            long startOffsetSec,
            long stepSizeSec,
            long numCalculations,
            List<OrbitElements> satellites,
            List<BallisticPoint> ballisticPoints,
            double sensorFovDeg,
            OrbitModel orbitModel,
            double targetSampleStepSec,
            double sourceReferenceEpochSec,
            SensorVisibilityConfig sensorConfig,
            boolean includeTrajectoryPoints,
            SatellitePositionProvider satellitePositionProvider,
            ProgressCallback progressCallback) {
        OrbitModel activeModel = orbitModel == null ? OrbitModel.TWO_BODY : orbitModel;

        List<BallisticPoint> sorted = sortedBallistic(ballisticPoints);
        List<BallisticPoint> samples = interpolateBallistic(sorted, visibilitySampleStepSec(sorted, targetSampleStepSec));
        int offsetCount = checkedCalculationCount(numCalculations);
        LaunchOffsetCalcResult[] indexedResults = new LaunchOffsetCalcResult[offsetCount];
        LaunchOffsetCalcResult best = null;
        String baseLaunchUtc = j2000ToUtcMillis(baseLaunchJ2000Sec);

        runOffsetSearch(
                -1,
                null,
                baseLaunchJ2000Sec,
                baseLaunchUtc,
                startOffsetSec,
                stepSizeSec,
                offsetCount,
                satellites,
                samples,
                sensorFovDeg,
                activeModel,
                sourceReferenceEpochSec,
                sensorConfig,
                includeTrajectoryPoints,
                satellitePositionProvider,
                indexedResults,
                progressCallback);

        List<LaunchOffsetCalcResult> allResults = new ArrayList<LaunchOffsetCalcResult>(offsetCount);
        for (int i = 0; i < offsetCount; i++) {
            LaunchOffsetCalcResult calc = indexedResults[i];
            allResults.add(calc);
            if (best == null || calc.totalObservableDurationSec() > best.totalObservableDurationSec()) {
                best = calc;
            }
        }
        return new LaunchSearchResultV2(
                allResults,
                best,
                j2000ToUtcMillis(sourceReferenceEpochSec),
                VisibilityConstraintSummary.fromConfig(sensorConfig));
    }

    public static LaunchSearchResultV2 searchLaunchOffsetsV2(
            List<BallisticTargetInput> targets,
            long startOffsetSec,
            long stepSizeSec,
            long numCalculations,
            List<OrbitElements> satellites,
            double sensorFovDeg,
            OrbitModel orbitModel,
            ProgressCallback progressCallback) throws IOException {
        return searchLaunchOffsetsV2(
                targets,
                startOffsetSec,
                stepSizeSec,
                numCalculations,
                satellites,
                sensorFovDeg,
                orbitModel,
                Double.NaN,
                progressCallback);
    }

    public static LaunchSearchResultV2 searchLaunchOffsetsV2(
            List<BallisticTargetInput> targets,
            long startOffsetSec,
            long stepSizeSec,
            long numCalculations,
            List<OrbitElements> satellites,
            double sensorFovDeg,
            OrbitModel orbitModel,
            double targetSampleStepSec,
            ProgressCallback progressCallback) throws IOException {
        return searchLaunchOffsetsV2(
                targets,
                startOffsetSec,
                stepSizeSec,
                numCalculations,
                satellites,
                sensorFovDeg,
                orbitModel,
                targetSampleStepSec,
                1,
                progressCallback);
    }

    public static LaunchSearchResultV2 searchLaunchOffsetsV2(
            List<BallisticTargetInput> targets,
            long startOffsetSec,
            long stepSizeSec,
            long numCalculations,
            List<OrbitElements> satellites,
            double sensorFovDeg,
            OrbitModel orbitModel,
            double targetSampleStepSec,
            int trajectoryPointOutput,
            ProgressCallback progressCallback) throws IOException {
        return searchLaunchOffsetsV2(
                targets,
                startOffsetSec,
                stepSizeSec,
                numCalculations,
                satellites,
                sensorFovDeg,
                orbitModel,
                targetSampleStepSec,
                trajectoryPointOutput,
                SensorVisibilityConfig.legacySimpleCone(sensorFovDeg),
                progressCallback);
    }

    public static LaunchSearchResultV2 searchLaunchOffsetsV2(
            List<BallisticTargetInput> targets,
            long startOffsetSec,
            long stepSizeSec,
            long numCalculations,
            List<OrbitElements> satellites,
            double sensorFovDeg,
            OrbitModel orbitModel,
            double targetSampleStepSec,
            int trajectoryPointOutput,
            SensorVisibilityConfig sensorConfig,
            ProgressCallback progressCallback) throws IOException {
        validateMultiTargetInputs(targets);
        validateSearchInputsV2(stepSizeSec, numCalculations, satellites, java.util.Collections.<BallisticPoint>singletonList(new BallisticPoint()), sensorFovDeg);
        OrbitModel activeModel = orbitModel == null ? OrbitModel.TWO_BODY : orbitModel;
        final SensorVisibilityConfig activeSensorConfig = normalizeSensorConfig(sensorConfig, sensorFovDeg);
        final boolean includeTrajectoryPoints = includeTrajectoryPoints(trajectoryPointOutput);
        final int offsetCount = checkedCalculationCount(numCalculations);

        List<PreparedBallisticTarget> preparedTargets = new ArrayList<PreparedBallisticTarget>();
        String firstReferenceUtc = null;
        for (int targetIndex = 0; targetIndex < targets.size(); targetIndex++) {
            BallisticTargetInput input = normalizedTargetInput(targets.get(targetIndex), targetIndex);
            ParsedBallisticCsv parsed = parseBallisticCsvWithReference(input.csvPath);
            List<BallisticPoint> points = parsed.points;
            double sourceReference = parsed.referenceEpochSec;
            String baseLaunchUtc = isBlank(input.baseLaunchUtc)
                    ? j2000ToUtcMillis(sourceReference)
                    : input.baseLaunchUtc.trim();
            double baseLaunchJ2000 = isBlank(input.baseLaunchUtc)
                    ? sourceReference
                    : utcToJ2000Sec(input.baseLaunchUtc.trim());
            if (firstReferenceUtc == null) {
                firstReferenceUtc = parsed.referenceUtc;
            }
            preparedTargets.add(new PreparedBallisticTarget(input, points, sourceReference, baseLaunchJ2000, baseLaunchUtc));
        }

        final List<List<BallisticPoint>> targetSamples = new ArrayList<List<BallisticPoint>>();
        for (PreparedBallisticTarget target : preparedTargets) {
            List<BallisticPoint> sorted = sortedBallistic(target.points);
            targetSamples.add(interpolateBallistic(sorted, visibilitySampleStepSec(sorted, targetSampleStepSec)));
        }

        final long totalProgress = (long) offsetCount * preparedTargets.size();
        final CachedSatellitePositionProvider sharedProvider = new CachedSatellitePositionProvider(satellites.size());
        int threadCount = recommendedSearchThreadCount(totalProgress);
        final LaunchOffsetCalcResult[][] perTargetResults =
                new LaunchOffsetCalcResult[preparedTargets.size()][offsetCount];

        if (threadCount <= 1) {
            long completed = 0L;
            for (int targetIndex = 0; targetIndex < preparedTargets.size(); targetIndex++) {
                PreparedBallisticTarget target = preparedTargets.get(targetIndex);
                List<BallisticPoint> samples = targetSamples.get(targetIndex);
                for (int offsetIndex = 0; offsetIndex < offsetCount; offsetIndex++) {
                    perTargetResults[targetIndex][offsetIndex] = analyzeOffset(
                            offsetIndex,
                            target.baseLaunchJ2000Sec,
                            target.input.targetId,
                            target.input.targetName,
                            target.input.csvPath,
                            target.baseLaunchUtc,
                            startOffsetSec,
                            stepSizeSec,
                            satellites,
                            samples,
                            sensorFovDeg,
                            activeModel,
                            target.sourceReferenceEpochSec,
                            activeSensorConfig,
                            includeTrajectoryPoints,
                            sharedProvider);
                    completed++;
                    if (progressCallback != null) {
                        progressCallback.onProgress(completed, totalProgress);
                    }
                }
            }
        } else {
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            try {
                CompletionService<IndexedLaunchOffsetCalc> completion =
                        new ExecutorCompletionService<IndexedLaunchOffsetCalc>(executor);
                int submitted = 0;
                for (int targetIndex = 0; targetIndex < preparedTargets.size(); targetIndex++) {
                    final int ti = targetIndex;
                    final PreparedBallisticTarget target = preparedTargets.get(targetIndex);
                    final List<BallisticPoint> samples = targetSamples.get(targetIndex);
                    for (int offsetIndex = 0; offsetIndex < offsetCount; offsetIndex++) {
                        final int oi = offsetIndex;
                        completion.submit(new Callable<IndexedLaunchOffsetCalc>() {
                            @Override
                            public IndexedLaunchOffsetCalc call() {
                                LaunchOffsetCalcResult calc = analyzeOffset(
                                        oi,
                                        target.baseLaunchJ2000Sec,
                                        target.input.targetId,
                                        target.input.targetName,
                                        target.input.csvPath,
                                        target.baseLaunchUtc,
                                        startOffsetSec,
                                        stepSizeSec,
                                        satellites,
                                        samples,
                                        sensorFovDeg,
                                        activeModel,
                                        target.sourceReferenceEpochSec,
                                        activeSensorConfig,
                                        includeTrajectoryPoints,
                                        sharedProvider);
                                return new IndexedLaunchOffsetCalc(ti, oi, calc);
                            }
                        });
                        submitted++;
                    }
                }
                for (int done = 0; done < submitted; done++) {
                    try {
                        IndexedLaunchOffsetCalc indexed = completion.take().get();
                        perTargetResults[indexed.targetIndex][indexed.offsetIndex] = indexed.calc;
                        if (progressCallback != null) {
                            progressCallback.onProgress(done + 1L, totalProgress);
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("多目标可见性计算被中断。", ex);
                    } catch (Exception ex) {
                        throw new IllegalStateException("多目标可见性计算失败：" + ex.getMessage(), ex);
                    }
                }
            } finally {
                executor.shutdownNow();
            }
        }

        List<LaunchOffsetCalcResult> allResults = new ArrayList<LaunchOffsetCalcResult>();
        LaunchOffsetCalcResult best = null;
        for (int offsetIndex = 0; offsetIndex < offsetCount; offsetIndex++) {
            LaunchOffsetCalcResult merged = new LaunchOffsetCalcResult();
            // 多目标合并时外层发射时间保留第一个目标的兼容展示值；
            // 每个目标、每个卫星弧段的真实时间写在弧段自身字段中。
            LaunchOffsetCalcResult first = perTargetResults[0][offsetIndex];
            merged.offsetSecond = first.offsetSecond;
            merged.launchTimeUtc = first.launchTimeUtc;
            merged.launchTimeJ2000Sec = first.launchTimeJ2000Sec;
            merged.sourceReferenceEpochSec = first.sourceReferenceEpochSec;
            merged.payloadType = first.payloadType;
            merged.visibilityConstraint = first.visibilityConstraint;

            for (int targetIndex = 0; targetIndex < perTargetResults.length; targetIndex++) {
                LaunchOffsetCalcResult targetCalc = perTargetResults[targetIndex][offsetIndex];
                merged.targets.addAll(targetCalc.targets);
                merged.visibleSatellites.addAll(targetCalc.visibleSatellites);
            }
            allResults.add(merged);
            if (best == null || merged.totalObservableDurationSec() > best.totalObservableDurationSec()) {
                best = merged;
            }
        }

        return new LaunchSearchResultV2(
                allResults,
                best,
                firstReferenceUtc == null ? "" : firstReferenceUtc,
                VisibilityConstraintSummary.fromConfig(activeSensorConfig));
    }

    private static void runOffsetSearch(final int targetIndex,
                                        final PreparedBallisticTarget target,
                                        final double baseLaunchJ2000Sec,
                                        final String baseLaunchUtc,
                                        final long startOffsetSec,
                                        final long stepSizeSec,
                                        final int offsetCount,
                                        final List<OrbitElements> satellites,
                                        final List<BallisticPoint> samples,
                                        final double sensorFovDeg,
                                        final OrbitModel activeModel,
                                        final double sourceReferenceEpochSec,
                                        final SensorVisibilityConfig sensorConfig,
                                        final boolean includeTrajectoryPoints,
                                        final SatellitePositionProvider satellitePositionProvider,
                                        final LaunchOffsetCalcResult[] results,
                                        final ProgressCallback progressCallback) {
        int threadCount = recommendedSearchThreadCount(offsetCount);
        final String targetId = target == null ? "TGT-001" : target.input.targetId;
        final String targetName = target == null ? "TGT-001" : target.input.targetName;
        final String csvPath = target == null ? null : target.input.csvPath;

        if (threadCount <= 1) {
            for (int offsetIndex = 0; offsetIndex < offsetCount; offsetIndex++) {
                results[offsetIndex] = analyzeOffset(
                        offsetIndex,
                        baseLaunchJ2000Sec,
                        targetId,
                        targetName,
                        csvPath,
                        baseLaunchUtc,
                        startOffsetSec,
                        stepSizeSec,
                        satellites,
                        samples,
                        sensorFovDeg,
                        activeModel,
                        sourceReferenceEpochSec,
                        sensorConfig,
                        includeTrajectoryPoints,
                        satellitePositionProvider);
                if (progressCallback != null) {
                    progressCallback.onProgress(offsetIndex + 1L, offsetCount);
                }
            }
            return;
        }

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            CompletionService<IndexedLaunchOffsetCalc> completion =
                    new ExecutorCompletionService<IndexedLaunchOffsetCalc>(executor);
            for (int offsetIndex = 0; offsetIndex < offsetCount; offsetIndex++) {
                final int oi = offsetIndex;
                completion.submit(new Callable<IndexedLaunchOffsetCalc>() {
                    @Override
                    public IndexedLaunchOffsetCalc call() {
                        LaunchOffsetCalcResult calc = analyzeOffset(
                                oi,
                                baseLaunchJ2000Sec,
                                targetId,
                                targetName,
                                csvPath,
                                baseLaunchUtc,
                                startOffsetSec,
                                stepSizeSec,
                                satellites,
                                samples,
                                sensorFovDeg,
                                activeModel,
                                sourceReferenceEpochSec,
                                sensorConfig,
                                includeTrajectoryPoints,
                                satellitePositionProvider);
                        return new IndexedLaunchOffsetCalc(targetIndex, oi, calc);
                    }
                });
            }
            for (int done = 0; done < offsetCount; done++) {
                try {
                    IndexedLaunchOffsetCalc indexed = completion.take().get();
                    results[indexed.offsetIndex] = indexed.calc;
                    if (progressCallback != null) {
                        progressCallback.onProgress(done + 1L, offsetCount);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("偏移量可见性计算被中断。", ex);
                } catch (Exception ex) {
                    throw new IllegalStateException("偏移量可见性计算失败：" + ex.getMessage(), ex);
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static LaunchOffsetCalcResult analyzeOffset(int offsetIndex,
                                                        double baseLaunchJ2000Sec,
                                                        String targetId,
                                                        String targetName,
                                                        String csvPath,
                                                        String baseLaunchUtc,
                                                        long startOffsetSec,
                                                        long stepSizeSec,
                                                        List<OrbitElements> satellites,
                                                        List<BallisticPoint> samples,
                                                        double sensorFovDeg,
                                                        OrbitModel activeModel,
                                                        double sourceReferenceEpochSec,
                                                        SensorVisibilityConfig sensorConfig,
                                                        boolean includeTrajectoryPoints,
                                                        SatellitePositionProvider satellitePositionProvider) {
        long offsetSec = startOffsetSec + (long) offsetIndex * stepSizeSec;
        double launchJ2000 = baseLaunchJ2000Sec + offsetSec;
        String launchUtc = j2000ToUtcMillis(launchJ2000);
        LaunchOffsetCalcResult calc = analyzeLaunchOffsetV2(
                launchJ2000,
                offsetSec,
                launchUtc,
                satellites,
                samples,
                sensorFovDeg,
                activeModel,
                sourceReferenceEpochSec,
                sensorConfig,
                includeTrajectoryPoints,
                satellitePositionProvider);
        decorateCalculationTarget(
                calc,
                isBlank(targetId) ? "TGT-001" : targetId,
                isBlank(targetName) ? (isBlank(targetId) ? "TGT-001" : targetId) : targetName,
                csvPath,
                baseLaunchUtc,
                sourceReferenceEpochSec);
        return calc;
    }

    private static int recommendedSearchThreadCount(long taskCount) {
        if (taskCount <= 1L) {
            return 1;
        }
        int processors = Runtime.getRuntime().availableProcessors();
        int workerLimit = Math.max(1, processors - 1);
        return (int) Math.max(1L, Math.min(taskCount, workerLimit));
    }

    private static int checkedCalculationCount(long numCalculations) {
        if (numCalculations > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("numCalculations过大。");
        }
        return (int) numCalculations;
    }

    private static void validateMultiTargetInputs(List<BallisticTargetInput> targets) {
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException("ballistics不能为空。");
        }
        for (int i = 0; i < targets.size(); i++) {
            BallisticTargetInput input = targets.get(i);
            if (input == null || isBlank(input.csvPath)) {
                throw new IllegalArgumentException("ballistics[" + i + "].csvPath不能为空。");
            }
        }
    }

    private static BallisticTargetInput normalizedTargetInput(BallisticTargetInput input, int index) {
        BallisticTargetInput result = new BallisticTargetInput();
        result.csvPath = input.csvPath == null ? "" : input.csvPath.trim();
        result.baseLaunchUtc = input.baseLaunchUtc == null ? null : input.baseLaunchUtc.trim();
        result.targetId = isBlank(input.targetId) ? "TGT-" + (index + 1) : input.targetId.trim();
        result.targetName = isBlank(input.targetName) ? result.targetId : input.targetName.trim();
        return result;
    }

    private static void applyTargetMetadata(LaunchSearchResultV2 result,
                                            BallisticTargetInput input,
                                            double sourceReferenceEpochSec,
                                            String baseLaunchUtc) {
        for (LaunchOffsetCalcResult calc : result.allResults) {
            decorateCalculationTarget(
                    calc,
                    input.targetId,
                    input.targetName,
                    input.csvPath,
                    baseLaunchUtc,
                    sourceReferenceEpochSec);
        }
    }

    private static void decorateCalculationTarget(LaunchOffsetCalcResult calc,
                                                  String targetId,
                                                  String targetName,
                                                  String csvPath,
                                                  String baseLaunchUtc,
                                                  double sourceReferenceEpochSec) {
        calc.targetId = targetId;
        calc.targetName = targetName;
        calc.ballisticCsvPath = csvPath;
        calc.baseLaunchUTC = baseLaunchUtc;
        calc.sourceReferenceEpochSec = sourceReferenceEpochSec;
        for (SatelliteVisibilityResult arc : calc.visibleSatellites) {
            arc.targetId = targetId;
            arc.targetName = targetName;
            arc.ballisticCsvPath = csvPath;
            arc.baseLaunchUTC = baseLaunchUtc;
            if (isBlank(arc.payloadType)) {
                arc.payloadType = calc.payloadType;
            }
            if (arc.visibilityConstraint == null) {
                arc.visibilityConstraint = calc.visibilityConstraint;
            }
            arc.launchUTC = calc.launchTimeUtc;
            arc.launchTimeJ2000Sec = calc.launchTimeJ2000Sec;
            arc.sourceReferenceEpochSec = sourceReferenceEpochSec;
        }
        calc.targets.clear();
        calc.targets.add(TargetVisibilitySummary.fromCalc(calc));
    }

    /**
     * 对一次确定的发射偏移量，计算所有卫星对弹道目标的可见弧段。
     *
     * 时间模型：卫星历元固定不变，每次通过 propagate() 从历元外推 relTime 秒到当前发射时刻。
     * relTime = (launchJ2000Sec - referenceEpochSec) + point.tSec，
     * 内部叠加 referenceEpochSec 后得到正确的J2000绝对时间。
     *
     * 可见性遍历：按CSV原始轨迹点逐点检查，不插值。对每个轨迹点逐卫星判断视场角和地球遮挡。
     * 连续可见的轨迹点归入同一弧段(same arcIndex)；不连续则开启新弧段。
     */
    private static LaunchOffsetCalcResult analyzeLaunchOffsetV2(
            double launchJ2000Sec,
            long offsetSec,
            String launchUtc,
            List<OrbitElements> satellites,
            List<BallisticPoint> samples,
            double sensorFovDeg,
            OrbitModel orbitModel,
            double sourceReferenceEpochSec,
            SensorVisibilityConfig sensorConfig,
            boolean includeTrajectoryPoints,
            SatellitePositionProvider satellitePositionProvider) {
        LaunchOffsetCalcResult calc = new LaunchOffsetCalcResult();
        calc.targetId = "TGT-001";
        calc.targetName = "TGT-001";
        calc.launchTimeUtc = launchUtc;
        calc.launchTimeJ2000Sec = launchJ2000Sec;
        calc.sourceReferenceEpochSec = sourceReferenceEpochSec;
        calc.offsetSecond = offsetSec;
        calc.visibilityConstraint = VisibilityConstraintSummary.fromConfig(sensorConfig);
        calc.payloadType = calc.visibilityConstraint.payloadType;

        // 卫星位置按平台传播一次；同一位置供该平台的所有载荷复用。
        List<List<SatellitePayloadConfig>> payloadsBySatellite =
                new ArrayList<List<SatellitePayloadConfig>>(satellites.size());
        boolean[][] payloadInArc = new boolean[satellites.size()][];
        int[][] payloadArcIndexes = new int[satellites.size()][];
        SatelliteVisibilityResult[][] payloadCurrent = new SatelliteVisibilityResult[satellites.size()][];
        for (int i = 0; i < satellites.size(); i++) {
            List<SatellitePayloadConfig> payloads = resolveSatellitePayloads(
                    satellites.get(i), sensorConfig, sensorFovDeg, i + 1);
            payloadsBySatellite.add(payloads);
            payloadInArc[i] = new boolean[payloads.size()];
            payloadArcIndexes[i] = new int[payloads.size()];
            payloadCurrent[i] = new SatelliteVisibilityResult[payloads.size()];
        }

        for (BallisticPoint point : samples) {
            double absoluteTime = launchJ2000Sec + point.tSec;
            Vector3 target = ballisticPointToEciAtTime(point, absoluteTime);

            for (int i = 0; i < satellites.size(); i++) {
                OrbitElements elements = satellites.get(i);
                Vector3 satellite = satellitePositionProvider.position(i, elements, absoluteTime, orbitModel);
                List<SatellitePayloadConfig> payloads = payloadsBySatellite.get(i);
                for (int payloadIndex = 0; payloadIndex < payloads.size(); payloadIndex++) {
                    SatellitePayloadConfig payload = payloads.get(payloadIndex);
                    SensorVisibilityConfig payloadConfig = payload.sensorConfig;
                    boolean visible = isTargetObservableFromSatelliteStable(
                            satellite,
                            target,
                            payloadConfig,
                            absoluteTime,
                            payloadInArc[i][payloadIndex]);

                    if (visible && !payloadInArc[i][payloadIndex]) {
                        payloadInArc[i][payloadIndex] = true;
                        payloadArcIndexes[i][payloadIndex]++;
                        SatelliteVisibilityResult arc = new SatelliteVisibilityResult();
                        arc.satelliteId = isBlank(elements.satelliteId) ? elements.name : elements.satelliteId;
                        arc.satelliteName = elements.name;
                        arc.satelliteIndex = i + 1;
                        arc.arcIndex = payloadArcIndexes[i][payloadIndex];
                        arc.payloadId = payload.payloadId;
                        arc.payloadName = payload.payloadName;
                        arc.targetId = calc.targetId;
                        arc.targetName = calc.targetName;
                        arc.payloadType = VisibilityConstraintSummary.fromConfig(payloadConfig).payloadType;
                        arc.visibilityConstraint = VisibilityConstraintSummary.fromConfig(payloadConfig);
                        arc.launchUTC = calc.launchTimeUtc;
                        arc.launchTimeJ2000Sec = calc.launchTimeJ2000Sec;
                        arc.sourceReferenceEpochSec = calc.sourceReferenceEpochSec;
                        payloadCurrent[i][payloadIndex] = arc;
                    }

                    if (visible) {
                        Vector3 rEcef = eciToEcefPos(target, absoluteTime);
                        double[] geo = ecefToGeodetic(rEcef);
                        recordVisiblePoint(
                                payloadCurrent[i][payloadIndex],
                                geo[1],
                                geo[0],
                                geo[2],
                                absoluteTime,
                                point.tSec,
                                includeTrajectoryPoints);
                    }

                    if (!visible && payloadInArc[i][payloadIndex]) {
                        payloadInArc[i][payloadIndex] = false;
                        calc.visibleSatellites.add(payloadCurrent[i][payloadIndex]);
                        payloadCurrent[i][payloadIndex] = null;
                    }
                }
            }
        }

        for (int i = 0; i < satellites.size(); i++) {
            for (int payloadIndex = 0; payloadIndex < payloadInArc[i].length; payloadIndex++) {
                SatelliteVisibilityResult arc = payloadCurrent[i][payloadIndex];
                if (payloadInArc[i][payloadIndex] && arc != null && hasVisiblePoints(arc)) {
                    calc.visibleSatellites.add(arc);
                }
            }
        }
        List<SatelliteVisibilityResult> stableArcs = stableVisibleArcs(
                calc.visibleSatellites,
                MIN_STABLE_VISIBLE_POINT_COUNT,
                MIN_STABLE_VISIBLE_DURATION_SEC);
        calc.visibleSatellites.clear();
        calc.visibleSatellites.addAll(stableArcs);
        return calc;
    }

    /**
     * 解析平台有效载荷。未配置 payloads 时创建一个兼容旧调用的逻辑载荷；
     * 配置了 payloads 时，每个载荷均保留独立的可见性约束。
     */
    private static List<SatellitePayloadConfig> resolveSatellitePayloads(OrbitElements satellite,
                                                                           SensorVisibilityConfig fallbackConfig,
                                                                           double sensorFovDeg,
                                                                           int satelliteIndex) {
        List<SatellitePayloadConfig> resolved = new ArrayList<SatellitePayloadConfig>();
        List<SatellitePayloadConfig> configured = satellite == null ? null : satellite.payloads;
        if (configured == null || configured.isEmpty()) {
            SatellitePayloadConfig legacy = new SatellitePayloadConfig();
            legacy.payloadId = "LEGACY-" + satelliteIndex;
            legacy.sensorConfig = normalizeSensorConfig(fallbackConfig, sensorFovDeg);
            legacy.payloadName = legacy.sensorConfig.payloadType.displayName();
            resolved.add(legacy);
            return resolved;
        }

        for (int payloadIndex = 0; payloadIndex < configured.size(); payloadIndex++) {
            SatellitePayloadConfig source = configured.get(payloadIndex);
            if (source == null) {
                throw new IllegalArgumentException("卫星" + satelliteIndex + "的payloads[" + payloadIndex + "]不能为空。");
            }
            SatellitePayloadConfig payload = new SatellitePayloadConfig();
            payload.sensorConfig = normalizeSensorConfig(
                    source.sensorConfig == null ? fallbackConfig : source.sensorConfig,
                    sensorFovDeg);
            payload.payloadId = isBlank(source.payloadId)
                    ? (isBlank(satellite.name) ? "SAT-" + satelliteIndex : satellite.name)
                    + "-PAYLOAD-" + (payloadIndex + 1)
                    : source.payloadId.trim();
            payload.payloadName = isBlank(source.payloadName)
                    ? payload.sensorConfig.payloadType.displayName()
                    : source.payloadName.trim();
            resolved.add(payload);
        }
        return resolved;
    }

    private static void validateSearchInputsV2(long stepSizeSec, long numCalculations,
                                                List<OrbitElements> satellites,
                                                List<BallisticPoint> ballisticPoints,
                                                double sensorFovDeg) {
        if (stepSizeSec <= 0L) throw new IllegalArgumentException("发射偏移步长必须大于0秒。");
        if (numCalculations <= 0L) throw new IllegalArgumentException("计算次数必须大于0。");
        if (sensorFovDeg <= 0.0 || sensorFovDeg > 180.0)
            throw new IllegalArgumentException("传感器视场角应在0到180度之间。");
        if (ballisticPoints == null || ballisticPoints.isEmpty())
            throw new IllegalArgumentException("弹道点不能为空。");
        if (satellites == null || satellites.isEmpty())
            throw new IllegalArgumentException("请至少输入一颗卫星。");
        for (OrbitElements elements : satellites) validateOrbit(elements);
    }

    public static List<Track> generateSatelliteTracks(List<OrbitElements> satellites,
                                                       double durationSec,
                                                       double stepSec) {
        return generateSatelliteTracks(satellites, 0.0, durationSec, stepSec);
    }

    public static List<Track> generateSatelliteTracks(List<OrbitElements> satellites,
                                                       double startSec,
                                                       double endSec,
                                                       double stepSec) {
        return generateSatelliteTracks(satellites, startSec, endSec, stepSec, OrbitModel.TWO_BODY);
    }

    public static List<Track> generateSatelliteTracks(List<OrbitElements> satellites,
                                                       double startSec,
                                                       double endSec,
                                                       double stepSec,
                                                       OrbitModel orbitModel) {
        validatePositive(stepSec, "时间步长");
        if (endSec < startSec) {
            throw new IllegalArgumentException("结束时间不能小于开始时间。");
        }
        OrbitModel activeModel = orbitModel == null ? OrbitModel.TWO_BODY : orbitModel;

        List<Track> tracks = new ArrayList<Track>();
        for (OrbitElements elements : satellites) {
            validateOrbit(elements);
            List<TimedPosition> points = new ArrayList<TimedPosition>();
            for (double t = startSec; t <= endSec + 1e-9; t += stepSec) {
                points.add(new TimedPosition(t, propagate(elements, t, activeModel)));
            }
            if (points.isEmpty() || points.get(points.size() - 1).tSec < endSec) {
                points.add(new TimedPosition(endSec, propagate(elements, endSec, activeModel)));
            }
            tracks.add(new Track(elements.name, points));
        }
        return tracks;
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
    public static double j2SecularRaanRateRadPerSec(double semiMajorAxisM,
                                                    double eccentricity,
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
        ParsedBallisticCsv parsed = parseBallisticCsvWithReference(filePath);
        referenceEpochSec = parsed.referenceEpochSec;
        return parsed.points;
    }

    public static ParsedBallisticCsv parseBallisticCsvWithReference(Path filePath) throws IOException {
        if (filePath == null) {
            throw new IllegalArgumentException("弹道文件路径不能为空。");
        }
        if (isExcelFile(filePath)) {
            return parseBallisticExcelWithReference(filePath);
        }
        BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8);
        try {
            return parseBallisticCsvWithReference(reader);
        } finally {
            reader.close();
        }
    }

    /**
     * 按位置字符串解析弹道文件：支持本地路径、http(s):// 直链、minio:// 地址（详见 TrajectoryFileResolver）。
     * 远程地址先下载到本地临时文件，再委托 Path 版本解析（保留 Excel 自动识别能力）。
     */
    public static List<BallisticPoint> parseBallisticCsv(String location) throws IOException {
        return parseBallisticCsv(TrajectoryFileResolver.resolveToLocalFile(location));
    }

    /**
     * 按位置字符串解析弹道文件并返回参考历元：支持本地路径、http(s):// 直链、minio:// 地址。
     */
    public static ParsedBallisticCsv parseBallisticCsvWithReference(String location) throws IOException {
        return parseBallisticCsvWithReference(TrajectoryFileResolver.resolveToLocalFile(location));
    }

    /**
     * 从UTF-8字节流解析弹道CSV。调用方负责关闭传入的InputStream；字节流入口不做Excel自动识别。
     */
    public static List<BallisticPoint> parseBallisticCsv(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            throw new IllegalArgumentException("弹道CSV输入流不能为空。");
        }
        ParsedBallisticCsv parsed = parseBallisticCsvWithReference(inputStream);
        referenceEpochSec = parsed.referenceEpochSec;
        return parsed.points;
    }

    public static ParsedBallisticCsv parseBallisticCsvWithReference(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            throw new IllegalArgumentException("弹道CSV输入流不能为空。");
        }
        return parseBallisticCsvWithReference(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
    }

    /**
     * 从字符流逐行解析弹道CSV。调用方负责关闭传入的Reader。
     */
    public static List<BallisticPoint> parseBallisticCsv(Reader sourceReader) throws IOException {
        ParsedBallisticCsv parsed = parseBallisticCsvWithReference(sourceReader);
        referenceEpochSec = parsed.referenceEpochSec;
        return parsed.points;
    }

    public static ParsedBallisticCsv parseBallisticCsvWithReference(Reader sourceReader) throws IOException {
        if (sourceReader == null) {
            throw new IllegalArgumentException("弹道CSV字符流不能为空。");
        }
        BufferedReader reader = sourceReader instanceof BufferedReader
                ? (BufferedReader) sourceReader
                : new BufferedReader(sourceReader);
        List<BallisticPoint> points = new ArrayList<BallisticPoint>();
        BallisticParseState state = new BallisticParseState();
        String rawLine;
        while ((rawLine = reader.readLine()) != null) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (isBallisticHeader(lower)) {
                continue;
            }

            BallisticPoint point = tryParseNewUtcSplitBallistic(line, state);
            if (point != null) {
                points.add(point);
                continue;
            }

            String[] parts = line.split(",");
            if (parts.length >= 18) {
                point = parseLegacyBallistic(parts, state);
                points.add(point);
            }
        }

        if (points.isEmpty()) {
            throw new IllegalArgumentException("弹道文件未解析到有效数据。");
        }
        Collections.sort(points, new Comparator<BallisticPoint>() {
            @Override
            public int compare(BallisticPoint a, BallisticPoint b) {
                return Double.compare(a.tSec, b.tSec);
            }
        });
        double sourceReference = Double.isNaN(state.referenceEpochSec) ? 0.0 : state.referenceEpochSec;
        return new ParsedBallisticCsv(points, sourceReference);
    }

    private static ParsedBallisticCsv parseBallisticExcelWithReference(Path excelPath) throws IOException {
        InputStream input = Files.newInputStream(excelPath);
        Workbook workbook = null;
        try {
            workbook = WorkbookFactory.create(input);
            if (workbook.getNumberOfSheets() <= 0) {
                throw new IllegalArgumentException("弹道Excel未包含工作表。");
            }
            Sheet sheet = workbook.getSheetAt(0);
            List<String> csvLines = new ArrayList<String>();
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            for (Row row : sheet) {
                String line = excelRowToCsvLine(row, formatter);
                if (!line.trim().isEmpty()) {
                    csvLines.add(line);
                }
            }
            return parseBallisticCsvWithReference(new java.io.StringReader(joinLines(csvLines)));
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            IOException io = new IOException("弹道Excel解析失败：" + ex.getMessage());
            io.initCause(ex);
            throw io;
        } finally {
            if (workbook != null) {
                workbook.close();
            }
            input.close();
        }
    }

    private static String excelRowToCsvLine(Row row, DataFormatter formatter) {
        int lastCell = row.getLastCellNum();
        if (lastCell <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lastCell; i++) {
            if (i > 0) {
                sb.append(',');
            }
            Cell cell = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            sb.append(excelCellText(cell, formatter));
        }
        return sb.toString();
    }

    private static String excelCellText(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            type = cell.getCachedFormulaResultType();
        }
        if (type == CellType.NUMERIC) {
            double value = cell.getNumericCellValue();
            if (Math.abs(value - Math.rint(value)) < 1e-9) {
                return String.format(Locale.ROOT, "%.0f", value);
            }
            return String.format(Locale.ROOT, "%.12f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        if (type == CellType.BOOLEAN) {
            return String.valueOf(cell.getBooleanCellValue());
        }
        return formatter.formatCellValue(cell).trim();
    }

    private static String joinLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static boolean isExcelFile(Path filePath) {
        String name = filePath.getFileName() == null ? "" : filePath.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".xlsx") || name.endsWith(".xls") || name.endsWith(".xlsm");
    }

    private static boolean isBallisticHeader(String lowerLine) {
        return lowerLine.startsWith("time")
                || lowerLine.startsWith("yyyy")
                || lowerLine.contains("utc_time")
                || lowerLine.contains("eci_x")
                || lowerLine.contains("ecef_x");
    }

    private static BallisticPoint tryParseNewUtcSplitBallistic(String line, BallisticParseState state) {
        String[] parts = line.trim().replace(',', ' ').split("\\s+");
        if (parts.length < 20 || !isIntegerText(parts[0]) || !isIntegerText(parts[1]) || !isIntegerText(parts[2])) {
            return null;
        }
        int year = Integer.parseInt(parts[0]);
        if (year < 1900 || year > 2200) {
            return null;
        }
        int month = Integer.parseInt(parts[1]);
        int day = Integer.parseInt(parts[2]);
        int hour = Integer.parseInt(parts[3]);
        int minute = Integer.parseInt(parts[4]);
        double second = parseDouble(parts[5]);
        double absoluteUtcSec = utcPartsToJ2000Sec(year, month, day, hour, minute, second, false);
        if (!state.epochParsed) {
            state.referenceEpochSec = absoluteUtcSec;
            state.epochParsed = true;
        }

        BallisticPoint point = new BallisticPoint();
        point.tSec = absoluteUtcSec - state.referenceEpochSec;
        point.ecefX = parseDouble(parts[6]);
        point.ecefY = parseDouble(parts[7]);
        point.ecefZ = parseDouble(parts[8]);
        point.eciX = parseDouble(parts[11]);
        point.eciY = parseDouble(parts[12]);
        point.eciZ = parseDouble(parts[13]);
        point.eciVx = parseDouble(parts[14]);
        point.eciVy = parseDouble(parts[15]);
        point.eciVz = parseDouble(parts[16]);
        point.ecefVx = parseDouble(parts[17]);
        point.ecefVy = parseDouble(parts[18]);
        point.ecefVz = parseDouble(parts[19]);
        double[] geo = ecefToGeodetic(new Vector3(point.ecefX, point.ecefY, point.ecefZ));
        point.latDeg = geo[0];
        point.lonDeg = geo[1];
        point.heightM = geo[2];
        point.speedMs = Math.sqrt(point.eciVx * point.eciVx + point.eciVy * point.eciVy + point.eciVz * point.eciVz);
        return point;
    }

    private static BallisticPoint parseLegacyBallistic(String[] parts, BallisticParseState state) {
        if (!state.epochParsed) {
            state.referenceEpochSec = utcToJ2000Sec(parts[1].trim());
            state.epochParsed = true;
        }
        BallisticPoint point = new BallisticPoint();
        point.tSec = parseDouble(parts[0]);
        point.eciX = parseDouble(parts[2]);
        point.eciY = parseDouble(parts[3]);
        point.eciZ = parseDouble(parts[4]);
        point.eciVx = parseDouble(parts[5]);
        point.eciVy = parseDouble(parts[6]);
        point.eciVz = parseDouble(parts[7]);
        point.ecefX = parseDouble(parts[8]);
        point.ecefY = parseDouble(parts[9]);
        point.ecefZ = parseDouble(parts[10]);
        point.ecefVx = parseDouble(parts[11]);
        point.ecefVy = parseDouble(parts[12]);
        point.ecefVz = parseDouble(parts[13]);
        point.heightM = parseDouble(parts[14]);
        point.latDeg = Math.toDegrees(parseDouble(parts[15]));
        point.lonDeg = Math.toDegrees(parseDouble(parts[16]));
        point.speedMs = parseDouble(parts[17]);
        return point;
    }

    public static List<BallisticPoint> interpolateBallistic(List<BallisticPoint> points, double stepSec) {
        validatePositive(stepSec, "弹道插值步长");
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("弹道点不能为空。");
        }
        if (points.size() == 1) {
            return new ArrayList<BallisticPoint>(points);
        }

        List<BallisticPoint> sorted = sortedBallistic(points);
        double start = sorted.get(0).tSec;
        double end = sorted.get(sorted.size() - 1).tSec;
        List<BallisticPoint> result = new ArrayList<BallisticPoint>();
        for (double t = start; t <= end + 1e-9; t += stepSec) {
            result.add(interpolateAt(sorted, t));
        }
        if (result.get(result.size() - 1).tSec < end) {
            result.add(interpolateAt(sorted, end));
        }
        return result;
    }

    private static double visibilitySampleStepSec(List<BallisticPoint> sorted) {
        return visibilitySampleStepSec(sorted, Double.NaN);
    }

    private static double visibilitySampleStepSec(List<BallisticPoint> sorted, double requestedStepSec) {
        if (!Double.isNaN(requestedStepSec) && !Double.isInfinite(requestedStepSec) && requestedStepSec > 0.0) {
            return Math.max(1e-3, requestedStepSec);
        }
        if (sorted == null || sorted.size() < 2) {
            return 1.0;
        }
        double minDt = Double.POSITIVE_INFINITY;
        for (int i = 0; i < sorted.size() - 1; i++) {
            double dt = sorted.get(i + 1).tSec - sorted.get(i).tSec;
            if (dt > 1e-9) {
                minDt = Math.min(minDt, dt);
            }
        }
        if (Double.isInfinite(minDt)) {
            return 1.0;
        }
        return Math.max(1e-3, Math.min(1.0, minDt));
    }

    public static double ballisticStartSec(List<BallisticPoint> ballisticPoints) {
        return sortedBallistic(ballisticPoints).get(0).tSec;
    }

    public static double ballisticEndSec(List<BallisticPoint> ballisticPoints) {
        List<BallisticPoint> sorted = sortedBallistic(ballisticPoints);
        return sorted.get(sorted.size() - 1).tSec;
    }

    public static Track generateBallisticTrack(List<BallisticPoint> ballisticPoints, double displayStepSec) {
        return generateBallisticTrack(ballisticPoints, displayStepSec, 0.0);
    }

    public static Track generateBallisticTrack(List<BallisticPoint> ballisticPoints,
                                               double displayStepSec,
                                               double launchOffsetSec) {
        return generateBallisticTrack(ballisticPoints, displayStepSec, launchOffsetSec, GeodeticDatum.WGS84);
    }

    public static Track generateBallisticTrack(List<BallisticPoint> ballisticPoints,
                                               double displayStepSec,
                                               double launchOffsetSec,
                                               GeodeticDatum datum) {
        List<BallisticPoint> samples = interpolateBallistic(ballisticPoints, displayStepSec);
        List<TimedPosition> positions = new ArrayList<TimedPosition>();
        for (BallisticPoint point : samples) {
            double absoluteTime = launchOffsetSec + point.tSec;
            positions.add(new TimedPosition(
                    absoluteTime,
                    ballisticPointToEciAtTime(point, referenceEpochSec + absoluteTime)
            ));
        }
        return new Track("弹道轨迹", positions);
    }

    public static List<Track> generateVisibleBallisticTracks(List<BallisticPoint> ballisticPoints,
                                                             double displayStepSec,
                                                             double launchOffsetSec,
                                                             List<VisibilityWindow> windows) {
        return generateVisibleBallisticTracks(ballisticPoints, displayStepSec, launchOffsetSec, windows, GeodeticDatum.WGS84);
    }

    public static List<Track> generateVisibleBallisticTracks(List<BallisticPoint> ballisticPoints,
                                                             double displayStepSec,
                                                             double launchOffsetSec,
                                                             List<VisibilityWindow> windows,
                                                             GeodeticDatum datum) {
        List<Track> tracks = new ArrayList<Track>();
        if (windows == null || windows.isEmpty()) {
            return tracks;
        }

        List<BallisticPoint> sorted = sortedBallistic(ballisticPoints);
        for (int index = 0; index < windows.size(); index++) {
            VisibilityWindow window = windows.get(index);
            List<TimedPosition> positions = new ArrayList<TimedPosition>();
            for (double t = window.startRelSec; t <= window.endRelSec + 1e-9; t += displayStepSec) {
                BallisticPoint point = interpolateAt(sorted, t);
                double absoluteTime = launchOffsetSec + point.tSec;
                positions.add(new TimedPosition(
                        absoluteTime,
                        ballisticPointToEciAtTime(point, referenceEpochSec + absoluteTime)
                ));
            }
            if (!positions.isEmpty() && positions.get(positions.size() - 1).tSec < launchOffsetSec + window.endRelSec) {
                BallisticPoint point = interpolateAt(sorted, window.endRelSec);
                double absoluteTime = launchOffsetSec + point.tSec;
                positions.add(new TimedPosition(
                        absoluteTime,
                        ballisticPointToEciAtTime(point, referenceEpochSec + absoluteTime)
                ));
            }
            if (positions.size() >= 2) {
                tracks.add(new Track("可见弹道段-" + (index + 1), positions));
            }
        }
        return tracks;
    }

    public static LaunchSearchResult searchLaunchOffsets(long startTimeSec,
                                                         long stepSizeSec,
                                                         long numCalculations,
                                                         List<OrbitElements> satellites,
                                                         List<BallisticPoint> ballisticPoints,
                                                         double sensorFovDeg) {
        return searchLaunchOffsets(startTimeSec, stepSizeSec, numCalculations, satellites, ballisticPoints, sensorFovDeg, GeodeticDatum.WGS84);
    }

    public static LaunchSearchResult searchLaunchOffsets(long startTimeSec,
                                                         long stepSizeSec,
                                                         long numCalculations,
                                                         List<OrbitElements> satellites,
                                                         List<BallisticPoint> ballisticPoints,
                                                         double sensorFovDeg,
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
    public static LaunchSearchResult searchLaunchOffsets(long startTimeSec,
                                                         long stepSizeSec,
                                                         long numCalculations,
                                                         List<OrbitElements> satellites,
                                                         List<BallisticPoint> ballisticPoints,
                                                         double sensorFovDeg,
                                                         GeodeticDatum datum,
                                                         OrbitModel orbitModel,
                                                         ProgressCallback progressCallback) {
        validateSearchInputs(numCalculations, stepSizeSec, satellites, ballisticPoints, sensorFovDeg);
        if (stepSizeSec <= 0L) {
            throw new IllegalArgumentException("计算步长必须大于0秒。");
        }
        if (numCalculations <= 0L) {
            throw new IllegalArgumentException("计算次数必须大于0。");
        }
        OrbitModel activeModel = orbitModel == null ? OrbitModel.TWO_BODY : orbitModel;

        List<BallisticPoint> samples = interpolateBallistic(ballisticPoints, 1.0);
        List<LaunchOffsetResult> candidates = new ArrayList<LaunchOffsetResult>();
        LaunchOffsetResult best = null;
        long completed = 0L;
        for (long i = 0; i < numCalculations; i++) {
            long offsetSecond = startTimeSec + i * stepSizeSec;
            LaunchOffsetResult candidate = analyzeLaunchOffset(offsetSecond, satellites, samples, sensorFovDeg, datum, activeModel);
            candidates.add(candidate);
            if (best == null || candidate.observableSeconds > best.observableSeconds) {
                best = candidate;
            }
            completed++;
            if (progressCallback != null) {
                progressCallback.onProgress(completed, numCalculations);
            }
        }
        return new LaunchSearchResult(candidates, best);
    }

    private static LaunchOffsetResult analyzeLaunchOffset(long offsetSecond,
                                                          List<OrbitElements> satellites,
                                                          List<BallisticPoint> samples,
                                                          double sensorFovDeg,
                                                          GeodeticDatum datum,
                                                          OrbitModel orbitModel) {
        List<VisibilityWindow> unionWindows = new ArrayList<VisibilityWindow>();
        List<SatelliteVisibilityWindow> satelliteWindows = new ArrayList<SatelliteVisibilityWindow>();
        boolean[] satelliteInWindow = new boolean[satellites.size()];
        double[] satelliteWindowStart = new double[satellites.size()];
        double[] satelliteLastVisible = new double[satellites.size()];

        boolean unionInWindow = false;
        double unionWindowStart = 0.0;
        double unionLastVisible = 0.0;

        for (BallisticPoint point : samples) {
            double absoluteTime = offsetSecond + point.tSec;
            Vector3 target = ballisticPointToEci(point, offsetSecond);
            boolean anyVisible = false;

            for (int i = 0; i < satellites.size(); i++) {
                OrbitElements elements = satellites.get(i);
                Vector3 satellite = propagate(elements, absoluteTime, orbitModel);
                boolean visible = isTargetObservableFromSatellite(
                        satellite, target, sensorFovDeg, referenceEpochSec + absoluteTime);
                anyVisible = anyVisible || visible;

                if (visible && !satelliteInWindow[i]) {
                    satelliteInWindow[i] = true;
                    satelliteWindowStart[i] = point.tSec;
                }
                if (visible) {
                    satelliteLastVisible[i] = point.tSec;
                }
                if (!visible && satelliteInWindow[i]) {
                    satelliteWindows.add(new SatelliteVisibilityWindow(elements.name, satelliteWindowStart[i], satelliteLastVisible[i]));
                    satelliteInWindow[i] = false;
                }
            }

            if (anyVisible && !unionInWindow) {
                unionInWindow = true;
                unionWindowStart = point.tSec;
            }
            if (anyVisible) {
                unionLastVisible = point.tSec;
            }
            if (!anyVisible && unionInWindow) {
                unionWindows.add(new VisibilityWindow(unionWindowStart, unionLastVisible));
                unionInWindow = false;
            }
        }

        if (unionInWindow) {
            unionWindows.add(new VisibilityWindow(unionWindowStart, unionLastVisible));
        }
        for (int i = 0; i < satellites.size(); i++) {
            if (satelliteInWindow[i]) {
                satelliteWindows.add(new SatelliteVisibilityWindow(satellites.get(i).name, satelliteWindowStart[i], satelliteLastVisible[i]));
            }
        }

        long observableSeconds = 0L;
        for (VisibilityWindow window : unionWindows) {
            observableSeconds += Math.round(window.durationSec());
        }
        return new LaunchOffsetResult(offsetSecond, observableSeconds, unionWindows, satelliteWindows);
    }

    public static LaunchEstimationResult estimateBestLaunchSecond(long startTimeSec,
                                                                  long stepSizeSec,
                                                                  long numCalculations,
                                                                  List<OrbitElements> satellites,
                                                                  List<BallisticPoint> ballisticPoints,
                                                                  double sensorFovDeg) {
        return estimateBestLaunchSecond(startTimeSec, stepSizeSec, numCalculations, satellites, ballisticPoints, sensorFovDeg, GeodeticDatum.WGS84);
    }

    public static LaunchEstimationResult estimateBestLaunchSecond(long startTimeSec,
                                                                  long stepSizeSec,
                                                                  long numCalculations,
                                                                  List<OrbitElements> satellites,
                                                                  List<BallisticPoint> ballisticPoints,
                                                                  double sensorFovDeg,
                                                                  GeodeticDatum datum) {
        LaunchSearchResult searchResult = searchLaunchOffsets(
                startTimeSec,
                stepSizeSec,
                numCalculations,
                satellites,
                ballisticPoints,
                sensorFovDeg,
                datum
        );
        LaunchEstimationResult result = new LaunchEstimationResult();
        result.bestLaunchSecond = searchResult.best == null ? startTimeSec : searchResult.best.offsetSecond;
        result.bestObservableSeconds = searchResult.best == null ? 0L : searchResult.best.observableSeconds;
        result.candidateCount = searchResult.candidates.size();
        return result;
    }

    public static List<VisibilityWindow> visibilityWindows(double launchOffsetSec,
                                                           List<OrbitElements> satellites,
                                                           List<BallisticPoint> oneSecondSamples,
                                                           double sensorFovDeg) {
        return visibilityWindows(launchOffsetSec, satellites, oneSecondSamples, sensorFovDeg, GeodeticDatum.WGS84);
    }

    public static List<VisibilityWindow> visibilityWindows(double launchOffsetSec,
                                                           List<OrbitElements> satellites,
                                                           List<BallisticPoint> oneSecondSamples,
                                                           double sensorFovDeg,
                                                           GeodeticDatum datum) {
        List<VisibilityWindow> windows = new ArrayList<VisibilityWindow>();
        boolean inWindow = false;
        double windowStart = 0.0;
        double lastVisible = 0.0;

        for (BallisticPoint point : oneSecondSamples) {
            boolean visible = isBallisticPointVisible(launchOffsetSec, point, satellites, sensorFovDeg, datum);
            if (visible && !inWindow) {
                inWindow = true;
                windowStart = point.tSec;
            }
            if (visible) {
                lastVisible = point.tSec;
            }
            if (!visible && inWindow) {
                windows.add(new VisibilityWindow(windowStart, lastVisible));
                inWindow = false;
            }
        }
        if (inWindow) {
            windows.add(new VisibilityWindow(windowStart, lastVisible));
        }
        return windows;
    }

    public static List<SatelliteVisibilityWindow> satelliteVisibilityWindows(double launchOffsetSec,
                                                                            List<OrbitElements> satellites,
                                                                            List<BallisticPoint> oneSecondSamples,
                                                                            double sensorFovDeg,
                                                                            GeodeticDatum datum) {
        List<SatelliteVisibilityWindow> windows = new ArrayList<SatelliteVisibilityWindow>();
        for (OrbitElements satellite : satellites) {
            boolean inWindow = false;
            double windowStart = 0.0;
            double lastVisible = 0.0;
            for (BallisticPoint point : oneSecondSamples) {
                boolean visible = isBallisticPointVisibleBySatellite(launchOffsetSec, point, satellite, sensorFovDeg, datum);
                if (visible && !inWindow) {
                    inWindow = true;
                    windowStart = point.tSec;
                }
                if (visible) {
                    lastVisible = point.tSec;
                }
                if (!visible && inWindow) {
                    windows.add(new SatelliteVisibilityWindow(satellite.name, windowStart, lastVisible));
                    inWindow = false;
                }
            }
            if (inWindow) {
                windows.add(new SatelliteVisibilityWindow(satellite.name, windowStart, lastVisible));
            }
        }
        return windows;
    }

    public static boolean isBallisticPointVisible(double launchOffsetSec,
                                                  BallisticPoint point,
                                                  List<OrbitElements> satellites,
                                                  double sensorFovDeg) {
        return isBallisticPointVisible(launchOffsetSec, point, satellites, sensorFovDeg, GeodeticDatum.WGS84);
    }

    public static boolean isBallisticPointVisible(double launchOffsetSec,
                                                  BallisticPoint point,
                                                  List<OrbitElements> satellites,
                                                  double sensorFovDeg,
                                                  GeodeticDatum datum) {
        double absoluteTime = launchOffsetSec + point.tSec;
        Vector3 target = ballisticPointToEci(point, launchOffsetSec);
        for (OrbitElements elements : satellites) {
            Vector3 satellite = propagate(elements, absoluteTime);
            if (isTargetObservableFromSatellite(
                    satellite, target, sensorFovDeg, referenceEpochSec + absoluteTime)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isBallisticPointVisibleBySatellite(double launchOffsetSec,
                                                             BallisticPoint point,
                                                             OrbitElements satelliteElements,
                                                             double sensorFovDeg,
                                                             GeodeticDatum datum) {
        double absoluteTime = launchOffsetSec + point.tSec;
        Vector3 target = ballisticPointToEci(point, launchOffsetSec);
        Vector3 satellite = propagate(satelliteElements, absoluteTime);
        return isTargetObservableFromSatellite(
                satellite, target, sensorFovDeg, referenceEpochSec + absoluteTime);
    }

    public static Vector3 geodeticToEci(double latDeg, double lonDeg, double heightM, double tSec) {
        return geodeticToEci(latDeg, lonDeg, heightM, tSec, GeodeticDatum.WGS84);
    }

    public static Vector3 geodeticToEci(double latDeg,
                                        double lonDeg,
                                        double heightM,
                                        double tSec,
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
        return parseBallisticCsvWithReference(filePath).referenceEpochSec;
    }

    /**
     * 从UTF-8字节流读取弹道CSV首个有效轨迹点的J2000绝对秒。调用方负责关闭传入的InputStream；字节流入口不做Excel自动识别。
     */
    public static double parseBallisticEpochSec(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            throw new IllegalArgumentException("弹道CSV输入流不能为空。");
        }
        return parseBallisticEpochSec(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
    }

    /**
     * 从字符流读取弹道CSV首个有效轨迹点的J2000绝对秒。调用方负责关闭传入的Reader。
     */
    public static double parseBallisticEpochSec(Reader sourceReader) throws IOException {
        if (sourceReader == null) {
            throw new IllegalArgumentException("弹道CSV字符流不能为空。");
        }
        BufferedReader reader = sourceReader instanceof BufferedReader
                ? (BufferedReader) sourceReader
                : new BufferedReader(sourceReader);
        String rawLine;
        while ((rawLine = reader.readLine()) != null) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (isBallisticHeader(lower)) {
                continue;
            }
            String[] splitParts = line.trim().replace(',', ' ').split("\\s+");
            if (splitParts.length >= 6
                    && isIntegerText(splitParts[0])
                    && isIntegerText(splitParts[1])
                    && isIntegerText(splitParts[2])
                    && isIntegerText(splitParts[3])
                    && isIntegerText(splitParts[4])) {
                int year = Integer.parseInt(splitParts[0]);
                if (year >= 1900 && year <= 2200) {
                    int month = Integer.parseInt(splitParts[1]);
                    int day = Integer.parseInt(splitParts[2]);
                    int hour = Integer.parseInt(splitParts[3]);
                    int minute = Integer.parseInt(splitParts[4]);
                    double second = parseDouble(splitParts[5]);
                    return utcPartsToJ2000Sec(year, month, day, hour, minute, second, false);
                }
            }
            String[] parts = line.split(",");
            if (parts.length >= 2) {
                return utcToJ2000Sec(parts[1].trim());
            }
        }
        return 0.0;
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

    private static double utcPartsToJ2000Sec(int year,
                                             int month,
                                             int day,
                                             int hour,
                                             int minute,
                                             double second,
                                             boolean applyConfiguredOffset) {
        return LegacyJ2000Time.utcPartsToJ2000Sec(year, month, day, hour, minute, second, applyConfiguredOffset, utcOffsetSec);
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
                                                               double launchOffsetSec,
                                                               double absoluteTimeSec) {
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
    private static com.runisys.orbit.spacevisibility.domain.geometry.Vector3 toDomainVector(Vector3 value) {
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
    public static List<TrajectoryPoint> propagateFromStateVector(
            Vector3 r0Eci, Vector3 v0Eci,
            double totalTimeSec, double stepSec,
            double outputStepSec, ProgressCallback progressCallback) {
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
    public static List<TrajectoryPoint> propagateFromStateVector(
            Vector3 r0Eci, Vector3 v0Eci,
            double launchEpochSec,
            double totalTimeSec, double stepSec,
            double outputStepSec, ProgressCallback progressCallback) {
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
        Vector3 nadir = satellite.scale(-1.0);               // 天底方向 = 卫星指向地心
        Vector3 lineOfSight = target.subtract(satellite);     // 视线矢量 = 目标 - 卫星
        double angle = degrees(angleBetween(nadir, lineOfSight));
        return angle <= sensorFovDeg / 2.0 && hasLineOfSight(satellite, target);
    }

    public static boolean isTargetObservableFromSatellite(Vector3 satellite,
                                                          Vector3 target,
                                                          double sensorFovDeg,
                                                          double absoluteJ2000Sec) {
        Vector3 nadir = satellite.scale(-1.0);
        Vector3 lineOfSight = target.subtract(satellite);
        double angle = degrees(angleBetween(nadir, lineOfSight));
        return angle <= sensorFovDeg / 2.0
                && hasLineOfSightWgs84Ellipsoid(satellite, target, absoluteJ2000Sec);
    }

    public static boolean isTargetObservableFromSatellite(Vector3 satellite,
                                                          Vector3 target,
                                                          SensorVisibilityConfig sensorConfig,
                                                          double absoluteJ2000Sec,
                                                          Vector3 sunDirectionEci) {
        return isTargetObservableFromSatellite(
                satellite,
                target,
                normalizeSensorConfig(sensorConfig, 140.0),
                absoluteJ2000Sec,
                sunDirectionEci,
                false);
    }

    private static boolean isTargetObservableFromSatelliteStable(Vector3 satellite,
                                                                 Vector3 target,
                                                                 double sensorFovDeg,
                                                                 double absoluteJ2000Sec,
                                                                 boolean alreadyInArc) {
        Vector3 nadir = satellite.scale(-1.0);
        Vector3 lineOfSight = target.subtract(satellite);
        double angle = degrees(angleBetween(nadir, lineOfSight));
        double tolerance = alreadyInArc ? VISIBILITY_EXIT_HYSTERESIS_DEG : VISIBILITY_ENTER_TOLERANCE_DEG;
        // 先保留原有视场角判定，再加入极小边界容差；遮挡判断仍使用原 WGS84 椭球线段相交逻辑。
        return angle <= sensorFovDeg / 2.0 + tolerance
                && hasLineOfSightWgs84Ellipsoid(satellite, target, absoluteJ2000Sec);
    }

    private static boolean isTargetObservableFromSatelliteStable(Vector3 satellite,
                                                                 Vector3 target,
                                                                 SensorVisibilityConfig sensorConfig,
                                                                 double absoluteJ2000Sec,
                                                                 boolean alreadyInArc) {
        return isTargetObservableFromSatellite(
                satellite,
                target,
                sensorConfig,
                absoluteJ2000Sec,
                sunDirectionEci(absoluteJ2000Sec),
                alreadyInArc);
    }

    private static boolean isTargetObservableFromSatellite(Vector3 satellite,
                                                           Vector3 target,
                                                           SensorVisibilityConfig sensorConfig,
                                                           double absoluteJ2000Sec,
                                                           Vector3 sunDirectionEci,
                                                           boolean alreadyInArc) {
        if (satellite == null || target == null) {
            return false;
        }
        SensorVisibilityConfig config = normalizeSensorConfig(sensorConfig, 140.0);
        Vector3 lineOfSight = target.subtract(satellite);
        double range = lineOfSight.norm();
        if (range <= 0.0) {
            return false;
        }
        if (isPositiveFinite(config.maxDetectRangeM) && range > config.maxDetectRangeM) {
            return false;
        }

        Vector3 nadir = satellite.scale(-1.0);
        double angle = degrees(angleBetween(nadir, lineOfSight));
        double tolerance = alreadyInArc ? VISIBILITY_EXIT_HYSTERESIS_DEG : VISIBILITY_ENTER_TOLERANCE_DEG;
        if (!isInPayloadFieldOfView(angle, config, tolerance)) {
            return false;
        }

        // 太阳规避角对所有载荷类型（含对地监视/跟踪）生效，仅当 sunExclusionAngleDeg 设为0才关闭；
        // 若对地载荷按业务不应做太阳规避，会因此漏窗口，需确认是否为设计意图。
        if (isPositiveFinite(config.sunExclusionAngleDeg) && sunDirectionEci != null) {
            double sunAngle = degrees(angleBetween(lineOfSight, sunDirectionEci));
            if (sunAngle < config.sunExclusionAngleDeg - tolerance) {
                return false;
            }
        }
        return hasLineOfSightWgs84Ellipsoid(satellite, target, absoluteJ2000Sec);
    }

    /**
     * 按载荷分类做视场判定，switch 天然互斥——单次判定只走一个分支。
     * 多载荷场景下由外层循环为每台载荷传入各自的 config 逐台判定，
     * 因此对空/对地载荷可在同一次计算中分别出窗口（见 resolveSatellitePayloads）。
     * simpleConeHalfAngleDeg 为正时走旧版简单圆锥分支，忽略载荷分类。
     */
    private static boolean isInPayloadFieldOfView(double angleDeg,
                                                  SensorVisibilityConfig config,
                                                  double toleranceDeg) {
        if (isPositiveFinite(config.simpleConeHalfAngleDeg)) {
            return angleDeg <= config.simpleConeHalfAngleDeg + toleranceDeg;
        }
        PayloadType type = config.payloadType == null ? PayloadType.GROUND_SURVEILLANCE : config.payloadType;
        switch (type) {
            case GROUND_TRACKING:
                return angleDeg <= DEFAULT_GROUND_TRACKING_HALF_ANGLE_DEG + toleranceDeg;
            case AIR_SURVEILLANCE:
                return angleDeg >= DEFAULT_AIR_SURVEILLANCE_INNER_ANGLE_DEG - toleranceDeg
                        && angleDeg <= DEFAULT_AIR_SURVEILLANCE_OUTER_ANGLE_DEG + toleranceDeg;
            case AIR_TRACKING:
                return angleDeg >= DEFAULT_AIR_TRACKING_INNER_ANGLE_DEG - toleranceDeg
                        && angleDeg <= DEFAULT_AIR_TRACKING_OUTER_ANGLE_DEG + toleranceDeg;
            case GROUND_SURVEILLANCE:
            default:
                return angleDeg <= DEFAULT_GROUND_SURVEILLANCE_HALF_ANGLE_DEG + toleranceDeg;
        }
    }

    private static SensorVisibilityConfig normalizeSensorConfig(SensorVisibilityConfig sensorConfig,
                                                                double fallbackSensorFovDeg) {
        SensorVisibilityConfig source = sensorConfig == null
                ? SensorVisibilityConfig.legacySimpleCone(fallbackSensorFovDeg)
                : sensorConfig;
        SensorVisibilityConfig normalized = new SensorVisibilityConfig();
        normalized.payloadType = source.payloadType == null ? PayloadType.GROUND_SURVEILLANCE : source.payloadType;
        normalized.maxDetectRangeM = isPositiveFinite(source.maxDetectRangeM) ? source.maxDetectRangeM : Double.NaN;
        normalized.sunExclusionAngleDeg = isNonNegativeFinite(source.sunExclusionAngleDeg)
                ? source.sunExclusionAngleDeg
                : DEFAULT_SUN_EXCLUSION_ANGLE_DEG;
        normalized.simpleConeHalfAngleDeg = isPositiveFinite(source.simpleConeHalfAngleDeg)
                ? source.simpleConeHalfAngleDeg
                : Double.NaN;
        return normalized;
    }

    private static Vector3 sunDirectionEci(double absoluteJ2000Sec) {
        double jd = 2451545.0 + absoluteJ2000Sec / 86400.0;
        double n = jd - 2451545.0;
        double meanLon = Math.toRadians(normalizeDegrees(280.460 + 0.9856474 * n));
        double meanAnomaly = Math.toRadians(normalizeDegrees(357.528 + 0.9856003 * n));
        double eclipticLon = meanLon
                + Math.toRadians(1.915) * Math.sin(meanAnomaly)
                + Math.toRadians(0.020) * Math.sin(2.0 * meanAnomaly);
        double obliquity = Math.toRadians(23.439 - 0.0000004 * n);
        return new Vector3(
                Math.cos(eclipticLon),
                Math.cos(obliquity) * Math.sin(eclipticLon),
                Math.sin(obliquity) * Math.sin(eclipticLon));
    }

    private static boolean isPositiveFinite(double value) {
        return value > 0.0 && !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static boolean isNonNegativeFinite(double value) {
        return value >= 0.0 && !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static double normalizeDegrees(double degrees) {
        double value = degrees % 360.0;
        return value < 0.0 ? value + 360.0 : value;
    }

    /**
     * 地球遮挡判断：线段 satellite→target 上距地心最近的点是否在地球内部。
     * 若最近点距离 ≤ EARTH_MEAN_RADIUS_M，则视线被地球遮挡。
     */
    private static boolean hasLineOfSight(Vector3 satellite, Vector3 target) {
        Vector3 segment = target.subtract(satellite);
        double denom = segment.dot(segment);
        if (denom == 0.0) {
            return false;
        }
        double u = -satellite.dot(segment) / denom;
        if (u <= 1e-9 || u >= 1.0 - 1e-9) {
            return true;
        }
        Vector3 closest = satellite.add(segment.scale(u));
        return closest.norm() > EARTH_MEAN_RADIUS_M;
    }

    private static boolean hasLineOfSightWgs84Ellipsoid(Vector3 satelliteEci,
                                                        Vector3 targetEci,
                                                        double absoluteJ2000Sec) {
        Vector3 satellite = eciToEcefPos(satelliteEci, absoluteJ2000Sec);
        Vector3 target = eciToEcefPos(targetEci, absoluteJ2000Sec);
        Vector3 segment = target.subtract(satellite);
        double a = EARTH_EQUATORIAL_RADIUS_M;
        double b = a * (1.0 - EARTH_FLATTENING);
        double a2 = a * a;
        double b2 = b * b;

        double coefficientA = (segment.x * segment.x + segment.y * segment.y) / a2
                + segment.z * segment.z / b2;
        if (coefficientA == 0.0) {
            return false;
        }
        double coefficientB = 2.0 * ((satellite.x * segment.x + satellite.y * segment.y) / a2
                + satellite.z * segment.z / b2);
        double coefficientC = (satellite.x * satellite.x + satellite.y * satellite.y) / a2
                + satellite.z * satellite.z / b2
                - 1.0;
        double discriminant = coefficientB * coefficientB - 4.0 * coefficientA * coefficientC;
        if (discriminant <= 0.0) {
            return true;
        }
        double sqrt = Math.sqrt(discriminant);
        double u1 = (-coefficientB - sqrt) / (2.0 * coefficientA);
        double u2 = (-coefficientB + sqrt) / (2.0 * coefficientA);
        return !isInteriorSegmentParameter(u1) && !isInteriorSegmentParameter(u2);
    }

    private static boolean isInteriorSegmentParameter(double u) {
        return u > 1e-9 && u < 1.0 - 1e-9;
    }

    private static void validateSearchInputs(long numCalculations,
                                             long stepSizeSec,
                                             List<OrbitElements> satellites,
                                             List<BallisticPoint> ballisticPoints,
                                             double sensorFovDeg) {
        if (numCalculations <= 0L) {
            throw new IllegalArgumentException("计算次数必须大于0。");
        }
        if (stepSizeSec <= 0L) {
            throw new IllegalArgumentException("计算步长必须大于0秒。");
        }
        if (sensorFovDeg <= 0.0 || sensorFovDeg > 180.0) {
            throw new IllegalArgumentException("传感器视场角应在0到180度之间。");
        }
        if (ballisticPoints == null || ballisticPoints.isEmpty()) {
            throw new IllegalArgumentException("弹道点不能为空。");
        }
        if (satellites == null || satellites.isEmpty()) {
            throw new IllegalArgumentException("请至少输入一颗卫星。");
        }
        for (OrbitElements elements : satellites) {
            validateOrbit(elements);
        }
    }

    private static BallisticPoint interpolateAt(List<BallisticPoint> sorted, double t) {
        if (t <= sorted.get(0).tSec) {
            return copyWithTime(sorted.get(0), t);
        }
        for (int i = 0; i < sorted.size() - 1; i++) {
            BallisticPoint a = sorted.get(i);
            BallisticPoint b = sorted.get(i + 1);
            if (t <= b.tSec + 1e-9) {
                double span = b.tSec - a.tSec;
                double ratio = span <= 0.0 ? 0.0 : (t - a.tSec) / span;
                BallisticPoint point = new BallisticPoint();
                point.tSec = t;
                point.eciX = lerp(a.eciX, b.eciX, ratio);
                point.eciY = lerp(a.eciY, b.eciY, ratio);
                point.eciZ = lerp(a.eciZ, b.eciZ, ratio);
                point.eciVx = lerp(a.eciVx, b.eciVx, ratio);
                point.eciVy = lerp(a.eciVy, b.eciVy, ratio);
                point.eciVz = lerp(a.eciVz, b.eciVz, ratio);
                point.ecefX = lerp(a.ecefX, b.ecefX, ratio);
                point.ecefY = lerp(a.ecefY, b.ecefY, ratio);
                point.ecefZ = lerp(a.ecefZ, b.ecefZ, ratio);
                point.ecefVx = lerp(a.ecefVx, b.ecefVx, ratio);
                point.ecefVy = lerp(a.ecefVy, b.ecefVy, ratio);
                point.ecefVz = lerp(a.ecefVz, b.ecefVz, ratio);
                point.latDeg = lerp(a.latDeg, b.latDeg, ratio);
                point.lonDeg = lerp(a.lonDeg, b.lonDeg, ratio);
                point.heightM = lerp(a.heightM, b.heightM, ratio);
                point.speedMs = lerp(a.speedMs, b.speedMs, ratio);
                return point;
            }
        }
        return copyWithTime(sorted.get(sorted.size() - 1), t);
    }

    private static BallisticPoint copyWithTime(BallisticPoint source, double t) {
        BallisticPoint point = new BallisticPoint();
        point.tSec = t;
        point.eciX = source.eciX;
        point.eciY = source.eciY;
        point.eciZ = source.eciZ;
        point.eciVx = source.eciVx;
        point.eciVy = source.eciVy;
        point.eciVz = source.eciVz;
        point.ecefX = source.ecefX;
        point.ecefY = source.ecefY;
        point.ecefZ = source.ecefZ;
        point.ecefVx = source.ecefVx;
        point.ecefVy = source.ecefVy;
        point.ecefVz = source.ecefVz;
        point.latDeg = source.latDeg;
        point.lonDeg = source.lonDeg;
        point.heightM = source.heightM;
        point.speedMs = source.speedMs;
        return point;
    }

    private static List<BallisticPoint> sortedBallistic(List<BallisticPoint> points) {
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("弹道点不能为空。");
        }
        List<BallisticPoint> sorted = new ArrayList<BallisticPoint>(points);
        Collections.sort(sorted, new Comparator<BallisticPoint>() {
            @Override
            public int compare(BallisticPoint a, BallisticPoint b) {
                return Double.compare(a.tSec, b.tSec);
            }
        });
        return sorted;
    }

    private static double angleBetween(Vector3 a, Vector3 b) {
        double denom = a.norm() * b.norm();
        if (denom == 0.0) {
            return Math.PI;
        }
        double cosine = clamp(a.dot(b) / denom, -1.0, 1.0);
        return Math.acos(cosine);
    }

    private static void validateOrbit(OrbitElements elements) {
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

    private static void validatePositive(double value, String name) {
        if (value <= 0.0) {
            throw new IllegalArgumentException(name + "必须大于0。");
        }
    }

    private static double lerp(double a, double b, double ratio) {
        return a + (b - a) * ratio;
    }

    private static double radians(double degrees) {
        return Math.toRadians(degrees);
    }

    private static double degrees(double radians) {
        return Math.toDegrees(radians);
    }

    private static double normalizeRadians(double value) {
        double result = value % (2.0 * Math.PI);
        return result < 0.0 ? result + 2.0 * Math.PI : result;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double parseDouble(String text) {
        return Double.parseDouble(text.trim());
    }

    private static boolean isIntegerText(String value) {
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        String text = value.trim();
        int start = text.charAt(0) == '-' || text.charAt(0) == '+' ? 1 : 0;
        if (start >= text.length()) {
            return false;
        }
        for (int i = start; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String formatJsonNumber(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "null";
        }
        return String.format(Locale.US, "%.9f", value);
    }
}
