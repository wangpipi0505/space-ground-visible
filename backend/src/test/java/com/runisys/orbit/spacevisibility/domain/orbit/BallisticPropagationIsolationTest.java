package com.runisys.orbit.spacevisibility.domain.orbit;

import com.runisys.orbit.spacevisibility.domain.ballistic.propagation.BallisticCartesianPropagation;
import com.runisys.orbit.spacevisibility.domain.ballistic.propagation.BallisticOrbitPropagation;

import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import com.runisys.orbit.spacevisibility.domain.ballistic.visibility.OrbitCalculator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T11 弹道不可变定义并发回归，三模型和拟合端点先对照T10有来源的十六进制黄金。
 * 不把旧全局字段当作并发安全接口：测试串行设置干扰值，只验证独立工具完全不读取它们。
 * 多历元定义保持原double相减；大历元拟合阈值以实际可表示时间差对照，不偷换成输入dt。
 */
class BallisticPropagationIsolationTest {
    private static final int WORKERS = 4;

    /** 原72位置和24数值状态，每条使用三组定义，历元分别平移0/64/-128秒、offset为0/28800/-28800。 */
    @Test
    void threeModelsShareImmutableToolsWithoutEpochOrOffsetLeakage() throws Exception {
        List<QueryCase> queries = new ArrayList<QueryCase>();
        Map<String, OrbitPropagationTool> tools = new LinkedHashMap<String, OrbitPropagationTool>();
        int goldenPositions = 0;
        int goldenStates = 0;
        double[] shifts = {0, 64, -128};
        double[] offsets = {0, 28800, -28800};
        for (int variant = 0; variant < shifts.length; variant++) {
            for (String[] row : rows("satellite-position.csv")) {
                OrbitPropagationTool tool = elementsTool(row, shifts[variant], offsets[variant], tools);
                OrbitTime time = OrbitTime.legacyJ2000(number(row, 10) + shifts[variant], offsets[variant]);
                Vector3 golden = vector(row, 11);
                OrbitState sequential = tool.stateAt(time, OrbitDefinition.Frame.J2000);
                bits(golden, sequential.positionM);
                queries.add(new QueryCase(tool, time, golden, sequential.velocityMps,
                        tool.definition().model == OrbitDefinition.Model.J2_NUMERICAL));
                goldenPositions++;
            }
            for (String[] row : rows("satellite-j2-state.csv")) {
                OrbitPropagationTool tool = elementsTool(row, shifts[variant], offsets[variant], tools);
                OrbitTime time = OrbitTime.legacyJ2000(number(row, 10) + shifts[variant], offsets[variant]);
                Vector3 position = vector(row, 11);
                Vector3 velocity = vector(row, 14);
                OrbitState sequential = tool.stateAt(time, OrbitDefinition.Frame.J2000);
                bits(position, sequential.positionM);
                bits(velocity, sequential.velocityMps);
                queries.add(new QueryCase(tool, time, position, velocity, true));
                goldenStates++;
            }
        }
        assertEquals(18, tools.size(), "两组根数×三模型×三个历元/offset定义，查询偏移不能纳入分组");
        assertEquals(216, goldenPositions);
        assertEquals(72, goldenStates);
        assertEquals(288, queries.size());
        int states = verifyInReusedPool(queries);
        assertEquals(2304, states);
        System.out.println("T11 ballistic models: 18 shared definitions; sequential 216 original golden positions"
                + " + 72 original golden PV states; concurrency 4 workers x 2 waves x 288 = 2304 states"
                + "/4608 vectors/13824 coordinates; exact bits versus golden/independent same-thread reference");
    }

    /** 两组端点×三种历元/offset；原零历元26条golden，另52条按实际相减独立生成顺序参考。 */
    @Test
    void fittedCartesianToolsRetainAbsoluteSubtractionDuringConcurrentReuse() throws Exception {
        List<QueryCase> queries = new ArrayList<QueryCase>();
        Map<String, OrbitPropagationTool> tools = new LinkedHashMap<String, OrbitPropagationTool>();
        double[] epochs = {0, 1024.125, 839317728.125};
        double[] offsets = {0, 28800, -28800};
        int goldenStates = 0;
        for (int variant = 0; variant < epochs.length; variant++) {
            for (String[] row : rows("fitter-state.csv")) {
                String key = variant + ":" + row[0];
                OrbitPropagationTool tool = tools.get(key);
                Vector3 initialPosition = vector(row, 1);
                Vector3 initialVelocity = vector(row, 4);
                if (tool == null) {
                    tool = BallisticCartesianPropagation.bind(new OrbitDefinition.Cartesian(
                            OrbitTime.legacyJ2000(epochs[variant], offsets[variant]),
                            initialPosition, initialVelocity));
                    tools.put(key, tool);
                }
                double absolute = epochs[variant] + number(row, 7);
                OrbitTime time = OrbitTime.legacyJ2000(absolute, offsets[variant]);
                BallisticCartesianPropagation.State independent = BallisticCartesianPropagation.propagateRelative(
                        new BallisticCartesianPropagation.State(initialPosition, initialVelocity),
                        absolute - epochs[variant]);
                if (variant == 0) {
                    bits(vector(row, 8), independent.r);
                    bits(vector(row, 11), independent.v);
                    goldenStates++;
                }
                OrbitState sequential = tool.stateAt(time, OrbitDefinition.Frame.J2000);
                bits(independent.r, sequential.positionM);
                bits(independent.v, sequential.velocityMps);
                queries.add(new QueryCase(tool, time, independent.r, independent.v, true));
            }
        }
        assertEquals(6, tools.size());
        assertEquals(26, goldenStates);
        assertEquals(78, queries.size());
        int states = verifyInReusedPool(queries);
        assertEquals(624, states);
        System.out.println("T11 Cartesian: 6 shared definitions; sequential 26 original golden PV + 52 actual"
                + " absolute-subtraction references; concurrency 4 workers x 2 waves x 78 = 624 states"
                + "/1248 vectors/3744 coordinates; exact bits; maxPositionM=0 maxVelocityMps=0");
    }

    /** 每一轮通过双latch确定性占满四线程；Future.get回收精确计数，异常不能被submit吞掉。 */
    private static int verifyInReusedPool(List<QueryCase> queries) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        Set<Long> previousThreads = new HashSet<Long>();
        double oldReference = OrbitCalculator.referenceEpochSec;
        double oldOffset = OrbitCalculator.utcOffsetSec;
        int states = 0;
        double maximumColdWarmVelocityDifferenceMps = 0;
        try {
            // 故意保持旧全局字段与所有定义不同；并发写旧字段属于旧入口限制，不在这里宣称安全。
            OrbitCalculator.referenceEpochSec = -123456.75;
            OrbitCalculator.utcOffsetSec = 54321.125;
            for (int wave = 0; wave < 2; wave++) {
                CountDownLatch ready = new CountDownLatch(WORKERS);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<WorkerResult>> futures = new ArrayList<Future<WorkerResult>>();
                final int currentWave = wave;
                for (int worker = 0; worker < WORKERS; worker++) {
                    final int workerIndex = worker;
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(30, TimeUnit.SECONDS), "等待确定性启动超时");
                        int count = 0;
                        double maximumColdWarmDifferenceMps = 0;
                        for (int index = 0; index < queries.size(); index++) {
                            // 第二轮反向，且不同线程错位遍历多个定义，持续共用每个工具实例。
                            int ordered = currentWave == 0 ? index : queries.size() - 1 - index;
                            QueryCase query = queries.get((ordered + workerIndex) % queries.size());
                            Vector3 expectedVelocity = query.velocity;
                            if (!query.originalVelocity) {
                                // 原解析接口没有速度golden；新增中心差分用同线程独立工具精确对照。
                                // 诊断支持末位差与JIT/解释模式相关，具体Math调用未定位；另记录冷/热差，不改原Math。
                                OrbitPropagationTool independent = BallisticOrbitPropagation.bind(
                                        (OrbitDefinition.Elements) query.tool.definition());
                                expectedVelocity = independent.velocityAt(query.time, OrbitDefinition.Frame.J2000);
                                maximumColdWarmDifferenceMps = Math.max(maximumColdWarmDifferenceMps,
                                        expectedVelocity.subtract(query.velocity).norm());
                            }
                            OrbitState actual = query.tool.stateAt(query.time, OrbitDefinition.Frame.J2000);
                            bits(query.position, actual.positionM);
                            bits(expectedVelocity, actual.velocityMps);
                            assertEquals(query.time, actual.time);
                            assertEquals(OrbitDefinition.Frame.J2000, actual.frame);
                            count++;
                        }
                        return new WorkerResult(Thread.currentThread().getId(), count, maximumColdWarmDifferenceMps);
                    }));
                }
                boolean allReady = ready.await(30, TimeUnit.SECONDS);
                start.countDown();
                assertTrue(allReady, "四个工作线程必须先就绪");
                Set<Long> currentThreads = new HashSet<Long>();
                for (Future<WorkerResult> future : futures) {
                    WorkerResult result = future.get(90, TimeUnit.SECONDS);
                    assertEquals(queries.size(), result.states);
                    states += result.states;
                    maximumColdWarmVelocityDifferenceMps = Math.max(maximumColdWarmVelocityDifferenceMps,
                            result.coldWarmVelocityDifferenceMps);
                    currentThreads.add(result.threadId);
                }
                assertEquals(WORKERS, currentThreads.size());
                if (wave == 0) {
                    previousThreads.addAll(currentThreads);
                } else {
                    assertEquals(previousThreads, currentThreads, "第二轮必须实际复用全部四线程");
                }
            }
            assertEquals(Double.doubleToRawLongBits(-123456.75),
                    Double.doubleToRawLongBits(OrbitCalculator.referenceEpochSec));
            assertEquals(Double.doubleToRawLongBits(54321.125),
                    Double.doubleToRawLongBits(OrbitCalculator.utcOffsetSec));
        } finally {
            pool.shutdownNow();
            boolean stopped = pool.awaitTermination(30, TimeUnit.SECONDS);
            OrbitCalculator.referenceEpochSec = oldReference;
            OrbitCalculator.utcOffsetSec = oldOffset;
            assertTrue(stopped, "线程池必须关闭并恢复旧兼容字段");
        }
        System.out.println("T11 ballistic analytic cold/warm measured velocity differenceMps="
                + maximumColdWarmVelocityDifferenceMps + "; shared/independent same-thread exact bits");
        return states;
    }

    private static OrbitPropagationTool elementsTool(String[] row, double shift, double offset,
                                                    Map<String, OrbitPropagationTool> tools) {
        String key = row[0] + ":" + row[1] + ":" + shift + ":" + offset;
        OrbitPropagationTool tool = tools.get(key);
        if (tool == null) {
            tool = BallisticOrbitPropagation.bind(new OrbitDefinition.Elements(OrbitDefinition.Model.valueOf(row[1]),
                    number(row, 2), number(row, 3), number(row, 4), number(row, 5), number(row, 6), number(row, 7),
                    OrbitTime.legacyJ2000(number(row, 8) + shift, offset), 0));
            tools.put(key, tool);
        }
        return tool;
    }

    private static List<String[]> rows(String name) throws Exception {
        List<String> lines = Files.readAllLines(Paths.get("..", "test-data", "baseline", "ballistic-propagation", name),
                StandardCharsets.UTF_8);
        List<String[]> result = new ArrayList<String[]>();
        for (String line : lines.subList(1, lines.size())) {
            result.add(line.split(","));
        }
        return result;
    }

    private static double number(String[] row, int column) {
        return Double.parseDouble(row[column]);
    }

    private static Vector3 vector(String[] row, int first) {
        return new Vector3(number(row, first), number(row, first + 1), number(row, first + 2));
    }

    private static void bits(Vector3 expected, Vector3 actual) {
        assertEquals(Double.doubleToRawLongBits(expected.getX()), Double.doubleToRawLongBits(actual.getX()));
        assertEquals(Double.doubleToRawLongBits(expected.getY()), Double.doubleToRawLongBits(actual.getY()));
        assertEquals(Double.doubleToRawLongBits(expected.getZ()), Double.doubleToRawLongBits(actual.getZ()));
    }

    private static final class QueryCase {
        private final OrbitPropagationTool tool;
        private final OrbitTime time;
        private final Vector3 position;
        private final Vector3 velocity;
        private final boolean originalVelocity;

        private QueryCase(OrbitPropagationTool tool, OrbitTime time, Vector3 position, Vector3 velocity,
                          boolean originalVelocity) {
            this.tool = tool;
            this.time = time;
            this.position = position;
            this.velocity = velocity;
            this.originalVelocity = originalVelocity;
        }
    }

    private static final class WorkerResult {
        private final long threadId;
        private final int states;
        private final double coldWarmVelocityDifferenceMps;

        private WorkerResult(long threadId, int states, double coldWarmVelocityDifferenceMps) {
            this.threadId = threadId;
            this.states = states;
            this.coldWarmVelocityDifferenceMps = coldWarmVelocityDifferenceMps;
        }
    }
}
