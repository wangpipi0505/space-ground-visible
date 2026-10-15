package com.tx.alg.api;

import com.tx.alg.OrbitCalculator;
import com.runisys.orbit.spacevisibility.domain.orbit.*;
import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.BufferedWriter;
import java.lang.reflect.Method;
import java.util.*;

/** A09隔离导出探针：只调用原解析、外推和坐标转换入口，不另写动力学。 */
public final class ControlledInputProbe {
    private static OrbitCalculator.OrbitElements elements(double anomaly) {
        OrbitCalculator.OrbitElements e = new OrbitCalculator.OrbitElements();
        e.semiMajorAxisM=7530612; e.eccentricity=.000850; e.inclinationDeg=64.8;
        e.raanDeg=120; e.argumentOfPerigeeDeg=270; e.meanAnomalyDeg=anomaly;
        e.epochSec=OrbitCalculator.utcToJ2000Sec("2026/07/01 10:00:00");
        return e;
    }
    private static OrbitPropagationTool bind(OrbitCalculator.OrbitElements e) {
        return BallisticOrbitPropagation.bind(new OrbitDefinition.Elements(OrbitDefinition.Model.J2_NUMERICAL,
            e.semiMajorAxisM,e.eccentricity,e.inclinationDeg,e.raanDeg,e.argumentOfPerigeeDeg,e.meanAnomalyDeg,
            OrbitTime.legacyJ2000(e.epochSec,0),0));
    }
    private static void vector(BufferedWriter w, Vector3 v) throws Exception {
        w.write(","+v.getX()+","+v.getY()+","+v.getZ());
    }
    /** 原始double逐位核对公开位置入口；速度来自真实RK4状态，不差分。 */
    private static void verify(OrbitState s, OrbitCalculator.OrbitElements e, double time) {
        OrbitCalculator.Vector3 p=OrbitCalculator.propagateAtAbsolute(e,time,OrbitCalculator.OrbitModel.J2_NUMERICAL);
        if (Double.doubleToRawLongBits(p.x)!=Double.doubleToRawLongBits(s.positionM.getX()) ||
            Double.doubleToRawLongBits(p.y)!=Double.doubleToRawLongBits(s.positionM.getY()) ||
            Double.doubleToRawLongBits(p.z)!=Double.doubleToRawLongBits(s.positionM.getZ()))
            throw new IllegalStateException("公开位置入口与真实状态逐位不一致");
    }
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.US); OrbitCalculator.utcOffsetSec=0;
        OrbitCalculator.ParsedBallisticCsv parsed=OrbitCalculator.parseBallisticCsvWithReference(Paths.get(args[0]));
        Path out=Paths.get(args[1]); Files.createDirectories(out);
        double launch=parsed.referenceEpochSec+2880;
        try (BufferedWriter w=Files.newBufferedWriter(out.resolve("original-times.csv"),StandardCharsets.UTF_8)) {
            w.write("tSec,absoluteJ2000Sec\n");
            for (OrbitCalculator.BallisticPoint p:parsed.points) w.write(p.tSec+","+(launch+p.tSec)+"\n");
        }
        Method step=OrbitCalculator.class.getDeclaredMethod("visibilitySampleStepSec",List.class,double.class);
        step.setAccessible(true);
        List<OrbitCalculator.BallisticPoint> samples=OrbitCalculator.interpolateBallistic(parsed.points,(Double)step.invoke(null,parsed.points,1.0));
        try (BufferedWriter w=Files.newBufferedWriter(out.resolve("samples.csv"),StandardCharsets.UTF_8)) {
            w.write("tSec,absoluteJ2000Sec,utc\n");
            for (OrbitCalculator.BallisticPoint p:samples) w.write(p.tSec+","+(launch+p.tSec)+","+OrbitCalculator.j2000ToUtcMillis(launch+p.tSec)+"\n");
        }
        Method sun=OrbitCalculator.class.getDeclaredMethod("sunDirectionEci",double.class); sun.setAccessible(true);
        OrbitCalculator.OrbitElements a=elements(30),b=elements(0);
        OrbitPropagationTool ta=bind(a),tb=bind(b);
        // 原0.1秒点和实际判定点取并集，确保实际double采样时刻也有直接状态。
        TreeMap<Double,OrbitCalculator.BallisticPoint> all=new TreeMap<Double,OrbitCalculator.BallisticPoint>();
        for (OrbitCalculator.BallisticPoint p:parsed.points) all.put(p.tSec,p);
        for (OrbitCalculator.BallisticPoint p:samples) all.put(p.tSec,p);
        try (BufferedWriter w=Files.newBufferedWriter(out.resolve("full-states.csv"),StandardCharsets.UTF_8)) {
            w.write("tSec,absoluteJ2000Sec,utc,SAT_A_x,SAT_A_y,SAT_A_z,SAT_A_vx,SAT_A_vy,SAT_A_vz,SAT_B_x,SAT_B_y,SAT_B_z,SAT_B_vx,SAT_B_vy,SAT_B_vz,TARGET_x,TARGET_y,TARGET_z,sun_x,sun_y,sun_z\n");
            for (OrbitCalculator.BallisticPoint p:all.values()) {
                double time=launch+p.tSec;
                OrbitState sa=ta.stateAt(OrbitTime.legacyJ2000(time,0),OrbitDefinition.Frame.J2000);
                OrbitState sb=tb.stateAt(OrbitTime.legacyJ2000(time,0),OrbitDefinition.Frame.J2000);
                verify(sa,a,time); verify(sb,b,time);
                OrbitCalculator.Vector3 target=OrbitCalculator.ballisticPointToEciAtTime(p,time);
                OrbitCalculator.Vector3 solar=(OrbitCalculator.Vector3)sun.invoke(null,time);
                w.write(p.tSec+","+time+","+OrbitCalculator.j2000ToUtcMillis(time));
                vector(w,sa.positionM);vector(w,sa.velocityMps);vector(w,sb.positionM);vector(w,sb.velocityMps);
                w.write(","+target.x+","+target.y+","+target.z+","+solar.x+","+solar.y+","+solar.z+"\n");
            }
        }
        System.out.println("原CSV点数="+parsed.points.size()+"；实际判定点数="+samples.size()+"；全状态并集="+all.size()+"；末点="+samples.get(samples.size()-1).tSec);
        System.out.println("发射UTC="+OrbitCalculator.j2000ToUtcMillis(launch)+"；两星全状态位置与公开入口逐位一致；UTC补偿=0；目标重新地固转惯性；太阳为原近似单位方向");
    }
}
