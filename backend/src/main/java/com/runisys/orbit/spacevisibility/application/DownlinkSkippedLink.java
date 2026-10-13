package com.runisys.orbit.spacevisibility.application;

/** 计算期发生异常而被跳过的一条卫星—地面站下传链路。 */
public final class DownlinkSkippedLink {

    public enum Stage {
        SCAN
    }

    private final Long satelliteId;
    private final String stationId;
    private final Stage stage;
    private final String reason;

    public DownlinkSkippedLink(Long satelliteId, String stationId, Stage stage, String reason) {
        if (satelliteId == null || stationId == null || stage == null || reason == null) {
            throw new IllegalArgumentException("跳过的下传链路参数无效");
        }
        this.satelliteId = satelliteId;
        this.stationId = stationId;
        this.stage = stage;
        this.reason = reason;
    }

    public Long getSatelliteId() {
        return satelliteId;
    }

    public String getStationId() {
        return stationId;
    }

    public Stage getStage() {
        return stage;
    }

    public String getReason() {
        return reason;
    }
}
