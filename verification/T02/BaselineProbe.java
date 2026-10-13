import java.lang.reflect.*;
import java.util.Locale;

/**
 * T02 独立反射探针：同一探针分别加载源码 classes 和交付 plain JAR。
 * 不引用或复写任何原版算法；位置单位米，数值 J2 速度单位米每秒。
 * 输出十六进制浮点数，使比较可以识别每一位差异，而无需放宽容差。
 */
public final class BaselineProbe {
    /** 使用非圆非赤道轨道，覆盖逆向、历元、非整步和跨日传播。 */
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Class<?> calculator = Class.forName("com.tx.alg.OrbitCalculator");
        Class<?> elementsType = Class.forName("com.tx.alg.OrbitCalculator$OrbitElements");
        Class<?> modelType = Class.forName("com.tx.alg.OrbitCalculator$OrbitModel");
        Object elements = elementsType.newInstance();
        String[] fields = {"semiMajorAxisM", "eccentricity", "inclinationDeg", "raanDeg", "argumentOfPerigeeDeg", "meanAnomalyDeg", "epochSec"};
        double[] inputs = {7200000, 0.07, 63.4, 27, 48, 91, 836049600};
        for (int i = 0; i < fields.length; i++) elementsType.getField(fields[i]).setDouble(elements, inputs[i]);
        Method propagate = calculator.getMethod("propagateAtAbsolute", elementsType, double.class, modelType);
        double[] times = {-86400.25, -61.3, -1, 0, 0.125, 17.7, 61.3, 86400.25};
        for (Object model : modelType.getEnumConstants()) {
            for (double offset : times) {
                Object vector = propagate.invoke(null, elements, inputs[6] + offset, model);
                System.out.print(model + "|" + offset);
                printVector(vector);
                System.out.println();
            }
        }
        // 私有数值入口返回完整状态；只观察已有结果，不改变访问代码或积分行为。
        Method numerical = calculator.getDeclaredMethod("propagateJ2Numerical", elementsType, double.class);
        numerical.setAccessible(true);
        for (double offset : times) {
            Object state = numerical.invoke(null, elements, inputs[6] + offset);
            Field velocity = state.getClass().getDeclaredField("velocity");
            velocity.setAccessible(true);
            System.out.print("J2_VELOCITY|" + offset);
            printVector(velocity.get(state));
            System.out.println();
        }
        Method parse = calculator.getMethod("utcToJ2000Sec", String.class);
        Method format = calculator.getMethod("j2000ToUtcMillis", double.class);
        for (String utc : new String[]{"2000/01/01 12:00:00", "2024/02/29 23:59:59.125", "2026/07/01 10:00:00"}) {
            double seconds = (Double) parse.invoke(null, utc);
            System.out.println("UTC|" + utc + "|" + Double.toHexString(seconds) + "|" + format.invoke(null, seconds));
        }
    }

    /** 精确输出向量各分量，避免十进制打印位数掩盖差异。 */
    private static void printVector(Object vector) throws Exception {
        for (String coordinate : new String[]{"x", "y", "z"}) {
            System.out.print("|" + Double.toHexString(vector.getClass().getField(coordinate).getDouble(vector)));
        }
    }
}
