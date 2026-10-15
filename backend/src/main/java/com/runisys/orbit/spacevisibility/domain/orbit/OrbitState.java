package com.runisys.orbit.spacevisibility.domain.orbit;

import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;

/** 不可变外推状态，显式携带原生时刻、坐标标签及 SI 单位；不执行任何缩放或转换。 */
public final class OrbitState {
    /** 实际查询时刻，与输入对象相同，保留全部原生精度。 */
    public final OrbitTime time;
    /** 位置和速度共同使用的原实现坐标系。 */
    public final OrbitDefinition.Frame frame;
    /** 位置，米。 */
    public final Vector3 positionM;
    /** 速度，米/秒；空间默认仍由原 provider 的 ±1秒中心差分产生。 */
    public final Vector3 velocityMps;

    /** 公共工具和各业务传播适配器在时间/frame校验后包装原返回值，不执行数值转换。 */
    public OrbitState(OrbitTime time, OrbitDefinition.Frame frame, Vector3 positionM, Vector3 velocityMps) {
        this.time = time;
        this.frame = frame;
        this.positionM = positionM;
        this.velocityMps = velocityMps;
    }
}
