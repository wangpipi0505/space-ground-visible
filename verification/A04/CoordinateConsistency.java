import com.tx.alg.OrbitCalculator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

/**
 * A04序列化轨迹坐标自洽测量，直接调用本轮编译的原转换实现，不另写地球物理公式。
 * 输入为完整拟合/模拟CSV或重定时CSV；位置米、速度米每秒。拟合/模拟ECEF速度沿用
 * STK Fixed惯性分量换轴，重定时按原逆变换的地固导数口径。所有行均测量且检查有限数。
 * CSV位置三位、速度九位和UTC毫秒量化会使测量非零；此处无独立外部真值，不设绝对精度通过线。
 */
public class CoordinateConsistency {
    /** 参数：CSV绝对路径及模式fixed/retime。输出每行坐标/速度分量误差及三维范数。 */
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.US);
        OrbitCalculator.utcOffsetSec = 0.0;
        List<String> lines = Files.readAllLines(Paths.get(args[0]), StandardCharsets.UTF_8);
        System.out.println("row,timeUtc,dxM,dyM,dzM,positionNormM,dvxMps,dvyMps,dvzMps,velocityNormMps,roundtripPositionM,roundtripVelocityMps");
        for (int i = 1; i < lines.size(); i++) {
            String[] row = lines.get(i).split(",", -1);
            if (row.length < 21) throw new IllegalArgumentException("轨迹字段缺失：" + i);
            String utc = row[0] + "/" + row[1] + "/" + row[2] + " " + row[3] + ":" + row[4] + ":" + row[5];
            double time = OrbitCalculator.utcToJ2000Sec(utc);
            OrbitCalculator.Vector3 ecef = vector(row, 6);
            OrbitCalculator.Vector3 eci = vector(row, 11);
            OrbitCalculator.Vector3 velocityEci = vector(row, 14);
            OrbitCalculator.Vector3 velocityEcef = vector(row, 17);
            OrbitCalculator.Vector3 dp, dv;
            if ("retime".equals(args[1])) {
                dp = OrbitCalculator.ecefToEciPos(ecef, time).subtract(eci);
                dv = OrbitCalculator.ecefToEciVel(velocityEcef, ecef, time).subtract(velocityEci);
            } else {
                dp = OrbitCalculator.eciToEcefPos(eci, time).subtract(ecef);
                dv = OrbitCalculator.eciToStkFixedVelocity(velocityEci, time).subtract(velocityEcef);
            }
            OrbitCalculator.Vector3 transformed = OrbitCalculator.eciToEcefPos(eci, time);
            OrbitCalculator.Vector3 derivative = OrbitCalculator.eciToEcefVel(velocityEci, eci, time);
            double positionRoundtrip = OrbitCalculator.ecefToEciPos(transformed, time).subtract(eci).norm();
            double velocityRoundtrip = OrbitCalculator.ecefToEciVel(derivative, transformed, time).subtract(velocityEci).norm();
            System.out.printf("%d,%s,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g%n",
                    i, utc, dp.x, dp.y, dp.z, dp.norm(), dv.x, dv.y, dv.z, dv.norm(), positionRoundtrip, velocityRoundtrip);
        }
    }

    /** 从原CSV列读取有限三维向量；NaN/Infinity不能被统计器静默跳过。 */
    private static OrbitCalculator.Vector3 vector(String[] row, int offset) {
        double x = Double.parseDouble(row[offset]);
        double y = Double.parseDouble(row[offset + 1]);
        double z = Double.parseDouble(row[offset + 2]);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new IllegalArgumentException("非有限轨迹状态");
        return new OrbitCalculator.Vector3(x, y, z);
    }
}
