# STK实机场景与Web标准载荷独立验证计划

> **For agentic workers:** 按subagent-driven-development执行一个A09事项；根独占STK COM实例、台账和Git，单一实施代理准备受控输入/软件基准，最终新独审核对真实STK来源与结论。无需用户重复批准常规测试与提交。

**Goal:** 在用户本机STK11.2.0创建并保存可打开的验证场景，取得真正由STK计算的Access与几何数据，核对当前Web标准载荷条件；差异逐项归因。

**Architecture:** 先将同一实际J2000轨迹输入双方，隔离载荷/Access判定与轨道传播误差；逐项启用LOS、距离、角窗口、角速度及太阳回避。根通过STK11.Application COM完成独立Access计算，不能把软件输出改名当STK真值。对连续Access与软件离散样本同时比较，保留采样边界差。原生STK外推作为单独模型对照，不与同输入载荷验证混算。

**Tech Stack:** Java8、SpringBoot2.7.18、Orekit12.0、现有Vue锁文件保持；已安装STK11.2.0 GUI/COM、WindowsPowerShell5.1、Python标准库。

## Global Constraints

- 只推进A09，不开始D01—D06；前后端5173/8080继续运行，两个原版目录只读。
- 用户已明确要求打开其STK、创建场景并测试；现有工程不关闭不覆盖。本次独立实例PID36172，场景WebPayload_A09_20261015，初始时间07:57—09:00UTC。
- 不改任何产品源码/接口/默认参数/数值模型/采样标准；验证脚本只放verification/A09，完整响应、场景/星历和编译运行根在ignored verification/runtime/A09。
- 默认演示双星SAT-A/B根数a7530612m、e0.000850、i64.8、RAAN120、ω270、M30/0，历元2026/07/01 10:00:00UTC，J2_NUMERICAL。目标原1507045字节CSV；首先固定发射偏移2880秒，目标采样1秒。此固定偏移验证不冒称STK重做361次最优搜索。
- 当前标准SPACE载荷距离1000—4000km、Web方位90—270°、俯仰−90—0°、跟踪0.3°/s、太阳回避90°、启动0.07°/s（仅元数据）。逐项关闭条件属于具名诊断场景，不回写生产默认。
- Web坐标基为T,N=R×T,R；STK卫星VVLH CBI为T,−N,−R，普通Access方位需A_STK=(360−A_Web)mod360，俯仰同号。当前对称90—270不变，另用非对称角窗口检查方向，不能仅凭对称窗口认定轴向正确。
- STK SimpleConic/Rectangular传感器不冒充Web方位/俯仰角范围；优先在观测Satellite对象配置Azimuth/Elevation/Range/LOS Access约束并对照官方定义。
- STK原生角速度瞬时定义与Web相邻LOS夹角/间隔不同；STK原生太阳回避有遮挡豁免、星历/视太阳方向，Web弹道始终回避且使用地心近似太阳。必须分别列定义与实际差异，不直接宣称只填同数值即完全相同。
- STK关闭光行时和像差等Web未实现的修正，地球遮挡使用椭球并显式记录；独立STK输出必须真实计算取得。软件轨迹可作为受控共同输入，不能被当作外部绝对轨道真值。
- 中文注释/报告/提交，Git格式类型(前端/后端/前后端): 中文说明 [T30]，正文A09；旧Txx/A01—A08报告/标签/参考不改，当前交付元数据只刷新身份。

## A09：实机场景与可复核测试

**Files:** verification/A09中的输入导出探针、STK自动化、比较脚本、实际命令双流、配置摘要与验证报告；docs/补充验收台账.md；本计划。运行场景及完整HTTP数据只在verification/runtime/A09；必要独立Access参考可按来源/SHA纳入test-data/reference/stk/A09。

- [x] 检查已装STK/运行工程；新实例成功打开独立场景，记录版本与PID；未关闭已有工程。初次PS5脚本UTF8无BOM解析失败后修正编码再启动，失败保留说明。
- [x] 冻结软件输入，真实HTTP取得固定偏移的逐条件结果；用现有实际外推/地固转惯性入口导出同刻全状态，不另写动力学或只导可见点；绑定源码/JAR/CSV及实际请求SHA。证据见verification/A09/软件基准与受控输入报告.md及software/input-summary.json、http-summary.json。
- [x] STK导入共同输入，创建两观测星/目标及逐项约束；真实Access及AER数据导出，另测非对称方位。独立STK计算共12组Access，STK运行结果位于ignored verification/runtime/A09/stk-results-rerun。
- [x] 对齐同UTC样本判定与连续窗口，列弧段起止/时长/点数差；角速度、太阳、地球与坐标/时标因素逐项归因，未实现等价的条件不写全通过。10,284个Web离散样本与STK连续Access在20微秒容差内零差；角速度直接比较STK Constraint Data.FromAngularRate 与Web实际采样间隔有限差分，原始末点偏差保留，太阳方向/遮挡边界按实测披露。
- [ ] 独立STK传播未在本次载荷约束对照中执行。当前比较为隔离载荷判定而共用输入状态，不能据此报告独立传播位置/速度精度；作为独立模型/真值任务另行登记，不把当前Access一致性替代绝对精度。
- [x] STK Connect SaveAs已保存可重开场景及对象/星历引用到ignored verification/runtime/A09；验证报告记录原始来源、实际STK数值和边界。独立复审Spec Pass、Quality Approved；当前提交及远端同步按台账收口。
