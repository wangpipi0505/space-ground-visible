package com.runisys.orbit.spacevisibility.domain.geometry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tx.alg.OrbitCalculator;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.nio.file.Paths;
import static org.junit.jupiter.api.Assertions.*;

/** T18 冻结原版黄金同时约束旧入口和纯数学入口；正常测试不加载忽略目录中的原 classes。 */
class SharedGeometryTest {
    @Test
    void sharedMathEntryMustExist() {
        assertDoesNotThrow(() -> Class.forName("com.runisys.orbit.spacevisibility.domain.geometry.GeometryMath"),
                "必须存在由两侧生产链实际使用的纯数学入口");
    }

    /** 130组直接原版数据，不在测试中重写求交公式；两入口的所有有限结果/NaN原位严格比对。 */
    @Test
    void originalGoldenConstrainsBothVectorShellsAndMath() throws Exception {
        JsonNode rows = rows();
        assertEquals(130, rows.size());
        Method angle = legacyMethod("angleBetween", false);
        Method sphere = legacyMethod("hasLineOfSight", false);
        Method ellipsoid = legacyMethod("hasLineOfSightWgs84Ellipsoid", true);
        for (JsonNode row : rows) {
            double[] p = coordinates(row.get("p")), q = coordinates(row.get("q"));
            Vector3 a = vector(p), b = vector(q);
            OrbitCalculator.Vector3 x = old(p), y = old(q);
            exact(row.get("dot"), a.dot(b)); exact(row.get("legacyDot"), x.dot(y));
            exact(row.get("dot"), GeometryMath.dot(p[0], p[1], p[2], q[0], q[1], q[2]));
            exact(row.get("normP"), a.norm()); exact(row.get("normQ"), b.norm());
            exact(row.get("legacyNormP"), x.norm()); exact(row.get("normP"), GeometryMath.norm(p[0], p[1], p[2]));
            vectorExact(row.get("difference"), b.subtract(a));
            OrbitCalculator.Vector3 diff = y.subtract(x);
            vectorExact(row.get("difference"), new Vector3(diff.x, diff.y, diff.z));
            exact(row.get("distance"), b.subtract(a).norm()); exact(row.get("legacyDistance"), diff.norm());
            exact(row.get("distance"), GeometryMath.distance(p[0], p[1], p[2], q[0], q[1], q[2]));
            if (row.has("angleException")) {
                assertEquals(row.get("angleException").asText(), assertThrows(IllegalStateException.class, () -> a.angleDeg(b)).getMessage());
            } else {
                exact(row.get("angleDeg"), a.angleDeg(b));
            }
            exact(row.get("angleRad"), (Double) angle.invoke(null, x, y));
            if (a.norm() * b.norm() != 0.0) {
                exact(row.get("angleRad"), GeometryMath.angleRad(a.dot(b), a.norm() * b.norm()));
            }
            assertEquals(row.get("spaceLos").asBoolean(), GroundStationGeometry.hasLineOfSight(a, b), row.get("id").asText());
            assertEquals(row.get("spaceLos").asBoolean(), GeometryMath.spaceLineOfSight(p[0], p[1], p[2], q[0], q[1], q[2]));
            assertEquals(row.get("sphereLos").asBoolean(), sphere.invoke(null, x, y));
            assertEquals(row.get("sphereLos").asBoolean(), GeometryMath.ballisticSphereLineOfSight(p[0], p[1], p[2], q[0], q[1], q[2]));
            for (JsonNode frame : row.get("frames")) {
                double time = number(frame.get("time"));
                OrbitCalculator.Vector3 ecefP = OrbitCalculator.eciToEcefPos(x, time), ecefQ = OrbitCalculator.eciToEcefPos(y, time);
                vectorExact(frame.get("pEcef"), new Vector3(ecefP.x, ecefP.y, ecefP.z));
                vectorExact(frame.get("qEcef"), new Vector3(ecefQ.x, ecefQ.y, ecefQ.z));
                assertEquals(frame.get("los").asBoolean(), ellipsoid.invoke(null, x, y, time), row.get("id").asText());
                double[] u = coordinates(frame.get("pEcef")), v = coordinates(frame.get("qEcef"));
                assertEquals(frame.get("los").asBoolean(), GeometryMath.ballisticEllipsoidLineOfSight(u[0], u[1], u[2], v[0], v[1], v[2]));
            }
        }
    }

    /** 精确相切与双内部点有意不同；不可用“统一口径”让两个政策给出相同答案。 */
    @Test
    void historicalPoliciesRemainDifferentAtTangentAndInsideEndpoints() {
        // x=±a、y=a令A=4/B=-4/C=1，判别式在double中也精确为零；一般几何相切可能有舍入残差。
        assertFalse(GeometryMath.spaceLineOfSight(-6378137, 6378137, 0, 6378137, 6378137, 0));
        assertTrue(GeometryMath.ballisticEllipsoidLineOfSight(-6378137, 6378137, 0, 6378137, 6378137, 0));
        assertFalse(GeometryMath.spaceLineOfSight(100, 200, 300, 200, 300, 400));
        assertTrue(GeometryMath.ballisticEllipsoidLineOfSight(100, 200, 300, 200, 300, 400));
        assertFalse(GeometryMath.ballisticSphereLineOfSight(-1000000, 6371008.8, 0, 1000000, 6371008.8, 0));
        assertThrows(IllegalStateException.class, () -> Vector3.ZERO.angleDeg(new Vector3(1, 0, 0)));
    }

    private static Method legacyMethod(String name, boolean time) throws Exception {
        Class<?> geometry = Class.forName("com.tx.alg.BallisticVisibilityGeometry");
        Method method = time ? geometry.getDeclaredMethod(name, OrbitCalculator.Vector3.class, OrbitCalculator.Vector3.class, double.class)
                : geometry.getDeclaredMethod(name, OrbitCalculator.Vector3.class, OrbitCalculator.Vector3.class);
        method.setAccessible(true);
        return method;
    }
    private static JsonNode rows() throws Exception {
        return new ObjectMapper().readTree(Paths.get("../test-data/baseline/geometry/geometry.json").toFile());
    }
    private static double number(JsonNode node) { return Double.longBitsToDouble(Long.parseUnsignedLong(node.asText(), 16)); }
    private static double[] coordinates(JsonNode node) { return new double[]{number(node.get(0)), number(node.get(1)), number(node.get(2))}; }
    private static Vector3 vector(double[] p) { return new Vector3(p[0], p[1], p[2]); }
    private static OrbitCalculator.Vector3 old(double[] p) { return new OrbitCalculator.Vector3(p[0], p[1], p[2]); }
    private static void exact(JsonNode expected, double actual) {
        assertEquals(expected.asText(), Long.toHexString(Double.doubleToRawLongBits(actual)));
    }
    private static void vectorExact(JsonNode expected, Vector3 actual) {
        exact(expected.get(0), actual.getX()); exact(expected.get(1), actual.getY()); exact(expected.get(2), actual.getZ());
    }
}
