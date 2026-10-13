import com.fasterxml.jackson.databind.ObjectMapper;
import com.runisys.orbit.spacevisibility.domain.geometry.GroundStationGeometry;
import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import com.tx.alg.OrbitCalculator;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;

/** T18 独立原版探针：只调用冻结原入口，不复制任何待抽取公式；全部 double 保存 rawbits。 */
public final class OriginalGeometryProbe {
    private static final List<Map<String, Object>> ROWS = new ArrayList<>();
    private static final double A = 6378137.0;
    private static final double B = A * (1.0 - 1.0 / 298.257223563);
    private static Method sphere, ellipsoid, angle;

    /** 参数为输出JSON；运行类路径必须以冻结原 classes 开头，不能混入当前 backend/classes。 */
    public static void main(String[] args) throws Exception {
        Class<?> v = OrbitCalculator.Vector3.class;
        sphere = OrbitCalculator.class.getDeclaredMethod("hasLineOfSight", v, v);
        ellipsoid = OrbitCalculator.class.getDeclaredMethod("hasLineOfSightWgs84Ellipsoid", v, v, double.class);
        angle = OrbitCalculator.class.getDeclaredMethod("angleBetween", v, v);
        sphere.setAccessible(true); ellipsoid.setAccessible(true); angle.setAccessible(true);
        pair("same-side", new double[]{7000000, 0, 0}, new double[]{8000000, 100000, 50000});
        pair("exact-discriminant-zero", new double[]{-A, A, 0}, new double[]{A, A, 0});
        pair("through-earth", new double[]{7000000, 0, 0}, new double[]{-8000000, 0, 0});
        pair("polar-through", new double[]{0, 0, 7000000}, new double[]{0, 0, -8000000});
        pair("both-inside", new double[]{100, 200, 300}, new double[]{200, 300, 400});
        pair("same", new double[]{7000000, 0, 0}, new double[]{7000000, 0, 0});
        pair("short", new double[]{7000000, 0, 0}, new double[]{Math.nextUp(7000000.0), 0, 0});
        pair("zero", new double[]{0, -0.0, 0}, new double[]{0, 0, -0.0});
        pair("zero-first", new double[]{0, 0, 0}, new double[]{1, 2, 3});
        pair("tiny", new double[]{1e-200, -1e-200, 1e-200}, new double[]{2e-200, 0, -1e-200});
        pair("huge", new double[]{1e200, -1e200, 0}, new double[]{1e200, 1e200, 1e200});
        pair("nan", new double[]{Double.NaN, 0, 0}, new double[]{1, 2, 3});
        pair("infinity", new double[]{Double.POSITIVE_INFINITY, 0, 0}, new double[]{1, 2, 3});
        for (double r : new double[]{A, B, 6371008.8}) {
            for (double offset : new double[]{-0.01, -1e-7, 0, 1e-7, 0.01}) {
                pair("tangent-" + r + "-" + offset, new double[]{-1000000, r + offset, 0}, new double[]{1000000, r + offset, 0});
                pair("polar-tangent-" + r + "-" + offset, new double[]{-1000000, 0, r + offset}, new double[]{1000000, 0, r + offset});
            }
        }
        for (double offset : new double[]{-0.01, -1e-5, -3e-6, -1e-7, 0, 1e-7, 3e-6, 1e-5, 0.01}) {
            pair("surface-out-" + offset, new double[]{A + offset, 0, 0}, new double[]{A + 1000000, 0, 0});
            pair("surface-through-" + offset, new double[]{A + offset, 0, 0}, new double[]{-A - 1000000, 0, 0});
            pair("polar-out-" + offset, new double[]{0, 0, B + offset}, new double[]{0, 0, B + 1000000});
        }
        // 根距离约1e-10/1e-9的两侧；与地表inside容差是两个不同判据。
        for (double u : new double[]{0.5e-10, 1e-10, 2e-10, 0.5e-9, 1e-9, 2e-9}) {
            pair("endpoint-" + u, new double[]{A + 1000000 * u, 0, 0}, new double[]{A - 1000000, 0, 0});
            pair("reverse-endpoint-" + u, new double[]{A - 1000000, 0, 0}, new double[]{A + 1000000 * u, 0, 0});
        }
        Random random = new Random(180017L);
        for (int i = 0; i < 48; i++) {
            double[] p = new double[3], q = new double[3];
            for (int j = 0; j < 3; j++) { p[j] = (random.nextDouble() - .5) * 2e7; q[j] = (random.nextDouble() - .5) * 2e7; }
            pair("seeded-" + i, p, q);
        }
        Path out = Paths.get(args[0]); Files.createDirectories(out.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out.toFile(), ROWS);
        System.out.println("PASS original geometry pairs=" + ROWS.size() + ", rotated ellipsoid cases=" + ROWS.size() * 4);
    }

    private static void pair(String id, double[] p, double[] q) throws Exception {
        Vector3 a = new Vector3(p[0], p[1], p[2]), b = new Vector3(q[0], q[1], q[2]);
        OrbitCalculator.Vector3 x = old(p), y = old(q);
        Map<String, Object> row = new LinkedHashMap<>(); row.put("id", id); row.put("p", bits(p)); row.put("q", bits(q));
        row.put("dot", bits(a.dot(b))); row.put("normP", bits(a.norm())); row.put("normQ", bits(b.norm()));
        Vector3 d = b.subtract(a); row.put("difference", bits(new double[]{d.getX(), d.getY(), d.getZ()})); row.put("distance", bits(d.norm()));
        row.put("legacyDot", bits(x.dot(y))); row.put("legacyNormP", bits(x.norm())); row.put("legacyDistance", bits(y.subtract(x).norm()));
        try { row.put("angleDeg", bits(a.angleDeg(b))); } catch (IllegalStateException e) { row.put("angleException", e.getMessage()); }
        row.put("angleRad", bits((Double) angle.invoke(null, x, y)));
        row.put("spaceLos", GroundStationGeometry.hasLineOfSight(a, b)); row.put("sphereLos", sphere.invoke(null, x, y));
        List<Map<String, Object>> frames = new ArrayList<>();
        for (double time : new double[]{0, 836049600.125, 836136000, -43200}) {
            OrbitCalculator.Vector3 u = OrbitCalculator.eciToEcefPos(x, time), v = OrbitCalculator.eciToEcefPos(y, time);
            Map<String, Object> frame = new LinkedHashMap<>(); frame.put("time", bits(time));
            frame.put("pEcef", bits(new double[]{u.x, u.y, u.z})); frame.put("qEcef", bits(new double[]{v.x, v.y, v.z}));
            frame.put("los", ellipsoid.invoke(null, x, y, time)); frames.add(frame);
        }
        row.put("frames", frames); ROWS.add(row);
    }
    private static OrbitCalculator.Vector3 old(double[] p) { return new OrbitCalculator.Vector3(p[0], p[1], p[2]); }
    private static String bits(double x) { return Long.toHexString(Double.doubleToRawLongBits(x)); }
    private static List<String> bits(double[] x) { List<String> a = new ArrayList<>(); for (double v : x) a.add(bits(v)); return a; }
}
