package com.runisys.orbit.spacevisibility.domain.payload;

import com.runisys.orbit.spacevisibility.domain.space.illumination.IlluminationState;
import com.runisys.orbit.spacevisibility.domain.space.illumination.SolarGeometry;
import com.runisys.orbit.spacevisibility.domain.space.visibility.PayloadVisibilityEvaluator;
import com.runisys.orbit.spacevisibility.domain.space.visibility.VisibilitySample;

import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import com.runisys.orbit.spacevisibility.domain.ballistic.visibility.OrbitCalculator;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

/** 两个原物理上下文各自构造背日侧短LOS：不跨参考系比较或伪造共同太阳。 */
class StandardPayloadSolarContextTest {
    private PayloadConstraint constraint(double sun) {
        return StandardPayloads.create(PayloadType.SPACE,0.0,0.0,null,null,null,null,0.0,sun,null);
    }
    @Test void 空间本影豁免而弹道背日侧仍回避原解析太阳() throws Exception {
        Instant instant=Instant.parse("2026-07-01T10:00:00Z");
        Vector3 spaceSun=SolarGeometry.sunDirectionTeme(instant);
        Vector3 observer=spaceSun.scale(-7000000);
        Vector3 target=observer.add(spaceSun.scale(1000));
        Vector3 velocity=spaceSun.cross(new Vector3(0,0,1)).scale(7500);
        VisibilitySample space=PayloadVisibilityEvaluator.evaluate(observer,velocity,target,constraint(10),instant,null,1);
        assertEquals(IlluminationState.UMBRA,space.getObserverIllumination());
        assertTrue(space.isGeometryVisible());

        // 反射读取冻结保留的原解析太阳入口，仅用于构造几何，不计算期望；同向夹角必为0。
        double time=OrbitCalculator.utcToJ2000Sec("2026/07/01 10:00:00.000");
        Method sunMethod=Class.forName("com.runisys.orbit.spacevisibility.domain.ballistic.visibility.BallisticVisibilityGeometry").getDeclaredMethod("sunDirectionEci",double.class);
        sunMethod.setAccessible(true);
        OrbitCalculator.Vector3 sun=(OrbitCalculator.Vector3)sunMethod.invoke(null,time);
        OrbitCalculator.Vector3 sat=sun.scale(-7000000), end=sat.add(sun.scale(1000));
        Vector3 v=new Vector3(sun.y,-sun.x,0).scale(7500);
        assertTrue(OrbitCalculator.isStandardTargetObservable(sat,v,end,constraint(0),time,null,1));
        assertFalse(OrbitCalculator.isStandardTargetObservable(sat,v,end,constraint(10),time,null,1));
    }
}
