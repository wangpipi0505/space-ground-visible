package com.runisys.orbit.spacevisibility.domain.payload;

import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadVisibilityEvaluatorTest {

    private static final Instant INSTANT = Instant.parse("2026-01-01T00:00:00Z");
    /** 观测星固定在 (7000km, 0, 0)，速度沿 +Y：LVLH 参考系 R=+X，T=+Y，N=+Z。 */
    private static final Vector3 OBSERVER = new Vector3(7_000_000.0, 0.0, 0.0);
    private static final Vector3 OBSERVER_VELOCITY = new Vector3(0.0, 7_500.0, 0.0);

    /** 验证一次采样同时返回既有几何可见性和观测星、目标的精确光照状态。 */
    @Test
    void evaluatesGeometryAndBothIlluminationStatesAtOneSample() {
        VisibilitySample sample = PayloadVisibilityEvaluator.evaluate(
                OBSERVER, OBSERVER_VELOCITY,
                new Vector3(7_000_000.0, 1_000_000.0, 0.0),
                PayloadConstraint.space(0.0, 2_000_000.0, null, null, null, null, 0.0, 0.0, null),
                INSTANT, null, 1.0);

        assertThat(sample.isGeometryVisible()).isTrue();
        assertThat(sample.getObserverIllumination()).isNotNull();
        assertThat(sample.getTargetIllumination()).isNotNull();
        assertThat(sample.getLineOfSightRateDegPerSec()).isEqualTo(0.0);
    }

    @Test
    void acceptsGroundTargetInsideNadirFullCone() {
        PayloadConstraint constraint = PayloadConstraint.legacyGroundCone(30.0, 2_000.0, 0.0);

        boolean visible = PayloadVisibilityEvaluator.isVisible(
                OBSERVER, OBSERVER_VELOCITY,
                new Vector3(6_500_000.0, 0.0, 0.0),
                constraint,
                INSTANT,
                null,
                1.0);

        assertThat(visible).isTrue();
    }

    /**
     * 文档中的对地载荷也采用统一的距离、LVLH 姿态与角速度参数。俯仰下限 -91 度
     * 用于包含严格天底方向（-90 度），不能再被旧的对空参数校验错误拒绝。
     */
    @Test
    void evaluatesStandardGroundPayloadWithUnifiedAttitudeConstraints() {
        PayloadConstraint constraint = PayloadConstraint.ground(400_000.0, 3_500_000.0,
                null, null, -91.0, -26.0, 2.0, 0.0, 10.0);

        boolean visible = PayloadVisibilityEvaluator.isVisible(
                OBSERVER, OBSERVER_VELOCITY,
                new Vector3(6_500_000.0, 0.0, 0.0),
                constraint,
                INSTANT,
                null,
                1.0);

        assertThat(constraint.isLegacyGroundCone()).isFalse();
        assertThat(visible).isTrue();
    }

    /** 验证目标距离低于最近探测距离时不可见。 */
    @Test
    void rejectsSpaceTargetBelowMinimumDetectRange() {
        PayloadConstraint constraint = PayloadConstraint.space(500_000.0, 2_000_000.0,
                null, null, null, null, 0.0, 0.0, null);

        boolean visible = PayloadVisibilityEvaluator.isVisible(
                OBSERVER, OBSERVER_VELOCITY,
                new Vector3(7_000_000.0, 100_000.0, 0.0),
                constraint,
                INSTANT,
                null,
                1.0);

        assertThat(visible).isFalse();
    }

    /** 验证 LVLH 方位角窗口：沿迹方向方位角 0 度，轨道法线方向方位角 90 度。 */
    @Test
    void rejectsSpaceTargetOutsideAzimuthWindow() {
        PayloadConstraint constraint = PayloadConstraint.space(0.0, 2_000_000.0,
                90.0, 270.0, null, null, 0.0, 0.0, null);

        boolean alongTrackVisible = PayloadVisibilityEvaluator.isVisible(
                OBSERVER, OBSERVER_VELOCITY,
                new Vector3(7_000_000.0, 1_000_000.0, 0.0),
                constraint, INSTANT, null, 1.0);
        boolean orbitNormalVisible = PayloadVisibilityEvaluator.isVisible(
                OBSERVER, OBSERVER_VELOCITY,
                new Vector3(7_000_000.0, 0.0, 1_000_000.0),
                constraint, INSTANT, null, 1.0);

        assertThat(alongTrackVisible).isFalse();
        assertThat(orbitNormalVisible).isTrue();
    }

    /** 验证 LVLH 俯仰角窗口：当地水平面内视线俯仰角 0 度，背向地球视线俯仰角 90 度。 */
    @Test
    void rejectsSpaceTargetOutsideElevationWindow() {
        PayloadConstraint constraint = PayloadConstraint.space(0.0, 2_000_000.0,
                null, null, 10.0, 80.0, 0.0, 0.0, null);

        boolean horizontalVisible = PayloadVisibilityEvaluator.isVisible(
                OBSERVER, OBSERVER_VELOCITY,
                new Vector3(7_000_000.0, 1_000_000.0, 0.0),
                constraint, INSTANT, null, 1.0);
        boolean zenithVisible = PayloadVisibilityEvaluator.isVisible(
                OBSERVER, OBSERVER_VELOCITY,
                new Vector3(8_000_000.0, 0.0, 0.0),
                constraint, INSTANT, null, 1.0);
        boolean elevatedVisible = PayloadVisibilityEvaluator.isVisible(
                OBSERVER, OBSERVER_VELOCITY,
                new Vector3(7_700_000.0, 700_000.0, 0.0),
                constraint, INSTANT, null, 1.0);

        assertThat(horizontalVisible).isFalse();
        assertThat(zenithVisible).isFalse();
        assertThat(elevatedVisible).isTrue();
    }

    /** 验证视线指向太阳方向且夹角小于太阳回避半角时不可见。 */
    @Test
    void rejectsSpaceTargetInsideSunAvoidanceHalfAngle() {
        Vector3 sunDirection = SolarGeometry.sunDirectionEci(INSTANT);
        Vector3 target = OBSERVER.add(sunDirection.scale(1_000_000.0));
        PayloadConstraint avoided = PayloadConstraint.space(0.0, 2_000_000.0,
                null, null, null, null, 0.0, 90.0, null);
        PayloadConstraint notAvoided = PayloadConstraint.space(0.0, 2_000_000.0,
                null, null, null, null, 0.0, 0.0, null);

        assertThat(PayloadVisibilityEvaluator.isVisible(OBSERVER, OBSERVER_VELOCITY, target,
                avoided, INSTANT, null, 1.0)).isFalse();
        assertThat(PayloadVisibilityEvaluator.isVisible(OBSERVER, OBSERVER_VELOCITY, target,
                notAvoided, INSTANT, null, 1.0)).isTrue();
    }

    @Test
    void rejectsSpaceTargetWhenTrackingRateExceedsPayloadLimit() {
        PayloadConstraint constraint = PayloadConstraint.space(0.0, 2_000_000.0,
                null, null, null, null, 1.0, 0.0, null);

        VisibilitySample sample = PayloadVisibilityEvaluator.evaluate(
                OBSERVER, OBSERVER_VELOCITY,
                new Vector3(7_000_000.0, 1_000_000.0, 0.0),
                constraint,
                INSTANT,
                new Vector3(1_000_000.0, 0.0, 0.0),
                1.0);

        assertThat(sample.isGeometryVisible()).isFalse();
        assertThat(sample.getLineOfSightRateDegPerSec()).isEqualTo(90.0);
    }
}
