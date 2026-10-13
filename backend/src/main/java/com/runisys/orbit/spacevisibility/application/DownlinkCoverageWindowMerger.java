package com.runisys.orbit.spacevisibility.application;

import com.runisys.orbit.spacevisibility.api.SpaceApiModels;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将多条站星链路的下传窗口归并为可供任务选择的连续覆盖窗口。
 *
 * <p>单链路窗口在下传规划器中已经应用前后保护与该链路自己的允许断链策略；本类不重复
 * 解释这些规则。这里仅回答一个更高层的问题：在多个卫星或地面站之间切换时，整个任务
 * 时间段是否始终有至少一条可用链路。为避免把实际断链伪装成连续服务，只合并重叠或首尾
 * 相接的窗口，哪怕间隔只有一毫秒也会保留为两个独立覆盖窗口。</p>
 */
public final class DownlinkCoverageWindowMerger {

    private DownlinkCoverageWindowMerger() {
    }

    /**
     * 构造全部原始链路窗口的连续覆盖并集，并为每段结果保留唯一的贡献链路。
     *
     * @param pairWindows 每个卫星—地面站关系的稳定窗口
     * @return 按开始时刻升序排列的连续覆盖窗口
     */
    public static List<SpaceApiModels.CoverageWindowResponse> merge(
            List<SpaceApiModels.SatelliteStationWindowResponse> pairWindows) {
        List<SourceWindow> source = flatten(pairWindows);
        source.sort(Comparator.comparing(SourceWindow::getStartTime).thenComparing(SourceWindow::getEndTime));
        List<SpaceApiModels.CoverageWindowResponse> result = new ArrayList<SpaceApiModels.CoverageWindowResponse>();
        if (source.isEmpty()) {
            return result;
        }

        Instant currentStart = source.get(0).getStartTime();
        Instant currentEnd = source.get(0).getEndTime();
        Map<String, SpaceApiModels.SatelliteStationLinkResponse> currentLinks = new LinkedHashMap<String, SpaceApiModels.SatelliteStationLinkResponse>();
        addLink(currentLinks, source.get(0));
        for (int index = 1; index < source.size(); index++) {
            SourceWindow next = source.get(index);
            if (!next.getStartTime().isAfter(currentEnd)) {
                if (next.getEndTime().isAfter(currentEnd)) {
                    currentEnd = next.getEndTime();
                }
                addLink(currentLinks, next);
                continue;
            }
            result.add(new SpaceApiModels.CoverageWindowResponse(currentStart, currentEnd,
                    new ArrayList<SpaceApiModels.SatelliteStationLinkResponse>(currentLinks.values())));
            currentStart = next.getStartTime();
            currentEnd = next.getEndTime();
            currentLinks.clear();
            addLink(currentLinks, next);
        }
        result.add(new SpaceApiModels.CoverageWindowResponse(currentStart, currentEnd,
                new ArrayList<SpaceApiModels.SatelliteStationLinkResponse>(currentLinks.values())));
        return result;
    }

    /** 将响应模型压平为可排序的原子窗口，同时跳过空链路或空窗口列表。 */
    private static List<SourceWindow> flatten(List<SpaceApiModels.SatelliteStationWindowResponse> pairWindows) {
        List<SourceWindow> result = new ArrayList<SourceWindow>();
        if (pairWindows == null) {
            return result;
        }
        for (SpaceApiModels.SatelliteStationWindowResponse pairWindow : pairWindows) {
            if (pairWindow == null || pairWindow.getWindows() == null) {
                continue;
            }
            for (SpaceApiModels.WindowResponse window : pairWindow.getWindows()) {
                if (window != null) {
                    result.add(new SourceWindow(pairWindow.getSatelliteId(), pairWindow.getStationId(),
                            window.getStartTime(), window.getEndTime()));
                }
            }
        }
        return result;
    }

    /** 使用复合键去重贡献链路，同时保留按首次出现顺序展示的可读性。 */
    private static void addLink(Map<String, SpaceApiModels.SatelliteStationLinkResponse> links, SourceWindow window) {
        String key = String.valueOf(window.getSatelliteId()) + '\u0000' + String.valueOf(window.getStationId());
        links.putIfAbsent(key, new SpaceApiModels.SatelliteStationLinkResponse(window.getSatelliteId(), window.getStationId()));
    }

    /** 归并实现内部使用的不可变原子窗口。 */
    private static final class SourceWindow {
        private final Long satelliteId;
        private final String stationId;
        private final Instant startTime;
        private final Instant endTime;

        private SourceWindow(Long satelliteId, String stationId, Instant startTime, Instant endTime) {
            this.satelliteId = satelliteId;
            this.stationId = stationId;
            this.startTime = startTime;
            this.endTime = endTime;
        }

        private Long getSatelliteId() { return satelliteId; }
        private String getStationId() { return stationId; }
        private Instant getStartTime() { return startTime; }
        private Instant getEndTime() { return endTime; }
    }
}
