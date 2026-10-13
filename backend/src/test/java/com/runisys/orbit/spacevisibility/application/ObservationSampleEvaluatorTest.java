package com.runisys.orbit.spacevisibility.application;

import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitStateProvider;
import com.runisys.orbit.spacevisibility.domain.payload.PayloadConstraint;
import com.runisys.orbit.spacevisibility.domain.payload.VisibilitySample;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationSampleEvaluatorTest {

    /** 验证单时刻观测判定统一读取双方轨道状态、构造前一时刻视线并返回载荷与光照结果。 */
    @Test
    void evaluatesPayloadAndIlluminationFromOrbitStates() {
        AtomicInteger observerCalls = new AtomicInteger();
        AtomicInteger targetCalls = new AtomicInteger();
        OrbitStateProvider observerOrbit = instant -> {
            observerCalls.incrementAndGet();
            return new Vector3(7_000_000.0, 0.0, 0.0);
        };
        OrbitStateProvider targetOrbit = instant -> {
            targetCalls.incrementAndGet();
            return new Vector3(7_000_000.0, 1_000_000.0, 0.0);
        };
        VisibilityObserver observer = new VisibilityObserver("A", observerOrbit,
                PayloadConstraint.space(0.0, 2_000_000.0, null, null, null, null, 0.0, 0.0, null));
        VisibilityTarget target = new VisibilityTarget("T", targetOrbit);

        ObservationSampleEvaluator evaluator = new ObservationSampleEvaluator();
        OrbitPositionCache observerCache = new OrbitPositionCache(10);
        OrbitPositionCache targetCache = new OrbitPositionCache(10);
        Instant instant = Instant.parse("2026-01-01T00:00:01Z");
        VisibilitySample sample = evaluator.evaluate(observer, target, observerCache, targetCache, instant, 1.0);
        evaluator.evaluate(observer, target, observerCache, targetCache, instant, 1.0);

        assertThat(sample.isGeometryVisible()).isTrue();
        assertThat(sample.getObserverIllumination()).isNotNull();
        assertThat(sample.getTargetIllumination()).isNotNull();
        assertThat(observerCalls).hasValue(3);
        assertThat(targetCalls).hasValue(2);
    }
}
