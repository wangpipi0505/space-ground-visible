package com.runisys.orbit.spacevisibility.domain.orbit;

import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import org.hipparchus.ode.nonstiff.ClassicalRungeKuttaIntegrator;
import org.orekit.forces.gravity.HolmesFeatherstoneAttractionModel;
import org.orekit.forces.gravity.potential.GravityFieldFactory;
import org.orekit.forces.gravity.potential.NormalizedSphericalHarmonicsProvider;
import org.orekit.forces.gravity.potential.TideSystem;
import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.orbits.KeplerianOrbit;
import org.orekit.orbits.OrbitType;
import org.orekit.orbits.PositionAngleType;
import org.orekit.propagation.SpacecraftState;
import org.orekit.propagation.numerical.NumericalPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScale;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.Constants;
import org.orekit.utils.PVCoordinates;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

public final class KeplerOrbitStateProvider implements OrbitStateProvider {

    private static final double DEFAULT_INTEGRATION_STEP_SEC = 10.0;
    /** 正向积分过程中记录锚点状态的时间间隔（秒），用于小幅回退请求的就近重启。 */
    private static final double ANCHOR_STEP_SEC = 60.0;
    /** 单线程保留的锚点状态上限，防止超长规划窗口无限累积。 */
    private static final int MAX_ANCHORS = 8192;
    private static final Frame INERTIAL_FRAME = FramesFactory.getEME2000();
    /** 数值积分在 EME2000 惯性系进行，对外输出统一转换为 TEME 系，与 TLE/SGP4 输出及 GMST 站心几何一致。 */
    private static final Frame OUTPUT_FRAME = FramesFactory.getTEME();
    private static final NormalizedSphericalHarmonicsProvider J2_GRAVITY_FIELD = createJ2GravityField();

    private final TimeScale utc;
    private final KeplerianOrbit initialOrbit;
    private final SpacecraftState initialState;
    private final double integrationStepSec;
    private final ThreadLocal<ThreadPropagation> propagation;

    public KeplerOrbitStateProvider(double semiMajorAxisM,
                                    double eccentricity,
                                    double inclinationDeg,
                                    double raanDeg,
                                    double argumentOfPerigeeDeg,
                                    double meanAnomalyDeg,
                                    Instant epoch) {
        this(semiMajorAxisM, eccentricity, inclinationDeg, raanDeg, argumentOfPerigeeDeg, meanAnomalyDeg,
                epoch, DEFAULT_INTEGRATION_STEP_SEC);
    }

    /**
     * 使用历元六根数构建二体中心引力和 J2 摄动下的固定步长 RK4 数值外推器。
     * 每个计算线程获得独立传播器，保证同一轨道被并行查询时不会共享可变积分状态。
     *
     * @param semiMajorAxisM 半长轴，单位米
     * @param eccentricity 偏心率
     * @param inclinationDeg 轨道倾角，单位度
     * @param raanDeg 升交点赤经，单位度
     * @param argumentOfPerigeeDeg 近地点幅角，单位度
     * @param meanAnomalyDeg 历元平近点角，单位度
     * @param epoch 历元 UTC 时刻
     * @param integrationStepSec RK4 积分步长，单位秒
     */
    public KeplerOrbitStateProvider(double semiMajorAxisM,
                                    double eccentricity,
                                    double inclinationDeg,
                                    double raanDeg,
                                    double argumentOfPerigeeDeg,
                                    double meanAnomalyDeg,
                                    Instant epoch,
                                    double integrationStepSec) {
        validate(semiMajorAxisM, eccentricity, inclinationDeg, raanDeg, argumentOfPerigeeDeg, meanAnomalyDeg,
                epoch, integrationStepSec);
        OrekitDataInitializer.initialize();
        this.utc = TimeScalesFactory.getUTC();
        AbsoluteDate initialDate = new AbsoluteDate(epoch, utc);
        this.initialOrbit = new KeplerianOrbit(semiMajorAxisM, eccentricity,
                Math.toRadians(inclinationDeg), Math.toRadians(argumentOfPerigeeDeg), Math.toRadians(raanDeg),
                Math.toRadians(meanAnomalyDeg), PositionAngleType.MEAN, INERTIAL_FRAME, initialDate,
                Constants.WGS84_EARTH_MU);
        this.initialState = new SpacecraftState(initialOrbit);
        this.integrationStepSec = integrationStepSec;
        this.propagation = new ThreadLocal<ThreadPropagation>() {
            @Override
            protected ThreadPropagation initialValue() {
                return new ThreadPropagation();
            }
        };
    }

    /**
     * 返回指定时刻的 TEME 系位置。数值积分结果由 EME2000 惯性系经 Orekit 岁差/章动模型链
     * 转换到 TEME，消除与 TLE/SGP4 轨道混用时的岁差量级系统误差（2026 年约 0.37°）。
     * 积分按时间增量推进：正向请求沿用上次积分状态继续，回退请求从最近的锚点状态重新积分，
     * 避免窗口扫描中每个采样点都从历元开始全程积分。
     */
    @Override
    public Vector3 positionAt(Instant instant) {
        if (instant == null) {
            throw new IllegalArgumentException("外推时刻不能为空");
        }
        AbsoluteDate date = new AbsoluteDate(instant, utc);
        PVCoordinates coordinates = propagation.get().propagateTo(date).getPVCoordinates();
        PVCoordinates temeCoordinates = INERTIAL_FRAME.getTransformTo(OUTPUT_FRAME, date)
                .transformPVCoordinates(coordinates);
        return new Vector3(temeCoordinates.getPosition().getX(), temeCoordinates.getPosition().getY(),
                temeCoordinates.getPosition().getZ());
    }

    /** 创建只包含中心引力和 WGS84 J2 项的 RK4 数值传播器。 */
    private NumericalPropagator createPropagator() {
        NumericalPropagator numericalPropagator = new NumericalPropagator(
                new ClassicalRungeKuttaIntegrator(integrationStepSec));
        numericalPropagator.setOrbitType(OrbitType.CARTESIAN);
        numericalPropagator.setResetAtEnd(false);
        numericalPropagator.setInitialState(initialState);
        numericalPropagator.addForceModel(new HolmesFeatherstoneAttractionModel(INERTIAL_FRAME, J2_GRAVITY_FIELD));
        return numericalPropagator;
    }

    /**
     * 单个计算线程的增量积分状态。正向传播由 Orekit 沿当前状态继续积分；
     * 目标时刻早于当前状态时（速度差分、回看上一采样等小幅回退），
     * 从时间树上不晚于目标时刻的最近锚点状态重启积分，把回退代价限制在锚点间隔量级。
     */
    private final class ThreadPropagation {
        private final NumericalPropagator numericalPropagator = createPropagator();
        private final TreeMap<AbsoluteDate, SpacecraftState> anchors = new TreeMap<AbsoluteDate, SpacecraftState>();
        private AbsoluteDate currentDate;

        SpacecraftState propagateTo(AbsoluteDate date) {
            boolean restarted = currentDate == null || date.compareTo(currentDate) < 0;
            if (restarted) {
                Map.Entry<AbsoluteDate, SpacecraftState> anchor = anchors.floorEntry(date);
                numericalPropagator.setInitialState(anchor != null ? anchor.getValue() : initialState);
            }
            SpacecraftState state = numericalPropagator.propagate(date);
            currentDate = date;
            if (restarted || date.durationFrom(anchors.lastKey()) >= ANCHOR_STEP_SEC) {
                anchors.put(date, state);
                if (anchors.size() > MAX_ANCHORS) {
                    anchors.pollFirstEntry();
                }
            }
            return state;
        }
    }

    /** 构建无需外部重力场数据文件的二阶零阶 WGS84 归一化 J2 重力场。 */
    private static NormalizedSphericalHarmonicsProvider createJ2GravityField() {
        return GravityFieldFactory.getNormalizedProvider(Constants.WGS84_EARTH_EQUATORIAL_RADIUS,
                Constants.WGS84_EARTH_MU, TideSystem.TIDE_FREE,
                new double[][]{{1.0}, {0.0}, {Constants.WGS84_EARTH_C20}},
                new double[][]{{0.0}, {0.0}, {0.0}});
    }

    /** 校验六根数和数值积分参数，避免无效轨道进入高开销的窗口扫描。 */
    private static void validate(double semiMajorAxisM,
                                 double eccentricity,
                                 double inclinationDeg,
                                 double raanDeg,
                                 double argumentOfPerigeeDeg,
                                 double meanAnomalyDeg,
                                 Instant epoch,
                                 double integrationStepSec) {
        if (!Double.isFinite(semiMajorAxisM) || semiMajorAxisM <= 0.0
                || !Double.isFinite(eccentricity) || eccentricity < 0.0 || eccentricity >= 1.0
                || !Double.isFinite(inclinationDeg) || !Double.isFinite(raanDeg)
                || !Double.isFinite(argumentOfPerigeeDeg) || !Double.isFinite(meanAnomalyDeg)
                || epoch == null || !Double.isFinite(integrationStepSec) || integrationStepSec <= 0.0) {
            throw new IllegalArgumentException("六根数轨道或RK4积分步长无效");
        }
    }
}
