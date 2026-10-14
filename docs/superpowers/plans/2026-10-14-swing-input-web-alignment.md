# Swing参数输入与Web弹道菜单对齐执行计划

> **For agentic workers:** Use superpowers:subagent-driven-development for the single A06 deliverable, followed by an independent specification and quality review of the complete change. Steps use checkbox syntax.

**Goal:** 保留空间页面，恢复弹道窗口和拟合的Swing参数组织、可编辑内容及明确默认值；弹道菜单仅保留“可视窗口计算”“目标弹道拟合”，后者的三项功能改为二级菜单。

**Architecture:** 复用现有Vue组件、唯一草稿、上传/登记/下载和原计算接口。原Swing只读作为参数依据，表单把同一数据直接提交给现有Spring Boot；标准载荷仍按SPACE/GROUND十字段配置，客户端不实现传播、拟合或时间转换。

**Tech Stack:** Vue 3、TypeScript、Element Plus、原Vite与pnpm锁；Java8/Spring Boot2.7.18/Orekit12.0和当前后端构建保持。

## 已确认设计

用户2026-10-14要求恢复Swing输入，仅载荷按合并要求变化；随后明确“空间的保留不动”。因此保留顶层“规划工作台”“任务结果记录”及空间页面的内容、行为、样式和后台任务；弹道顶层由四个入口收为“可视窗口计算”和“目标弹道拟合”。后者内嵌“基于102包拟合”“系统模拟弹道”“弹道文件重定时”三个二级页签。切换仍保留草稿、计算快照和真实生成引用。

窗口页直接显示可编辑双星六根数表、示例星座与增删/生成星座、多目标输入、原扫描参数；无需先从空间目录挑选身份。样例须标为非生产，不能冒充空间目录真实轨道。拟合页以原任务字段和102数据表为主要输入，模拟/重定时恢复原任务时间、端点和标识默认；完整JSON高级编辑及原别名/大整数字符串保留。

旧Swing独立轨道绘制的总时长/步长、显示坐标基准，以及历史全局UTC偏移必须依据调用链与旧HTTP契约分类。它们不在原窗口DTO中；本项不通过添加假可编辑控件、静默丢弃值、新字段或客户端时区转换冒称恢复。报告逐项列出具体边界。载荷旧四类锥角/兼容视场角按已经批准的空间标准调整，不将140°圆锥误称为标准矩形等价。

## Global Constraints

- 所有产品代码位于G:/5-YG/space-ground-visible；基准a66c100f3d5f746c5995dc16c72fcb284fc03167，在同一目录新分支feat/swing-input-web-alignment实施，现有Vite服务直接指向该目录，不另建副本工作树。两个原版目录只读。
- 保持Java8、Spring Boot2.7.18、Orekit12.0和原前端锁，不改后端、算法、积分/采样、原接口/CSV/数值标准；D01—D06暂缓。窗口/拟合/模拟/重定时全通过浏览器和既有HTTP完成。
- 空间规划工作台、任务结果记录及空间目录/载荷/下传/筛选/异步任务内容和行为保持，弹道不复制或覆盖空间草稿，不把默认根数贴到真实目录身份。
- 弹道一级菜单仅“可视窗口计算”“目标弹道拟合”；拟合三项子功能作为二级页签，保留挂载/草稿/运行中任务/结果引用。不得保留额外弹道一级“载荷配置”“模拟弹道”“重定时”。
- 原输入名称、单位、UTC字面字符串、数据编码、含义和可编辑项依据原Swing Main.java/TargetTrajectoryFitPanel.java；旧UI与旧HTTP默认不同（例如361/101）须明确恢复UI默认，不能改服务器缺省冒称接口未变。
- 载荷统一SPACE/GROUND十字段及每星多载荷，沿现有公共校验/编辑器；新增卫星、载荷和目标保持独立稳定身份，切页及加载示例不串数据，不把旧圆锥/环形载荷等价映射成矩形。
- 文件使用真实上传、生成文件登记和HTTP下载；禁止C:\\fakepath/本机路径冒充serverPath，默认数据也必须先取得真实引用。服务结果目录管理保持，不增前端outputDir绝对路径。
- 拟合时间码/ID/102观测字符串不经Number舍入；JSON高级编辑、嵌套别名和未知兼容字段不得因表格修改重构丢失。表单、表格、JSON必须是同一真实草稿，不维护会相互覆盖的第二份输入。
- 原A01—A05/Txx数值报告、原流、候选及历史标签不改、不倒填；新A06保存实际命令UTC/argv/cwd/退出码/双流SHA和来源。当前使用说明可更新，历史标签仍是旧UI版本。根只刷新A04/A05的当前元数据交付清单以绑定README/台账，旧标签清单保持。
- 中文业务注释详细，Git为类型(前端/前后端):中文说明 [T30]，正文注明A06；根负责Git/index/refs，新增acceptance/2026-10-14/A06，正常推送，不移动旧标签。

## A06：弹道参数与导航对齐（一个独立可验收事项）

**Files:**

- Modify: frontend/src/App.vue、App.spec.ts，保留空间正文，重组弹道入口与拟合二级页签。
- Modify: frontend/src/views/BallisticPayloadConfigurationView.vue及spec、components/BallisticWindowControls.vue及spec、utils/ballisticWindow.ts、api/ballisticWindowTypes.ts（仅已有DTO参数）。
- Modify: frontend/src/views/BallisticFitView.vue、BallisticSimulationView.vue、BallisticRetimeView.vue及对应spec；必要的小型表格/示例辅助复用现有组件，避免新框架。
- Create if needed: frontend/src/utils/ballisticSwingDefaults.ts与定向spec；必要非生产默认数据模块（来源及SHA绑定，不复制工程）。
- Modify: docs/软件功能使用说明.md；Create: docs/Swing与Web参数对照.md。
- Create: verification/A06/参数对照与验证报告.md、实际命令/原流/来源与保护绑定、浏览器截图和独立审查；完整运行产物在verification/runtime/A06。
- Root Modify: README.md、docs/补充验收台账.md、本计划、A04/A05当前交付清单；Create: A06最终交付清单/核对记录。

**Interfaces:** App继续收集三种GeneratedBallisticResult[]并按fileId合并；窗口组件接收唯一BallisticSatelliteDraft[]。窗口POST仍/api/orbit/visibility/search，拟合/模拟/重定时路径及请求响应不改。表格编辑必须直接更新最终请求的同一对象，正在编辑但尚未失焦的最后一格也必须被提交。新增默认数据通过现有上传/登记取得引用后才能进入请求。

- [x] 逐项核对原Swing与当前HTTP/网页：卫星六根数/历元、四示例星座及星座生成、目标ID/名称/来源/个别与默认基准UTC、偏移起点/步长/次数/目标采样/模型，以及拟合/模拟/重定时字段；保存源SHA和调用行号，形成有边界的参数对照。
- [x] 保存当前空间正文/相关组件、后端/锁/旧核心报告/必要原目录SHA，检查现有前端基线；只推进A06，不改用户运行数据或原目录。
- [x] 为实质行为添加RED反例：两个弹道一级入口/三个二级入口；默认双星直接可编辑和361扫描次数；修改表格末格实际请求同值；默认基准UTC与每目标优先；四星座/生成星座参数；102大整数/别名保存及同一草稿；切换菜单保留输入和生成引用。沿现有Vitest，不编写文案镜像测试。
- [x] 实现最小参数默认来源与表单。双星为SAT-A/SAT-B，a7530612m/e0.000850/i64.8/RAAN120/ω270/M30或0、epoch2026/07/01 10:00:00；扫描-500/10/361、目标采样1秒、J2_NUMERICAL。示例星座精确沿Main.java:1407—1450；默认新增星M0，真实目录根数保持独立。
- [x] 恢复目标弹道拟合三子页主要表单、102可编辑表与默认数据按钮。拟合defaultFitTask/2026-07-01 07:56:55.000—07:57:10.000/0.5秒；模拟defaultSimTask/SIM-001/2026-06-04 04:00:00.000—04:42:55.000/原两组三维米端点/1秒；重定时retimeTrajectory/RET-001/2026-07-01 09:00:00.000。导入的全部高级兼容字段原值保留，服务管理输出目录保持。
- [x] 重组App弹道导航，空间内容保持；选中父菜单/子页、懒挂载、草稿、运行冻结和三类文件引用相互不串，窄屏表格可滚动且有完整标签与错误定位。JSON及协议长说明折叠到高级设置，主要流程不用JSON输入。
- [x] 运行定向GREEN、完整前端测试及类型/构建；不升级锁。若失败先修复A06。后端未变不重跑77项精度/压测冒称新运行；通过真实HTTP请求/浏览器完成同输入结果核对和至少一个非空窗口，绑定当前活服务JAR SHA。
- [x] 真浏览器验证默认输入、增删/四星座/生成、载荷多星编辑、请求末格与UTC、102/模拟/重定时三子页真实计算与登记/复用/下载、菜单草稿保留、空间入口/原工作台/任务记录可用；保存真实请求/响应及截图，不能以mock替代B/S证据。
- [x] 更新当前使用说明和参数对照，报告恢复项、标准载荷变更与旧GUI专用参数边界；核对产品/旧证据保护和原栈；独立规格与质量审查整个A06，必要修复后定向复审。
- [ ] 测试和独审通过后更新台账、计划及根交付清单，实际暂存/版本Git字节核对，中文提交和A06新标签，正常远端同步并核对引用；保留已启动服务供用户查看。

核心默认值与回归断言示例（最终测试按实际组件事件发起，不能仅比较常量自身）：

```ts
const swingScanDefaults = { startOffsetSec: -500, stepSizeSec: 10, numCalculations: 361,
  targetSampleStepSec: 1, trajectoryPointOutput: 1 as const, orbitModel: 'J2_NUMERICAL' as const }
// 挂载窗口页后编辑SAT-A的平近点角，不失焦直接提交；断言捕获的真实请求字段。
expect(submitted.satellites[0].meanAnomalyDeg).toBe(42)
expect(submitted.numCalculations).toBe(361)
expect(submitted.ballistics[0].baseLaunchUTC).toBe('2026/07/01 08:24:00.000')
// 切换一级/二级菜单，再返回输入及真实文件引用仍在。
expect(restored.package102[0].tack3d_time_code).toBe('20260701075700000')
```

初轮设计自检：用户已明确恢复原输入和弹道菜单，并明确空间保持；本计划只落地该已授权范围，不另设常规实现/测试/提交批准门。页面不实现原Swing独立3D传播或非零全局时间偏移；参数对照须给出调用证据，不能将未支持项说成已恢复。
