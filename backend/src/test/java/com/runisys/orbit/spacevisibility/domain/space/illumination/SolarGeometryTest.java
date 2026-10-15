package com.runisys.orbit.spacevisibility.domain.space.illumination;

import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 WinTool 确认的 WGS84 椭球圆柱影：不使用太阳视半径，也不产生半影。 */
class SolarGeometryTest {

    @Test
    void treatsRayOutsideWgs84PolarRadiusAsSunlitRatherThanPenumbraOrUmbra() {
        // z=6,360km 位于 WGS84 极半径（约6,356.75km）之外、旧球半径（6,378.137km）之内。
        // 因此旧球体会误判遮挡，WGS84 椭球圆柱影必须为阳照。
        Vector3 objectPosition = new Vector3(-7_000_000.0, 0.0, 6_360_000.0);
        Vector3 sunDirection = new Vector3(1.0, 0.0, 0.0);

        assertThat(SolarGeometry.illumination(objectPosition, sunDirection))
                .isEqualTo(IlluminationState.SUNLIT);
    }
}
