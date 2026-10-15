package com.runisys.orbit.spacevisibility.domain.orbit;

import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import com.runisys.orbit.spacevisibility.domain.ballistic.OrbitCalculator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冻结原版状态 CSV 均以十六进制 double 保存；逐原始 bits 比较，不放宽位置或速度误差。
 * 卫星 CSV 的 queryOffsetSec 是查询相对根数历元的秒差；原采集 utcOffsetSec 恒为 0。
 * 查询偏移不属于轨道定义，不能据此重建工具实例或设置旧 UTC 偏移。
 */
class BallisticOrbitBindingTest {
    private static final double ORIGINAL_UTC_OFFSET_SEC = 0.0;

    private List<String> rows(String name) throws Exception {
        return Files.readAllLines(Paths.get("..", "test-data", "baseline", "ballistic-propagation", name),
                StandardCharsets.UTF_8);
    }

    private double number(String[] row, int column) {
        return Double.parseDouble(row[column]);
    }

    private OrbitDefinition.Elements definition(String[] row) {
        return new OrbitDefinition.Elements(OrbitDefinition.Model.valueOf(row[1]),
                number(row, 2), number(row, 3), number(row, 4), number(row, 5),
                number(row, 6), number(row, 7),
                OrbitTime.legacyJ2000(number(row, 8), ORIGINAL_UTC_OFFSET_SEC), 0.0);
    }

    private OrbitTime queryTime(String[] row) {
        return OrbitTime.legacyJ2000(number(row, 10), ORIGINAL_UTC_OFFSET_SEC);
    }

    private void bits(double expected, double actual) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
    }

    private void vector(String[] row, int first, Vector3 actual) {
        bits(number(row, first), actual.getX());
        bits(number(row, first + 1), actual.getY());
        bits(number(row, first + 2), actual.getZ());
    }

    /** 固定 case、model、六根数及历元分组；保留 CSV 内查询顺序，排除查询偏移。 */
    private Map<String, List<String[]>> positionGroups() throws Exception {
        List<String> lines = rows("satellite-position.csv");
        assertEquals("queryOffsetSec", lines.get(0).split(",")[9]);
        Map<String, List<String[]>> groups = new LinkedHashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] row = line.split(",");
            String key = String.join(",", Arrays.copyOfRange(row, 0, 9));
            List<String[]> group = groups.get(key);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(key, group);
            }
            group.add(row);
        }
        return groups;
    }

    @Test
    void 三模型独立实例与同历史乱序位置均逐位一致() throws Exception {
        Map<String, List<String[]>> groups = positionGroups();
        assertEquals(6, groups.size(), "两组根数乘三个模型，必须只有六组固定轨道定义");
        int totalQueries = 0;
        for (Map.Entry<String, List<String[]>> entry : groups.entrySet()) {
            List<String[]> group = entry.getValue();
            assertEquals(12, group.size(), entry.getKey() + " 必须保留十二次查询");
            OrbitDefinition.Elements definition = definition(group.get(0));
            OrbitPropagationTool history = BallisticOrbitPropagation.bind(definition);
            Set<Long> queriedTimes = new HashSet<>();
            boolean repeated = false;
            boolean unordered = false;
            boolean reversePropagation = false;
            double previousTime = Double.NEGATIVE_INFINITY;
            int historyQueries = 0;
            // 同一实例连续执行十二次；独立实例每次从同一根数重新绑定，二者均对照原黄金。
            for (String[] row : group) {
                OrbitTime time = queryTime(row);
                double absoluteTime = number(row, 10);
                double queryOffsetSec = number(row, 9);
                bits(queryOffsetSec, absoluteTime - number(row, 8));
                OrbitPropagationTool fresh = BallisticOrbitPropagation.bind(definition);
                vector(row, 11, fresh.positionAt(time, OrbitDefinition.Frame.J2000));
                vector(row, 11, history.positionAt(time, OrbitDefinition.Frame.J2000));
                assertSame(definition, fresh.definition());
                assertSame(definition, history.definition());
                assertEquals(OrbitDefinition.Frame.J2000, fresh.definition().outputFrame);
                bits(ORIGINAL_UTC_OFFSET_SEC, history.definition().utcOffsetSec);
                repeated |= !queriedTimes.add(Double.doubleToRawLongBits(absoluteTime));
                unordered |= absoluteTime < previousTime;
                reversePropagation |= queryOffsetSec < 0;
                previousTime = absoluteTime;
                historyQueries++;
            }
            assertEquals(12, historyQueries, entry.getKey() + " 同一实例必须执行十二次查询");
            assertTrue(repeated, entry.getKey() + " 必须包含同一时刻的重复查询");
            assertTrue(unordered, entry.getKey() + " 必须包含向前查询后回退时刻的乱序历史");
            assertTrue(reversePropagation, entry.getKey() + " 必须包含历元以前的反向传播");
            totalQueries += historyQueries;
        }
        assertEquals(72, totalQueries);
    }

    @Test
    void 原公有相对绝对入口及可变历元保留() throws Exception {
        double oldOffset = OrbitCalculator.utcOffsetSec;
        double oldReference = OrbitCalculator.referenceEpochSec;
        try {
            List<String> lines = rows("satellite-position.csv");
            for (String line : lines.subList(1, lines.size())) {
                String[] row = line.split(",");
                OrbitCalculator.OrbitElements elements = new OrbitCalculator.OrbitElements();
                elements.semiMajorAxisM = number(row, 2);
                elements.eccentricity = number(row, 3);
                elements.inclinationDeg = number(row, 4);
                elements.raanDeg = number(row, 5);
                elements.argumentOfPerigeeDeg = number(row, 6);
                elements.meanAnomalyDeg = number(row, 7);
                elements.epochSec = number(row, 8);
                // 使用原采集的 UTC 偏移；queryOffsetSec 只表示查询间隔。
                OrbitCalculator.utcOffsetSec = ORIGINAL_UTC_OFFSET_SEC;
                OrbitCalculator.Vector3 position = OrbitCalculator.propagateAtAbsolute(elements,
                        number(row, 10), OrbitCalculator.OrbitModel.valueOf(row[1]));
                vector(row, 11, new Vector3(position.x, position.y, position.z));
                OrbitCalculator.referenceEpochSec = number(row, 10);
                position = OrbitCalculator.propagate(elements, 0.0,
                        OrbitCalculator.OrbitModel.valueOf(row[1]));
                vector(row, 11, new Vector3(position.x, position.y, position.z));
            }
        } finally {
            OrbitCalculator.utcOffsetSec = oldOffset;
            OrbitCalculator.referenceEpochSec = oldReference;
        }
    }

    @Test
    void 数值J2原生速度逐位一致() throws Exception {
        List<String> lines = rows("satellite-j2-state.csv");
        for (String line : lines.subList(1, lines.size())) {
            String[] row = line.split(",");
            OrbitPropagationTool tool = BallisticOrbitPropagation.bind(definition(row));
            OrbitState state = tool.stateAt(queryTime(row), OrbitDefinition.Frame.J2000);
            vector(row, 11, state.positionM);
            vector(row, 14, state.velocityMps);
            vector(row, 14, tool.velocityAt(state.time, state.frame));
        }
    }

    @Test
    void 拟合区间外原状态含短间隔阈值逐位一致() throws Exception {
        List<String> lines = rows("fitter-state.csv");
        for (String line : lines.subList(1, lines.size())) {
            String[] row = line.split(",");
            Vector3 position = new Vector3(number(row, 1), number(row, 2), number(row, 3));
            Vector3 velocity = new Vector3(number(row, 4), number(row, 5), number(row, 6));
            BallisticCartesianPropagation.State result = BallisticCartesianPropagation.propagateRelative(
                    new BallisticCartesianPropagation.State(position, velocity), number(row, 7));
            vector(row, 8, result.r);
            vector(row, 11, result.v);
            OrbitPropagationTool tool = BallisticCartesianPropagation.bind(new OrbitDefinition.Cartesian(
                    OrbitTime.legacyJ2000(0, 0), position, velocity));
            OrbitState state = tool.stateAt(OrbitTime.legacyJ2000(number(row, 7), 0),
                    OrbitDefinition.Frame.J2000);
            vector(row, 8, state.positionM);
            vector(row, 11, state.velocityMps);
        }
    }

    @Test
    void 普通Java绝对Cartesian定义使用原相减且互不串扰() {
        double epoch = 839317728.125;
        Vector3 position = new Vector3(7000000, 0, 10000);
        Vector3 velocity = new Vector3(-10, 7500, 150);
        OrbitPropagationTool tool = BallisticCartesianPropagation.bind(new OrbitDefinition.Cartesian(
                OrbitTime.legacyJ2000(epoch, -28800), position, velocity));
        for (double dt : new double[]{0, 61.125, -3.25, 61.125, 0.00002}) {
            double absolute = epoch + dt;
            // 保留原 double 相减，不能把大绝对历元造成的表示误差替换为输入 dt。
            BallisticCartesianPropagation.State expected = BallisticCartesianPropagation.propagateRelative(
                    new BallisticCartesianPropagation.State(position, velocity), absolute - epoch);
            OrbitState actual = tool.stateAt(OrbitTime.legacyJ2000(absolute, -28800),
                    OrbitDefinition.Frame.J2000);
            bits(expected.r.getX(), actual.positionM.getX());
            bits(expected.r.getY(), actual.positionM.getY());
            bits(expected.r.getZ(), actual.positionM.getZ());
            bits(expected.v.getX(), actual.velocityMps.getX());
            bits(expected.v.getY(), actual.velocityMps.getY());
            bits(expected.v.getZ(), actual.velocityMps.getZ());
        }
    }

    @Test
    void 两解析模型速度明确为中心差分并保持查询口径() throws Exception {
        List<String> lines = rows("satellite-position.csv");
        for (String line : lines.subList(1, lines.size())) {
            String[] row = line.split(",");
            if (row[1].equals("J2_NUMERICAL")) {
                continue;
            }
            OrbitPropagationTool tool = BallisticOrbitPropagation.bind(definition(row));
            double time = number(row, 10);
            Vector3 expected = tool.positionAt(OrbitTime.legacyJ2000(time + 1, ORIGINAL_UTC_OFFSET_SEC),
                    OrbitDefinition.Frame.J2000)
                    .subtract(tool.positionAt(OrbitTime.legacyJ2000(time - 1, ORIGINAL_UTC_OFFSET_SEC),
                            OrbitDefinition.Frame.J2000)).scale(0.5);
            Vector3 actual = tool.velocityAt(queryTime(row), OrbitDefinition.Frame.J2000);
            bits(expected.getX(), actual.getX());
            bits(expected.getY(), actual.getY());
            bits(expected.getZ(), actual.getZ());
            assertThrows(IllegalArgumentException.class, () -> tool.positionAt(
                    OrbitTime.legacyJ2000(time, ORIGINAL_UTC_OFFSET_SEC + 1), OrbitDefinition.Frame.J2000));
            assertThrows(IllegalArgumentException.class, () -> tool.positionAt(queryTime(row),
                    OrbitDefinition.Frame.TEME));
        }
    }

    @Test
    void 拟合异常仍采用原文本及初始状态检查() {
        BallisticCartesianPropagation.State valid = new BallisticCartesianPropagation.State(
                new Vector3(7000000, 0, 0), Vector3.ZERO);
        assertEquals("二体RK4外推时间间隔无效，dt=NaN 秒。", assertThrows(IllegalArgumentException.class,
                () -> BallisticCartesianPropagation.propagateRelative(valid, Double.NaN)).getMessage());
        assertEquals("二体RK4外推初始状态为空，无法继续弹道外推。dt=1.000000 秒。",
                assertThrows(IllegalStateException.class,
                        () -> BallisticCartesianPropagation.propagateRelative(null, 1)).getMessage());
        assertTrue(assertThrows(IllegalStateException.class,
                () -> BallisticCartesianPropagation.propagateRelative(
                        new BallisticCartesianPropagation.State(Vector3.ZERO, Vector3.ZERO), 1))
                .getMessage().contains("位置半径过小"));
    }

    /** 原公有入口没有有限时间校验；新增工具校验不能替换其历史 NaN 行为或错误优先级。 */
    @Test
    void 原公有入口空参数错误及非有限时间历史行为保留() {
        assertEquals("卫星六根数不能为空。", assertThrows(IllegalArgumentException.class,
                () -> OrbitCalculator.propagateAtAbsolute(null, Double.NaN, null)).getMessage());
        OrbitCalculator.OrbitElements elements = new OrbitCalculator.OrbitElements();
        elements.semiMajorAxisM = 7078137;
        elements.eccentricity = 0.01;
        elements.inclinationDeg = 50;
        elements.raanDeg = 30;
        elements.argumentOfPerigeeDeg = 20;
        elements.epochSec = 839317728.125;
        OrbitCalculator.Vector3 initial = OrbitCalculator.propagateAtAbsolute(elements, elements.epochSec,
                OrbitCalculator.OrbitModel.J2_NUMERICAL);
        for (OrbitCalculator.OrbitModel model : OrbitCalculator.OrbitModel.values()) {
            OrbitCalculator.Vector3 actual = OrbitCalculator.propagateAtAbsolute(elements, Double.NaN, model);
            if (model == OrbitCalculator.OrbitModel.J2_NUMERICAL) {
                bits(initial.x, actual.x);
                bits(initial.y, actual.y);
                bits(initial.z, actual.z);
            } else {
                assertTrue(Double.isNaN(actual.x));
                assertTrue(Double.isNaN(actual.y));
                assertTrue(Double.isNaN(actual.z));
            }
        }
        OrbitCalculator.Vector3 defaultModel = OrbitCalculator.propagateAtAbsolute(elements, elements.epochSec, null);
        OrbitCalculator.Vector3 explicitModel = OrbitCalculator.propagateAtAbsolute(elements, elements.epochSec,
                OrbitCalculator.OrbitModel.TWO_BODY);
        bits(explicitModel.x, defaultModel.x);
        bits(explicitModel.y, defaultModel.y);
        bits(explicitModel.z, defaultModel.z);
        double offset = OrbitCalculator.utcOffsetSec;
        try {
            OrbitCalculator.utcOffsetSec = Double.NaN;
            OrbitCalculator.Vector3 actual = OrbitCalculator.propagateAtAbsolute(elements, elements.epochSec,
                    OrbitCalculator.OrbitModel.TWO_BODY);
            bits(defaultModel.x, actual.x);
            bits(defaultModel.y, actual.y);
            bits(defaultModel.z, actual.z);
        } finally {
            OrbitCalculator.utcOffsetSec = offset;
        }
    }
}
