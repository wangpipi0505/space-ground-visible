package com.runisys.orbit.spacevisibility.api;

import com.runisys.orbit.spacevisibility.application.PlanningFacadeService;
import com.runisys.orbit.spacevisibility.application.PlanningTask;
import com.runisys.orbit.spacevisibility.application.PlanningTaskService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 异步空间目标可见性规划任务接口。
 * 全目标遍历规划耗时较长，调用方提交任务后轮询状态，成功后再获取规划结果。
 */
@RestController
@RequestMapping("/api/v1/space/visibility/tasks")
public class PlanningTaskController {

    private final PlanningFacadeService planningFacadeService;
    private final PlanningTaskService planningTaskService;

    public PlanningTaskController(PlanningFacadeService planningFacadeService,
                                  PlanningTaskService planningTaskService) {
        this.planningFacadeService = planningFacadeService;
        this.planningTaskService = planningTaskService;
    }

    /**
     * 提交一次异步可见性规划任务，请求体与同步规划接口完全一致。
     *
     * @param request 观测星、目标、时间段、粗精选步长与光照组合临时条件
     * @return 新任务标识
     */
    @PostMapping
    public Map<String, String> submitTask(@RequestBody SpaceApiModels.VisibilityPlanRequest request) {
        PlanningTask task = planningFacadeService.submitVisibilityTask(request);
        return Collections.singletonMap("taskId", task.getTaskId());
    }

    /**
     * 查询任务状态与批次进度，不包含规划结果明细。
     *
     * @param taskId 任务标识
     * @return 任务状态；任务不存在时返回 404
     */
    @GetMapping("/{taskId}")
    public ResponseEntity<SpaceApiModels.PlanningTaskStatusResponse> taskStatus(@PathVariable String taskId) {
        PlanningTask task = planningTaskService.find(taskId);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toStatusResponse(task));
    }

    /**
     * 获取已完成任务的规划结果；任务未完成时返回 409 及当前状态说明。
     *
     * @param taskId 任务标识
     * @return 与同步规划接口一致的规划结果
     */
    @GetMapping("/{taskId}/result")
    public ResponseEntity<?> taskResult(@PathVariable String taskId) {
        PlanningTask task = planningTaskService.find(taskId);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        if (task.getStatus() != PlanningTask.Status.SUCCEEDED) {
            Map<String, String> body = new LinkedHashMap<String, String>();
            body.put("error", "规划任务尚未成功完成，当前状态: " + task.getStatus());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
        return ResponseEntity.ok(task.getResult());
    }

    /**
     * 取消任务。排队中的任务直接终结，执行中的任务在批次边界退出。
     *
     * @param taskId 任务标识
     * @return 当前真实状态；200/RUNNING仅表示已受理取消，需继续轮询至CANCELLED；已成功任务保留结果，未知任务404
     */
    @DeleteMapping("/{taskId}")
    public ResponseEntity<SpaceApiModels.PlanningTaskStatusResponse> cancelTask(@PathVariable String taskId) {
        PlanningTask task = planningTaskService.cancel(taskId);
        if (task == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toStatusResponse(task));
    }

    /** 将任务对象转换为对外状态响应，不携带规划结果明细。 */
    private SpaceApiModels.PlanningTaskStatusResponse toStatusResponse(PlanningTask task) {
        return new SpaceApiModels.PlanningTaskStatusResponse(task.getTaskId(), task.getStatus().name(),
                task.getTotalPairs(), task.getCompletedPairs(), task.getTotalTargets(), task.getCompletedTargets(),
                task.getErrorMessage(), task.getCreatedAt(), task.getCompletedAt());
    }
}
