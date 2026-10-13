import com.fasterxml.jackson.databind.ObjectMapper;
import com.runisys.orbit.spacevisibility.domain.geometry.GroundStationGeometry;
import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import com.runisys.orbit.spacevisibility.domain.orbit.OrekitDataInitializer;
import com.tx.alg.OrbitCalculator;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.frames.FramesFactory;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.PVCoordinates;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T12 原版时间/坐标口径采集；只使用冻结原编译类与原依赖，不加载迁入后端。
 * JSON 内全部 double 输入/输出均为十六进制字符串，保持原数值位与负零。
 * 原 UTC 解析/格式化的历史限制如实保留，向量同时采集真地固导数与STK旋转惯性分量。
 */
public final class OriginalTimeFrameProbe {
    private static final double[][] STATES = {
        {7000000.0, 1200000.0, -600000.0, -1000.0, 7200.0, 500.0},
        {6378137.0, 0.0, 0.0, 0.0, 0.0, 0.0},
        {0.0, 0.0, 7000000.0, 7500.0, -10.0, 150.0}
    };

    private static final String[] INSTANTS = {
        "2000-01-01T12:00:00Z",
        "2024-02-29T23:59:59.999999999Z",
        "2026-07-01T10:00:00.125Z",
        "2026-07-02T00:00:00.000000007Z",
        "2026-12-31T23:59:59.999999999Z",
        "2027-01-01T00:00:00Z"
    };

    private OriginalTimeFrameProbe() {
    }

    /** 参数依次为输出目录、原样Orekit数据副本目录；异常会令采集失败，不存伪成功结果。 */
    public static void main(String[] args) throws Exception {
        Path output = Paths.get(args[0]);
        Files.createDirectories(output);
        System.setProperty("orekit.data.path", Paths.get(args[1]).toAbsolutePath().toString());
        OrekitDataInitializer.initialize();
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, Object>> times = captureTimes();
        List<Map<String, Object>> legacy = captureLegacyFrames();
        List<Map<String, Object>> teme = captureTemeFrames();
        List<Map<String, Object>> eme = captureEmeFrames();
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("legacy-time.json").toFile(), times);
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("legacy-j2000-ecef.json").toFile(), legacy);
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("teme-ecef.json").toFile(), teme);
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("eme2000-teme.json").toFile(), eme);
        System.out.println("PASS original time records=" + times.size() + "; J2000/ECEF="
                + legacy.size() + "; TEME/ECEF=" + teme.size() + "; EME2000/TEME=" + eme.size());
    }

    /** 原同一入口在三种偏移下采集；非法/旧限制保留为返回值或原异常，不套新校验。 */
    private static List<Map<String, Object>> captureTimes() {
        String[] inputs = {
            "2000/01/01 12:00:00", "2000/01/01 00:00:00.000",
            "2024-02-29 23:59:59.999", "2024/03/01 00:00:00.000",
            "2026/07/01 10:00:00.125", "2026-07-01 23:59:59.999",
            "2026/07/02 00:00:00.001", "2026-12-31 23:59:59.999",
            "2027-01-01 00:00:00.000", "1999/12/31 23:59:59.999",
            "2026-07-01T10:00:00Z", "invalid", "2026/07 10:00:00",
            "2026/07/01 10:00", "2026/07/01 aa:00:00", null
        };
        List<Map<String, Object>> records = new ArrayList<>();
        double savedOffset = OrbitCalculator.utcOffsetSec;
        try {
            for (double offset : new double[]{0.0, 28800.0, -28800.0}) {
                OrbitCalculator.utcOffsetSec = offset;
                for (String input : inputs) {
                    Map<String, Object> record = row();
                    record.put("input", input);
                    record.put("utcOffsetSec", hex(offset));
                    try {
                        double seconds = OrbitCalculator.utcToJ2000Sec(input);
                        record.put("seconds", hex(seconds));
                        String formatted = OrbitCalculator.j2000ToUtcMillis(seconds);
                        record.put("utcMillis", formatted);
                        record.put("utcSeconds", OrbitCalculator.j2000ToUtc(seconds));
                        record.put("roundTripSeconds", hex(OrbitCalculator.utcToJ2000Sec(formatted)));
                    } catch (RuntimeException exception) {
                        record.put("exception", exception.getClass().getName());
                        record.put("exceptionMessage", exception.getMessage());
                    }
                    records.add(record);
                }
            }
        } finally {
            OrbitCalculator.utcOffsetSec = savedOffset;
        }
        return records;
    }

    /** J2000原岁差/章动/GAST链；两种ECEF速度、正反及往返结果分别存，不混用。 */
    private static List<Map<String, Object>> captureLegacyFrames() {
        List<Map<String, Object>> records = new ArrayList<>();
        double[] seconds = {
            0.0, -0.125, 836085600.125, 836136000.0000001, 836172000.375, 788961599.999
        };
        for (int index = 0; index < STATES.length; index++) {
            double[] values = STATES[index];
            OrbitCalculator.Vector3 position = legacyVector(values, 0);
            OrbitCalculator.Vector3 velocity = legacyVector(values, 3);
            for (double time : seconds) {
                Map<String, Object> record = row();
                record.put("case", index);
                record.put("timeSeconds", hex(time));
                record.put("positionJ2000", vector(position));
                record.put("velocityJ2000", vector(velocity));
                OrbitCalculator.Vector3 fixedPosition = OrbitCalculator.eciToEcefPos(position, time);
                OrbitCalculator.Vector3 derivative = OrbitCalculator.eciToEcefVel(velocity, position, time);
                OrbitCalculator.Vector3 fixedComponents = OrbitCalculator.eciToStkFixedVelocity(velocity, time);
                record.put("positionEcef", vector(fixedPosition));
                record.put("coordinateDerivativeEcef", vector(derivative));
                record.put("inertialComponentsEcef", vector(fixedComponents));
                record.put("reversePositionJ2000", vector(OrbitCalculator.ecefToEciPos(fixedPosition, time)));
                record.put("reverseVelocityJ2000", vector(OrbitCalculator.ecefToEciVel(
                        derivative, fixedPosition, time)));
                record.put("reverseFixedComponentsJ2000", vector(OrbitCalculator.ecefToEciPos(
                        fixedComponents, time)));
                records.add(record);
            }
        }
        return records;
    }

    /** 原空间GMST转动仅用于TEME与ECEF位置，不虚构新速度链。 */
    private static List<Map<String, Object>> captureTemeFrames() {
        List<Map<String, Object>> records = new ArrayList<>();
        for (int index = 0; index < STATES.length; index++) {
            Vector3 position = vector(STATES[index], 0);
            for (String text : INSTANTS) {
                Instant instant = Instant.parse(text);
                Vector3 ecef = GroundStationGeometry.eciToEcef(position, instant);
                Map<String, Object> record = row();
                record.put("case", index);
                record.put("instant", text);
                record.put("positionTeme", vector(position));
                record.put("positionEcef", vector(ecef));
                record.put("reversePositionTeme", vector(GroundStationGeometry.ecefToEci(ecef, instant)));
                records.add(record);
            }
        }
        return records;
    }

    /** 原Kepler位置计算使用transformPVCoordinates；保持PV调用链，不改成另一种速度约定。 */
    private static List<Map<String, Object>> captureEmeFrames() {
        List<Map<String, Object>> records = new ArrayList<>();
        for (int index = 0; index < STATES.length; index++) {
            double[] values = STATES[index];
            PVCoordinates input = new PVCoordinates(
                    new Vector3D(values[0], values[1], values[2]),
                    new Vector3D(values[3], values[4], values[5]));
            for (String text : INSTANTS) {
                AbsoluteDate date = new AbsoluteDate(Instant.parse(text), TimeScalesFactory.getUTC());
                PVCoordinates converted = FramesFactory.getEME2000().getTransformTo(
                        FramesFactory.getTEME(), date).transformPVCoordinates(input);
                Map<String, Object> record = row();
                record.put("case", index);
                record.put("instant", text);
                record.put("positionEme2000", vector(input.getPosition()));
                record.put("velocityEme2000", vector(input.getVelocity()));
                record.put("positionTeme", vector(converted.getPosition()));
                record.put("velocityTeme", vector(converted.getVelocity()));
                record.put("utcDurationFromJ2000Tt", hex(date.durationFrom(AbsoluteDate.J2000_EPOCH)));
                records.add(record);
            }
        }
        return records;
    }

    private static Map<String, Object> row() {
        return new LinkedHashMap<>();
    }

    private static List<String> vector(OrbitCalculator.Vector3 vector) {
        List<String> values = new ArrayList<>();
        values.add(hex(vector.x));
        values.add(hex(vector.y));
        values.add(hex(vector.z));
        return values;
    }

    private static List<String> vector(Vector3 vector) {
        List<String> values = new ArrayList<>();
        values.add(hex(vector.getX()));
        values.add(hex(vector.getY()));
        values.add(hex(vector.getZ()));
        return values;
    }

    private static List<String> vector(Vector3D vector) {
        List<String> values = new ArrayList<>();
        values.add(hex(vector.getX()));
        values.add(hex(vector.getY()));
        values.add(hex(vector.getZ()));
        return values;
    }

    private static Vector3 vector(double[] values, int start) {
        return new Vector3(values[start], values[start + 1], values[start + 2]);
    }

    private static OrbitCalculator.Vector3 legacyVector(double[] values, int start) {
        return new OrbitCalculator.Vector3(values[start], values[start + 1], values[start + 2]);
    }

    private static String hex(double value) {
        return Double.toHexString(value);
    }
}
