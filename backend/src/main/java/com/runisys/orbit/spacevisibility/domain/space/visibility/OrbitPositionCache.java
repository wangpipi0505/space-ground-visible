package com.runisys.orbit.spacevisibility.domain.space.visibility;

import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitStateProvider;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 缓存单次观测规划中同一我方卫星的轨道位置。
 * 缓存按访问顺序淘汰，生命周期仅限当前规划调用，避免全量目标遍历时重复轨道外推且不跨请求占用内存。
 */
public final class OrbitPositionCache {

    private final int capacity;
    private final Map<Instant, Vector3> positions;

    /** 创建单次观测计算内使用的缓存；capacity为允许保留的时刻数，必须大于零。 */
    public OrbitPositionCache(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("观测星轨道位置缓存容量必须大于 0");
        }
        this.capacity = capacity;
        this.positions = new LinkedHashMap<Instant, Vector3>(capacity + 1, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Instant, Vector3> eldest) {
                return size() > OrbitPositionCache.this.capacity;
            }
        };
    }

    /**
     * 返回指定时刻的位置；缓存未命中时只由一个并发调用执行轨道外推。
     *
     * @param orbit 我方观测星轨道
     * @param instant 待外推时刻
     * @return ECI 位置
     */
    public synchronized Vector3 positionAt(OrbitStateProvider orbit, Instant instant) {
        Vector3 position = positions.get(instant);
        if (position != null) {
            return position;
        }
        position = orbit.positionAt(instant);
        positions.put(instant, position);
        return position;
    }
}
