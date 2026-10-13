package com.runisys.orbit.spacevisibility.application;

import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import com.runisys.orbit.spacevisibility.domain.payload.PayloadVisibilityEvaluator;
import com.runisys.orbit.spacevisibility.domain.payload.VisibilitySample;

import java.time.Instant;

/**
 * 执行单个采样时刻的观测判定。
 * 将轨道状态读取、观测星速度差分、相邻采样视线构造与载荷/光照判定隔离，窗口扫描只关心返回的可见状态。
 */
final class ObservationSampleEvaluator {

    /**
     * 读取观测星和目标星的轨道状态，以位置中心差分估计观测星速度，构造前一采样时刻的视线，
     * 并委托领域载荷判定器生成采样结果。
     *
     * @param observer 我方观测星及载荷约束
     * @param target 被观测空间目标
     * @param observerPositionCache 当前规划内的观测星位置缓存
     * @param instant 当前采样时刻
     * @param previousSampleElapsedSec 前一采样间隔，单位秒
     * @return 当前时刻的几何可见性、视线角速度与双方光照状态
     */
    VisibilitySample evaluate(VisibilityObserver observer,
                              VisibilityTarget target,
                              OrbitPositionCache observerPositionCache,
                              OrbitPositionCache targetPositionCache,
                              Instant instant,
                              double previousSampleElapsedSec) {
        return evaluate(observer, target, observerPositionCache, targetPositionCache, instant,
                previousSampleElapsedSec,
                com.runisys.orbit.spacevisibility.domain.payload.SolarGeometry.DEFAULT_SUN_ANGULAR_RADIUS_DEG);
    }

    /** 按本次规划指定的太阳视半径计算采样结果，保证目标观测与下传使用同一光照口径。 */
    VisibilitySample evaluate(VisibilityObserver observer,
                              VisibilityTarget target,
                              OrbitPositionCache observerPositionCache,
                              OrbitPositionCache targetPositionCache,
                              Instant instant,
                              double previousSampleElapsedSec,
                              double sunAngularRadiusDeg) {
        Vector3 observerPosition = observerPositionCache.positionAt(observer.getOrbit(), instant);
        Vector3 targetPosition = targetPositionCache.positionAt(target.getOrbit(), instant);
        Vector3 observerVelocity = observerPositionCache.positionAt(observer.getOrbit(), instant.plusSeconds(1))
                .subtract(observerPositionCache.positionAt(observer.getOrbit(), instant.minusSeconds(1)))
                .scale(0.5);
        Instant previousInstant = instant.minusNanos(Math.round(previousSampleElapsedSec * 1_000_000_000.0));
        Vector3 previousLineOfSight = targetPositionCache.positionAt(target.getOrbit(), previousInstant)
                .subtract(observerPositionCache.positionAt(observer.getOrbit(), previousInstant));
        return PayloadVisibilityEvaluator.evaluate(observerPosition, observerVelocity, targetPosition,
                observer.getPayload(), instant, previousLineOfSight, previousSampleElapsedSec, sunAngularRadiusDeg);
    }
}
