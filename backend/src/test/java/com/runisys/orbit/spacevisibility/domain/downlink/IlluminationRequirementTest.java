package com.runisys.orbit.spacevisibility.domain.downlink;

import com.runisys.orbit.spacevisibility.domain.payload.IlluminationState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class IlluminationRequirementTest {

    @Test
    void acceptsSunlitOnlyWhenSunlitIsRequired() {
        assertThat(IlluminationRequirement.SUNLIT.matches(IlluminationState.SUNLIT)).isTrue();
        assertThat(IlluminationRequirement.SUNLIT.matches(IlluminationState.PENUMBRA)).isFalse();
        assertThat(IlluminationRequirement.SUNLIT.matches(IlluminationState.UMBRA)).isFalse();
    }

    @Test
    /** WinTool 的“阴影区”只接受地球完全遮住太阳盘的本影状态。 */
    void treatsOnlyUmbraAsWinToolShadow() {
        assertThat(IlluminationRequirement.SHADOW.matches(IlluminationState.SUNLIT)).isFalse();
        assertThat(IlluminationRequirement.SHADOW.matches(IlluminationState.PENUMBRA)).isFalse();
        assertThat(IlluminationRequirement.SHADOW.matches(IlluminationState.UMBRA)).isTrue();
    }

    @Test
    void parsesOptionalApiValue() {
        assertThat(IlluminationRequirement.fromApiValue(null)).isEqualTo(IlluminationRequirement.ANY);
        assertThat(IlluminationRequirement.fromApiValue("sunlit")).isEqualTo(IlluminationRequirement.SUNLIT);
        assertThat(IlluminationRequirement.fromApiValue(" SHADOW ")).isEqualTo(IlluminationRequirement.SHADOW);
        assertThatIllegalArgumentException().isThrownBy(() -> IlluminationRequirement.fromApiValue("UNKNOWN"));
    }
}
