import java.util.Locale;
public class A06FixFormatter {
    static double norm(double value) { double r = value % 360.0; return r < 0.0 ? r + 360.0 : r; }
    public static void main(String[] args) {
        double[][] seeds={{7530612.4,.0008504,64.85,1.15,-1.15,30},{7530612.5,.0008505,-1.15,120,64.85,30},{7530612.49,.0008515,1.15,-1.15,1.15,1.15}};
        for(double[] s: seeds) for(int p=0;p<7;p++) {
            double r=norm(s[3]+p*360.0/7), m=norm(s[5]+p*360.0/7);
            System.out.printf(Locale.US,"p=%d a=%.0f e=%.6f i=%.1f r=%.1f arg=%.1f m=%.1f%n",p,s[0],s[1],s[2],r,s[4],m);
        }
        for(double x:new double[]{1.15,-1.15,64.85,171.42857142857144}) System.out.printf(Locale.US,"half %.17g -> %.1f%n",x,x);
    }
}
