package com.runisys.orbit.spacevisibility.application.space;

import com.runisys.orbit.spacevisibility.domain.space.visibility.VisibilityObservationResult;

import com.runisys.orbit.spacevisibility.application.space.contract.SpacePlanningModels;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PlanningTaskServiceTest {

    /** 验证任务提交后异步执行成功，结果、进度和完成时刻齐全。 */
    @Test
    void runsSubmittedTaskToSuccess() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            PlanningTaskService service = new PlanningTaskService(executor, 1);
            PlanningTask task = service.submit(running -> {
                running.reportProgress(1L, 1L);
                return new SpacePlanningModels.VisibilityPlanResponse(
                        Collections.<VisibilityObservationResult>emptyList());
            });

            awaitTerminal(task);

            assertThat(task.getStatus()).isEqualTo(PlanningTask.Status.SUCCEEDED);
            assertThat(task.getCompletedPairs()).isEqualTo(1L);
            assertThat(task.getTotalPairs()).isEqualTo(1L);
            assertThat(task.getResult()).isNotNull();
            assertThat(task.getCompletedAt()).isNotNull();
            assertThat(service.find(task.getTaskId())).isSameAs(task);
        } finally {
            executor.shutdownNow();
        }
    }

    /** 验证任务体抛出的业务异常转为失败终态，错误信息可直接展示。 */
    @Test
    void marksTaskFailedWithBusinessErrorMessage() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            PlanningTaskService service = new PlanningTaskService(executor, 1);
            PlanningTask task = service.submit(running -> {
                throw new IllegalArgumentException("请至少选择一颗观测卫星");
            });

            awaitTerminal(task);

            assertThat(task.getStatus()).isEqualTo(PlanningTask.Status.FAILED);
            assertThat(task.getErrorMessage()).isEqualTo("请至少选择一颗观测卫星");
            assertThat(service.find(task.getTaskId()).getErrorMessage()).isEqualTo("请至少选择一颗观测卫星");
        } finally {
            executor.shutdownNow();
        }
    }

    /** 线程池或领域层包装异常时，任务状态应同时保留主消息和可定位的根因链。 */
    @Test
    void marksTaskFailedWithRootCauseMessage() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            PlanningTaskService service = new PlanningTaskService(executor, 1);
            PlanningTask task = service.submit(running -> {
                throw new RuntimeException("任务包装异常", new IllegalArgumentException("目标 TLE 已超出有效期"));
            });

            awaitTerminal(task);

            assertThat(task.getStatus()).isEqualTo(PlanningTask.Status.FAILED);
            assertThat(task.getErrorMessage()).isEqualTo("任务包装异常；根因：IllegalArgumentException: 目标 TLE 已超出有效期");
        } finally {
            executor.shutdownNow();
        }
    }

    /** 验证排队中的任务可在执行前直接取消，不再进入运行状态。 */
    @Test
    void cancelsPendingTaskBeforeItStarts() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch release = new CountDownLatch(1);
        try {
            PlanningTaskService service = new PlanningTaskService(executor, 1);
            service.submit(running -> {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return new SpacePlanningModels.VisibilityPlanResponse(
                        Collections.<VisibilityObservationResult>emptyList());
            });
            PlanningTask pending = service.submit(running -> new SpacePlanningModels.VisibilityPlanResponse(
                    Collections.<VisibilityObservationResult>emptyList()));

            service.cancel(pending.getTaskId());
            release.countDown();

            assertThat(pending.getStatus()).isEqualTo(PlanningTask.Status.CANCELLED);
            assertThat(pending.getCompletedAt()).isNotNull();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    /** 验证执行中的任务响应取消标志并进入已取消终态。 */
    @Test
    void cancelsRunningTaskThroughCancellationFlag() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            PlanningTaskService service = new PlanningTaskService(executor, 1);
            PlanningTask task = service.submit(running -> {
                while (!running.isCancellationRequested()) {
                    Thread.yield();
                }
                throw new CancellationException("可见性规划任务已取消");
            });
            service.cancel(task.getTaskId());

            awaitTerminal(task);

            assertThat(task.getStatus()).isEqualTo(PlanningTask.Status.CANCELLED);
        } finally {
            executor.shutdownNow();
        }
    }

    /** 最后一批已产出结果但尚未返回时接受取消，任务体即使不再检查标志也不能发布成功。 */
    @Test
    void cancelsCompletedWorkBeforePublishingResult() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch workCompleted = new CountDownLatch(1);
        CountDownLatch allowReturn = new CountDownLatch(1);
        CountDownLatch notified = new CountDownLatch(1);
        try {
            PlanningTaskService service = new PlanningTaskService(executor, 1);
            PlanningTask task = service.submit("T27-final-batch", running -> {
                running.reportProgress(1L, 1L);
                workCompleted.countDown();
                try {
                    assertThat(allowReturn.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return new SpacePlanningModels.VisibilityPlanResponse(Collections.<VisibilityObservationResult>emptyList());
            }, terminal -> notified.countDown());
            assertThat(workCompleted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(service.cancel(task.getTaskId()).getStatus()).isEqualTo(PlanningTask.Status.RUNNING);
            allowReturn.countDown();
            assertThat(notified.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(task.getStatus()).isEqualTo(PlanningTask.Status.CANCELLED);
            assertThat(task.getResult()).isNull();
            assertThat(task.getCompletedAt()).isNotNull();
        } finally {
            allowReturn.countDown();
            executor.shutdownNow();
        }
    }

    /** 成功已发布后取消必须保持成功与原结果；不能把终态改写为已取消。 */
    @Test
    void keepsSuccessfulTerminalStateWhenCancelledAfterPublication() {
        PlanningTaskService service = new PlanningTaskService(Runnable::run, 1);
        SpacePlanningModels.VisibilityPlanResponse result = new SpacePlanningModels.VisibilityPlanResponse(
                Collections.<VisibilityObservationResult>emptyList());
        PlanningTask task = service.submit(running -> result);
        service.cancel(task.getTaskId());
        assertThat(task.getStatus()).isEqualTo(PlanningTask.Status.SUCCEEDED);
        assertThat(task.getResult()).isSameAs(result);
        assertThat(task.isCancellationRequested()).isFalse();
    }

    /** 普通业务异常仍保留原FAILED/错误根因；受理取消不能把真实领域故障伪装成正常取消退出。 */
    @Test
    void retainsOriginalFailureWhenCancelledWorkThrowsBusinessError() {
        PlanningTaskService service = new PlanningTaskService(Runnable::run, 1);
        PlanningTask task = service.submit(running -> {
            running.requestCancellation();
            throw new IllegalArgumentException("取消期间真实轨道数据错误");
        });
        assertThat(task.getStatus()).isEqualTo(PlanningTask.Status.FAILED);
        assertThat(task.getErrorMessage()).isEqualTo("取消期间真实轨道数据错误");
    }

    /** 验证查询和取消不存在的任务返回空。 */
    @Test
    void returnsNullForUnknownTaskId() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            PlanningTaskService service = new PlanningTaskService(executor, 1);

            assertThat(service.find("missing")).isNull();
            assertThat(service.cancel("missing")).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    /** 轮询等待任务进入终态，避免异步执行时序导致的偶发失败。 */
    private void awaitTerminal(PlanningTask task) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            PlanningTask.Status status = task.getStatus();
            if (status == PlanningTask.Status.SUCCEEDED || status == PlanningTask.Status.FAILED
                    || status == PlanningTask.Status.CANCELLED) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("任务未在预期时间内结束，当前状态: " + task.getStatus());
    }
}
