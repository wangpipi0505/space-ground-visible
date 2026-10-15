package com.runisys.orbit.spacevisibility.api.space;

import com.runisys.orbit.spacevisibility.application.space.VisibilityTaskResultService;
import com.runisys.orbit.spacevisibility.application.space.contract.SpacePlanningModels;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 异步可见性规划任务持久化结果记录的查询维护接口。
 * 记录由任务提交与终态回调自动写入，本接口提供分页查询、明细读取、备注更新和删除。
 */
@RestController
@RequestMapping("/api/v1/space/visibility/task-results")
public class VisibilityTaskResultController {

    private final VisibilityTaskResultService visibilityTaskResultService;

    public VisibilityTaskResultController(VisibilityTaskResultService visibilityTaskResultService) {
        this.visibilityTaskResultService = visibilityTaskResultService;
    }

    /**
     * 分页查询任务结果记录，不包含请求与结果 JSON 大字段，按提交时刻倒序。
     *
     * @param pageNumber 页码，从 1 开始
     * @param pageSize 每页记录数，最大为 200
     * @param status 任务状态筛选，可为空
     * @param keyword 匹配任务标识或备注的关键字，可为空
     * @return 任务结果记录分页结果
     */
    @GetMapping
    public SpacePlanningModels.PageResponse<SpacePlanningModels.VisibilityTaskResultSummaryResponse> page(
            @RequestParam(defaultValue = "1") long pageNumber,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return visibilityTaskResultService.page(pageNumber, pageSize, status, keyword);
    }

    /**
     * 读取单条任务结果记录全字段，包含请求与结果 JSON 原文。
     *
     * @param id 记录主键
     * @return 记录明细；记录不存在时返回 404
     */
    @GetMapping("/{id}")
    public ResponseEntity<SpacePlanningModels.VisibilityTaskResultDetailResponse> detail(@PathVariable Long id) {
        SpacePlanningModels.VisibilityTaskResultDetailResponse detail = visibilityTaskResultService.findDetail(id);
        if (detail == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(detail);
    }

    /**
     * 更新任务结果记录的用户备注；传入空值表示清除备注。
     *
     * @param id 记录主键
     * @param request 备注内容
     * @return 更新后的记录明细；记录不存在时返回 404
     */
    @PutMapping("/{id}")
    public ResponseEntity<SpacePlanningModels.VisibilityTaskResultDetailResponse> updateRemark(
            @PathVariable Long id,
            @RequestBody SpacePlanningModels.VisibilityTaskResultRemarkRequest request) {
        SpacePlanningModels.VisibilityTaskResultDetailResponse updated =
                visibilityTaskResultService.updateRemark(id, request.getRemark());
        if (updated == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(updated);
    }

    /**
     * 保存“确认选择”:用户在观测结果列表勾选目标后提交的明细与合并完整时段。
     * 请求体原文存入记录的 SELECTION_JSON_,以最后一次提交为准。
     *
     * @param id 任务结果记录主键
     * @param request 确认选择请求体
     * @return 回执与更新后明细;记录不存在时返回 404
     */
    @PutMapping("/{id}/selection")
    public ResponseEntity<SpacePlanningModels.VisibilityTaskResultSelectionResponse> updateSelection(
            @PathVariable Long id,
            @RequestBody SpacePlanningModels.VisibilityTaskResultSelectionRequest request) {
        SpacePlanningModels.VisibilityTaskResultSelectionResponse response =
                visibilityTaskResultService.updateSelection(id, request);
        if (response == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(response);
    }

    /**
     * 删除任务结果记录。
     *
     * @param id 记录主键
     * @return 删除成功返回 200；记录不存在时返回 404
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!visibilityTaskResultService.delete(id)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().build();
    }
}
