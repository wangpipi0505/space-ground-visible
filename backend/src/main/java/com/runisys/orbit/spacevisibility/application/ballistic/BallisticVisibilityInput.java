package com.runisys.orbit.spacevisibility.application.ballistic;

import com.runisys.orbit.spacevisibility.domain.ballistic.OrbitCalculator;
import java.util.List;

/**
 * 窗口搜索的应用输入视图。HTTP 请求实现此接口，应用服务只依赖领域类型与本层输入约定。
 * 转换方法按计算流程延迟调用，保持卫星校验、文件加载及载荷校验的既有先后顺序；
 * 不复制整份请求字段，也不在领域层引入 Jackson、Controller 或远程下载器。
 */
public interface BallisticVisibilityInput {
    /** 是否提交卫星；空集合使用内置双星。 */
    List<?> getSatellites();
    /** 是否提交多目标；空集合走单目标路径。 */
    List<?> getBallistics();
    /** 单目标文件原始地址，保留路径及网络 URI 回显。 */
    String getBallisticCsvPath();
    /** 基准发射 UTC；空值使用源文件首点历元。 */
    String getBaseLaunchUtc();
    /** 偏移起点，单位秒。 */
    long getStartOffsetSec();
    /** 偏移步长，单位秒。 */
    long getStepSizeSec();
    /** 偏移计算次数。 */
    long getNumCalculations();
    /** 简单圆锥兼容配置的全锥角，单位度。 */
    double getSensorFovDeg();
    /** 目标采样间隔，单位秒，特殊值沿用请求约定。 */
    double getTargetSampleStepSec();
    /** 轨迹点输出模式，0 为摘要，1 为完整。 */
    int getTrajectoryPointOutput();
    /** 转换卫星及载荷，保持字段默认值与校验规则。 */
    List<OrbitCalculator.OrbitElements> toSatellites();
    /** 转换多目标文件标识及各自独立发射历元。 */
    List<OrbitCalculator.BallisticTargetInput> toBallisticInputs();
    /** 选择既有传播模型，本次整理不替换实现。 */
    OrbitCalculator.OrbitModel toOrbitModel();
    /** 转换顶层载荷兼容字段。 */
    OrbitCalculator.SensorVisibilityConfig toSensorVisibilityConfig();
}
