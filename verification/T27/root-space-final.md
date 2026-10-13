# T27 根独立空间工作台验收

使用 Java 8、headless 后端18178、独立 Vue/Vite 地址15178、Chrome中文及Asia/Shanghai时区。最终冻结运行包 SHA-256 为 `6f0cde71a608294585c40e512d9c649c80d826627d1c16f851b93bb1102e20cb`。H2为本轮原新21文件对照生成的空间fixture独立副本；不修改原软件或其他验收服务的数据库。

[最终实际调用摘要](root-space-final-summary.json)保留34次空间HTTP真实请求和响应，console/pageerror均为0。

1. 浏览器输入北京时间2026-07-01 18:00–18:10，真实下传请求为UTC10:00–10:10，前置保护0、粗步长60、细步长1。选择原T04星下站后，下传返回10:00–10:07:21共441秒，完整JSON通过Decimal严格等值比较原空间黄金，没有数值或结构差异。
2. 用实际下传链路提交单星目标异步规划，获得441秒非空窗口。第一轮默认细步长0.001；取消后恢复轮显式60/1。真实选择确认200，恢复任务结果的完整持久化JSON与HTTP实际结果相等。明细读取请求、结果及选择JSON；19位记录ID使用字符串保留。
3. 使用粗步长0.005、细步长0.001触发末批完成附近取消。任务 `4e87ec6a-c3f2-4cd8-9042-f9f3c2c1828c` 的DELETE实际返回200/RUNNING，页面继续显示执行中并轮询；最终GET为CANCELLED，结果GET409，数据库记录CANCELLED。即使已完成1/1批次、进度100%，已接受取消也不发布成功结果。恢复到60/1后新任务 `60ad8ca2-6fb5-4bac-8c83-97436256dd68` 正常成功441秒。修复前反例与原响应独立保留在[root-space-cancel-failure.json](root-space-cancel-failure.json)，没有覆盖失败轮。
4. SPACE距离200–8000km与GROUND距离1000–4000km、角速度1分别真实保存。切换载荷后SPACE仍回显200–8000，刷新页面并重新选择卫星后GET与界面均保存原配置。
5. 恢复任务真实确认选择后读取明细、保存备注，再从表格删除自身记录，DELETE200、行消失、记录明细GET404。没有删除外部数据库或生产记录。

先前PowerShell/Python控制台显示部分中文乱码；重新按UTF-8读取原摘要并统计，实际文件U+FFFD为0，顶层备注和真实PUT响应均精确为“T27修复后空间非空完整B/S验收”，不是证据文件或产品中文损坏。另用ASCII脚本Unicode转义输入、Python subprocess直接保留CLI UTF-8字节，在独立冻结包/H2副本中，对本轮另一个成功任务真实GUI保存中文备注并刷新回显；实际PUT、GET均200，正文精确等于同一中文，console/pageerror0。见[root-space-chinese-remark.json](root-space-chinese-remark.json)与[root-space-chinese-remark.png](root-space-chinese-remark.png)。首次补证脚本相对URL缺少APIContext baseURL而失败，改验证脚本为完整URL后复测；不是产品故障。

根最终规划服务PID55628执行真实context.close，原日志uncaught0、MAIN_RETURN、没有残留非守护线程；关闭调用自然WaitForExit返回true。再次核验进程消失和18178实际socket复绑。该轮保留Tomcat关闭时线程提示，不能把自然退出等同于长期热部署无泄漏。随后中文补证服务PID16896自然退出码0、0.681秒、uncaught0、MAIN_RETURN、stderr0、端口复绑，见[root-space-chinese-shutdown.json](root-space-chinese-shutdown.json)。两个根Chrome会话已关闭，Vite15178两个Node进程按PID及命令身份核验后清理；Vite进程清理不冒称应用自然关闭。

另独立冻结包/H2/Chrome真实勾选基准观测星1及共同观测星3，在“目标观测组合”控件选择两星并输入400秒，点击添加组合约束后提交。实际请求`observerSatelliteIds=[1,3]`、`observationConstraints=[{satelliteIds:[1,3],minDurationSec:400}]`、粗细步长60/1，异步任务`86be0d5b-dfb8-4d0f-9221-57fe47231df2`实际200返回两星共同441秒，console/pageerror0，见[root-space-common.json](root-space-common.json)及[root-space-common.png](root-space-common.png)。没有修改轨道、原黄金或降采样。该服务PID20236也真实context.close自然退出0、uncaught0、MAIN_RETURN、端口复绑，见[root-space-common-shutdown.json](root-space-common-shutdown.json)；Chrome关闭、Vite40468身份核验后清理。

浏览器覆盖实际单星和双星共同非空流程及状态、记录、载荷操作；复杂共同可见约束仍由本轮21文件原新版完整计算对照覆盖。本文不虚构GUI普通异常FAILED场景。
