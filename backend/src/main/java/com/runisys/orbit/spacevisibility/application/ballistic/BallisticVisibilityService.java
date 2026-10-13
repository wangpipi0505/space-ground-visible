package com.runisys.orbit.spacevisibility.application.ballistic;

import com.tx.alg.OrbitCalculator;
import com.tx.alg.api.VisibilityRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 弹道可见窗口唯一应用编排：保持旧请求转换、默认两星、单/多目标分支及原 V2 搜索顺序。
 * Web 构造器经 T13 解析服务持有上传租约或远程作用域，输入完整加载后立即释放，计算只访问内存。
 * 原 Java 兼容构造器仍使用原文件解析入口；数值算法、手写 JSON 及 HTTP 响应由各自层负责。
 */
@Service
public class BallisticVisibilityService {
    private final OrbitCalculator.BallisticCsvLoader loader;

    /** 正式 Web 使用显式资源生命周期；原 URI 仍从请求传给算法，不替换成临时文件路径。 */
    @Autowired
    public BallisticVisibilityService(BallisticTrajectoryService trajectories) {
        this.loader = source -> {
            // 窗口旧接口的空源错误文案含句号；文件辅助入口自行保留其既有协议，不让辅助文案覆盖计算合同。
            if (source == null || source.trim().isEmpty()) {
                throw new IllegalArgumentException("弹道文件位置不能为空。");
            }
            return trajectories.parse(source).parsed;
        };
    }

    /** 保留直接 Java 调用的原解析策略，不要求启动 Spring 容器。 */
    public BallisticVisibilityService() {
        this.loader = OrbitCalculator::parseBallisticCsvWithReference;
    }

    /**
     * 按原控制器顺序转换、加载与搜索；每份源数据携带自身参考历元，绝不写静态 referenceEpochSec。
     * utcOffsetSec 仍按原 HTTP 合同置零；并发的外部 Java 调用若主动修改它，仍有原全局状态风险，留待 T26。
     * @param request 原兼容 DTO，单位、缺省值及别名保持原定义
     * @return 原 V2 结果，由控制器调用 writeJsonTo 流式输出
     * @throws IOException 原源文件读取错误，控制器保持原 500 语义
     */
    public OrbitCalculator.LaunchSearchResultV2 search(VisibilityRequest request) throws IOException {
        OrbitCalculator.utcOffsetSec = 0.0;

        // 1. 解析卫星列表（空则用默认星座）
        List<OrbitCalculator.OrbitElements> satellites = request.getSatellites().isEmpty()
                ? defaultSatellites()
                : request.toSatellites();

        OrbitCalculator.LaunchSearchResultV2 result;
        if (!request.getBallistics().isEmpty()) {
            result = OrbitCalculator.searchLaunchOffsetsV2(
                    request.toBallisticInputs(),
                    request.getStartOffsetSec(),
                    request.getStepSizeSec(),
                    request.getNumCalculations(),
                    satellites,
                    request.getSensorFovDeg(),
                    request.toOrbitModel(),
                    request.getTargetSampleStepSec(),
                    request.getTrajectoryPointOutput(),
                    request.toSensorVisibilityConfig(),
                    null, loader);
        } else {
            // 2. 兼容旧单目标输入：解析弹道文件，支持CSV和Excel
            OrbitCalculator.ParsedBallisticCsv parsedBallistic =
                    loader.load(request.getBallisticCsvPath());
            List<OrbitCalculator.BallisticPoint> ballisticPoints = parsedBallistic.points;

            // 3. 确定基准发射时间
            String utcInput = request.getBaseLaunchUtc();
            double baseLaunchJ2000;
            if (utcInput != null && !utcInput.trim().isEmpty()) {
                baseLaunchJ2000 = OrbitCalculator.utcToJ2000Sec(utcInput.trim());
            } else {
                baseLaunchJ2000 = parsedBallistic.referenceEpochSec;
            }

            // 4. 执行单目标搜索
            result = OrbitCalculator.searchLaunchOffsetsV2(
                    baseLaunchJ2000,
                    request.getStartOffsetSec(),
                    request.getStepSizeSec(),
                    request.getNumCalculations(),
                    satellites,
                    ballisticPoints,
                    request.getSensorFovDeg(),
                    request.toOrbitModel(),
                    request.getTargetSampleStepSec(),
                    parsedBallistic.referenceEpochSec,
                    request.getTrajectoryPointOutput(),
                    request.toSensorVisibilityConfig(),
                    null);
        }

        return result;
    }

    // ========== 内置默认星座 ==========

    private static List<OrbitCalculator.OrbitElements> defaultSatellites() {
        List<OrbitCalculator.OrbitElements> list = new ArrayList<>();
        list.add(defaultSat("SAT-A", 30.0));
        list.add(defaultSat("SAT-B", 0.0));
        return list;
    }

    private static OrbitCalculator.OrbitElements defaultSat(String name, double meanAnomalyDeg) {
        OrbitCalculator.OrbitElements e = new OrbitCalculator.OrbitElements();
        e.satelliteId = name;
        e.name = name;
        e.semiMajorAxisM = 7530612.0;
        e.eccentricity = 0.00085;
        e.inclinationDeg = 64.8;
        e.raanDeg = 120.0;
        e.argumentOfPerigeeDeg = 270.0;
        e.meanAnomalyDeg = meanAnomalyDeg;
        e.epochSec = OrbitCalculator.getDefaultSatelliteEpochSec();
        return e;
    }

}
