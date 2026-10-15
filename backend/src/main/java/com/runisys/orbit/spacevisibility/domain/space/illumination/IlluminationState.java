package com.runisys.orbit.spacevisibility.domain.space.illumination;

/**
 * 原空间光照状态标签，供筛选/窗口分段；具体太阳和本影判据由SolarGeometry执行。
 */
public enum IlluminationState {
    SUNLIT,
    PENUMBRA,
    UMBRA
}
