package com.runisys.orbit.spacevisibility.api.ballistic;

import com.runisys.orbit.spacevisibility.domain.ballistic.visibility.OrbitCalculator;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 文件辅助引用只填原路径字段，三种发射历元别名仍按原优先级取值。 */
class BallisticFileInputCompatibilityTest {
    @Test
    void 原路径及baseLaunchUTC别名优先级保持() {
        VisibilityRequest request = new VisibilityRequest();
        VisibilityRequest.BallisticDef definition = new VisibilityRequest.BallisticDef();
        definition.setCsvPath("http://example.test/trajectory.csv");
        definition.setBaseLaunchUTC("2026/07/01 00:00:00.000");
        definition.setBaseLaunchUtc("2026/07/02 00:00:00.000");
        definition.setBaselaunchUTC("2026/07/03 00:00:00.000");
        request.setBallistics(Collections.singletonList(definition));
        OrbitCalculator.BallisticTargetInput first = request.toBallisticInputs().get(0);
        assertEquals("http://example.test/trajectory.csv", first.csvPath);
        assertEquals("2026/07/01 00:00:00.000", first.baseLaunchUtc);
        definition.setBaseLaunchUTC("  ");
        assertEquals("2026/07/02 00:00:00.000", request.toBallisticInputs().get(0).baseLaunchUtc);
        definition.setBaseLaunchUtc(null);
        assertEquals("2026/07/03 00:00:00.000", request.toBallisticInputs().get(0).baseLaunchUtc);
    }
}
