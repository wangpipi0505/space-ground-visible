package com.runisys.orbit.spacevisibility.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runisys.orbit.spacevisibility.api.SpaceApiModels;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.VisibilityTaskResultEntity;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.mapper.VisibilityTaskResultMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 持久化异步可见性规划任务的提交记录与终态结果，并提供任务结果记录的查询维护能力。
 * 任务提交时写入请求原文与待处理状态，任务进入终态时回填状态、进度、错误信息、
 * 规划结果（仅成功）与完成时刻；运行中的进度仍以内存任务表为准，不落库。
 * 持久化失败只记录日志，不影响任务本身的执行与状态查询。
 * 终态回填分两步：先更新状态等小字段（保证任务状态绝不滞留），成功任务再单独写入结果 JSON；
 * 结果 JSON 过大超出数据库写入限制（如 MySQL max_allowed_packet）或序列化失败时，
 * 降级为在 ERROR_MESSAGE_ 中记录说明文字，任务状态保持 SUCCEEDED。
 */
@Service
public class VisibilityTaskResultService implements PlanningTaskService.PlanningTaskListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(VisibilityTaskResultService.class);

    private static final long MAX_PAGE_SIZE = 200L;
    private static final int MAX_ERROR_MESSAGE_LENGTH = 1024;
    private static final int MAX_REMARK_LENGTH = 512;
    private static final String TASK_TYPE_VISIBILITY = "VISIBILITY";

    private final VisibilityTaskResultMapper taskResultMapper;
    private final ObjectMapper objectMapper;

    public VisibilityTaskResultService(VisibilityTaskResultMapper taskResultMapper, ObjectMapper objectMapper) {
        this.taskResultMapper = taskResultMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 任务提交时写入待处理记录，包含任务标识与请求原文。
     *
     * @param taskId 异步任务标识
     * @param request 提交时的可见性规划请求
     */
    public void recordSubmitted(String taskId, SpaceApiModels.VisibilityPlanRequest request) {
        try {
            VisibilityTaskResultEntity entity = new VisibilityTaskResultEntity();
            entity.setTaskId(taskId);
            entity.setTaskType(TASK_TYPE_VISIBILITY);
            entity.setStatus(PlanningTask.Status.PENDING.name());
            entity.setRequestJson(objectMapper.writeValueAsString(request));
            entity.setCreateTime(LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
            taskResultMapper.insert(entity);
        } catch (Exception exception) {
            LOGGER.warn("持久化可见性规划任务提交记录失败：taskId={}，原因={}", taskId, exception.getMessage());
        }
    }

    /**
     * 任务进入终态时分两步回填：先更新状态、进度、错误信息与完成时刻等小字段，
     * 成功任务再单独写入规划结果 JSON，避免大结果写入失败时状态信息一并丢失。
     *
     * @param task 已进入终态的任务对象
     */
    @Override
    public void onTerminal(PlanningTask task) {
        updateTerminalState(task);
        if (task.getStatus() == PlanningTask.Status.SUCCEEDED) {
            persistResultJson(task);
            persistSkippedPairs(task);
        }
    }

    /** 第一步：只更新状态、进度、错误与完成时刻等小字段，保证任务状态绝不滞留在 PENDING。 */
    private void updateTerminalState(PlanningTask task) {
        try {
            VisibilityTaskResultEntity entity = new VisibilityTaskResultEntity();
            entity.setStatus(task.getStatus().name());
            entity.setTotalPairs(task.getTotalPairs());
            entity.setCompletedPairs(task.getCompletedPairs());
            entity.setErrorMessage(truncate(task.getErrorMessage(), MAX_ERROR_MESSAGE_LENGTH));
            entity.setCompleteTime(task.getCompletedAt() == null ? null
                    : LocalDateTime.ofInstant(task.getCompletedAt(), ZoneOffset.UTC));
            int updated = taskResultMapper.update(entity, new LambdaUpdateWrapper<VisibilityTaskResultEntity>()
                    .eq(VisibilityTaskResultEntity::getTaskId, task.getTaskId()));
            if (updated == 0) {
                LOGGER.warn("持久化可见性规划任务终态时未找到提交记录：taskId={}", task.getTaskId());
            }
        } catch (Exception exception) {
            LOGGER.warn("持久化可见性规划任务终态失败：taskId={}，原因={}", task.getTaskId(), exception.getMessage());
        }
    }

    /**
     * 第二步：成功任务单独写入规划结果 JSON 原文。
     * 结果过大超出数据库写入限制或序列化失败时，降级为在错误信息列写入说明文字。
     */
    private void persistResultJson(PlanningTask task) {
        String resultJson;
        try {
            resultJson = objectMapper.writeValueAsString(task.getResult());
        } catch (Exception exception) {
            LOGGER.warn("序列化可见性规划任务结果失败：taskId={}，原因={}", task.getTaskId(), exception.getMessage());
            markResultNotPersisted(task.getTaskId(), "任务计算成功，但结果 JSON 序列化失败，未持久化；请重新提交规划获取结果");
            return;
        }
        try {
            VisibilityTaskResultEntity entity = new VisibilityTaskResultEntity();
            entity.setResultJson(resultJson);
            int updated = taskResultMapper.update(entity, new LambdaUpdateWrapper<VisibilityTaskResultEntity>()
                    .eq(VisibilityTaskResultEntity::getTaskId, task.getTaskId()));
            if (updated == 0) {
                LOGGER.warn("持久化可见性规划任务结果时未找到提交记录：taskId={}", task.getTaskId());
            }
        } catch (Exception exception) {
            LOGGER.warn("持久化可见性规划任务结果 JSON 失败：taskId={}，结果字符数={}，原因={}",
                    task.getTaskId(), resultJson.length(), exception.getMessage());
            markResultNotPersisted(task.getTaskId(), "任务计算成功，但结果 JSON（" + resultJson.length()
                    + " 字符）超出数据库写入限制，未持久化；请重新提交规划获取结果");
        }
    }

    /**
     * 将接口展示上限之外的跳过组合一并持久化。该字段独立写入，确保结果 JSON 因数据库包大小
     * 无法落库时，仍可在任务明细中追溯完整的坏数据组合。
     */
    private void persistSkippedPairs(PlanningTask task) {
        if (task.getResult() == null || task.getResult().getAllSkippedPairs() == null
                || task.getResult().getAllSkippedPairs().isEmpty()) {
            return;
        }
        String skippedPairsJson;
        try {
            skippedPairsJson = objectMapper.writeValueAsString(task.getResult().getAllSkippedPairs());
        } catch (Exception exception) {
            LOGGER.warn("序列化可见性规划任务跳过组合失败：taskId={}，原因={}",
                    task.getTaskId(), exception.getMessage());
            return;
        }
        try {
            VisibilityTaskResultEntity entity = new VisibilityTaskResultEntity();
            entity.setSkippedPairsJson(skippedPairsJson);
            int updated = taskResultMapper.update(entity, new LambdaUpdateWrapper<VisibilityTaskResultEntity>()
                    .eq(VisibilityTaskResultEntity::getTaskId, task.getTaskId()));
            if (updated == 0) {
                LOGGER.warn("持久化可见性规划任务跳过组合时未找到提交记录：taskId={}", task.getTaskId());
            }
        } catch (Exception exception) {
            LOGGER.warn("持久化可见性规划任务跳过组合失败：taskId={}，跳过组合数={}，原因={}",
                    task.getTaskId(), task.getResult().getAllSkippedPairs().size(), exception.getMessage());
        }
    }

    /** 结果 JSON 无法持久化时，用小字段更新把降级说明写入错误信息列；该兜底写入失败只记日志。 */
    private void markResultNotPersisted(String taskId, String message) {
        try {
            VisibilityTaskResultEntity entity = new VisibilityTaskResultEntity();
            entity.setErrorMessage(truncate(message, MAX_ERROR_MESSAGE_LENGTH));
            taskResultMapper.update(entity, new LambdaUpdateWrapper<VisibilityTaskResultEntity>()
                    .eq(VisibilityTaskResultEntity::getTaskId, taskId));
        } catch (Exception exception) {
            LOGGER.warn("写入可见性规划任务结果降级说明失败：taskId={}，原因={}", taskId, exception.getMessage());
        }
    }

    /**
     * 分页查询任务结果记录，不包含请求与结果 JSON 大字段，按提交时刻倒序。
     *
     * @param pageNumber 页码，从 1 开始
     * @param pageSize 每页记录数，最大为 200
     * @param status 任务状态筛选（PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED），可为空
     * @param keyword 匹配任务标识或备注的关键字，可为空
     * @return 任务结果记录分页结果
     */
    public SpaceApiModels.PageResponse<SpaceApiModels.VisibilityTaskResultSummaryResponse> page(long pageNumber,
                                                                                                long pageSize,
                                                                                                String status,
                                                                                                String keyword) {
        Page<VisibilityTaskResultEntity> page = new Page<VisibilityTaskResultEntity>(
                validatePageNumber(pageNumber), validatePageSize(pageSize));
        LambdaQueryWrapper<VisibilityTaskResultEntity> query = new LambdaQueryWrapper<VisibilityTaskResultEntity>()
                .select(VisibilityTaskResultEntity::getId, VisibilityTaskResultEntity::getTaskId,
                        VisibilityTaskResultEntity::getTaskType, VisibilityTaskResultEntity::getStatus,
                        VisibilityTaskResultEntity::getTotalPairs, VisibilityTaskResultEntity::getCompletedPairs,
                        VisibilityTaskResultEntity::getErrorMessage, VisibilityTaskResultEntity::getRemark,
                        VisibilityTaskResultEntity::getCreateTime, VisibilityTaskResultEntity::getCompleteTime)
                .orderByDesc(VisibilityTaskResultEntity::getCreateTime)
                .orderByDesc(VisibilityTaskResultEntity::getId);
        if (status != null && !status.trim().isEmpty()) {
            query.eq(VisibilityTaskResultEntity::getStatus, parseStatus(status));
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            String normalizedKeyword = keyword.trim();
            query.and(condition -> condition.like(VisibilityTaskResultEntity::getTaskId, normalizedKeyword)
                    .or().like(VisibilityTaskResultEntity::getRemark, normalizedKeyword));
        }
        taskResultMapper.selectPage(page, query);
        List<SpaceApiModels.VisibilityTaskResultSummaryResponse> records = page.getRecords().stream()
                .map(SpaceApiModels.VisibilityTaskResultSummaryResponse::new)
                .collect(Collectors.toList());
        return new SpaceApiModels.PageResponse<SpaceApiModels.VisibilityTaskResultSummaryResponse>(
                page.getTotal(), page.getCurrent(), page.getSize(), records);
    }

    /**
     * 按主键读取任务结果记录全字段，包含请求与结果 JSON 原文。
     *
     * @param id 记录主键
     * @return 记录明细；记录不存在时返回 null
     */
    public SpaceApiModels.VisibilityTaskResultDetailResponse findDetail(Long id) {
        VisibilityTaskResultEntity entity = taskResultMapper.selectById(id);
        return entity == null ? null : new SpaceApiModels.VisibilityTaskResultDetailResponse(entity);
    }

    /**
     * 更新任务结果记录的用户备注；传入空值表示清除备注。
     *
     * @param id 记录主键
     * @param remark 备注内容，最长 512 字符，可为空
     * @return 更新后的记录明细；记录不存在时返回 null
     */
    public SpaceApiModels.VisibilityTaskResultDetailResponse updateRemark(Long id, String remark) {
        if (remark != null && remark.length() > MAX_REMARK_LENGTH) {
            throw new IllegalArgumentException("备注长度不能超过 512 字符");
        }
        VisibilityTaskResultEntity entity = new VisibilityTaskResultEntity();
        entity.setId(id);
        entity.setRemark(remark);
        if (taskResultMapper.updateById(entity) == 0) {
            return null;
        }
        return findDetail(id);
    }

    /**
     * 保存“确认选择”明细:请求体原序列化入 SELECTION_JSON_,以最后一次提交为准。
     *
     * @param id 任务结果记录主键
     * @param request 确认选择请求(勾选目标明细 + 合并完整时段)
     * @return 回执(记录标识、目标数、合并段数)与更新后明细;记录不存在时返回 null
     */
    public SpaceApiModels.VisibilityTaskResultSelectionResponse updateSelection(
            Long id, SpaceApiModels.VisibilityTaskResultSelectionRequest request) {
        if (request == null || request.getSelections() == null || !request.getSelections().isArray()
                || request.getSelections().size() == 0) {
            throw new IllegalArgumentException("确认选择内容无效:selections 不能为空");
        }
        VisibilityTaskResultEntity entity = taskResultMapper.selectById(id);
        if (entity == null) {
            return null;
        }
        String selectionJson;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            selectionJson = mapper.writeValueAsString(request);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("确认选择内容序列化失败");
        }
        VisibilityTaskResultEntity update = new VisibilityTaskResultEntity();
        update.setId(id);
        update.setSelectionJson(selectionJson);
        if (taskResultMapper.updateById(update) == 0) {
            return null;
        }
        if (request.getRemark() != null) {
            entity.setRemark(request.getRemark());
        }
        int selectionCount = request.getSelections().size();
        int mergedSegments = 0;
        com.fasterxml.jackson.databind.JsonNode range = request.getMergedTimeRange();
        if (range != null && range.has("segments") && range.get("segments").isArray()) {
            mergedSegments = range.get("segments").size();
        }
        SpaceApiModels.VisibilityTaskResultDetailResponse detail = findDetail(id);
        return new SpaceApiModels.VisibilityTaskResultSelectionResponse(String.valueOf(id), true,
                selectionCount, mergedSegments, "确认选择已保存", detail);
    }

    /**
     * 删除任务结果记录。
     *
     * @param id 记录主键
     * @return 是否删除了记录；记录不存在时返回 false
     */
    public boolean delete(Long id) {
        return taskResultMapper.deleteById(id) > 0;
    }

    /** 校验状态筛选值，非法状态直接提示调用方。 */
    private String parseStatus(String status) {
        try {
            return PlanningTask.Status.valueOf(status.trim()).name();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("任务状态筛选无效");
        }
    }

    /** 校验页码，避免无效页码导致数据库产生不可预期的偏移量。 */
    private long validatePageNumber(long pageNumber) {
        if (pageNumber < 1L) {
            throw new IllegalArgumentException("页码必须大于 0");
        }
        return pageNumber;
    }

    /** 校验并限制单页记录数，避免单次请求读取过多记录。 */
    private long validatePageSize(long pageSize) {
        if (pageSize < 1L || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("每页记录数必须在 1 到 200 之间");
        }
        return pageSize;
    }

    /** 按列长度截断可空文本，避免超长内容写入失败。 */
    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
