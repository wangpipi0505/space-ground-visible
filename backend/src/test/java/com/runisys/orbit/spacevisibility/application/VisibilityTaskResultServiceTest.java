package com.runisys.orbit.spacevisibility.application;

import com.runisys.orbit.spacevisibility.api.SpaceApiModels;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.VisibilityTaskResultEntity;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.mapper.VisibilityTaskResultMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证异步可见性规划任务结果的持久化写入时机与查询维护逻辑（H2 环境）。 */
@SpringBootTest
class VisibilityTaskResultServiceTest {

    @Autowired
    private VisibilityTaskResultService taskResultService;
    @Autowired
    private VisibilityTaskResultMapper taskResultMapper;

    @BeforeEach
    void resetTaskResults() {
        taskResultMapper.delete(null);
    }

    /** 验证任务提交时写入待处理记录并保留请求原文。 */
    @Test
    void recordsSubmittedTaskAsPending() {
        SpaceApiModels.VisibilityPlanRequest request = new SpaceApiModels.VisibilityPlanRequest();
        request.setObserverSatelliteIds(Collections.singletonList(1L));
        request.setMinWeight(new BigDecimal("10"));

        taskResultService.recordSubmitted("task-pending", request);

        VisibilityTaskResultEntity entity = taskResultMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<VisibilityTaskResultEntity>()
                        .eq(VisibilityTaskResultEntity::getTaskId, "task-pending"));
        assertThat(entity).isNotNull();
        assertThat(entity.getId()).isNotNull();
        assertThat(entity.getTaskType()).isEqualTo("VISIBILITY");
        assertThat(entity.getStatus()).isEqualTo("PENDING");
        assertThat(entity.getRequestJson()).contains("observerSatelliteIds").contains("minWeight");
        assertThat(entity.getCreateTime()).isNotNull();
        assertThat(entity.getCompleteTime()).isNull();
        assertThat(entity.getResultJson()).isNull();
    }

    /** 验证任务成功终态回填状态、进度、结果原文与完成时刻。 */
    @Test
    void updatesSucceededTaskWithResultJson() {
        taskResultService.recordSubmitted("task-succeeded", new SpaceApiModels.VisibilityPlanRequest());
        PlanningTask task = new PlanningTask("task-succeeded");
        task.reportProgress(3L, 3L);
        task.markSucceeded(new SpaceApiModels.VisibilityPlanResponse(
                Collections.<VisibilityObservationResult>emptyList(),
                Collections.<SpaceApiModels.SkippedPairResponse>emptyList(),
                1L,
                Collections.singletonList(new SpaceApiModels.SkippedPairResponse(
                        "observer-1", "观测星一", "target-1", "目标一", "PRECHECK", "轨道状态不可用"))));

        taskResultService.onTerminal(task);

        VisibilityTaskResultEntity entity = findByTaskId("task-succeeded");
        assertThat(entity.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(entity.getTotalPairs()).isEqualTo(3L);
        assertThat(entity.getCompletedPairs()).isEqualTo(3L);
        assertThat(entity.getResultJson()).contains("results");
        assertThat(entity.getSkippedPairsJson()).contains("target-1").contains("轨道状态不可用");
        assertThat(entity.getCompleteTime()).isNotNull();
        assertThat(entity.getErrorMessage()).isNull();
        assertThat(taskResultService.findDetail(entity.getId()).getSkippedPairsJson())
                .contains("observer-1").contains("PRECHECK");
    }

    /** 验证任务失败终态回填错误信息且不写结果原文。 */
    @Test
    void updatesFailedTaskWithErrorMessage() {
        taskResultService.recordSubmitted("task-failed", new SpaceApiModels.VisibilityPlanRequest());
        PlanningTask task = new PlanningTask("task-failed");
        task.markFailed("存在未找到的观测卫星");

        taskResultService.onTerminal(task);

        VisibilityTaskResultEntity entity = findByTaskId("task-failed");
        assertThat(entity.getStatus()).isEqualTo("FAILED");
        assertThat(entity.getErrorMessage()).isEqualTo("存在未找到的观测卫星");
        assertThat(entity.getResultJson()).isNull();
        assertThat(entity.getCompleteTime()).isNotNull();
    }

    /** 验证缺少提交记录的终态回填只记日志，不向调用方抛出异常。 */
    @Test
    void toleratesTerminalUpdateWithoutSubmittedRecord() {
        PlanningTask task = new PlanningTask("task-missing");
        task.markCancelled();

        taskResultService.onTerminal(task);

        assertThat(taskResultMapper.selectCount(null)).isZero();
    }

    /** 验证分页查询按提交时刻倒序，列表项不读取请求与结果 JSON 大字段。 */
    @Test
    void pagesRecordsOrderedByCreateTimeDesc() {
        insertRecord("task-old", "SUCCEEDED", "例行任务", LocalDateTime.of(2026, 8, 1, 0, 0));
        insertRecord("task-new", "FAILED", null, LocalDateTime.of(2026, 8, 3, 0, 0));
        insertRecord("task-mid", "SUCCEEDED", null, LocalDateTime.of(2026, 8, 2, 0, 0));

        SpaceApiModels.PageResponse<SpaceApiModels.VisibilityTaskResultSummaryResponse> page =
                taskResultService.page(1L, 20L, null, null);

        assertThat(page.getTotal()).isEqualTo(3L);
        assertThat(page.getRecords()).extracting(SpaceApiModels.VisibilityTaskResultSummaryResponse::getTaskId)
                .containsExactly("task-new", "task-mid", "task-old");
    }

    /** 验证状态筛选与关键字同时匹配任务标识和备注。 */
    @Test
    void filtersByStatusAndKeyword() {
        insertRecord("task-alpha", "SUCCEEDED", "重点目标", LocalDateTime.of(2026, 8, 1, 0, 0));
        insertRecord("task-beta", "FAILED", "重点目标", LocalDateTime.of(2026, 8, 2, 0, 0));
        insertRecord("task-gamma", "SUCCEEDED", null, LocalDateTime.of(2026, 8, 3, 0, 0));

        assertThat(taskResultService.page(1L, 20L, "SUCCEEDED", null).getTotal()).isEqualTo(2L);
        assertThat(taskResultService.page(1L, 20L, null, "重点").getTotal()).isEqualTo(2L);
        assertThat(taskResultService.page(1L, 20L, null, "beta").getTotal()).isEqualTo(1L);
        assertThat(taskResultService.page(1L, 20L, "SUCCEEDED", "重点").getRecords())
                .extracting(SpaceApiModels.VisibilityTaskResultSummaryResponse::getTaskId)
                .containsExactly("task-alpha");
    }

    /** 验证非法状态与分页参数被拒绝。 */
    @Test
    void rejectsInvalidQueryParameters() {
        assertThatThrownBy(() -> taskResultService.page(1L, 20L, "UNKNOWN", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> taskResultService.page(0L, 20L, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> taskResultService.page(1L, 201L, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 验证备注更新、明细读取与删除在记录存在和不存在时的行为。 */
    @Test
    void updatesRemarkAndDeletesRecord() {
        insertRecord("task-maintain", "SUCCEEDED", null, LocalDateTime.of(2026, 8, 1, 0, 0));
        Long id = findByTaskId("task-maintain").getId();

        SpaceApiModels.VisibilityTaskResultDetailResponse updated = taskResultService.updateRemark(id, "已核对");
        assertThat(updated.getRemark()).isEqualTo("已核对");
        assertThat(updated.getRequestJson()).isEqualTo("{\"observerSatelliteIds\":[1]}");
        assertThat(taskResultService.findDetail(id).getRemark()).isEqualTo("已核对");

        assertThat(taskResultService.updateRemark(999999L, "x")).isNull();
        assertThat(taskResultService.findDetail(999999L)).isNull();

        assertThat(taskResultService.delete(id)).isTrue();
        assertThat(taskResultService.delete(id)).isFalse();
        assertThat(taskResultService.findDetail(id)).isNull();
    }

    /** 验证超长备注被拒绝。 */
    @Test
    void rejectsTooLongRemark() {
        StringBuilder longRemark = new StringBuilder();
        for (int i = 0; i < 513; i++) {
            longRemark.append('a');
        }
        assertThatThrownBy(() -> taskResultService.updateRemark(1L, longRemark.toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private VisibilityTaskResultEntity findByTaskId(String taskId) {
        return taskResultMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<VisibilityTaskResultEntity>()
                        .eq(VisibilityTaskResultEntity::getTaskId, taskId));
    }

    private void insertRecord(String taskId, String status, String remark, LocalDateTime createTime) {
        VisibilityTaskResultEntity entity = new VisibilityTaskResultEntity();
        entity.setTaskId(taskId);
        entity.setTaskType("VISIBILITY");
        entity.setStatus(status);
        entity.setRequestJson("{\"observerSatelliteIds\":[1]}");
        entity.setRemark(remark);
        entity.setCreateTime(createTime);
        taskResultMapper.insert(entity);
    }
}
