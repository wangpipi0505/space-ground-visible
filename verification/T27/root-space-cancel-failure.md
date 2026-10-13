# 空间浏览器取消失败的实际反例

根独立服务18178/PID64812、候选311fc2包、Java8/headless/独立T27 H2，实际Chrome15178/AsiaShanghai。本轮原生GUI已得到原版相同的441秒下传、441秒非空单星观测、目标选择/结果明细/备注和双profile保存；随后在同一下传合法窗口，将粗选设0.005、精选保持0.001秒，提交真实任务96d7219d-2d1d-43a4-9c62-56bcb34191d4。

DELETE返回200，响应状态RUNNING；页面即时显示“任务状态：已取消”“配对0/0”并停止轮询。约5.1秒后实际服务端status为SUCCEEDED，result接口200，非取消409。证据[root-space-cancel-failure.json](root-space-cancel-failure.json)保留真实请求、取消响应、GUI文字与随后GET原结果；原始CLI/全部请求在ignored runtime/T27/root-space中，没有构造假响应。

根核对冻结原空间源码也存在两段代码：原useVisibilityWorkbench在取消返回后无条件强置CANCELLED，原PlanningTaskService工作正常返回直接markSucceeded，而最后批次后未再检查取消。这是原空间取消边界缺陷，本次合并完整验收暴露；不属于轨道算法精度差异。T26有限取消场景实际在批次开始前取消而通过，本轮新场景不能沿用该成功证明。

已按T27待修复处理：前端以真实GET/DELETE状态继续轮询并保护终态与任务ID；后端以同一任务的原子终态转换处理取消与成功竞争，已终结任务不再被迟到取消污染。API字段/地址/DTO保持，未取消的物理计算和数值标准保持；确定交错RED/GREEN、最终包及本浏览器场景需重新验收，不能仅看到页面文字就宣称取消成功。

根已保存修复前完整43次HTTP/GUI原记录，关闭t27-root Chrome，旧64812服务创建STOP真实context.close/uncaught0/mainreturn自然退出。该关闭与取消失败是不同验证边界，前者通过不能抹去后者。本条失败保留到最终报告，不将其写成已通过的取消测试。
