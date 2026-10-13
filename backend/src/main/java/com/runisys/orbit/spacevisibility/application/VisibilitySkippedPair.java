package com.runisys.orbit.spacevisibility.application;

/**
 * 一个未能完成的观测星—目标组合。
 *
 * <p>失败按组合而不是按目标记录，避免某目标只对部分观测星不可算时丢失可用结果。</p>
 */
public final class VisibilitySkippedPair {

    public enum Stage {
        PRECHECK,
        SCAN
    }

    private final String observerSatelliteId;
    private final String targetId;
    private final Stage stage;
    private final String reason;

    public VisibilitySkippedPair(String observerSatelliteId, String targetId, Stage stage, String reason) {
        if (observerSatelliteId == null || targetId == null || stage == null || reason == null) {
            throw new IllegalArgumentException("跳过的可见性规划组合参数无效");
        }
        this.observerSatelliteId = observerSatelliteId;
        this.targetId = targetId;
        this.stage = stage;
        this.reason = reason;
    }

    public String getObserverSatelliteId() {
        return observerSatelliteId;
    }

    public String getTargetId() {
        return targetId;
    }

    public Stage getStage() {
        return stage;
    }

    public String getReason() {
        return reason;
    }
}
