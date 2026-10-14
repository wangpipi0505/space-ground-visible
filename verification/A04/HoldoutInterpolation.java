import com.tx.alg.OrbitCalculator;
import com.tx.alg.trajectory.TargetTrajectoryFitter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A04稠密轨迹代理留出检验：输入完整原0.1秒ECI轨迹，按1/5/10秒选观测且首末保留。
 * 原102样例的中段弹道目标政策（type=0x01、phase=0x01）固定为具名实验条件。
 * public fitSamples用超过时域的大输出步长验证观测节点；未参与拟合的点通过反射调用
 * 产品实际policyFor/estimateKnotDerivatives/pointFromConstrainedSegment，不重写Hermite。
 * 这样可在每个原参考的同一double绝对秒精确查询，避免outputTimes的0.1秒累加漂移污染误差。
 * 参考是原轨迹代理，不是独立STK真值；本探针只测区间内插值，不混入原始点或外推精度。
 */
public class HoldoutInterpolation {
    /** 参数：完整原CSV绝对路径、观测间隔秒；stdout保留原/输出位置速度和完全同刻查询bits。 */
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.US);
        OrbitCalculator.utcOffsetSec = 0.0;
        int stride = Integer.parseInt(args[1]) * 10;
        List<String> lines = Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8);
        List<TargetTrajectoryFitter.TrajectorySample> reference = new ArrayList<TargetTrajectoryFitter.TrajectorySample>();
        List<TargetTrajectoryFitter.TrajectorySample> observed = new ArrayList<TargetTrajectoryFitter.TrajectorySample>();
        List<Integer> indices = new ArrayList<Integer>();
        TargetTrajectoryFitter.TargetInfo info = new TargetTrajectoryFitter.TargetInfo();
        info.prop_tgt_type_8 = "0x01";
        info.prop_tgt_flight_phase_8 = "0x01";
        for (int i = 1; i < lines.size(); i++) {
            String[] row = lines.get(i).split(",", -1);
            if (row.length != 20) throw new IllegalArgumentException("原稠密轨迹字段不符");
            TargetTrajectoryFitter.TrajectorySample sample = new TargetTrajectoryFitter.TrajectorySample();
            sample.targetId = "A04-DENSE-PROXY";
            sample.targetInfo = info;
            sample.sourceIndex = i - 1;
            sample.absoluteTimeSec = OrbitCalculator.utcToJ2000Sec(row[0] + "/" + row[1] + "/" + row[2]
                    + " " + row[3] + ":" + row[4] + ":" + row[5]);
            sample.positionEciM = vector(row, 11);
            sample.velocityEciMs = vector(row, 14);
            sample.accelerationEciMs2 = new OrbitCalculator.Vector3(0, 0, 0);
            sample.originalConstraint = true;
            if (!reference.isEmpty() && sample.absoluteTimeSec <= reference.get(reference.size()-1).absoluteTimeSec)
                throw new IllegalArgumentException("原参考时序不递增");
            reference.add(sample);
            if ((i-1) % stride == 0 || i == lines.size()-1) {
                observed.add(sample);
                indices.add(i-1);
            }
        }
        if (reference.size() != 8560) throw new IllegalArgumentException("原参考必须完整8560点");
        TargetTrajectoryFitter.FittedTrajectory knots = TargetTrajectoryFitter.fitSamples(observed, 1000000.0,
                observed.get(0).absoluteTimeSec, observed.get(observed.size()-1).absoluteTimeSec);
        if (knots.points.size() != observed.size()) throw new IllegalStateException("原始节点数量不符");
        for (int i = 0; i < knots.points.size(); i++) {
            TargetTrajectoryFitter.FittedPoint point = knots.points.get(i);
            if (!point.original || point.positionEciM.subtract(observed.get(i).positionEciM).norm() != 0.0
                    || point.velocityEciMs.subtract(observed.get(i).velocityEciMs).norm() != 0.0)
                throw new IllegalStateException("原始节点约束变化");
        }
        Class<?> fitter = TargetTrajectoryFitter.class;
        Method policyFor = fitter.getDeclaredMethod("policyFor", TargetTrajectoryFitter.TargetInfo.class);
        policyFor.setAccessible(true);
        Object policy = policyFor.invoke(null, info);
        Class<?> vectors = OrbitCalculator.Vector3[].class;
        Method estimate = fitter.getDeclaredMethod("estimateKnotDerivatives", List.class, policy.getClass(), vectors, vectors);
        estimate.setAccessible(true);
        OrbitCalculator.Vector3[] velocities = new OrbitCalculator.Vector3[observed.size()];
        OrbitCalculator.Vector3[] accelerations = new OrbitCalculator.Vector3[observed.size()];
        estimate.invoke(null, observed, policy, velocities, accelerations);
        Method query = fitter.getDeclaredMethod("pointFromConstrainedSegment", double.class,
                TargetTrajectoryFitter.TrajectorySample.class, TargetTrajectoryFitter.TrajectorySample.class,
                OrbitCalculator.Vector3.class, OrbitCalculator.Vector3.class, OrbitCalculator.Vector3.class, OrbitCalculator.Vector3.class);
        query.setAccessible(true);
        System.out.println("row,timeBits,queryBits,observed,referenceX,referenceY,referenceZ,actualX,actualY,actualZ,referenceVx,referenceVy,referenceVz,actualVx,actualVy,actualVz");
        int segment = 0;
        for (int i = 0; i < reference.size(); i++) {
            TargetTrajectoryFitter.TrajectorySample source = reference.get(i);
            boolean isObserved = i % stride == 0 || i == reference.size()-1;
            TargetTrajectoryFitter.FittedPoint point;
            if (isObserved) {
                int index = indices.indexOf(i);
                point = knots.points.get(index);
            } else {
                while (segment < observed.size()-2 && source.absoluteTimeSec > observed.get(segment+1).absoluteTimeSec) segment++;
                point = (TargetTrajectoryFitter.FittedPoint) query.invoke(null, source.absoluteTimeSec,
                        observed.get(segment), observed.get(segment+1), velocities[segment], velocities[segment+1],
                        accelerations[segment], accelerations[segment+1]);
            }
            if (Double.doubleToRawLongBits(source.absoluteTimeSec) != Double.doubleToRawLongBits(point.absoluteTimeSec))
                throw new IllegalStateException("查询时刻错位");
            System.out.printf("%d,%s,%s,%d,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g%n",
                    i, Long.toHexString(Double.doubleToRawLongBits(source.absoluteTimeSec)),
                    Long.toHexString(Double.doubleToRawLongBits(point.absoluteTimeSec)), isObserved ? 1 : 0,
                    source.positionEciM.x, source.positionEciM.y, source.positionEciM.z,
                    point.positionEciM.x, point.positionEciM.y, point.positionEciM.z,
                    source.velocityEciMs.x, source.velocityEciMs.y, source.velocityEciMs.z,
                    point.velocityEciMs.x, point.velocityEciMs.y, point.velocityEciMs.z);
        }
    }

    /** 输入位置米、速度米每秒；所有原字段必须有限，不接受缺失状态。 */
    private static OrbitCalculator.Vector3 vector(String[] row, int start) {
        double x = Double.parseDouble(row[start]);
        double y = Double.parseDouble(row[start+1]);
        double z = Double.parseDouble(row[start+2]);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException("非有限原状态");
        return new OrbitCalculator.Vector3(x, y, z);
    }
}
