package com.runisys.orbit.spacevisibility.domain.payload;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class IlluminationConstraintTest {

    /** 验证 WinTool 的阴影约束只允许本影；半影不参与任何计算。 */
    @Test
    void treatsOnlyUmbraAsShadowForRecommendedMode() {
        IlluminationConstraint constraint = IlluminationConstraint.from(
                "OBSERVER_SHADOW_TARGET_SUNLIT", Collections.<String>emptyList(), Collections.<String>emptyList());

        assertThat(constraint.allows(IlluminationState.PENUMBRA, IlluminationState.SUNLIT)).isFalse();
        assertThat(constraint.allows(IlluminationState.UMBRA, IlluminationState.SUNLIT)).isTrue();
        assertThat(constraint.allows(IlluminationState.SUNLIT, IlluminationState.SUNLIT)).isFalse();
    }

    /** 验证自定义模式只接受用户明确勾选的两端精确状态。 */
    @Test
    void acceptsOnlyExplicitCustomStates() {
        IlluminationConstraint constraint = IlluminationConstraint.from(
                "CUSTOM", Collections.singletonList("UMBRA"), Collections.singletonList("PENUMBRA"));

        assertThat(constraint.allows(IlluminationState.UMBRA, IlluminationState.PENUMBRA)).isTrue();
        assertThat(constraint.allows(IlluminationState.PENUMBRA, IlluminationState.PENUMBRA)).isFalse();
    }
}
