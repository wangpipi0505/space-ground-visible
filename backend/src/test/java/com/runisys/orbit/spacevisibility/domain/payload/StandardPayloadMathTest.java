package com.runisys.orbit.spacevisibility.domain.payload;

import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 独立手算几何：R=+X、T=+Y、N=+Z，直角与距离边界不由被测实现生成期望。 */
class StandardPayloadMathTest {
    private final Vector3 observer = new Vector3(8388608, 0, 0);
    private final Vector3 velocity = new Vector3(0, 7500, 0);

    private PayloadConstraint constraint(String type, Double azMin, Double azMax,
            Double elMin, Double elMax, double rate, double sun, Double startup) {
        return StandardPayloads.create(PayloadType.valueOf(type), 1000.0, 2000.0,
                azMin, azMax, elMin, elMax, rate, sun, startup);
    }

    @Test void 方位四象限和俯仰正负及径向退化() {
        Vector3[] directions = {new Vector3(0,1,0), new Vector3(0,0,1),
                new Vector3(0,-1,0), new Vector3(0,0,-1)};
        for (int i=0;i<4;i++) {
            PayloadConstraintMath.LvlhAngles angles = PayloadConstraintMath.lvlhAngles(observer, velocity, directions[i]);
            assertEquals(i*90.0, angles.azimuthDeg, 1e-12);
            assertEquals(0, angles.elevationDeg, 1e-12);
        }
        assertEquals(90, PayloadConstraintMath.lvlhAngles(observer, velocity, new Vector3(1,0,0)).elevationDeg);
        assertEquals(-90, PayloadConstraintMath.lvlhAngles(observer, velocity, new Vector3(-1,0,0)).elevationDeg);
        assertNull(PayloadConstraintMath.lvlhAngles(observer, new Vector3(1,0,0), directions[0]));
        PayloadConstraint open = constraint("SPACE",null,null,null,null,0,0,null);
        assertTrue(PayloadConstraintMath.insideAttitudeWindow(null,open));
        assertFalse(PayloadConstraintMath.insideAttitudeWindow(null,constraint("GROUND",0.0,90.0,null,null,0,0,null)));
    }

    @Test void 距离闭边界与真实九十度每两秒角速度() {
        PayloadConstraint c = constraint("SPACE",null,null,null,null,44,0,null);
        assertTrue(PayloadConstraintMath.insideRange(1000,c));
        assertTrue(PayloadConstraintMath.insideRange(2000,c));
        assertFalse(PayloadConstraintMath.insideRange(999,c));
        assertFalse(PayloadConstraintMath.insideRange(2001,c));
        Vector3 previous = new Vector3(0,1000,0), current = new Vector3(0,0,1000);
        assertEquals(45,PayloadConstraintMath.lineOfSightRate(current,previous,2));
        assertFalse(PayloadConstraintMath.insideRate(45,previous,2,c));
        assertTrue(PayloadConstraintMath.insideRate(45,previous,2,constraint("SPACE",null,null,null,null,45,0,null)));
        assertTrue(PayloadConstraintMath.insideRate(45,null,2,c));
        assertTrue(PayloadConstraintMath.insideRate(45,previous,0,c));
        assertTrue(PayloadConstraintMath.insideRate(45,previous,-2,c));
        assertFalse(PayloadConstraintMath.insideRate(45,previous,2,constraint("GROUND",null,null,null,null,0,0,null)));
    }

    @Test void 太阳严格边界关闭和两种光照政策及启动元数据() {
        Vector3 los = new Vector3(0,1000,0), sun = new Vector3(0,0,1);
        assertTrue(PayloadConstraintMath.outsideSunAvoidance(los,sun,constraint("SPACE",null,null,null,null,0,90,null),true));
        PayloadConstraint reject = constraint("SPACE",null,null,null,null,0,91,null);
        assertFalse(PayloadConstraintMath.outsideSunAvoidance(los,sun,reject,true));
        assertTrue(PayloadConstraintMath.outsideSunAvoidance(los,sun,reject,false));
        assertTrue(PayloadConstraintMath.outsideSunAvoidance(los,los,constraint("SPACE",null,null,null,null,0,0,null),true));
        for (Double startup : new Double[]{null,-1.0,0.0,1.0,1000.0}) {
            PayloadConstraint c = constraint("GROUND",0.0,90.0,-91.0,0.0,0,0,startup);
            assertTrue(PayloadConstraintMath.isVisible(observer,velocity,observer.add(los),c,null,1,true,sun,true));
            assertFalse(PayloadConstraintMath.isVisible(observer,velocity,observer.add(los.scale(-1)),c,null,1,true,sun,true));
            assertFalse(PayloadConstraintMath.isVisible(observer,velocity,observer.add(los),c,null,1,false,sun,true));
        }
    }
}
