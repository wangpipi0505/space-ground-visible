package com.runisys.orbit.spacevisibility.domain.window;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Paths;
import static org.junit.jupiter.api.Assertions.*;

/** T19 先验证共用主体存在，再用独立冻结原实现生成的黄金约束生产行为。 */
class SharedWindowTest {
    /** 纳秒超过double整数精度仍逐位不变；弹道负零/NaN标记只搬运原位，不清洗。 */
    @Test
    void sharedRunsKeepNativeMarkersAndFirstLastSamples() {
        long start = 9007199254740993L;
        SampledStateRuns<String> runs = new SampledStateRuns<String>("A", start);
        assertNull(runs.accept(new String("A"), start + 1));
        SampledStateRuns.Segment<String> first = runs.accept("B", start + 2);
        assertEquals(start, first.getStart());
        assertEquals(start + 1, first.getLast());
        assertEquals(2, first.getPointCount());
        assertEquals("A", first.getState());
        assertEquals(start + 2, runs.getStart());
        SampledStateRuns<Boolean> points = new SampledStateRuns<Boolean>(null, 0);
        points.accept(true, Double.doubleToRawLongBits(-0.0));
        points.accept(true, 0x7ff8000000000001L);
        SampledStateRuns.Segment<Boolean> arc = points.accept(null, 0);
        assertEquals(Long.MIN_VALUE, arc.getStart());
        assertEquals(0x7ff8000000000001L, arc.getLast());
        assertEquals(2, arc.getPointCount());
        assertNull(points.snapshot());
    }

    /** 原累计黄金包含两个相反顺序；这里直接约束共用主体不能改并集或补偿求和。 */
    @Test
    void sharedTotalsKeepOverlapAndFloatingPointOrder() {
        java.util.List<TimeInterval> values = java.util.Arrays.asList(
                TimeInterval.of(0, 1e16), TimeInterval.of(0, 1), TimeInterval.of(0, 1));
        WindowIntervals.Totals total = WindowIntervals.total(values, TimeInterval::durationSec, value -> 2L);
        assertEquals(1e16, total.getDurationSec());
        assertEquals(6L, total.getPointCount());
        java.util.Collections.reverse(values);
        assertEquals(1e16 + 2, WindowIntervals.total(values, TimeInterval::durationSec, null).getDurationSec());
    }
    @Test
    void sharedRunAndIntervalInfrastructureMustExist() {
        assertDoesNotThrow(() -> Class.forName("com.runisys.orbit.spacevisibility.domain.window.SampledStateRuns"),
                "两侧扫描应实际共用连续状态段主体");
        assertDoesNotThrow(() -> Class.forName("com.runisys.orbit.spacevisibility.domain.window.WindowIntervals"));
    }

    /** 原classes两次及原plain JAR结果一致；默认运行不依赖任何冻结class或原目录。 */
    @Test
    void originalWindowGoldenMustRemainExact() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        // 两侧均经JSON读取：valueToTree保留LongNode，文件readTree的小整数为IntNode，
        // 直接比较节点会把值完全相同的计数误报为差异；浮点仍由rawbits字符串严格约束。
        com.fasterxml.jackson.databind.JsonNode expected = mapper.readTree(
                Paths.get("../test-data/baseline/windows/windows.json").toFile());
        com.fasterxml.jackson.databind.JsonNode actual = mapper.readTree(mapper.writeValueAsBytes(WindowReferenceScenarios.capture()));
        compareExact("$", expected, actual);
    }

    /** 首个差异只报告路径和叶值；完整递归仍严格检查字段、数组顺序和原JSON字符串。 */
    private static void compareExact(String path, com.fasterxml.jackson.databind.JsonNode expected,
                                     com.fasterxml.jackson.databind.JsonNode actual) throws Exception {
        assertNotNull(actual, path);
        if (expected.isObject()) {
            assertTrue(actual.isObject(), path);
            assertEquals(expected.size(), actual.size(), path + ".fieldCount");
            java.util.Iterator<String> keys = expected.fieldNames();
            while (keys.hasNext()) {
                String key = keys.next(); compareExact(path + "." + key, expected.get(key), actual.get(key));
            }
        } else if (expected.isArray()) {
            assertTrue(actual.isArray(), path);
            assertEquals(expected.size(), actual.size(), path + ".length");
            for (int i = 0; i < expected.size(); i++) compareExact(path + "[" + i + "]", expected.get(i), actual.get(i));
        } else if (expected.isTextual() && expected.asText().startsWith("{\n") && !expected.equals(actual)) {
            ObjectMapper mapper = new ObjectMapper();
            compareExact(path + ".json", mapper.readTree(expected.asText()), mapper.readTree(actual.asText()));
            fail(path + ": JSON值相同但原输出格式不同");
        } else {
            assertEquals(expected, actual, path);
        }
    }
}
