package com.runisys.orbit.spacevisibility.domain.orbit;

import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;

import java.util.Objects;

/**
 * 原生轨道参数的不可变描述，限定为项目现有的 TLE、六根数和拟合端点状态。
 * 本类只标识模型、单位、时间及坐标口径，不执行传播、坐标转换或业务实体读取。
 * 数值轨道有效范围仍由原传播器校验，避免借抽取入口改变旧接口的校验/错误语义。
 */
public abstract class OrbitDefinition {
    /** 现有实际模型；SPACE_KEPLER 对应业务 KEPLER_6，不能误解为纯二体。 */
    public enum Model {
        /** TLE 原 SGP4/SDP4 自动选择，输出 TEME。 */
        TLE_SGP4,
        /** 空间六根数：EME2000 二体加 J2，固定步长 RK4，默认10秒，输出 TEME。 */
        SPACE_KEPLER_J2_RK4,
        /** 原弹道二体、J2 长期项和 J2 数值实现，均输出原 J2000。 */
        TWO_BODY, J2_SECULAR, J2_NUMERICAL,
        /** 拟合区间外的笛卡尔二体 RK4，最大步长1秒。 */
        CARTESIAN_TWO_BODY_RK4
    }

    /** 坐标标签不代表转换能力；J2000 指原弹道实现口径，不自动等同于 EME2000。 */
    public enum Frame { TEME, EME2000, J2000 }

    /** 实际传播模型，必填。 */
    public final Model model;
    /** 输入根数或状态的原生坐标系，必填。 */
    public final Frame inputFrame;
    /** 原实现输出坐标系，必填。 */
    public final Frame outputFrame;
    /** 原生查询时刻口径；工具不转换时间。 */
    public final OrbitTime.Basis timeBasis;
    /** 旧弹道偏移约定，单位秒；UTC Instant 模型固定为0且不参与运算。 */
    public final double utcOffsetSec;

    private OrbitDefinition(Model model, Frame inputFrame, Frame outputFrame,
                            OrbitTime.Basis timeBasis, double utcOffsetSec) {
        this.model = model;
        this.inputFrame = inputFrame;
        this.outputFrame = outputFrame;
        this.timeBasis = timeBasis;
        this.utcOffsetSec = utcOffsetSec;
    }

    /** TLE 两行原文；历元由第一行内嵌 UTC 年/年积日定义，交由原 TLE 解析器处理。 */
    public static final class Tle extends OrbitDefinition {
        /** 原第一行，含内嵌历元；格式和 alpha-5 编号继续由原 provider 校验。 */
        public final String firstLine;
        /** 原第二行，角度和平均运动等单位沿用标准 TLE 固定列格式。 */
        public final String secondLine;

        /** 原样保存两行根数，不重复解析或重新舍入历元。 */
        public Tle(String firstLine, String secondLine) {
            super(Model.TLE_SGP4, Frame.TEME, Frame.TEME, OrbitTime.Basis.UTC_INSTANT, 0);
            this.firstLine = firstLine;
            this.secondLine = secondLine;
        }
    }

    /** 六根数及历元，适用空间原 RK4 和弹道原三种模型，不归一化角度或改写参数。 */
    public static final class Elements extends OrbitDefinition {
        /** 半长轴，米，原有效范围大于0。 */
        public final double semiMajorAxisM;
        /** 偏心率，无量纲，原有效范围 [0,1)。 */
        public final double eccentricity;
        /** 轨道倾角，度。 */
        public final double inclinationDeg;
        /** 升交点赤经，度。 */
        public final double raanDeg;
        /** 近地点幅角，度。 */
        public final double argumentOfPerigeeDeg;
        /** 历元平近点角，度，不是真近点角。 */
        public final double meanAnomalyDeg;
        /** 根数历元，必填且与模型原生时间一致。 */
        public final OrbitTime epoch;
        /** 空间 RK4 正步长（秒，原默认10）；弹道固定为0，表示沿用原内部积分策略。 */
        public final double integrationStepSec;

        /**
         * 保存六根数；仅检查参数类型与模型口径。空间调用者传入原配置步长（默认10秒），
         * 弹道调用者传0，不通过此入口给原算法引入新的可配置步长。
         */
        public Elements(Model model, double semiMajorAxisM, double eccentricity, double inclinationDeg,
                        double raanDeg, double argumentOfPerigeeDeg, double meanAnomalyDeg,
                        OrbitTime epoch, double integrationStepSec) {
            super(requireElementsModel(model), model == Model.SPACE_KEPLER_J2_RK4 ? Frame.EME2000 : Frame.J2000,
                    model == Model.SPACE_KEPLER_J2_RK4 ? Frame.TEME : Frame.J2000,
                    model == Model.SPACE_KEPLER_J2_RK4 ? OrbitTime.Basis.UTC_INSTANT : OrbitTime.Basis.LEGACY_J2000_SECONDS,
                    epochOffset(epoch));
            epoch.requireBasis(timeBasis);
            if (model == Model.SPACE_KEPLER_J2_RK4
                    ? !Double.isFinite(integrationStepSec) || integrationStepSec <= 0
                    : integrationStepSec != 0) {
                throw new IllegalArgumentException("空间RK4需正步长；弹道步长参数必须为0以沿用原实现");
            }
            this.semiMajorAxisM = semiMajorAxisM;
            this.eccentricity = eccentricity;
            this.inclinationDeg = inclinationDeg;
            this.raanDeg = raanDeg;
            this.argumentOfPerigeeDeg = argumentOfPerigeeDeg;
            this.meanAnomalyDeg = meanAnomalyDeg;
            this.epoch = epoch;
            this.integrationStepSec = integrationStepSec;
        }
    }

    /** 拟合区间外外推所需的原端点位置、速度及绝对时刻；不包含拟合或插值算法。 */
    public static final class Cartesian extends OrbitDefinition {
        /** 端点原生绝对时刻，必填。 */
        public final OrbitTime epoch;
        /** 原 J2000 位置，米，必填。 */
        public final Vector3 positionM;
        /** 原 J2000 速度，米/秒，必填。 */
        public final Vector3 velocityMps;
        /** 原二体 RK4 最大积分步长，秒；只描述原值，不改变原步长划分策略。 */
        public final double maxIntegrationStepSec = 1.0;

        /** 保存原拟合端点，禁止将 UTC Instant 或其他坐标的状态暗中当作旧 J2000 状态。 */
        public Cartesian(OrbitTime epoch, Vector3 positionM, Vector3 velocityMps) {
            super(Model.CARTESIAN_TWO_BODY_RK4, Frame.J2000, Frame.J2000,
                    OrbitTime.Basis.LEGACY_J2000_SECONDS, epochOffset(epoch));
            epoch.requireBasis(timeBasis);
            this.epoch = epoch;
            this.positionM = Objects.requireNonNull(positionM, "端点位置不能为空");
            this.velocityMps = Objects.requireNonNull(velocityMps, "端点速度不能为空");
        }
    }

    private static Model requireElementsModel(Model model) {
        if (model != Model.SPACE_KEPLER_J2_RK4 && model != Model.TWO_BODY
                && model != Model.J2_SECULAR && model != Model.J2_NUMERICAL) {
            throw new IllegalArgumentException("模型不接受六根数：" + model);
        }
        return model;
    }

    private static double epochOffset(OrbitTime epoch) {
        Objects.requireNonNull(epoch, "历元不能为空");
        return epoch.basis == OrbitTime.Basis.UTC_INSTANT ? 0 : epoch.utcOffsetSec();
    }
}
