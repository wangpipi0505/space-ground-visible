package com.runisys.orbit.spacevisibility.application;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class VisibilityExecutorConfigurationTest {

    /** 验证队列饱和时由当前同步请求线程直接计算，不继续无界堆积异步组合任务。 */
    @Test
    void runsOverflowTaskInCallingThreadWhenTheBoundedQueueIsFull() throws Exception {
        ExecutorService executor = new VisibilityExecutorConfiguration().visibilityExecutor(1, 1);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch allowWorkerFinish = new CountDownLatch(1);
        try {
            executor.submit(() -> {
                workerStarted.countDown();
                try {
                    allowWorkerFinish.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();
            executor.submit(() -> { });

            Future<String> overflow = executor.submit(() -> Thread.currentThread().getName());

            assertThat(overflow.get(1, TimeUnit.SECONDS)).isEqualTo(Thread.currentThread().getName());
        } finally {
            allowWorkerFinish.countDown();
            executor.shutdownNow();
        }
    }
}
