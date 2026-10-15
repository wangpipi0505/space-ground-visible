package com.runisys.orbit.spacevisibility.domain.downlink;

import com.runisys.orbit.spacevisibility.domain.space.downlink.DownlinkPlanner;
import com.runisys.orbit.spacevisibility.domain.space.downlink.IlluminationRequirement;

import com.runisys.orbit.spacevisibility.domain.space.geometry.GroundStationCoordinate;
import com.runisys.orbit.spacevisibility.domain.space.geometry.GroundStationGeometry;
import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitStateProvider;
import com.runisys.orbit.spacevisibility.domain.space.orbit.TleOrbitStateProvider;
import com.runisys.orbit.spacevisibility.domain.space.illumination.IlluminationState;
import com.runisys.orbit.spacevisibility.domain.space.illumination.SolarGeometry;
import com.runisys.orbit.spacevisibility.domain.window.TimeInterval;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Word 文档中两组完整 WinTool 下传结果的领域回归测试。
 *
 * <p>输入严格使用文档指定的三颗卫星 TLE、两座地面站、阴影区、前保护 300 秒、
 * 允许断链 0 秒及页面保存的 10°–87°。下传生产主链路不再读取 GROUND 载荷，故本用例
 * 必须走无载荷重载，防止测试与实际 REST 调用脱节。窗口数量、顺序和每个端点都必须
 * 与 WinTool 参考数据一一对应。</p>
 */
class WinToolDownlinkReferenceTest {

    /**
     * WinTool 已确认口径的验收门槛：每一条单星下传窗口的开始、结束均须与 WinTool 相差不超过 3 秒。
     * 不允许用按卫星/日期偏移、放宽容差或测试白名单掩盖差异。
     */
    private static final double BOUNDARY_TOLERANCE_SEC = 3.0;
    private static final GroundStationCoordinate KASHI = new GroundStationCoordinate(75.717, 39.468, 1376.6);
    private static final GroundStationCoordinate MUDANJIANG = new GroundStationCoordinate(129.543, 44.5893, 342.0);

    private static final LinkCase A = new LinkCase("A", new TleOrbitStateProvider(
            "1 54699U 22169A   26222.92719756 -.00000049  00000-0  34238-4 0  9991",
            "2 54699  59.9959  59.5305 0002059 300.6111  59.4721 14.29555684191183"));
    private static final LinkCase B = new LinkCase("B", new TleOrbitStateProvider(
            "1 54700U 22169B   26222.93157105 -.00000044  00000-0  35880-4 0  9998",
            "2 54700  59.9970  59.5864 0007686 292.5992  67.4230 14.29556166191186"));
    private static final LinkCase C = new LinkCase("C", new TleOrbitStateProvider(
            "1 54214U 22142A   26222.93625795 -.00000058  00000-0  30509-4 0  9992",
            "2 54214  59.9954 119.4031 0017772  18.6395 341.5288 14.29566330197528"));

    /** 回归 Word 文档“规划时间 2026/1/18”的全部 23 条单星下传弧段。 */
    @Test
    void matchesAllJanuaryWinToolDownlinkWindows() {
        Instant start = Instant.parse("2026-01-18T10:00:00Z");
        Instant end = Instant.parse("2026-01-18T20:00:00Z");
        verify(start, end, A, KASHI, expected(
                "2026-01-18T15:24:29.062Z/2026-01-18T15:26:36.515Z",
                "2026-01-18T17:05:16.890Z/2026-01-18T17:10:30.796Z",
                "2026-01-18T18:50:31.796Z/2026-01-18T18:57:30.875Z"));
        verify(start, end, A, MUDANJIANG, expected(
                "2026-01-18T10:22:03Z/2026-01-18T10:22:38Z",
                "2026-01-18T12:02:52.500Z/2026-01-18T12:06:57.187Z",
                "2026-01-18T13:43:41.312Z/2026-01-18T13:51:39.468Z",
                "2026-01-18T15:27:32.968Z/2026-01-18T15:38:09.000Z",
                "2026-01-18T17:12:00.625Z/2026-01-18T17:24:47.718Z",
                "2026-01-18T18:56:29.031Z/2026-01-18T19:08:47.546Z"));
        verify(start, end, B, KASHI, expected(
                "2026-01-18T15:30:43.125Z/2026-01-18T15:32:59.796Z",
                "2026-01-18T17:11:30.796Z/2026-01-18T17:16:56.687Z",
                "2026-01-18T18:57:01.437Z/2026-01-18T19:04:11.656Z"));
        verify(start, end, B, MUDANJIANG, expected(
                "2026-01-18T10:28:17.812Z/2026-01-18T10:29:05.828Z",
                "2026-01-18T12:09:06.828Z/2026-01-18T12:13:22.578Z",
                "2026-01-18T13:49:54.515Z/2026-01-18T13:58:09.343Z",
                "2026-01-18T15:34:02.843Z/2026-01-18T15:44:43.484Z",
                "2026-01-18T17:18:28.546Z/2026-01-18T17:31:17.484Z",
                "2026-01-18T19:02:59.734Z/2026-01-18T19:15:03.500Z"));
        verify(start, end, C, KASHI, expected(
                "2026-01-18T17:06:12.187Z/2026-01-18T17:18:52.828Z",
                "2026-01-18T18:50:42.578Z/2026-01-18T19:03:08.468Z"));
        verify(start, end, C, MUDANJIANG, expected(
                "2026-01-18T13:46:26.250Z/2026-01-18T13:58:56.750Z",
                "2026-01-18T15:30:29.625Z/2026-01-18T15:43:24.093Z",
                "2026-01-18T17:17:10.093Z/2026-01-18T17:27:53.500Z",
                "2026-01-18T19:03:40.437Z/2026-01-18T19:10:43.281Z"));
    }

    /** 回归 Word 文档“规划时间 2026/10/18”的全部 13 条单星下传弧段。 */
    @Test
    void matchesAllOctoberWinToolDownlinkWindows() {
        Instant start = Instant.parse("2026-10-18T10:00:00Z");
        Instant end = Instant.parse("2026-10-18T20:00:00Z");
        verify(start, end, A, KASHI, expected(
                "2026-10-18T15:13:34.687Z/2026-10-18T15:17:50.437Z",
                "2026-10-18T16:54:20.500Z/2026-10-18T17:01:56.531Z"));
        verify(start, end, A, MUDANJIANG, expected(
                "2026-10-18T11:52:03.750Z/2026-10-18T11:56:07.515Z",
                "2026-10-18T13:32:48.828Z/2026-10-18T13:38:53.515Z"));
        verify(start, end, B, KASHI, expected(
                "2026-10-18T15:19:48.750Z/2026-10-18T15:24:25.703Z",
                "2026-10-18T17:00:34.203Z/2026-10-18T17:08:15.765Z"));
        verify(start, end, B, MUDANJIANG, expected(
                "2026-10-18T11:58:17.812Z/2026-10-18T12:02:38.171Z",
                "2026-10-18T13:39:03.546Z/2026-10-18T13:45:07.312Z"));
        verify(start, end, C, KASHI, expected(
                "2026-10-18T16:58:12.187Z/2026-10-18T17:01:42.687Z",
                "2026-10-18T18:38:57.750Z/2026-10-18T18:50:09.812Z"));
        verify(start, end, C, MUDANJIANG, expected(
                "2026-10-18T13:36:42.187Z/2026-10-18T13:42:14.531Z",
                "2026-10-18T15:17:27.718Z/2026-10-18T15:28:48.078Z",
                "2026-10-18T17:00:31.265Z/2026-10-18T17:12:32.265Z"));
    }

    /** 使用页面保存的 10°–87°、WinTool 30 秒短弧口径和确认的无半影圆柱影调用生产规划器。 */
    private static void verify(Instant start,
                               Instant end,
                               LinkCase link,
                               GroundStationCoordinate station,
                               List<ExpectedWindow> expected) {
        List<TimeInterval> actual = DownlinkPlanner.planEffectiveWindows(
                link.orbit, station, start, end, 60.0, 1.0, 10.0, 87.0,
                300.0, 0.0, 0.0, IlluminationRequirement.SHADOW, 30.0, 0.0);
        assertThat(actual).as(link.name + " -> " + station.getLongitudeDeg() + "," + station.getLatitudeDeg())
                .hasSize(expected.size());
        for (int index = 0; index < expected.size(); index++) {
            ExpectedWindow reference = expected.get(index);
            TimeInterval actualWindow = actual.get(index);
            assertBoundary(actualInstant(start, actualWindow.getStartSec()), reference.start, link, station, index, "start");
            assertBoundary(actualInstant(start, actualWindow.getEndSec()), reference.end, link, station, index, "end");
        }
    }

    /** 精选步长为 1 秒；超出历史基线容差时附带几何和光照诊断信息。 */
    private static void assertBoundary(Instant actual,
                                       Instant reference,
                                       LinkCase link,
                                       GroundStationCoordinate station,
                                       int index,
                                       String boundary) {
        double errorSec = Math.abs(Duration.between(reference, actual).toNanos() / 1_000_000_000.0);
        assertThat(errorSec).as(link.name + " window " + (index + 1) + " " + boundary
                + ": actual=" + actual + ", reference=" + reference
                + "; " + boundaryCondition(link.orbit, station, reference, "reference")
                + "; " + boundaryCondition(link.orbit, station, actual, "actual"))
                .isLessThanOrEqualTo(BOUNDARY_TOLERANCE_SEC);
    }

    /**
     * 在验收误差超限时输出站点几何和阴影状态，便于确认问题究竟位于 GMST/仰角转换还是太阳方向。
     * 该诊断只存在于基线测试失败文本，不改变任何规划判定。
     */
    private static String boundaryCondition(OrbitStateProvider orbit,
                                            GroundStationCoordinate station,
                                            Instant instant,
                                            String label) {
        Vector3 position = orbit.positionAt(instant);
        double elevation = GroundStationGeometry.elevationDegFromEcef(station,
                GroundStationGeometry.eciToEcef(position, instant));
        IlluminationState illumination = SolarGeometry.illumination(position, instant, 0.0);
        return label + "Elevation=" + elevation + "deg, illumination=" + illumination;
    }

    private static Instant actualInstant(Instant start, double offsetSec) {
        return start.plusNanos(Math.round(offsetSec * 1_000_000_000.0));
    }

    private static List<ExpectedWindow> expected(String... windows) {
        ExpectedWindow[] result = new ExpectedWindow[windows.length];
        for (int index = 0; index < windows.length; index++) {
            String[] boundaries = windows[index].split("/");
            result[index] = new ExpectedWindow(Instant.parse(boundaries[0]), Instant.parse(boundaries[1]));
        }
        return Arrays.asList(result);
    }

    /** 单星的 TLE；下传生产计算只使用站点几何、光照和窗口保护参数。 */
    private static final class LinkCase {
        private final String name;
        private final OrbitStateProvider orbit;

        private LinkCase(String name, OrbitStateProvider orbit) {
            this.name = name;
            this.orbit = orbit;
        }
    }

    /** 文档输出的一个 UTC 下传窗口。 */
    private static final class ExpectedWindow {
        private final Instant start;
        private final Instant end;

        private ExpectedWindow(Instant start, Instant end) {
            this.start = start;
            this.end = end;
        }
    }
}
