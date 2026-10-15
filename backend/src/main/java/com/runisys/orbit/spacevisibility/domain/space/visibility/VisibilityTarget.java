package com.runisys.orbit.spacevisibility.domain.space.visibility;

import com.runisys.orbit.spacevisibility.domain.orbit.OrbitStateProvider;

/**
 * 目标的字符串身份及原轨道provider定义，由应用层创建，单位与坐标遵守provider合同。
 */
public final class VisibilityTarget {

    private final String targetId;
    private final OrbitStateProvider orbit;

    public VisibilityTarget(String targetId, OrbitStateProvider orbit) {
        this.targetId = targetId;
        this.orbit = orbit;
    }

    public String getTargetId() { return targetId; }
    public OrbitStateProvider getOrbit() { return orbit; }
}
