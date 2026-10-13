package com.runisys.orbit.spacevisibility.application;

import com.runisys.orbit.spacevisibility.domain.orbit.OrbitStateProvider;
import com.runisys.orbit.spacevisibility.domain.payload.PayloadConstraint;

/**
 * 观测星的计算定义，聚合字符串身份、轨道provider与有效载荷约束。
 * 应用层从实体构造；扫描器只查询该定义，不跨请求改写实体或根数。
 */
public final class VisibilityObserver {

    private final String satelliteId;
    private final OrbitStateProvider orbit;
    private final PayloadConstraint payload;

    public VisibilityObserver(String satelliteId, OrbitStateProvider orbit, PayloadConstraint payload) {
        this.satelliteId = satelliteId;
        this.orbit = orbit;
        this.payload = payload;
    }

    public String getSatelliteId() { return satelliteId; }
    public OrbitStateProvider getOrbit() { return orbit; }
    public PayloadConstraint getPayload() { return payload; }
}
