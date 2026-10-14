# STK原始数据精度补充测试执行方案

> **For agentic workers:** Use superpowers:subagent-driven-development for the single A05 deliverable, followed by an independent specification and quality review of the complete change. Steps use checkbox syntax.

**Goal:** 将已找到的STK原始轨迹纳入测试，按旧报告可核实的参数、时间、坐标与速度口径执行原版/合并版位置、速度和窗口对照；缺少独立STK Access结果的部分明确未测。

**Architecture:** 原始STK CSV按字节保存为有来源/校验值的必要参考输入；Python标准库只解析和统计，Java8探针调用两版本真实模拟、传播和窗口实现。原JAR与当前新编译类在独立进程中执行同一请求；结果写入新的A05运行目录，旧报告、黄金及运行产物不覆盖。

**Tech Stack:** Java8、Spring Boot2.7.18、Orekit12.0、现有Maven、Python标准库；不新增依赖，不修改数值模型。

## Global Constraints

- 工程为G:/5-YG/space-ground-visible，当前基准提交e38880d85883a7780dd01bc115d95cb3ac2aee20；两个原版目录及G:/Code/Code-V3/alg、G:/STK-file均只读。
- 保持Java8/Spring Boot2.7.18/Orekit12.0及前端锁；产品、数学模型、常数、积分/采样、旧接口/CSV和已验收标准不改；D01—D06继续暂缓。
- 旧A01—A04/Txx数值结果、报告、输入绑定和候选快照不倒填。根可在最终收口刷新A04的当前版本交付清单以绑定更新的README/台账，其原标签版本和870/1426/927历史快照保持；实现者不编辑A04。
- 只提交必要STK原始参考CSV、参数出处/源SHA、QA源码及实际验证证据。完整源码运行副本、JAR/classes、生产结果及临时数据写verification/runtime/A05，不提交冻结副本。
- 比较必须同目标、同UTC、同坐标系和同速度定义；原单位km/km/s明确乘1000为m/m/s。旧J2000秒沿用UTC日历2000-01-01 12:00，不当Orekit TT历元。
- STK导出的重复列名按位置和已核实帧顺序读取，禁止DictReader覆盖前一组三维状态；检查有限数、单调UTC、重复时刻、完整行数/时域，任何失配具名报告。
- 原版和当前以相同输入双跑；位置/速度/窗口差异不能以放宽容差、改黄金、截断样本、错位行相减或偷换物理条件掩盖。
- 独立STK真值只取实际外部CSV/Access起止表。*.e、software_visibility_*.csv和access_diagnostic.csv中的软件输出不是独立真值；缺少Access时标NOT_TESTED_MISSING_STK_ACCESS，不填零误差，不把原/新窗口一致性称为绝对窗口精度通过。
- 旧报告四项STK误差与本轮值分别展示，注明试验条件及可重现程度；遇旧文字近似端点、缺失原请求或场景快照与CSV日期/参数不同，展示原始冲突，不能默默使用近似值后宣称完全复现旧试验。
- 中文注释/报告、真实命令UTC/argv/cwd/退出码/原stdout-stderr字节SHA及失败→修复→复测记录；原始参考与旧证据前后SHA保护。
- 验收与独立复审后更新台账，Git为类型(后端/前后端):中文 [T30]，正文注明A05；根负责Git/index/标签/正常远端同步。原task/A01—A04标签不移动，新增acceptance/2026-10-14/A05。

### A05：原始STK数据纳入与精度补充报告

**Files:**

- Create: test-data/reference/stk/A05/raw/中的三份必要原始CSV及来源与校验.md。
- Create: verification/A05/run_stk_validation.py、StkStateProbe.java、test_stk_validation.py及实际输入绑定、场景、指标、命令/原流JSON。
- Create: verification/A05/STK原始数据精度补充测试报告.md、delivery_check.py及当前候选清单；完整逐点输出保存在A05 runtime，报告保留完整统计/最差点/数量/时域及源输出SHA。
- Root Modify: docs/补充验收台账.md、README.md、本计划勾选、A04当前交付清单及新增A05最终交付清单；旧核心报告不覆盖。

**Interfaces:** 执行器使用当前源码新编译产物及原verification/baseline-copies/ballistic/build/alg-toolkit-1.0.0-plain.jar。探针在各自classpath下直接调用TargetTrajectoryFitter.simulateToCsv和OrbitCalculator传播/窗口真实方法；轨迹请求沿用原SimulationRequest、输出原21列CSV。统计器输入按绝对UTC匹配的参考与原/当前结果，输出分量/三维范数平均、RMSE、P95、最大值、最差UTC及原/当前差异，不实现新的物理传播。

- [x] 核对并按原字节纳入G:/STK-file/Scenario3/Missile1 ECEFECI-0.1.csv、Satellite1 J2000 Position Velocity-HPOP.csv、Satellite1 J2000 Position Velocity-hpop-nodrag.csv；绑定旧报告、Scenario3.sc/.sca、Satellite1.sa、Sensor3.sn及7371/8641点时域。需要的配置片段可具名摘录并绑定完整源SHA，不复制整个STK目录。
- [x] 从原报告及CSV恢复参数。模拟时域2026-07-01 08:24:00.000至08:36:16.990、0.1秒、二体Lambert/RK4，ECI/J2000及ECEF/STK Fixed速度；先查原精确请求，缺失时以STK原始首末端点构造具名重建场景，分别披露与旧文字近似值/后改场景快照的冲突，不声称未能核实的完全相同参数。
- [x] 卫星按旧默认根数a=7530612m、e=0.000850、i=64.8°、RAAN=120°、ω=270°、M=30°、epoch=2026/07/01 10:00:00，J2_NUMERICAL；对HPOP与hpop-nodrag各8641个原UTC新执行原/当前位置及真实传播状态速度对照。全天及08:24:00—08:36:16.990窗口段分开统计，旧362/501米窗口描述与996.640/1154.136/2590.957米全天历史报告分开，不混范围。速度若需私有状态探针，具名说明实际原/当前核函数，不用数值差分冒充真实速度。
- [x] 窗口按旧诊断明确的130°全锥角/65°半角、0.5秒采样、SAT-A/B默认根数（M=30/0）、J2_NUMERICAL、base=2026/07/01 08:24:00及offset=0/1120秒新执行两版本，保存全部弧段/开始/结束/时长/点数及参数。使用实际STK目标轨迹或该报告时域的新模拟输出时分别具名；与旧诊断引用已失去的原模拟CSV不能冒称同一文件。原/当前一致性和外部STK Access未测分列。
- [x] 先用小型反例检查新解析/对齐/统计：重复列名、km→m、UTC偏移/缺行/重排/非有限数、微小位置/速度变化、1ms窗口变化、拒绝软件诊断输出作Access真值。保留真实RED→GREEN；不为低影响文案镜像造测试。
- [x] 当前源码在A05新副本实际编译并绑定635项必要源/输入及新JAR/classes，两个classpath隔离运行，不沿用旧数值结果称新跑。仅做本次所需检查，不机械重跑未改的前端/性能或A04全套。
- [x] 报告四类轨迹误差：原/当前分别对STK的ECI/ECEF位置与速度、原/当前相互差异；注明全部UTC覆盖、输出打印/时刻量化、单位/速度物理定义、旧四项误差及本轮差异来源。不能用原点硬约束零误差作为预测绝对精度。必要时模拟轨迹与原观测拟合场景分开说明。
- [x] 检查原STK/原目录/产品及旧证据SHA保持；输出可在没有runtime/JAR/classes情况下只读核验的具名交付入口和候选清单，不承诺缺资产完整重算。
- [x] 独立审查整个A05代码、输入出处、统计和报告；必要修复后定向复测/复审。更新台账、README和计划，新最终Git blob清单实际核对，中文提交、新A05标签、正常推送并核对远端引用。
