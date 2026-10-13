package com.runisys.orbit.spacevisibility.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.SatelliteEntity;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.mapper.SatelliteMapper;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.mapper.TargetMapper;
import com.runisys.orbit.spacevisibility.application.PlanningTaskService;
import com.runisys.orbit.spacevisibility.application.VisibilityObservationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PlanningTaskControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SatelliteMapper satelliteMapper;
    @Autowired
    private TargetMapper targetMapper;
    @Autowired
    private PlanningTaskService planningTaskService;

    @BeforeEach
    void resetReferenceData() {
        targetMapper.delete(null);
        satelliteMapper.delete(null);

        SatelliteEntity satellite = new SatelliteEntity();
        satellite.setId(1L);
        satellite.setName("测试我方星");
        satellite.setModelType("KEPLER_6");
        satellite.setSemiMajorAxis(new BigDecimal("7000000"));
        satellite.setEccentricity(BigDecimal.ZERO);
        satellite.setInclination(BigDecimal.ZERO);
        satellite.setRaan(BigDecimal.ZERO);
        satellite.setArgumentOfPerigee(BigDecimal.ZERO);
        satellite.setMeanAnomaly(BigDecimal.ZERO);
        satellite.setEpochTime(LocalDateTime.of(2000, 1, 1, 12, 0));
        satellite.setPayloadJson("{\"payloadType\":\"SPACE\",\"distance\":[0,3000000],\"angleRate\":1,\"sunAvoidHalfAngle\":0}");
        satelliteMapper.insert(satellite);

        com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.TargetEntity target =
                new com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.TargetEntity();
        target.setId(2L);
        target.setName("测试目标");
        target.setModelType("KEPLER_6");
        target.setSemiMajorAxis(new BigDecimal("7000000"));
        target.setEccentricity(BigDecimal.ZERO);
        target.setInclination(BigDecimal.ZERO);
        target.setRaan(BigDecimal.ZERO);
        target.setArgumentOfPerigee(BigDecimal.ZERO);
        target.setMeanAnomaly(BigDecimal.ONE);
        target.setEpochTime(LocalDateTime.of(2000, 1, 1, 12, 0));
        targetMapper.insert(target);
    }

    /** 验证异步任务提交、轮询状态到成功后获取规划结果的完整流程。 */
    @Test
    void submitsPollsAndFetchesVisibilityPlanTaskResult() throws Exception {
        MvcResult submitted = mockMvc.perform(post("/api/v1/space/visibility/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"observerSatelliteIds\":[1],\"targetIds\":[2],"
                                + "\"startTime\":\"2000-01-01T12:00:00Z\",\"endTime\":\"2000-01-01T12:00:05Z\","
                                + "\"coarseStepSec\":1,\"fineStepSec\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").isString())
                .andReturn();
        String taskId = objectMapper.readTree(submitted.getResponse().getContentAsByteArray())
                .get("taskId").asText();

        JsonNode statusNode = pollUntilTerminal(taskId);

        assertThat(statusNode.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(statusNode.get("totalPairs").asLong()).isEqualTo(1L);
        assertThat(statusNode.get("completedPairs").asLong()).isEqualTo(1L);
        assertThat(statusNode.get("createdAt").isTextual()).isTrue();
        assertThat(statusNode.get("completedAt").isTextual()).isTrue();
        assertThat(statusNode.has("result")).isFalse();

        mockMvc.perform(get("/api/v1/space/visibility/tasks/{taskId}/result", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results").isArray())
                .andExpect(jsonPath("$.results[0].targetName").value("测试目标"));
    }

    /** 验证参数校验失败的异步任务进入失败终态并携带业务错误信息。 */
    @Test
    void reportsValidationFailureAsFailedTask() throws Exception {
        MvcResult submitted = mockMvc.perform(post("/api/v1/space/visibility/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"observerSatelliteIds\":[999],\"targetIds\":[2],"
                                + "\"startTime\":\"2000-01-01T12:00:00Z\",\"endTime\":\"2000-01-01T12:00:05Z\","
                                + "\"coarseStepSec\":1,\"fineStepSec\":1}"))
                .andExpect(status().isOk())
                .andReturn();
        String taskId = objectMapper.readTree(submitted.getResponse().getContentAsByteArray())
                .get("taskId").asText();

        JsonNode statusNode = pollUntilTerminal(taskId);

        assertThat(statusNode.get("status").asText()).isEqualTo("FAILED");
        assertThat(statusNode.get("errorMessage").asText()).isEqualTo("存在未找到的观测卫星");

        mockMvc.perform(get("/api/v1/space/visibility/tasks/{taskId}/result", taskId))
                .andExpect(status().isConflict());
    }

    /** 验证不存在的任务在状态、结果和取消端点均返回 404。 */
    @Test
    void returnsNotFoundForUnknownTask() throws Exception {
        mockMvc.perform(get("/api/v1/space/visibility/tasks/{taskId}", "missing"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/space/visibility/tasks/{taskId}/result", "missing"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/space/visibility/tasks/{taskId}", "missing"))
                .andExpect(status().isNotFound());
    }

    /** 真实服务在最后批完成后被DELETE取消：接口先仍RUNNING，任务退出必须CANCELLED且结果409。 */
    @Test
    void returnsConflictAfterCancellationAcceptedBeforeFinalPublication() throws Exception {
        CountDownLatch workCompleted = new CountDownLatch(1);
        CountDownLatch allowReturn = new CountDownLatch(1);
        try {
            String taskId = planningTaskService.submit(running -> {
                running.reportProgress(1L, 1L);
                workCompleted.countDown();
                try {
                    assertThat(allowReturn.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return new SpaceApiModels.VisibilityPlanResponse(Collections.<VisibilityObservationResult>emptyList());
            }).getTaskId();
            assertThat(workCompleted.await(5, TimeUnit.SECONDS)).isTrue();
            mockMvc.perform(delete("/api/v1/space/visibility/tasks/{taskId}", taskId))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RUNNING"));
            allowReturn.countDown();
            assertThat(pollUntilTerminal(taskId).get("status").asText()).isEqualTo("CANCELLED");
            mockMvc.perform(get("/api/v1/space/visibility/tasks/{taskId}/result", taskId))
                    .andExpect(status().isConflict());
            mockMvc.perform(delete("/api/v1/space/visibility/tasks/{taskId}", taskId))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        } finally {
            allowReturn.countDown();
        }
    }

    /** 轮询任务状态直到进入终态，避免异步执行时序导致的偶发失败。 */
    private JsonNode pollUntilTerminal(String taskId) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            MvcResult polled = mockMvc.perform(get("/api/v1/space/visibility/tasks/{taskId}", taskId))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode statusNode = objectMapper.readTree(polled.getResponse().getContentAsByteArray());
            String status = statusNode.get("status").asText();
            if ("SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
                return statusNode;
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("任务未在预期时间内结束: " + taskId);
    }
}
