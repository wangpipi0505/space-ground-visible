package com.runisys.orbit.spacevisibility.domain.orbit;

import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import org.junit.jupiter.api.Test;
import org.orekit.propagation.SpacecraftState;
import org.orekit.propagation.numerical.NumericalPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScalesFactory;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T11 空间调用历史与线程隔离回归。原实例与公共工具各持独立传播器，按同一历史逐 bits 比较。
 * 反射仅在测试内只读原缓存，用实际锚点、重启状态和淘汰证据验证策略；不向产品添加测试接口。
 */
class SpacePropagationIsolationTest {
    private static final Instant EPOCH = Instant.parse("2026-01-01T23:30:00.123456789Z");
    private static final String L1 = "1 25544U 98067A   24100.51782528  .00014321  00000+0  25813-3 0  9997";
    private static final String L2 = "2 25544  51.6396  46.5178 0004400  75.0248  50.3079 15.50175300448688";
    private static final String A1 = "1 A0000U 26067CY  26222.88571527  .00004719  00000-0  21676-3 0  9994";
    private static final String A2 = "2 A0000  97.4613 180.8071 0008233 161.3775 198.7764 15.20848640 20016";
    private static final int WORKERS = 4;
    private static final double[][] HISTORIES = {
        {-61.125, -3.25, 0, 0.125, 59.999999, 60, 120, 1800.125, 3601.3, 86400.125},
        {86400.125, 3601.3, 1800.125, 120, 60, 59.999999, 0.125, 0, -3.25, -61.125},
        {0, 60, 120, 59.999999, 59.999999, -3.25, 86400.125, 3601.3, 0.125, 0}
    };

    /** 三种真实历史均先位置后速度；速度仍由原 ±1秒位置差分，不添加容差。 */
    @Test
    void originalAndToolHaveIdenticalBitsForForwardReverseAndRepeatedHistories() {
        int states = 0;
        for (int model = 0; model < 4; model++) {
            for (double[] history : HISTORIES) {
                OrbitStateProvider original = original(model);
                SpaceOrbitStateProvider bridge = bridge(model);
                for (double seconds : history) {
                    Instant query = query(seconds);
                    Vector3 position = original.positionAt(query);
                    Vector3 velocity = original.velocityAt(query);
                    OrbitState actual = bridge.tool().stateAt(OrbitTime.utc(query), OrbitDefinition.Frame.TEME);
                    bits(position, actual.positionM);
                    bits(velocity, actual.velocityMps);
                    states++;
                }
            }
        }
        assertEquals(120, states);
        System.out.println("T11 space histories: 120 states/240 vectors/720 coordinates; original/tool exact bits");
    }

    /** 沿用原返回顺序测试的 <0.001米规则；跨历史速度只记录，不虚构原未定义的容差。 */
    @Test
    void keplerQueryOrderRetainsOriginalMillimetrePositionCriterion() {
        double maximumPositionM = 0;
        double maximumVelocityMps = 0;
        for (int model = 0; model < 2; model++) {
            OrbitStateProvider directOriginal = original(model);
            SpaceOrbitStateProvider directTool = bridge(model);
            Instant target = EPOCH.plusSeconds(3601).plusMillis(300);
            Vector3 expectedPosition = directOriginal.positionAt(target);
            Vector3 expectedVelocity = directOriginal.velocityAt(target);
            bits(expectedPosition, directTool.positionAt(target));
            bits(expectedVelocity, directTool.velocityAt(target));
            OrbitStateProvider historyOriginal = original(model);
            SpaceOrbitStateProvider historyTool = bridge(model);
            Instant later = EPOCH.plusSeconds(86395).plusMillis(700);
            bits(historyOriginal.positionAt(later), historyTool.positionAt(later));
            Vector3 actualPosition = historyOriginal.positionAt(target);
            Vector3 actualVelocity = historyOriginal.velocityAt(target);
            bits(actualPosition, historyTool.positionAt(target));
            bits(actualVelocity, historyTool.velocityAt(target));
            double positionErrorM = actualPosition.subtract(expectedPosition).norm();
            assertTrue(positionErrorM < 0.001, "原跨历史位置标准，步长模型=" + model + "，误差米=" + positionErrorM);
            maximumPositionM = Math.max(maximumPositionM, positionErrorM);
            maximumVelocityMps = Math.max(maximumVelocityMps, actualVelocity.subtract(expectedVelocity).norm());
        }
        System.out.println("T11 cross-history: maxPositionM=" + maximumPositionM
                + "; maxVelocityMps=" + maximumVelocityMps + "; 10s/3.5s; 10 exact vector comparisons");
    }

    /** 实際累计8193锚点，检查60秒阈值、重复复用、floor锚点重启及淘汰后历元回退。 */
    @Test
    void actualAnchorsRespectSixtySecondsEvictionAndFallback() throws Exception {
        KeplerOrbitStateProvider nativeActual = kepler(10);
        KeplerOrbitStateProvider expected = kepler(10);
        SpaceOrbitStateProvider tool = new SpaceOrbitStateProvider(elements(10), nativeActual);
        comparePosition(expected, tool, EPOCH);
        Object context = context(nativeActual);
        TreeMap<AbsoluteDate, SpacecraftState> anchors = anchors(context);
        AbsoluteDate initialDate = date(EPOCH);
        SpacecraftState firstAnchor = anchors.get(initialDate);
        comparePosition(expected, tool, EPOCH);
        assertEquals(1, anchors.size());
        assertSame(firstAnchor, anchors.get(initialDate), "重复查询复用已保留锚点");
        comparePosition(expected, tool, EPOCH.plusSeconds(59).plusNanos(999999999));
        assertEquals(1, anchors.size(), "不足60秒不得新增锚点");
        comparePosition(expected, tool, EPOCH.plusSeconds(60));
        assertEquals(2, anchors.size());
        comparePosition(expected, tool, EPOCH.plusSeconds(120));
        SpacecraftState floor = anchors.get(date(EPOCH.plusSeconds(60)));
        comparePosition(expected, tool, EPOCH.plusSeconds(61));
        NumericalPropagator propagator = (NumericalPropagator) field(context, "numericalPropagator");
        assertSame(floor, propagator.getInitialState(), "回退从不晚于查询的最近60秒锚点重启");
        assertEquals(4, anchors.size(), "回退查询61秒也成为原算法锚点");
        // 使用新独立实例，避免上述61秒回退锚点改变上限场景的确定性计数。
        // 原算法每次restarted都会插入锚点。历元后10秒起逐毫秒回退，实际形成8193个锚点，
        // 既覆盖真实淘汰，又避免把容量测试变成8193次不断增长的跨日完整积分性能测试。
        nativeActual = kepler(10);
        expected = kepler(10);
        tool = new SpaceOrbitStateProvider(elements(10), nativeActual);
        for (int index = 0; index <= 8192; index++) {
            comparePosition(expected, tool, EPOCH.plusSeconds(10).minusMillis(index));
        }
        context = context(nativeActual);
        anchors = anchors(context);
        assertEquals(8192, anchors.size());
        assertEquals(date(EPOCH.plusSeconds(10).minusMillis(8191)), anchors.firstKey());
        assertEquals(date(EPOCH.plusSeconds(10)), anchors.lastKey());
        assertFalse(anchors.containsKey(date(EPOCH.plusSeconds(10).minusMillis(8192))),
                "第8193个实际锚点按时间树规则淘汰最早时刻锚点");
        comparePosition(expected, tool, EPOCH.plusSeconds(1));
        propagator = (NumericalPropagator) field(context, "numericalPropagator");
        assertSame(field(nativeActual, "initialState"), propagator.getInitialState(), "早于最老锚点须回到原历元");
        assertEquals(8192, anchors.size());
        assertFalse(anchors.containsKey(date(EPOCH.plusSeconds(1))), "较早回退新锚点被上限规则立即淘汰");
        System.out.println("T11 cache: 8200 exact position comparisons; 8193 actual anchors -> 8192 retained;"
                + " oldest=+1.809s newest=+10s; repeated/60s/floor/+1s epoch fallback verified");
    }

    /** 两轮任务确定性占满同一4线程池；每线程原实例持续同历史，共享工具且跨模型交替。 */
    @Test
    void sharedToolsRetainIndependentNativeContextsAcrossReusedPoolThreads() throws Exception {
        OrbitStateProvider[] actualNative = {original(0), original(1), original(2), original(3)};
        OrbitDefinition[] definitions = {elements(10), elements(3.5), new OrbitDefinition.Tle(L1, L2),
            new OrbitDefinition.Tle(A1, A2)};
        SpaceOrbitStateProvider[] tools = new SpaceOrbitStateProvider[4];
        for (int model = 0; model < 4; model++) {
            tools[model] = new SpaceOrbitStateProvider(definitions[model], actualNative[model]);
        }
        ThreadLocal<OrbitStateProvider[]> references = ThreadLocal.withInitial(() ->
                new OrbitStateProvider[]{original(0), original(1), original(2), original(3)});
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        Map<Long, Object[]> firstWave = new HashMap<Long, Object[]>();
        int states = 0;
        try {
            for (int wave = 0; wave < 2; wave++) {
                CountDownLatch ready = new CountDownLatch(WORKERS);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<WorkerResult>> futures = new ArrayList<Future<WorkerResult>>();
                final int currentWave = wave;
                for (int worker = 0; worker < WORKERS; worker++) {
                    final int historyIndex = worker % HISTORIES.length;
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(30, TimeUnit.SECONDS), "等待确定性启动超时");
                        int comparisons = 0;
                        for (double seconds : HISTORIES[(historyIndex + currentWave) % HISTORIES.length]) {
                            for (int model = 0; model < 4; model++) {
                                Instant instant = query(seconds);
                                Vector3 position = references.get()[model].positionAt(instant);
                                Vector3 velocity = references.get()[model].velocityAt(instant);
                                OrbitState actual = tools[model].tool().stateAt(OrbitTime.utc(instant),
                                        OrbitDefinition.Frame.TEME);
                                bits(position, actual.positionM);
                                bits(velocity, actual.velocityMps);
                                comparisons++;
                            }
                        }
                        Object[] contexts = new Object[4];
                        for (int model = 0; model < 4; model++) {
                            contexts[model] = context(actualNative[model]);
                        }
                        return new WorkerResult(Thread.currentThread().getId(), contexts, comparisons);
                    }));
                }
                boolean allReady = ready.await(30, TimeUnit.SECONDS);
                start.countDown();
                assertTrue(allReady, "必须全部工作线程就绪才开始共享工具查询");
                for (Future<WorkerResult> future : futures) {
                    WorkerResult result = future.get(90, TimeUnit.SECONDS);
                    assertEquals(40, result.states);
                    states += result.states;
                    assertNotSame(result.contexts[0], result.contexts[1], "同线程不同定义也有独立缓存");
                    if (wave == 0) {
                        firstWave.put(result.threadId, result.contexts);
                    } else {
                        Object[] previous = firstWave.get(result.threadId);
                        assertTrue(previous != null, "第二轮必须实际复用原工作线程");
                        for (int model = 0; model < 4; model++) {
                            assertSame(previous[model], result.contexts[model], "线程池复用保留该线程缓存");
                        }
                    }
                }
            }
            assertEquals(WORKERS, firstWave.size());
            for (int model = 0; model < 4; model++) {
                Map<Object, Boolean> identities = new IdentityHashMap<Object, Boolean>();
                for (Object[] contexts : firstWave.values()) {
                    identities.put(contexts[model], Boolean.TRUE);
                }
                assertEquals(WORKERS, identities.size(), "每个共享模型实际拥有4个不同原传播上下文");
            }
            assertEquals(320, states);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "线程池必须关闭");
        }
        System.out.println("T11 space concurrency: 4 workers x 2 waves x 4 shared models x 10 queries"
                + " = 320 states/640 vectors/1920 coordinates; exact bits; 16 distinct reused contexts");
    }

    private static final class WorkerResult {
        private final long threadId;
        private final Object[] contexts;
        private final int states;

        private WorkerResult(long threadId, Object[] contexts, int states) {
            this.threadId = threadId;
            this.contexts = contexts;
            this.states = states;
        }
    }

    private static OrbitDefinition.Elements elements(double step) {
        return new OrbitDefinition.Elements(OrbitDefinition.Model.SPACE_KEPLER_J2_RK4,
                8000000, 0.1, 45, 20, 10, 30, OrbitTime.utc(EPOCH), step);
    }

    private static KeplerOrbitStateProvider kepler(double step) {
        return new KeplerOrbitStateProvider(8000000, 0.1, 45, 20, 10, 30, EPOCH, step);
    }

    private static OrbitStateProvider original(int model) {
        if (model < 2) {
            return kepler(model == 0 ? 10 : 3.5);
        }
        return new TleOrbitStateProvider(model == 2 ? L1 : A1, model == 2 ? L2 : A2);
    }

    private static SpaceOrbitStateProvider bridge(int model) {
        return model < 2 ? SpaceOrbitStateProvider.fromDefinition(elements(model == 0 ? 10 : 3.5))
                : SpaceOrbitStateProvider.fromTle(model == 2 ? L1 : A1, model == 2 ? L2 : A2);
    }

    private static Instant query(double seconds) {
        return EPOCH.plusNanos(Math.round(seconds * 1000000000.0));
    }

    private static AbsoluteDate date(Instant instant) {
        return new AbsoluteDate(instant, TimeScalesFactory.getUTC());
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static Object context(OrbitStateProvider provider) throws Exception {
        String name = provider instanceof KeplerOrbitStateProvider ? "propagation" : "propagator";
        return ((ThreadLocal<?>) field(provider, name)).get();
    }

    @SuppressWarnings("unchecked")
    private static TreeMap<AbsoluteDate, SpacecraftState> anchors(Object context) throws Exception {
        return (TreeMap<AbsoluteDate, SpacecraftState>) field(context, "anchors");
    }

    private static void comparePosition(OrbitStateProvider expected, SpaceOrbitStateProvider actual, Instant instant) {
        bits(expected.positionAt(instant), actual.positionAt(instant));
    }

    private static void bits(Vector3 expected, Vector3 actual) {
        assertEquals(Double.doubleToRawLongBits(expected.getX()), Double.doubleToRawLongBits(actual.getX()));
        assertEquals(Double.doubleToRawLongBits(expected.getY()), Double.doubleToRawLongBits(actual.getY()));
        assertEquals(Double.doubleToRawLongBits(expected.getZ()), Double.doubleToRawLongBits(actual.getZ()));
    }
}
