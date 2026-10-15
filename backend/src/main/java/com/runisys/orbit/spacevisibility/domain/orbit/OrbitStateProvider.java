package com.runisys.orbit.spacevisibility.domain.orbit;

import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import java.time.Instant;

/**
 * 轨道状态提供器：对外只暴露按时刻获取轨道状态的能力，是轨道外推与窗口计算之间的唯一接口。
 * 窗口扫描（domain.window）只消费本接口返回的位置/速度，不感知 SGP4、数值积分等具体外推实现；
 * 其他功能需要轨道外推时，直接以 TLE 两行根数或历元六根数构造具体实现类即可独立使用。
 *
 * 坐标系统一约定：所有实现输出的位置/速度均为 TEME 系（真赤道平春分点 of-date 系）。
 * TLE/SGP4 原生输出即 TEME；六根数数值积分在 EME2000 惯性系计算后转换到 TEME。
 * 该约定与站心几何的 GMST 旋转、太阳方向解析模型（均为 of-date 系）配套。
 */
public interface OrbitStateProvider {

    /**
     * 返回指定时刻的 TEME 系位置。
     *
     * @param instant 外推时刻（UTC）
     * @return TEME 位置，单位米
     */
    Vector3 positionAt(Instant instant);

    /**
     * 返回指定时刻的 TEME 系速度。默认实现用位置中心差分（±1 秒），精度满足方位/俯仰等
     * 几何约束计算；具体外推实现可覆盖本方法直接给出解析速度。
     *
     * @param instant 外推时刻（UTC）
     * @return TEME 速度，单位米/秒
     */
    default Vector3 velocityAt(Instant instant) {
        Vector3 before = positionAt(instant.minusSeconds(1));
        Vector3 after = positionAt(instant.plusSeconds(1));
        return after.subtract(before).scale(0.5);
    }
}
