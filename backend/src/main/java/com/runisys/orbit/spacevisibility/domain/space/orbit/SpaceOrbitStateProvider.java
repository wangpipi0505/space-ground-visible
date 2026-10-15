package com.runisys.orbit.spacevisibility.domain.space.orbit;

import com.runisys.orbit.spacevisibility.domain.geometry.Vector3;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitDefinition;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitPropagationTool;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitStateProvider;
import com.runisys.orbit.spacevisibility.domain.orbit.OrbitTime;
import java.time.Instant;

/**
 * 空间原传播器与公共轨道工具之间的普通 Java8 桥，无实体、Spring 或持久化依赖。
 * 从 TLE/六根数定义构建一次真实原 provider，再把旧 Instant 查询转换为显式 UTC 时间标签。
 * 位置和速度分别经工具对应方法执行；不使用完整状态代替位置、不换公式或坐标转换链。
 * 原 TLE 的 SGP4/SDP4、Alpha5 遮蔽和六根数 EME2000 二体+J2 固定步长 RK4 都由原类负责。
 * 桥与工具不另设缓存：同一个原实例持续接收原查询顺序；原 ThreadLocal 线程隔离、
 * RK4 60秒锚点及8192条上限保持在原类，禁止每次查询重建传播器或跨线程共享积分状态。
 */
public final class SpaceOrbitStateProvider implements OrbitStateProvider {
    private final OrbitStateProvider original;
    private final OrbitPropagationTool tool;

    /**
     * 绑定已经按同一组参数构建的原 provider；只组装，不传播、不预热线程缓存。
     * 包内边界供真实构建路径共用，绑定者须保证定义与原实例一致，不能猜测内部传播参数。
     */
    SpaceOrbitStateProvider(OrbitDefinition definition, final OrbitStateProvider original) {
        this.original = original;
        this.tool = new OrbitPropagationTool(definition, new OrbitPropagationTool.NativePropagation() {
            @Override
            public Vector3 positionAt(OrbitTime time) {
                return original.positionAt(time.instant());
            }

            @Override
            public Vector3 velocityAt(OrbitTime time) {
                // 委托原默认差分：先 t-1秒、后 t+1秒，不读取 Orekit 解析 PV 速度。
                return original.velocityAt(time.instant());
            }
        });
    }

    /**
     * 用两行 TLE 原文构建真实工具绑定。先运行原 TLE 格式/解析校验，错误原样传递；
     * 定义保留原文及内嵌 UTC 历元，Alpha5 仅由原 provider 内部处理。
     * @param firstLine TLE 第一行原文
     * @param secondLine TLE 第二行原文
     * @return 输出 TEME 的旧接口桥，tool() 可提供显式领域查询
     */
    public static SpaceOrbitStateProvider fromTle(String firstLine, String secondLine) {
        TleOrbitStateProvider original = new TleOrbitStateProvider(firstLine, secondLine);
        return new SpaceOrbitStateProvider(new OrbitDefinition.Tle(firstLine, secondLine), original);
    }

    /**
     * 原空间六根数入口。先由原构造器检查数值/历元/步长，避免新定义校验改变旧错误信息。
     * @param semiMajorAxisM 半长轴，米，有限且大于0
     * @param eccentricity 偏心率，无量纲，[0,1)
     * @param inclinationDeg 轨道倾角，度，有限数
     * @param raanDeg 升交点赤经，度，有限数
     * @param argumentOfPerigeeDeg 近地点幅角，度，有限数
     * @param meanAnomalyDeg 历元平近点角，度，有限数，保持原根数顺序
     * @param epoch UTC Instant，保留纳秒；应用实体时间转换由应用层完成
     * @param integrationStepSec 原 RK4 正步长，秒；应用默认10秒，不在桥中调整
     * @return EME2000 积分、TEME 输出的旧接口桥
     */
    public static SpaceOrbitStateProvider fromElements(double semiMajorAxisM, double eccentricity,
                                                       double inclinationDeg, double raanDeg,
                                                       double argumentOfPerigeeDeg, double meanAnomalyDeg,
                                                       Instant epoch, double integrationStepSec) {
        KeplerOrbitStateProvider original = new KeplerOrbitStateProvider(semiMajorAxisM, eccentricity,
                inclinationDeg, raanDeg, argumentOfPerigeeDeg, meanAnomalyDeg, epoch, integrationStepSec);
        OrbitDefinition.Elements definition = new OrbitDefinition.Elements(
                OrbitDefinition.Model.SPACE_KEPLER_J2_RK4, semiMajorAxisM, eccentricity, inclinationDeg,
                raanDeg, argumentOfPerigeeDeg, meanAnomalyDeg, OrbitTime.utc(epoch), integrationStepSec);
        return new SpaceOrbitStateProvider(definition, original);
    }

    /**
     * 以纯领域定义构建真实空间工具，可被普通 Java 调用。只接收原空间两种模型，
     * 其他定义明确拒绝；弹道及拟合定义由后续事项绑定，不在这里改用空间数值模型。
     * @param definition TLE 或 SPACE_KEPLER_J2_RK4 六根数定义
     * @return 持有真实原传播器的空间桥
     */
    public static SpaceOrbitStateProvider fromDefinition(OrbitDefinition definition) {
        if (definition instanceof OrbitDefinition.Tle) {
            OrbitDefinition.Tle tle = (OrbitDefinition.Tle) definition;
            return fromTle(tle.firstLine, tle.secondLine);
        }
        if (definition instanceof OrbitDefinition.Elements
                && definition.model == OrbitDefinition.Model.SPACE_KEPLER_J2_RK4) {
            OrbitDefinition.Elements elements = (OrbitDefinition.Elements) definition;
            return fromElements(elements.semiMajorAxisM, elements.eccentricity, elements.inclinationDeg,
                    elements.raanDeg, elements.argumentOfPerigeeDeg, elements.meanAnomalyDeg,
                    elements.epoch.instant(), elements.integrationStepSec);
        }
        throw new IllegalArgumentException("空间绑定不接受此轨道定义");
    }

    /** 返回实际查询使用的公共工具，允许调用者显式选择位置、速度或完整状态及 TEME frame。 */
    public OrbitPropagationTool tool() {
        return tool;
    }

    /**
     * 旧位置入口仅查询工具位置，单位米，不计算速度、不额外推进缓存。
     * null 是旧接口错误路径，交原 provider 抛出原异常，避免 OrbitTime 新校验替换兼容错误。
     */
    @Override
    public Vector3 positionAt(Instant instant) {
        return instant == null ? original.positionAt(null)
                : tool.positionAt(OrbitTime.utc(instant), OrbitDefinition.Frame.TEME);
    }

    /** 旧速度入口仅查询工具速度（米/秒），默认±1秒中心差分及错误路径继续由原 provider 执行。 */
    @Override
    public Vector3 velocityAt(Instant instant) {
        return instant == null ? original.velocityAt(null)
                : tool.velocityAt(OrbitTime.utc(instant), OrbitDefinition.Frame.TEME);
    }
}
