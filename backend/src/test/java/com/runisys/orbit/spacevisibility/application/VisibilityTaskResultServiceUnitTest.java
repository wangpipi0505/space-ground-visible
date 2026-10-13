package com.runisys.orbit.spacevisibility.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.runisys.orbit.spacevisibility.api.SpaceApiModels;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.VisibilityTaskResultEntity;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.mapper.VisibilityTaskResultMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证任务结果持久化在结果 JSON 写入失败时的两步写入与降级行为。
 * 使用 mock 的 Mapper 模拟第二次（携带结果 JSON 的）更新抛出数据库写入限制异常。
 */
class VisibilityTaskResultServiceUnitTest {

    /** 结果 JSON 写入失败时：状态小字段已先行更新，随后写入降级说明到错误信息列。 */
    @Test
    @SuppressWarnings("unchecked")
    void degradesToErrorMessageWhenResultJsonWriteFails() {
        VisibilityTaskResultMapper taskResultMapper = mock(VisibilityTaskResultMapper.class);
        when(taskResultMapper.update(argThat(entity -> entity != null && entity.getResultJson() != null), any()))
                .thenThrow(new RuntimeException("PacketTooBigException: Packet for query is too large"));
        when(taskResultMapper.update(argThat(entity -> entity != null && entity.getResultJson() == null), any()))
                .thenReturn(1);
        VisibilityTaskResultService service = new VisibilityTaskResultService(taskResultMapper, new ObjectMapper());

        PlanningTask task = new PlanningTask("task-oversized");
        task.reportProgress(19503L, 19503L);
        task.markSucceeded(new SpaceApiModels.VisibilityPlanResponse(
                Collections.<VisibilityObservationResult>emptyList()));

        service.onTerminal(task);

        ArgumentCaptor<VisibilityTaskResultEntity> captor = ArgumentCaptor.forClass(VisibilityTaskResultEntity.class);
        verify(taskResultMapper, times(3)).update(captor.capture(), any());
        List<VisibilityTaskResultEntity> updates = captor.getAllValues();

        VisibilityTaskResultEntity stateUpdate = updates.get(0);
        assertThat(stateUpdate.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(stateUpdate.getTotalPairs()).isEqualTo(19503L);
        assertThat(stateUpdate.getCompletedPairs()).isEqualTo(19503L);
        assertThat(stateUpdate.getResultJson()).isNull();
        assertThat(stateUpdate.getCompleteTime()).isNotNull();

        assertThat(updates.get(1).getResultJson()).contains("results");

        VisibilityTaskResultEntity degradationUpdate = updates.get(2);
        assertThat(degradationUpdate.getErrorMessage())
                .contains("任务计算成功")
                .contains("超出数据库写入限制")
                .contains("未持久化")
                .contains("字符");
        assertThat(degradationUpdate.getResultJson()).isNull();
        assertThat(degradationUpdate.getStatus()).isNull();
    }

    /** 结果 JSON 正常写入时只发生两次更新，不写入降级说明。 */
    @Test
    @SuppressWarnings("unchecked")
    void writesResultJsonInSecondStepWhenSmallEnough() {
        VisibilityTaskResultMapper taskResultMapper = mock(VisibilityTaskResultMapper.class);
        when(taskResultMapper.update(any(VisibilityTaskResultEntity.class), any())).thenReturn(1);
        VisibilityTaskResultService service = new VisibilityTaskResultService(taskResultMapper, new ObjectMapper());

        PlanningTask task = new PlanningTask("task-normal");
        task.markSucceeded(new SpaceApiModels.VisibilityPlanResponse(
                Collections.<VisibilityObservationResult>emptyList()));

        service.onTerminal(task);

        ArgumentCaptor<VisibilityTaskResultEntity> captor = ArgumentCaptor.forClass(VisibilityTaskResultEntity.class);
        verify(taskResultMapper, times(2)).update(captor.capture(), any());
        assertThat(captor.getAllValues().get(0).getStatus()).isEqualTo("SUCCEEDED");
        assertThat(captor.getAllValues().get(0).getResultJson()).isNull();
        assertThat(captor.getAllValues().get(1).getResultJson()).contains("results");
        assertThat(captor.getAllValues().get(1).getErrorMessage()).isNull();
    }

    /**
     * 接口响应中的跳过明细会受展示上限约束；任务结果表仍须单独保留未截断的完整清单，
     * 以便定位单个目标或卫星组合为何未参与计算。
     */
    @Test
    @SuppressWarnings("unchecked")
    void writesCompleteSkippedPairsInSeparateColumn() {
        VisibilityTaskResultMapper taskResultMapper = mock(VisibilityTaskResultMapper.class);
        when(taskResultMapper.update(any(VisibilityTaskResultEntity.class), any())).thenReturn(1);
        VisibilityTaskResultService service = new VisibilityTaskResultService(taskResultMapper, new ObjectMapper());

        SpaceApiModels.SkippedPairResponse responseItem = new SpaceApiModels.SkippedPairResponse(
                "observer-1", "观测星一", "target-1", "目标一", "PRECHECK", "轨道状态不可用");
        SpaceApiModels.SkippedPairResponse persistedOnlyItem = new SpaceApiModels.SkippedPairResponse(
                "observer-2", "观测星二", "target-2", "目标二", "SCAN", "传播失败");
        PlanningTask task = new PlanningTask("task-skipped-pairs");
        task.markSucceeded(new SpaceApiModels.VisibilityPlanResponse(
                Collections.<VisibilityObservationResult>emptyList(),
                Collections.singletonList(responseItem),
                2L,
                java.util.Arrays.asList(responseItem, persistedOnlyItem)));

        service.onTerminal(task);

        ArgumentCaptor<VisibilityTaskResultEntity> captor = ArgumentCaptor.forClass(VisibilityTaskResultEntity.class);
        verify(taskResultMapper, times(3)).update(captor.capture(), any());
        assertThat(captor.getAllValues().get(2).getSkippedPairsJson())
                .contains("target-1")
                .contains("target-2")
                .contains("传播失败");
    }

    /** 非成功终态只更新小字段，不尝试写入结果 JSON。 */
    @Test
    @SuppressWarnings("unchecked")
    void skipsResultJsonForFailedTask() {
        VisibilityTaskResultMapper taskResultMapper = mock(VisibilityTaskResultMapper.class);
        when(taskResultMapper.update(any(VisibilityTaskResultEntity.class), any())).thenReturn(1);
        VisibilityTaskResultService service = new VisibilityTaskResultService(taskResultMapper, new ObjectMapper());

        PlanningTask task = new PlanningTask("task-failed");
        task.markFailed("存在未找到的观测卫星");

        service.onTerminal(task);

        ArgumentCaptor<VisibilityTaskResultEntity> captor = ArgumentCaptor.forClass(VisibilityTaskResultEntity.class);
        verify(taskResultMapper, times(1)).update(captor.capture(), any());
        assertThat(captor.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(captor.getValue().getErrorMessage()).isEqualTo("存在未找到的观测卫星");
        assertThat(captor.getValue().getResultJson()).isNull();
    }
}
