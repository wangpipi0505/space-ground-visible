package com.runisys.orbit.spacevisibility.application;

import com.runisys.orbit.spacevisibility.domain.orbit.OrbitStateProvider;
import com.runisys.orbit.spacevisibility.domain.orbit.SpaceOrbitStateProvider;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.SatelliteEntity;
import com.runisys.orbit.spacevisibility.infrastructure.persistence.entity.TargetEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;

/**
 * 把空间持久化实体转换为普通领域参数并接入公共轨道传播工具。
 * BigDecimal 的原 doubleValue 顺序及 LocalDateTime 按 UTC 转 Instant 保留在应用层；
 * 返回值仍为旧 OrbitStateProvider，窗口扫描和应用调用签名无需改变。
 */
@Component
public class OrbitStateProviderFactory {

    /** 原应用 RK4 配置步长，秒；默认10秒，合法性继续由原传播器校验。 */
    private final double keplerRk4StepSec;

    /** 接收原应用配置，不提前校验，以保留 TLE 路径不受六根数步长影响的旧行为。 */
    public OrbitStateProviderFactory(@Value("${space.visibility.orbit.kepler-rk4-step-sec:10}") double keplerRk4StepSec) {
        this.keplerRk4StepSec = keplerRk4StepSec;
    }

    /**
     * 将我方卫星表记录转换为可按时刻外推位置的轨道提供器，支持 TLE_SGP4 和 KEPLER_6 两种模型。
     *
     * @param entity 我方卫星持久化实体
     * @return 对应轨道外推器
     */
    public OrbitStateProvider fromSatellite(SatelliteEntity entity) {
        return create(entity.getModelType(), entity.getFirstOrbit(), entity.getSecondOrbit(),
                entity.getSemiMajorAxis(), entity.getEccentricity(), entity.getInclination(),
                entity.getRaan(), entity.getArgumentOfPerigee(), entity.getMeanAnomaly(), entity.getEpochTime());
    }

    /**
     * 将空间目标表记录转换为可按时刻外推位置的轨道提供器。
     *
     * @param entity 空间目标持久化实体
     * @return 对应轨道外推器
     */
    public OrbitStateProvider fromTarget(TargetEntity entity) {
        return create(entity.getModelType(), entity.getFirstOrbit(), entity.getSecondOrbit(),
                entity.getSemiMajorAxis(), entity.getEccentricity(), entity.getInclination(),
                entity.getRaan(), entity.getArgumentOfPerigee(), entity.getMeanAnomaly(), entity.getEpochTime());
    }

    /**
     * 根据轨道模型选择 TLE SGP4 或六根数开普勒外推，并在所需字段缺失时尽早返回明确输入错误。
     *
     * @param modelType 轨道模型标识
     * @param firstOrbit TLE 第一行
     * @param secondOrbit TLE 第二行
     * @param semiMajorAxis 半长轴（米）
     * @param eccentricity 偏心率
     * @param inclination 轨道倾角（度）
     * @param raan 升交点赤经（度）
     * @param argumentOfPerigee 近地点幅角（度）
     * @param meanAnomaly 平近点角（度）
     * @param epochTime 六根数历元（UTC）
     * @return 轨道外推器
     */
    private OrbitStateProvider create(String modelType,
                                      String firstOrbit,
                                      String secondOrbit,
                                      java.math.BigDecimal semiMajorAxis,
                                      java.math.BigDecimal eccentricity,
                                      java.math.BigDecimal inclination,
                                      java.math.BigDecimal raan,
                                      java.math.BigDecimal argumentOfPerigee,
                                      java.math.BigDecimal meanAnomaly,
                                      java.time.LocalDateTime epochTime) {
        if ("TLE_SGP4".equalsIgnoreCase(modelType)) {
            return SpaceOrbitStateProvider.fromTle(firstOrbit, secondOrbit);
        }
        if (!"KEPLER_6".equalsIgnoreCase(modelType) || semiMajorAxis == null || eccentricity == null
                || inclination == null || raan == null || argumentOfPerigee == null || meanAnomaly == null
                || epochTime == null) {
            throw new IllegalArgumentException("轨道模型或轨道参数无效");
        }
        return SpaceOrbitStateProvider.fromElements(
                semiMajorAxis.doubleValue(), eccentricity.doubleValue(), inclination.doubleValue(),
                raan.doubleValue(), argumentOfPerigee.doubleValue(), meanAnomaly.doubleValue(),
                epochTime.toInstant(ZoneOffset.UTC), keplerRk4StepSec);
    }
}
