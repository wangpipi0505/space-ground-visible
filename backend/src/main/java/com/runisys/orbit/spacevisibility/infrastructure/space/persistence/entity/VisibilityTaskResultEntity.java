package com.runisys.orbit.spacevisibility.infrastructure.space.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 异步可见性规划任务的持久化结果记录。
 * 任务提交时写入请求原文与待处理状态，进入终态时回填状态、进度、结果与完成时刻。
 */
@TableName("ZG_VISIBILITY_TASK_RESULT")
public class VisibilityTaskResultEntity {

    @TableId(value = "ID_", type = IdType.ASSIGN_ID)
    private Long id;
    @TableField("TASK_ID_") private String taskId;
    @TableField("TASK_TYPE_") private String taskType;
    @TableField("STATUS_") private String status;
    @TableField("TOTAL_PAIRS_") private Long totalPairs;
    @TableField("COMPLETED_PAIRS_") private Long completedPairs;
    @TableField("ERROR_MESSAGE_") private String errorMessage;
    @TableField("REQUEST_JSON_") private String requestJson;
    @TableField("RESULT_JSON_") private String resultJson;
    @TableField("SKIPPED_PAIRS_") private String skippedPairsJson;
    @TableField("REMARK_") private String remark;
    @TableField("SELECTION_JSON_") private String selectionJson;
    @TableField("CREATE_TIME_") private LocalDateTime createTime;
    @TableField("COMPLETE_TIME_") private LocalDateTime completeTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getTotalPairs() { return totalPairs; }
    public void setTotalPairs(Long totalPairs) { this.totalPairs = totalPairs; }
    public Long getCompletedPairs() { return completedPairs; }
    public void setCompletedPairs(Long completedPairs) { this.completedPairs = completedPairs; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getRequestJson() { return requestJson; }
    public void setRequestJson(String requestJson) { this.requestJson = requestJson; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public String getSkippedPairsJson() { return skippedPairsJson; }
    public void setSkippedPairsJson(String skippedPairsJson) { this.skippedPairsJson = skippedPairsJson; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getSelectionJson() { return selectionJson; }
    public void setSelectionJson(String selectionJson) { this.selectionJson = selectionJson; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getCompleteTime() { return completeTime; }
    public void setCompleteTime(LocalDateTime completeTime) { this.completeTime = completeTime; }
}
