package com.runisys.orbit.spacevisibility.application.space;

import com.runisys.orbit.spacevisibility.domain.space.visibility.VisibilityPairResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次空间目标可见性规划的完整计算结果。
 *
 * <p>正常组合与被跳过组合分开保存：单个目标传播失败时，调用方仍可使用其他组合的结果，
 * 同时能够获得可审计的失败范围与原因。</p>
 */
public final class VisibilityPlanningOutcome {

    private final List<VisibilityPairResult> results;
    private final List<VisibilitySkippedPair> skippedPairs;

    public VisibilityPlanningOutcome(List<VisibilityPairResult> results,
                                     List<VisibilitySkippedPair> skippedPairs) {
        this.results = Collections.unmodifiableList(new ArrayList<VisibilityPairResult>(results));
        this.skippedPairs = Collections.unmodifiableList(new ArrayList<VisibilitySkippedPair>(skippedPairs));
    }

    public List<VisibilityPairResult> getResults() {
        return results;
    }

    public List<VisibilitySkippedPair> getSkippedPairs() {
        return skippedPairs;
    }
}
