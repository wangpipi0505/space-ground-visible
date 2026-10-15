package com.runisys.orbit.spacevisibility.domain.ballistic.propagation;

import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitDefinition;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitPropagationTool;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitState;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitTime;

/**
 * 弹道卫星原传播计算的唯一实现，源自冻结 OrbitCalculator 的三种模型。
 * 普通 Java 可直接用六根数定义绑定公共工具；不依赖旧业务 DTO、Spring 或文件解析。
 * 每次查询从不可变定义重新计算，没有历史缓存或全局参考历元，实例可独立并发使用。
 * 时间为旧 UTC 日历 J2000 秒；坐标为原 J2000，位置米、速度米/秒。
 */
public final class BallisticOrbitPropagation implements OrbitPropagationTool.NativePropagation {
    private static final double EARTH_GRAVITATIONAL_PARAMETER = 3.986004418e14;
    private static final double EARTH_EQUATORIAL_RADIUS_M = 6378137.0;
    private static final double EARTH_MEAN_RADIUS_M = 6371008.8;
    private static final double EARTH_J2 = 1.08262668e-3;
    private static final double NUMERICAL_J2_MAX_STEP_SEC = 30.0;
    private final OrbitDefinition.Elements elements;

    private BallisticOrbitPropagation(OrbitDefinition.Elements elements) {
        this.elements = elements;
    }

    /** 绑定实际原实现；只接受弹道三种根数模型，不隐式替换模型。 */
    public static OrbitPropagationTool bind(OrbitDefinition.Elements elements) {
        if (elements == null || (elements.model != OrbitDefinition.Model.TWO_BODY
                && elements.model != OrbitDefinition.Model.J2_SECULAR
                && elements.model != OrbitDefinition.Model.J2_NUMERICAL)) {
            throw new IllegalArgumentException("需要弹道三种模型的六根数定义");
        }
        return new OrbitPropagationTool(elements, new BallisticOrbitPropagation(elements));
    }

    /**
     * 仅供旧公有入口保持非有限时间的历史行为；普通工具仍坚持有限时间契约。
     * 旧实现没有验证t/epoch，NaN可能产生NaN位置（数值模型可能返回初始状态）。
     * 这个兼容分支仍调用同一个抽取内核，绝不创建第二套算法或修改新工具的校验。
     */
    public static Vector3 legacyNonFinitePosition(OrbitDefinition.Elements elements, double tSec, double epochSec) {
        if (elements.model == OrbitDefinition.Model.J2_SECULAR) {
            return propagateJ2Secular(elements, tSec, epochSec);
        }
        if (elements.model == OrbitDefinition.Model.J2_NUMERICAL) {
            return propagateJ2Numerical(elements, tSec, epochSec).position;
        }
        return propagateTwoBody(elements, tSec, epochSec);
    }

    /** 只计算位置；旧窗口扫描不会为了工具状态额外调用速度。 */
    @Override
    public Vector3 positionAt(OrbitTime time) {
        double seconds = time.legacySeconds();
        if (elements.model == OrbitDefinition.Model.J2_SECULAR) {
            return propagateJ2Secular(elements, seconds, elements.epoch.legacySeconds());
        }
        if (elements.model == OrbitDefinition.Model.J2_NUMERICAL) {
            return propagateJ2Numerical(elements, seconds, elements.epoch.legacySeconds()).position;
        }
        return propagateTwoBody(elements, seconds, elements.epoch.legacySeconds());
    }

    /**
     * 数值 J2 返回原 RK4 状态速度；解析二体和长期 J2 旧接口没有速度，新增工具明确
     * 用位置 t+1秒减t-1秒后乘0.5的中心差分，不能称为旧解析速度契约。
     */
    @Override
    public Vector3 velocityAt(OrbitTime time) {
        if (elements.model == OrbitDefinition.Model.J2_NUMERICAL) {
            return propagateJ2Numerical(elements, time.legacySeconds(), elements.epoch.legacySeconds()).velocity;
        }
        Vector3 after = positionAt(OrbitTime.legacyJ2000(time.legacySeconds() + 1.0, elements.utcOffsetSec));
        Vector3 before = positionAt(OrbitTime.legacyJ2000(time.legacySeconds() - 1.0, elements.utcOffsetSec));
        return after.subtract(before).scale(0.5);
    }

    /** 数值模型一次积分返回完整状态；解析模型仍按上述明确差分约定提供速度。 */
    @Override
    public OrbitState stateAt(OrbitTime time, OrbitDefinition.Frame frame) {
        if (elements.model == OrbitDefinition.Model.J2_NUMERICAL) {
            StateVector state = propagateJ2Numerical(elements, time.legacySeconds(), elements.epoch.legacySeconds());
            return new OrbitState(time, frame, state.position, state.velocity);
        }
        return OrbitPropagationTool.NativePropagation.super.stateAt(time, frame);
    }

    /** 积分内部不可变位置速度对，避免任何业务类型进入计算工具。 */
    public static final class StateVector {
        public final Vector3 position;
        public final Vector3 velocity;
        /** 原积分状态，位置米、速度米/秒。 */
        public StateVector(Vector3 position, Vector3 velocity) {
            this.position = position;
            this.velocity = velocity;
        }
    }

    /** 原二体开普勒位置；dt 直接由旧 double 相减，保留平近点角归一化和迭代顺序。 */
    private static Vector3 propagateTwoBody(OrbitDefinition.Elements elements, double tSec, double epochSec) {
        validateOrbit(elements);
        double dt = tSec - epochSec;

        double a = elements.semiMajorAxisM;
        double e = elements.eccentricity;
        double inclination = radians(elements.inclinationDeg);
        double raan = radians(elements.raanDeg);
        double argumentOfPerigee = radians(elements.argumentOfPerigeeDeg);
        double meanAnomaly0 = normalizeRadians(radians(elements.meanAnomalyDeg));
        double meanMotion = Math.sqrt(EARTH_GRAVITATIONAL_PARAMETER / (a * a * a));
        double meanAnomaly = normalizeRadians(meanAnomaly0 + meanMotion * dt);
        double eccentricAnomaly = solveKepler(meanAnomaly, e);

        double xPqw = a * (Math.cos(eccentricAnomaly) - e);
        double yPqw = a * Math.sqrt(1.0 - e * e) * Math.sin(eccentricAnomaly);
        return pqwToEci(xPqw, yPqw, 0.0, raan, inclination, argumentOfPerigee);
    }

    /** 原 J2 长期项位置；升交点、近地点及平近点角三个长期角速度逐式保留。 */
    private static Vector3 propagateJ2Secular(OrbitDefinition.Elements elements, double tSec, double epochSec) {
        validateOrbit(elements);
        double dt = tSec - epochSec;

        double a = elements.semiMajorAxisM;
        double e = elements.eccentricity;
        double inclination = radians(elements.inclinationDeg);
        double raan0 = radians(elements.raanDeg);
        double argumentOfPerigee0 = radians(elements.argumentOfPerigeeDeg);
        double meanAnomaly0 = normalizeRadians(radians(elements.meanAnomalyDeg));

        double p = a * (1.0 - e * e);
        double n = Math.sqrt(EARTH_GRAVITATIONAL_PARAMETER / (a * a * a));
        double factor = EARTH_J2 * Math.pow(EARTH_EQUATORIAL_RADIUS_M / p, 2);
        double cosI = Math.cos(inclination);
        double raanRate = j2SecularRaanRateRadPerSec(a, e, elements.inclinationDeg);
        double argumentRate = 0.75 * factor * n * (5.0 * cosI * cosI - 1.0);
        double meanRate = n + 0.75 * factor * n * Math.sqrt(1.0 - e * e) * (3.0 * cosI * cosI - 1.0);

        double raan = raan0 + raanRate * dt;
        double argumentOfPerigee = argumentOfPerigee0 + argumentRate * dt;
        double meanAnomaly = normalizeRadians(meanAnomaly0 + meanRate * dt);
        double eccentricAnomaly = solveKepler(meanAnomaly, e);

        double xPqw = a * (Math.cos(eccentricAnomaly) - e);
        double yPqw = a * Math.sqrt(1.0 - e * e) * Math.sin(eccentricAnomaly);
        return pqwToEci(xPqw, yPqw, 0.0, raan, inclination, argumentOfPerigee);
    }

    /** 原 J2 升交点长期变化率，单位弧度/秒；兼容入口也委托这一公式。 */
    public static double j2SecularRaanRateRadPerSec(double semiMajorAxisM,
                                                    double eccentricity,
                                                    double inclinationDeg) {
        double p = semiMajorAxisM * (1.0 - eccentricity * eccentricity);
        double n = Math.sqrt(EARTH_GRAVITATIONAL_PARAMETER
                / (semiMajorAxisM * semiMajorAxisM * semiMajorAxisM));
        return -1.5 * EARTH_J2
                * Math.pow(EARTH_EQUATORIAL_RADIUS_M / p, 2.0)
                * n
                * Math.cos(radians(inclinationDeg));
    }

    /** 原 J2 数值积分：每次从根数历元重新开始，正反向最大30秒，末段阈值1e-9秒。 */
    private static StateVector propagateJ2Numerical(OrbitDefinition.Elements elements, double tSec, double epochSec) {
        validateOrbit(elements);
        StateVector state = elementsToState(elements);
        double dt = tSec - epochSec;
        if (Math.abs(dt) < 1e-12) {
            return state;
        }

        double remaining = dt;
        double direction = remaining >= 0.0 ? 1.0 : -1.0;
        while (Math.abs(remaining) > 1e-9) {
            double step = direction * Math.min(NUMERICAL_J2_MAX_STEP_SEC, Math.abs(remaining));
            state = rk4StepJ2(state, step);
            remaining -= step;
        }
        return state;
    }

    /** 原偏近点角转换位置和速度，保留乘除顺序及 PQW 到 J2000 旋转。 */
    private static StateVector elementsToState(OrbitDefinition.Elements elements) {
        double a = elements.semiMajorAxisM;
        double e = elements.eccentricity;
        double inclination = radians(elements.inclinationDeg);
        double raan = radians(elements.raanDeg);
        double argumentOfPerigee = radians(elements.argumentOfPerigeeDeg);
        double meanAnomaly = normalizeRadians(radians(elements.meanAnomalyDeg));
        double eccentricAnomaly = solveKepler(meanAnomaly, e);
        double sqrtOneMinusE2 = Math.sqrt(1.0 - e * e);
        double cosE = Math.cos(eccentricAnomaly);
        double sinE = Math.sin(eccentricAnomaly);
        double denom = 1.0 - e * cosE;
        double meanMotion = Math.sqrt(EARTH_GRAVITATIONAL_PARAMETER / (a * a * a));

        double xPqw = a * (cosE - e);
        double yPqw = a * sqrtOneMinusE2 * sinE;
        double vxPqw = -a * meanMotion * sinE / denom;
        double vyPqw = a * meanMotion * sqrtOneMinusE2 * cosE / denom;

        Vector3 position = pqwToEci(xPqw, yPqw, 0.0, raan, inclination, argumentOfPerigee);
        Vector3 velocity = pqwToEci(vxPqw, vyPqw, 0.0, raan, inclination, argumentOfPerigee);
        return new StateVector(position, velocity);
    }

    /** 原四阶龙格库塔单步，四个导数采样及向量累加顺序不变。 */
    public static StateVector rk4StepJ2(StateVector state, double stepSec) {
        StateVector k1 = derivativeJ2(state);
        StateVector k2 = derivativeJ2(addState(state, k1, stepSec * 0.5));
        StateVector k3 = derivativeJ2(addState(state, k2, stepSec * 0.5));
        StateVector k4 = derivativeJ2(addState(state, k3, stepSec));

        Vector3 positionDelta = k1.position
                .add(k2.position.scale(2.0))
                .add(k3.position.scale(2.0))
                .add(k4.position)
                .scale(stepSec / 6.0);
        Vector3 velocityDelta = k1.velocity
                .add(k2.velocity.scale(2.0))
                .add(k3.velocity.scale(2.0))
                .add(k4.velocity)
                .scale(stepSec / 6.0);
        return new StateVector(state.position.add(positionDelta), state.velocity.add(velocityDelta));
    }

    /** 状态导数为速度与二体加 J2 的加速度。 */
    private static StateVector derivativeJ2(StateVector state) {
        return new StateVector(state.velocity, accelerationJ2(state.position));
    }

    /** 按原顺序形成 RK4 中间状态，不改变分量运算精度。 */
    private static StateVector addState(StateVector state, StateVector derivative, double scale) {
        return new StateVector(
                state.position.add(derivative.position.scale(scale)),
                state.velocity.add(derivative.velocity.scale(scale))
        );
    }

    /** 原二体引力加 J2 加速度，米/秒平方；常量及运算结合顺序保持冻结版本。 */
    private static Vector3 accelerationJ2(Vector3 position) {
        double x = position.getX();
        double y = position.getY();
        double z = position.getZ();
        double r2 = x * x + y * y + z * z;
        double r = Math.sqrt(r2);
        double r3 = r2 * r;
        double central = -EARTH_GRAVITATIONAL_PARAMETER / r3;

        double z2OverR2 = (z * z) / r2;
        double j2Factor = 1.5 * EARTH_J2 * EARTH_GRAVITATIONAL_PARAMETER
                * EARTH_EQUATORIAL_RADIUS_M * EARTH_EQUATORIAL_RADIUS_M / (r2 * r2 * r);
        double common = 5.0 * z2OverR2 - 1.0;
        double ax = central * x + j2Factor * x * common;
        double ay = central * y + j2Factor * y * common;
        double az = central * z + j2Factor * z * (5.0 * z2OverR2 - 3.0);
        return new Vector3(ax, ay, az);
    }

    /** 原轨道平面到 J2000 旋转矩阵，输入输出均使用米或米/秒。 */
    private static Vector3 pqwToEci(double x, double y, double z, double raan, double inclination, double argPerigee) {
        double cosO = Math.cos(raan);
        double sinO = Math.sin(raan);
        double cosI = Math.cos(inclination);
        double sinI = Math.sin(inclination);
        double cosW = Math.cos(argPerigee);
        double sinW = Math.sin(argPerigee);

        double r11 = cosO * cosW - sinO * sinW * cosI;
        double r12 = -cosO * sinW - sinO * cosW * cosI;
        double r13 = sinO * sinI;
        double r21 = sinO * cosW + cosO * sinW * cosI;
        double r22 = -sinO * sinW + cosO * cosW * cosI;
        double r23 = -cosO * sinI;
        double r31 = sinW * sinI;
        double r32 = cosW * sinI;
        double r33 = cosI;

        return new Vector3(
                r11 * x + r12 * y + r13 * z,
                r21 * x + r22 * y + r23 * z,
                r31 * x + r32 * y + r33 * z
        );
    }

    /** 原 Newton 开普勒求解：最多30次，delta小于1e-12弧度终止，高偏心率初值为π。 */
    private static double solveKepler(double meanAnomaly, double eccentricity) {
        double eccentricAnomaly = eccentricity < 0.8 ? meanAnomaly : Math.PI;
        for (int iter = 0; iter < 30; iter++) {
            double f = eccentricAnomaly - eccentricity * Math.sin(eccentricAnomaly) - meanAnomaly;
            double fp = 1.0 - eccentricity * Math.cos(eccentricAnomaly);
            double delta = f / fp;
            eccentricAnomaly -= delta;
            if (Math.abs(delta) < 1e-12) {
                break;
            }
        }
        return eccentricAnomaly;
    }

    /** 沿用原根数合法性及错误文本，不增加有限数校验或改写业务边界。 */
    private static void validateOrbit(OrbitDefinition.Elements elements) {
        if (elements == null) {
            throw new IllegalArgumentException("卫星六根数不能为空。");
        }
        validatePositive(elements.semiMajorAxisM, "半长轴");
        if (elements.eccentricity < 0.0 || elements.eccentricity >= 1.0) {
            throw new IllegalArgumentException("偏心率需要满足 0 <= e < 1。");
        }
        double perigeeRadius = elements.semiMajorAxisM * (1.0 - elements.eccentricity);
        if (perigeeRadius <= EARTH_MEAN_RADIUS_M) {
            throw new IllegalArgumentException("近地点半径不能落入地球内部，请增大半长轴或减小偏心率。");
        }
    }

    /** 保留原正值校验语义。 */
    private static void validatePositive(double value, String name) {
        if (value <= 0.0) {
            throw new IllegalArgumentException(name + "必须大于0。");
        }
    }

    /** 角度转弧度，保持原 Math.toRadians 调用。 */
    private static double radians(double degrees) {
        return Math.toRadians(degrees);
    }

    /** 原角度余数归一化，保持负数与负零的浮点行为。 */
    private static double normalizeRadians(double value) {
        double result = value % (2.0 * Math.PI);
        return result < 0.0 ? result + 2.0 * Math.PI : result;
    }
}
