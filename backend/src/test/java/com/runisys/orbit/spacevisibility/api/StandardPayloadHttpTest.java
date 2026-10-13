package com.runisys.orbit.spacevisibility.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.jupiter.api.Assertions.*;

/** 真实随机端口Tomcat：标准/旧混合、双目标双星多载荷、点开关与完整预览原值。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="space.visibility.runtime.tomcat-protocol=org.apache.coyote.http11.Http11Nio2Protocol")
class StandardPayloadHttpTest {
    @TempDir static Path root;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("ballistic.files.root",()->root.toString());
    }
    private ObjectNode standard(String id,String type) {
        ObjectNode p=mapper.createObjectNode();p.put("payloadId",id).put("payloadName",id).put("payloadType",type);
        p.put("minDistanceKm",0.0).put("maxDistanceKm",0.0).put("angleRateDegPerSec",type.equals("SPACE")?-1.0:180.0)
                .put("sunAvoidHalfAngleDeg",0.0).put("startupAngleRateDegPerSec",-1.0);
        p.putNull("azimuthMinDeg").putNull("azimuthMaxDeg").putNull("elevationMinDeg").putNull("elevationMaxDeg");
        return p;
    }
    @Test void 辅助配置完整回显和非法参数400() {
        ObjectNode p=standard("P","SPACE");
        ResponseEntity<JsonNode> response=http.postForEntity("/api/v1/ballistic/payloads/preview",p,JsonNode.class);
        assertEquals(200,response.getStatusCodeValue());assertEquals(p,response.getBody());
        assertEquals(-1,response.getBody().path("angleRateDegPerSec").asInt());
        p.put("azimuthMinDeg",0);
        response=http.postForEntity("/api/v1/ballistic/payloads/preview",p,JsonNode.class);
        assertEquals(400,response.getStatusCodeValue());assertEquals("INVALID_ARGUMENT",response.getBody().path("code").asText());
        p=standard("P","GROUND");p.put("angleRateDegPerSec",-1);
        assertEquals(400,http.postForEntity("/api/v1/ballistic/payloads/preview",p,JsonNode.class).getStatusCodeValue());
        p=standard("P","SPACE");p.remove("minDistanceKm");
        assertEquals(400,http.postForEntity("/api/v1/ballistic/payloads/preview",p,JsonNode.class).getStatusCodeValue());
    }
    @Test void 双目标双星标准旧载荷真实分层摘要点开关() throws Exception {
        JsonNode conditions=mapper.readTree(Files.readAllBytes(Paths.get("../test-data/baseline/ballistic/conditions.json")));
        ObjectNode request=null;
        for(JsonNode condition:conditions)if(condition.path("name").asText().equals("visibility-http-ground"))
            request=(ObjectNode)condition.path("request").deepCopy();
        assertNotNull(request);
        String csv=Paths.get("../test-data/reference/ballistic/trajectory-0.1s-standard.csv").toAbsolutePath().normalize().toString();
        for(JsonNode target:request.path("ballistics"))((ObjectNode)target).put("csvPath",csv);
        // 原基准仅SAT-A在该时段可见；复制其轨道而保留两个平台身份，以明确覆盖双星输出。
        ObjectNode second=((ObjectNode)request.path("satellites").get(0)).deepCopy();
        second.put("satelliteId","SAT-B").put("name","SAT-B");
        ((ArrayNode)request.path("satellites")).set(1,second);
        for(JsonNode satellite:request.path("satellites")) {
            ArrayNode payloads=((ObjectNode)satellite).putArray("payloads");
            payloads.add(standard("STD-SPACE","SPACE"));payloads.add(standard("STD-GROUND","GROUND"));
            payloads.addObject().put("payloadId","LEGACY").put("payloadName","LEGACY")
                    .put("payloadType","对空跟踪").put("sunExclusionAngleDeg",0);
        }
        long points=-1;double duration=-1;
        for(int output:new int[]{0,1}) {
            request.put("trajectoryPointOutput",output);
            ResponseEntity<JsonNode> response=http.postForEntity("/api/orbit/visibility/search",request,JsonNode.class);
            assertEquals(200,response.getStatusCodeValue(),String.valueOf(response.getBody()));
            JsonNode result=response.getBody();assertEquals("SPACE",result.path("payloadType").asText());
            assertTrue(result.path("visibilityConstraint").path("halfConeAngleDeg").isNull());
            assertEquals(3,result.path("allResults").size());assertEquals(2,result.path("best").path("targets").size());
            JsonNode best=result.path("best");assertTrue(best.path("visibleArcCount").asInt()>0);
            if(points<0){points=best.path("visiblePointCount").asLong();duration=best.path("visibleDurationSec").asDouble();}
            else {assertEquals(points,best.path("visiblePointCount").asLong());assertEquals(duration,best.path("visibleDurationSec").asDouble());}
            assertEquals(2,best.path("visibleSatellites").size());
            for(JsonNode satellite:best.path("visibleSatellites")) {
                assertEquals(3,satellite.path("payloadResults").size());
                for(JsonNode payload:satellite.path("payloadResults")) {
                    String type=payload.path("payloadType").asText();
                    if(type.equals("对空跟踪"))assertEquals(53,payload.path("visibilityConstraint").path("innerConeAngleDeg").asInt());
                    else for(String field:new String[]{"simpleConeHalfAngleDeg","halfConeAngleDeg","innerConeAngleDeg","outerConeAngleDeg"})
                        assertTrue(payload.path("visibilityConstraint").path(field).isNull());
                    assertEquals(2,payload.path("targets").size());
                    for(JsonNode target:payload.path("targets"))for(JsonNode arc:target.path("visibleArcs")) {
                        assertEquals(payload.path("payloadId"),arc.path("payloadId"));
                        assertEquals(type,arc.path("payloadType").asText());
                        assertEquals(output==1,arc.path("trajectoryPoints").size()>0);
                    }
                }
            }
        }
        // 新非法载荷仍由原计算入口返回400单error，不套辅助接口错误wrapper。
        ((ObjectNode)request.path("satellites").get(0).path("payloads").get(0)).put("minDistanceKm",-1);
        ResponseEntity<JsonNode> error=http.postForEntity("/api/orbit/visibility/search",request,JsonNode.class);
        assertEquals(400,error.getStatusCodeValue());assertEquals(1,error.getBody().size());assertTrue(error.getBody().has("error"));
    }
}
