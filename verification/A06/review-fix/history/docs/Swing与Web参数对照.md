# Swing与Web参数对照（A06）

以只读原版 `G:/Code/Code-V3/alg/deliverables/alg-0723` 的 `Main.java`、`trajectory/TargetTrajectoryFitPanel.java`、`TargetTrajectoryFitter.java` 与当前 HTTP DTO/服务调用为依据。各来源路径、原字节 SHA256 及大小见 [来源绑定](../verification/A06/source-bindings.json)。本项恢复弹道输入和导航；空间“规划工作台”“任务结果记录”及其业务组件保持。

## 窗口输入

| 原Swing输入/默认与单位 | 原调用证据 | 当前Web同一草稿/HTTP映射 |
|---|---|---|
| 卫星名称及a(m)、e、i/RAAN/ω/M(deg)、历元UTC | Main:1154—1180 `parseSatellites`，1407—1458示例/新增 | 六根数可编辑表；`satellites[].name/semiMajorAxisM/eccentricity/inclinationDeg/raanDeg/argumentOfPerigeeDeg/meanAnomalyDeg/epochUtc`。原DTO:160—222历元字面解析，空沿服务默认 |
| 默认SAT-A/B：7530612/.000850/64.8/120/270，M30或0，UTC2026/07/01 10:00:00 | Main:1423—1426；OrbitCalculator:68—70 | 页面直接预填手工示例，独立MANUAL身份；不把这些数值套到目录卫星。新增手工星M0 |
| SSO四星7078000/.001/98，RAAN和M0/90/180/270，ω0 | Main:1409—1413 | 示例1精确恢复；每次加载新身份，旧目录草稿不变 |
| MEO六星26560000/.005/55，RAAN和M每60°，ω0 | Main:1414—1417 | 示例2精确恢复 |
| Molniya三星26600000/.72/63.4，RAAN和M0/120/240，ω270 | Main:1418—1421 | 示例3精确恢复 |
| 生成：低轨星座/6平面/每平面6，种子默认同SAT-A | Main:1314—1396 | 相同输入；RAAN=normalize(seedRAAN+p×360/P)，M=normalize(seedM+s×360/S+p×360/(P×S))；只生成根数，不浏览器传播 |
| 目标ID/名称/文件源/个别基准UTC；默认目标TGT-1/目标1 | Main:1190—1290、1456—1458 | 目标表直接编辑，内部identity与用户可编辑ID分离；`ballistics[].targetId/targetName/csvPath/baseLaunchUTC` |
| 原默认CSV：trajectory-0.1s-standard.csv；默认UTC空 | Main:89、133、1457；1265—1288每目标空时回退全局默认 | 前端自带原1507045B输入，通过真实HTTP上传201才使用serverPath；自身UTC优先默认UTC，再空省略字段沿文件。不是本机路径或生产结果 |
| 起点-500秒/扫描步长10秒/次数361/目标采样1秒 | Main:138—142；834—876原searchLaunchOffsetsV2调用 | `startOffsetSec/stepSizeSec/numCalculations/targetSampleStepSec`。仅恢复网页默认361；原HTTP VisibilityRequest:37缺省101保持 |
| 轨道模型J2_NUMERICAL；真实窗口点输出1 | Main:845、874—876 | `orbitModel/trajectoryPointOutput`沿原DTO；无算法、采样或积分改动 |
| 每星载荷及旧传感器140°视场角 | Main:136、840—841、872、buildSensorVisibilityConfig | 沿已批准SPACE/GROUND十字段与稳定payloadId/Name，每星多载荷。旧圆锥/半锥/环形不能与LVLH矩形姿态窗口等价，本页不做假换算 |

四星座与生成只替换本页当前选择；空间目录身份第一次选择仍空根数，重新选回原草稿。删除、新增、例子替换不复用正在响应的对象身份。最后一格通过原生input立即进入唯一最终请求，不依赖blur。

## 目标弹道拟合三子页

一级仅“可视窗口计算”“目标弹道拟合”；拟合二级为“102包轨迹拟合”“模拟弹道”“弹道重定时”。空间两个入口原位置保持。懒挂载后保留输入、执行状态、结果和当前会话真实引用；刷新不持久化。

| 原Swing输入/默认 | 原调用证据 | Web映射及边界 |
|---|---|---|
| 拟合defaultFitTask；UTC2026-07-01 07:56:55.000—07:57:10.000；步长.5秒 | FitPanel:112—116，466—472，885—891 | 主要任务表单直接编辑`taskName/taskStartTime/taskEndTime/interpolationStepSec`；HTTP `/api/trajectory/fit/package102`不变 |
| 默认八条102摊平观测28列 | FitPanel:80—110列键；474—506逐字段；893—900八行默认 | 28列可编辑表，字符串时间码/ID/代码/观测值不经Number；单位位置.1m、速度.0001m/s、加速度.01m/s²；增删直接操作同一JSON草稿的package102 |
| coordinateInfo/coordinate/coords与targetInfo/target、tack/track时间别名 | Fitter:172—200对象选择；原DTO兼容字段 | 表格按原对象优先级定位生效字段，时间tack非空优先track；只改被编辑叶值，保留其他嵌套、备用别名和未知兼容字符串。高级JSON也编辑同一inputText，无第二份表格草稿 |
| 模拟defaultSimTask/SIM-001；物理与任务UTC2026-06-04 04:00:00.000—04:42:55.000 | FitPanel:121—137、514—535 | 主要表单`taskName/targetId/taskStartTime/taskEndTime/launchTimeUtc/impactTimeUtc`；默认全部恢复，HTTP `/api/trajectory/simulate`不变 |
| 发射J2000米(2167790.227,4775481.109,3618557.253)，落点(3578635.760,3522525.227,3919735.591)，1秒 | FitPanel:127—136、520—534 | `launchPointX/Y/Z/CoordFrame/Unit`及impact对应字段；outputPointDurationSec和outputStepSec默认1。原坐标别名/WGS84语义与高级旧字段保留，端点Lambert/二体RK4模型不变 |
| 重定时retimeTrajectory/RET-001，UTC2026-07-01 09:00:00.000 | FitPanel:138—142、539—544 | 表单恢复Swing默认，`taskName/targetId/trajectoryCsvPath/launchTimeUtc`；HTTP `/api/trajectory/retime`不变。源先真实上传或选择登记引用，不能把默认本机文件路径提交 |
| 原输出目录选择 | FitPanel:116/137/142、471/535/544 | 已有Web适配继续省略outputDir，服务创建管理根内结果目录；导入旧outputDir显示明确省略通知。没有新增客户端绝对输出目录 |

默认102来自上述原Swing行，TS按字符串构成八行；可与已有带来源的reference JSON逐字段比对。主要表单、表格、高级JSON是同一真实草稿，非法数值允许留在输入并纠正；危险数字/损坏JSON仅在高级原文中修正，禁止舍入后继续请求。

编码保真不等于增加原服务的时间格式：原Fitter:2893—2914 `parseTimeCode`只把14位紧凑日历码及可选小数（如`20260701075700.000`）转换为UTC；无分隔的其他数字串按J2000秒解析。17位`20260701075700000`在浏览器不舍入，真实HTTP原服务仍会因任务范围错误返回400，A06保留这个边界，没有把它偷偷转为日历UTC。合法UTC字面样例、14位加小数编码和大整数兼容字段原文分别处理。

## 原GUI专用与历史兼容参数

| 原参数 | 真实调用链 | 本阶段分类 |
|---|---|---|
| 轨道推演总时长6000秒、推演步长10秒 | Main:791—814 `drawOrbitScene`→`generateSatelliteTracks/generateBallisticTrack/setScene/configureSlider`；1017—1022、1549—1555重绘/播放 | 原Swing独立3D传播/显示时间轴参数。窗口扫描834—876仅传扫描步长与目标采样；HTTP DTO没有duration/animationStep，未制造新表单/后端字段，也不误当积分步长 |
| 经纬高基准datumCombo | Main:794—810绘图及1021/1553场景重建，1782选择 | 原GUI地理显示基准。原窗口HTTP未暴露；没有把此选项假映射为模拟端点坐标系或改计算含义 |
| 显示视场锥、播放/时间轴、3D拖拽 | Main:146、152—160、810 | 原GUI交互专用；Web沿真实窗口点展示，不实现原Swing3D传播 |
| 历史全局UTC偏移utcOffsetSec，默认0 | Main:143、796绘图/842扫描设置；OrbitCalculator:58、3472—3507解析加偏移、3533—3542格式化反向补偿 | 历史全局时间补偿会影响原解析/格式化，不只是GUI显示参数；旧HTTP无此字段。当前服务以0为标准，不新增字段、不进行客户端时区加减；非零旧历史场景本项未恢复，不能冒称相同含义 |

默认0的UTC字面流、原积分/模型/文件格式/接口保持。非零历史全局补偿及原独立3D功能在既有HTTP边界外。验证事实与实际命令见 [A06参数验证报告](../verification/A06/参数对照与验证报告.md)；旧A01—A05/Txx精度报告与冻结副本未倒填为本项新运行。
