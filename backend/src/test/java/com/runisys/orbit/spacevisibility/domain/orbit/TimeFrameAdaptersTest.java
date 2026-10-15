package com.runisys.orbit.spacevisibility.domain.orbit;

import com.runisys.orbit.spacevisibility.domain.space.orbit.OrekitDataInitializer;
import com.runisys.orbit.spacevisibility.domain.space.orbit.SpaceTimeFrames;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runisys.orbit.spacevisibility.domain.space.geometry.GroundStationGeometry;
import com.runisys.orbit.spacevisibility.domain.geometry.LegacyJ2000Frames;
import com.runisys.orbit.spacevisibility.domain.space.geometry.TemeEarthFixedFrames;
import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import com.runisys.orbit.spacevisibility.domain.time.LegacyJ2000Time;
import com.runisys.orbit.spacevisibility.domain.ballistic.visibility.OrbitCalculator;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.junit.jupiter.api.Test;
import org.orekit.data.DataContext;
import org.orekit.data.DataProvider;
import org.orekit.data.LazyLoadedDataContext;
import org.orekit.frames.FramesFactory;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TAIUTCDatFilesLoader;
import org.orekit.time.TimeScale;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.PVCoordinates;

import java.nio.file.Paths;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** T12 的领域转换入口及原数学口径回归；黄金数据由冻结原实现独立采集。 */
class TimeFrameAdaptersTest {
    /** 新入口必须可独立加载，旧工具类不能成为唯一算法入口。 */
    @Test
    void exposesExplicitLegacyUtcConversionWithoutGlobalOffset() throws Exception {
        Class<?> adapter = Class.forName("com.runisys.orbit.spacevisibility.domain.time.LegacyJ2000Time");
        double result = (Double) adapter.getMethod("utcToJ2000Sec", String.class, double.class)
                .invoke(null, "2000/01/01 12:00:00", 28800.0);
        assertThat(Double.doubleToRawLongBits(result)).isEqualTo(Double.doubleToRawLongBits(28800.0));
    }

    /** 48组原字符串含三种offset、闰日、跨年、无效格式和2000年前历史限制；不改黄金或错误文本。 */
    @Test
    void legacyTimeMatchesFrozenParserAndFormatterBits() throws Exception {
        JsonNode records = rows("legacy-time.json");
        assertEquals(48, records.size());
        double previousOffset = OrbitCalculator.utcOffsetSec;
        try {
            for (JsonNode row : records) {
                String input = row.get("input").isNull() ? null : row.get("input").asText();
                double offset = number(row.get("utcOffsetSec"));
                OrbitCalculator.utcOffsetSec = offset;
                if (row.has("exception")) {
                    RuntimeException direct = assertThrows(RuntimeException.class,
                            () -> LegacyJ2000Time.utcToJ2000Sec(input, offset));
                    RuntimeException compatible = assertThrows(RuntimeException.class,
                            () -> OrbitCalculator.utcToJ2000Sec(input));
                    assertEquals(row.get("exception").asText(), direct.getClass().getName());
                    JsonNode message = row.get("exceptionMessage");
                    assertEquals(message.isNull() ? null : message.asText(), direct.getMessage());
                    assertEquals(direct.getClass(), compatible.getClass());
                    assertEquals(direct.getMessage(), compatible.getMessage());
                    continue;
                }
                double seconds = LegacyJ2000Time.utcToJ2000Sec(input, offset);
                bits(number(row.get("seconds")), seconds);
                bits(seconds, OrbitCalculator.utcToJ2000Sec(input));
                String formatted = LegacyJ2000Time.j2000ToUtcMillis(seconds, offset);
                assertEquals(row.get("utcMillis").asText(), formatted);
                assertEquals(formatted, OrbitCalculator.j2000ToUtcMillis(seconds));
                assertEquals(row.get("utcSeconds").asText(), LegacyJ2000Time.j2000ToUtc(seconds, offset));
                assertEquals(row.get("utcSeconds").asText(), OrbitCalculator.j2000ToUtc(seconds));
                bits(number(row.get("roundTripSeconds")), LegacyJ2000Time.utcToJ2000Sec(formatted, offset));
            }
        } finally {
            OrbitCalculator.utcOffsetSec = previousOffset;
        }
    }

    /** 18组原J2000状态直接对照六项原结果；旧入口和新主体都必须逐bits一致。 */
    @Test
    void legacyPositionAndBothVelocityConventionsMatchOriginalBits() throws Exception {
        JsonNode records = rows("legacy-j2000-ecef.json");
        assertEquals(18, records.size());
        double maxPositionError = 0;
        double maxVelocityError = 0;
        for (JsonNode row : records) {
            double time = number(row.get("timeSeconds"));
            Vector3 position = vector(row.get("positionJ2000"));
            Vector3 velocity = vector(row.get("velocityJ2000"));
            Vector3 ecef = LegacyJ2000Frames.eciToEcefPos(position, time);
            Vector3 derivative = LegacyJ2000Frames.eciToEcefVel(velocity, position, time);
            Vector3 fixed = LegacyJ2000Frames.eciToStkFixedVelocity(velocity, time);
            vectorBits(row.get("positionEcef"), ecef);
            vectorBits(row.get("coordinateDerivativeEcef"), derivative);
            vectorBits(row.get("inertialComponentsEcef"), fixed);
            Vector3 restoredPosition = LegacyJ2000Frames.ecefToEciPos(ecef, time);
            Vector3 restoredVelocity = LegacyJ2000Frames.ecefToEciVel(derivative, ecef, time);
            vectorBits(row.get("reversePositionJ2000"), restoredPosition);
            vectorBits(row.get("reverseVelocityJ2000"), restoredVelocity);
            // STK Fixed仅是惯性向量换轴，逆变换也只能换轴，不能把它当真地固导数加Ω×r。
            vectorBits(row.get("reverseFixedComponentsJ2000"), LegacyJ2000Frames.ecefToEciPos(fixed, time));
            vectorBits(row.get("positionEcef"), domain(OrbitCalculator.eciToEcefPos(legacy(position), time)));
            vectorBits(row.get("coordinateDerivativeEcef"), domain(OrbitCalculator.eciToEcefVel(
                    legacy(velocity), legacy(position), time)));
            vectorBits(row.get("inertialComponentsEcef"), domain(OrbitCalculator.eciToStkFixedVelocity(legacy(velocity), time)));
            vectorBits(row.get("reversePositionJ2000"), domain(OrbitCalculator.ecefToEciPos(legacy(ecef), time)));
            vectorBits(row.get("reverseVelocityJ2000"), domain(OrbitCalculator.ecefToEciVel(
                    legacy(derivative), legacy(ecef), time)));
            vectorBits(row.get("reverseFixedComponentsJ2000"), domain(OrbitCalculator.ecefToEciPos(legacy(fixed), time)));
            maxPositionError = Math.max(maxPositionError, restoredPosition.subtract(position).norm());
            maxVelocityError = Math.max(maxVelocityError, restoredVelocity.subtract(velocity).norm());
        }
        // 记录实际原链往返残差；逐bits黄金已经冻结相同残差，不另加宽松阈值掩盖变化。
        System.out.println("T12 J2000/ECEF original roundtrip maxPositionM=" + maxPositionError
                + " maxVelocityMps=" + maxVelocityError + "; 18 x 6 x 2 vectors exact bits");
    }

    /** TEME/GMST正反链18组，兼容ECI命名入口必须返回同一TEME语义与原bits。 */
    @Test
    void temeEarthFixedMatchesOriginalGmstBits() throws Exception {
        JsonNode records = rows("teme-ecef.json");
        assertEquals(18, records.size());
        double maxError = 0;
        for (JsonNode row : records) {
            Instant instant = Instant.parse(row.get("instant").asText());
            Vector3 position = vector(row.get("positionTeme"));
            Vector3 ecef = TemeEarthFixedFrames.temeToEcef(position, instant);
            Vector3 reverse = TemeEarthFixedFrames.ecefToTeme(ecef, instant);
            vectorBits(row.get("positionEcef"), ecef);
            vectorBits(row.get("reversePositionTeme"), reverse);
            vectorBits(row.get("positionEcef"), GroundStationGeometry.eciToEcef(position, instant));
            vectorBits(row.get("reversePositionTeme"), GroundStationGeometry.ecefToEci(ecef, instant));
            maxError = Math.max(maxError, reverse.subtract(position).norm());
        }
        System.out.println("T12 TEME/ECEF original roundtrip maxPositionM=" + maxError + "; 18 x 2 x 2 vectors exact bits");
    }

    /** 原UTC时标、纳秒Instant和EME2000→TEME PV链均由原Orekit黄金逐bits约束。 */
    @Test
    void orekitUtcAndEme2000TemeMatchOriginalBits() throws Exception {
        OrekitDataInitializer.initialize();
        // 冻结probe从空帧缓存按固定顺序采集。本测试复制相同数据源到独立上下文，
        // 避免其他业务测试的插值缓存历史影响黄金末位；不改全局上下文及生产缓存。
        LazyLoadedDataContext context = new LazyLoadedDataContext();
        for (DataProvider provider : DataContext.getDefault().getDataProvidersManager().getProviders()) {
            context.getDataProvidersManager().addProvider(provider);
        }
        context.getTimeScales().clearUTCTAIOffsetsLoaders();
        context.getTimeScales().addUTCTAIOffsetsLoader(new TAIUTCDatFilesLoader(
                TAIUTCDatFilesLoader.DEFAULT_SUPPORTED_NAMES, context.getDataProvidersManager()));
        TimeScale utc = context.getTimeScales().getUTC();
        JsonNode records = rows("eme2000-teme.json");
        assertEquals(18, records.size());
        for (JsonNode row : records) {
            Instant instant = Instant.parse(row.get("instant").asText());
            AbsoluteDate date = SpaceTimeFrames.utcToAbsoluteDate(instant, utc);
            bits(number(row.get("utcDurationFromJ2000Tt")), date.durationFrom(AbsoluteDate.J2000_EPOCH));
            PVCoordinates input = new PVCoordinates(hipparchus(vector(row.get("positionEme2000"))),
                    hipparchus(vector(row.get("velocityEme2000"))));
            PVCoordinates result = SpaceTimeFrames.eme2000ToTeme(input, date,
                    context.getFrames().getEME2000(), context.getFrames().getTEME());
            vectorBits(row.get("positionTeme"), domain(result.getPosition()));
            vectorBits(row.get("velocityTeme"), domain(result.getVelocity()));
        }
        AbsoluteDate noonUtc = SpaceTimeFrames.utcToAbsoluteDate(Instant.parse("2000-01-01T12:00:00Z"), utc);
        assertThat(noonUtc.durationFrom(AbsoluteDate.J2000_EPOCH)).isGreaterThan(64.0);
        bits(0.0, LegacyJ2000Time.utcToJ2000Sec("2000/01/01 12:00:00", 0));
    }

    /** 已有全局帧缓存经过任意业务历史后，原链和抽取入口仍须同上下文逐bits一致。 */
    @Test
    void warmedOrekitFramesPreserveOriginalChainBits() throws Exception {
        OrekitDataInitializer.initialize();
        TimeScale utc = TimeScalesFactory.getUTC();
        double maxGoldenDifference = 0;
        for (JsonNode row : rows("eme2000-teme.json")) {
            AbsoluteDate date = SpaceTimeFrames.utcToAbsoluteDate(Instant.parse(row.get("instant").asText()), utc);
            PVCoordinates input = new PVCoordinates(hipparchus(vector(row.get("positionEme2000"))),
                    hipparchus(vector(row.get("velocityEme2000"))));
            PVCoordinates original = FramesFactory.getEME2000().getTransformTo(FramesFactory.getTEME(), date)
                    .transformPVCoordinates(input);
            PVCoordinates actual = SpaceTimeFrames.eme2000ToTeme(input, date,
                    FramesFactory.getEME2000(), FramesFactory.getTEME());
            bits(original.getPosition().getX(), actual.getPosition().getX());
            bits(original.getPosition().getY(), actual.getPosition().getY());
            bits(original.getPosition().getZ(), actual.getPosition().getZ());
            bits(original.getVelocity().getX(), actual.getVelocity().getX());
            bits(original.getVelocity().getY(), actual.getVelocity().getY());
            bits(original.getVelocity().getZ(), actual.getVelocity().getZ());
            maxGoldenDifference = Math.max(maxGoldenDifference,
                    domain(original.getVelocity()).subtract(vector(row.get("velocityTeme"))).norm());
        }
        System.out.println("T12 shared Orekit history original/extracted 18 PV exact bits; cold/hot velocity differenceMps="
                + maxGoldenDifference);
    }

    /** CSV独立日历字段禁用旧offset；新领域API无论旧全局如何设置均仅使用显式参数。 */
    @Test
    void csvCalendarAndExplicitOffsetsDoNotReadLegacyGlobals() throws Exception {
        double previousOffset = OrbitCalculator.utcOffsetSec;
        try {
            OrbitCalculator.utcOffsetSec = 54321.125;
            double expected = LegacyJ2000Time.utcToJ2000Sec("2026/07/01 10:00:00.125", 0);
            for (double offset : new double[]{0, 28800, -28800, -0.0}) {
                bits(expected, LegacyJ2000Time.utcPartsToJ2000Sec(2026, 7, 1, 10, 0, 0.125, false, offset));
                bits(expected + offset, LegacyJ2000Time.utcToJ2000Sec("2026/07/01 10:00:00.125", offset));
            }
            java.lang.reflect.Method csvTime = Class.forName("com.runisys.orbit.spacevisibility.domain.ballistic.visibility.BallisticTrajectoryReader").getDeclaredMethod("utcPartsToJ2000Sec",
                    int.class, int.class, int.class, int.class, int.class, double.class, boolean.class);
            csvTime.setAccessible(true);
            bits(expected, (Double) csvTime.invoke(null, 2026, 7, 1, 10, 0, 0.125, false));
            bits(54321.125, OrbitCalculator.utcOffsetSec);
        } finally {
            OrbitCalculator.utcOffsetSec = previousOffset;
        }
    }

    /** 明确两类速度恰差原Ω×r，防止把CSV列误作位置微分并送入错误逆变换。 */
    @Test
    void fixedComponentsAndCoordinateDerivativeRemainDistinct() {
        Vector3 position = new Vector3(7000000, 1200000, -600000);
        Vector3 velocity = new Vector3(-1000, 7200, 500);
        double time = 836172000.125;
        Vector3 ecef = LegacyJ2000Frames.eciToEcefPos(position, time);
        Vector3 fixed = LegacyJ2000Frames.eciToStkFixedVelocity(velocity, time);
        Vector3 derivative = LegacyJ2000Frames.eciToEcefVel(velocity, position, time);
        bits(fixed.getX() + 7.2921150e-5 * ecef.getY(), derivative.getX());
        bits(fixed.getY() - 7.2921150e-5 * ecef.getX(), derivative.getY());
        bits(fixed.getZ(), derivative.getZ());
        assertThat(fixed.subtract(derivative).norm()).isGreaterThan(100.0);
        assertThat(LegacyJ2000Frames.ecefToEciVel(fixed, ecef, time).subtract(velocity).norm()).isGreaterThan(100.0);
    }

    private JsonNode rows(String name) throws Exception {
        return new ObjectMapper().readTree(Paths.get("..", "test-data", "baseline", "time-frame", name).toFile());
    }

    private double number(JsonNode value) {
        return Double.parseDouble(value.asText());
    }

    private Vector3 vector(JsonNode value) {
        return new Vector3(number(value.get(0)), number(value.get(1)), number(value.get(2)));
    }

    private void vectorBits(JsonNode expected, Vector3 actual) {
        bits(number(expected.get(0)), actual.getX());
        bits(number(expected.get(1)), actual.getY());
        bits(number(expected.get(2)), actual.getZ());
    }

    private void bits(double expected, double actual) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual),
                () -> "expected=" + Double.toHexString(expected) + " actual=" + Double.toHexString(actual));
    }

    private OrbitCalculator.Vector3 legacy(Vector3 value) {
        return new OrbitCalculator.Vector3(value.getX(), value.getY(), value.getZ());
    }

    private Vector3 domain(OrbitCalculator.Vector3 value) {
        return new Vector3(value.x, value.y, value.z);
    }

    private Vector3 domain(Vector3D value) {
        return new Vector3(value.getX(), value.getY(), value.getZ());
    }

    private Vector3D hipparchus(Vector3 value) {
        return new Vector3D(value.getX(), value.getY(), value.getZ());
    }
}
