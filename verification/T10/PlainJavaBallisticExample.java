import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import com.runisys.orbit.spacevisibility.domain.orbit.BallisticCartesianPropagation;
import com.runisys.orbit.spacevisibility.domain.orbit.BallisticOrbitPropagation;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitDefinition;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitPropagationTool;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitState;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitTime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 普通 Java8 独立调用弹道工具示例；类路径只有此次 javac 输出，没有框架或旧兼容核心。
 * 读取原源码/JAR 独立采集的黄金十六进制向量，按真实工具入口逐坐标原始 bits 检查。
 * 根数历元/查询秒不经 UTC 往返；拟合相对桥与明确绝对历元定义分别验证。
 * queryOffsetSec 是查询相对历元秒差，原采集 utcOffsetSec 恒为 0，二者不能混用。
 */
public final class PlainJavaBallisticExample {
    private static final double ORIGINAL_UTC_OFFSET_SEC = 0.0;

    private PlainJavaBallisticExample() {
    }

    /** 参数为黄金目录；任何差异、依赖污染或原始位不符直接失败。 */
    public static void main(String[] args) throws Exception {
        Path golden = Paths.get(args[0]);
        assertAbsent("org.springframework.context.ApplicationContext");
        assertAbsent("org.apache.ibatis.session.SqlSession");
        assertAbsent("com.tx.alg.OrbitCalculator");
        assertAbsent("org.apache.poi.ss.usermodel.Workbook");
        assertAbsent("io.minio.MinioClient");
        int positions = verifyPositions(golden.resolve("satellite-position.csv"));
        int states = verifyNumerical(golden.resolve("satellite-j2-state.csv"));
        int fitted = verifyFitter(golden.resolve("fitter-state.csv"));
        System.out.println("PASS Java=" + System.getProperty("java.version")
                + "; JDK-only domain classes; Spring/MyBatis/compatibility/POI/MinIO absent; "
                + positions + " position / " + states + " native J2 state / " + fitted
                + " fitter state comparisons; exact bits; 6 histories x 12 queries, repeated/unordered/reverse"
                + "; utcOffsetSec=0, queryOffsetSec is relative query time; absolute Cartesian epoch also checked");
    }

    /** 固定 case、model、根数及历元，按 CSV 顺序复用六个工具，各连续查询十二次。 */
    private static int verifyPositions(Path path) throws Exception {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (!"queryOffsetSec".equals(lines.get(0).split(",")[9])) {
            throw new AssertionError("卫星黄金必须明确区分 queryOffsetSec 与 utcOffsetSec");
        }
        Map<String, List<String[]>> groups = new LinkedHashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] input = line.split(",");
            // 分组键只包含固定轨道定义，查询偏移和查询绝对时刻不参与实例分组。
            String key = String.join(",", Arrays.copyOfRange(input, 0, 9));
            List<String[]> group = groups.get(key);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(key, group);
            }
            group.add(input);
        }
        if (groups.size() != 6) {
            throw new AssertionError("预期六组轨道定义，实际 " + groups.size());
        }
        int count = 0;
        for (List<String[]> group : groups.values()) {
            if (group.size() != 12) {
                throw new AssertionError("每组必须连续查询十二次，实际 " + group.size());
            }
            OrbitDefinition.Elements definition = elements(group.get(0));
            OrbitPropagationTool history = BallisticOrbitPropagation.bind(definition);
            Set<Long> queriedTimes = new HashSet<>();
            boolean repeated = false;
            boolean unordered = false;
            boolean reversePropagation = false;
            double previousTime = Double.NEGATIVE_INFINITY;
            for (String[] input : group) {
                double absoluteTime = number(input[10]);
                double queryOffsetSec = number(input[9]);
                assertBits(absoluteTime - number(input[8]), queryOffsetSec, "query offset");
                OrbitTime time = OrbitTime.legacyJ2000(absoluteTime, ORIGINAL_UTC_OFFSET_SEC);
                Vector3 position = history.positionAt(time, OrbitDefinition.Frame.J2000);
                assertVector(position, input, 11, "satellite history row " + count);
                Vector3 fresh = BallisticOrbitPropagation.bind(definition).positionAt(time,
                        OrbitDefinition.Frame.J2000);
                assertVector(fresh, input, 11, "satellite fresh row " + count);
                repeated |= !queriedTimes.add(Double.doubleToRawLongBits(absoluteTime));
                unordered |= absoluteTime < previousTime;
                reversePropagation |= queryOffsetSec < 0;
                previousTime = absoluteTime;
                count++;
            }
            if (!repeated || !unordered || !reversePropagation) {
                throw new AssertionError("每组历史必须保留重复、乱序和历元前反向查询");
            }
        }
        return count;
    }

    /** 数值 J2 状态使用原积分PV，不以任何新增差分速度与黄金比较。 */
    private static int verifyNumerical(Path path) throws Exception {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        int count = 0;
        for (String line : lines.subList(1, lines.size())) {
            String[] input = line.split(",");
            OrbitState state = BallisticOrbitPropagation.bind(elements(input)).stateAt(
                    OrbitTime.legacyJ2000(number(input[10]), ORIGINAL_UTC_OFFSET_SEC), OrbitDefinition.Frame.J2000);
            assertVector(state.positionM, input, 11, "J2 position row " + count);
            assertVector(state.velocityMps, input, 14, "J2 velocity row " + count);
            count++;
        }
        return count;
    }

    /** 原拟合dt在零相对锚点上按原bits进入自治动力学；另验证可明确指定绝对历元。 */
    private static int verifyFitter(Path path) throws Exception {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        int count = 0;
        for (String line : lines.subList(1, lines.size())) {
            String[] input = line.split(",");
            Vector3 initialPosition = vector(input, 1);
            Vector3 initialVelocity = vector(input, 4);
            double dt = number(input[7]);
            OrbitDefinition.Cartesian definition = new OrbitDefinition.Cartesian(
                    OrbitTime.legacyJ2000(0.0, 0.0), initialPosition, initialVelocity);
            OrbitState state = BallisticCartesianPropagation.bind(definition).stateAt(
                    OrbitTime.legacyJ2000(dt, 0.0), OrbitDefinition.Frame.J2000);
            assertVector(state.positionM, input, 8, "fitter position row " + count);
            assertVector(state.velocityMps, input, 11, "fitter velocity row " + count);
            if (dt == 1.125) {
                // 此二进制精确间隔在大绝对历元仍可精确表示，不重写原黄金或模拟精度损失。
                double epoch = 836085600.125;
                OrbitDefinition.Cartesian absolute = new OrbitDefinition.Cartesian(
                        OrbitTime.legacyJ2000(epoch, 0.0), initialPosition, initialVelocity);
                OrbitState absoluteState = BallisticCartesianPropagation.bind(absolute).stateAt(
                        OrbitTime.legacyJ2000(epoch + dt, 0.0), OrbitDefinition.Frame.J2000);
                assertVector(absoluteState.positionM, input, 8, "absolute fitter position");
                assertVector(absoluteState.velocityMps, input, 11, "absolute fitter velocity");
            }
            count++;
        }
        return count;
    }

    private static OrbitDefinition.Elements elements(String[] input) {
        return new OrbitDefinition.Elements(OrbitDefinition.Model.valueOf(input[1]),
                number(input[2]), number(input[3]), number(input[4]), number(input[5]),
                number(input[6]), number(input[7]),
                OrbitTime.legacyJ2000(number(input[8]), ORIGINAL_UTC_OFFSET_SEC), 0.0);
    }

    private static Vector3 vector(String[] input, int start) {
        return new Vector3(number(input[start]), number(input[start + 1]), number(input[start + 2]));
    }

    private static double number(String text) {
        return Double.parseDouble(text);
    }

    /** 不设置容差，不通过显示格式舍入掩盖差异，负零亦按原始位比较。 */
    private static void assertVector(Vector3 actual, String[] expected, int start, String label) {
        assertBits(actual.getX(), number(expected[start]), label + " x");
        assertBits(actual.getY(), number(expected[start + 1]), label + " y");
        assertBits(actual.getZ(), number(expected[start + 2]), label + " z");
    }

    private static void assertBits(double actual, double expected, String label) {
        if (Double.doubleToRawLongBits(actual) != Double.doubleToRawLongBits(expected)) {
            throw new AssertionError(label + ": " + Double.toHexString(actual)
                    + " != " + Double.toHexString(expected));
        }
    }

    private static void assertAbsent(String type) throws Exception {
        try {
            Class.forName(type);
            throw new AssertionError("独立类路径含额外依赖: " + type);
        } catch (ClassNotFoundException expected) {
            // 确认真正不依赖框架、旧业务核心和文件解析库。
        }
    }
}
