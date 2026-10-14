import com.tx.alg.OrbitCalculator;
import com.tx.alg.trajectory.TargetTrajectoryFitter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A04公开fitSamples的0.1秒输出网格诊断：1秒观测首末保留，原/当前各自调用公开入口。
 * 每个输出对完整原参考中最近的预期0.1秒时刻测量时间差，不把不同查询时间的位置差当插值误差。
 * 继续使用原绝对秒解析与t+=step采样；记录原始点标志、实际点数及全部状态以核对原/新行为。
 */
public class PublicGridDiagnostics {
    /** 输入完整稠密轨迹CSV，输出实际绝对秒/bits、最近原格点及时间差，位置米、速度米每秒。 */
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.US);
        OrbitCalculator.utcOffsetSec = 0.0;
        List<String> lines = Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8);
        List<Double> times = new ArrayList<Double>();
        List<TargetTrajectoryFitter.TrajectorySample> observed = new ArrayList<TargetTrajectoryFitter.TrajectorySample>();
        TargetTrajectoryFitter.TargetInfo info = new TargetTrajectoryFitter.TargetInfo();
        info.prop_tgt_type_8 = "0x01";
        info.prop_tgt_flight_phase_8 = "0x01";
        for (int i=1; i<lines.size(); i++) {
            String[] row = lines.get(i).split(",", -1);
            double time = OrbitCalculator.utcToJ2000Sec(row[0]+"/"+row[1]+"/"+row[2]+" "+row[3]+":"+row[4]+":"+row[5]);
            times.add(time);
            if ((i-1)%10==0 || i==lines.size()-1) {
                TargetTrajectoryFitter.TrajectorySample sample = new TargetTrajectoryFitter.TrajectorySample();
                sample.targetId = "A04-DENSE-PROXY";
                sample.targetInfo = info;
                sample.sourceIndex = i-1;
                sample.absoluteTimeSec = time;
                sample.positionEciM = new OrbitCalculator.Vector3(Double.parseDouble(row[11]),Double.parseDouble(row[12]),Double.parseDouble(row[13]));
                sample.velocityEciMs = new OrbitCalculator.Vector3(Double.parseDouble(row[14]),Double.parseDouble(row[15]),Double.parseDouble(row[16]));
                sample.accelerationEciMs2 = new OrbitCalculator.Vector3(0,0,0);
                observed.add(sample);
            }
        }
        if (times.size()!=8560 || observed.size()!=857) throw new IllegalStateException("必须保留完整参考和首末观测");
        TargetTrajectoryFitter.FittedTrajectory trajectory = TargetTrajectoryFitter.fitSamples(observed,0.1,times.get(0),times.get(times.size()-1));
        System.out.println("row,timeBits,absoluteTimeSec,original,nearestReferenceRow,referenceTimeBits,referenceTimeSec,timeDeltaSec,x,y,z,vx,vy,vz");
        int nearest = 0;
        for (int i=0; i<trajectory.points.size(); i++) {
            TargetTrajectoryFitter.FittedPoint point = trajectory.points.get(i);
            while (nearest<times.size()-1 && Math.abs(times.get(nearest+1)-point.absoluteTimeSec)<Math.abs(times.get(nearest)-point.absoluteTimeSec)) nearest++;
            System.out.printf("%d,%s,%.17g,%d,%d,%s,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g%n",
                i,Long.toHexString(Double.doubleToRawLongBits(point.absoluteTimeSec)),point.absoluteTimeSec,point.original?1:0,
                nearest,Long.toHexString(Double.doubleToRawLongBits(times.get(nearest))),times.get(nearest),point.absoluteTimeSec-times.get(nearest),
                point.positionEciM.x,point.positionEciM.y,point.positionEciM.z,point.velocityEciMs.x,point.velocityEciMs.y,point.velocityEciMs.z);
        }
    }
}
