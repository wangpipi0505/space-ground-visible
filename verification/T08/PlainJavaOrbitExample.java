import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import com.runisys.orbit.spacevisibility.domain.orbit.KeplerOrbitStateProvider;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitDefinition;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitPropagationTool;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitState;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitStateProvider;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitTime;
import com.runisys.orbit.spacevisibility.domain.orbit.TleOrbitStateProvider;

import java.time.Instant;

/**
 * T08 普通 Java8 入口示例；只手工绑定真实领域 provider，完全不启动 Spring。
 * 独立构建相同参数的原 provider，以相同查询序列逐坐标比较浮点 bits，不放宽数值容差。
 */
public final class PlainJavaOrbitExample {
    /** 命令行无参数；资源路径由 JVM classpath/orekit.data.path 显式提供。 */
    public static void main(String[] args) throws Exception {
        requireAbsent("org.springframework.context.ApplicationContext");
        requireAbsent("org.apache.ibatis.session.SqlSession");
        Instant epoch = Instant.parse("2026-07-01T10:00:00.123456789Z");
        OrbitDefinition.Elements elements = new OrbitDefinition.Elements(
                OrbitDefinition.Model.SPACE_KEPLER_J2_RK4,
                7000000, 0.01, 50, 20, 30, 40, OrbitTime.utc(epoch), 10);
        verify(elements, createKepler(elements), createKepler(elements), epoch);

        // TLE 合成验证样例沿用已有 TleOrbitStateProviderTest，内嵌历元由原解析器读取。
        OrbitDefinition.Tle tle = new OrbitDefinition.Tle(
                "1 25544U 98067A   24100.51782528  .00014321  00000+0  25813-3 0  9997",
                "2 25544  51.6396  46.5178 0004400  75.0248  50.3079 15.50175300448688");
        verify(tle, new TleOrbitStateProvider(tle.firstLine, tle.secondLine),
                new TleOrbitStateProvider(tle.firstLine, tle.secondLine), Instant.parse("2024-04-10T12:30:00Z"));
        System.out.println("PASS Java=" + System.getProperty("java.version")
                + "; Spring/MyBatis absent; 2 real models; 12 position + 2 state comparisons; exact bits");
    }

    private static OrbitStateProvider createKepler(OrbitDefinition.Elements elements) {
        return new KeplerOrbitStateProvider(elements.semiMajorAxisM, elements.eccentricity,
                elements.inclinationDeg, elements.raanDeg, elements.argumentOfPerigeeDeg,
                elements.meanAnomalyDeg, elements.epoch.instant(), elements.integrationStepSec);
    }

    /** 位置专用调用覆盖正向、回退、重复和纳秒时刻；完整状态最后单独验证默认差分速度。 */
    private static void verify(OrbitDefinition definition, OrbitStateProvider original,
                               OrbitStateProvider bound, Instant epoch) {
        final int[] calls = new int[2];
        OrbitPropagationTool tool = new OrbitPropagationTool(definition, new OrbitPropagationTool.NativePropagation() {
            public Vector3 positionAt(OrbitTime time) {
                calls[0]++;
                return bound.positionAt(time.instant());
            }
            public Vector3 velocityAt(OrbitTime time) {
                calls[1]++;
                return bound.velocityAt(time.instant());
            }
        });
        long[] offsets = {0, 60, 120, 59, 59, 3600};
        for (long offset : offsets) {
            Instant query = epoch.plusSeconds(offset).plusNanos(7);
            Vector3 expected = original.positionAt(query);
            assertVector(expected, tool.positionAt(OrbitTime.utc(query), OrbitDefinition.Frame.TEME));
            if (calls[1] != 0) {
                throw new AssertionError("位置专用查询触发了额外速度");
            }
        }
        Instant query = epoch.plusSeconds(3601);
        Vector3 expectedPosition = original.positionAt(query);
        Vector3 expectedVelocity = original.velocityAt(query);
        OrbitState state = tool.stateAt(OrbitTime.utc(query), OrbitDefinition.Frame.TEME);
        assertVector(expectedPosition, state.positionM);
        assertVector(expectedVelocity, state.velocityMps);
        if (calls[0] != 7 || calls[1] != 1 || state.frame != OrbitDefinition.Frame.TEME) {
            throw new AssertionError("状态调用顺序/次数或frame错误");
        }
        System.out.println(definition.model + " positionNormM=" + state.positionM.norm()
                + " velocityNormMps=" + state.velocityMps.norm() + " nativeCalls=" + calls[0] + "/" + calls[1]);
    }

    private static void assertVector(Vector3 expected, Vector3 actual) {
        if (Double.doubleToRawLongBits(expected.getX()) != Double.doubleToRawLongBits(actual.getX())
                || Double.doubleToRawLongBits(expected.getY()) != Double.doubleToRawLongBits(actual.getY())
                || Double.doubleToRawLongBits(expected.getZ()) != Double.doubleToRawLongBits(actual.getZ())
                || !Double.isFinite(actual.norm())) {
            throw new AssertionError("真实传播结果的浮点bits发生变化");
        }
    }

    private static void requireAbsent(String className) throws Exception {
        try {
            Class.forName(className);
            throw new AssertionError("独立示例类路径不应包含：" + className);
        } catch (ClassNotFoundException expected) {
            // 通过实际类加载失败确认没有 Spring/MyBatis；不以未启动容器代替依赖隔离证明。
        }
    }
}
