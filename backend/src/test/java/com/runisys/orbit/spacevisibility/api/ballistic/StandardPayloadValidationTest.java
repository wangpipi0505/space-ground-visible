package com.runisys.orbit.spacevisibility.api.ballistic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runisys.orbit.spacevisibility.application.PayloadConstraintFactory;
import com.runisys.orbit.spacevisibility.domain.payload.PayloadConstraint;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 新弹道输入和空间保存共用协议的边界测试；期望取自冻结字段约定，不生成数值黄金。 */
class StandardPayloadValidationTest {
    private VisibilityRequest.PayloadDef valid(String type) {
        VisibilityRequest.PayloadDef p = new VisibilityRequest.PayloadDef();
        p.setPayloadType(type); p.setMinDistanceKm(1.0); p.setMaxDistanceKm(2.0);
        p.setAngleRateDegPerSec(0.0); p.setSunAvoidHalfAngleDeg(0.0);
        return p;
    }

    @Test void 两侧共用单位哨兵与完整原值回显() {
        PayloadConstraintFactory factory = new PayloadConstraintFactory(new ObjectMapper());
        for (String type : new String[]{"SPACE","GROUND"}) {
            for (double rate : new double[]{0,1,180}) {
                for (Double startup : new Double[]{null,-1.0,0.0,1.0}) {
                    VisibilityRequest.PayloadDef p = valid(type);
                    p.setAngleRateDegPerSec(rate); p.setStartupAngleRateDegPerSec(startup);
                    p.setElevationMinDeg(type.equals("SPACE")?-90.0:-91.0); p.setElevationMaxDeg(90.0);
                    p.setAzimuthMinDeg(0.0); p.setAzimuthMaxDeg(360.0);
                    PayloadConstraint actual = p.toStandardConstraint();
                    String saved = type.equals("SPACE")
                            ? factory.createSpacePayloadJson(1000.0,2000.0,0.0,360.0,-90.0,90.0,rate,0.0,startup)
                            : factory.createGroundPayloadJson(1000.0,2000.0,0.0,360.0,-91.0,90.0,rate,0.0,startup);
                    PayloadConstraint space = factory.parse(saved);
                    assertEquals(1000,actual.getMinDetectRangeM()); assertEquals(2000,actual.getMaxDetectRangeM());
                    assertEquals(space.getAngleRateDegPerSec(),actual.getAngleRateDegPerSec());
                    assertEquals(space.getStartupAngleRateDegPerSec(),actual.getStartupAngleRateDegPerSec());
                    Map<String,Object> echo=p.standardConfiguration();
                    assertEquals(12,echo.size()); assertEquals(rate,echo.get("angleRateDegPerSec"));
                    assertEquals(startup,echo.get("startupAngleRateDegPerSec"));
                    assertEquals(type,echo.get("payloadType"));
                }
            }
        }
        VisibilityRequest.PayloadDef p=valid(" space "); p.setAngleRateDegPerSec(-1.0);
        assertEquals(Double.MAX_VALUE,p.toStandardConstraint().getAngleRateDegPerSec());
        assertEquals(-1.0,p.standardConfiguration().get("angleRateDegPerSec"));
        p.setMaxDistanceKm(0.0); assertEquals(0,p.toStandardConstraint().getMaxDetectRangeM());
    }

    @Test void 必填有限数和转换溢出均拒绝() {
        for (Double value : new Double[]{null,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            VisibilityRequest.PayloadDef p=valid("SPACE"); p.setMinDistanceKm(value);
            assertThrows(IllegalArgumentException.class,p::toStandardConstraint);
            p=valid("SPACE"); p.setMaxDistanceKm(value); assertThrows(IllegalArgumentException.class,p::toStandardConstraint);
            p=valid("SPACE"); p.setAngleRateDegPerSec(value); assertThrows(IllegalArgumentException.class,p::toStandardConstraint);
            p=valid("SPACE"); p.setSunAvoidHalfAngleDeg(value); assertThrows(IllegalArgumentException.class,p::toStandardConstraint);
        }
        VisibilityRequest.PayloadDef p=valid("SPACE"); p.setMaxDistanceKm(Double.MAX_VALUE);
        assertThrows(IllegalArgumentException.class,p::toStandardConstraint);
    }

    @Test void 非法距离窗口太阳速率与元数据表() {
        for (double value : new double[]{-1,1,0.5}) {
            VisibilityRequest.PayloadDef p=valid("SPACE");p.setMaxDistanceKm(value);
            assertThrows(IllegalArgumentException.class,p::toStandardConstraint);
        }
        VisibilityRequest.PayloadDef p=valid("SPACE");p.setMinDistanceKm(-1.0);
        assertThrows(IllegalArgumentException.class,p::toStandardConstraint);
        for (Double[] pair : new Double[][]{{0.0,null},{null,360.0},{Double.NaN,360.0},
                {0.0,Double.POSITIVE_INFINITY},{-1.0,1.0},{1.0,1.0},{10.0,9.0},{0.0,361.0}}) {
            p=valid("SPACE");p.setAzimuthMinDeg(pair[0]);p.setAzimuthMaxDeg(pair[1]);
            assertThrows(IllegalArgumentException.class,p::toStandardConstraint);
        }
        for (Double[] pair : new Double[][]{{0.0,null},{null,90.0},{Double.NaN,90.0},
                {-90.0,Double.POSITIVE_INFINITY},{-91.0,90.0},{1.0,1.0},{10.0,9.0},{0.0,91.0}}) {
            p=valid("SPACE");p.setElevationMinDeg(pair[0]);p.setElevationMaxDeg(pair[1]);
            assertThrows(IllegalArgumentException.class,p::toStandardConstraint);
        }
        p=valid("GROUND");p.setElevationMinDeg(-91.1);p.setElevationMaxDeg(0.0);
        assertThrows(IllegalArgumentException.class,p::toStandardConstraint);
        p=valid("GROUND");p.setAngleRateDegPerSec(-1.0);assertThrows(IllegalArgumentException.class,p::toStandardConstraint);
        p=valid("SPACE");p.setAngleRateDegPerSec(-2.0);assertThrows(IllegalArgumentException.class,p::toStandardConstraint);
        for (double value : new double[]{-1,181}) {
            p=valid("SPACE");p.setSunAvoidHalfAngleDeg(value);assertThrows(IllegalArgumentException.class,p::toStandardConstraint);
        }
        for (double value : new double[]{-2,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            p=valid("SPACE");p.setStartupAngleRateDegPerSec(value);assertThrows(IllegalArgumentException.class,p::toStandardConstraint);
        }
    }
}
