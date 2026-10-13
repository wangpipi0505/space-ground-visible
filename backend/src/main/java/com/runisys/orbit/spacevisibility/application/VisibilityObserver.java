package com.runisys.orbit.spacevisibility.application;

import com.runisys.orbit.spacevisibility.domain.orbit.OrbitStateProvider;
import com.runisys.orbit.spacevisibility.domain.payload.PayloadConstraint;

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
