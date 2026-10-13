package com.runisys.orbit.spacevisibility.application;

import com.runisys.orbit.spacevisibility.api.SpaceApiModels;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 表示一次异步空间目标可见性规划任务。
 * 进度和状态字段使用 volatile 保证执行线程与查询线程间的可见性，结果仅在任务成功后写入。
 */
public final class PlanningTask {

    public enum Status {
        PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED
    }

    private final String taskId;
    private final Instant createdAt;
    private final AtomicBoolean cancellationRequested = new AtomicBoolean();
    private volatile Status status;
    private volatile long totalPairs;
    private volatile long completedPairs;
    /** 进度按筛选后的目标数量展示：配对数 = 目标数 × 观测星数。 */
    private volatile long totalTargets;
    private volatile long completedTargets;
    private volatile SpaceApiModels.VisibilityPlanResponse result;
    private volatile String errorMessage;
    private volatile Instant completedAt;

    PlanningTask(String taskId) {
        this.taskId = taskId;
        this.createdAt = Instant.now();
        this.status = Status.PENDING;
    }

    public String getTaskId() { return taskId; }
    public Status getStatus() { return status; }
    public long getTotalPairs() { return totalPairs; }
    public long getCompletedPairs() { return completedPairs; }
    public long getTotalTargets() { return totalTargets; }
    public long getCompletedTargets() { return completedTargets; }
    public SpaceApiModels.VisibilityPlanResponse getResult() { return result; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }

    /**
     * 请求取消任务。排队中的任务直接置为已取消，执行中的任务由规划循环在批次边界检查取消标志后退出。
     */
    public void requestCancellation() {
        cancellationRequested.set(true);
        if (status == Status.PENDING) {
            status = Status.CANCELLED;
            completedAt = Instant.now();
        }
    }

    /** @return 规划循环在批次边界查询的取消标志。 */
    public boolean isCancellationRequested() {
        return cancellationRequested.get();
    }

    /** 由规划循环在批次收集点上报进度。 */
    public void reportProgress(long completedPairs, long totalPairs) {
        this.completedPairs = completedPairs;
        this.totalPairs = totalPairs;
    }

    /** 上报按目标数量折算的进度（配对数 ÷ 观测星数）。 */
    public void reportTargetProgress(long completedTargets, long totalTargets) {
        this.completedTargets = completedTargets;
        this.totalTargets = totalTargets;
    }

    /** 尝试开始执行；已在排队期间被取消的任务不再进入运行状态。 */
    boolean markRunning() {
        if (cancellationRequested.get()) {
            status = Status.CANCELLED;
            completedAt = Instant.now();
            return false;
        }
        status = Status.RUNNING;
        return true;
    }

    void markSucceeded(SpaceApiModels.VisibilityPlanResponse planResult) {
        this.result = planResult;
        this.status = Status.SUCCEEDED;
        this.completedAt = Instant.now();
    }

    void markFailed(String message) {
        this.errorMessage = message;
        this.status = Status.FAILED;
        this.completedAt = Instant.now();
    }

    void markCancelled() {
        this.status = Status.CANCELLED;
        this.completedAt = Instant.now();
    }
}
