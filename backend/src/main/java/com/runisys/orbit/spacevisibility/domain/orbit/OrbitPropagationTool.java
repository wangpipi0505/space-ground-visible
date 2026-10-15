package com.runisys.orbit.spacevisibility.domain.orbit;

import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import java.util.Objects;

/**
 * 普通 Java8 可调用的轨道外推入口，无 Spring、实体、HTTP 或文件 UI 依赖。
 * 调用者以对应定义绑定原实现，适配器必须使用定义中同一组参数/模型/时标/frame。
 * 本类不自动选择或替换模型，不提供插件注册、积分或坐标转换；异常原样传递。
 * 工具不维护传播缓存；并发能力沿用绑定实现（空间 provider 自身按线程隔离）。
 */
public final class OrbitPropagationTool {
    /** 原生传播适配边界，输出遵守绑定定义的坐标系及 SI 单位。 */
    public interface NativePropagation {
        /** 原生位置查询，单位米；应仅调用旧实现的位置路径。 */
        Vector3 positionAt(OrbitTime time);
        /** 原生速度查询，单位米/秒；空间适配直接委托原 velocityAt，保留差分顺序。 */
        Vector3 velocityAt(OrbitTime time);
        /** 默认按原位置、速度顺序查询；原生积分同时得到PV的适配器可覆盖以避免重复积分。 */
        default OrbitState stateAt(OrbitTime time, OrbitDefinition.Frame frame) {
            return new OrbitState(time, frame, positionAt(time), velocityAt(time));
        }
    }

    private final OrbitDefinition definition;
    private final NativePropagation propagation;

    /** 绑定必填轨道定义与其原生实现；不会提前传播或预热缓存。 */
    public OrbitPropagationTool(OrbitDefinition definition, NativePropagation propagation) {
        this.definition = Objects.requireNonNull(definition, "轨道定义不能为空");
        this.propagation = Objects.requireNonNull(propagation, "原生传播器不能为空");
    }

    /** 返回绑定的不可变参数与模型标签，供调用方确认单位、历元和坐标口径。 */
    public OrbitDefinition definition() {
        return definition;
    }

    /**
     * 仅查询位置（米），不额外计算速度，保证旧窗口扫描的调用次数和缓存顺序不变。
     * @param time 原生时刻，必填，旧 double 不经 Instant 转换
     * @param outputFrame 所需输出坐标系，必须等于原实现 frame；本工具不隐式转换
     */
    public Vector3 positionAt(OrbitTime time, OrbitDefinition.Frame outputFrame) {
        validateQuery(time, outputFrame);
        return propagation.positionAt(time);
    }

    /** 仅查询速度（米/秒），保留原速度算法；空间原默认中心差分仍由 provider 执行。 */
    public Vector3 velocityAt(OrbitTime time, OrbitDefinition.Frame outputFrame) {
        validateQuery(time, outputFrame);
        return propagation.velocityAt(time);
    }

    /**
     * 显式查询完整状态；默认依次调用位置、速度各一次，原生PV模型可一次积分。
     * 旧空间速度内部的位置 ±1秒查询保持原有次序，不替换为传播器解析速度。
     */
    public OrbitState stateAt(OrbitTime time, OrbitDefinition.Frame outputFrame) {
        validateQuery(time, outputFrame);
        return propagation.stateAt(time, outputFrame);
    }

    private void validateQuery(OrbitTime time, OrbitDefinition.Frame outputFrame) {
        Objects.requireNonNull(time, "外推时刻不能为空").requireBasis(definition.timeBasis);
        if (outputFrame != definition.outputFrame) {
            throw new IllegalArgumentException("坐标口径不匹配，原生输出为" + definition.outputFrame
                    + "，请求为" + outputFrame + "；请显式使用坐标转换适配");
        }
        if (time.basis == OrbitTime.Basis.LEGACY_J2000_SECONDS && time.utcOffsetSec() != definition.utcOffsetSec) {
            throw new IllegalArgumentException("utcOffsetSec与轨道定义不一致");
        }
    }
}
