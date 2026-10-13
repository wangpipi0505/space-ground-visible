import com.tx.alg.OrbitCalculator;
import com.tx.alg.trajectory.TargetTrajectoryFitter;

import java.io.BufferedWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * T10 冻结原版传播状态采集器，仅在原版隔离类路径执行，不引用迁入后的任何算法。
 * 原公开位置入口覆盖三种模型；反射只读取原私有 J2 数值状态和拟合 RK4 状态，
 * 不构造新速度公式，不以拟合/插值结果替代真正区间外传播。
 * 输出输入和结果的十六进制 double，保留原数值位；CSV 仅为回归证据格式。
 */
public final class OriginalPropagationProbe {
    private OriginalPropagationProbe() {
    }

    /** 两组根数含低偏心/倾斜与较高偏心/逆行，近地点均在原允许范围外。 */
    private static final double[][] ELEMENTS = {
        {7078137.0, 0.01, 50.0, 30.0, 20.0, 0.0},
        {12000000.0, 0.25, 120.0, -450.0, 405.0, 350.0}
    };

    /** 顺序有重复/逆向/非整步/跨日；按每次从原历元传播的策略采集。 */
    private static final double[] OFFSETS = {
        0.0, 0.125, 30.0, 30.125, -0.125, -30.125, 60.0,
        30.125, 3600.375, -3600.375, 86400.25, -86400.25
    };

    /** 每组输入都是原 J2000 笛卡尔位置米/速度米秒；不进行参考系转换。 */
    private static final double[][] CARTESIAN = {
        {7000000.0, 0.0, 10000.0, -10.0, 7500.0, 150.0},
        {6500000.0, 1200000.0, -600000.0, -1000.0, 7200.0, 500.0}
    };

    /** 覆盖原1e-5秒不传播阈值两侧、1秒节点、正反向和非整步，保留 dt 原 bits。 */
    private static final double[] FIT_OFFSETS = {
        -0.0, 0.0, 1e-6, 1e-5, 1.00001e-5, -1.00001e-5,
        0.125, 1.0, 1.125, -1.125, 125.375, -125.375, 1.125
    };

    /** 生成三份原版黄金 CSV；参数为输出目录，任何反射或传播错误均使进程失败。 */
    public static void main(String[] args) throws Exception {
        Path output = Paths.get(args[0]);
        Files.createDirectories(output);
        OrbitCalculator.utcOffsetSec = 0.0;
        double epoch = OrbitCalculator.utcToJ2000Sec("2026/07/01 10:00:00.125");
        Method numerical = OrbitCalculator.class.getDeclaredMethod(
                "propagateJ2Numerical", OrbitCalculator.OrbitElements.class, double.class);
        numerical.setAccessible(true);
        try (BufferedWriter positions = writer(output, "satellite-position.csv");
             BufferedWriter states = writer(output, "satellite-j2-state.csv")) {
            // 查询偏移只是 absolute-epoch 的输入，采集时的 utcOffsetSec 固定为0，两者不得混读。
            positions.write("case,model,a,e,i,raan,argperi,mean,epoch,queryOffsetSec,absolute,px,py,pz\n");
            states.write("case,model,a,e,i,raan,argperi,mean,epoch,queryOffsetSec,absolute,px,py,pz,vx,vy,vz\n");
            for (int index = 0; index < ELEMENTS.length; index++) {
                OrbitCalculator.OrbitElements elements = elements(ELEMENTS[index], epoch);
                for (OrbitCalculator.OrbitModel model : OrbitCalculator.OrbitModel.values()) {
                    for (double offset : OFFSETS) {
                        double absolute = epoch + offset;
                        OrbitCalculator.Vector3 position = OrbitCalculator.propagateAtAbsolute(
                                elements, absolute, model);
                        String prefix = index + "," + model.name() + "," + values(ELEMENTS[index])
                                + "," + hex(epoch) + "," + hex(offset) + "," + hex(absolute);
                        positions.write(prefix + "," + vector(position) + "\n");
                        if (model == OrbitCalculator.OrbitModel.J2_NUMERICAL) {
                            Object state = numerical.invoke(null, elements, absolute);
                            OrbitCalculator.Vector3 statePosition = fieldVector(state, "position");
                            OrbitCalculator.Vector3 velocity = fieldVector(state, "velocity");
                            states.write(prefix + "," + vector(statePosition) + "," + vector(velocity) + "\n");
                        }
                    }
                }
            }
        }
        Class<?> stateType = Class.forName("com.tx.alg.trajectory.TargetTrajectoryFitter$State");
        Constructor<?> stateConstructor = stateType.getDeclaredConstructor(
                OrbitCalculator.Vector3.class, OrbitCalculator.Vector3.class);
        stateConstructor.setAccessible(true);
        Method propagate = TargetTrajectoryFitter.class.getDeclaredMethod(
                "propagateTwoBody", stateType, double.class);
        propagate.setAccessible(true);
        try (BufferedWriter states = writer(output, "fitter-state.csv")) {
            states.write("case,rx,ry,rz,vx,vy,vz,dt,px,py,pz,svx,svy,svz\n");
            for (int index = 0; index < CARTESIAN.length; index++) {
                double[] initial = CARTESIAN[index];
                for (double dt : FIT_OFFSETS) {
                    // 原传播逐次从独立原端点开始，不把上一轮的传播状态用作下一轮初值。
                    Object state = stateConstructor.newInstance(
                            new OrbitCalculator.Vector3(initial[0], initial[1], initial[2]),
                            new OrbitCalculator.Vector3(initial[3], initial[4], initial[5]));
                    Object result = propagate.invoke(null, state, dt);
                    states.write(index + "," + values(initial) + "," + hex(dt) + ","
                            + vector(fieldVector(result, "r")) + ","
                            + vector(fieldVector(result, "v")) + "\n");
                }
            }
        }
        System.out.println("PASS original frozen classes; 72 satellite positions / 24 native J2 states / 26 fitter states; hexadecimal doubles");
    }

    /** 从黄金参数逐字段建立原公有模型，保留字段赋值而不经过新定义的额外校验。 */
    private static OrbitCalculator.OrbitElements elements(double[] input, double epoch) {
        OrbitCalculator.OrbitElements elements = new OrbitCalculator.OrbitElements();
        elements.semiMajorAxisM = input[0];
        elements.eccentricity = input[1];
        elements.inclinationDeg = input[2];
        elements.raanDeg = input[3];
        elements.argumentOfPerigeeDeg = input[4];
        elements.meanAnomalyDeg = input[5];
        elements.epochSec = epoch;
        return elements;
    }

    /** 只读原私有状态的向量字段，不修改原对象或算法控制流程。 */
    private static OrbitCalculator.Vector3 fieldVector(Object state, String name) throws Exception {
        Field field = state.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (OrbitCalculator.Vector3) field.get(state);
    }

    private static BufferedWriter writer(Path directory, String filename) throws Exception {
        return Files.newBufferedWriter(directory.resolve(filename), StandardCharsets.UTF_8);
    }

    private static String vector(OrbitCalculator.Vector3 value) {
        return hex(value.x) + "," + hex(value.y) + "," + hex(value.z);
    }

    private static String values(double[] values) {
        StringBuilder result = new StringBuilder();
        for (double value : values) {
            if (result.length() > 0) {
                result.append(',');
            }
            result.append(hex(value));
        }
        return result.toString();
    }

    private static String hex(double value) {
        return Double.toHexString(value);
    }
}
