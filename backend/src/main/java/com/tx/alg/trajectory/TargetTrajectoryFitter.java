package com.tx.alg.trajectory;

import com.tx.alg.OrbitCalculator;
import com.runisys.orbit.spacevisibility.domain.orbit.BallisticCartesianPropagation;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 原目标轨迹拟合与模拟兼容入口，保留全部公开嵌套请求、样本和结果类型。
 *
 * <p>package102 点作为硬状态约束，以原二体RK4外推重建请求任务区间；
 * 精确观测时刻回填原样本，确保输出严格经过观测点。传播主体已抽入纯领域工具，
 * 插值、端点、事件求根及模拟专用动力学的原调用顺序保持。</p>
 */
public final class TargetTrajectoryFitter {
    public static final double PACKAGE102_POSITION_UNIT_M = 0.1;
    public static final double PACKAGE102_VELOCITY_UNIT_M_S = 0.0001;
    public static final double PACKAGE102_ACCELERATION_UNIT_M_S2 = 0.01;
    public static final double IMPACT_SOFT_CONSTRAINT_WEIGHT = 0.15;

    private static final double MIN_STEP_SEC = 1e-3;
    private static final double TIME_UNIQUE_EPS_SEC = 1e-5;
    private static final double TWO_BODY_MAX_STEP_SEC = 1.0;
    private static final double DEFAULT_EVENT_SEARCH_SEC = 6.0 * 3600.0;
    private static final double MAX_EVENT_SEARCH_SEC = 24.0 * 3600.0;
    private static final double EVENT_SEARCH_MARGIN_SEC = 600.0;
    private static final double EVENT_TIME_TOLERANCE_SEC = 1e-3;
    private static final double EVENT_SURFACE_HEIGHT_TOLERANCE_M = 0.0;
    private static final double EVENT_SECOND_SNAP_TOLERANCE_SEC = 0.5;
    private static final double EVENT_REFERENCE_SNAP_TOLERANCE_SEC = 2.0;
    private static final int ENDPOINT_VELOCITY_FIT_MAX_SAMPLES = 32;
    private static final double ENDPOINT_VELOCITY_FIT_MAX_SPAN_SEC = 120.0;
    private static final int ENDPOINT_VELOCITY_FIT_ITERATIONS = 4;
    private static final double ENDPOINT_VELOCITY_FIT_PERTURB_M_S = 0.05;
    private static final double ENDPOINT_VELOCITY_FIT_MAX_CORRECTION_M_S = 20.0;
    private static final double TARGET_ORBIT_VELOCITY_RESCALE = 1000.0;
    private static final double TARGET_ORBIT_ACCELERATION_RESCALE = 10.0;
    private static final double TARGET_ORBIT_MIN_UNIT_RATIO = 100.0;
    private static final double TARGET_ORBIT_MAX_UNIT_RATIO = 10000.0;
    private static final double SIMULATION_MIN_TOF_SEC = 60.0;
    private static final double SIMULATION_MAX_TOF_SEC = 7200.0;
    private static final double SIMULATION_APOGEE_RANGE_FACTOR = 1.9;
    private static final double SIMULATION_MIN_APOGEE_M = 120000.0;
    private static final double SIMULATION_MAX_APOGEE_M = 4000000.0;
    private static final double SIMULATION_RANGE_SPEED_M_S = 2200.0;
    private static final double SIMULATION_MIN_ENDPOINT_RADIUS_FACTOR = 0.75;
    private static final double SIMULATION_MAX_ENDPOINT_RADIUS_FACTOR = 20.0;
    private static final double SIMULATION_LAMBERT_TOLERANCE_M = 0.05;
    private static final int SIMULATION_LAMBERT_MAX_ITERATIONS = 12;
    private static final double SIMULATION_LAMBERT_PERTURB_M_S = 0.01;
    private static final double MAX_DIRECTION_ANGLE_DEG = 90.0;
    private static final double TURN_DOT_EPS = -1e-10;
    private static final double EARTH_MU = OrbitCalculator.EARTH_GRAVITATIONAL_PARAMETER;
    private static final double EARTH_J2 = OrbitCalculator.EARTH_J2;
    private static final double EARTH_RADIUS = OrbitCalculator.EARTH_EQUATORIAL_RADIUS_M;
    private static final DateTimeFormatter FILE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    private TargetTrajectoryFitter() {
    }

    /** 将最深原因格式化为原异常类型加消息；空原因仍使用原中文缺省文本。 */
    public static String describeFailure(Throwable throwable) {
        Throwable cause = deepestCause(throwable);
        String type = cause == null ? "未知异常" : cause.getClass().getSimpleName();
        String message = cause == null ? null : cause.getMessage();
        return type + ": " + (isBlank(message) ? "无详细错误信息" : message);
    }

    /** 在原失败描述后追加完整堆栈，供原调用方诊断，不改变异常链。 */
    public static String describeFailureWithStackTrace(Throwable throwable) {
        StringBuilder sb = new StringBuilder(describeFailure(throwable));
        if (throwable != null) {
            StringWriter writer = new StringWriter();
            throwable.printStackTrace(new PrintWriter(writer));
            sb.append('\n').append(writer.toString());
        }
        return sb.toString();
    }

    private static Throwable deepestCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    /** 原拟合/模拟模型标识，枚举名称直接进入结果JSON，保持不变。 */
    public enum TrajectoryModel {
        BOOST_POWERED_DYNAMIC,
        BALLISTIC_MIDCOURSE_TWO_BODY,
        BALLISTIC_TERMINAL_TWO_BODY,
        ORBITAL_TWO_BODY,
        AERODYNAMIC_GLIDE_DYNAMIC,
        CONSERVATIVE_FRAGMENT_DYNAMIC,
        LAUNCH_VEHICLE_DYNAMIC,
        UNKNOWN_CONSERVATIVE_DYNAMIC,
        BOOST_POWERED_HERMITE,
        BALLISTIC_MIDCOURSE_HERMITE,
        BALLISTIC_TERMINAL_HERMITE,
        ORBITAL_HERMITE,
        AERODYNAMIC_GLIDE_HERMITE,
        CONSERVATIVE_FRAGMENT_HERMITE,
        LAUNCH_VEHICLE_HERMITE,
        UNKNOWN_CONSERVATIVE_HERMITE,
        SYSTEM_SIMULATED_J2,
        SYSTEM_SIMULATED_BOOST,
        SYSTEM_SIMULATED_AERO,
        SYSTEM_SIMULATED_LAMBERT
    }

    /** Package102拟合请求。package102为原观测记录；taskStartTime/taskEndTime为UTC任务区间；interpolationStepSec默认1秒，outputDir为服务端目录。 */
    public static final class FitRequest {
        public String taskName = "task";
        public List<Package102Record> package102 = new ArrayList<Package102Record>();
        public String taskStartTime;
        public String taskEndTime;
        public double interpolationStepSec = 1.0;
        public String outputDir = ".";
    }

    /** 原102消息兼容记录。coordinateInfo/coordinate/coords和targetInfo/target按原优先级解析；扁平字段及tack3d历史拼写必须保留。位置/速度/加速度字符串默认单位分别0.1米、0.0001米每秒、0.01米每秒平方；空字段按原缺失规则处理。 */
    public static final class Package102Record {
        public CoordinateInfo coordinateInfo;
        public CoordinateInfo coordinate;
        public CoordinateInfo coords;
        public TargetInfo targetInfo;
        public TargetInfo target;

        public String tack3d_time_code;
        public String track3d_time_code;
        public String tack3d_tgt_pos_x;
        public String tack3d_tgt_pos_y;
        public String tack3d_tgt_pos_z;
        public String tack3d_tgt_vel_x;
        public String tack3d_tgt_vel_y;
        public String tack3d_tgt_vel_z;
        public String tack3d_tgt_acc_x;
        public String tack3d_tgt_acc_y;
        public String tack3d_tgt_acc_z;
        public String prop_tgt_type_8;
        public String prop_tgt_shape_8;
        public String prop_tgt_flight_phase_8;
        public String prob_tgt_critical_incident;
        public String prob_tgt_critical_incident_time_code;
        public String track_tgt_id;
        public String launch_time_code;
        public String launch_coord_frame;
        public String launch_pos_x;
        public String launch_pos_y;
        public String launch_pos_z;
        public String launch_pos_unit;
        public String impact_time_code;
        public String impact_coord_frame;
        public String impact_pos_x;
        public String impact_pos_y;
        public String impact_pos_z;
        public String impact_pos_unit;

        CoordinateInfo coordinate() {
            if (coordinateInfo != null) return coordinateInfo;
            if (coordinate != null) return coordinate;
            if (coords != null) return coords;
            CoordinateInfo c = new CoordinateInfo();
            c.tack3d_time_code = firstNonBlank(tack3d_time_code, track3d_time_code);
            c.track3d_time_code = track3d_time_code;
            c.tack3d_tgt_pos_x = tack3d_tgt_pos_x;
            c.tack3d_tgt_pos_y = tack3d_tgt_pos_y;
            c.tack3d_tgt_pos_z = tack3d_tgt_pos_z;
            c.tack3d_tgt_vel_x = tack3d_tgt_vel_x;
            c.tack3d_tgt_vel_y = tack3d_tgt_vel_y;
            c.tack3d_tgt_vel_z = tack3d_tgt_vel_z;
            c.tack3d_tgt_acc_x = tack3d_tgt_acc_x;
            c.tack3d_tgt_acc_y = tack3d_tgt_acc_y;
            c.tack3d_tgt_acc_z = tack3d_tgt_acc_z;
            return c;
        }

        TargetInfo target() {
            if (targetInfo != null) return targetInfo;
            if (target != null) return target;
            TargetInfo t = new TargetInfo();
            t.prop_tgt_type_8 = prop_tgt_type_8;
            t.prop_tgt_shape_8 = prop_tgt_shape_8;
            t.prop_tgt_flight_phase_8 = prop_tgt_flight_phase_8;
            t.prob_tgt_critical_incident = prob_tgt_critical_incident;
            t.prob_tgt_critical_incident_time_code = prob_tgt_critical_incident_time_code;
            t.track_tgt_id = track_tgt_id;
            return t;
        }
    }

    /** 102嵌套坐标字段；与扁平同名字段单位一致，时间为原UTC文本，track3d/tack3d别名保持。 */
    public static final class CoordinateInfo {
        public String tack3d_time_code;
        public String track3d_time_code;
        public String tack3d_tgt_pos_x;
        public String tack3d_tgt_pos_y;
        public String tack3d_tgt_pos_z;
        public String tack3d_tgt_vel_x;
        public String tack3d_tgt_vel_y;
        public String tack3d_tgt_vel_z;
        public String tack3d_tgt_acc_x;
        public String tack3d_tgt_acc_y;
        public String tack3d_tgt_acc_z;
    }

    /** 原目标类型、形状、飞行阶段和关键事件代码，原字符串参与模型选择与边界估计，不重新编码。 */
    public static final class TargetInfo {
        public String prop_tgt_type_8;
        public String prop_tgt_shape_8;
        public String prop_tgt_flight_phase_8;
        public String prob_tgt_critical_incident;
        public String prob_tgt_critical_incident_time_code;
        public String track_tgt_id;
    }

    /** 输入硬约束样本。absoluteTimeSec为旧UTC日历J2000秒；ECI位置/速度/加速度分别为米、米每秒、米每秒平方。originalConstraint标记原观测，sourceIndex保留输入顺序。 */
    public static final class TrajectorySample {
        public String targetId;
        public double absoluteTimeSec;
        public OrbitCalculator.Vector3 positionEciM;
        public OrbitCalculator.Vector3 velocityEciMs;
        public OrbitCalculator.Vector3 accelerationEciMs2;
        public TargetInfo targetInfo;
        public int sourceIndex;
        public boolean originalConstraint = true;
    }

    /** 输出轨迹点。absoluteTimeSec为绝对旧秒，relativeTimeSec为相对输出起点秒；ECI为原J2000，ECEF速度沿用STK固定坐标转换约定；位置米、速度米每秒，dtMs毫秒；original标记精确原始点。 */
    public static final class FittedPoint {
        public double absoluteTimeSec;
        public double relativeTimeSec;
        public OrbitCalculator.Vector3 positionEciM;
        public OrbitCalculator.Vector3 velocityEciMs;
        public OrbitCalculator.Vector3 accelerationEciMs2;
        public OrbitCalculator.Vector3 positionEcefM;
        public OrbitCalculator.Vector3 velocityEcefMs;
        public double energy;
        public double dtMs;
        public boolean original;
    }

    /** 单目标拟合结果；points按时间有序，warnings保留原生成次序，model名称作为结果合同。 */
    public static final class FittedTrajectory {
        public String targetId;
        public TrajectoryModel model;
        public List<FittedPoint> points = new ArrayList<FittedPoint>();
        public List<String> warnings = new ArrayList<String>();
    }

    /** 单目标CSV文件结果：服务端绝对csvPath、实际pointCount及原警告列表，不包含文件字节。 */
    public static final class FileResult {
        public String targetId;
        public String model;
        public String csvPath;
        public int pointCount;
        public List<String> warnings = new ArrayList<String>();
    }

    /** 原拟合/模拟/重定时共同返回体；taskName、targetCount、files字段及类型原样保留。 */
    public static final class FitRunResult {
        public String taskName;
        public int targetCount;
        public List<FileResult> files = new ArrayList<FileResult>();
    }

    /** 模拟兼容请求。起落点按launchPoint/impactPoint坐标标签和单位解析，UTC发射时刻必填；输出间隔秒，phaseEvents为分段动力学事件。默认值和旧模拟字段继续保留。 */
    public static final class SimulationRequest {
        public String taskName = "simulation";
        public String targetId = "SIM-TGT";
        public String taskStartTime;
        public String taskEndTime;
        public String launchTimeUtc;
        public String impactTimeUtc;
        public String impactTime;
        public double launchPointX = 2167790.227;
        public double launchPointY = 4775481.109;
        public double launchPointZ = 3618557.253;
        public String launchPointCoordFrame = "J2000";
        public String launchPointUnit = "m";
        public double impactPointX = 3578635.760;
        public double impactPointY = 3522525.227;
        public double impactPointZ = 3919735.591;
        public String impactPointCoordFrame = "J2000";
        public String impactPointUnit = "m";
        public double outputPointDurationSec = 1.0;
        public List<PhaseEvent> phaseEvents = new ArrayList<PhaseEvent>();

        /*
         * 保留旧模拟字段以兼容JSON/API；端点约束的系统模拟路径不再使用这些字段。
         * 原界面只展示任务、目标、端点、发射时刻和输出间隔。
         */
        public String targetTypeCode = "0x01";
        public String targetShapeCode = "0x02";
        public String flightPhaseCode = "0x01";
        public double launchLatDeg;
        public double launchLonDeg;
        public double launchHeightM;
        public double initialSpeedMs = 1500.0;
        public double azimuthDeg = 90.0;
        public double elevationDeg = 45.0;
        public double durationSec = 600.0;
        public double integrationStepSec = 0.1;
        public double outputStepSec = 1.0;
        public double boostDurationSec = 80.0;
        public double boostAccelerationMs2 = 18.0;
        public double dragCdAreaOverMass = 0.0;
        public String outputDir = ".";
    }

    /** CSV重定时请求：保留地固轨迹，按新UTC发射时刻重算J2000状态；trajectoryCsvPath/outputDir均为服务端路径。 */
    public static final class RetimeTrajectoryRequest {
        public String taskName = "retimeTrajectory";
        public String targetId = "RETIMED-TGT";
        public String trajectoryCsvPath;
        public String launchTimeUtc;
        public String outputDir = ".";
    }

    /** 分段事件：phaseType/eventType及时间文本按原规则解析；accelerationMs2、deltaVms为SI单位，升阻比和阻力面积质量比保留原默认0。 */
    public static final class PhaseEvent {
        public String phaseType;
        public String eventType;
        public String startTime;
        public String endTime;
        public String eventTime;
        public String dynamics;
        public String directionMode;
        public double accelerationMs2;
        public double deltaVms;
        public double liftDragRatio;
        public double dragCdAreaOverMass;
    }

    private static final class FittingPolicy {
        final TrajectoryModel model;
        final double suppliedVelocityWeight;
        final double suppliedAccelerationWeight;
        final double tangentLimit;
        final double accelerationLimit;

        FittingPolicy(TrajectoryModel model,
                      double suppliedVelocityWeight,
                      double suppliedAccelerationWeight,
                      double tangentLimit,
                      double accelerationLimit) {
            this.model = model;
            this.suppliedVelocityWeight = suppliedVelocityWeight;
            this.suppliedAccelerationWeight = suppliedAccelerationWeight;
            this.tangentLimit = tangentLimit;
            this.accelerationLimit = accelerationLimit;
        }

        FittingPolicy scaled(double scale) {
            return new FittingPolicy(
                    model,
                    suppliedVelocityWeight,
                    suppliedAccelerationWeight,
                    tangentLimit * scale,
                    accelerationLimit * scale * scale
            );
        }
    }

    private static final class ValidationResult {
        boolean ok = true;
        String message;
    }

    private static final class State {
        final OrbitCalculator.Vector3 r;
        final OrbitCalculator.Vector3 v;

        State(OrbitCalculator.Vector3 r, OrbitCalculator.Vector3 v) {
            this.r = r;
            this.v = v;
        }
    }

    private static final class SimulationEndpointPlan {
        double timeOfFlightSec;
        double impactTimeSec;
        OrbitCalculator.Vector3 impactPosition;
        OrbitCalculator.Vector3 initialVelocity;
        boolean impactTimeProvided;
    }

    private static final class LambertSolution {
        final OrbitCalculator.Vector3 initialVelocity;
        final OrbitCalculator.Vector3 finalVelocity;

        LambertSolution(OrbitCalculator.Vector3 initialVelocity,
                        OrbitCalculator.Vector3 finalVelocity) {
            this.initialVelocity = initialVelocity;
            this.finalVelocity = finalVelocity;
        }
    }

    private static final class Bracket {
        final double lo;
        final double hi;

        Bracket(double lo, double hi) {
            this.lo = lo;
            this.hi = hi;
        }
    }

    private static final class CompleteTrajectoryInput {
        List<TrajectorySample> samples;
        double outputStartSec;
        double outputEndSec;
        String outputStartLabel;
        String outputEndLabel;
        List<String> warnings = new ArrayList<String>();
    }

    private static final class BoundaryInfo {
        boolean present;
        double timeSec;
        String timeText;
        boolean secondPrecision;
        OrbitCalculator.Vector3 positionEciM;
        String frame;
        String unit;
    }

    private static final class PhysicalWindow {
        double launchSec;
        double impactSec;
        State launchState;
        State impactState;
        List<String> warnings = new ArrayList<String>();
    }

    private static final class EventEstimate {
        boolean found;
        double timeSec;
        State state;
        double heightM;
    }

    /**
     * 原拟合入口在创建目录前执行的三项校验，单独供 Web 编排在分配结果作用域前复用。
     * 校验次序、原错误文本及 normalizeStep 阈值保持原样，不在应用层复制数值规则。
     */
    public static void validateFitRequest(FitRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("拟合请求不能为空。");
        }
        if (request.package102 == null || request.package102.isEmpty()) {
            throw new IllegalArgumentException("package102数据不能为空。");
        }
        normalizeStep(request.interpolationStepSec);
    }

    /** 按目标分组解析102观测，估计物理起落边界、保持原点硬约束，再输出原CSV格式；任务区间和单位校验按原次序执行。 */
    public static FitRunResult fitPackage102ToCsv(FitRequest request) throws IOException {
        validateFitRequest(request);
        double stepSec = normalizeStep(request.interpolationStepSec);
        String taskName = blankToDefault(request.taskName, "task");
        Path outputDir = Paths.get(blankToDefault(request.outputDir, "."));
        Files.createDirectories(outputDir);

        Map<String, List<TrajectorySample>> byTarget = new LinkedHashMap<String, List<TrajectorySample>>();
        Map<String, List<Package102Record>> recordsByTarget = new LinkedHashMap<String, List<Package102Record>>();
        for (int i = 0; i < request.package102.size(); i++) {
            Package102Record record = request.package102.get(i);
            TrajectorySample sample = toSample(record, i);
            List<TrajectorySample> list = byTarget.get(sample.targetId);
            if (list == null) {
                list = new ArrayList<TrajectorySample>();
                byTarget.put(sample.targetId, list);
            }
            list.add(sample);
            List<Package102Record> records = recordsByTarget.get(sample.targetId);
            if (records == null) {
                records = new ArrayList<Package102Record>();
                recordsByTarget.put(sample.targetId, records);
            }
            records.add(record);
        }

        FitRunResult run = new FitRunResult();
        run.taskName = taskName;
        run.targetCount = byTarget.size();
        String created = LocalDateTime.now().format(FILE_TIME_FORMAT);
        double taskStartSec = parseOptionalTimeCode(request.taskStartTime);
        double taskEndSec = parseOptionalTimeCode(request.taskEndTime);
        for (Map.Entry<String, List<TrajectorySample>> entry : byTarget.entrySet()) {
            CompleteTrajectoryInput complete = completeTrajectoryInput(
                    entry.getValue(),
                    recordsByTarget.get(entry.getKey()),
                    taskStartSec,
                    taskEndSec
            );
            FittedTrajectory trajectory = fitSamples(
                    complete.samples,
                    stepSec,
                    complete.outputStartSec,
                    complete.outputEndSec
            );
            trajectory.warnings.addAll(0, complete.warnings);
            Path csv = writeTrajectoryCsv(
                    trajectory,
                    outputDir,
                    taskName,
                    complete.outputStartLabel,
                    complete.outputEndLabel,
                    created
            );
            FileResult file = new FileResult();
            file.targetId = trajectory.targetId;
            file.model = trajectory.model.name();
            file.csvPath = csv.toAbsolutePath().normalize().toString();
            file.pointCount = trajectory.points.size();
            file.warnings.addAll(trajectory.warnings);
            run.files.add(file);
        }
        return run;
    }

    /** 保留观测硬约束并补充起落边界；软落点约束与原观测分别标记，避免把估计点当成原始样本。 */
    private static CompleteTrajectoryInput completeTrajectoryInput(List<TrajectorySample> observed,
                                                                   List<Package102Record> records,
                                                                   double taskStartSec,
                                                                   double taskEndSec) {
        if (observed == null || observed.size() < 2) {
            throw new IllegalArgumentException("每个目标至少需要两个已观测的package102轨迹点。");
        }
        List<TrajectorySample> observedSamples = new ArrayList<TrajectorySample>(observed);
        sortSamplesByTime(observedSamples);
        validateSamples(observedSamples);
        List<String> unitWarnings = normalizePackage102StateUnits(observedSamples);
        TrajectorySample firstObserved = observedSamples.get(0);
        TrajectorySample lastObserved = observedSamples.get(observedSamples.size() - 1);
        BoundaryInfo launch = boundaryInfo(records, true);
        BoundaryInfo impact = boundaryInfo(records, false);
        validateTaskWindowCoversObservedIfPresent(taskStartSec, taskEndSec, firstObserved, lastObserved);
        PhysicalWindow physicalWindow = estimatePhysicalWindow(observedSamples, launch, impact, taskStartSec, taskEndSec);

        CompleteTrajectoryInput complete = new CompleteTrajectoryInput();
        complete.warnings.addAll(unitWarnings);
        complete.warnings.addAll(physicalWindow.warnings);
        complete.samples = new ArrayList<TrajectorySample>();
        complete.samples.addAll(observedSamples);
        complete.outputStartSec = physicalWindow.launchSec;
        complete.outputEndSec = physicalWindow.impactSec;
        complete.outputStartLabel = isFinite(taskStartSec) ? labelTime(taskStartSec) : labelTime(complete.outputStartSec);
        complete.outputEndLabel = isFinite(taskEndSec) ? labelTime(taskEndSec) : labelTime(complete.outputEndSec);
        validateOutputWindowCoversObserved(complete.outputStartSec, complete.outputEndSec, firstObserved, lastObserved);

        if (launch.present) {
            if (launch.timeSec > firstObserved.absoluteTimeSec + TIME_UNIQUE_EPS_SEC) {
                complete.warnings.add("launch_time_code晚于第一个package102轨迹点，仅作为无效参考保留。发射时间="
                        + labelTime(launch.timeSec) + ", 第一个package102时间=" + labelTime(firstObserved.absoluteTimeSec));
            } else {
                double miss = physicalWindow.launchState == null ? Double.NaN
                        : physicalWindow.launchState.r.subtract(launch.positionEciM).norm();
                complete.warnings.add("launch_time_code和发射点位置仅作为参考使用。推算发射时间="
                        + labelTime(complete.outputStartSec)
                        + ", 参考发射时间=" + labelTime(launch.timeSec)
                        + ", 时间差秒=" + String.format(Locale.US, "%.3f", complete.outputStartSec - launch.timeSec)
                        + ", 位置偏差米=" + formatOptionalDistance(miss)
                        + ", 坐标系=" + blankToDefault(launch.frame, "J2000")
                        + ", 单位=" + blankToDefault(launch.unit, "0.1m"));
            }
        }

        if (impact.present) {
            if (impact.timeSec < lastObserved.absoluteTimeSec - TIME_UNIQUE_EPS_SEC) {
                complete.warnings.add("impact_time_code早于最后一个package102轨迹点，仅作为无效参考保留。落点时间="
                        + labelTime(impact.timeSec) + ", 最后一个package102时间=" + labelTime(lastObserved.absoluteTimeSec));
            } else {
                double miss = physicalWindow.impactState == null ? Double.NaN
                        : physicalWindow.impactState.r.subtract(impact.positionEciM).norm();
                complete.warnings.add("impact_time_code和落点位置仅作为参考使用。推算落点时间="
                        + labelTime(complete.outputEndSec)
                        + ", 参考落点时间=" + labelTime(impact.timeSec)
                        + ", 时间差秒=" + String.format(Locale.US, "%.3f", complete.outputEndSec - impact.timeSec)
                        + ", 位置偏差米=" + formatOptionalDistance(miss)
                        + ", 坐标系=" + blankToDefault(impact.frame, "J2000")
                        + ", 单位=" + blankToDefault(impact.unit, "0.1m"));
            }
        }

        Collections.sort(complete.samples, new Comparator<TrajectorySample>() {
            @Override
            public int compare(TrajectorySample a, TrajectorySample b) {
                return Double.compare(a.absoluteTimeSec, b.absoluteTimeSec);
            }
        });
        return complete;
    }

    private static void validateTaskWindowCoversObservedIfPresent(double taskStartSec,
                                                                  double taskEndSec,
                                                                  TrajectorySample firstObserved,
                                                                  TrajectorySample lastObserved) {
        if (isFinite(taskStartSec) && isFinite(taskEndSec)
                && taskEndSec <= taskStartSec + TIME_UNIQUE_EPS_SEC) {
            throw new IllegalArgumentException("taskEndTime必须晚于taskStartTime。");
        }
        if (isFinite(taskStartSec)
                && taskStartSec > firstObserved.absoluteTimeSec + TIME_UNIQUE_EPS_SEC) {
            throw new IllegalArgumentException("taskStartTime必须早于或等于第一个package102轨迹点时间。taskStart="
                    + labelTime(taskStartSec) + ", 第一个package102时间=" + labelTime(firstObserved.absoluteTimeSec));
        }
        if (isFinite(taskEndSec)
                && taskEndSec < lastObserved.absoluteTimeSec - TIME_UNIQUE_EPS_SEC) {
            throw new IllegalArgumentException("taskEndTime必须晚于或等于最后一个package102轨迹点时间。taskEnd="
                    + labelTime(taskEndSec) + ", 最后一个package102时间=" + labelTime(lastObserved.absoluteTimeSec));
        }
    }

    /** 从首尾观测状态沿二体轨迹估计地面交点，保留搜索上限、任务边界和原警告策略。 */
    private static PhysicalWindow estimatePhysicalWindow(List<TrajectorySample> observedSamples,
                                                         BoundaryInfo launch,
                                                         BoundaryInfo impact,
                                                         double taskStartSec,
                                                         double taskEndSec) {
        TrajectorySample first = observedSamples.get(0);
        TrajectorySample last = observedSamples.get(observedSamples.size() - 1);
        PhysicalWindow window = new PhysicalWindow();

        double launchHorizon = eventSearchHorizon(first.absoluteTimeSec, launch, taskStartSec);
        EventEstimate launchEvent = estimateGroundCrossing(observedSamples, 0, true, launchHorizon);
        if (launchEvent.found && launchEvent.timeSec <= first.absoluteTimeSec + TIME_UNIQUE_EPS_SEC) {
            double launchSec = normalizeEstimatedEventTime(launchEvent.timeSec, launch);
            window.launchSec = launchSec;
            window.launchState = propagateTwoBody(stateForPropagation(observedSamples, 0), launchSec - first.absoluteTimeSec);
            window.warnings.add("已根据package102状态通过二体反向地面交点推算发射时间。推算发射时间="
                    + labelTime(window.launchSec)
                    + ", 第一个package102时间=" + labelTime(first.absoluteTimeSec)
                    + ", 反推秒数=" + String.format(Locale.US, "%.3f", first.absoluteTimeSec - window.launchSec));
        } else if (launch.present && launch.timeSec <= first.absoluteTimeSec + TIME_UNIQUE_EPS_SEC) {
            window.launchSec = launch.timeSec;
            window.launchState = propagateTwoBody(stateForPropagation(observedSamples, 0), window.launchSec - first.absoluteTimeSec);
            window.warnings.add("在搜索范围内未能推算发射地面交点，使用launch_time_code作为兜底参考。发射时间="
                    + labelTime(window.launchSec));
        } else {
            window.launchSec = first.absoluteTimeSec;
            window.launchState = stateForPropagation(observedSamples, 0);
            window.warnings.add("在搜索范围内未能推算发射地面交点，输出从第一个package102轨迹点开始。第一个package102时间="
                    + labelTime(first.absoluteTimeSec));
        }

        double impactHorizon = eventSearchHorizon(last.absoluteTimeSec, impact, taskEndSec);
        EventEstimate impactEvent = estimateGroundCrossing(observedSamples, observedSamples.size() - 1, false, impactHorizon);
        if (impactEvent.found && impactEvent.timeSec >= last.absoluteTimeSec - TIME_UNIQUE_EPS_SEC) {
            double impactSec = normalizeEstimatedEventTime(impactEvent.timeSec, impact);
            window.impactSec = impactSec;
            window.impactState = propagateTwoBody(stateForPropagation(observedSamples, observedSamples.size() - 1), impactSec - last.absoluteTimeSec);
            window.warnings.add("已根据package102状态通过二体正向地面交点推算落点时间。推算落点时间="
                    + labelTime(window.impactSec)
                    + ", 最后一个package102时间=" + labelTime(last.absoluteTimeSec)
                    + ", 正推秒数=" + String.format(Locale.US, "%.3f", window.impactSec - last.absoluteTimeSec));
        } else if (impact.present && impact.timeSec >= last.absoluteTimeSec - TIME_UNIQUE_EPS_SEC && impact.secondPrecision) {
            window.impactSec = impact.timeSec;
            window.impactState = propagateTwoBody(stateForPropagation(observedSamples, observedSamples.size() - 1), window.impactSec - last.absoluteTimeSec);
            window.warnings.add("在搜索范围内未能推算落地交点，使用秒级精度impact_time_code作为兜底参考。落点时间="
                    + labelTime(window.impactSec));
        } else {
            window.impactSec = last.absoluteTimeSec;
            window.impactState = stateForPropagation(observedSamples, observedSamples.size() - 1);
            window.warnings.add("在搜索范围内未能推算落地交点，输出到最后一个package102轨迹点结束。最后一个package102时间="
                    + labelTime(last.absoluteTimeSec));
        }

        if (isFinite(taskStartSec) && Math.abs(window.launchSec - taskStartSec) > TIME_UNIQUE_EPS_SEC) {
            window.warnings.add("taskStartTime是任务/文件时间，不是物理发射时间。任务开始时间="
                    + labelTime(taskStartSec) + ", 推算发射时间=" + labelTime(window.launchSec));
        }
        if (isFinite(taskEndSec) && Math.abs(window.impactSec - taskEndSec) > TIME_UNIQUE_EPS_SEC) {
            window.warnings.add("taskEndTime是任务/文件时间，不是物理落点时间。任务结束时间="
                    + labelTime(taskEndSec) + ", 推算落点时间=" + labelTime(window.impactSec));
        }
        return window;
    }

    private static double eventSearchHorizon(double anchorTimeSec, BoundaryInfo boundary, double taskBoundarySec) {
        double horizon = DEFAULT_EVENT_SEARCH_SEC;
        if (boundary.present) {
            horizon = Math.max(horizon, Math.abs(anchorTimeSec - boundary.timeSec) + EVENT_SEARCH_MARGIN_SEC);
        }
        if (isFinite(taskBoundarySec)) {
            horizon = Math.max(horizon, Math.abs(anchorTimeSec - taskBoundarySec) + EVENT_SEARCH_MARGIN_SEC);
        }
        return Math.min(MAX_EVENT_SEARCH_SEC, Math.max(TWO_BODY_MAX_STEP_SEC, horizon));
    }

    /** 按原方向逐步搜索高度符号变化；单步仍调用同一抽取RK4，保留事件搜索节点和时间容差。 */
    private static EventEstimate estimateGroundCrossing(List<TrajectorySample> samples,
                                                        int anchorIndex,
                                                        boolean backward,
                                                        double maxDurationSec) {
        TrajectorySample anchor = samples.get(anchorIndex);
        State previousState = stateForPropagation(samples, anchorIndex);
        double previousTime = anchor.absoluteTimeSec;
        double previousHeight = heightAboveSurfaceM(previousState, previousTime);
        EventEstimate estimate = new EventEstimate();
        if (previousHeight <= EVENT_SURFACE_HEIGHT_TOLERANCE_M) {
            estimate.found = true;
            estimate.timeSec = previousTime;
            estimate.state = previousState;
            estimate.heightM = previousHeight;
            return estimate;
        }

        double direction = backward ? -1.0 : 1.0;
        double elapsed = 0.0;
        while (elapsed < maxDurationSec - 1e-9) {
            double step = Math.min(TWO_BODY_MAX_STEP_SEC, maxDurationSec - elapsed);
            State currentState = rk4TwoBodyStep(previousState, direction * step);
            double currentTime = previousTime + direction * step;
            double currentHeight = heightAboveSurfaceM(currentState, currentTime);
            if (currentHeight <= EVENT_SURFACE_HEIGHT_TOLERANCE_M) {
                return refineGroundCrossing(previousTime, previousState, currentTime, currentState);
            }
            previousState = currentState;
            previousTime = currentTime;
            previousHeight = currentHeight;
            elapsed += step;
        }
        estimate.found = false;
        estimate.timeSec = previousTime;
        estimate.state = previousState;
        estimate.heightM = previousHeight;
        return estimate;
    }

    /** 地面事件括区间内求根，保留原迭代顺序和毫秒级时间停止条件，不改为新积分模型。 */
    private static EventEstimate refineGroundCrossing(double aboveTime,
                                                      State aboveState,
                                                      double belowTime,
                                                      State belowState) {
        double lowTime = belowTime;
        State lowState = belowState;
        double highTime = aboveTime;
        State highState = aboveState;
        for (int i = 0; i < 60 && Math.abs(highTime - lowTime) > EVENT_TIME_TOLERANCE_SEC; i++) {
            double midTime = 0.5 * (highTime + lowTime);
            State midState = propagateTwoBody(highState, midTime - highTime);
            double midHeight = heightAboveSurfaceM(midState, midTime);
            if (midHeight <= EVENT_SURFACE_HEIGHT_TOLERANCE_M) {
                lowTime = midTime;
                lowState = midState;
            } else {
                highTime = midTime;
                highState = midState;
            }
        }
        EventEstimate estimate = new EventEstimate();
        estimate.found = true;
        estimate.timeSec = 0.5 * (highTime + lowTime);
        estimate.state = propagateTwoBody(highState, estimate.timeSec - highTime);
        estimate.heightM = heightAboveSurfaceM(estimate.state, estimate.timeSec);
        return estimate;
    }

    private static double heightAboveSurfaceM(State state, double timeSec) {
        return OrbitCalculator.ecefToGeodetic(OrbitCalculator.eciToEcefPos(state.r, timeSec))[2];
    }

    /** 按原秒与参考事件接近阈值吸附估计时刻，保持原落点/发射时间兼容口径。 */
    private static double normalizeEstimatedEventTime(double inferredTimeSec, BoundaryInfo boundary) {
        if (boundary.present && boundary.secondPrecision
                && Math.abs(inferredTimeSec - boundary.timeSec) <= EVENT_REFERENCE_SNAP_TOLERANCE_SEC) {
            return boundary.timeSec;
        }
        double rounded = Math.rint(inferredTimeSec);
        if (Math.abs(inferredTimeSec - rounded) <= EVENT_SECOND_SNAP_TOLERANCE_SEC) {
            return rounded;
        }
        return inferredTimeSec;
    }

    private static String formatOptionalDistance(double value) {
        if (!isFinite(value)) {
            return "NaN";
        }
        return String.format(Locale.US, "%.3f", value);
    }

    private static double outputStartTime(double taskStartSec,
                                          BoundaryInfo launch,
                                          TrajectorySample firstObserved) {
        if (isFinite(taskStartSec)) {
            return taskStartSec;
        }
        if (launch.present && launch.timeSec <= firstObserved.absoluteTimeSec + TIME_UNIQUE_EPS_SEC) {
            return launch.timeSec;
        }
        return firstObserved.absoluteTimeSec;
    }

    private static double outputEndTime(double taskEndSec,
                                        BoundaryInfo impact,
                                        TrajectorySample lastObserved) {
        if (isFinite(taskEndSec)) {
            return taskEndSec;
        }
        if (impact.present && impact.timeSec >= lastObserved.absoluteTimeSec - TIME_UNIQUE_EPS_SEC) {
            return impact.timeSec;
        }
        return lastObserved.absoluteTimeSec;
    }

    private static void validateOutputWindowCoversObserved(double outputStartSec,
                                                           double outputEndSec,
                                                           TrajectorySample firstObserved,
                                                           TrajectorySample lastObserved) {
        if (!isFinite(outputStartSec) || !isFinite(outputEndSec)) {
            throw new IllegalArgumentException("taskStartTime和taskEndTime必须构成有效的输出时间窗口。");
        }
        if (outputEndSec <= outputStartSec + TIME_UNIQUE_EPS_SEC) {
            throw new IllegalArgumentException("taskEndTime必须晚于taskStartTime。");
        }
        if (outputStartSec > firstObserved.absoluteTimeSec + TIME_UNIQUE_EPS_SEC) {
            throw new IllegalArgumentException("taskStartTime必须早于或等于第一个package102轨迹点时间。taskStart="
                    + labelTime(outputStartSec) + ", 第一个package102时间=" + labelTime(firstObserved.absoluteTimeSec));
        }
        if (outputEndSec < lastObserved.absoluteTimeSec - TIME_UNIQUE_EPS_SEC) {
            throw new IllegalArgumentException("taskEndTime必须晚于或等于最后一个package102轨迹点时间。taskEnd="
                    + labelTime(outputEndSec) + ", 最后一个package102时间=" + labelTime(lastObserved.absoluteTimeSec));
        }
    }

    private static TrajectorySample boundarySample(BoundaryInfo boundary,
                                                   TrajectorySample reference,
                                                   int sourceIndex) {
        TrajectorySample sample = new TrajectorySample();
        sample.targetId = reference.targetId;
        sample.targetInfo = reference.targetInfo;
        sample.sourceIndex = sourceIndex;
        sample.absoluteTimeSec = boundary.timeSec;
        sample.positionEciM = boundary.positionEciM;
        sample.velocityEciMs = zero();
        sample.accelerationEciMs2 = zero();
        sample.originalConstraint = false;
        return sample;
    }

    private static TrajectorySample softImpactSample(BoundaryInfo impact,
                                                     List<TrajectorySample> observedSamples,
                                                     int sourceIndex) {
        TrajectorySample last = observedSamples.get(observedSamples.size() - 1);
        double dt = impact.timeSec - last.absoluteTimeSec;
        OrbitCalculator.Vector3 velocity = endpointVelocity(observedSamples, true);
        OrbitCalculator.Vector3 acceleration = isUseful(last.accelerationEciMs2) ? last.accelerationEciMs2 : zero();
        OrbitCalculator.Vector3 predicted = last.positionEciM
                .add(velocity.scale(dt))
                .add(acceleration.scale(0.5 * dt * dt));
        OrbitCalculator.Vector3 position = blend(predicted, impact.positionEciM, IMPACT_SOFT_CONSTRAINT_WEIGHT);
        TrajectorySample sample = new TrajectorySample();
        sample.targetId = last.targetId;
        sample.targetInfo = last.targetInfo;
        sample.sourceIndex = sourceIndex;
        sample.absoluteTimeSec = impact.timeSec;
        sample.positionEciM = position;
        sample.velocityEciMs = dt > 1e-9 ? position.subtract(last.positionEciM).scale(1.0 / dt) : velocity;
        sample.accelerationEciMs2 = zero();
        sample.originalConstraint = false;
        return sample;
    }

    private static OrbitCalculator.Vector3 endpointVelocity(List<TrajectorySample> samples, boolean lastEndpoint) {
        int n = samples.size();
        TrajectorySample endpoint = lastEndpoint ? samples.get(n - 1) : samples.get(0);
        if (isUseful(endpoint.velocityEciMs)) {
            return endpoint.velocityEciMs;
        }
        if (n < 2) {
            return zero();
        }
        TrajectorySample a = lastEndpoint ? samples.get(n - 2) : samples.get(0);
        TrajectorySample b = lastEndpoint ? samples.get(n - 1) : samples.get(1);
        double dt = b.absoluteTimeSec - a.absoluteTimeSec;
        if (Math.abs(dt) < 1e-9) {
            return zero();
        }
        return b.positionEciM.subtract(a.positionEciM).scale(1.0 / dt);
    }

    /** 原样本拟合入口：按时间排序及校验，原时刻硬约束回填，区间内插值、区间外原二体RK4；未提供边界时用观测首尾时刻。 */
    public static FittedTrajectory fitSamples(List<TrajectorySample> input, double interpolationStepSec) {
        return fitSamples(input, interpolationStepSec, Double.NaN, Double.NaN);
    }

    /** 原样本拟合入口：按时间排序及校验，原时刻硬约束回填，区间内插值、区间外原二体RK4；未提供边界时用观测首尾时刻。 */
    public static FittedTrajectory fitSamples(List<TrajectorySample> input,
                                              double interpolationStepSec,
                                              double outputStartSec,
                                              double outputEndSec) {
        if (input == null || input.size() < 2) {
            throw new IllegalArgumentException("至少需要两个三维轨迹点。");
        }
        double stepSec = normalizeStep(interpolationStepSec);
        List<TrajectorySample> samples = new ArrayList<TrajectorySample>(input);
        sortSamplesByTime(samples);
        validateSamples(samples);
        TargetInfo targetInfo = samples.get(0).targetInfo;
        FittingPolicy basePolicy = policyFor(targetInfo);
        double firstSampleTime = samples.get(0).absoluteTimeSec;
        double lastSampleTime = samples.get(samples.size() - 1).absoluteTimeSec;
        double requestedStart = isFinite(outputStartSec) ? outputStartSec : firstSampleTime;
        double requestedEnd = isFinite(outputEndSec) ? outputEndSec : lastSampleTime;
        validateOutputTimeRange(requestedStart, requestedEnd, firstSampleTime, lastSampleTime);

        return generateTrajectory(samples, stepSec, basePolicy, requestedStart, requestedEnd);
    }

    /** 按原端点约束模拟并输出CSV；只替换所共用的二体传播计算主体，模拟专用动力学及输出命名保持。 */
    public static FitRunResult simulateToCsv(SimulationRequest request) throws IOException {
        if (request == null) {
            throw new IllegalArgumentException("系统模拟请求不能为空。");
        }
        double outputStepSec = normalizeStep(firstPositive(request.outputPointDurationSec, request.outputStepSec, 1.0));
        if (request.launchTimeUtc == null || request.launchTimeUtc.trim().isEmpty()) {
            throw new IllegalArgumentException("launchTimeUtc不能为空。");
        }

        Path outputDir = Paths.get(blankToDefault(request.outputDir, "."));
        Files.createDirectories(outputDir);
        FittedTrajectory trajectory = simulate(request, outputStepSec);
        String created = LocalDateTime.now().format(FILE_TIME_FORMAT);
        Path csv = writeTrajectoryCsv(
                trajectory,
                outputDir,
                blankToDefault(request.taskName, "simulation"),
                firstNonBlank(request.taskStartTime, request.launchTimeUtc),
                firstNonBlank(request.taskEndTime, request.launchTimeUtc),
                created
        );

        FitRunResult result = new FitRunResult();
        result.taskName = blankToDefault(request.taskName, "simulation");
        result.targetCount = 1;
        FileResult file = new FileResult();
        file.targetId = trajectory.targetId;
        file.model = trajectory.model.name();
        file.csvPath = csv.toAbsolutePath().normalize().toString();
        file.pointCount = trajectory.points.size();
        file.warnings.addAll(trajectory.warnings);
        result.files.add(file);
        return result;
    }

    /** 读取原20列CSV，保持每行ECEF位置/速度和相对时间，平移UTC发射历元并重算ECI列；严格保留原列识别及失败文本。 */
    public static FitRunResult retimeTrajectoryToCsv(RetimeTrajectoryRequest request) throws IOException {
        if (request == null) {
            throw new IllegalArgumentException("弹道文件重定时请求不能为空。");
        }
        if (isBlank(request.trajectoryCsvPath)) {
            throw new IllegalArgumentException("trajectoryCsvPath不能为空。");
        }
        if (isBlank(request.launchTimeUtc)) {
            throw new IllegalArgumentException("launchTimeUtc不能为空。");
        }

        Path sourceCsv = Paths.get(request.trajectoryCsvPath.trim());
        if (!Files.exists(sourceCsv)) {
            throw new IllegalArgumentException("弹道CSV文件不存在：" + sourceCsv);
        }
        Path outputDir = Paths.get(blankToDefault(request.outputDir, "."));
        Files.createDirectories(outputDir);

        List<String> lines = Files.readAllLines(sourceCsv, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("弹道CSV文件为空：" + sourceCsv);
        }

        String[] header = splitCsvLine(lines.get(0));
        int yearIndex = requiredColumn(header, "YYYY");
        int monthIndex = requiredColumn(header, "MM");
        int dayIndex = requiredColumn(header, "dd");
        int hourIndex = requiredColumn(header, "HH");
        int minuteIndex = requiredColumn(header, "mm");
        int secondIndex = requiredColumn(header, "ss.SSS");
        int ecefXIndex = requiredColumn(header, "ECEF_X");
        int ecefYIndex = requiredColumn(header, "ECEF_Y");
        int ecefZIndex = requiredColumn(header, "ECEF_Z");
        int eciXIndex = requiredColumn(header, "ECI_X");
        int eciYIndex = requiredColumn(header, "ECI_Y");
        int eciZIndex = requiredColumn(header, "ECI_Z");
        int eciVxIndex = requiredColumn(header, "ECI_VX");
        int eciVyIndex = requiredColumn(header, "ECI_VY");
        int eciVzIndex = requiredColumn(header, "ECI_VZ");
        int ecefVxIndex = requiredColumn(header, "ECEF_Vx");
        int ecefVyIndex = requiredColumn(header, "ECEF_Vy");
        int ecefVzIndex = requiredColumn(header, "ECEF_Vz");
        int dtIndex = optionalColumn(header, "dt");
        int intervalIndex = optionalColumn(header, "timeIntervalMs");

        List<String[]> rows = new ArrayList<String[]>();
        List<Double> originalTimes = new ArrayList<Double>();
        for (int lineIndex = 1; lineIndex < lines.size(); lineIndex++) {
            String line = lines.get(lineIndex);
            if (isBlank(line)) {
                continue;
            }
            String[] row = splitCsvLine(line);
            if (row.length < header.length) {
                throw new IllegalArgumentException("弹道CSV第" + (lineIndex + 1)
                        + "行列数不足，期望至少" + header.length + "列，实际" + row.length + "列。");
            }
            rows.add(row);
            originalTimes.add(parseCsvRowTime(row, yearIndex, monthIndex, dayIndex, hourIndex, minuteIndex, secondIndex));
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("弹道CSV文件未解析到轨迹数据行：" + sourceCsv);
        }

        List<String> outputHeader = new ArrayList<String>();
        Collections.addAll(outputHeader, header);
        if (intervalIndex < 0) {
            outputHeader.add("timeIntervalMs");
        }

        double firstOriginalTime = originalTimes.get(0);
        double newLaunchTime = parseTimeCode(request.launchTimeUtc);
        String taskName = blankToDefault(request.taskName, "retimeTrajectory");
        String targetId = blankToDefault(request.targetId, "RETIMED-TGT");
        String created = LocalDateTime.now().format(FILE_TIME_FORMAT);
        String fileName = fileToken(taskName)
                + "_" + fileToken(labelTime(newLaunchTime))
                + "_" + fileToken(targetId)
                + "_" + created
                + ".csv";
        Path outputCsv = outputDir.resolve(fileName);

        try (BufferedWriter writer = Files.newBufferedWriter(outputCsv, StandardCharsets.UTF_8)) {
            writer.write(joinCsvLine(outputHeader));
            writer.newLine();
            double previousOutputTime = Double.NaN;
            for (int i = 0; i < rows.size(); i++) {
                String[] row = rows.get(i);
                double elapsedSec = originalTimes.get(i) - firstOriginalTime;
                double outputTime = newLaunchTime + elapsedSec;
                double intervalMs = i == 0 ? 0.0 : Math.max(0.0, (outputTime - previousOutputTime) * 1000.0);
                previousOutputTime = outputTime;

                String[] outputRow = new String[outputHeader.size()];
                System.arraycopy(row, 0, outputRow, 0, Math.min(row.length, header.length));
                applyUtcToCsvRow(outputRow, outputTime, yearIndex, monthIndex, dayIndex, hourIndex, minuteIndex, secondIndex);
                // 轨迹在地固系中保持不变；绝对时刻改变后，重新计算J2000惯性系状态。
                OrbitCalculator.Vector3 ecefPosition = new OrbitCalculator.Vector3(
                        parseNumeric(row[ecefXIndex]),
                        parseNumeric(row[ecefYIndex]),
                        parseNumeric(row[ecefZIndex]));
                OrbitCalculator.Vector3 ecefVelocity = new OrbitCalculator.Vector3(
                        parseNumeric(row[ecefVxIndex]),
                        parseNumeric(row[ecefVyIndex]),
                        parseNumeric(row[ecefVzIndex]));
                OrbitCalculator.Vector3 eciPosition = OrbitCalculator.ecefToEciPos(ecefPosition, outputTime);
                OrbitCalculator.Vector3 eciVelocity = OrbitCalculator.ecefToEciVel(
                        ecefVelocity, ecefPosition, outputTime);
                outputRow[eciXIndex] = String.format(Locale.US, "%.3f", eciPosition.x);
                outputRow[eciYIndex] = String.format(Locale.US, "%.3f", eciPosition.y);
                outputRow[eciZIndex] = String.format(Locale.US, "%.3f", eciPosition.z);
                outputRow[eciVxIndex] = String.format(Locale.US, "%.9f", eciVelocity.x);
                outputRow[eciVyIndex] = String.format(Locale.US, "%.9f", eciVelocity.y);
                outputRow[eciVzIndex] = String.format(Locale.US, "%.9f", eciVelocity.z);
                if (dtIndex >= 0) {
                    outputRow[dtIndex] = String.format(Locale.US, "%.3f", Math.max(0.0, elapsedSec * 1000.0));
                }
                int outputIntervalIndex = intervalIndex >= 0 ? intervalIndex : outputRow.length - 1;
                outputRow[outputIntervalIndex] = String.format(Locale.US, "%.3f", intervalMs);
                writer.write(joinCsvLine(outputRow));
                writer.newLine();
            }
        }

        FitRunResult result = new FitRunResult();
        result.taskName = taskName;
        result.targetCount = 1;
        FileResult file = new FileResult();
        file.targetId = targetId;
        file.model = "RETIMED_TRAJECTORY";
        file.csvPath = outputCsv.toAbsolutePath().normalize().toString();
        file.pointCount = rows.size();
        file.warnings.add("弹道文件重定时保持ECEF位置和速度不变；按新的绝对时刻重算ECI位置和速度，并更新时间列、dt列和timeIntervalMs列。");
        result.files.add(file);
        return result;
    }

    /** 生成排序输出时间，原观测时刻回填位置/速度，区间内约束插值，首尾外按端点状态二体外推；随后填充地固列与几何诊断。 */
    private static FittedTrajectory generateTrajectory(List<TrajectorySample> samples,
                                                       double interpolationStepSec,
                                                       FittingPolicy policy,
                                                       double outputStartSec,
                                                       double outputEndSec) {
        int n = samples.size();
        double[] times = new double[n];
        boolean[] originalTime = new boolean[n];
        for (int i = 0; i < n; i++) {
            TrajectorySample sample = samples.get(i);
            times[i] = sample.absoluteTimeSec;
            originalTime[i] = sample.originalConstraint;
        }

        OrbitCalculator.Vector3[] knotVelocities = new OrbitCalculator.Vector3[n];
        OrbitCalculator.Vector3[] knotAccelerations = new OrbitCalculator.Vector3[n];
        estimateKnotDerivatives(samples, policy, knotVelocities, knotAccelerations);

        List<Double> outputTimes = outputTimes(times, interpolationStepSec, outputStartSec, outputEndSec);
        FittedTrajectory trajectory = new FittedTrajectory();
        trajectory.targetId = samples.get(0).targetId;
        trajectory.model = policy.model;
        if (outputStartSec < times[0] - TIME_UNIQUE_EPS_SEC || outputEndSec > times[n - 1] + TIME_UNIQUE_EPS_SEC) {
            trajectory.warnings.add("输出时间范围超出package102轨迹点覆盖区间，区间外已使用端点状态进行二体RK4外推。");
        }
        trajectory.warnings.add("Package102拟合在原始点之间使用受端点约束的Hermite平滑插值，区间外使用二体RK4动力学外推；命中package102原始时刻时保留源样本，以保证严格通过原始点。");

        int segmentIndex = 0;
        FittedPoint previous = null;
        for (double t : outputTimes) {
            TrajectorySample exact = exactSampleAt(samples, t);
            FittedPoint point;
            if (exact != null) {
                State exactState = stateFromSample(samples, samples.indexOf(exact));
                point = pointFromState(t, exactState);
                point.original = exact.originalConstraint;
            } else {
                while (segmentIndex < n - 2 && t > times[segmentIndex + 1] + TIME_UNIQUE_EPS_SEC) {
                    segmentIndex++;
                }
                int anchorIndex;
                if (t < times[0] - TIME_UNIQUE_EPS_SEC) {
                    anchorIndex = 0;
                } else if (t > times[n - 1] + TIME_UNIQUE_EPS_SEC) {
                    anchorIndex = n - 1;
                } else {
                    anchorIndex = segmentIndex;
                }
                if (t >= times[0] - TIME_UNIQUE_EPS_SEC && t <= times[n - 1] + TIME_UNIQUE_EPS_SEC) {
                    point = pointFromConstrainedSegment(
                            t,
                            samples.get(segmentIndex),
                            samples.get(segmentIndex + 1),
                            knotVelocities[segmentIndex],
                            knotVelocities[segmentIndex + 1],
                            knotAccelerations[segmentIndex],
                            knotAccelerations[segmentIndex + 1]
                    );
                } else {
                    TrajectorySample anchor = samples.get(anchorIndex);
                    State anchorState = stateForPropagation(samples, anchorIndex);
                    point = pointFromState(t, propagateTwoBody(anchorState, t - anchor.absoluteTimeSec));
                }
                point.original = false;
            }
            point.original = point.original || isOriginalTime(t, times, originalTime);
            if (previous == null) {
                point.dtMs = 0.0;
            } else {
                point.dtMs = Math.max(0.0, (point.absoluteTimeSec - previous.absoluteTimeSec) * 1000.0);
            }
            fillDerivedFields(point, outputStartSec);
            trajectory.points.add(point);
            previous = point;
        }
        return trajectory;
    }

    private static TrajectorySample exactSampleAt(List<TrajectorySample> samples, double timeSec) {
        for (TrajectorySample sample : samples) {
            if (Math.abs(sample.absoluteTimeSec - timeSec) <= TIME_UNIQUE_EPS_SEC) {
                return sample;
            }
        }
        return null;
    }

    private static State stateFromSample(List<TrajectorySample> samples, int index) {
        TrajectorySample sample = samples.get(index);
        OrbitCalculator.Vector3 velocity = isUseful(sample.velocityEciMs)
                ? sample.velocityEciMs
                : fallbackVelocity(samples, index);
        return new State(sample.positionEciM, velocity);
    }

    private static State stateForPropagation(List<TrajectorySample> samples, int index) {
        State base = stateFromSample(samples, index);
        if (index != 0 && index != samples.size() - 1) {
            return base;
        }
        return fitEndpointVelocity(samples, index, base);
    }

    /** 以原邻近样本拟合端点速度，保持32点/120秒范围、4次迭代和0.05米每秒扰动，避免因抽取改变原端点误差。 */
    private static State fitEndpointVelocity(List<TrajectorySample> samples, int anchorIndex, State base) {
        List<Integer> fitIndices = endpointFitIndices(samples, anchorIndex);
        if (fitIndices.isEmpty()) {
            return base;
        }
        double baseRms = endpointVelocityFitRms(samples, anchorIndex, base.v, fitIndices);
        OrbitCalculator.Vector3 velocity = base.v;
        for (int iteration = 0; iteration < ENDPOINT_VELOCITY_FIT_ITERATIONS; iteration++) {
            double[][] normal = new double[3][3];
            double[] rhs = new double[3];
            double currentRms = accumulateEndpointVelocityFit(
                    samples,
                    anchorIndex,
                    velocity,
                    fitIndices,
                    normal,
                    rhs
            );
            double[] delta = solve3x3(normal, rhs);
            if (delta == null) {
                break;
            }
            OrbitCalculator.Vector3 candidate = velocity.add(new OrbitCalculator.Vector3(delta[0], delta[1], delta[2]));
            if (candidate.subtract(base.v).norm() > ENDPOINT_VELOCITY_FIT_MAX_CORRECTION_M_S) {
                break;
            }
            double candidateRms = endpointVelocityFitRms(samples, anchorIndex, candidate, fitIndices);
            if (candidateRms > currentRms + 1e-9) {
                break;
            }
            velocity = candidate;
            if (Math.sqrt(delta[0] * delta[0] + delta[1] * delta[1] + delta[2] * delta[2]) < 1e-6) {
                break;
            }
        }
        double fittedRms = endpointVelocityFitRms(samples, anchorIndex, velocity, fitIndices);
        if (isFinite(fittedRms) && fittedRms <= baseRms
                && velocity.subtract(base.v).norm() <= ENDPOINT_VELOCITY_FIT_MAX_CORRECTION_M_S) {
            return new State(base.r, velocity);
        }
        return base;
    }

    private static List<Integer> endpointFitIndices(List<TrajectorySample> samples, int anchorIndex) {
        List<Integer> indices = new ArrayList<Integer>();
        double anchorTime = samples.get(anchorIndex).absoluteTimeSec;
        if (anchorIndex == 0) {
            for (int i = 1; i < samples.size() && indices.size() < ENDPOINT_VELOCITY_FIT_MAX_SAMPLES; i++) {
                if (samples.get(i).absoluteTimeSec - anchorTime > ENDPOINT_VELOCITY_FIT_MAX_SPAN_SEC) {
                    break;
                }
                indices.add(i);
            }
        } else if (anchorIndex == samples.size() - 1) {
            for (int i = samples.size() - 2; i >= 0 && indices.size() < ENDPOINT_VELOCITY_FIT_MAX_SAMPLES; i--) {
                if (anchorTime - samples.get(i).absoluteTimeSec > ENDPOINT_VELOCITY_FIT_MAX_SPAN_SEC) {
                    break;
                }
                indices.add(i);
            }
        }
        return indices;
    }

    private static double accumulateEndpointVelocityFit(List<TrajectorySample> samples,
                                                        int anchorIndex,
                                                        OrbitCalculator.Vector3 velocity,
                                                        List<Integer> fitIndices,
                                                        double[][] normal,
                                                        double[] rhs) {
        TrajectorySample anchor = samples.get(anchorIndex);
        State baseState = new State(anchor.positionEciM, velocity);
        double sumSq = 0.0;
        int residualCount = 0;
        for (Integer fitIndex : fitIndices) {
            TrajectorySample observed = samples.get(fitIndex.intValue());
            double dt = observed.absoluteTimeSec - anchor.absoluteTimeSec;
            OrbitCalculator.Vector3 predicted = propagateTwoBody(baseState, dt).r;
            OrbitCalculator.Vector3 residual = predicted.subtract(observed.positionEciM);
            OrbitCalculator.Vector3[] columns = new OrbitCalculator.Vector3[3];
            for (int axis = 0; axis < 3; axis++) {
                OrbitCalculator.Vector3 perturb = axisVector(axis, ENDPOINT_VELOCITY_FIT_PERTURB_M_S);
                OrbitCalculator.Vector3 perturbed = propagateTwoBody(
                        new State(anchor.positionEciM, velocity.add(perturb)),
                        dt
                ).r;
                columns[axis] = perturbed.subtract(predicted).scale(1.0 / ENDPOINT_VELOCITY_FIT_PERTURB_M_S);
            }
            double[] r = new double[]{residual.x, residual.y, residual.z};
            for (int row = 0; row < 3; row++) {
                double[] jRow = new double[]{component(columns[0], row), component(columns[1], row), component(columns[2], row)};
                for (int a = 0; a < 3; a++) {
                    rhs[a] -= jRow[a] * r[row];
                    for (int b = 0; b < 3; b++) {
                        normal[a][b] += jRow[a] * jRow[b];
                    }
                }
                sumSq += r[row] * r[row];
                residualCount++;
            }
        }
        return residualCount == 0 ? Double.POSITIVE_INFINITY : Math.sqrt(sumSq / residualCount);
    }

    private static double endpointVelocityFitRms(List<TrajectorySample> samples,
                                                 int anchorIndex,
                                                 OrbitCalculator.Vector3 velocity,
                                                 List<Integer> fitIndices) {
        TrajectorySample anchor = samples.get(anchorIndex);
        State state = new State(anchor.positionEciM, velocity);
        double sumSq = 0.0;
        int residualCount = 0;
        for (Integer fitIndex : fitIndices) {
            TrajectorySample observed = samples.get(fitIndex.intValue());
            double dt = observed.absoluteTimeSec - anchor.absoluteTimeSec;
            OrbitCalculator.Vector3 residual = propagateTwoBody(state, dt).r.subtract(observed.positionEciM);
            sumSq += residual.dot(residual);
            residualCount += 3;
        }
        return residualCount == 0 ? Double.POSITIVE_INFINITY : Math.sqrt(sumSq / residualCount);
    }

    private static OrbitCalculator.Vector3 axisVector(int axis, double value) {
        if (axis == 0) {
            return new OrbitCalculator.Vector3(value, 0.0, 0.0);
        }
        if (axis == 1) {
            return new OrbitCalculator.Vector3(0.0, value, 0.0);
        }
        return new OrbitCalculator.Vector3(0.0, 0.0, value);
    }

    private static double component(OrbitCalculator.Vector3 vector, int axis) {
        if (axis == 0) {
            return vector.x;
        }
        if (axis == 1) {
            return vector.y;
        }
        return vector.z;
    }

    private static double[] solve3x3(double[][] matrix, double[] rhs) {
        double[][] a = new double[3][4];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                a[i][j] = matrix[i][j];
            }
            a[i][3] = rhs[i];
        }
        for (int col = 0; col < 3; col++) {
            int pivot = col;
            for (int row = col + 1; row < 3; row++) {
                if (Math.abs(a[row][col]) > Math.abs(a[pivot][col])) {
                    pivot = row;
                }
            }
            if (Math.abs(a[pivot][col]) < 1e-12) {
                return null;
            }
            if (pivot != col) {
                double[] temp = a[col];
                a[col] = a[pivot];
                a[pivot] = temp;
            }
            double divisor = a[col][col];
            for (int j = col; j < 4; j++) {
                a[col][j] /= divisor;
            }
            for (int row = 0; row < 3; row++) {
                if (row == col) {
                    continue;
                }
                double factor = a[row][col];
                for (int j = col; j < 4; j++) {
                    a[row][j] -= factor * a[col][j];
                }
            }
        }
        return new double[]{a[0][3], a[1][3], a[2][3]};
    }

    private static OrbitCalculator.Vector3 fallbackVelocity(List<TrajectorySample> samples, int index) {
        int n = samples.size();
        if (n < 2) {
            return zero();
        }
        if (index <= 0) {
            return chordVelocity(samples.get(0), samples.get(1));
        }
        if (index >= n - 1) {
            return chordVelocity(samples.get(n - 2), samples.get(n - 1));
        }
        OrbitCalculator.Vector3 previous = chordVelocity(samples.get(index - 1), samples.get(index));
        OrbitCalculator.Vector3 next = chordVelocity(samples.get(index), samples.get(index + 1));
        if (isUseful(previous) && isUseful(next)) {
            return previous.add(next).scale(0.5);
        }
        return isUseful(previous) ? previous : next;
    }

    private static OrbitCalculator.Vector3 chordVelocity(TrajectorySample a, TrajectorySample b) {
        double dt = b.absoluteTimeSec - a.absoluteTimeSec;
        if (Math.abs(dt) <= TIME_UNIQUE_EPS_SEC) {
            return zero();
        }
        return b.positionEciM.subtract(a.positionEciM).scale(1.0 / dt);
    }

    private static FittedPoint pointFromState(double timeSec, State state) {
        FittedPoint point = new FittedPoint();
        point.absoluteTimeSec = timeSec;
        point.positionEciM = state.r;
        point.velocityEciMs = state.v;
        point.accelerationEciMs2 = accelerationTwoBody(state.r);
        return point;
    }

    /** 保持原约束段多项式及导数计算，位置/速度/加速度边界硬约束不被外推工具替换。 */
    private static FittedPoint pointFromConstrainedSegment(double timeSec,
                                                           TrajectorySample start,
                                                           TrajectorySample end,
                                                           OrbitCalculator.Vector3 startVelocity,
                                                           OrbitCalculator.Vector3 endVelocity,
                                                           OrbitCalculator.Vector3 startAcceleration,
                                                           OrbitCalculator.Vector3 endAcceleration) {
        double dt = end.absoluteTimeSec - start.absoluteTimeSec;
        if (dt <= TIME_UNIQUE_EPS_SEC) {
            return pointFromState(timeSec, stateFromSingleSample(start, startVelocity));
        }

        double u = clamp((timeSec - start.absoluteTimeSec) / dt, 0.0, 1.0);
        double u2 = u * u;
        double u3 = u2 * u;
        double u4 = u3 * u;
        double u5 = u4 * u;

        double h00 = 1.0 - 10.0 * u3 + 15.0 * u4 - 6.0 * u5;
        double h10 = u - 6.0 * u3 + 8.0 * u4 - 3.0 * u5;
        double h20 = 0.5 * u2 - 1.5 * u3 + 1.5 * u4 - 0.5 * u5;
        double h01 = 10.0 * u3 - 15.0 * u4 + 6.0 * u5;
        double h11 = -4.0 * u3 + 7.0 * u4 - 3.0 * u5;
        double h21 = 0.5 * u3 - u4 + 0.5 * u5;

        OrbitCalculator.Vector3 p0 = start.positionEciM;
        OrbitCalculator.Vector3 p1 = end.positionEciM;
        OrbitCalculator.Vector3 v0 = startVelocity == null ? zero() : startVelocity;
        OrbitCalculator.Vector3 v1 = endVelocity == null ? zero() : endVelocity;
        OrbitCalculator.Vector3 a0 = startAcceleration == null ? zero() : startAcceleration;
        OrbitCalculator.Vector3 a1 = endAcceleration == null ? zero() : endAcceleration;

        OrbitCalculator.Vector3 position = p0.scale(h00)
                .add(v0.scale(dt * h10))
                .add(a0.scale(dt * dt * h20))
                .add(p1.scale(h01))
                .add(v1.scale(dt * h11))
                .add(a1.scale(dt * dt * h21));

        double dh00 = (-30.0 * u2 + 60.0 * u3 - 30.0 * u4) / dt;
        double dh10 = 1.0 - 18.0 * u2 + 32.0 * u3 - 15.0 * u4;
        double dh20 = dt * (u - 4.5 * u2 + 6.0 * u3 - 2.5 * u4);
        double dh01 = (30.0 * u2 - 60.0 * u3 + 30.0 * u4) / dt;
        double dh11 = -12.0 * u2 + 28.0 * u3 - 15.0 * u4;
        double dh21 = dt * (1.5 * u2 - 4.0 * u3 + 2.5 * u4);

        OrbitCalculator.Vector3 velocity = p0.scale(dh00)
                .add(v0.scale(dh10))
                .add(a0.scale(dh20))
                .add(p1.scale(dh01))
                .add(v1.scale(dh11))
                .add(a1.scale(dh21));

        double ddh00 = (-60.0 * u + 180.0 * u2 - 120.0 * u3) / (dt * dt);
        double ddh10 = (-36.0 * u + 96.0 * u2 - 60.0 * u3) / dt;
        double ddh20 = 1.0 - 9.0 * u + 18.0 * u2 - 10.0 * u3;
        double ddh01 = (60.0 * u - 180.0 * u2 + 120.0 * u3) / (dt * dt);
        double ddh11 = (-24.0 * u + 84.0 * u2 - 60.0 * u3) / dt;
        double ddh21 = 3.0 * u - 12.0 * u2 + 10.0 * u3;

        OrbitCalculator.Vector3 acceleration = p0.scale(ddh00)
                .add(v0.scale(ddh10))
                .add(a0.scale(ddh20))
                .add(p1.scale(ddh01))
                .add(v1.scale(ddh11))
                .add(a1.scale(ddh21));

        FittedPoint point = new FittedPoint();
        point.absoluteTimeSec = timeSec;
        point.positionEciM = position;
        point.velocityEciMs = velocity;
        point.accelerationEciMs2 = acceleration;
        return point;
    }

    private static State stateFromSingleSample(TrajectorySample sample, OrbitCalculator.Vector3 velocity) {
        return new State(sample.positionEciM, velocity == null ? zero() : velocity);
    }

    /** 自治二体时间平移后通过公共工具外推，保留dt原值及原短间隔返回初始对象行为。 */
    private static State propagateTwoBody(State initial, double dt) {
        BallisticCartesianPropagation.State result = BallisticCartesianPropagation.propagateRelative(
                initial == null ? null : new BallisticCartesianPropagation.State(toDomain(initial.r), toDomain(initial.v)), dt);
        if (Math.abs(dt) <= TIME_UNIQUE_EPS_SEC) return initial;
        return new State(fromDomain(result.r), fromDomain(result.v));
    }



    /** 事件搜索沿用原单步调用节点，复用与外推完全相同的抽取RK4，不额外改变校验。 */
    private static State rk4TwoBodyStep(State state, double h) {
        BallisticCartesianPropagation.State result = BallisticCartesianPropagation.rk4TwoBodyStep(
                new BallisticCartesianPropagation.State(toDomain(state.r), toDomain(state.v)), h);
        return new State(fromDomain(result.r), fromDomain(result.v));
    }



    /** 拟合加速度和积分共用原二体中心引力公式。 */
    private static OrbitCalculator.Vector3 accelerationTwoBody(OrbitCalculator.Vector3 position) {
        return fromDomain(BallisticCartesianPropagation.accelerationTwoBody(toDomain(position)));
    }

    /** 兼容向量到纯领域向量仅逐分量拷贝，不缩放、不舍入。 */
    private static com.runisys.orbit.spacevisibility.domain.geometry.Vector3 toDomain(OrbitCalculator.Vector3 value) {
        return value == null ? null : new com.runisys.orbit.spacevisibility.domain.geometry.Vector3(value.x, value.y, value.z);
    }

    /** 将原计算的米/米每秒分量返回旧嵌套类型，保持调用方规范名。 */
    private static OrbitCalculator.Vector3 fromDomain(com.runisys.orbit.spacevisibility.domain.geometry.Vector3 value) {
        return new OrbitCalculator.Vector3(value.getX(), value.getY(), value.getZ());
    }

    /** 按相邻弦方向及原提供速度/加速度权重估计节点导数，保持速度限幅与转向限制，避免插值回折。 */
    private static void estimateKnotDerivatives(List<TrajectorySample> samples,
                                                FittingPolicy policy,
                                                OrbitCalculator.Vector3[] velocities,
                                                OrbitCalculator.Vector3[] accelerations) {
        int n = samples.size();
        double[] times = new double[n];
        OrbitCalculator.Vector3[] p = new OrbitCalculator.Vector3[n];
        OrbitCalculator.Vector3[] d = new OrbitCalculator.Vector3[n - 1];
        double[] dt = new double[n - 1];
        for (int i = 0; i < n; i++) {
            times[i] = samples.get(i).absoluteTimeSec;
            p[i] = samples.get(i).positionEciM;
        }
        for (int i = 0; i < n - 1; i++) {
            dt[i] = times[i + 1] - times[i];
            d[i] = p[i + 1].subtract(p[i]).scale(1.0 / dt[i]);
        }

        for (int i = 0; i < n; i++) {
            OrbitCalculator.Vector3 geometric;
            double speedLimit;
            if (i == 0) {
                geometric = d[0];
                speedLimit = d[0].norm();
            } else if (i == n - 1) {
                geometric = d[n - 2];
                speedLimit = d[n - 2].norm();
            } else {
                geometric = interiorTangent(d[i - 1], d[i], dt[i - 1], dt[i]);
                speedLimit = Math.min(d[i - 1].norm(), d[i].norm());
            }

            OrbitCalculator.Vector3 supplied = samples.get(i).velocityEciMs;
            OrbitCalculator.Vector3 velocity = blendIfUseful(
                    geometric,
                    supplied,
                    policy.suppliedVelocityWeight,
                    i == 0 ? null : d[i - 1],
                    i == n - 1 ? null : d[i]
            );
            velocities[i] = limitVector(velocity, policy.tangentLimit * speedLimit);
        }

        for (int i = 0; i < n; i++) {
            OrbitCalculator.Vector3 geometric;
            double accLimit;
            if (i == 0) {
                geometric = zero();
                accLimit = d[0].norm() / dt[0];
            } else if (i == n - 1) {
                geometric = zero();
                accLimit = d[n - 2].norm() / dt[n - 2];
            } else {
                geometric = d[i].subtract(d[i - 1]).scale(2.0 / (dt[i - 1] + dt[i]));
                accLimit = Math.min(d[i - 1].norm() / dt[i - 1], d[i].norm() / dt[i]);
            }

            OrbitCalculator.Vector3 supplied = samples.get(i).accelerationEciMs2;
            OrbitCalculator.Vector3 acceleration = blend(
                    geometric,
                    isUseful(supplied) ? supplied : geometric,
                    policy.suppliedAccelerationWeight
            );
            accelerations[i] = limitVector(acceleration, Math.max(1e-9, policy.accelerationLimit * accLimit * 4.0));
        }
    }

    private static OrbitCalculator.Vector3 interiorTangent(OrbitCalculator.Vector3 previous,
                                                           OrbitCalculator.Vector3 next,
                                                           double previousDt,
                                                           double nextDt) {
        double prevNorm = previous.norm();
        double nextNorm = next.norm();
        if (prevNorm < 1e-12 || nextNorm < 1e-12) {
            return zero();
        }
        double dot = previous.dot(next) / (prevNorm * nextNorm);
        dot = clamp(dot, -1.0, 1.0);
        if (dot < 0.0) {
            OrbitCalculator.Vector3 bisector = previous.scale(1.0 / prevNorm).add(next.scale(1.0 / nextNorm));
            double bisectorNorm = bisector.norm();
            if (bisectorNorm < 1e-12) {
                return zero();
            }
            return bisector.scale(Math.min(prevNorm, nextNorm) * Math.max(0.0, Math.sqrt((1.0 + dot) * 0.5)) / bisectorNorm);
        }
        return previous.scale(nextDt).add(next.scale(previousDt)).scale(1.0 / (previousDt + nextDt));
    }

    private static OrbitCalculator.Vector3 blendIfUseful(OrbitCalculator.Vector3 geometric,
                                                          OrbitCalculator.Vector3 supplied,
                                                          double weight,
                                                          OrbitCalculator.Vector3 previousChord,
                                                          OrbitCalculator.Vector3 nextChord) {
        if (!isUseful(supplied)) {
            return geometric;
        }
        if (previousChord != null && supplied.dot(previousChord) < 0.0) {
            return geometric;
        }
        if (nextChord != null && supplied.dot(nextChord) < 0.0) {
            return geometric;
        }
        return blend(geometric, supplied, weight);
    }

    /** 合并规则输出采样与原节点时刻，使用原去重阈值，保留首尾和所有观测点。 */
    private static List<Double> outputTimes(double[] knots, double stepSec, double outputStartSec, double outputEndSec) {
        List<Double> times = new ArrayList<Double>();
        double start = outputStartSec;
        double end = outputEndSec;
        addUniqueTime(times, start);
        addUniqueTime(times, end);
        for (double knot : knots) {
            addUniqueTime(times, knot);
        }
        for (double t = start; t <= end + 1e-9; t += stepSec) {
            addUniqueTime(times, Math.min(t, end));
        }
        Collections.sort(times);
        return times;
    }

    private static void addUniqueTime(List<Double> times, double t) {
        for (Double existing : times) {
            if (Math.abs(existing.doubleValue() - t) <= TIME_UNIQUE_EPS_SEC) {
                return;
            }
        }
        times.add(t);
    }

    private static boolean isOriginalTime(double t, double[] knots, boolean[] original) {
        for (int i = 0; i < knots.length; i++) {
            if (original[i] && Math.abs(t - knots[i]) <= TIME_UNIQUE_EPS_SEC) {
                return true;
            }
        }
        return false;
    }

    /** 拟合区间外从端点状态按原相对dt调用公共二体工具，再填入输出点；不先加到大历元再相减。 */
    private static FittedPoint evaluateEndpointExtrapolation(double anchorTime,
                                                             OrbitCalculator.Vector3 anchorPosition,
                                                             OrbitCalculator.Vector3 anchorVelocity,
                                                             OrbitCalculator.Vector3 anchorAcceleration,
                                                             double t) {
        double dt = t - anchorTime;
        FittedPoint point = new FittedPoint();
        point.absoluteTimeSec = t;
        point.positionEciM = anchorPosition
                .add(anchorVelocity.scale(dt))
                .add(anchorAcceleration.scale(0.5 * dt * dt));
        point.velocityEciMs = anchorVelocity.add(anchorAcceleration.scale(dt));
        point.accelerationEciMs2 = anchorAcceleration;
        return point;
    }

    /** 区间内沿用原Hermite段的多项式及一、二阶导数，不改变曲线形状和原容差。 */
    private static FittedPoint evaluateSegment(double t0,
                                               double t1,
                                               OrbitCalculator.Vector3 p0,
                                               OrbitCalculator.Vector3 p1,
                                               OrbitCalculator.Vector3 v0,
                                               OrbitCalculator.Vector3 v1,
                                               OrbitCalculator.Vector3 a0,
                                               OrbitCalculator.Vector3 a1,
                                               double t) {
        double h = t1 - t0;
        double s = h <= 0.0 ? 0.0 : clamp((t - t0) / h, 0.0, 1.0);
        double s2 = s * s;
        double s3 = s2 * s;
        double s4 = s3 * s;
        double s5 = s4 * s;

        double b0 = 1.0 - 10.0 * s3 + 15.0 * s4 - 6.0 * s5;
        double b1 = s - 6.0 * s3 + 8.0 * s4 - 3.0 * s5;
        double b2 = 0.5 * (s2 - 3.0 * s3 + 3.0 * s4 - s5);
        double b3 = 10.0 * s3 - 15.0 * s4 + 6.0 * s5;
        double b4 = -4.0 * s3 + 7.0 * s4 - 3.0 * s5;
        double b5 = 0.5 * (s3 - 2.0 * s4 + s5);

        double db0 = -30.0 * s2 + 60.0 * s3 - 30.0 * s4;
        double db1 = 1.0 - 18.0 * s2 + 32.0 * s3 - 15.0 * s4;
        double db2 = 0.5 * (2.0 * s - 9.0 * s2 + 12.0 * s3 - 5.0 * s4);
        double db3 = 30.0 * s2 - 60.0 * s3 + 30.0 * s4;
        double db4 = -12.0 * s2 + 28.0 * s3 - 15.0 * s4;
        double db5 = 0.5 * (3.0 * s2 - 8.0 * s3 + 5.0 * s4);

        double d2b0 = -60.0 * s + 180.0 * s2 - 120.0 * s3;
        double d2b1 = -36.0 * s + 96.0 * s2 - 60.0 * s3;
        double d2b2 = 1.0 - 9.0 * s + 18.0 * s2 - 10.0 * s3;
        double d2b3 = 60.0 * s - 180.0 * s2 + 120.0 * s3;
        double d2b4 = -24.0 * s + 84.0 * s2 - 60.0 * s3;
        double d2b5 = 3.0 * s - 12.0 * s2 + 10.0 * s3;

        OrbitCalculator.Vector3 position = p0.scale(b0)
                .add(v0.scale(h * b1))
                .add(a0.scale(h * h * b2))
                .add(p1.scale(b3))
                .add(v1.scale(h * b4))
                .add(a1.scale(h * h * b5));

        OrbitCalculator.Vector3 velocity = p0.scale(db0 / h)
                .add(v0.scale(db1))
                .add(a0.scale(h * db2))
                .add(p1.scale(db3 / h))
                .add(v1.scale(db4))
                .add(a1.scale(h * db5));

        OrbitCalculator.Vector3 acceleration = p0.scale(d2b0 / (h * h))
                .add(v0.scale(d2b1 / h))
                .add(a0.scale(d2b2))
                .add(p1.scale(d2b3 / (h * h)))
                .add(v1.scale(d2b4 / h))
                .add(a1.scale(d2b5));

        FittedPoint point = new FittedPoint();
        point.absoluteTimeSec = t;
        point.positionEciM = position;
        point.velocityEciMs = velocity;
        point.accelerationEciMs2 = acceleration;
        return point;
    }

    /** 将J2000状态转换至原ECEF/STK速度口径，并计算相对时间、能量等派生列。 */
    private static void fillDerivedFields(FittedPoint point, double startTimeSec) {
        point.relativeTimeSec = point.absoluteTimeSec - startTimeSec;
        point.positionEcefM = OrbitCalculator.eciToEcefPos(point.positionEciM, point.absoluteTimeSec);
        point.velocityEcefMs = OrbitCalculator.eciToStkFixedVelocity(point.velocityEciMs, point.absoluteTimeSec);
        double r = Math.max(1e-9, point.positionEciM.norm());
        double v = point.velocityEciMs.norm();
        point.energy = 0.5 * v * v - EARTH_MU / r;
    }

    private static void validateOutputTimeRange(double outputStartSec,
                                                double outputEndSec,
                                                double firstSampleTime,
                                                double lastSampleTime) {
        if (!isFinite(outputStartSec) || !isFinite(outputEndSec)) {
            throw new IllegalArgumentException("taskStartTime和taskEndTime必须是有效时间。");
        }
        if (outputEndSec <= outputStartSec + TIME_UNIQUE_EPS_SEC) {
            throw new IllegalArgumentException("taskEndTime必须晚于taskStartTime。");
        }
        if (outputStartSec > firstSampleTime + TIME_UNIQUE_EPS_SEC) {
            throw new IllegalArgumentException("taskStartTime必须早于或等于第一个package102轨迹点时间。");
        }
        if (outputEndSec < lastSampleTime - TIME_UNIQUE_EPS_SEC) {
            throw new IllegalArgumentException("taskEndTime必须晚于或等于最后一个package102轨迹点时间。");
        }
    }

    private static ValidationResult validateFittedGeometry(List<FittedPoint> points) {
        ValidationResult result = new ValidationResult();
        for (int i = 1; i < points.size() - 1; i++) {
            OrbitCalculator.Vector3 a = points.get(i).positionEciM.subtract(points.get(i - 1).positionEciM);
            OrbitCalculator.Vector3 b = points.get(i + 1).positionEciM.subtract(points.get(i).positionEciM);
            if (a.norm() < 1e-9 || b.norm() < 1e-9) {
                continue;
            }
            if (a.dot(b) < TURN_DOT_EPS) {
                result.ok = false;
                result.message = "输出轨迹相邻段夹角在第 " + i + " 行附近超过 "
                        + MAX_DIRECTION_ANGLE_DEG + " 度。";
                return result;
            }
        }
        String intersection = firstSelfIntersection(points);
        if (intersection != null) {
            result.ok = false;
            result.message = intersection;
            return result;
        }
        return result;
    }

    private static String firstSelfIntersection(List<FittedPoint> points) {
        if (points.size() < 4) {
            return null;
        }
        double tolerance = intersectionTolerance(points);
        for (int i = 0; i < points.size() - 1; i++) {
            OrbitCalculator.Vector3 a0 = points.get(i).positionEciM;
            OrbitCalculator.Vector3 a1 = points.get(i + 1).positionEciM;
            if (a1.subtract(a0).norm() < 1e-9) {
                continue;
            }
            for (int j = i + 2; j < points.size() - 1; j++) {
                OrbitCalculator.Vector3 b0 = points.get(j).positionEciM;
                OrbitCalculator.Vector3 b1 = points.get(j + 1).positionEciM;
                if (b1.subtract(b0).norm() < 1e-9) {
                    continue;
                }
                if (segmentDistance(a0, a1, b0, b1) <= tolerance) {
                    return "输出折线在第 " + i + " 段和第 " + j + " 段之间发生自交或近似自交。";
                }
            }
        }
        return null;
    }

    private static double intersectionTolerance(List<FittedPoint> points) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (FittedPoint point : points) {
            OrbitCalculator.Vector3 p = point.positionEciM;
            minX = Math.min(minX, p.x);
            minY = Math.min(minY, p.y);
            minZ = Math.min(minZ, p.z);
            maxX = Math.max(maxX, p.x);
            maxY = Math.max(maxY, p.y);
            maxZ = Math.max(maxZ, p.z);
        }
        double dx = maxX - minX;
        double dy = maxY - minY;
        double dz = maxZ - minZ;
        double diag = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return Math.max(1e-3, diag * 1e-9);
    }

    private static double segmentDistance(OrbitCalculator.Vector3 p1,
                                          OrbitCalculator.Vector3 q1,
                                          OrbitCalculator.Vector3 p2,
                                          OrbitCalculator.Vector3 q2) {
        OrbitCalculator.Vector3 d1 = q1.subtract(p1);
        OrbitCalculator.Vector3 d2 = q2.subtract(p2);
        OrbitCalculator.Vector3 r = p1.subtract(p2);
        double a = d1.dot(d1);
        double e = d2.dot(d2);
        double f = d2.dot(r);
        double s;
        double t;
        if (a <= 1e-18 && e <= 1e-18) {
            return p1.subtract(p2).norm();
        }
        if (a <= 1e-18) {
            s = 0.0;
            t = clamp(f / e, 0.0, 1.0);
        } else {
            double c = d1.dot(r);
            if (e <= 1e-18) {
                t = 0.0;
                s = clamp(-c / a, 0.0, 1.0);
            } else {
                double b = d1.dot(d2);
                double denom = a * e - b * b;
                if (denom != 0.0) {
                    s = clamp((b * f - c * e) / denom, 0.0, 1.0);
                } else {
                    s = 0.0;
                }
                double tNom = b * s + f;
                if (tNom < 0.0) {
                    t = 0.0;
                    s = clamp(-c / a, 0.0, 1.0);
                } else if (tNom > e) {
                    t = 1.0;
                    s = clamp((b - c) / a, 0.0, 1.0);
                } else {
                    t = tNom / e;
                }
            }
        }
        OrbitCalculator.Vector3 c1 = p1.add(d1.scale(s));
        OrbitCalculator.Vector3 c2 = p2.add(d2.scale(t));
        return c1.subtract(c2).norm();
    }

    private static FittedTrajectory simulate(SimulationRequest request,
                                             double outputStepSec) {
        double launchJ2000 = parseTimeCode(request.launchTimeUtc.trim());
        OrbitCalculator.Vector3 launchPosition = simulationEndpointEci(request, true, launchJ2000);
        SimulationEndpointPlan plan = buildSimulationEndpointPlan(request, launchJ2000, launchPosition);
        State initialState = new State(launchPosition, plan.initialVelocity);

        FittedTrajectory trajectory = new FittedTrajectory();
        trajectory.targetId = blankToDefault(request.targetId, "SIM-TGT");
        trajectory.model = TrajectoryModel.SYSTEM_SIMULATED_LAMBERT;
        trajectory.warnings.add("系统模拟使用端点约束Lambert射击法和二体RK4传播。");
        trajectory.warnings.add((plan.impactTimeProvided
                ? "落点时间作为硬端点约束输入。落点时间="
                : "落点时间由系统模拟模型计算得到。计算落点时间=")
                + labelTime(plan.impactTimeSec)
                + ", 飞行时长秒=" + String.format(Locale.US, "%.3f", plan.timeOfFlightSec)
                + ", 计算初速度米每秒=" + String.format(Locale.US, "%.6f", plan.initialVelocity.norm()));
        if (request.phaseEvents != null && !request.phaseEvents.isEmpty()) {
            trajectory.warnings.add("phaseEvents已作为预留扩展数据接收，但当前端点Lambert模拟模型暂未使用。数量="
                    + request.phaseEvents.size());
        }
        if (!isBlank(request.taskStartTime)) {
            double taskStartSec = parseTimeCode(request.taskStartTime);
            if (Math.abs(taskStartSec - launchJ2000) > TIME_UNIQUE_EPS_SEC) {
                trajectory.warnings.add("taskStartTime是系统模拟输出的任务/文件元数据；物理发射时间launchTimeUtc="
                        + labelTime(launchJ2000) + ", 任务开始时间=" + labelTime(taskStartSec));
            }
        }
        if (!isBlank(request.taskEndTime)) {
            double taskEndSec = parseTimeCode(request.taskEndTime);
            if (Math.abs(taskEndSec - plan.impactTimeSec) > TIME_UNIQUE_EPS_SEC) {
                trajectory.warnings.add("taskEndTime是系统模拟输出的任务/文件元数据；计算得到的物理落点时间="
                        + labelTime(plan.impactTimeSec) + ", 任务结束时间=" + labelTime(taskEndSec));
            }
        }

        List<Double> relativeTimes = simulationOutputRelativeTimes(plan.timeOfFlightSec, outputStepSec);
        State state = initialState;
        double currentRel = 0.0;
        FittedPoint previous = null;
        for (Double rel : relativeTimes) {
            double nextRel = rel.doubleValue();
            if (Math.abs(nextRel - currentRel) > TIME_UNIQUE_EPS_SEC) {
                state = propagateTwoBody(state, nextRel - currentRel);
                currentRel = nextRel;
            }
            FittedPoint point = pointFromState(launchJ2000 + nextRel, state);
            point.original = false;
            point.dtMs = previous == null ? 0.0 : (point.absoluteTimeSec - previous.absoluteTimeSec) * 1000.0;
            fillDerivedFields(point, launchJ2000);
            trajectory.points.add(point);
            previous = point;
        }

        if (!trajectory.points.isEmpty()) {
            FittedPoint last = trajectory.points.get(trajectory.points.size() - 1);
            double miss = last.positionEciM.subtract(plan.impactPosition).norm();
            trajectory.warnings.add("端点闭合检查：最终ECI偏差="
                    + String.format(Locale.US, "%.6f", miss) + " m.");
        }
        return trajectory;
    }

    private static SimulationEndpointPlan buildSimulationEndpointPlan(SimulationRequest request,
                                                                      double launchTimeSec,
                                                                      OrbitCalculator.Vector3 launchPosition) {
        validateSimulationEndpointRadius("发射点", launchPosition);
        String suppliedImpactTime = firstNonBlank(request.impactTimeUtc, request.impactTime);
        if (!isBlank(suppliedImpactTime)) {
            double impactTimeSec = parseTimeCode(suppliedImpactTime);
            if (impactTimeSec <= launchTimeSec + TIME_UNIQUE_EPS_SEC) {
                throw new IllegalArgumentException("impactTimeUtc必须晚于launchTimeUtc。");
            }
            double timeOfFlight = impactTimeSec - launchTimeSec;
            OrbitCalculator.Vector3 impactPosition = simulationEndpointEci(request, false, impactTimeSec);
            validateSimulationEndpointRadius("落点", impactPosition);
            LambertSolution solution = solveSimulationLambert(launchPosition, impactPosition, timeOfFlight);
            if (solution == null) {
                throw new IllegalArgumentException("无法根据输入的impactTimeUtc求解端点约束系统模拟弹道。");
            }
            SimulationEndpointPlan plan = new SimulationEndpointPlan();
            plan.timeOfFlightSec = timeOfFlight;
            plan.impactTimeSec = impactTimeSec;
            plan.impactPosition = impactPosition;
            plan.initialVelocity = solution.initialVelocity;
            plan.impactTimeProvided = true;
            return plan;
        }

        OrbitCalculator.Vector3 launchRangeReference = simulationEndpointRangeReference(request, true);
        OrbitCalculator.Vector3 impactRangeReference = simulationEndpointRangeReference(request, false);
        validateSimulationEndpointRadius("发射点射程参考位置", launchRangeReference);
        validateSimulationEndpointRadius("落点射程参考位置", impactRangeReference);

        double timeOfFlight = estimateSimulationTimeOfFlight(launchRangeReference, impactRangeReference);
        timeOfFlight = clamp(timeOfFlight, SIMULATION_MIN_TOF_SEC, SIMULATION_MAX_TOF_SEC);
        OrbitCalculator.Vector3 impactPosition = simulationEndpointEci(request, false, launchTimeSec + timeOfFlight);
        validateSimulationEndpointRadius("落点", impactPosition);

        LambertSolution solution = solveSimulationLambert(launchPosition, impactPosition, timeOfFlight);
        if (solution == null) {
            double[] factors = new double[]{0.75, 1.25, 1.5, 2.0, 0.5};
            for (double factor : factors) {
                double candidateTof = clamp(timeOfFlight * factor, SIMULATION_MIN_TOF_SEC, SIMULATION_MAX_TOF_SEC);
                OrbitCalculator.Vector3 candidateImpact = simulationEndpointEci(request, false, launchTimeSec + candidateTof);
                validateSimulationEndpointRadius("落点", candidateImpact);
                solution = solveSimulationLambert(launchPosition, candidateImpact, candidateTof);
                if (solution != null) {
                    timeOfFlight = candidateTof;
                    impactPosition = candidateImpact;
                    break;
                }
            }
        }
        if (solution == null) {
            throw new IllegalArgumentException("无法求解从发射点到落点的端点约束系统模拟弹道。");
        }

        SimulationEndpointPlan plan = new SimulationEndpointPlan();
        plan.timeOfFlightSec = timeOfFlight;
        plan.impactTimeSec = launchTimeSec + timeOfFlight;
        plan.impactPosition = impactPosition;
        plan.initialVelocity = solution.initialVelocity;
        plan.impactTimeProvided = false;
        return plan;
    }

    private static OrbitCalculator.Vector3 simulationEndpointEci(SimulationRequest request,
                                                                 boolean launch,
                                                                 double timeSec) {
        if (launch) {
            return parseBoundaryPositionEci(
                    String.valueOf(request.launchPointX),
                    String.valueOf(request.launchPointY),
                    String.valueOf(request.launchPointZ),
                    blankToDefault(request.launchPointCoordFrame, "J2000"),
                    blankToDefault(request.launchPointUnit, "m"),
                    timeSec
            );
        }
        return parseBoundaryPositionEci(
                String.valueOf(request.impactPointX),
                String.valueOf(request.impactPointY),
                String.valueOf(request.impactPointZ),
                blankToDefault(request.impactPointCoordFrame, "J2000"),
                blankToDefault(request.impactPointUnit, "m"),
                timeSec
        );
    }

    private static OrbitCalculator.Vector3 simulationEndpointRangeReference(SimulationRequest request,
                                                                            boolean launch) {
        String frameText = launch
                ? blankToDefault(request.launchPointCoordFrame, "J2000")
                : blankToDefault(request.impactPointCoordFrame, "J2000");
        String unitText = launch
                ? blankToDefault(request.launchPointUnit, "m")
                : blankToDefault(request.impactPointUnit, "m");
        double x = launch ? request.launchPointX : request.impactPointX;
        double y = launch ? request.launchPointY : request.impactPointY;
        double z = launch ? request.launchPointZ : request.impactPointZ;
        String frame = normalizeFrame(frameText);
        if (isGeodeticFrame(frame)) {
            return geodeticToEcef(y, x, z);
        }
        double scale = positionUnitScale(unitText);
        return new OrbitCalculator.Vector3(x * scale, y * scale, z * scale);
    }

    private static void validateSimulationEndpointRadius(String label,
                                                         OrbitCalculator.Vector3 position) {
        if (position == null || !isFinite(position.x) || !isFinite(position.y) || !isFinite(position.z)) {
            throw new IllegalArgumentException(label + "坐标无效。");
        }
        double radius = position.norm();
        if (radius < EARTH_RADIUS * SIMULATION_MIN_ENDPOINT_RADIUS_FACTOR) {
            throw new IllegalArgumentException(label + "半径过小，不符合弹道端点要求："
                    + String.format(Locale.US, "%.3f", radius)
                    + " m。若输入的是经度/纬度/高度，请选择WGS84经纬高，而不是J2000三维坐标。");
        }
        if (radius > EARTH_RADIUS * SIMULATION_MAX_ENDPOINT_RADIUS_FACTOR) {
            throw new IllegalArgumentException(label + "半径过大，超出当前导弹系统模拟模型适用范围："
                    + String.format(Locale.US, "%.3f", radius) + " m.");
        }
    }

    private static double estimateSimulationTimeOfFlight(OrbitCalculator.Vector3 launchPosition,
                                                         OrbitCalculator.Vector3 impactPosition) {
        double r1 = Math.max(1e-9, launchPosition.norm());
        double r2 = Math.max(1e-9, impactPosition.norm());
        double cosAngle = clamp(launchPosition.dot(impactPosition) / (r1 * r2), -1.0, 1.0);
        double angle = Math.acos(cosAngle);
        double referenceRadius = Math.max(EARTH_RADIUS, 0.5 * (r1 + r2));
        double arcRange = referenceRadius * angle;
        double chordRange = impactPosition.subtract(launchPosition).norm();
        double range = Math.max(arcRange, chordRange);
        if (range <= 1.0) {
            throw new IllegalArgumentException("发射点和落点距离过近，无法进行系统模拟弹道计算。");
        }

        double apogeeHeight = clamp(
                range * SIMULATION_APOGEE_RANGE_FACTOR,
                SIMULATION_MIN_APOGEE_M,
                SIMULATION_MAX_APOGEE_M
        );
        double gravity = EARTH_MU / (referenceRadius * referenceRadius);
        double verticalTime = 2.0 * Math.sqrt(2.0 * apogeeHeight / Math.max(1e-6, gravity));
        double rangeTime = range / SIMULATION_RANGE_SPEED_M_S;
        return clamp(verticalTime + rangeTime, SIMULATION_MIN_TOF_SEC, SIMULATION_MAX_TOF_SEC);
    }

    private static List<Double> simulationOutputRelativeTimes(double durationSec, double outputStepSec) {
        List<Double> times = new ArrayList<Double>();
        addUniqueTime(times, 0.0);
        addUniqueTime(times, durationSec);
        for (double t = 0.0; t <= durationSec + 1e-9; t += outputStepSec) {
            addUniqueTime(times, Math.min(t, durationSec));
        }
        Collections.sort(times);
        return times;
    }

    private static LambertSolution solveSimulationLambert(OrbitCalculator.Vector3 r1,
                                                          OrbitCalculator.Vector3 r2,
                                                          double dt) {
        LambertSolution initial = solveLambertUniversal(r1, r2, dt, true);
        OrbitCalculator.Vector3 velocity = initial == null
                ? estimateLambertVelocity(r1, r2, dt)
                : initial.initialVelocity;
        OrbitCalculator.Vector3 refined = refineLambertVelocity(r1, r2, dt, velocity);
        if (refined == null) {
            return initial;
        }
        State finalState = propagateTwoBody(new State(r1, refined), dt);
        double miss = finalState.r.subtract(r2).norm();
        if (miss > Math.max(10.0, r2.subtract(r1).norm() * 1e-5)) {
            return initial;
        }
        return new LambertSolution(refined, finalState.v);
    }

    private static LambertSolution solveLambertUniversal(OrbitCalculator.Vector3 r1,
                                                         OrbitCalculator.Vector3 r2,
                                                         double dt,
                                                         boolean shortWay) {
        if (dt <= 0.0) {
            return null;
        }
        double r1m = r1.norm();
        double r2m = r2.norm();
        if (r1m <= 1e-9 || r2m <= 1e-9) {
            return null;
        }
        double cosNu = clamp(r1.dot(r2) / (r1m * r2m), -1.0, 1.0);
        double sinNu = cross(r1, r2).norm() / (r1m * r2m);
        if (!shortWay) {
            sinNu = -sinNu;
        }

        double denom = 1.0 - cosNu;
        if (Math.abs(denom) < 1e-12) {
            return null;
        }
        double a = sinNu * Math.sqrt(r1m * r2m / denom);
        if (Math.abs(a) < 1e-12) {
            return null;
        }

        Bracket bracket = findLambertBracket(r1m, r2m, a, dt);
        if (bracket == null) {
            return null;
        }

        double lo = bracket.lo;
        double hi = bracket.hi;
        double flo = lambertTimeEquation(lo, r1m, r2m, a, dt);
        double z = 0.0;
        for (int i = 0; i < SIMULATION_LAMBERT_MAX_ITERATIONS * 8; i++) {
            z = 0.5 * (lo + hi);
            double fz = lambertTimeEquation(z, r1m, r2m, a, dt);
            if (!Double.isFinite(fz)) {
                return null;
            }
            if (Math.abs(fz) < 1e-9) {
                break;
            }
            if (flo * fz <= 0.0) {
                hi = z;
            } else {
                lo = z;
                flo = fz;
            }
        }

        double c = stumpffC(z);
        double s = stumpffS(z);
        if (c <= 0.0) {
            return null;
        }
        double y = r1m + r2m + a * (z * s - 1.0) / Math.sqrt(c);
        if (y <= 0.0) {
            return null;
        }
        double f = 1.0 - y / r1m;
        double g = a * Math.sqrt(y / EARTH_MU);
        double gdot = 1.0 - y / r2m;
        if (Math.abs(g) < 1e-12) {
            return null;
        }
        OrbitCalculator.Vector3 v1 = r2.subtract(r1.scale(f)).scale(1.0 / g);
        OrbitCalculator.Vector3 v2 = r2.scale(gdot).subtract(r1).scale(1.0 / g);
        return new LambertSolution(v1, v2);
    }

    private static Bracket findLambertBracket(double r1m, double r2m, double a, double dt) {
        double previousZ = Double.NaN;
        double previousF = Double.NaN;
        for (double z = -39.0; z <= 39.0; z += 0.05) {
            double fz = lambertTimeEquation(z, r1m, r2m, a, dt);
            if (!Double.isFinite(fz)) {
                continue;
            }
            if (Double.isFinite(previousF) && previousF * fz <= 0.0) {
                return new Bracket(previousZ, z);
            }
            previousZ = z;
            previousF = fz;
        }
        return null;
    }

    private static double lambertTimeEquation(double z,
                                              double r1m,
                                              double r2m,
                                              double a,
                                              double dt) {
        double c = stumpffC(z);
        double s = stumpffS(z);
        if (c <= 0.0) {
            return Double.NaN;
        }
        double y = r1m + r2m + a * (z * s - 1.0) / Math.sqrt(c);
        if (y < 0.0) {
            return Double.NaN;
        }
        return Math.pow(y / c, 1.5) * s + a * Math.sqrt(y) - Math.sqrt(EARTH_MU) * dt;
    }

    private static double stumpffC(double z) {
        if (Math.abs(z) < 1e-8) {
            return 0.5 - z / 24.0 + z * z / 720.0;
        }
        if (z > 0.0) {
            return (1.0 - Math.cos(Math.sqrt(z))) / z;
        }
        return (Math.cosh(Math.sqrt(-z)) - 1.0) / (-z);
    }

    private static double stumpffS(double z) {
        if (Math.abs(z) < 1e-8) {
            return 1.0 / 6.0 - z / 120.0 + z * z / 5040.0;
        }
        if (z > 0.0) {
            double root = Math.sqrt(z);
            return (root - Math.sin(root)) / (root * root * root);
        }
        double root = Math.sqrt(-z);
        return (Math.sinh(root) - root) / (root * root * root);
    }

    private static OrbitCalculator.Vector3 estimateLambertVelocity(OrbitCalculator.Vector3 r1,
                                                                   OrbitCalculator.Vector3 r2,
                                                                   double dt) {
        OrbitCalculator.Vector3 linear = r2.subtract(r1).scale(1.0 / dt);
        double r = Math.max(1e-9, r1.norm());
        OrbitCalculator.Vector3 up = r1.scale(1.0 / r);
        double gravity = EARTH_MU / (r * r);
        return linear.add(up.scale(0.5 * gravity * dt));
    }

    private static OrbitCalculator.Vector3 refineLambertVelocity(OrbitCalculator.Vector3 r1,
                                                                 OrbitCalculator.Vector3 r2,
                                                                 double dt,
                                                                 OrbitCalculator.Vector3 initialVelocity) {
        OrbitCalculator.Vector3 velocity = initialVelocity;
        for (int iteration = 0; iteration < SIMULATION_LAMBERT_MAX_ITERATIONS; iteration++) {
            State predicted = propagateTwoBody(new State(r1, velocity), dt);
            OrbitCalculator.Vector3 residual = r2.subtract(predicted.r);
            if (residual.norm() <= SIMULATION_LAMBERT_TOLERANCE_M) {
                return velocity;
            }

            double[][] jacobian = new double[3][3];
            for (int axis = 0; axis < 3; axis++) {
                OrbitCalculator.Vector3 perturb = axisVector(axis, SIMULATION_LAMBERT_PERTURB_M_S);
                State perturbed = propagateTwoBody(new State(r1, velocity.add(perturb)), dt);
                OrbitCalculator.Vector3 column = perturbed.r.subtract(predicted.r)
                        .scale(1.0 / SIMULATION_LAMBERT_PERTURB_M_S);
                jacobian[0][axis] = column.x;
                jacobian[1][axis] = column.y;
                jacobian[2][axis] = column.z;
            }

            double[] delta = solve3x3(jacobian, new double[]{residual.x, residual.y, residual.z});
            if (delta == null) {
                return iteration == 0 ? null : velocity;
            }
            OrbitCalculator.Vector3 dv = new OrbitCalculator.Vector3(delta[0], delta[1], delta[2]);
            if (!isUseful(dv) || dv.norm() > 1000.0) {
                return iteration == 0 ? null : velocity;
            }
            velocity = velocity.add(dv);
            if (dv.norm() < 1e-7) {
                return velocity;
            }
        }
        return velocity;
    }

    private static OrbitCalculator.Vector3 cross(OrbitCalculator.Vector3 a, OrbitCalculator.Vector3 b) {
        return new OrbitCalculator.Vector3(
                a.y * b.z - a.z * b.y,
                a.z * b.x - a.x * b.z,
                a.x * b.y - a.y * b.x
        );
    }

    private static State rk4SimulationStep(State state,
                                           double t,
                                           double h,
                                           SimulationRequest request,
                                           TrajectoryModel model) {
        State k1 = derivative(state, t, request, model);
        State k2 = derivative(addState(state, k1, h * 0.5), t + h * 0.5, request, model);
        State k3 = derivative(addState(state, k2, h * 0.5), t + h * 0.5, request, model);
        State k4 = derivative(addState(state, k3, h), t + h, request, model);

        OrbitCalculator.Vector3 dr = k1.r.add(k2.r.scale(2.0)).add(k3.r.scale(2.0)).add(k4.r).scale(h / 6.0);
        OrbitCalculator.Vector3 dv = k1.v.add(k2.v.scale(2.0)).add(k3.v.scale(2.0)).add(k4.v).scale(h / 6.0);
        return new State(state.r.add(dr), state.v.add(dv));
    }

    private static State derivative(State state, double t, SimulationRequest request, TrajectoryModel model) {
        return new State(state.v, simulationAcceleration(state, t, request, model));
    }

    private static State addState(State state, State derivative, double scale) {
        return new State(state.r.add(derivative.r.scale(scale)), state.v.add(derivative.v.scale(scale)));
    }

    private static OrbitCalculator.Vector3 simulationAcceleration(State state,
                                                                  double relTime,
                                                                  SimulationRequest request,
                                                                  TrajectoryModel model) {
        OrbitCalculator.Vector3 acceleration = accelerationJ2(state.r);
        if (model == TrajectoryModel.SYSTEM_SIMULATED_BOOST && relTime <= Math.max(0.0, request.boostDurationSec)) {
            double speed = state.v.norm();
            if (speed > 1e-9) {
                acceleration = acceleration.add(state.v.scale(Math.max(0.0, request.boostAccelerationMs2) / speed));
            }
        }
        double cdAreaOverMass = request.dragCdAreaOverMass;
        if (model == TrajectoryModel.SYSTEM_SIMULATED_AERO && cdAreaOverMass <= 0.0) {
            cdAreaOverMass = 0.003;
        }
        if (cdAreaOverMass > 0.0) {
            double altitude = Math.max(0.0, OrbitCalculator.ecefToGeodetic(
                    OrbitCalculator.eciToEcefPos(state.r, 0.0)
            )[2]);
            double density = 1.225 * Math.exp(-altitude / 8500.0);
            OrbitCalculator.Vector3 vRel = inertialRelativeVelocity(state.r, state.v);
            double speed = vRel.norm();
            if (speed > 1e-9) {
                acceleration = acceleration.add(vRel.scale(-0.5 * density * cdAreaOverMass * speed));
            }
        }
        return acceleration;
    }

    private static OrbitCalculator.Vector3 accelerationJ2(OrbitCalculator.Vector3 position) {
        double x = position.x;
        double y = position.y;
        double z = position.z;
        double r2 = x * x + y * y + z * z;
        double r = Math.sqrt(r2);
        double r3 = r2 * r;
        double central = -EARTH_MU / r3;
        double z2OverR2 = (z * z) / r2;
        double j2Factor = 1.5 * EARTH_J2 * EARTH_MU * EARTH_RADIUS * EARTH_RADIUS / (r2 * r2 * r);
        double common = 5.0 * z2OverR2 - 1.0;
        return new OrbitCalculator.Vector3(
                central * x + j2Factor * x * common,
                central * y + j2Factor * y * common,
                central * z + j2Factor * z * (5.0 * z2OverR2 - 3.0)
        );
    }

    private static OrbitCalculator.Vector3 inertialRelativeVelocity(OrbitCalculator.Vector3 rEci,
                                                                    OrbitCalculator.Vector3 vEci) {
        OrbitCalculator.Vector3 omegaCrossR = new OrbitCalculator.Vector3(
                -OrbitCalculator.EARTH_ROTATION_RATE_RAD_S * rEci.y,
                OrbitCalculator.EARTH_ROTATION_RATE_RAD_S * rEci.x,
                0.0
        );
        return vEci.subtract(omegaCrossR);
    }

    private static BoundaryInfo boundaryInfo(List<Package102Record> records, boolean launch) {
        BoundaryInfo info = new BoundaryInfo();
        String time = boundaryField(records, launch, "time");
        String x = boundaryField(records, launch, "x");
        String y = boundaryField(records, launch, "y");
        String z = boundaryField(records, launch, "z");
        if (isBlank(time) && isBlank(x) && isBlank(y) && isBlank(z)) {
            return info;
        }
        if (isBlank(time) || isBlank(x) || isBlank(y) || isBlank(z)) {
            throw new IllegalArgumentException((launch ? "发射端点" : "落点端点")
                    + "需要同时提供time_code和pos_x/pos_y/pos_z。");
        }
        info.present = true;
        info.timeText = time;
        info.secondPrecision = hasTimeSecondPrecision(time);
        info.timeSec = parseTimeCode(time);
        info.frame = blankToDefault(boundaryField(records, launch, "frame"), "J2000");
        info.unit = boundaryField(records, launch, "unit");
        info.positionEciM = parseBoundaryPositionEci(x, y, z, info.frame, info.unit, info.timeSec);
        return info;
    }

    private static String boundaryField(List<Package102Record> records, boolean launch, String field) {
        if (records == null) {
            return null;
        }
        for (Package102Record record : records) {
            if (record == null) {
                continue;
            }
            String value;
            if (launch) {
                if ("time".equals(field)) value = record.launch_time_code;
                else if ("frame".equals(field)) value = record.launch_coord_frame;
                else if ("x".equals(field)) value = record.launch_pos_x;
                else if ("y".equals(field)) value = record.launch_pos_y;
                else if ("z".equals(field)) value = record.launch_pos_z;
                else if ("unit".equals(field)) value = record.launch_pos_unit;
                else value = null;
            } else {
                if ("time".equals(field)) value = record.impact_time_code;
                else if ("frame".equals(field)) value = record.impact_coord_frame;
                else if ("x".equals(field)) value = record.impact_pos_x;
                else if ("y".equals(field)) value = record.impact_pos_y;
                else if ("z".equals(field)) value = record.impact_pos_z;
                else if ("unit".equals(field)) value = record.impact_pos_unit;
                else value = null;
            }
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static OrbitCalculator.Vector3 parseBoundaryPositionEci(String xText,
                                                                    String yText,
                                                                    String zText,
                                                                    String frameText,
                                                                    String unitText,
                                                                    double timeSec) {
        String frame = normalizeFrame(frameText);
        if (isGeodeticFrame(frame)) {
            double lonDeg = parseNumeric(xText);
            double latDeg = parseNumeric(yText);
            double heightM = parseNumeric(zText);
            return ecefToEci(geodeticToEcef(latDeg, lonDeg, heightM), timeSec);
        }
        double scale = positionUnitScale(unitText);
        OrbitCalculator.Vector3 position = new OrbitCalculator.Vector3(
                parseNumeric(xText) * scale,
                parseNumeric(yText) * scale,
                parseNumeric(zText) * scale
        );
        if (isEciFrame(frame)) {
            return position;
        }
        if (isEcefFrame(frame)) {
            return ecefToEci(position, timeSec);
        }
        throw new IllegalArgumentException("不支持的端点坐标系：" + frameText
                + "。支持的取值：J2000/ECI、ECEF、LLA/LLH/WGS84。");
    }

    private static String normalizeFrame(String frameText) {
        return blankToDefault(frameText, "J2000")
                .trim()
                .toUpperCase(Locale.US)
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "");
    }

    private static boolean isEciFrame(String frame) {
        return "J2000".equals(frame) || "ECI".equals(frame) || "ECIJ2000".equals(frame);
    }

    private static boolean isEcefFrame(String frame) {
        return "ECEF".equals(frame) || "ITRF".equals(frame) || "WGS84ECEF".equals(frame);
    }

    private static boolean isGeodeticFrame(String frame) {
        return "LLA".equals(frame) || "LLH".equals(frame) || "BLH".equals(frame)
                || "GEODETIC".equals(frame) || "WGS84".equals(frame) || "WGS84LLA".equals(frame);
    }

    private static double positionUnitScale(String unitText) {
        if (isBlank(unitText)) {
            return PACKAGE102_POSITION_UNIT_M;
        }
        String unit = unitText.trim().toLowerCase(Locale.US).replace(" ", "");
        if ("m".equals(unit) || "meter".equals(unit) || "meters".equals(unit)) {
            return 1.0;
        }
        if ("km".equals(unit) || "kilometer".equals(unit) || "kilometers".equals(unit)) {
            return 1000.0;
        }
        if ("0.1m".equals(unit) || "dm".equals(unit) || "package102".equals(unit) || "102".equals(unit)) {
            return PACKAGE102_POSITION_UNIT_M;
        }
        return parseNumeric(unitText);
    }

    private static String labelTime(double timeSec) {
        return OrbitCalculator.j2000ToUtcMillis(timeSec).replace('/', '-');
    }

    private static Path writeTrajectoryCsv(FittedTrajectory trajectory,
                                           Path outputDir,
                                           String taskName,
                                           String taskStartTime,
                                           String taskEndTime,
                                           String created) throws IOException {
        String fileName = fileToken(taskName)
                + "_" + fileToken(blankToDefault(taskStartTime, "start"))
                + "_" + fileToken(blankToDefault(taskEndTime, "end"))
                + "_" + fileToken(trajectory.targetId)
                + "_" + created
                + ".csv";
        Path path = outputDir.resolve(fileName);
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("YYYY,MM,dd,HH,mm,ss.SSS,ECEF_X,ECEF_Y,ECEF_Z,energy,dt,ECI_X,ECI_Y,ECI_Z,ECI_VX,ECI_VY,ECI_VZ,ECEF_Vx,ECEF_Vy,ECEF_Vz,isOriginal");
            writer.newLine();
            for (FittedPoint point : trajectory.points) {
                writer.write(csvRow(point));
                writer.newLine();
            }
        }
        return path;
    }

    private static String csvRow(FittedPoint p) {
        String utc = OrbitCalculator.j2000ToUtcMillis(p.absoluteTimeSec).replace('/', '-');
        String[] dateTime = utc.split(" ");
        String[] date = dateTime[0].split("-");
        String[] time = dateTime[1].split(":");
        return String.format(Locale.US,
                "%s,%s,%s,%s,%s,%s,%.3f,%.3f,%.3f,%.9f,%.3f,%.3f,%.3f,%.3f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%d",
                date[0], date[1], date[2],
                time[0], time[1], time[2],
                p.positionEcefM.x, p.positionEcefM.y, p.positionEcefM.z,
                p.energy,
                p.dtMs,
                p.positionEciM.x, p.positionEciM.y, p.positionEciM.z,
                p.velocityEciMs.x, p.velocityEciMs.y, p.velocityEciMs.z,
                p.velocityEcefMs.x, p.velocityEcefMs.y, p.velocityEcefMs.z,
                p.original ? 1 : 0
        );
    }

    private static String[] splitCsvLine(String line) {
        return line.split(",", -1);
    }

    private static int requiredColumn(String[] header, String name) {
        int index = optionalColumn(header, name);
        if (index < 0) {
            throw new IllegalArgumentException("弹道CSV缺少必要列：" + name);
        }
        return index;
    }

    private static int optionalColumn(String[] header, String name) {
        for (int i = 0; i < header.length; i++) {
            if (name.equals(header[i] == null ? "" : header[i].trim())) {
                return i;
            }
        }
        return -1;
    }

    private static double parseCsvRowTime(String[] row,
                                          int yearIndex,
                                          int monthIndex,
                                          int dayIndex,
                                          int hourIndex,
                                          int minuteIndex,
                                          int secondIndex) {
        String time = row[yearIndex].trim()
                + "/" + row[monthIndex].trim()
                + "/" + row[dayIndex].trim()
                + " " + row[hourIndex].trim()
                + ":" + row[minuteIndex].trim()
                + ":" + row[secondIndex].trim();
        return parseTimeCode(time);
    }

    private static void applyUtcToCsvRow(String[] row,
                                         double timeSec,
                                         int yearIndex,
                                         int monthIndex,
                                         int dayIndex,
                                         int hourIndex,
                                         int minuteIndex,
                                         int secondIndex) {
        String utc = OrbitCalculator.j2000ToUtcMillis(timeSec).replace('/', '-');
        String[] dateTime = utc.split(" ");
        String[] date = dateTime[0].split("-");
        String[] time = dateTime[1].split(":");
        row[yearIndex] = date[0];
        row[monthIndex] = date[1];
        row[dayIndex] = date[2];
        row[hourIndex] = time[0];
        row[minuteIndex] = time[1];
        row[secondIndex] = time[2];
    }

    private static String joinCsvLine(List<String> columns) {
        return joinCsvLine(columns.toArray(new String[0]));
    }

    private static String joinCsvLine(String[] columns) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(columns[i] == null ? "" : columns[i]);
        }
        return builder.toString();
    }

    private static TrajectorySample toSample(Package102Record record, int sourceIndex) {
        if (record == null) {
            throw new IllegalArgumentException("package102在索引 " + sourceIndex + " 处存在空记录。");
        }
        CoordinateInfo c = record.coordinate();
        TargetInfo t = record.target();
        TrajectorySample sample = new TrajectorySample();
        sample.sourceIndex = sourceIndex;
        sample.targetInfo = t;
        sample.targetId = blankToDefault(t.track_tgt_id, "TGT-" + (sourceIndex + 1));
        sample.absoluteTimeSec = parseTimeCode(firstNonBlank(c.tack3d_time_code, c.track3d_time_code));
        sample.positionEciM = new OrbitCalculator.Vector3(
                parseScaled(c.tack3d_tgt_pos_x, PACKAGE102_POSITION_UNIT_M),
                parseScaled(c.tack3d_tgt_pos_y, PACKAGE102_POSITION_UNIT_M),
                parseScaled(c.tack3d_tgt_pos_z, PACKAGE102_POSITION_UNIT_M)
        );
        sample.velocityEciMs = new OrbitCalculator.Vector3(
                parseScaled(c.tack3d_tgt_vel_x, PACKAGE102_VELOCITY_UNIT_M_S),
                parseScaled(c.tack3d_tgt_vel_y, PACKAGE102_VELOCITY_UNIT_M_S),
                parseScaled(c.tack3d_tgt_vel_z, PACKAGE102_VELOCITY_UNIT_M_S)
        );
        sample.accelerationEciMs2 = new OrbitCalculator.Vector3(
                parseScaled(blankToDefault(c.tack3d_tgt_acc_x, "0"), PACKAGE102_ACCELERATION_UNIT_M_S2),
                parseScaled(blankToDefault(c.tack3d_tgt_acc_y, "0"), PACKAGE102_ACCELERATION_UNIT_M_S2),
                parseScaled(blankToDefault(c.tack3d_tgt_acc_z, "0"), PACKAGE102_ACCELERATION_UNIT_M_S2)
        );
        return sample;
    }

    private static void sortSamplesByTime(List<TrajectorySample> samples) {
        Collections.sort(samples, new Comparator<TrajectorySample>() {
            @Override
            public int compare(TrajectorySample a, TrajectorySample b) {
                int timeCompare = Double.compare(a.absoluteTimeSec, b.absoluteTimeSec);
                if (timeCompare != 0) {
                    return timeCompare;
                }
                return Integer.compare(a.sourceIndex, b.sourceIndex);
            }
        });
    }

    private static void validateSamples(List<TrajectorySample> samples) {
        for (int i = 0; i < samples.size(); i++) {
            TrajectorySample sample = samples.get(i);
            if (sample.positionEciM == null) {
                throw new IllegalArgumentException("第 " + i + " 个轨迹样本缺少位置信息。");
            }
            validateFiniteVector(sample.positionEciM, sample, i, "位置");
            if (sample.velocityEciMs != null) {
                validateFiniteVector(sample.velocityEciMs, sample, i, "速度");
            }
            if (sample.accelerationEciMs2 != null) {
                validateFiniteVector(sample.accelerationEciMs2, sample, i, "加速度");
            }
            if (!isFinite(sample.absoluteTimeSec)) {
                throw new IllegalArgumentException("第 " + i + " 个轨迹样本时间无效。");
            }
            if (i > 0) {
                double dt = sample.absoluteTimeSec - samples.get(i - 1).absoluteTimeSec;
                if (dt <= TIME_UNIQUE_EPS_SEC) {
                    TrajectorySample previous = samples.get(i - 1);
                    throw new IllegalArgumentException("同一目标的package102轨迹点允许乱序和断档，但排序后同一时刻只能保留一个轨迹点；目标ID="
                            + blankToDefault(sample.targetId, "UNKNOWN")
                            + "，重复或过近时间="
                            + OrbitCalculator.j2000ToUtcMillis(sample.absoluteTimeSec)
                            + "，当前源序号=" + sample.sourceIndex
                            + "，上一源序号=" + previous.sourceIndex
                            + "。请删除重复时刻，或先合并同一时刻的目标点。");
                }
            }
        }
    }

    private static void validateFiniteVector(OrbitCalculator.Vector3 value,
                                             TrajectorySample sample,
                                             int sortedIndex,
                                             String label) {
        if (value == null || !isFinite(value.x) || !isFinite(value.y) || !isFinite(value.z)) {
            throw new IllegalArgumentException("第 " + sortedIndex + " 个轨迹样本" + label + "数值无效；目标ID="
                    + blankToDefault(sample.targetId, "UNKNOWN")
                    + "，源序号=" + sample.sourceIndex
                    + "，" + label + "=("
                    + formatDebugDouble(value == null ? Double.NaN : value.x) + ", "
                    + formatDebugDouble(value == null ? Double.NaN : value.y) + ", "
                    + formatDebugDouble(value == null ? Double.NaN : value.z) + ")。");
        }
    }

    private static List<String> normalizePackage102StateUnits(List<TrajectorySample> samples) {
        List<String> warnings = new ArrayList<String>();
        if (samples.size() < 2) {
            return warnings;
        }
        List<Double> ratios = new ArrayList<Double>();
        for (int i = 0; i < samples.size() - 1; i++) {
            TrajectorySample a = samples.get(i);
            TrajectorySample b = samples.get(i + 1);
            double dt = b.absoluteTimeSec - a.absoluteTimeSec;
            if (dt <= TIME_UNIQUE_EPS_SEC) {
                continue;
            }
            double chordSpeed = b.positionEciM.subtract(a.positionEciM).norm() / dt;
            double suppliedSpeed = averageUsefulSpeed(a.velocityEciMs, b.velocityEciMs);
            if (chordSpeed > 1e-6 && suppliedSpeed > 1e-6) {
                ratios.add(chordSpeed / suppliedSpeed);
            }
        }
        if (ratios.isEmpty()) {
            return warnings;
        }
        Collections.sort(ratios);
        double medianRatio = ratios.get(ratios.size() / 2);
        if (medianRatio >= TARGET_ORBIT_MIN_UNIT_RATIO && medianRatio <= TARGET_ORBIT_MAX_UNIT_RATIO) {
            for (TrajectorySample sample : samples) {
                if (sample.velocityEciMs != null) {
                    sample.velocityEciMs = sample.velocityEciMs.scale(TARGET_ORBIT_VELOCITY_RESCALE);
                }
                if (sample.accelerationEciMs2 != null) {
                    sample.accelerationEciMs2 = sample.accelerationEciMs2.scale(TARGET_ORBIT_ACCELERATION_RESCALE);
                }
            }
            warnings.add("检测到targetOrbit风格的package102单位比例，速度按0.1m/s单位解释，加速度按0.1m/s^2单位解释。弦线速度比中位数="
                    + String.format(Locale.US, "%.3f", medianRatio));
        }
        return warnings;
    }

    private static double averageUsefulSpeed(OrbitCalculator.Vector3 a, OrbitCalculator.Vector3 b) {
        double sum = 0.0;
        int count = 0;
        if (isUseful(a)) {
            sum += a.norm();
            count++;
        }
        if (isUseful(b)) {
            sum += b.norm();
            count++;
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private static FittingPolicy policyFor(TargetInfo info) {
        int type = parseCode(info == null ? null : info.prop_tgt_type_8, -1);
        int shape = parseCode(info == null ? null : info.prop_tgt_shape_8, -1);
        int phase = parseCode(info == null ? null : info.prop_tgt_flight_phase_8, -1);
        if (shape == 0x03) {
            return new FittingPolicy(TrajectoryModel.CONSERVATIVE_FRAGMENT_DYNAMIC, 0.10, 0.00, 0.45, 0.15);
        }
        if (type == 0x02 || type == 0x03) {
            return new FittingPolicy(TrajectoryModel.ORBITAL_TWO_BODY, 0.85, 0.45, 1.35, 0.85);
        }
        if (type == 0x05) {
            return new FittingPolicy(TrajectoryModel.LAUNCH_VEHICLE_DYNAMIC, 0.75, 0.35, 1.10, 0.70);
        }
        if (phase == 0x00) {
            return new FittingPolicy(TrajectoryModel.BOOST_POWERED_DYNAMIC, 0.75, 0.35, 1.05, 0.70);
        }
        if (phase == 0x01) {
            return new FittingPolicy(TrajectoryModel.BALLISTIC_MIDCOURSE_TWO_BODY, 0.80, 0.35, 1.20, 0.75);
        }
        if (phase == 0x02) {
            return new FittingPolicy(TrajectoryModel.BALLISTIC_TERMINAL_TWO_BODY, 0.60, 0.25, 0.85, 0.50);
        }
        if (phase == 0x03 || type == 0x00 || type == 0x04) {
            return new FittingPolicy(TrajectoryModel.AERODYNAMIC_GLIDE_DYNAMIC, 0.45, 0.20, 0.70, 0.35);
        }
        return new FittingPolicy(TrajectoryModel.UNKNOWN_CONSERVATIVE_DYNAMIC, 0.25, 0.00, 0.55, 0.20);
    }

    private static TrajectoryModel simulationModelFor(SimulationRequest request) {
        int type = parseCode(request.targetTypeCode, -1);
        int phase = parseCode(request.flightPhaseCode, -1);
        if (phase == 0x00 || type == 0x05) {
            return TrajectoryModel.SYSTEM_SIMULATED_BOOST;
        }
        if (phase == 0x03 || type == 0x00 || type == 0x04) {
            return TrajectoryModel.SYSTEM_SIMULATED_AERO;
        }
        return TrajectoryModel.SYSTEM_SIMULATED_J2;
    }

    private static OrbitCalculator.Vector3 geodeticToEcef(double latDeg, double lonDeg, double heightM) {
        double lat = Math.toRadians(latDeg);
        double lon = Math.toRadians(lonDeg);
        double f = 1.0 / 298.257223563;
        double e2 = f * (2.0 - f);
        double sinLat = Math.sin(lat);
        double cosLat = Math.cos(lat);
        double n = EARTH_RADIUS / Math.sqrt(1.0 - e2 * sinLat * sinLat);
        return new OrbitCalculator.Vector3(
                (n + heightM) * cosLat * Math.cos(lon),
                (n + heightM) * cosLat * Math.sin(lon),
                (n * (1.0 - e2) + heightM) * sinLat
        );
    }

    private static OrbitCalculator.Vector3 localVelocityToEcef(double latDeg,
                                                               double lonDeg,
                                                               double speed,
                                                               double azimuthDeg,
                                                               double elevationDeg) {
        double lat = Math.toRadians(latDeg);
        double lon = Math.toRadians(lonDeg);
        double az = Math.toRadians(azimuthDeg);
        double el = Math.toRadians(elevationDeg);
        OrbitCalculator.Vector3 east = new OrbitCalculator.Vector3(-Math.sin(lon), Math.cos(lon), 0.0);
        OrbitCalculator.Vector3 north = new OrbitCalculator.Vector3(
                -Math.sin(lat) * Math.cos(lon),
                -Math.sin(lat) * Math.sin(lon),
                Math.cos(lat)
        );
        OrbitCalculator.Vector3 up = new OrbitCalculator.Vector3(
                Math.cos(lat) * Math.cos(lon),
                Math.cos(lat) * Math.sin(lon),
                Math.sin(lat)
        );
        double horizontal = speed * Math.cos(el);
        return north.scale(horizontal * Math.cos(az))
                .add(east.scale(horizontal * Math.sin(az)))
                .add(up.scale(speed * Math.sin(el)));
    }

    private static OrbitCalculator.Vector3 ecefToEci(OrbitCalculator.Vector3 ecef, double j2000Sec) {
        return OrbitCalculator.ecefToEciPos(ecef, j2000Sec);
    }

    private static OrbitCalculator.Vector3 ecefVelocityToEci(OrbitCalculator.Vector3 vEcef,
                                                             OrbitCalculator.Vector3 rEcef,
                                                             double j2000Sec) {
        return OrbitCalculator.ecefToEciVel(vEcef, rEcef, j2000Sec);
    }

    private static double parseTimeCode(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("102时间码不能为空。");
        }
        String value = text.trim();
        if (value.matches("\\d{14}(\\.\\d+)?")) {
            String s = value;
            String formatted = s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6, 8)
                    + " " + s.substring(8, 10) + ":" + s.substring(10, 12) + ":" + s.substring(12);
            return OrbitCalculator.utcToJ2000Sec(formatted);
        }
        if (value.contains("T")) {
            value = value.replace('T', ' ').replace("Z", "");
        }
        if (value.contains("-") || value.contains("/")) {
            value = normalizeUtcTimeText(value);
            return OrbitCalculator.utcToJ2000Sec(value);
        }
        return Double.parseDouble(value);
    }

    private static String normalizeUtcTimeText(String value) {
        String text = value.trim().replaceAll("\\s+", " ");
        String[] parts = text.split(" ");
        if (parts.length == 1) {
            return parts[0] + " 00:00:00";
        }
        String time = parts[1];
        int colonCount = 0;
        for (int i = 0; i < time.length(); i++) {
            if (time.charAt(i) == ':') {
                colonCount++;
            }
        }
        if (colonCount == 0) {
            time = time + ":00:00";
        } else if (colonCount == 1) {
            time = time + ":00";
        }
        return parts[0] + " " + time;
    }

    private static boolean hasTimeSecondPrecision(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        String value = text.trim();
        if (value.matches("\\d{14}(\\.\\d+)?")) {
            return true;
        }
        if (value.contains("T")) {
            value = value.replace('T', ' ').replace("Z", "");
        }
        if (!(value.contains("-") || value.contains("/"))) {
            return true;
        }
        value = value.replaceAll("\\s+", " ");
        String[] parts = value.split(" ");
        if (parts.length < 2) {
            return false;
        }
        int colonCount = 0;
        for (int i = 0; i < parts[1].length(); i++) {
            if (parts[1].charAt(i) == ':') {
                colonCount++;
            }
        }
        return colonCount >= 2;
    }

    private static double parseOptionalTimeCode(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Double.NaN;
        }
        return parseTimeCode(text);
    }

    private static double parseScaled(String text, double unit) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少102数值字段。");
        }
        return parseNumeric(text) * unit;
    }

    private static double parseNumeric(String text) {
        String value = text.trim();
        if (value.startsWith("0x") || value.startsWith("0X")
                || value.startsWith("-0x") || value.startsWith("-0X")) {
            return Long.decode(value).doubleValue();
        }
        return Double.parseDouble(value);
    }

    private static int parseCode(String text, int fallback) {
        if (text == null || text.trim().isEmpty()) {
            return fallback;
        }
        try {
            return (int) Math.round(parseNumeric(text));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static OrbitCalculator.Vector3 limitVector(OrbitCalculator.Vector3 value, double limit) {
        if (value == null || limit <= 0.0) {
            return zero();
        }
        double norm = value.norm();
        if (norm <= limit || norm < 1e-12) {
            return value;
        }
        return value.scale(limit / norm);
    }

    private static OrbitCalculator.Vector3 blend(OrbitCalculator.Vector3 a,
                                                 OrbitCalculator.Vector3 b,
                                                 double weight) {
        double w = clamp(weight, 0.0, 1.0);
        return a.scale(1.0 - w).add(b.scale(w));
    }

    private static boolean isUseful(OrbitCalculator.Vector3 value) {
        return value != null && isFinite(value.x) && isFinite(value.y) && isFinite(value.z) && value.norm() > 1e-9;
    }

    private static OrbitCalculator.Vector3 zero() {
        return new OrbitCalculator.Vector3(0.0, 0.0, 0.0);
    }

    private static double normalizeStep(double stepSec) {
        if (!isFinite(stepSec) || stepSec < MIN_STEP_SEC) {
            throw new IllegalArgumentException("插值/输出步长必须大于等于 " + MIN_STEP_SEC + " 秒。");
        }
        return stepSec;
    }

    private static double firstPositive(double a, double b, double fallback) {
        if (isFinite(a) && a > 0.0) {
            return a;
        }
        if (isFinite(b) && b > 0.0) {
            return b;
        }
        return fallback;
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static String formatDebugDouble(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (Double.isInfinite(value)) {
            return value > 0.0 ? "Infinity" : "-Infinity";
        }
        return String.format(Locale.US, "%.6f", value);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.trim().isEmpty()) return a;
        return b;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String fileToken(String value) {
        String token = blankToDefault(value, "value").replaceAll("[^A-Za-z0-9._-]+", "_");
        token = token.replaceAll("_+", "_");
        if (token.length() > 80) {
            token = token.substring(0, 80);
        }
        return token;
    }
}
