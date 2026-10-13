import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import com.runisys.orbit.spacevisibility.domain.orbit.KeplerOrbitStateProvider;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitDefinition;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitPropagationTool;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitState;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitStateProvider;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitTime;
import com.runisys.orbit.spacevisibility.domain.orbit.SpaceOrbitStateProvider;
import com.runisys.orbit.spacevisibility.domain.orbit.TleOrbitStateProvider;

import java.time.Instant;

/** T09 普通 Java8 示例：从纯领域定义构建真实公共工具，无 Spring/MyBatis/应用实体类路径。 */
public final class PlainJavaOrbitExample {
    /** 无参数；Orekit 资源由脚本传入，查询结果只写验证日志，不输出生产文件。 */
    public static void main(String[] args) throws Exception {
        requireAbsent("org.springframework.context.ApplicationContext");
        requireAbsent("org.apache.ibatis.session.SqlSession");
        requireAbsent("com.runisys.orbit.spacevisibility.application.OrbitStateProviderFactory");
        Instant epoch = Instant.parse("2026-07-01T10:00:00.123456789Z");
        OrbitDefinition.Elements elements = new OrbitDefinition.Elements(
                OrbitDefinition.Model.SPACE_KEPLER_J2_RK4,
                7000000, 0.01, 50, 20, 30, 40, OrbitTime.utc(epoch), 10);
        verify(elements, new KeplerOrbitStateProvider(7000000, 0.01, 50, 20, 30, 40, epoch, 10), epoch);

        OrbitDefinition.Tle tle = new OrbitDefinition.Tle(
                "1 25544U 98067A   24100.51782528  .00014321  00000+0  25813-3 0  9997",
                "2 25544  51.6396  46.5178 0004400  75.0248  50.3079 15.50175300448688");
        verify(tle, new TleOrbitStateProvider(tle.firstLine, tle.secondLine),
                Instant.parse("2024-04-10T12:30:00Z"));
        OrbitDefinition.Tle alpha5 = new OrbitDefinition.Tle(
                "1 A0000U 26067CY  26222.88571527  .00004719  00000-0  21676-3 0  9994",
                "2 A0000  97.4613 180.8071 0008233 161.3775 198.7764 15.20848640 20016");
        verify(alpha5, new TleOrbitStateProvider(alpha5.firstLine, alpha5.secondLine), epoch);
        System.out.println("PASS Java=" + System.getProperty("java.version")
                + "; Spring/MyBatis/application absent; 3 real bindings from definitions;"
                + " 24 position + 24 velocity + 3 state comparisons; exact bits");
    }

    /** 原 provider 与真正由定义构建的工具独立演进，查询先位置后速度，覆盖回退、重复与纳秒。 */
    private static void verify(OrbitDefinition definition, OrbitStateProvider original, Instant epoch) {
        OrbitPropagationTool tool = SpaceOrbitStateProvider.fromDefinition(definition).tool();
        for (long offset : new long[]{0, 60, 120, 59, 59, -3, 3600, 3601}) {
            Instant query = epoch.plusSeconds(offset).plusNanos(7);
            assertVector(original.positionAt(query), tool.positionAt(OrbitTime.utc(query), OrbitDefinition.Frame.TEME));
            assertVector(original.velocityAt(query), tool.velocityAt(OrbitTime.utc(query), OrbitDefinition.Frame.TEME));
        }
        Instant query = epoch.plusSeconds(3602).plusNanos(13);
        Vector3 expectedPosition = original.positionAt(query);
        Vector3 expectedVelocity = original.velocityAt(query);
        OrbitState state = tool.stateAt(OrbitTime.utc(query), OrbitDefinition.Frame.TEME);
        assertVector(expectedPosition, state.positionM);
        assertVector(expectedVelocity, state.velocityMps);
        System.out.println(definition.model + " positionNormM=" + state.positionM.norm()
                + " velocityNormMps=" + state.velocityMps.norm() + " queries=8 position/8 velocity/1 state");
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
            // 实际类加载失败才确认隔离，不能用未启动 Spring 容器代替无框架依赖证明。
        }
    }
}
