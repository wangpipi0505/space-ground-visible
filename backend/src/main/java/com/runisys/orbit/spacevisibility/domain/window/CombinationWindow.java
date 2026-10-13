package com.runisys.orbit.spacevisibility.domain.window;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 共同观测卫星集合及相对扫描起点的秒制区间；复制集合，保留窗口计算器产生的顺序。
 */
public final class CombinationWindow {

    private final List<String> satelliteIds;
    private final TimeInterval interval;

    public CombinationWindow(List<String> satelliteIds, TimeInterval interval) {
        this.satelliteIds = Collections.unmodifiableList(new ArrayList<String>(satelliteIds));
        this.interval = interval;
    }

    public List<String> getSatelliteIds() {
        return satelliteIds;
    }

    public TimeInterval getInterval() {
        return interval;
    }
}
