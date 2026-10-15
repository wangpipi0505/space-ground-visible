package com.runisys.orbit.spacevisibility.domain.ballistic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runisys.orbit.spacevisibility.domain.payload.StandardPayloads;
import com.runisys.orbit.spacevisibility.domain.payload.PayloadType;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 使用原三模型位置构造已知LOS，验证真实扫描、实际采样dt及各载荷独立判定。 */
class StandardPayloadScanTest {
    private static final double EPOCH=OrbitCalculator.utcToJ2000Sec("2026/07/01 10:00:00.000");
    private OrbitCalculator.OrbitElements satellite() {
        OrbitCalculator.OrbitElements s=new OrbitCalculator.OrbitElements();
        s.satelliteId="SAT-A";s.name="SAT-A";s.semiMajorAxisM=8388608;s.eccentricity=0;
        s.inclinationDeg=0;s.raanDeg=0;s.argumentOfPerigeeDeg=0;s.meanAnomalyDeg=0;s.epochSec=EPOCH;
        return s;
    }
    private OrbitCalculator.SatellitePayloadConfig payload(String id,String type,double rate,Double startup,double min,double max) {
        OrbitCalculator.SatellitePayloadConfig p=new OrbitCalculator.SatellitePayloadConfig();p.payloadId=id;p.payloadName=id;
        p.sensorConfig=OrbitCalculator.SensorVisibilityConfig.standard(StandardPayloads.create(PayloadType.valueOf(type),
                min,max,null,null,null,null,rate,0.0,startup));return p;
    }
    private List<OrbitCalculator.BallisticPoint> points(OrbitCalculator.OrbitElements satellite,OrbitCalculator.OrbitModel model) {
        List<OrbitCalculator.BallisticPoint> points=new ArrayList<>();
        // 0,2,3秒原点，LOS先+Y后+Z再+Y：真实速率45、90度/秒；固定1秒错拒第二点，固定2秒错收尾点。
        double[] times={0,2,3};
        for (int i=0;i<times.length;i++) {
            OrbitCalculator.Vector3 sat=OrbitCalculator.propagateAtAbsolute(satellite,EPOCH+times[i],model);
            OrbitCalculator.Vector3 los=i==1?new OrbitCalculator.Vector3(0,0,1500):new OrbitCalculator.Vector3(0,1500,0);
            OrbitCalculator.Vector3 target=OrbitCalculator.eciToEcefPos(sat.add(los),EPOCH+times[i]);
            OrbitCalculator.BallisticPoint p=new OrbitCalculator.BallisticPoint();p.tSec=times[i];
            p.ecefX=target.x;p.ecefY=target.y;p.ecefZ=target.z;points.add(p);
        }
        return points;
    }
    private OrbitCalculator.LaunchSearchResultV2 scan(OrbitCalculator.OrbitElements s,OrbitCalculator.OrbitModel model,int output) {
        return OrbitCalculator.searchLaunchOffsetsV2(EPOCH,0,1,1,Arrays.asList(s),points(s,model),140,model,
                2,EPOCH,output,OrbitCalculator.SensorVisibilityConfig.forPayload("对地监视",0,0),null);
    }
    @Test void 三原模型实际采样角速度阈值与点开关() throws Exception {
        for (OrbitCalculator.OrbitModel model:OrbitCalculator.OrbitModel.values()) {
            for (int output:new int[]{0,1}) {
                OrbitCalculator.OrbitElements s=satellite();
                s.payloads.add(payload("ALLOW","SPACE",46,null,1000,2000));
                s.payloads.add(payload("REJECT","GROUND",0,null,1000,2000));
                OrbitCalculator.LaunchSearchResultV2 result=scan(s,model,output);
                assertEquals(1,result.best.visibleSatellites.size());
                assertEquals("ALLOW",result.best.visibleSatellites.get(0).payloadId);
                assertEquals(2,result.best.totalObservablePointCount());
                assertEquals(2,result.best.totalObservableDurationSec());
                assertEquals(output==1?2:0,result.best.visibleSatellites.get(0).trajectoryPoints.size());
                JsonNode json=new ObjectMapper().readTree(result.toJson());
                assertEquals("SPACE",json.path("payloadType").asText());
                assertTrue(json.path("visibilityConstraint").path("halfConeAngleDeg").isNull());
            }
        }
    }
    @Test void 真实新距离拒绝空结果和启动元数据不参与窗口() throws Exception {
        OrbitCalculator.OrbitElements s=satellite();s.payloads.add(payload("EMPTY","SPACE",0,null,2000,3000));
        JsonNode empty=new ObjectMapper().readTree(scan(s,OrbitCalculator.OrbitModel.TWO_BODY,0).toJson());
        assertEquals("SPACE",empty.path("payloadType").asText());
        assertEquals(0,empty.path("best").path("visibleSatellites").size());
        assertTrue(empty.path("best").path("visibilityConstraint").path("halfConeAngleDeg").isNull());
        String expected=null;
        for(Double startup:new Double[]{null,-1.0,0.0,1.0,1000.0}) {
            s.payloads.clear();s.payloads.add(payload("SAME","SPACE",46,startup,1000,2000));
            String current=scan(s,OrbitCalculator.OrbitModel.TWO_BODY,1).toJson();
            if(expected==null)expected=current;else assertEquals(expected,current);
        }
    }

    @Test void 标准对地沿迹与法向矩形窗口实际使用原模型速度() {
        for (OrbitCalculator.OrbitModel model:OrbitCalculator.OrbitModel.values()) {
            OrbitCalculator.OrbitElements s=satellite();
            OrbitCalculator.SatellitePayloadConfig ground=payload("GROUND-ALLOW","GROUND",46,null,1000,2000);
            // 初始沿迹+Y方位0°，下一点法向+Z方位90°；接收此矩形但原对地圆锥不会接收近水平LOS。
            ground.sensorConfig=OrbitCalculator.SensorVisibilityConfig.standard(StandardPayloads.create(PayloadType.GROUND,
                    1000.0,2000.0,0.0,100.0,-1.0,1.0,46.0,0.0,null));
            s.payloads.add(ground);
            OrbitCalculator.SatellitePayloadConfig reject=payload("GROUND-REJECT","GROUND",46,null,1000,2000);
            reject.sensorConfig=OrbitCalculator.SensorVisibilityConfig.standard(StandardPayloads.create(PayloadType.GROUND,
                    1000.0,2000.0,100.0,200.0,-1.0,1.0,46.0,0.0,null));
            s.payloads.add(reject);
            OrbitCalculator.LaunchSearchResultV2 result=scan(s,model,0);
            assertEquals(1,result.best.visibleSatellites.size());
            assertEquals("GROUND-ALLOW",result.best.visibleSatellites.get(0).payloadId);
            assertEquals(2,result.best.totalObservablePointCount());
            assertEquals(2,result.best.totalObservableDurationSec());
        }
    }
}
