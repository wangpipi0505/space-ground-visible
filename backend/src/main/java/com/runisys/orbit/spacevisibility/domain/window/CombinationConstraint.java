package com.runisys.orbit.spacevisibility.domain.window;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 共同观测卫星集合与最小持续时长（秒），复制有序ID且拒绝重复/空身份，访问器仅读取。
 */
public final class CombinationConstraint {

    private final List<String> satelliteIds;
    private final double minDurationSec;

    public CombinationConstraint(List<String> satelliteIds, double minDurationSec) {
        if (satelliteIds == null || satelliteIds.isEmpty() || minDurationSec < 0.0) {
            throw new IllegalArgumentException("组合卫星或最小时长无效");
        }
        LinkedHashSet<String> uniqueIds = new LinkedHashSet<String>(satelliteIds);
        if (uniqueIds.size() != satelliteIds.size() || uniqueIds.contains(null) || uniqueIds.contains("")) {
            throw new IllegalArgumentException("组合卫星标识无效");
        }
        this.satelliteIds = Collections.unmodifiableList(new ArrayList<String>(satelliteIds));
        this.minDurationSec = minDurationSec;
    }

    public List<String> getSatelliteIds() {
        return satelliteIds;
    }

    public double getMinDurationSec() {
        return minDurationSec;
    }
}
