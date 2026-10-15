package com.runisys.orbit.spacevisibility.application.space;

import com.runisys.orbit.spacevisibility.domain.space.downlink.DownlinkPairResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一次下传规划的正常窗口与异常链路清单。 */
public final class DownlinkPlanningOutcome {

    private final List<DownlinkPairResult> results;
    private final List<DownlinkSkippedLink> skippedLinks;

    public DownlinkPlanningOutcome(List<DownlinkPairResult> results,
                                   List<DownlinkSkippedLink> skippedLinks) {
        this.results = Collections.unmodifiableList(new ArrayList<DownlinkPairResult>(results));
        this.skippedLinks = Collections.unmodifiableList(new ArrayList<DownlinkSkippedLink>(skippedLinks));
    }

    public List<DownlinkPairResult> getResults() {
        return results;
    }

    public List<DownlinkSkippedLink> getSkippedLinks() {
        return skippedLinks;
    }
}
