package com.runisys.orbit.spacevisibility.domain.space.visibility;

import com.runisys.orbit.spacevisibility.domain.space.visibility.CombinationConstraint;
import com.runisys.orbit.spacevisibility.domain.space.illumination.IlluminationState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class VisibilityConstraintPlannerTest {

    @Test
    void calculatesCombinationArcsOnlyForTheSameTarget() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        VisibilityConstraintPlanner planner = new VisibilityConstraintPlanner();
        List<VisibilityPairResult> pairResults = Arrays.asList(
                new VisibilityPairResult("A", "TARGET-1", Collections.singletonList(arc(start, 0.0, 500.0))),
                new VisibilityPairResult("B", "TARGET-1", Collections.singletonList(arc(start, 100.0, 600.0))),
                new VisibilityPairResult("A", "TARGET-2", Collections.singletonList(arc(start, 0.0, 500.0))),
                new VisibilityPairResult("B", "TARGET-2", Collections.singletonList(arc(start, 600.0, 700.0))));

        List<VisibilityObservationResult> results = planner.calculate(start, pairResults,
                Collections.singletonList(new CombinationConstraint(Arrays.asList("A", "B"), 350.0)));

        assertThat(results).hasSize(1);
        VisibilityObservationResult result = results.get(0);
        assertThat(result.getTargetId()).isEqualTo("TARGET-1");
        assertThat(result.getObserverSatelliteIds()).containsExactly("A", "B");
        assertThat(result.getObservationType()).isEqualTo("COOPERATIVE");
        assertThat(result.getObservationTypeName()).isEqualTo("共同观测");
        assertThat(result.getArcs()).extracting(VisibilityArc::getDurationSec).containsExactly(400.0);
    }

    /** 验证半影和本影组成的连续允许窗口保留为父窗口，精确状态仅作为明细输出。 */
    @Test
    void keepsCombinedDurationAcrossAdjacentAllowedShadowStatesThenSplitsOutput() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        List<VisibilityPairResult> pairResults = Arrays.asList(
                new VisibilityPairResult("A", "TARGET", Arrays.asList(
                        illuminatedArc(start, 0.0, 100.0, "A", IlluminationState.PENUMBRA),
                        illuminatedArc(start, 100.0, 200.0, "A", IlluminationState.UMBRA))),
                new VisibilityPairResult("B", "TARGET", Arrays.asList(
                        illuminatedArc(start, 0.0, 100.0, "B", IlluminationState.PENUMBRA),
                        illuminatedArc(start, 100.0, 200.0, "B", IlluminationState.UMBRA))));

        List<VisibilityObservationResult> results = new VisibilityConstraintPlanner().calculate(start, pairResults,
                Collections.singletonList(new CombinationConstraint(Arrays.asList("A", "B"), 150.0)));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getArcs()).extracting(VisibilityArc::getDurationSec).containsExactly(200.0);
        assertThat(results.get(0).getArcs().get(0).getIlluminationSegments())
                .extracting(VisibilityArc.IlluminationSegment::getDurationSec).containsExactly(100.0, 100.0);
        assertThat(results.get(0).getArcs().get(0).getIlluminationSegments().get(0).getObserverIlluminations())
                .extracting(VisibilityArc.ObserverIllumination::getState)
                .containsOnly(IlluminationState.PENUMBRA.name());
        assertThat(results.get(0).getArcs().get(0).getIlluminationSegments().get(1).getObserverIlluminations())
                .extracting(VisibilityArc.ObserverIllumination::getState)
                .containsOnly(IlluminationState.UMBRA.name());
    }

    /**
     * 400 秒门槛的判断对象是连续共视窗口，而不是窗口内部因光照状态改变产生的子片段。
     * 主结果因此必须保持为 500 秒的一个合格窗口；状态切分只能作为该窗口的明细返回。
     */
    @Test
    void keepsOneQualifiedWindowWhenIlluminationChangesInsideIt() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        List<VisibilityPairResult> pairResults = Arrays.asList(
                new VisibilityPairResult("A", "TARGET", Arrays.asList(
                        illuminatedArc(start, 0.0, 499.9, "A", IlluminationState.UMBRA),
                        illuminatedArc(start, 499.9, 500.0, "A", IlluminationState.SUNLIT))),
                new VisibilityPairResult("B", "TARGET", Arrays.asList(
                        illuminatedArc(start, 0.0, 499.9, "B", IlluminationState.UMBRA),
                        illuminatedArc(start, 499.9, 500.0, "B", IlluminationState.SUNLIT))));

        List<VisibilityObservationResult> results = new VisibilityConstraintPlanner().calculate(start, pairResults,
                Collections.singletonList(new CombinationConstraint(Arrays.asList("A", "B"), 400.0)));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getArcs()).hasSize(1);
        assertThat(results.get(0).getArcs().get(0).getDurationSec()).isEqualTo(500.0);
        assertThat(results.get(0).getArcs().get(0).getIlluminationSegments()).hasSize(2);
        assertThat(results.get(0).getArcs().get(0).getIlluminationSegments().get(0).getDurationSec())
                .isCloseTo(499.9, within(0.000000001));
        assertThat(results.get(0).getArcs().get(0).getIlluminationSegments().get(1).getDurationSec())
                .isCloseTo(0.1, within(0.000000001));
    }

    /**
     * 多条观测约束在同一次规划中各自独立生效：同一组配对结果下，
     * 350 秒门槛的组合保留共视弧段，450 秒门槛的同组合无输出；
     * 单星约束弧段不携带各星独立时段；组合约束弧段携带覆盖共同窗口的原始单星连续时段。
     */
    @Test
    void evaluatesConstraintsIndependentlyAndExposesSegmentsOnlyForCombinations() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        List<VisibilityPairResult> pairResults = Arrays.asList(
                new VisibilityPairResult("A", "TARGET", Collections.singletonList(
                        illuminatedArc(start, 100.0, 600.0, "A", IlluminationState.UMBRA))),
                new VisibilityPairResult("B", "TARGET", Collections.singletonList(
                        illuminatedArc(start, 0.0, 500.0, "B", IlluminationState.SUNLIT))));

        List<VisibilityObservationResult> results = new VisibilityConstraintPlanner().calculate(start, pairResults,
                Arrays.asList(new CombinationConstraint(Arrays.asList("A", "B"), 350.0),
                        new CombinationConstraint(Arrays.asList("A", "B"), 450.0),
                        new CombinationConstraint(Collections.singletonList("A"), 100.0)));

        // A∩B = 100s..500s 共 400s：350 秒门槛组合有结果，450 秒门槛同组合无结果
        assertThat(results).hasSize(2);
        VisibilityObservationResult combination = results.stream()
                .filter(result -> result.getObserverSatelliteIds().size() == 2).findFirst().orElseThrow(java.util.NoSuchElementException::new);
        assertThat(combination.getArcs()).extracting(VisibilityArc::getDurationSec).containsExactly(400.0);
        assertThat(combination.getArcs().get(0).getSatelliteSegments())
                .extracting(VisibilityArc.SatelliteSegment::getSatelliteId)
                .containsExactly("A", "B");
        // 共同窗口为 A∩B 的 100s..500s，但展开明细必须保留每颗星真实的原始可观测范围。
        assertThat(combination.getArcs().get(0).getSatelliteSegments())
                .extracting(VisibilityArc.SatelliteSegment::getStartTime)
                .containsExactly(start.plusSeconds(100), start);
        assertThat(combination.getArcs().get(0).getSatelliteSegments())
                .extracting(VisibilityArc.SatelliteSegment::getEndTime)
                .containsExactly(start.plusSeconds(600), start.plusSeconds(500));

        // 单星约束正常输出，但弧段不携带 satelliteSegments（无可展开内容）
        VisibilityObservationResult single = results.stream()
                .filter(result -> result.getObserverSatelliteIds().size() == 1).findFirst().orElseThrow(java.util.NoSuchElementException::new);
        assertThat(single.getArcs()).extracting(VisibilityArc::getDurationSec).containsExactly(500.0);
        assertThat(single.getArcs()).allSatisfy(arc -> assertThat(arc.getSatelliteSegments()).isEmpty());
    }

    private VisibilityArc arc(Instant start, double startOffsetSec, double endOffsetSec) {
        return new VisibilityArc(start.plusNanos(Math.round(startOffsetSec * 1_000_000_000.0)),
                start.plusNanos(Math.round(endOffsetSec * 1_000_000_000.0)));
    }

    /** 创建带精确光照状态的单星观测弧段，用于验证组合阶段的状态切分。 */
    private VisibilityArc illuminatedArc(Instant start,
                                         double startOffsetSec,
                                         double endOffsetSec,
                                         String satelliteId,
                                         IlluminationState observerState) {
        return new VisibilityArc(start.plusNanos(Math.round(startOffsetSec * 1_000_000_000.0)),
                start.plusNanos(Math.round(endOffsetSec * 1_000_000_000.0)),
                Collections.singletonList(new VisibilityArc.ObserverIllumination(satelliteId, satelliteId, observerState)),
                new VisibilityArc.TargetIllumination("TARGET", "TARGET", IlluminationState.SUNLIT));
    }
}
