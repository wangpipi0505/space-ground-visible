package com.runisys.orbit.spacevisibility.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证底层轨道库的英文异常永远不会直接写入面向规划人员的任务结果。 */
class PlanningFailureReasonFormatterTest {

    @Test
    void translatesTooLargeEccentricityIntoActionableChineseReason() {
        String message = PlanningFailureReasonFormatter.toUserMessage(
                new IllegalStateException("too large eccentricity for propagation model: e = 7.172"));

        assertThat(message)
                .isEqualTo("轨道传播失败：传播过程中的偏心率超出椭圆轨道有效范围，请核对星历时效和轨道参数。")
                .doesNotContain("eccentricity")
                .doesNotContain("7.172");
    }
}
