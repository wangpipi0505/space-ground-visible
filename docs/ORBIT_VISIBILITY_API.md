# 轨道推演与可见窗口接口文档

本文档描述轨道推演与目标可见窗口计算模块的算法口径、Java 调用方式和 Spring Boot 接口示例。核心计算类为：

```text
com.runisys.orbit.spacevisibility.domain.ballistic.OrbitCalculator
```

正式 Spring Boot HTTP 控制器：

```text
com.runisys.orbit.spacevisibility.api.ballistic.BallisticVisibilityController
```

本分册沿用原交付 `ORBIT_VISIBILITY_API.md` 的章节、参数表及输入输出示例形式。A11代码重构未改变计算HTTP路径、参数、响应或文件格式；相对最初弹道软件，载荷已按空间标准增加配置，浏览器增加上传/预览/下载流程。全部空间及辅助操作另见[完整接口文档](接口文档.md)。文中路径为服务端路径示意，JSON输出展示结构，不作为该示例请求的实测精度或统计基准。

## 一、功能范围

模块根据目标弹道文件、卫星六根数、标准载荷约束和发射时间偏移搜索参数，计算每个偏移量下所有目标、所有卫星的可见弧段，并给出最优偏移量。目标弹道文件支持 CSV 和 Excel，系统会根据文件扩展名自动识别。

计算结果保持两层结构：

- `best`：所有偏移量中可见时长最大的结果。
- `allResults`：所有偏移量结果，数组顺序与偏移量搜索顺序一致。

多目标输入时，`allResults[i]` 表示同一个 `offsetSecond` 下所有目标、所有卫星、所有可见弧段的聚合结果。

## 二、Spring Boot 接口示例

健康检查：

```http
GET /api/orbit/health
```

可见窗口搜索：

```http
POST /api/orbit/visibility/search
Content-Type: application/json
```

请求示例：

```json
{
  "ballistics": [
    {
      "csvPath": "C:/service-data/trajectory/trajectory.csv",
      "baseLaunchUTC": "2026/07/01 07:57:00.000",
      "targetId": "TGT-001",
      "targetName": "目标1"
    }
  ],
  "startOffsetSec": -1800,
  "stepSizeSec": 10,
  "numCalculations": 361,
  "sensorFovDeg": 140.0,
  "targetSampleStepSec": 1.0,
  "trajectoryPointOutput": 1,
  "orbitModel": "J2_NUMERICAL",
  "satellites": [
    {
      "satelliteId": "SAT-A",
      "name": "SAT-A",
      "semiMajorAxisM": 7530612.0,
      "eccentricity": 0.00085,
      "inclinationDeg": 64.8,
      "raanDeg": 120.0,
      "argumentOfPerigeeDeg": 270.0,
      "meanAnomalyDeg": 30.0,
      "epochUtc": "2026/07/01 10:00:00.000",
      "payloads": [
        {
          "payloadId": "PAYLOAD-A-01",
          "payloadName": "对地载荷",
          "payloadType": "GROUND",
          "minDistanceKm": 0.0,
          "maxDistanceKm": 2500.0,
          "azimuthMinDeg": 0.0,
          "azimuthMaxDeg": 360.0,
          "elevationMinDeg": -90.0,
          "elevationMaxDeg": 90.0,
          "angleRateDegPerSec": 0.3,
          "sunAvoidHalfAngleDeg": 45.0,
          "startupAngleRateDegPerSec": -1.0
        },
        {
          "payloadId": "PAYLOAD-A-02",
          "payloadName": "对空载荷",
          "payloadType": "SPACE",
          "minDistanceKm": 0.0,
          "maxDistanceKm": 1200.0,
          "azimuthMinDeg": 0.0,
          "azimuthMaxDeg": 360.0,
          "elevationMinDeg": -90.0,
          "elevationMaxDeg": 90.0,
          "angleRateDegPerSec": 0.0,
          "sunAvoidHalfAngleDeg": 45.0,
          "startupAngleRateDegPerSec": -1.0
        }
      ]
    }
  ]
}
```

每颗卫星在自身的 `payloads[]` 内配置载荷，推荐使用本章的 `GROUND/SPACE` 标准字段。原中文分类请求样例保留于 `test-data/reference/examples/orbit_visibility_multi_payload_default.json`，用于兼容回归；网页默认扫描次数361与HTTP缺省101分别属于界面与接口默认值。

### 请求字段

| 字段                      | 类型     | 默认值                            | 说明                                                        |
| ----------------------- | ------ | ------------------------------ | --------------------------------------------------------- |
| `ballistics`            | array  | 空数组                            | 多目标弹道输入数组。有值时优先使用该字段。                                     |
| `ballisticCsvPath`      | string | `trajectory-0.1s-standard.csv` | 单目标兼容字段，弹道文件位置：本地路径、http(s):// 直链或 minio:// 地址，文件支持 `.csv`、`.xls`、`.xlsx`、`.xlsm`。 |
| `baseLaunchUtc`         | string | 弹道文件首行 UTC                     | 单目标兼容字段，目标基准发射 UTC。                                       |
| `startOffsetSec`        | long   | `-500`                         | 发射偏移搜索起点，单位秒。                                             |
| `stepSizeSec`           | long   | `10`                           | 发射偏移搜索步长，单位秒，必须大于 0。                                      |
| `numCalculations`       | long   | `101`                          | 搜索次数，必须大于 0。                                              |
| `sensorFovDeg` | double | `140.0` | 历史兼容字段，范围(0,180]；标准载荷使用内嵌LVLH方位/俯仰，不由本字段定义圆锥视场。 |
| `payloadType` | string | `对地监视` | 每个平台payloads为空时的历史分类回退；不是内嵌标准GROUND/SPACE的替代字段。 |
| `maxDetectRangeKm`      | double | `0.0`                          | 旧版单载荷兼容字段。仅在未配置平台内嵌载荷时生效；小于等于 0 表示不限制探测距离。                            |
| `sunExclusionAngleDeg`  | double | `45.0`                         | 旧版单载荷兼容字段。仅在未配置平台内嵌载荷时生效；视线与太阳夹角小于该值判为不可见。                          |
| `targetSampleStepSec`   | double | `1.0`                          | 目标轨迹可见性采样步长，单位秒；小于等于 0 时使用算法自动步长。                         |
| `trajectoryPointOutput` | int    | `1`                            | `1` 输出窗口内轨迹点明细；`0` 只输出窗口时间、点数和摘要。                         |
| `orbitModel`            | string | `J2_NUMERICAL`                 | 卫星轨道传播模型：`TWO_BODY`、`J2_SECULAR`、`J2_NUMERICAL`。          |
| `satellites`            | array  | 默认 SAT-A/SAT-B                 | 卫星六根数列表。                                                  |

`ballistics[]` 字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `csvPath` | string | 是 | 当前目标弹道文件位置：本地路径、http(s):// 直链或 minio:// 地址，文件支持 `.csv`、`.xls`、`.xlsx`、`.xlsm`。远程地址由服务端解析时下载到本地临时文件后解析。 |
| `baseLaunchUTC` | string | 否 | 当前目标基准发射 UTC。为空时使用该目标弹道文件首行 UTC。 |
| `baseLaunchUtc` | string | 否 | 同 `baseLaunchUTC`，用于兼容不同命名风格。 |
| `baselaunchUTC` | string | 否 | 同 `baseLaunchUTC`，用于兼容历史命名。 |
| `targetId` | string | 否 | 目标 ID，默认 `TGT-{序号}`。 |
| `targetName` | string | 否 | 目标名称，默认同 `targetId`。 |

`satellites[]` 字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `satelliteId` | string | 卫星平台 ID；为空时取 `name`，再为空时自动生成 `SAT-{序号}`。 |
| `name` | string | 卫星平台名称；为空时使用 `satelliteId`。 |
| `semiMajorAxisM` | double | 半长轴，单位米。 |
| `eccentricity` | double | 偏心率，取值 `0 <= e < 1`。 |
| `inclinationDeg` | double | 轨道倾角，单位度。 |
| `raanDeg` | double | 升交点赤经，单位度。 |
| `argumentOfPerigeeDeg` | double | 近地点幅角，单位度。 |
| `meanAnomalyDeg` | double | 历元时刻平近点角，单位度。 |
| `epochUtc` | string | 六根数历元 UTC，优先级高于默认历元。 |
| `payloads` | array | 平台内嵌载荷数组。非空时，算法对该平台的每个载荷分别计算全部目标。 |

`satellites[].payloads[]` 字段（与空间载荷的十个业务字段一致，另有两个身份字段）：

| 字段 | 类型 | 必填/默认 | 说明 |
| --- | --- | --- | --- |
| `payloadId` | string | 否 | 载荷ID；为空生成 `{satelliteId}-PAYLOAD-{序号}`。 |
| `payloadName` | string | 否 | 名称；为空使用载荷ID。 |
| `payloadType` | string | 是 | `GROUND`对地、`SPACE`对空；去首尾空白且不区分大小写。 |
| `minDistanceKm` | number | 是 | 最近距离km，有限非负。 |
| `maxDistanceKm` | number | 是 | 最远距离km；0不限，否则严格大于最近距离。 |
| `azimuthMinDeg` | number/null | 否 | LVLH方位下限°；与上限同时null不限。 |
| `azimuthMaxDeg` | number/null | 否 | 方位上限°；非空须满足 `0≤min<max≤360`，不接受倒置跨零范围。 |
| `elevationMinDeg` | number/null | 否 | LVLH俯仰下限°；与上限同时null不限；SPACE下限≥-90、GROUND下限≥-91。 |
| `elevationMaxDeg` | number/null | 否 | 俯仰上限°；非空严格大于下限且≤90。 |
| `angleRateDegPerSec` | number | 是 | 跟踪角速度°/s；SPACE的0或-1不限，GROUND的0是真零上限且不接受-1。 |
| `sunAvoidHalfAngleDeg` | number | 是 | 太阳回避半角°，有限且0～180；0关闭，等于边界允许。 |
| `startupAngleRateDegPerSec` | number/null | 否 | 启动角速度元数据°/s；null/-1禁用，其他有限非负值只回显，不参与窗口判定。 |

平台标识、六根数及历元仍属于 `satellites[]`。标准载荷按LVLH方位/俯仰范围、距离、角速度、太阳回避及地球遮挡组合判定，不使用历史圆锥角代替方位/俯仰参数。完整配置可通过 `POST /api/v1/ballistic/payloads/preview` 校验回显。

历史 `对地监视/对空监视/对地跟踪/对空跟踪` 输入仍按分类兼容，仅读取 `payloadType/maxDetectRangeKm/sunExclusionAngleDeg`，它们不是GROUND/SPACE的别名。历史分支的内嵌载荷仍使用57.5°/65°对地圆锥或59～79°/53～150°对空环锥；每个平台的payloads为空时单独回退顶层历史配置。`sensorFovDeg`仍按(0,180]校验；HTTP的分类配置不会因此变成Java `legacySimpleCone` 调用。新标准输入应完整提供本表必填字段。

## 三、弹道文件格式

目标弹道文件使用固定的时间分列格式。CSV 支持空格、Tab 或逗号分隔；Excel 使用第一张工作表，列顺序与 CSV 完全一致。

自动识别规则：

- `.csv` 按 UTF-8 文本解析。
- `.xls`、`.xlsx`、`.xlsm` 按 Excel 解析。
- Excel 可以包含表头行，表头行会自动跳过。

```text
YYYY MM dd HH mm ss.SSS ECEF_X ECEF_Y ECEF_Z energy dt ECI_X ECI_Y ECI_Z ECI_VX ECI_VY ECI_VZ ECEF_Vx ECEF_Vy ECEF_Vz
```

字段含义：

| 字段                        | 说明                          |
| ------------------------- | --------------------------- |
| `YYYY MM dd HH mm ss.SSS` | UTC 时间，秒字段支持毫秒小数。           |
| `ECEF_X/Y/Z`              | ECEF 位置，单位米。                |
| `energy`                  | 能量字段，当前可见性计算不使用，保留输出兼容。     |
| `dt`                      | 时间步长字段，当前可见性计算不直接使用，保留输出兼容。 |
| `ECI_X/Y/Z`               | J2000/ECI 位置，单位米。           |
| `ECI_VX/VY/VZ`            | J2000/ECI 速度，单位米/秒。         |
| `ECEF_Vx/Vy/Vz`           | ECEF 速度，单位米/秒。              |

时间处理规则：

- 弹道文件首行轨迹时间作为该目标的 `referenceUtc` 和局部参考历元。
- 每行 `relativeTimeSec = 当前行 UTC - 首行 UTC`。
- 新格式按 UTC 直接解析，不叠加北京时或其他时区偏移。
- 多目标计算时，每个目标独立解析自己的参考历元，互不共享全局状态。

## 四、网络路径与文件流

浏览器本地文件先通过 `POST /api/v1/ballistic/files`（multipart字段名 `file`）上传；取得201响应中的 `serverPath`，赋给 `ballistics[].csvPath` 或 `ballisticCsvPath`。`fileId`用于预览/下载，不直接替代计算路径。浏览器本机盘符或fakepath不能当作服务端路径。

| 操作 | 接口 | 用法 |
| --- | --- | --- |
| 上传 | `POST /api/v1/ballistic/files` | multipart上传，成功201；从响应取fileId/serverPath。 |
| 登记已有结果 | `POST /api/v1/ballistic/files/register-result` | JSON `{ "csvPath": "实际计算响应路径" }`，成功201取得fileId。 |
| 预览 | `GET /api/v1/ballistic/files/{fileId}/preview` | 查询参数 `page=1,limit=50`；保留CSV原表头及单元格文本。 |
| 下载 | `GET /api/v1/ballistic/files/{fileId}/download` | 返回原文件字节。 |

接口前缀及参数以[完整接口文档](接口文档.md)的文件操作为准。路径示例需替换成当前服务响应，过期引用需重新上传/登记。

登记只接受服务配置results根目录内的现有文件。显式outputDir越出管理结果根时，计算兼容输出仍有效，但不能通过上述辅助接口开放下载；网页调用拟合/模拟/重定时推荐省略outputDir。

`csvPath` / `ballisticCsvPath` 仍支持三种服务端位置形式，按扩展名自动识别CSV或Excel：

- 本地路径：JVM 可访问的文件系统路径，行为与旧版本一致。
- `http://` / `https://` 直链：服务端解析时下载到本地临时文件后解析；连接超时 15 秒、读超时 60 秒，跟随重定向，非 2xx 响应按错误返回。
- `minio://` 地址：通过 MinIO SDK 拉取对象到本地临时文件后解析，地址语法如下。

```text
minio://[accessKey:secretKey@]host[:port]/bucket/object[?secure=true]
```

- `accessKey:secretKey`：可选凭证，缺省走匿名访问。
- `port`：可选，缺省 http 为 `9000`、https 为 `443`。
- `object`：对象名，支持多级前缀，按 URL 解码。
- `?secure=true`：可选，使用 https 连接，默认 http。

示例：

```text
minio://<ACCESS_KEY>:<SECRET_KEY>@storage.example.com:9000/trajectory/target2.csv
minio://obs.example.com/trajectory/target2.xlsx?secure=true
```


算法层同样提供流式解析入口，适配层可自行从 MinIO、HTTP 下载或上传文件中取得 `InputStream` 后调用：

```java
OrbitCalculator.ParsedBallisticCsv parsed =
        OrbitCalculator.parseBallisticCsvWithReference(inputStream);
```

领域计算类OrbitCalculator提供以下入口：

```java
parseBallisticCsvWithReference(Path path)
parseBallisticCsvWithReference(InputStream inputStream)
parseBallisticCsvWithReference(Reader reader)
```

同类的`parseBallisticCsv(Path/InputStream/Reader)`入口保留。字符串地址（本地路径、HTTP/HTTPS、MinIO）由`infrastructure.files.TrajectoryFileResolver.parseBallisticCsvWithReference(String location)`或`parseBallisticCsv(String location)`加载后委托领域解析；正式Web通过BallisticTrajectoryService管理文件租约与临时文件作用域。

多目标Java搜索使用带显式`BallisticCsvLoader`的重载，调用方提供加载策略；例如将`TrajectoryFileResolver::parseBallisticCsvWithReference`作为最后一个参数。正式Web的应用服务已注入带资源管理的加载器。

## 五、输出结构

标准载荷的 `visibilityConstraint` 保留历史摘要结构：类型映射为GROUND/SPACE，最远距离和太阳半角映射到 `maxDetectRangeKm/sunExclusionAngleDeg`，四个圆锥角字段均为null。该摘要不包含方位/俯仰、最近距离和角速度的全部配置；不能据此恢复完整载荷参数。

窗口控制器内捕获的失败仍使用HTTP400/500及 `{ "error": "错误说明" }`，不新增成功包装；JSON无法解析等控制器外异常可进入既有全局兜底，返回500及code/error/requestId。文件辅助接口有各自的错误合同，见完整文档。

返回顶层对象：

| 字段 | 说明 |
| --- | --- |
| `referenceUtc` | 第一个目标弹道文件首行 UTC，只表示输入文件参考时间。 |
| `payloadType` | 本次计算使用的载荷分类。 |
| `visibilityConstraint` | 本次计算使用的可见性约束摘要，包含载荷分类、探测距离、太阳夹角和视场角配置。 |
| `best` | 最优偏移量结果。 |
| `allResults` | 所有偏移量结果，长度为 `numCalculations`。 |

单个偏移量结果：

| 字段 | 说明 |
| --- | --- |
| `offsetSecond` | 当前偏移量，单位秒。 |
| `launchTimeUtc` | 兼容字段，取该偏移下第一目标发射时间。 |
| `launchTimeJ2000Sec` | 兼容字段，取该偏移下第一目标发射 J2000 秒。 |
| `payloadType` | 顶层兼容字段。多载荷请求时不表示某一个具体载荷，具体分类以 `visibleSatellites[].payloadResults[]` 中的字段为准。 |
| `visibilityConstraint` | 顶层兼容字段。多载荷请求时不表示某一个具体载荷的完整约束，具体约束以 `payloadResults[].visibilityConstraint` 为准。 |
| `finalVisibleWindow` | 当前偏移下所有目标、所有卫星、所有弧段的外包可见时间窗口。 |
| `visibleArcCount` | 当前偏移下可见弧段总数。 |
| `visiblePointCount` | 当前偏移下可见采样点总数。 |
| `visibleDurationSec` | 当前偏移下按弧段累计的可见时长。 |
| `targets` | 当前偏移下目标摘要，不包含轨迹点明细；`visibleWindows[]` 中按卫星平台和载荷分别列出窗口摘要。 |
| `visibleSatellites` | 按卫星平台聚合后的可见结果，载荷、目标和弧段依次位于 `payloadResults[]`、`targets[]`、`visibleArcs[]`。 |

时间窗口字段统一使用：

```json
{
  "startTime": "2026/07/01 10:00:12.000",
  "endTime": "2026/07/01 10:02:33.000",
  "startJ2000Sec": 836172012.0,
  "endJ2000Sec": 836172153.0,
  "durationSeconds": 141.0
}
```

可见性约束摘要字段统一使用：

```json
{
  "payloadType": "SPACE",
  "maxDetectRangeKm": 1200.0,
  "sunExclusionAngleDeg": 45.0,
  "simpleConeHalfAngleDeg": null,
  "halfConeAngleDeg": null,
  "innerConeAngleDeg": null,
  "outerConeAngleDeg": null
}
```

多载荷结果按以下层级组织：

```text
visibleSatellites[]（卫星平台）
  └─ payloadResults[]（该平台的一台载荷）
       └─ targets[]（该载荷对一个目标的结果）
            └─ visibleArcs[]（该载荷对该目标的可见弧段）
```

| 路径 | 字段 | 说明 |
| --- | --- | --- |
| `visibleSatellites[]` | `satelliteId`、`satelliteName`、`satelliteIndex` | 存在可见弧段的卫星平台标识与显示名称。 |
| `visibleSatellites[]` | `finalVisibleWindow` | 该平台全部载荷、全部目标弧段的外包时间窗口。 |
| `visibleSatellites[].payloadResults[]` | `payloadId`、`payloadName`、`payloadType` | 该平台上存在可见弧段的具体载荷。 |
| `visibleSatellites[].payloadResults[]` | `visibilityConstraint` | 当前载荷实际使用的探测距离、太阳规避角和视场参数。 |
| `visibleSatellites[].payloadResults[].targets[]` | `targetId`、`targetName` | 当前载荷对一个目标的结果。 |
| `visibleSatellites[].payloadResults[].targets[].visibleArcs[]` | `arcIndex`、时间窗口、点数和 `trajectoryPoints` | 当前载荷对该目标的一条连续可见弧段。 |

`satelliteId` 标识卫星平台，`payloadId` 标识具体载荷。同一卫星平台的每个载荷都会对所有输入目标独立计算，不存在调用前绑定“某载荷只计算某目标”的前提。没有任何可见弧段的平台或载荷不写入 `visibleSatellites[]` / `payloadResults[]`；`payloadType`、`payloadId`、`payloadName` 会在 `payloadResults[]`、`visibleArcs[]` 和 `targets[].visibleWindows[]` 中输出，集成方应以这些字段进行载荷维度的过滤、分组和展示。

一个目标被同一平台的多个载荷同时观测时，每个载荷保留独立弧段，`visibleArcCount`、`visiblePointCount` 和 `visibleDurationSec` 按“卫星平台 × 载荷 × 目标 × 弧段”累计。因此这些汇总字段可能包含不同载荷在同一时段的重复观测时长；不应把它们解释为目标去重后的时间并集。

`trajectoryPointOutput` 的影响：

- `trajectoryPointOutput = 1`：`visibleSatellites[].payloadResults[].targets[].visibleArcs[].trajectoryPoints[]` 输出窗口内轨迹点明细。
- `trajectoryPointOutput = 0`：`trajectoryPoints[]` 输出为空数组，但 `startTime`、`endTime`、`pointCount`、`visibleDurationSec` 和 `best/allResults` 选择逻辑保持完整。

## 六、核心算法口径

1. 目标以文件ECEF轨迹描述地面相对路径；发射偏移后，按新的绝对时刻将ECEF位置重算到J2000/ECI参与可见性判断。
2. 目标点按 `targetSampleStepSec` 进行线性插值采样，用于控制可见性遍历密度。
3. 卫星位置按六根数历元传播到每个目标绝对时刻。
4. `TWO_BODY` 使用二体 Kepler 传播。
5. `J2_SECULAR` 使用 J2 长期项修正 RAAN、近地点幅角和平近点角。
6. `J2_NUMERICAL` 使用 RK4 数值积分 J2 摄动加速度。
7. 标准GROUND/SPACE载荷使用空间标准LVLH方位/俯仰与角速度约束；历史中文分类分支继续使用圆锥/环锥。
8. 在视场约束之外，继续判断最远探测距离、视线方向与太阳方向夹角和 WGS84 椭球遮挡；任一条件不满足则判为不可见。
9. 连续可见采样点合并为一个 `visibleArc`，不可见后再次可见则开启新的弧段。
10. 最优偏移量按 `totalObservableDurationSec()` 最大选择；该时长按“卫星平台 × 载荷 × 目标 × 弧段”累计，不对不同载荷的重叠观测去重。

## 七、当前算法优化

| 优化项 | 说明 |
| --- | --- |
| 目标与偏移量并行 | 多目标搜索按 `目标 × offset` 拆分独立任务；单目标搜索按 offset 并行。 |
| 卫星位置缓存 | 同一卫星、同一 J2000 时刻、同一模型的传播结果在一次搜索中复用。 |
| 轨迹点输出开关 | `trajectoryPointOutput=0` 时跳过窗口内点明细写出，只保留窗口摘要和计数。 |
| 采样步长参数 | `targetSampleStepSec` 控制目标遍历密度，便于在输出规模和时间分辨率之间取舍。 |
| JSON 流式写出 | 正式HTTP使用 `writeJsonTo(Writer, true)` 直接写响应流，避免先构造大字符串。 |
| 请求内参考历元隔离 | 多目标和 HTTP 单目标路径使用 `ParsedBallisticCsv` 保存每个弹道文件的参考时间。 |

## 八、Java 调用示例

以下演示保留的历史分类单目标Java调用，该单目标重载的参数与数值口径保持；Java import使用本版domain.ballistic包。正式浏览器应用使用前述HTTP及标准载荷JSON。该代码中的本地路径必须是调用JVM可访问的路径。

```java
OrbitCalculator.ParsedBallisticCsv parsed =
        OrbitCalculator.parseBallisticCsvWithReference(Paths.get("C:/service-data/trajectory/trajectory.csv"));

List<OrbitCalculator.OrbitElements> satellites = new ArrayList<>();
OrbitCalculator.OrbitElements sat = new OrbitCalculator.OrbitElements();
sat.satelliteId = "SAT-A";
sat.name = "SAT-A";
sat.semiMajorAxisM = 7530612.0;
sat.eccentricity = 0.000850;
sat.inclinationDeg = 64.8;
sat.raanDeg = 120.0;
sat.argumentOfPerigeeDeg = 270.0;
sat.meanAnomalyDeg = 30.0;
sat.epochSec = OrbitCalculator.utcToJ2000Sec("2026/07/01 10:00:00.000");
OrbitCalculator.SatellitePayloadConfig payload = new OrbitCalculator.SatellitePayloadConfig();
payload.payloadId = "PAYLOAD-A-01";
payload.payloadName = "对地监视载荷";
payload.sensorConfig = OrbitCalculator.SensorVisibilityConfig.forPayload("对地监视", 2500.0, 45.0);
sat.payloads.add(payload);
satellites.add(sat);

OrbitCalculator.SensorVisibilityConfig sensorConfig =
        OrbitCalculator.SensorVisibilityConfig.forPayload("对地监视", 0.0, 45.0);

OrbitCalculator.LaunchSearchResultV2 result =
        OrbitCalculator.searchLaunchOffsetsV2(
                parsed.referenceEpochSec,
                -1800,
                10,
                361,
                satellites,
                parsed.points,
                140.0,
                OrbitCalculator.OrbitModel.J2_NUMERICAL,
                1.0,
                parsed.referenceEpochSec,
                1,
                sensorConfig,
                null);
```

## 九、JSON 输入输出示例

### 9.1 输入参数 JSON 示例

多目标请求示例：

```json
{
  "ballistics": [
    {
      "csvPath": "C:/service-data/trajectory/trajectory_A.csv",
      "baseLaunchUTC": "2026/07/01 07:57:00.000",
      "targetId": "TGT-001",
      "targetName": "目标一"
    },
    {
      "csvPath": "C:/service-data/trajectory/trajectory_B.csv",
      "baseLaunchUTC": "2026/07/01 07:58:20.000",
      "targetId": "TGT-002",
      "targetName": "目标二"
    }
  ],
  "startOffsetSec": -1800,
  "stepSizeSec": 10,
  "numCalculations": 361,
  "targetSampleStepSec": 1.0,
  "trajectoryPointOutput": 1,
  "orbitModel": "J2_NUMERICAL",
  "satellites": [
    {
      "satelliteId": "SAT-A",
      "name": "SAT-A",
      "semiMajorAxisM": 7530612.0,
      "eccentricity": 0.00085,
      "inclinationDeg": 64.8,
      "raanDeg": 120.0,
      "argumentOfPerigeeDeg": 270.0,
      "meanAnomalyDeg": 30.0,
      "epochUtc": "2026/07/01 10:00:00.000",
      "payloads": [
        {
          "payloadId": "PAYLOAD-A-01",
          "payloadName": "对地载荷",
          "payloadType": "GROUND",
          "minDistanceKm": 0.0,
          "maxDistanceKm": 2500.0,
          "azimuthMinDeg": 0.0,
          "azimuthMaxDeg": 360.0,
          "elevationMinDeg": -90.0,
          "elevationMaxDeg": 90.0,
          "angleRateDegPerSec": 0.3,
          "sunAvoidHalfAngleDeg": 45.0,
          "startupAngleRateDegPerSec": -1.0
        },
        {
          "payloadId": "PAYLOAD-A-02",
          "payloadName": "对空载荷",
          "payloadType": "SPACE",
          "minDistanceKm": 0.0,
          "maxDistanceKm": 1200.0,
          "azimuthMinDeg": 0.0,
          "azimuthMaxDeg": 360.0,
          "elevationMinDeg": -90.0,
          "elevationMaxDeg": 90.0,
          "angleRateDegPerSec": 0.0,
          "sunAvoidHalfAngleDeg": 45.0,
          "startupAngleRateDegPerSec": -1.0
        }
      ]
    },
    {
      "satelliteId": "SAT-B",
      "name": "SAT-B",
      "semiMajorAxisM": 7530612.0,
      "eccentricity": 0.00085,
      "inclinationDeg": 64.8,
      "raanDeg": 120.0,
      "argumentOfPerigeeDeg": 270.0,
      "meanAnomalyDeg": 0.0,
      "epochUtc": "2026/07/01 10:00:00.000",
      "payloads": [
        {
          "payloadId": "PAYLOAD-B-01",
          "payloadName": "对空载荷",
          "payloadType": "SPACE",
          "minDistanceKm": 0.0,
          "maxDistanceKm": 1600.0,
          "azimuthMinDeg": 0.0,
          "azimuthMaxDeg": 360.0,
          "elevationMinDeg": -90.0,
          "elevationMaxDeg": 90.0,
          "angleRateDegPerSec": 0.0,
          "sunAvoidHalfAngleDeg": 45.0,
          "startupAngleRateDegPerSec": -1.0
        }
      ]
    }
  ]
}
```

单目标兼容请求示例：

```json
{
  "ballisticCsvPath": "C:/service-data/trajectory/trajectory.csv",
  "baseLaunchUtc": "2026/07/01 07:57:00.000",
  "startOffsetSec": -600,
  "stepSizeSec": 10,
  "numCalculations": 121,
  "sensorFovDeg": 140.0,
  "payloadType": "对空跟踪",
  "maxDetectRangeKm": 1200.0,
  "sunExclusionAngleDeg": 45.0,
  "targetSampleStepSec": 1.0,
  "trajectoryPointOutput": 0,
  "orbitModel": "J2_NUMERICAL",
  "satellites": []
}
```

### 9.2 输出参数 JSON 示例

`trajectoryPointOutput=1` 时，窗口内轨迹点会输出在 `visibleSatellites[].payloadResults[].targets[].visibleArcs[].trajectoryPoints[]` 中：

```json
{
  "referenceUtc": "2026/07/01 07:57:00.000",
  "payloadType": "GROUND",
  "visibilityConstraint": {
    "payloadType": "GROUND",
    "maxDetectRangeKm": 0.0,
    "sunExclusionAngleDeg": 45.0,
    "simpleConeHalfAngleDeg": null,
    "halfConeAngleDeg": null,
    "innerConeAngleDeg": null,
    "outerConeAngleDeg": null
  },
  "best": {
    "offsetSecond": 2860,
    "launchTimeUtc": "2026/07/01 08:44:40.000",
    "launchTimeJ2000Sec": 836167480.0,
    "payloadType": "GROUND",
    "visibilityConstraint": {
      "payloadType": "GROUND",
      "maxDetectRangeKm": 0.0,
      "sunExclusionAngleDeg": 45.0,
      "simpleConeHalfAngleDeg": null,
      "halfConeAngleDeg": null,
      "innerConeAngleDeg": null,
      "outerConeAngleDeg": null
    },
    "finalVisibleWindow": {
      "startTime": "2026/07/01 08:47:12.000",
      "endTime": "2026/07/01 08:49:36.000",
      "startJ2000Sec": 836167632.0,
      "endJ2000Sec": 836167776.0,
      "durationSeconds": 144.0
    },
    "visibleArcCount": 1,
    "visiblePointCount": 145,
    "visibleDurationSec": 144.0,
    "targets": [
      {
        "targetId": "TGT-001",
        "targetName": "目标一",
        "csvPath": "C:/service-data/trajectory/trajectory_A.csv",
        "baseLaunchUTC": "2026/07/01 07:57:00.000",
        "launchUTC": "2026/07/01 08:44:40.000",
        "payloadType": "GROUND",
        "launchTimeJ2000Sec": 836167480.0,
        "finalVisibleWindow": {
          "startTime": "2026/07/01 08:47:12.000",
          "endTime": "2026/07/01 08:49:36.000",
          "startJ2000Sec": 836167632.0,
          "endJ2000Sec": 836167776.0,
          "durationSeconds": 144.0
        },
        "visibleWindows": [
          {
            "satelliteId": "SAT-A",
            "satelliteName": "SAT-A",
            "satelliteIndex": 1,
            "arcIndex": 1,
            "payloadId": "PAYLOAD-A-01",
            "payloadName": "对地监视载荷",
            "payloadType": "GROUND",
            "startTime": "2026/07/01 08:47:12.000",
            "endTime": "2026/07/01 08:49:36.000",
            "startJ2000Sec": 836167632.0,
            "endJ2000Sec": 836167776.0,
            "durationSeconds": 144.0,
            "pointCount": 145
          }
        ]
      }
    ],
    "visibleSatellites": [
      {
        "satelliteId": "SAT-A",
        "satelliteName": "SAT-A",
        "satelliteIndex": 1,
        "finalVisibleWindow": {
          "startTime": "2026/07/01 08:47:12.000",
          "endTime": "2026/07/01 08:49:36.000",
          "startJ2000Sec": 836167632.0,
          "endJ2000Sec": 836167776.0,
          "durationSeconds": 144.0
        },
        "payloadResults": [
          {
            "payloadId": "PAYLOAD-A-01",
            "payloadName": "对地监视载荷",
            "payloadType": "GROUND",
            "visibilityConstraint": {
              "payloadType": "GROUND",
              "maxDetectRangeKm": 2500.0,
              "sunExclusionAngleDeg": 45.0,
              "simpleConeHalfAngleDeg": null,
              "halfConeAngleDeg": null,
              "innerConeAngleDeg": null,
              "outerConeAngleDeg": null
            },
            "finalVisibleWindow": {
              "startTime": "2026/07/01 08:47:12.000",
              "endTime": "2026/07/01 08:49:36.000",
              "startJ2000Sec": 836167632.0,
              "endJ2000Sec": 836167776.0,
              "durationSeconds": 144.0
            },
            "targets": [
              {
                "targetId": "TGT-001",
                "targetName": "目标一",
                "payloadId": "PAYLOAD-A-01",
                "payloadName": "对地监视载荷",
                "payloadType": "GROUND",
                "visibleArcs": [
                  {
                    "arcIndex": 1,
                    "payloadId": "PAYLOAD-A-01",
                    "payloadName": "对地监视载荷",
                    "payloadType": "GROUND",
                    "startTime": "2026/07/01 08:47:12.000",
                    "endTime": "2026/07/01 08:49:36.000",
                    "startJ2000Sec": 836167632.0,
                    "endJ2000Sec": 836167776.0,
                    "durationSeconds": 144.0,
                    "pointCount": 145,
                    "trajectoryPoints": [
                      {
                        "longitudeDeg": 111.5712,
                        "latitudeDeg": 38.7041,
                        "heightM": 50212.4,
                        "absoluteTimeJ2000Sec": 836167632.0,
                        "absoluteTimeUtc": "2026/07/01 08:47:12.000",
                        "relativeTimeSec": 152.0,
                        "sourceTrajectoryUtc": "2026/07/01 07:59:32.000"
                      }
                    ]
                  }
                ]
              }
            ]
          }
        ]
      }
    ]
  },
  "allResults": [
    {
      "offsetSecond": -1800,
      "launchTimeUtc": "2026/07/01 07:27:00.000",
      "launchTimeJ2000Sec": 836162620.0,
      "payloadType": "GROUND",
      "visibilityConstraint": {
        "payloadType": "GROUND",
        "maxDetectRangeKm": 0.0,
        "sunExclusionAngleDeg": 45.0,
        "simpleConeHalfAngleDeg": null,
        "halfConeAngleDeg": null,
        "innerConeAngleDeg": null,
        "outerConeAngleDeg": null
      },
      "finalVisibleWindow": null,
      "visibleArcCount": 0,
      "visiblePointCount": 0,
      "visibleDurationSec": 0.0,
      "targets": [
        {
          "targetId": "TGT-001",
          "targetName": "目标一",
          "csvPath": "C:/service-data/trajectory/trajectory_A.csv",
          "baseLaunchUTC": "2026/07/01 07:57:00.000",
          "launchUTC": "2026/07/01 07:27:00.000",
          "payloadType": "GROUND",
          "launchTimeJ2000Sec": 836162620.0,
          "finalVisibleWindow": null,
          "visibleWindows": []
        }
      ],
      "visibleSatellites": []
    }
  ]
}
```

`trajectoryPointOutput=0` 时，`trajectoryPoints` 为空数组，但窗口摘要字段仍然完整：

```json
{
  "arcIndex": 1,
  "payloadId": "PAYLOAD-A-01",
  "payloadName": "对地监视载荷",
  "payloadType": "GROUND",
  "startTime": "2026/07/01 08:47:12.000",
  "endTime": "2026/07/01 08:49:36.000",
  "startJ2000Sec": 836167632.0,
  "endJ2000Sec": 836167776.0,
  "durationSeconds": 144.0,
  "pointCount": 145,
  "trajectoryPoints": []
}
```
