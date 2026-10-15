package com.runisys.orbit.spacevisibility.api.ballistic;

import com.runisys.orbit.spacevisibility.domain.ballistic.TargetTrajectoryFitter;

/**
 * 拟合、模拟及重定时 HTTP 请求。继承领域输入的公开字段和默认值，Jackson 直接绑定继承字段，
 * 避免重复维护两份字段定义；应用服务接收父类型，领域输入不依赖 HTTP。
 * 原 JSON 字段、别名、单位和显式 null 语义保持。
 */
public final class BallisticTrajectoryRequests {
    private BallisticTrajectoryRequests() {
    }

    /** 102 包拟合请求；时间为 UTC，坐标及单位按原始观测声明解释。 */
    public static final class FitRequest extends TargetTrajectoryFitter.FitInput {
    }

    /** 系统模拟请求；端点、阶段事件和积分参数沿用领域输入的字段及单位。 */
    public static final class SimulationRequest extends TargetTrajectoryFitter.SimulationInput {
    }

    /** 重定时请求；UTC 时间列和源坐标按既有文件规则处理。 */
    public static final class RetimeRequest extends TargetTrajectoryFitter.RetimeInput {
    }
}
