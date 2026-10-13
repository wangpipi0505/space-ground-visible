# 根代理独立 T26 验收

本轮独立运行最终候选 `311fc2a9019de875a899f3e8089deca06f18a157f110a1fa4453cefb74626c4b`，服务 `final-root-nio-v2`，Java8/headless、端口18176、PID62520，独立H2/storage；前端15176为实际Vue开发服务。启动日志确认来源为该服务目录 `server.jar`，未使用旧包冒充最终候选。

## 异步任务及错误恢复

实际执行 `python -X utf8 verification/T26/root_async_http.py final-root-nio-v2`。两日窗口、coarse/fine均1秒；读取RUNNING后DELETE返回RUNNING，另轮询得到CANCELLED，结果接口409。非法时间段同步接口400；相同非法请求异步提交200后FAILED，结果409。随后有效请求SUCCEEDED，同一请求的同步与异步结果JSON结构/数值/顺序精确相同。CANCELLED、FAILED、SUCCEEDED三终态各通过独立任务结果分页读取确认持久化，证据 `root-async-http-summary.json`，原响应在对应服务runtime目录。

根初版验收脚本曾把异步非法时间段误设为提交时400，已依据实际原实现修正为200接受/FAILED终态，未修改产品契约。空间Instant字段非法文本实际500；原与新 `ApiExceptionHandler.java` SHA均 `9f349fac5660161ef0a7cb2f3b2bc2b458862ecfb5c2d805b58713ca114f8384`，原全局Exception通路保持；500包含安全文案及requestId，随后请求恢复。没有按弹道专用处理器400口径修改空间接口。

## 浏览器完整轨迹、等待、失败及恢复

真实Chrome、Asia/Shanghai，使用原生文件选择器上传完整原轨迹：8560行、1507045字节；明确手工采用原非生产卫星根数，标准SPACE距离0/0、姿态双端null、跟踪-1、太阳0、启动-1。偏移1200起点、步长10、31次，目标采样1秒、真实点输出1、J2_NUMERICAL。实际请求等待时显示“正在计算真实窗口…”，计算按钮和新增卫星禁用，成功后恢复。

31项响应保留全部偏移和13062点，原始响应浏览器下载约4MB（精确字节/哈希在 `root-browser-summary.json`），best偏移1500、1弧段、554.900000215秒。原文件浏览器下载与完整冻结源逐字节相同，SHA `5734dbfce47108374d390497f40b61a1246c451d945604c0d61c5b6f2005fec8`。后台原数值黄金回归由独立原版运行器验证，本31次新组合是操作/规模证据，不作为替换原黄金。

真实坏列CSV上传201后，原窗口接口400“弹道文件未解析到有效数据。”，页面清除旧成功并恢复可操作；原生重新上传完整轨迹201，再计算3次、采样仍1秒，返回200/best1220/1弧段180点，错误清除。实际201响应含真实ID/serverPath/大小，摘要收录；3次为错误恢复单独场景，未用缩减31次原规模声明性能改善。

成功主流程无pageerror。同一Chrome会话在修复前后各跑一次真实坏列400，因此console保留两条预期HTTP400资源报错，pageerror为0；未写为全程零console。busy原始属性采集中嵌套fieldset自身disabled=false，但外层fieldset禁用仍让控件继承禁用；仅以原生计算按钮和新增卫星实际禁用为已验收结论，不虚称所有fieldset自己的disabled均true。截图 `root-window-busy.png`、`root-window-success.png`、`root-window-error.png` 为实际最终候选。

## 关闭与验证脚本迭代

第一轮root-nio使用旧候选公共JAR，另一轮更新公共包时旧JVM还在运行，按需加载受污染，STOP后出现NoClassDefFoundError且端口仍占用。根后续新服务也因端口在用启动失败；这两轮不计关闭通过。仅核对精确62368/PropertiesLauncher/root-nio命令后清理该污染JVM，属于测试清理，不是优雅关闭证明。

修正 `start-server.ps1`：每服务启动前独立冻结server.jar，已有副本或STOP拒绝覆盖、使用新Name。最终独立服务62520通过创建STOP真实context.close，`WaitForExit(20000)` 返回true；日志 `T26_CONTEXT_CLOSE_END uncaught=0`、`T26_MAIN_RETURN`，无NON_DAEMON_REMAINING，CIM该PID消失，随后原18176端口真实socket重新绑定成功。未强制结束最终候选JVM。

默认NIO最终日志仍保留：故意非法Instant的ERROR、故意非法异步任务的WARN、Spring默认异步执行器提醒，以及Tomcat停止Web上下文时System Clock/space-planning-task/space-visibility线程提示。最终context.close后自然退出证明本轮没有残留非守护线程，不能由此声称日志无WARN/ERROR或所有生产规模无资源风险。浏览器已关闭，前端开发进程另作精确测试清理，不能与JVM自然退出证据混淆。
