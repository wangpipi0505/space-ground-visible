package com.runisys.orbit.spacevisibility.application.space;

import com.runisys.orbit.spacevisibility.application.space.contract.SpacePlanningModels;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 表示一次异步空间目标可见性规划任务。
 * 进度和状态字段使用 volatile 保证执行线程与查询线程间的可见性；取消受理与终态发布在同一任务锁内。
 * 已受理的运行中取消可能等待当前批次退出，但即使最后批次不再检查标志，也不能发布成功结果。
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
    private volatile SpacePlanningModels.VisibilityPlanResponse result;
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
    public SpacePlanningModels.VisibilityPlanResponse getResult() { return result; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }

    /**
     * 请求取消任务。排队中的任务直接取消；运行中的任务仍显示RUNNING，直到当前批次实际退出。
     * 共用终态发布锁：取消先受理且正常返回时收敛CANCELLED，普通异常仍FAILED；已发布终态保持原状态/结果。
     */
    public synchronized void requestCancellation() {
        if (isTerminal()) {
            return;
        }
        cancellationRequested.set(true);
        if (status == Status.PENDING) {
            markCancelled();
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
    synchronized boolean markRunning() {
        if (isTerminal()) {
            return false;
        }
        if (cancellationRequested.get()) {
            markCancelled();
            return false;
        }
        status = Status.RUNNING;
        return true;
    }

    /** 在同一短任务锁内裁决取消与成功；锁不覆盖数值计算或持久化监听器。 */
    synchronized void markSucceeded(SpacePlanningModels.VisibilityPlanResponse planResult) {
        if (isTerminal()) {
            return;
        }
        if (cancellationRequested.get()) {
            markCancelled();
            return;
        }
        this.result = planResult;
        this.completedAt = Instant.now();
        this.status = Status.SUCCEEDED;
    }

    /** 普通业务异常仍发布原FAILED及根因，只有CancellationException由服务明确发布取消终态。 */
    synchronized void markFailed(String message) {
        if (isTerminal()) {
            return;
        }
        this.errorMessage = message;
        this.completedAt = Instant.now();
        this.status = Status.FAILED;
    }

    /** 实际任务退出后发布取消终态；已发布的任一终态均不得改写。 */
    synchronized void markCancelled() {
        if (isTerminal()) {
            return;
        }
        this.completedAt = Instant.now();
        this.status = Status.CANCELLED;
    }

    /** 调用方均持有任务锁，保证取消标志与终态判断不会在成功发布间隙发生交错。 */
    private boolean isTerminal() {
        return status == Status.SUCCEEDED || status == Status.FAILED || status == Status.CANCELLED;
    }
}
