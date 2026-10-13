package com.runisys.orbit.spacevisibility.domain.orbit;

import org.junit.jupiter.api.Test;

import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * alpha-5 目录编号（六位 NORAD 扩展表示，Space-Track/CelesTrak：A0000=100000…）兼容回归。
 * 编号列含字母时须遮蔽传播而非拒绝——SGP4 轨道元素与编号无关。
 */
class TleOrbitStateProviderAlpha5Test {

    private static final String L1 = "1 A0000U 26067CY  26222.88571527  .00004719  00000-0  21676-3 0  9994";
    private static final String L2 = "2 A0000  97.4613 180.8071 0008233 161.3775 198.7764 15.20848640 20016";

    @Test
    void propagatesAlpha5CatalogTle() {
        TleOrbitStateProvider provider = new TleOrbitStateProvider(L1, L2);
        Instant t = Instant.parse("2026-01-18T13:50:00Z");
        Vector3 pos = provider.positionAt(t);
        assertThat(pos.norm()).isBetween(6_000_000.0, 60_000_000.0);
    }

    @Test
    void numericCatalogTleUnaffected() {
        // 纯数字编号原样保留（不被遮蔽），行为与历史一致
        String l1 = "1 54700U 22169B   26222.93157105 -.00000044  00000-0  35880-4 0  9998";
        String l2 = "2 54700  59.9970  59.5864 0007686 292.5992  67.4230 14.29556166191186";
        assertThatCode(() -> new TleOrbitStateProvider(l1, l2)).doesNotThrowAnyException();
    }

    @Test
    void maskCatalogOnlyTouchesNonNumericCatalogSegment() {
        String numeric = "1 54700U 22169B   26222.93157105 -.00000044  00000-0  35880-4 0  9998";
        String alpha5 = "1 A0000U 26067CY  26222.88571527  .00004719  00000-0  21676-3 0  9994";
        assertThat(TleOrbitStateProvider.maskCatalog(numeric)).isEqualTo(numeric);
        assertThat(TleOrbitStateProvider.maskCatalog(alpha5))
                .isEqualTo("1 00000U 26067CY  26222.88571527  .00004719  00000-0  21676-3 0  9994");
    }
}
