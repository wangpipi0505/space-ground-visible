# 目标轨迹拟合模块接口文档

本文档描述目标轨迹拟合模块的接口结构和输入输出约束。算法设计沿用原交付说明；当前HTTP完整字段另见[完整接口文档](接口文档.md)。

请求Java类型位于`api.ballistic.BallisticTrajectoryRequests`：FitRequest、SimulationRequest、RetimeRequest，分别继承领域FitInput、SimulationInput、RetimeInput。以下Java DTO块展开字段供阅读；JSON字段和原章节表格保持，OpenAPI仍使用既有schema名称。

核心算法类：

```text
com.runisys.orbit.spacevisibility.domain.ballistic.trajectory.TargetTrajectoryFitter
```

正式Spring Boot HTTP控制器：

```text
com.runisys.orbit.spacevisibility.api.ballistic.BallisticFitController
com.runisys.orbit.spacevisibility.api.ballistic.BallisticSimulationController
com.runisys.orbit.spacevisibility.api.ballistic.BallisticRetimeController
```

本分册沿用原交付 `TARGET_TRAJECTORY_FIT_API.md` 的章节、请求结构、字段表及输入输出示例。A11重构没有改动下面三个接口；102单位、模型、CSV及响应字段继续保持。文中JSON输出用于展示结构，路径、点数、警告和时间戳不是该输入的固定计算期望值；实际值以当次响应为准。

HTTP省略 `outputDir` 时，正式服务使用受管理的独立结果目录。显式提交该字段时，按既有服务端目录规则处理；Java DTO中的 `outputDir="."` 默认值不表示浏览器文件会保存到用户电脑当前目录。通过[文件辅助接口](ORBIT_VISIBILITY_API.md#四网络路径与文件流)登记实际 `csvPath` 后，用fileId预览/下载；完整错误及字段合同见[完整接口文档](接口文档.md)。

辅助登记只开放配置results根目录内的文件；显式outputDir越出该根时不能直接使用浏览器辅助下载。以下HTTP请求JSON示例省略outputDir以使用受管理目录；若需兼容指定目录，再显式增加该字段。

## 一、接口范围

模块提供三类能力：

1. 基于 102 包轨迹点进行目标轨迹拟合，输出 CSV。
2. 基于系统模拟参数生成目标弹道，输出同格式 CSV。
3. 基于已经生成的弹道 CSV 和新的发射时间生成重定时弹道 CSV。

三类能力的返回结构一致：

```json
{
  "taskName": "taskA",
  "targetCount": 1,
  "files": [
    {
      "targetId": "T001",
      "model": "BALLISTIC_MIDCOURSE_TWO_BODY",
      "csvPath": "output/trajectory-fit/taskA_..._T001_....csv",
      "pointCount": 1200,
      "warnings": []
    }
  ]
}
```

拟合/模拟CSV以逗号分隔，字段顺序固定；下列空格仅用于显示列名：

```text
YYYY MM dd HH mm ss.SSS ECEF_X ECEF_Y ECEF_Z energy dt ECI_X ECI_Y ECI_Z ECI_VX ECI_VY ECI_VZ ECEF_Vx ECEF_Vy ECEF_Vz isOriginal
```

`isOriginal=1`表示原始102硬约束点，0表示生成点。位置单位m、速度m/s；拟合/模拟dt为相邻间隔ms且首行0，重定时已有dt改为距新首点累计ms。ECEF速度沿用原STK Fixed分量约定，不能与包含地球自转修正的地固导数混用。重定时保留未知列及顺序，并新增或更新timeIntervalMs。

## 二、102 包拟合接口

Spring Boot接口：

```http
POST /api/trajectory/fit/package102
Content-Type: application/json
```

Java 调用（request使用对应领域输入，或继承它的API请求）：

```java
TargetTrajectoryFitter.FitRunResult result =
        TargetTrajectoryFitter.fitPackage102ToCsv(request);
```

请求结构（Java DTO主要字段；完整兼容字段见汇总文档）：

```java
public static final class FitRequest {
    public String taskName = "task";
    public List<Package102Record> package102;
    public String taskStartTime;
    public String taskEndTime;
    public double interpolationStepSec = 1.0;
    public String outputDir = ".";
}
```

请求示例：

```json
{
  "taskName": "taskA",
  "taskStartTime": "2026-07-01 07:56:58",
  "taskEndTime": "2026-07-01 08:05:00",
  "interpolationStepSec": 0.5,
  "package102": [
    {
      "track_tgt_id": "T001",
      "tack3d_time_code": "2026-07-01 07:57:00",
      "tack3d_tgt_pos_x": "70000000",
      "tack3d_tgt_pos_y": "0",
      "tack3d_tgt_pos_z": "0",
      "tack3d_tgt_vel_x": "10000000",
      "tack3d_tgt_vel_y": "1000000",
      "tack3d_tgt_vel_z": "0",
      "tack3d_tgt_acc_x": "0",
      "tack3d_tgt_acc_y": "0",
      "tack3d_tgt_acc_z": "0",
      "prop_tgt_type_8": "0x01",
      "prop_tgt_shape_8": "0x02",
      "prop_tgt_flight_phase_8": "0x01",
      "prob_tgt_critical_incident": "0xFF",
      "prob_tgt_critical_incident_time_code": "",
      "launch_time_code": "2026-07-01 07:56:55",
      "launch_coord_frame": "J2000",
      "launch_pos_x": "69950000",
      "launch_pos_y": "-5000",
      "launch_pos_z": "0",
      "launch_pos_unit": "0.1m",
      "impact_time_code": "2026-07-01 07:57:10",
      "impact_coord_frame": "J2000",
      "impact_pos_x": "70100000",
      "impact_pos_y": "10000",
      "impact_pos_z": "0",
      "impact_pos_unit": "0.1m"
    },
    {
      "track_tgt_id": "T001",
      "tack3d_time_code": "2026-07-01 07:57:00.500",
      "tack3d_tgt_pos_x": "70005000",
      "tack3d_tgt_pos_y": "500",
      "tack3d_tgt_pos_z": "0",
      "tack3d_tgt_vel_x": "10000010",
      "tack3d_tgt_vel_y": "1000020",
      "tack3d_tgt_vel_z": "0",
      "tack3d_tgt_acc_x": "0",
      "tack3d_tgt_acc_y": "0",
      "tack3d_tgt_acc_z": "0",
      "prop_tgt_type_8": "0x01",
      "prop_tgt_shape_8": "0x02",
      "prop_tgt_flight_phase_8": "0x01",
      "prob_tgt_critical_incident": "0xFF",
      "prob_tgt_critical_incident_time_code": "",
      "launch_time_code": "2026-07-01 07:56:55",
      "launch_coord_frame": "J2000",
      "launch_pos_x": "69950000",
      "launch_pos_y": "-5000",
      "launch_pos_z": "0",
      "launch_pos_unit": "0.1m",
      "impact_time_code": "2026-07-01 07:57:10",
      "impact_coord_frame": "J2000",
      "impact_pos_x": "70100000",
      "impact_pos_y": "10000",
      "impact_pos_z": "0",
      "impact_pos_unit": "0.1m"
    }
  ]
}
```

### FitRequest 字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `taskName` | string | 任务名称，用于输出文件命名。 |
| `package102` | array | 摊平的 102 轨迹点记录，一行即一个目标点。 |
| `taskStartTime` | string | 任务开始时间，必须早于等于 102 首点。 |
| `taskEndTime` | string | 任务结束时间，必须晚于等于 102 末点。 |
| `interpolationStepSec` | double | 输出点间隔秒，默认1；必须为有限数且大于等于0.001，小于下限直接拒绝。 |
| `outputDir` | string | 服务端CSV目录；HTTP省略时使用受管理独立目录，显式值按兼容规则处理。 |

### Package102Record 字段

102 包支持两种输入方式：摊平字段，或 `coordinateInfo/targetInfo` 对象。推荐使用摊平字段。

| 字段 | 说明 |
| --- | --- |
| `track_tgt_id` | 目标 ID。 |
| `tack3d_time_code` / `track3d_time_code` | 当前点时间。 |
| `tack3d_tgt_pos_x/y/z` | J2000 位置，默认 102 当量 `0.1m`。 |
| `tack3d_tgt_vel_x/y/z` | J2000 速度，默认102当量 `0.0001m/s`（`0.01cm/s`）。 |
| `tack3d_tgt_acc_x/y/z` | J2000 加速度，默认 102 当量 `0.01m/s^2`。 |
| `prop_tgt_type_8` | 目标类型。 |
| `prop_tgt_shape_8` | 目标形态。 |
| `prop_tgt_flight_phase_8` | 飞行阶段。 |
| `prob_tgt_critical_incident` | 关键事件。 |
| `prob_tgt_critical_incident_time_code` | 关键事件时刻。 |
| `launch_time_code` | 发射时间，作为参考信息。 |
| `launch_coord_frame` | 发射点坐标系：`J2000`、`ECI`、`ECEF`、`LLA`、`LLH`、`WGS84`。 |
| `launch_pos_x/y/z` | 发射点位置。LLA/WGS84 模式下 `x=经度(deg)`、`y=纬度(deg)`、`z=高度(m)`。 |
| `launch_pos_unit` | 发射点直角坐标单位，默认 `0.1m`。 |
| `impact_time_code` | 预估落点时刻，作为软参考。 |
| `impact_coord_frame` | 预估落点坐标系。 |
| `impact_pos_x/y/z` | 预估落点位置。 |
| `impact_pos_unit` | 预估落点直角坐标单位，默认 `0.1m`。 |

## 三、系统模拟弹道接口

Spring Boot接口：

```http
POST /api/trajectory/simulate
Content-Type: application/json
```

Java 调用：

```java
TargetTrajectoryFitter.FitRunResult result =
        TargetTrajectoryFitter.simulateToCsv(request);
```

请求结构（Java DTO主要字段；完整兼容字段见汇总文档）：

```java
public static final class SimulationRequest {
    public String taskName = "simulation";
    public String targetId = "SIM-TGT";
    public String taskStartTime;
    public String taskEndTime;
    public String launchTimeUtc;
    public String impactTimeUtc;
    public double launchPointX = 2167790.227;
    public double launchPointY = 4775481.109;
    public double launchPointZ = 3618557.253;
    public String launchPointCoordFrame = "J2000";
    public String launchPointUnit = "m";
    public double impactPointX = 3578635.760;
    public double impactPointY = 3522525.227;
    public double impactPointZ = 3919735.591;
    public String impactPointCoordFrame = "J2000";
    public String impactPointUnit = "m";
    public double outputPointDurationSec = 1.0;
    public List<PhaseEvent> phaseEvents = new ArrayList<PhaseEvent>();
    public String outputDir = ".";
}
```

请求示例：

```json
{
  "taskName": "simTask",
  "targetId": "SIM001",
  "taskStartTime": "2026-07-01 07:57:00",
  "taskEndTime": "2026-07-01 08:11:20",
  "launchTimeUtc": "2026-07-01 07:57:00",
  "impactTimeUtc": "2026-07-01 08:11:15.900",
  "launchPointCoordFrame": "LLA",
  "launchPointX": 111.57,
  "launchPointY": 38.7,
  "launchPointZ": 1600.0,
  "impactPointCoordFrame": "LLA",
  "impactPointX": 86.1746,
  "impactPointY": 41.725,
  "impactPointZ": 1000.0,
  "outputPointDurationSec": 1.0
}
```

### SimulationRequest 字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `taskName` | string | 任务名称，用于输出文件命名。 |
| `targetId` | string | 目标 ID。 |
| `taskStartTime` | string | 任务开始时间，用于任务元数据、差异告警和文件命名；不裁剪或扩展模拟CSV时域。 |
| `taskEndTime` | string | 任务结束时间，用于任务元数据、差异告警和文件命名；模拟CSV仍从物理发射时刻输出至物理落点时刻。 |
| `launchTimeUtc` | string | 发射时间。 |
| `impactTimeUtc` | string | 落点时间。为空时算法根据端点约束估计飞行时间。 |
| `launchPointX/Y/Z` | double | 发射点位置。 |
| `launchPointCoordFrame` | string | 发射点坐标系。 |
| `launchPointUnit` | string | 发射点直角坐标单位，LLA 模式下忽略。 |
| `impactPointX/Y/Z` | double | 落点位置。 |
| `impactPointCoordFrame` | string | 落点坐标系。 |
| `impactPointUnit` | string | 落点直角坐标单位，LLA 模式下忽略。 |
| `outputPointDurationSec` | double | 输出点间隔秒，默认1；先取该字段、兼容字段outputStepSec中的首个有限正值，否则取1，再要求大于等于0.001。 |
| `phaseEvents` | array | 多阶段事件预留字段，当前端点 Lambert 模拟模型接收但不施加动力学影响。 |
| `outputDir` | string | 服务端CSV目录；HTTP省略时使用受管理独立目录，显式值按兼容规则处理。 |

坐标系：

| 值 | 说明 |
| --- | --- |
| `J2000` / `ECI` | J2000 惯性直角坐标。 |
| `ECEF` | 地固直角坐标，按对应时刻转换到 J2000。 |
| `LLA` / `LLH` / `WGS84` | 经纬高，`X=经度(deg)`、`Y=纬度(deg)`、`Z=高度(m)`。 |

## 四、弹道文件重定时接口

该接口用于把已经生成的弹道 CSV 整体平移到新的发射时间。输出第一行时间严格等于入参发射时间；保持每一行的 ECEF 位置、ECEF 速度、`energy` 和 `isOriginal` 不变，按新的绝对时刻重新完成 ECEF 到 J2000/ECI 的坐标和速度转换，并重写时间列、`dt` 列、新增或更新 `timeIntervalMs` 列。

Spring Boot接口：

```http
POST /api/trajectory/retime
Content-Type: application/json
```

Java 调用：

```java
TargetTrajectoryFitter.FitRunResult result =
        TargetTrajectoryFitter.retimeTrajectoryToCsv(request);
```

请求结构（Java DTO主要字段；完整兼容字段见汇总文档）：

```java
public static final class RetimeTrajectoryRequest {
    public String taskName = "retimeTrajectory";
    public String targetId = "RETIMED-TGT";
    public String trajectoryCsvPath;
    public String launchTimeUtc;
    public String outputDir = ".";
}
```

请求示例：

```json
{
  "taskName": "retimeTask",
  "targetId": "RET-001",
  "trajectoryCsvPath": "C:/service-data/trajectory/output/trajectory-fit/source.csv",
  "launchTimeUtc": "2026-07-01 09:00:00.000"
}
```

### RetimeTrajectoryRequest 字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `taskName` | string | 任务名称，用于输出文件命名。 |
| `targetId` | string | 目标 ID，用于输出文件命名和返回结果。 |
| `trajectoryCsvPath` | string | 服务端CSV路径，可使用上传返回serverPath或拟合/模拟实际csvPath；本接口不直接读取HTTP/MinIO地址。输入须包含规定时间与位置/速度列。 |
| `launchTimeUtc` | string | 新的发射时间，输出第一行时间以该值为准。 |
| `outputDir` | string | 服务端新CSV目录；HTTP省略时使用受管理独立目录。 |

返回结构仍为统一的 `FitRunResult`。重定时输出文件的 `model` 为：

```text
RETIMED_TRAJECTORY
```

## 五、模型选择

102 包拟合根据目标类型、形态和飞行阶段选择模型标识：

| 条件 | 模型 |
| --- | --- |
| `prop_tgt_shape_8 = 0x03` | `CONSERVATIVE_FRAGMENT_DYNAMIC` |
| `prop_tgt_type_8 = 0x02/0x03` | `ORBITAL_TWO_BODY` |
| `prop_tgt_type_8 = 0x05` | `LAUNCH_VEHICLE_DYNAMIC` |
| `prop_tgt_flight_phase_8 = 0x00` | `BOOST_POWERED_DYNAMIC` |
| `prop_tgt_flight_phase_8 = 0x01` | `BALLISTIC_MIDCOURSE_TWO_BODY` |
| `prop_tgt_flight_phase_8 = 0x02` | `BALLISTIC_TERMINAL_TWO_BODY` |
| `prop_tgt_flight_phase_8 = 0x03` 或高超/空气动力目标 | `AERODYNAMIC_GLIDE_DYNAMIC` |
| 其他情况 | `UNKNOWN_CONSERVATIVE_DYNAMIC` |

系统模拟弹道使用端点约束 Lambert 初速求解和二体 RK4 传播，模型标识为：

```text
SYSTEM_SIMULATED_LAMBERT
```

## 六、输入限制

参数校验失败通常返回HTTP400，计算/读取错误返回500，计算接口在控制器内捕获的错误体为 `{ "error": "错误说明" }`。JSON无法解析等控制器外异常可进入既有全局兜底，返回HTTP500及 `{ "code": "INTERNAL_ERROR", "error": "错误说明", "requestId": "请求标识" }`，不能统一按400处理。拟合/模拟/重定时成功体均为FitRunResult，不增加统一wrapper。

- `package102` 不能为空。
- 同一目标内 102 点允许乱序和断档；算法内部会按时间排序。
- 排序后同一目标同一时刻只能保留一个轨迹点。
- 每个目标至少需要两个有效 102 点。
- `taskStartTime` 必须早于等于该目标 102 首点。
- `taskEndTime` 必须晚于等于该目标 102 末点。
- 102 原始点为硬约束，输出必须严格通过原始点。
- 不再对原始 102 点执行方向角和自交硬拒绝；拟合输出轨迹也暂不执行 90 度方向角和自交硬拒绝。相关验证方法保留在算法内部，后续如需严格几何约束可重新启用。
- 拟合的interpolationStepSec必须为有限数且大于等于0.001秒，不作下限归一。模拟先取outputPointDurationSec、outputStepSec中的首个有限正值，否则使用1秒，再检查不小于0.001秒；选中的正数小于下限时直接拒绝。
- 发射点和落点坐标系缺省按 `J2000` 处理。
- 弹道文件重定时要求输入 CSV 采用本模块标准列顺序，至少包含 `YYYY,MM,dd,HH,mm,ss.SSS` 时间列、ECEF 位置与速度列、ECI 位置与速度列；如果输入文件不存在 `timeIntervalMs` 列，输出文件会自动追加该列。

## 七、输出文件命名

文件名由计算层生成，目录由HTTP参数与服务配置确定。集成方使用返回的csvPath，不自行拼接创建时间或猜测输出目录。

输出文件名格式：

```text
taskName_taskStartTime_taskEndTime_targetId_fileCreateTime.csv
```

重定时文件使用新的发射时间命名：

```text
taskName_launchTimeUtc_targetId_fileCreateTime.csv
```

## 八、JSON 输入输出示例

### 8.1 102 包拟合输入 JSON 示例

```json
{
  "taskName": "taskA",
  "taskStartTime": "2026-07-01 07:56:58.000",
  "taskEndTime": "2026-07-01 08:05:00.000",
  "interpolationStepSec": 0.5,
  "package102": [
    {
      "track_tgt_id": "T001",
      "tack3d_time_code": "2026-07-01 07:57:00.000",
      "tack3d_tgt_pos_x": "70000000",
      "tack3d_tgt_pos_y": "0",
      "tack3d_tgt_pos_z": "0",
      "tack3d_tgt_vel_x": "10000000",
      "tack3d_tgt_vel_y": "1000000",
      "tack3d_tgt_vel_z": "0",
      "tack3d_tgt_acc_x": "0",
      "tack3d_tgt_acc_y": "0",
      "tack3d_tgt_acc_z": "0",
      "prop_tgt_type_8": "0x01",
      "prop_tgt_shape_8": "0x02",
      "prop_tgt_flight_phase_8": "0x01",
      "prob_tgt_critical_incident": "0xFF",
      "prob_tgt_critical_incident_time_code": "",
      "launch_time_code": "2026-07-01 07:56:55.000",
      "launch_coord_frame": "J2000",
      "launch_pos_x": "69950000",
      "launch_pos_y": "-5000",
      "launch_pos_z": "0",
      "launch_pos_unit": "0.1m",
      "impact_time_code": "2026-07-01 08:11:15.900",
      "impact_coord_frame": "J2000",
      "impact_pos_x": "70100000",
      "impact_pos_y": "10000",
      "impact_pos_z": "0",
      "impact_pos_unit": "0.1m"
    },
    {
      "track_tgt_id": "T001",
      "tack3d_time_code": "2026-07-01 07:57:00.500",
      "tack3d_tgt_pos_x": "70005000",
      "tack3d_tgt_pos_y": "500",
      "tack3d_tgt_pos_z": "0",
      "tack3d_tgt_vel_x": "10000010",
      "tack3d_tgt_vel_y": "1000020",
      "tack3d_tgt_vel_z": "0",
      "tack3d_tgt_acc_x": "0",
      "tack3d_tgt_acc_y": "0",
      "tack3d_tgt_acc_z": "0",
      "prop_tgt_type_8": "0x01",
      "prop_tgt_shape_8": "0x02",
      "prop_tgt_flight_phase_8": "0x01",
      "prob_tgt_critical_incident": "0xFF",
      "prob_tgt_critical_incident_time_code": "",
      "launch_time_code": "2026-07-01 07:56:55.000",
      "launch_coord_frame": "J2000",
      "launch_pos_x": "69950000",
      "launch_pos_y": "-5000",
      "launch_pos_z": "0",
      "launch_pos_unit": "0.1m",
      "impact_time_code": "2026-07-01 08:11:15.900",
      "impact_coord_frame": "J2000",
      "impact_pos_x": "70100000",
      "impact_pos_y": "10000",
      "impact_pos_z": "0",
      "impact_pos_unit": "0.1m"
    }
  ]
}
```

### 8.2 102 包拟合输出 JSON 示例

```json
{
  "taskName": "taskA",
  "targetCount": 1,
  "files": [
    {
      "targetId": "T001",
      "model": "BALLISTIC_MIDCOURSE_TWO_BODY",
      "csvPath": "C:/service-data/trajectory/output/trajectory-fit/taskA_20260701_075658_20260701_080500_T001_20260701_101530_123.csv",
      "pointCount": 1698,
      "warnings": [
        "Package102 fitting used two-body RK4 dynamics. Exact package102 timestamps were reset to source samples to guarantee hard pass-through."
      ]
    }
  ]
}
```

失败响应示例：

```json
{
  "error": "同一目标的package102轨迹点允许乱序和断档，但排序后同一时刻只能保留一个轨迹点；目标ID=T001"
}
```

### 8.3 系统模拟弹道输入 JSON 示例

经纬高输入：

```json
{
  "taskName": "simTask",
  "targetId": "SIM001",
  "taskStartTime": "2026-07-01 07:57:00.000",
  "taskEndTime": "2026-07-01 08:11:20.000",
  "launchTimeUtc": "2026-07-01 07:57:00.000",
  "impactTimeUtc": "2026-07-01 08:11:15.900",
  "launchPointCoordFrame": "LLA",
  "launchPointX": 111.57,
  "launchPointY": 38.7,
  "launchPointZ": 1600.0,
  "impactPointCoordFrame": "LLA",
  "impactPointX": 86.1746,
  "impactPointY": 41.725,
  "impactPointZ": 1000.0,
  "outputPointDurationSec": 1.0,
  "phaseEvents": []
}
```

J2000 直角坐标输入：

```json
{
  "taskName": "simTask",
  "targetId": "SIM001",
  "taskStartTime": "2026-07-01 07:57:00.000",
  "taskEndTime": "2026-07-01 08:11:20.000",
  "launchTimeUtc": "2026-07-01 07:57:00.000",
  "impactTimeUtc": "2026-07-01 08:11:15.900",
  "launchPointCoordFrame": "J2000",
  "launchPointUnit": "m",
  "launchPointX": 2167790.227,
  "launchPointY": 4775481.109,
  "launchPointZ": 3618557.253,
  "impactPointCoordFrame": "J2000",
  "impactPointUnit": "m",
  "impactPointX": 3578635.76,
  "impactPointY": 3522525.227,
  "impactPointZ": 3919735.591,
  "outputPointDurationSec": 1.0,
  "phaseEvents": [
    {
      "phaseType": "BOOST",
      "eventType": "CUTOFF",
      "eventTime": "2026-07-01 07:59:25.100",
      "dynamics": "RESERVED"
    }
  ]
}
```

### 8.4 系统模拟弹道输出 JSON 示例

```json
{
  "taskName": "simTask",
  "targetCount": 1,
  "files": [
    {
      "targetId": "SIM001",
      "model": "SYSTEM_SIMULATED_LAMBERT",
      "csvPath": "C:/service-data/trajectory/output/trajectory-fit/simTask_20260701_075700_20260701_081120_SIM001_20260701_101612_045.csv",
      "pointCount": 856,
      "warnings": [
        "System simulation used endpoint-constrained Lambert shooting and two-body RK4 propagation.",
        "Simulation impact time was supplied by request."
      ]
    }
  ]
}
```

### 8.5 弹道文件重定时输入 JSON 示例

```json
{
  "taskName": "retimeTask",
  "targetId": "RET-001",
  "trajectoryCsvPath": "C:/service-data/trajectory/output/trajectory-fit/simTask_20260701_075700_20260701_081120_SIM001_20260701_101612_045.csv",
  "launchTimeUtc": "2026-07-01 09:00:00.000"
}
```

### 8.6 弹道文件重定时输出 JSON 示例

```json
{
  "taskName": "retimeTask",
  "targetCount": 1,
  "files": [
    {
      "targetId": "RET-001",
      "model": "RETIMED_TRAJECTORY",
      "csvPath": "C:/service-data/trajectory/output/trajectory-fit/retimeTask_2026-07-01_09_00_00.000_RET-001_20260717_101612_045.csv",
      "pointCount": 856,
      "warnings": [
        "弹道文件重定时保持ECEF位置和速度不变；按新的绝对时刻重算ECI位置和速度，并更新时间列、dt列和timeIntervalMs列。"
      ]
    }
  ]
}
```

时间字段会转换为适合文件名的安全字符串。
