# A18 空间与弹道按业务分包执行计划

> 按已获用户确认的结构在当前工程执行；各步骤完成实际验证后更新台账，不重复请求常规实施许可。

**目标：** 空间与弹道分别组织业务代码，领域层按模型、规则和算法职责归位，共用能力只维护一份。

**架构：** API的空间控制器与弹道控制器并列；应用层分别提供空间、弹道用例；领域层以space、ballistic组织专有计算，geometry、orbit、payload、time、window保留共用模型与算法。文件、持久化和Web运行配置分别归入对应基础设施业务包或公共包。

**环境：** Java8、Spring Boot2.7.18、Orekit12.0，前端锁文件和依赖版本不变。

## 范围与约束

- 仅整理职责、包与依赖，数值模型、常数、积分/采样、缓存和线程顺序均不改变；不推进D01—D06。
- HTTP路径、输入输出、默认值、状态码、JSON序列化、CSV列及文件格式保持。Java调用随新包更新引用和入口位置。
- 原版与历史黄金只读；测试副本与运行结果隔离至verification/runtime/A18，不干扰已有服务。
- 用例契约集中定义，避免API与应用之间重复字段。领域不得反向依赖应用、API或基础设施。
- 保持中文注释和提交规范，事项记录A18，提交标题仍关联[T30]。

## 分包落点

| 当前代码 | 目标与职责 |
| --- | --- |
| api根的三个空间控制器 | api.space，HTTP适配 |
| ApiCorsConfiguration、ApiExceptionHandler | api.common，共用HTTP边界 |
| SpaceApiModels | application.space.contract.SpacePlanningModels，用例输入输出；保留全部字段和Jackson规则，控制器直接绑定 |
| BallisticFileModels | application.ballistic.contract，上传、登记与预览共用契约，存储无需引用API |
| application根的空间流程、任务、目录与结果服务 | application.space |
| VisibilityArc/Observer/Target/PairResult/PairStatistics/ObservationResult及组合计算 | domain.space.visibility，空间观测模型与规则 |
| ObservationSampleEvaluator、OrbitPositionCache | domain.space.visibility，本次观测计算内的采样与缓存支撑；跨层调用仅开放必要访问权限 |
| 空间太阳、光照状态与组合约束 | domain.space.illumination |
| 下传算法、照明要求、DownlinkPair/PairResult | domain.space.downlink |
| 地面站几何与TEME/地固转换 | domain.space.geometry，保持原坐标口径 |
| 空间传播器及其既有Orekit上下文 | domain.space.orbit，只迁包，不重构传播或数据装载时机 |
| 弹道窗口七类、拟合器、两种传播实现 | 分别domain.ballistic.visibility、trajectory、propagation |
| 共用向量/几何数学、轨道定义与查询、标准载荷、时间、扫描和区间 | 保留domain.geometry/orbit/payload/time/window，继续直接复用 |
| 弹道文件设施 | infrastructure.ballistic.files |
| 空间实体、Mapper、数据库配置 | infrastructure.space.persistence及子包 |
| Tomcat运行支撑 | infrastructure.common.web |

## 实施与验证

- [x] 保存当前源码清单和完整后端构建基线，记录命令原始双流与退出码。
- [x] 生成逐类映射，机械迁移主代码、测试、框架扫描与反射字符串；冻结原版加载字符串保持。
- [x] 将应用模型和领域模型归位，补齐同包访问及依赖；不添加旧包转发壳，不复制契约字段。
- [x] 完整后端测试；复算15份弹道、9份空间、21份扩展空间严格黄金；48份序列化和真实HTTP前后对照。
- [x] 核对35个HTTP操作与OpenAPI实际合同、计算主体、原目录保护；运行前端既有测试与构建确认合同调用保持。
- [x] 更新代码说明、两侧增量集成说明、接口Java引用及台账，生成含Maven库的源码包；实际解压离线构建。
- [ ] 独立审查，修复所有阻断项；中文提交、新A18验收标签及正常原子同步，历史标签不移动。

证据保存verification/A18，最终状态以本轮测试报告、交付字节核对和台账为准。

当前状态：实现、完整回归、说明和r2源码包离线验证已完成，独立审查和最终包复核均已通过；正在提交同步。
