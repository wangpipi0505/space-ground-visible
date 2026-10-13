package com.runisys.orbit.spacevisibility.application;

import com.runisys.orbit.spacevisibility.api.SpaceApiModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 管理异步空间目标可见性规划任务的内存任务表。
 * 任务统一提交到独立的单线程调度器串行执行，避免多个全目标遍历规划同时挤占共享计算线程池；
 * 已完成任务按存活时间在访问时惰性清理。
 */
@Service
public class PlanningTaskService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlanningTaskService.class);
    private static final int MAX_FAILURE_REASON_LENGTH = 500;

    private final Executor taskExecutor;
    private final long taskTtlMillis;
    private final Map<String, PlanningTask> tasks = new ConcurrentHashMap<String, PlanningTask>();

    public PlanningTaskService(@Qualifier("planningTaskExecutor") Executor planningTaskExecutor,
                               @Value("${space.visibility.planning-task.ttl-hours:2}") long taskTtlHours) {
        if (taskTtlHours < 1) {
            throw new IllegalArgumentException("规划任务存活时间必须不少于 1 小时");
        }
        this.taskExecutor = planningTaskExecutor;
        this.taskTtlMillis = taskTtlHours * 3_600_000L;
    }

    /** 一次异步可见性规划的实际工作，通过任务对象上报进度并响应取消。 */
    public interface PlanningTaskWork {
        SpaceApiModels.VisibilityPlanResponse execute(PlanningTask task);
    }

    /** 任务进入终态（成功、失败或取消）时的回调，由任务执行线程触发。 */
    public interface PlanningTaskListener {
        void onTerminal(PlanningTask task);
    }

    /**
     * 登记并异步提交一次可见性规划任务。
     *
     * @param work 复用同步规划逻辑的任务体
     * @return 已登记的任务，调用方通过任务标识轮询状态和结果
     */
    public PlanningTask submit(PlanningTaskWork work) {
        return submit(UUID.randomUUID().toString(), work, null);
    }

    /**
     * 登记并异步提交一次可见性规划任务，任务进入终态时通知监听器。
     * 任务标识由调用方生成，便于在任务开始执行前先登记任务的持久化记录。
     *
     * @param taskId 调用方生成的任务标识
     * @param work 复用同步规划逻辑的任务体
     * @param terminalListener 终态回调，可为空；回调异常只记日志，不影响任务状态
     * @return 已登记的任务，调用方通过任务标识轮询状态和结果
     */
    public PlanningTask submit(String taskId, PlanningTaskWork work, PlanningTaskListener terminalListener) {
        cleanupExpiredTasks();
        PlanningTask task = new PlanningTask(taskId);
        tasks.put(task.getTaskId(), task);
        taskExecutor.execute(() -> runTask(task, work, terminalListener));
        LOGGER.info("异步可见性规划任务已提交：taskId={}", task.getTaskId());
        return task;
    }

    /**
     * 按标识查询任务；不存在或已过期清理时返回 null。
     *
     * @param taskId 任务标识
     * @return 任务对象或 null
     */
    public PlanningTask find(String taskId) {
        cleanupExpiredTasks();
        return tasks.get(taskId);
    }

    /**
     * 请求取消任务；不存在时返回 null，已终结的任务保持原状态不变。
     *
     * @param taskId 任务标识
     * @return 取消后的任务对象或 null
     */
    public PlanningTask cancel(String taskId) {
        PlanningTask task = find(taskId);
        if (task != null) {
            task.requestCancellation();
        }
        return task;
    }

    /** 执行任务体并收敛结果、取消和异常三种终态，异常信息可直接展示给调用方。 */
    private void runTask(PlanningTask task, PlanningTaskWork work, PlanningTaskListener terminalListener) {
        if (!task.markRunning()) {
            notifyTerminal(task, terminalListener);
            return;
        }
        try {
            task.markSucceeded(work.execute(task));
            if (task.getStatus() == PlanningTask.Status.CANCELLED) {
                LOGGER.info("异步可见性规划任务已取消：taskId={}", task.getTaskId());
            } else {
                LOGGER.info("异步可见性规划任务完成：taskId={}", task.getTaskId());
            }
        } catch (CancellationException exception) {
            task.markCancelled();
            LOGGER.info("异步可见性规划任务已取消：taskId={}", task.getTaskId());
        } catch (Exception exception) {
            String reason = rootCauseMessage(exception);
            task.markFailed(reason);
            LOGGER.warn("异步可见性规划任务失败：taskId={}，异常类型={}，根因={}",
                    task.getTaskId(), exception.getClass().getSimpleName(), reason);
        }
        notifyTerminal(task, terminalListener);
    }

    /** 任务进入终态后触发回调；回调失败只记日志，不改变任务自身状态。 */
    private void notifyTerminal(PlanningTask task, PlanningTaskListener terminalListener) {
        if (terminalListener == null) {
            return;
        }
        try {
            terminalListener.onTerminal(task);
        } catch (Exception exception) {
            LOGGER.warn("异步可见性规划任务终态回调失败：taskId={}，原因={}", task.getTaskId(), exception.getMessage());
        }
    }

    /** 惰性清理已完成且超过存活时间的任务，避免内存任务表无限增长。 */
    private void cleanupExpiredTasks() {
        Instant expireBefore = Instant.now().minusMillis(taskTtlMillis);
        tasks.values().removeIf(task -> task.getCompletedAt() != null && task.getCompletedAt().isBefore(expireBefore));
    }

    /**
     * 保留异常主消息与逐层根因，避免执行器包装掩盖领域错误；统一限长以适配接口展示和持久化。
     */
    private String rootCauseMessage(Throwable exception) {
        String primaryMessage = nonBlankMessage(exception);
        StringBuilder message = new StringBuilder(primaryMessage == null ? "规划任务执行失败" : primaryMessage);
        Throwable cause = exception.getCause();
        if (cause != null && cause != exception) {
            message.append("；根因：");
            boolean firstCause = true;
            while (cause != null && cause.getCause() != cause) {
                if (!firstCause) {
                    message.append(" <- ");
                }
                message.append(cause.getClass().getSimpleName());
                String causeMessage = nonBlankMessage(cause);
                if (causeMessage != null) {
                    message.append(": ").append(causeMessage);
                }
                firstCause = false;
                cause = cause.getCause();
            }
        }
        return message.length() <= MAX_FAILURE_REASON_LENGTH ? message.toString()
                : message.substring(0, MAX_FAILURE_REASON_LENGTH);
    }

    /** 返回非空异常消息，空消息由调用方使用统一兜底语。 */
    private String nonBlankMessage(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty() ? null : message;
    }
}
