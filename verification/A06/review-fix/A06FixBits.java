import java.util.Locale;
public class A06FixBits {
  public static void main(String[] args) {
    double[] values={1.15,-1.15,64.85,171.42857142857144,.0008504,.0008505,.0008515,7530612.4,7530612.5,1e23,-1e23,1e18+128,-1e18-128,Double.MAX_VALUE,-Double.MAX_VALUE,Double.MIN_VALUE,-Double.MIN_VALUE,0.0,-0.0,1e-7,-1e-7,0.0000005,-0.0000005};
    for(double x:values) for(int d:new int[]{0,1,6}) {
      String f=String.format(Locale.US,"%."+d+"f",x);
      System.out.println("{\"input\":\""+Double.toString(x)+"\",\"digits\":"+d+",\"formatted\":\""+f+"\",\"bits\":\""+String.format("%016x",Double.doubleToRawLongBits(Double.parseDouble(f)))+"\"}");
    }
  }
}
