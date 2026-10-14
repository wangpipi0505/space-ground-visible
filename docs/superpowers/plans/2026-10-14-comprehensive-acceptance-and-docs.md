# 全面验收与新版文档执行方案

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按现有精度/接口/版本/中文规范，重新实测完整前后端功能与性能，交付可信测试报告、完整接口文档和可照步骤使用的软件说明。

**Architecture:** 产品基准030e73d，源与文档在指定新工程；运行使用ignored独立源码副本、端口和数据库，复制而非覆盖T30发布包。复用已有构建/精度比较/ManagedServer/HTTP例子/真实浏览器流程，补齐性能采样与用例矩阵；测试先完成，再顺序产生两份文档。

**Tech Stack:** Java8、SpringBoot2.7.18、Orekit12.0、Maven3.9.16、Vue3/TypeScript/ElementPlus/Vite冻结锁、Node24.16.0、pnpm11.21.0；测试辅助优先Python/Node标准库和现有Playwright CLI。

## Global Constraints

- 两原目录只读，产品均在G:\5-YG\space-ground-visible，前后端独立B/S，D01—D06暂缓。
- 保留原HTTP/Java契约/CSV/传播与拟合模型、步长及精度标准；标准载荷十字段口径保持，不降采样/截断/放宽比较。
- 详细中文业务注释；Git类型(前端/后端/前后端): 中文说明 [T30]，正文关联本轮A编号，原task/Txx标签不移动。
- 报告记录真实新命令/时间/环境/源码及JAR指纹、样本、统计和失败；无SLA只测量不虚构达标；不可运行环境标明缺口。
- A01—A03依次通过测试、独立审查、台账/提交才推进下一项。所有运行根隔离、清理仅自己实例，禁止覆盖活JAR。

### A01：功能及性能全面验收

**Files:** 新建verification/A01/测试报告.md、verification/A01/验收用例矩阵.json、verification/A01/acceptance.py、verification/A01/performance.py；必要浏览器流程放verification/A01/，截图output/playwright/A01/，运行根verification/runtime/A01/。复用deploy/build.ps1、deploy/package.ps1、verification/T30/final_verification.py、docs/examples/http_flow.py、verification/T26/ManagedServer.java、verification/T27/T28浏览器流程、scripts/run_baseline.py/ run_space_baseline.py/ run_space_regression.py。

**Interfaces:** 输入本轮不可覆写构建JAR、正式dist、真实test-data；输出逐用例矩阵/HTTP原响应SHA/数值差异、backend-performance.json与frontend-performance.json、命令原stdout/退出、截图及测试报告。A02/A03读取本项真实用例、请求/响应与页面截图。

- [x] 从实际HEAD/产品工作文件创建干净运行副本，绑定锁/源码/697原目录；正式Java8 build.ps1 all，新跑完整347/112及类型生产构建（数量以实际为准）。
- [x] 新capture原弹道15、空间9和完整空间21，沿用未改严格比较；普通Java工具、50类型1174ABI核验对应当前版本。
- [x] 建立35活动路由覆盖矩阵；运行HTTP例子并增加分页边界、非法字段/范围/UTC、取消与真实持久化、201引用/预览/下载/错误恢复、旧显式路径兼容及上传限额/失败清理；原JSON与CSV逐结构/Decimal/字节核验。
- [x] 真实Chrome经过空间筛选→载荷→下传→同步/异步/共同观测→结果选择/历史维护，以及四弹道页面上传/原生下载/轨迹/生成引用回窗；记录全部实际用例与截图、控制台/pageerror、HTTP错误→恢复、UTC/多目标多星多载荷边界。CLI原生filechooser与实际download字节必须核验。
- [x] 功能/构建停止后独占执行性能：各计算场景热身2次，串行10样本；并发1/2/4/6每级至少12完整请求（重任务按总内存观测合理分组，样本不得不足却写p99）。测窗口points0/1、三原模型、SPACE/GROUND、多目标/多星/多载荷、拟合/模拟/重定时、目录/规划/文件上传下载；记录每请求耗时/状态/字节/结果一致、总墙时吞吐量与错误率，进程工作集/CPU/线程/堆/GC可用指标与采样间隔。重负载服务最大堆明确记录，必须完整返回原结果。
- [x] 前端独立Chrome5次导航记录TTFB/DOMContentLoaded/load/FCP/LCP/资源尺寸/JS堆可用值，四页面及大结果加载/切换偏移/渲染记录动作和完成界限；阶段区分服务计算与浏览器处理，不虚构无泄漏或无界承载。
- [x] 资源场景新增客户端断开、取消/失败恢复、流式大结果、分离文件/租约/TTL及自己服务自然关闭/端口复绑；执行器容量和阶段WARN实际呈现。真实外部依赖只按可用环境声明，Docker连接失败为环境事实，不触碰生产实例。
- [x] 输出中文详细报告，模块/用例ID/参数/预期/实际/证据/通过失败跳过未测、精度、性能统计表及容量解释、缺陷/复测/环境限制；独立审查后更新补充台账并中文提交，标记acceptance/2026-10-14/A01。

关键新统计计算使用标准库，分位数采用nearest-rank并记录：

```python
def percentile_ms(values, p):
    import math
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * p) - 1)]
assert percentile_ms([10, 20, 30, 40], .5) == 20
assert percentile_ms([10, 20, 30, 40], .95) == 40
```

### A02：新接口文档

**Files:** 新建docs/接口文档.md、docs/openapi/openapi.json，必要生成/核验脚本verification/A02/，报告verification/A02/验证报告.md；README添加新入口。输入A01测试和正式Controller/DTO/序列化/前端调用，输出人读中文合同及OpenAPI3.0.3 JSON。

- [x] 以实际35路由建立每操作：方法/路径/用途/内容类型、path/query/body/multipart、必填/默认/类型/单位/特殊值/约束、成功/错误/取消状态、真实脱敏请求响应与curl。
- [x] 全量对照请求/响应嵌套字段/枚举/别名、Long与字符串ID、两UTC口径、完整CSV列及速度、显式outputDir/上传登记/下载/预览生命周期；新标准payload与兼容旧摘要分列。
- [x] OpenAPI定义真实活动操作及组件schema/nullable/format、文件二进制/JSON响应、已有错误形态，不新增包装/身份认证/伪字段；以A01实际请求/响应核对，不引用动态假fileId当实测。
- [x] 标准库加载JSON、遍历全部$ref存在、operations=35、方法路径与Controller清单一致；检查示例字段/required/响应类型与本项实际请求；核验中文Markdown链接/样例语法。新增普通Java工具接口入口引用现有独立工具说明。
- [x] 独立审查后补充台账A02完成/中文提交/acceptance日期标签，旧T03/T29历史文档保留来源，新README指向本完整文档。

### A03：软件功能使用说明

**Files:** 新建docs/软件功能使用说明.md；复用A01实际截图和输入；报告verification/A03/验证报告.md。必要更新README及补充台账；最终整个补充分支最强模型审查。

- [x] 面向操作者写环境安装/新目录/正式构建启动/开发联调/生产配置边界，使用当前实际脚本与控件名，不需要桌面UI或填写客户端绝对路径。
- [x] 空间按目录筛选、载荷SPACE/GROUND保存启用/切换、站星下传、单星/共同观测、同步/异步/取消、选择确认、记录查看/备注/删除逐步描述输入、操作、等待/结果和失败恢复。
- [x] 四弹道页面逐步描述102包来源/拟合原始点、模拟起落点/时刻、重定时选择/ECI重算/ECEF保留、多目标多星多载荷窗口/偏移/全部结果；CSV/JSON原字节下载、预览和已有引用回用截图与实际流程对应。
- [x] 明确公里/米/度/度每秒、UTC字面与DateInstant、SPACE/GROUND特殊值、真实根数显式绑定/非生产样例区别、旧结果快照/刷新草稿/取消受理/404过期/413限额及常见错误恢复。
- [x] 独立无上下文读者按说明验证路线/输入/控件/结果，实际截图与链接存在；不为不变代码机械重复全量数学。最终分支只读审查、全部补充事项台账完成、中文提交/标签/指定远端正常同步，交付三份新文档和实际证据。
