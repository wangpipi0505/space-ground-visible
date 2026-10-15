package com.runisys.orbit.spacevisibility.domain.space.downlink;

import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitStateProvider;
import com.runisys.orbit.spacevisibility.domain.payload.PayloadConstraint;
import com.runisys.orbit.spacevisibility.domain.payload.PayloadType;
import com.runisys.orbit.spacevisibility.domain.space.geometry.GroundStationCoordinate;
import com.runisys.orbit.spacevisibility.domain.space.geometry.GroundStationGeometry;
import com.runisys.orbit.spacevisibility.domain.space.illumination.SolarGeometry;
import com.runisys.orbit.spacevisibility.domain.space.visibility.PayloadVisibilityEvaluator;
import com.runisys.orbit.spacevisibility.domain.space.visibility.VisibilitySample;
import com.runisys.orbit.spacevisibility.domain.window.TimeInterval;
import com.runisys.orbit.spacevisibility.domain.window.TimeWindowScanner;
import com.runisys.orbit.spacevisibility.domain.window.WindowNormalizer;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 纯领域站星下传判定及稳定窗口处理，保留原地球遮挡、站点仰角、光照、断链与保护顺序。
 * 坐标转换由既有TEME/地固几何链完成，时间为UTC Instant，步长及窗口长度以秒计。
 */
public final class DownlinkPlanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(DownlinkPlanner.class);

    /**
     * WinTool 页面保存的“最小观测角”是双侧视场角；桌面端实际以下半角参与地面站可见性计算。
     * 例如文档及页面均保存 10°，而两组参考窗口的边界对应物理仰角 5°。该常量只用于
     * 下传兼容层，不影响空间目标观测的载荷俯仰参数或页面展示值。
     */
    private static final double WINTOOL_STATION_MIN_ELEVATION_HALF_ANGLE_FACTOR = 0.5;
    /** WinTool 结果列表忽略小于 30 秒的稳定下传段，避免向后续规划传递瞬时链路。 */
    private static final double WINTOOL_MIN_EFFECTIVE_DOWNLINK_DURATION_SEC = 30.0;

    private DownlinkPlanner() {
    }

    /**
     * 在不限制卫星光照状态时计算地面站稳定下传窗口，兼容只使用传统仰角与保护时间的调用方。
     *
     * @param satellite 卫星轨道外推器
     * @param station 地面站经纬高
     * @param start 规划开始时刻
     * @param end 规划结束时刻
     * @param coarseStepSec 粗选扫描步长（秒）
     * @param fineStepSec 精选边界步长（秒）
     * @param minElevationDeg 最低仰角（度）
     * @param maxElevationDeg 最高仰角（度），小于等于零时不限制
     * @param preGuardSec 前保护时长（秒）
     * @param postGuardSec 后保护时长（秒）
     * @param allowedGapSec 允许断链时长（秒）
     * @return 经过归一化的稳定下传窗口
     */
    public static List<TimeInterval> planEffectiveWindows(OrbitStateProvider satellite,
                                                           GroundStationCoordinate station,
                                                           Instant start,
                                                           Instant end,
                                                           double coarseStepSec,
                                                           double fineStepSec,
                                                           double minElevationDeg,
                                                           double maxElevationDeg,
                                                           double preGuardSec,
                                                           double postGuardSec,
                                                           double allowedGapSec) {
        return planEffectiveWindows(satellite, station, start, end, coarseStepSec, fineStepSec,
                minElevationDeg, maxElevationDeg, preGuardSec, postGuardSec, allowedGapSec,
                IlluminationRequirement.ANY, null);
    }

    /**
     * 根据仰角、卫星光照、前后保护和允许断链约束计算可建立稳定数传链路的时间窗口。
     *
     * @param satellite 卫星轨道外推器
     * @param station 地面站经纬高
     * @param start 规划开始时刻
     * @param end 规划结束时刻
     * @param coarseStepSec 粗选扫描步长（秒）
     * @param fineStepSec 精选边界步长（秒）
     * @param minElevationDeg 最低仰角（度）
     * @param maxElevationDeg 最高仰角（度），小于等于零时不限制
     * @param preGuardSec 前保护时长（秒）
     * @param postGuardSec 后保护时长（秒）
     * @param allowedGapSec 允许断链时长（秒）
     * @param illuminationRequirement 卫星阳照、阴影或不限约束
     * @return 经过保护时间处理后的稳定下传窗口
     */
    public static List<TimeInterval> planEffectiveWindows(OrbitStateProvider satellite,
                                                           GroundStationCoordinate station,
                                                           Instant start,
                                                           Instant end,
                                                           double coarseStepSec,
                                                           double fineStepSec,
                                                           double minElevationDeg,
                                                           double maxElevationDeg,
                                                           double preGuardSec,
                                                           double postGuardSec,
                                                           double allowedGapSec,
                                                           IlluminationRequirement illuminationRequirement) {
        return planEffectiveWindows(satellite, station, start, end, coarseStepSec, fineStepSec,
                minElevationDeg, maxElevationDeg, preGuardSec, postGuardSec, allowedGapSec,
                illuminationRequirement, null);
    }

    /**
     * 生产下传主链路使用的无载荷重载：最小关注事件和太阳视半径均由运行配置明确传入。
     */
    public static List<TimeInterval> planEffectiveWindows(OrbitStateProvider satellite,
                                                           GroundStationCoordinate station,
                                                           Instant start,
                                                           Instant end,
                                                           double coarseStepSec,
                                                           double fineStepSec,
                                                           double minElevationDeg,
                                                           double maxElevationDeg,
                                                           double preGuardSec,
                                                           double postGuardSec,
                                                           double allowedGapSec,
                                                           IlluminationRequirement illuminationRequirement,
                                                           double minEventSec,
                                                           double sunAngularRadiusDeg) {
        return planEffectiveWindows(satellite, station, start, end, coarseStepSec, fineStepSec,
                minElevationDeg, maxElevationDeg, preGuardSec, postGuardSec, allowedGapSec,
                illuminationRequirement, null, minEventSec, sunAngularRadiusDeg);
    }

    /**
     * 生产下传主链路的带采样统计重载。统计仅用于请求级 INFO 汇总，不影响窗口求解和过滤规则。
     *
     * @param statisticsConsumer 接收本链路扫描统计的可选回调
     */
    public static List<TimeInterval> planEffectiveWindows(OrbitStateProvider satellite,
                                                           GroundStationCoordinate station,
                                                           Instant start,
                                                           Instant end,
                                                           double coarseStepSec,
                                                           double fineStepSec,
                                                           double minElevationDeg,
                                                           double maxElevationDeg,
                                                           double preGuardSec,
                                                           double postGuardSec,
                                                           double allowedGapSec,
                                                           IlluminationRequirement illuminationRequirement,
                                                           double minEventSec,
                                                           double sunAngularRadiusDeg,
                                                           Consumer<TimeWindowScanner.ScanStatistics> statisticsConsumer) {
        return planEffectiveWindows(satellite, station, start, end, coarseStepSec, fineStepSec,
                minElevationDeg, maxElevationDeg, preGuardSec, postGuardSec, allowedGapSec,
                illuminationRequirement, null, minEventSec, sunAngularRadiusDeg, statisticsConsumer);
    }

    /**
     * 根据站点约束和 WinTool 对地载荷 profile 计算稳定下传窗口。
     *
     * <p>传统调用只需要站点仰角与卫星光照，因此保留上方重载以保证兼容；当提供对地载荷时，
     * 每个采样点会额外使用 GROUND profile 的距离、LVLH 方位/俯仰、太阳规避和跟踪速率。
     * 地面站坐标在进入载荷评估前转换为 TEME，与卫星轨道状态保持同一参考系。</p>
     *
     * @param satellite 卫星轨道外推器
     * @param station 地面站经纬高
     * @param start 规划开始时刻
     * @param end 规划结束时刻
     * @param coarseStepSec 粗选扫描步长（秒）
     * @param fineStepSec 精选边界步长（秒），同时作为相邻视线角速度的采样间隔
     * @param minElevationDeg 站点最低仰角（度）
     * @param maxElevationDeg 站点最高仰角（度），小于等于零时不限制
     * @param preGuardSec 前保护时长（秒）
     * @param postGuardSec 后保护时长（秒）
     * @param allowedGapSec 允许断链时长（秒）
     * @param illuminationRequirement 卫星阳照、阴影或不限约束
     * @param groundPayload GROUND 载荷约束；为 {@code null} 时保持传统站点窗口逻辑
     * @return 经过保护时间处理后的稳定下传窗口
     */
    public static List<TimeInterval> planEffectiveWindows(OrbitStateProvider satellite,
                                                           GroundStationCoordinate station,
                                                           Instant start,
                                                           Instant end,
                                                           double coarseStepSec,
                                                           double fineStepSec,
                                                           double minElevationDeg,
                                                           double maxElevationDeg,
                                                           double preGuardSec,
                                                           double postGuardSec,
                                                           double allowedGapSec,
                                                           IlluminationRequirement illuminationRequirement,
                                                           PayloadConstraint groundPayload) {
        return planEffectiveWindows(satellite, station, start, end, coarseStepSec, fineStepSec,
                minElevationDeg, maxElevationDeg, preGuardSec, postGuardSec, allowedGapSec,
                illuminationRequirement, groundPayload, coarseStepSec,
                SolarGeometry.DEFAULT_SUN_ANGULAR_RADIUS_DEG);
    }

    /**
     * 仅供载荷几何工具和回归使用的完整重载；下传生产主链路固定传入空载荷。
     */
    public static List<TimeInterval> planEffectiveWindows(OrbitStateProvider satellite,
                                                           GroundStationCoordinate station,
                                                           Instant start,
                                                           Instant end,
                                                           double coarseStepSec,
                                                           double fineStepSec,
                                                           double minElevationDeg,
                                                           double maxElevationDeg,
                                                           double preGuardSec,
                                                           double postGuardSec,
                                                           double allowedGapSec,
                                                           IlluminationRequirement illuminationRequirement,
                                                           PayloadConstraint groundPayload,
                                                           double minEventSec,
                                                           double sunAngularRadiusDeg) {
        return planEffectiveWindows(satellite, station, start, end, coarseStepSec, fineStepSec,
                minElevationDeg, maxElevationDeg, preGuardSec, postGuardSec, allowedGapSec,
                illuminationRequirement, groundPayload, minEventSec, sunAngularRadiusDeg, null);
    }

    /**
     * 仅供工具和生产统计桥接使用的完整重载。GROUND 参数仍只可由历史工具路径传入，生产调用传空值。
     */
    public static List<TimeInterval> planEffectiveWindows(OrbitStateProvider satellite,
                                                           GroundStationCoordinate station,
                                                           Instant start,
                                                           Instant end,
                                                           double coarseStepSec,
                                                           double fineStepSec,
                                                           double minElevationDeg,
                                                           double maxElevationDeg,
                                                           double preGuardSec,
                                                           double postGuardSec,
                                                           double allowedGapSec,
                                                           IlluminationRequirement illuminationRequirement,
                                                           PayloadConstraint groundPayload,
                                                           double minEventSec,
                                                           double sunAngularRadiusDeg,
                                                           Consumer<TimeWindowScanner.ScanStatistics> statisticsConsumer) {
        if (illuminationRequirement == null) {
            throw new IllegalArgumentException("卫星光照约束无效");
        }
        if (groundPayload != null && groundPayload.getPayloadType() != PayloadType.GROUND) {
            throw new IllegalArgumentException("下传只能使用对地载荷约束");
        }
        double effectiveMinElevationDeg = toWinToolStationMinElevationDeg(minElevationDeg);
        List<TimeInterval> rawWindows = TimeWindowScanner.scan(
                start,
                end,
                coarseStepSec,
                fineStepSec,
                minEventSec,
                instant -> isVisibleFromStation(satellite, station, instant, effectiveMinElevationDeg, maxElevationDeg,
                        illuminationRequirement, fineStepSec, groundPayload, sunAngularRadiusDeg),
                statisticsConsumer);
        List<TimeInterval> guardedWindows = applyWinToolIlluminationPreGuard(satellite, start, rawWindows,
                fineStepSec, preGuardSec, illuminationRequirement, sunAngularRadiusDeg);
        // WinTool 的前保护只表示“选定光照状态已经稳定多久”，不能作为所有交集窗口的统一裁剪。
        // 因此在上一步已经完成状态型前保护；这里仅保留传统 ANY 模式的兼容裁剪，以及后保护和断链归一化。
        double genericPreGuardSec = illuminationRequirement == IlluminationRequirement.ANY ? preGuardSec : 0.0;
        List<TimeInterval> normalized = WindowNormalizer.normalize(guardedWindows, genericPreGuardSec,
                postGuardSec, allowedGapSec);
        return preGuardSec > 0.0 && illuminationRequirement != IlluminationRequirement.ANY
                ? removeWinToolTransientWindows(normalized, start) : normalized;
    }

    /**
     * 判断卫星在单个时刻是否同时满足站点仰角、卫星光照及可选的对地载荷约束。
     *
     * @param satellite 卫星轨道外推器
     * @param station 地面站经纬高
     * @param instant 待检查时刻
     * @param minElevationDeg 最低仰角（度）
     * @param maxElevationDeg 最高仰角（度）
     * @param illuminationRequirement 卫星光照约束
     * @param fineStepSec 精选采样间隔，用于计算相邻视线角速度
     * @param groundPayload 可选的 GROUND profile
     * @return 是否可用于候选下传窗口
     */
    private static boolean isVisibleFromStation(OrbitStateProvider satellite,
                                                GroundStationCoordinate station,
                                                Instant instant,
                                                double minElevationDeg,
                                                double maxElevationDeg,
                                                IlluminationRequirement illuminationRequirement,
                                                double fineStepSec,
                                                PayloadConstraint groundPayload,
                                                double sunAngularRadiusDeg) {
        Vector3 position = satellite.positionAt(instant);
        double elevation = GroundStationGeometry.elevationDegFromEcef(
                station,
                GroundStationGeometry.eciToEcef(position, instant));
        if (elevation < minElevationDeg || (maxElevationDeg > 0.0 && elevation > maxElevationDeg)) {
            return false;
        }
        if (groundPayload == null) {
            return illuminationRequirement.matches(SolarGeometry.illumination(position, instant, sunAngularRadiusDeg));
        }
        VisibilitySample sample = evaluateGroundPayload(satellite, station, instant, fineStepSec, groundPayload,
                sunAngularRadiusDeg);
        return sample.isGeometryVisible() && illuminationRequirement.matches(sample.getObserverIllumination());
    }

    /**
     * 在与轨道一致的 TEME 参考系中构造“卫星观察地面站”的采样几何。
     *
     * <p>相邻时刻的视线向量由同一地面站在两个惯性时刻的位置分别计算，既保留地球自转，
     * 也使 {@link PayloadVisibilityEvaluator} 的角速度限制与目标观测窗口共用同一套定义。</p>
     */
    private static VisibilitySample evaluateGroundPayload(OrbitStateProvider satellite,
                                                          GroundStationCoordinate station,
                                                          Instant instant,
                                                          double fineStepSec,
                                                          PayloadConstraint groundPayload,
                                                          double sunAngularRadiusDeg) {
        Vector3 stationEci = stationEci(station, instant);
        Vector3 previousLineOfSight = null;
        if (fineStepSec > 0.0) {
            long elapsedNanos = Math.round(fineStepSec * 1_000_000_000.0);
            Instant previousInstant = instant.minusNanos(elapsedNanos);
            previousLineOfSight = stationEci(station, previousInstant)
                    .subtract(satellite.positionAt(previousInstant));
        }
        return PayloadVisibilityEvaluator.evaluate(
                satellite.positionAt(instant), satellite.velocityAt(instant), stationEci,
                groundPayload, instant, previousLineOfSight, fineStepSec, sunAngularRadiusDeg);
    }

    /**
     * 依照 WinTool 语义应用前保护：卫星进入指定光照状态后需先稳定 {@code preGuardSec}，
     * 但若站点窗口开始时该状态已经稳定足够久，则不会再从站点窗口起点扣减保护时间。
     *
     * <p>例如 A 星先进入本影、数分钟后才飞至牡丹江上空时，结果应从地面站可见边界开始；
     * 若本影发生在地面站可见期间，则结果从“本影进入时刻 + 前保护”开始。文档的两组
     * WinTool 样例均验证了这一行为。ANY 没有状态进入事件，仍由传统归一化逻辑处理。</p>
     */
    private static List<TimeInterval> applyWinToolIlluminationPreGuard(OrbitStateProvider satellite,
                                                                         Instant planningStart,
                                                                         List<TimeInterval> rawWindows,
                                                                         double fineStepSec,
                                                                         double preGuardSec,
                                                                         IlluminationRequirement illuminationRequirement,
                                                                         double sunAngularRadiusDeg) {
        if (rawWindows.isEmpty() || preGuardSec <= 0.0 || illuminationRequirement == IlluminationRequirement.ANY) {
            return rawWindows;
        }
        long stepNanos = Math.max(1L, Math.round(fineStepSec * 1_000_000_000.0));
        long guardNanos = Math.round(preGuardSec * 1_000_000_000.0);
        List<TimeInterval> guarded = new java.util.ArrayList<TimeInterval>();
        for (TimeInterval rawWindow : rawWindows) {
            Instant rawStart = planningStart.plusNanos(Math.round(rawWindow.getStartSec() * 1_000_000_000.0));
            Instant stateStart = findCurrentIlluminationStateStart(satellite, rawStart, stepNanos, guardNanos,
                    illuminationRequirement, sunAngularRadiusDeg);
            Instant readyAt = stateStart.plusNanos(guardNanos);
            Instant effectiveStart = readyAt.isAfter(rawStart) ? readyAt : rawStart;
            double effectiveStartSec = secondsSince(planningStart, effectiveStart);
            if (effectiveStartSec < rawWindow.getEndSec()) {
                guarded.add(TimeInterval.of(effectiveStartSec, rawWindow.getEndSec()));
            }
        }
        return guarded;
    }

    /**
     * 从候选窗口起点向前最多回看一个前保护周期，定位当前合格光照状态的进入时刻。
     * 使用精选步长而非粗选步长，保证前保护产生的窗口边界与常规扫描具有相同的精度。
     */
    private static Instant findCurrentIlluminationStateStart(OrbitStateProvider satellite,
                                                              Instant rawStart,
                                                              long fineStepNanos,
                                                              long guardNanos,
                                                              IlluminationRequirement illuminationRequirement,
                                                              double sunAngularRadiusDeg) {
        Instant probe = rawStart;
        Instant earliestProbe = rawStart.minusNanos(guardNanos);
        while (probe.isAfter(earliestProbe)) {
            Instant previous = probe.minusNanos(fineStepNanos);
            if (previous.isBefore(earliestProbe)) {
                previous = earliestProbe;
            }
            if (!illuminationRequirement.matches(SolarGeometry.illumination(
                    satellite.positionAt(previous), previous, sunAngularRadiusDeg))) {
                break;
            }
            probe = previous;
        }
        return probe;
    }

    /** 以秒表示指定时刻相对规划起点的偏移，保留精选步长带来的纳秒精度。 */
    private static double secondsSince(Instant start, Instant instant) {
        return instant.getEpochSecond() - start.getEpochSecond()
                + (instant.getNano() - start.getNano()) / 1_000_000_000.0;
    }

    /**
     * 保留 API/数据库中的原始站点参数，同时将 WinTool 双侧最小观察角换算为参与几何计算的半角。
     * 最小值为 0 时仍为 0，避免把“允许地平线”配置意外收紧。
     */
    private static double toWinToolStationMinElevationDeg(double configuredMinElevationDeg) {
        return configuredMinElevationDeg <= 0.0 ? configuredMinElevationDeg
                : configuredMinElevationDeg * WINTOOL_STATION_MIN_ELEVATION_HALF_ANGLE_FACTOR;
    }

    /**
     * 过滤桌面端不展示的瞬时稳定段。过滤发生在断链合并和后保护之后，确保相邻短段若已按
     * 允许断链合并为一条有效链路，不会被过早丢弃。
     */
    private static List<TimeInterval> removeWinToolTransientWindows(List<TimeInterval> windows, Instant planningStart) {
        List<TimeInterval> stable = new java.util.ArrayList<TimeInterval>();
        for (TimeInterval window : windows) {
            if (window.durationSec() >= WINTOOL_MIN_EFFECTIVE_DOWNLINK_DURATION_SEC) {
                stable.add(window);
            } else {
                LOGGER.info("按 WinTool 30 秒口径丢弃瞬时下传弧段：开始={}，结束={}，时长={}s",
                        planningStart.plusNanos(Math.round(window.getStartSec() * 1_000_000_000.0)),
                        planningStart.plusNanos(Math.round(window.getEndSec() * 1_000_000_000.0)),
                        window.durationSec());
            }
        }
        return stable;
    }

    /** 将 WGS-84 站点经纬高转换为当前 TEME 时刻的位置，供载荷几何模型统一使用。 */
    private static Vector3 stationEci(GroundStationCoordinate station, Instant instant) {
        return GroundStationGeometry.ecefToEci(GroundStationGeometry.toEcef(station), instant);
    }
}
