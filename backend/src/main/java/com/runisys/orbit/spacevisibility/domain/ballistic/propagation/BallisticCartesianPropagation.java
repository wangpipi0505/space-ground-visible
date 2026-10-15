package com.runisys.orbit.spacevisibility.domain.ballistic.propagation;

import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitDefinition;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitPropagationTool;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitState;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitTime;
import java.util.Locale;

/**
 * 从原拟合器抽出的自治二体 RK4；唯一计算主体同时服务区间外外推与事件搜索单步。
 * 使用原 J2000 坐标，米/米每秒；μ、1秒上限、1e-5短间隔阈值以及1e-9末段阈值不变。
 * 不处理观测点、插值或模拟专用动力学；普通Java可绑定真实绝对历元Cartesian定义。
 */
public final class BallisticCartesianPropagation implements OrbitPropagationTool.NativePropagation {
    private static final double EARTH_MU = 3.986004418e14;
    private static final double TIME_UNIQUE_EPS_SEC = 1e-5;
    private static final double TWO_BODY_MAX_STEP_SEC = 1.0;
    private final OrbitDefinition.Cartesian definition;

    private BallisticCartesianPropagation(OrbitDefinition.Cartesian definition) {
        this.definition = definition;
    }

    /** 不可变原积分状态，允许校验函数处理空值以保持原错误文本。 */
    public static final class State {
        public final Vector3 r;
        public final Vector3 v;
        /** 保存原J2000位置（米）和速度（米/秒），校验由原传播入口负责。 */
        public State(Vector3 r, Vector3 v) {
            this.r = r;
            this.v = v;
        }
    }

    /** 绑定原拟合二体算法，绝对查询间隔仍用 querySeconds-epochSeconds 直接计算。 */
    public static OrbitPropagationTool bind(OrbitDefinition.Cartesian definition) {
        return new OrbitPropagationTool(definition, new BallisticCartesianPropagation(definition));
    }

    /**
     * 兼容旧拟合内部仅有 initial+dt 的调用节点，不先把dt加到大绝对历元再相减。
     * 自治动力学不依赖绝对时刻，这里以零作相对时间锚点；零并非观测点的UTC历元。
     * 校验在构造工具定义前执行，使非有限dt及空/非法初始状态仍报原错误文本。
     */
    public static State propagateRelative(State initial, double dt) {
        if (!isFinite(dt)) {
            throw new IllegalArgumentException("二体RK4外推时间间隔无效，dt=" + formatDebugDouble(dt) + " 秒。");
        }
        validatePropagationState(initial, "二体RK4外推初始状态", dt);
        OrbitDefinition.Cartesian definition = new OrbitDefinition.Cartesian(OrbitTime.legacyJ2000(0.0, 0.0), initial.r, initial.v);
        OrbitState state = bind(definition).stateAt(OrbitTime.legacyJ2000(dt, 0.0), OrbitDefinition.Frame.J2000);
        return new State(state.positionM, state.velocityMps);
    }

    /** 仅位置查询；内部RK4必然同时推进速度，不触发第二次速度查询。 */
    @Override
    public Vector3 positionAt(OrbitTime time) {
        return propagate(time).r;
    }

    /** 返回原RK4积分速度，不以差分替换。 */
    @Override
    public Vector3 velocityAt(OrbitTime time) {
        return propagate(time).v;
    }

    /** 一次原积分返回完整PV，保留每步状态检查。 */
    @Override
    public OrbitState stateAt(OrbitTime time, OrbitDefinition.Frame frame) {
        State state = propagate(time);
        return new OrbitState(time, frame, state.r, state.v);
    }

    private State propagate(OrbitTime time) {
        return propagateTwoBody(new State(definition.positionM, definition.velocityMps),
                time.legacySeconds() - definition.epoch.legacySeconds());
    }

    /** 原二体外推：有限间隔、初始状态及每个中间状态按原次序检查，最大1秒。 */
    private static State propagateTwoBody(State initial, double dt) {
        if (!isFinite(dt)) {
            throw new IllegalArgumentException("二体RK4外推时间间隔无效，dt=" + formatDebugDouble(dt) + " 秒。");
        }
        validatePropagationState(initial, "二体RK4外推初始状态", dt);
        if (Math.abs(dt) <= TIME_UNIQUE_EPS_SEC) {
            return initial;
        }
        State state = initial;
        double remaining = dt;
        while (Math.abs(remaining) > 1e-9) {
            double h = Math.copySign(Math.min(TWO_BODY_MAX_STEP_SEC, Math.abs(remaining)), remaining);
            state = rk4TwoBodyStep(state, h);
            validatePropagationState(state, "二体RK4外推中间状态", dt);
            remaining -= h;
        }
        return state;
    }

    /** 原失败文本及半径检查，原样保留单位和小数格式。 */
    private static void validatePropagationState(State state, String stage, double dt) {
        if (state == null || state.r == null || state.v == null) {
            throw new IllegalStateException(stage + "为空，无法继续弹道外推。dt="
                    + formatDebugDouble(dt) + " 秒。");
        }
        if (!isFinite(state.r.getX()) || !isFinite(state.r.getY()) || !isFinite(state.r.getZ())
                || !isFinite(state.v.getX()) || !isFinite(state.v.getY()) || !isFinite(state.v.getZ())) {
            throw new IllegalStateException(stage + "数值无效，无法继续弹道外推。dt="
                    + formatDebugDouble(dt)
                    + " 秒，位置=("
                    + formatDebugDouble(state.r.getX()) + ", "
                    + formatDebugDouble(state.r.getY()) + ", "
                    + formatDebugDouble(state.r.getZ()) + ")，速度=("
                    + formatDebugDouble(state.v.getX()) + ", "
                    + formatDebugDouble(state.v.getY()) + ", "
                    + formatDebugDouble(state.v.getZ()) + ")。");
        }
        if (state.r.norm() <= 1.0) {
            throw new IllegalStateException(stage + "位置半径过小，无法继续弹道外推。dt="
                    + formatDebugDouble(dt)
                    + " 秒，位置半径=" + formatDebugDouble(state.r.norm()) + " m。");
        }
    }

    /** 原四阶RK4单步；事件求根和区间外外推共用此方法，不合并不同积分策略。 */
    public static State rk4TwoBodyStep(State state, double h) {
        State k1 = twoBodyDerivative(state);
        State k2 = twoBodyDerivative(addState(state, k1, h * 0.5));
        State k3 = twoBodyDerivative(addState(state, k2, h * 0.5));
        State k4 = twoBodyDerivative(addState(state, k3, h));

        Vector3 dr = k1.r.add(k2.r.scale(2.0)).add(k3.r.scale(2.0)).add(k4.r).scale(h / 6.0);
        Vector3 dv = k1.v.add(k2.v.scale(2.0)).add(k3.v.scale(2.0)).add(k4.v).scale(h / 6.0);
        return new State(state.r.add(dr), state.v.add(dv));
    }

    /** 原二体状态导数。 */
    private static State twoBodyDerivative(State state) {
        return new State(state.v, accelerationTwoBody(state.r));
    }

    /** 原中心引力，半径接近零时保持原零加速度分支。 */
    public static Vector3 accelerationTwoBody(Vector3 position) {
        double r2 = position.dot(position);
        double r = Math.sqrt(r2);
        if (r <= 1e-9) {
            return zero();
        }
        double factor = -EARTH_MU / (r2 * r);
        return position.scale(factor);
    }

    /** 原RK4中间状态。 */
    private static State addState(State state, State derivative, double scale) {
        return new State(state.r.add(derivative.r.scale(scale)), state.v.add(derivative.v.scale(scale)));
    }

    /** 原有限数判断。 */
    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    /** 原调试数字格式，保留NaN/Infinity和六位小数。 */
    private static String formatDebugDouble(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (Double.isInfinite(value)) {
            return value > 0.0 ? "Infinity" : "-Infinity";
        }
        return String.format(Locale.US, "%.6f", value);
    }

    /** 原零向量。 */
    private static Vector3 zero() {
        return new Vector3(0.0, 0.0, 0.0);
    }
}
