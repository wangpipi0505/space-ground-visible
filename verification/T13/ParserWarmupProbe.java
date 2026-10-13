import com.tx.alg.OrbitCalculator;
import java.nio.file.Paths;

/** 原解析器连续两次调用的派生大地高诊断；不经过新增应用服务，也不改变数值实现。 */
public final class ParserWarmupProbe {
    public static void main(String[] args) throws Exception {
        OrbitCalculator.ParsedBallisticCsv first = OrbitCalculator.parseBallisticCsvWithReference(Paths.get(args[0]));
        OrbitCalculator.ParsedBallisticCsv second = OrbitCalculator.parseBallisticCsvWithReference(Paths.get(args[0]));
        int different = 0;
        double maximum = 0;
        for (int i = 0; i < first.points.size(); i++) {
            double one = first.points.get(i).heightM;
            double two = second.points.get(i).heightM;
            if (Double.doubleToLongBits(one) != Double.doubleToLongBits(two)) {
                different++;
                maximum = Math.max(maximum, Math.abs(one - two));
            }
        }
        System.out.println("points=" + first.points.size());
        System.out.println("originalToOriginalHeightDifferentBits=" + different);
        System.out.println("originalToOriginalMaxHeightDifferenceMetres=" + maximum);
    }
}
