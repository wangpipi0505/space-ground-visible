package com.runisys.orbit.spacevisibility.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Spring注入的空间计算线程池及规划服务配置；worker/queue/batch/maxPairTasks分别限定资源。
 * 原容量与拒绝策略保持，服务关闭由容器管理；该执行器不等同于MVC流式响应执行器。
 */
@Configuration
public class VisibilityExecutorConfiguration {

    /**
     * 创建所有可见性和下传组合计算共享的固定大小线程池，应用关闭时自动等待资源释放。
     *
     * @param workerThreads 可并行执行的计算线程数
     * @return 带业务线程名前缀的计算线程池
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService visibilityExecutor(@Value("${space.visibility.calculation.worker-threads}") int workerThreads,
                                              @Value("${space.visibility.calculation.work-queue-capacity:16}") int workQueueCapacity) {
        if (workerThreads < 1 || workQueueCapacity < 1) {
            throw new IllegalArgumentException("可见性计算线程数和工作队列容量必须大于 0");
        }
        return new ThreadPoolExecutor(workerThreads, workerThreads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(workQueueCapacity), new VisibilityThreadFactory(),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * 装配空间目标可见性规划服务，并注入公共计算线程池及单次组合数上限。
     *
     * @param visibilityExecutor 可见性计算线程池
     * @param maxPairTasks 单次请求允许的最大观测星—目标组合数
     * @return 可见性规划服务
     */
    @Bean
    public VisibilityPlanningService visibilityPlanningService(ExecutorService visibilityExecutor,
                                                               @Value("${space.visibility.calculation.pair-batch-size:32}") int pairBatchSize,
                                                               @Value("${space.visibility.calculation.observer-orbit-cache-size:10000}") int observerOrbitCacheSize,
                                                               @Value("${space.visibility.scan.visibility-min-event-sec:10}") double visibilityMinEventSec) {
        return new VisibilityPlanningService(visibilityExecutor, pairBatchSize, observerOrbitCacheSize,
                visibilityMinEventSec, 0.0);
    }

    /**
     * 装配卫星对地下传规划服务，并复用公共线程池以控制总并发量。
     *
     * @param visibilityExecutor 可见性计算线程池
     * @param maxPairTasks 单次请求允许的最大卫星—地面站组合数
     * @return 下传规划服务
     */
    @Bean
    public DownlinkPlanningService downlinkPlanningService(ExecutorService visibilityExecutor,
                                                           @Value("${space.visibility.calculation.max-pair-tasks}") int maxPairTasks,
                                                           @Value("${space.visibility.calculation.pair-batch-size:32}") int pairBatchSize,
                                                           @Value("${space.visibility.scan.downlink-min-event-sec:30}") double downlinkMinEventSec) {
        return new DownlinkPlanningService(visibilityExecutor, maxPairTasks, pairBatchSize,
                downlinkMinEventSec, 0.0);
    }

    /**
     * 创建异步可见性规划任务的独立单线程调度器。规划任务串行提交执行，
     * 避免多个全目标遍历规划同时挤占共享计算线程池，实现提交侧自然背压。
     *
     * @return 规划任务调度线程池
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService planningTaskExecutor() {
        return Executors.newSingleThreadExecutor(new VisibilityThreadFactory("space-planning-task-"));
    }

    private static final class VisibilityThreadFactory implements ThreadFactory {
        private final String namePrefix;
        private final AtomicInteger index = new AtomicInteger(1);

        private VisibilityThreadFactory() {
            this("space-visibility-");
        }

        private VisibilityThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        /**
         * 创建守护计算线程并赋予可识别名称，便于日志定位且不会阻止 JVM 正常退出。
         *
         * @param task 线程需要执行的计算任务
         * @return 已完成命名和守护设置的线程
         */
        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, namePrefix + index.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
