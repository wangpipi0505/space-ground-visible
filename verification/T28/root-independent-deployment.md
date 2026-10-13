# 根独立部署边界验收

[实际汇总](root-deployment-summary.json)绑定正式v3交付JAR `1ce148b106493a0229594491f11d204b5a9d632cd0bad9b018233d97a04657f9`。复制完整冻结v3包到含空格的`root deploy package`，逐个文件字节核对相等；从另一个含空格、无源码和构建产物的`root unrelated cwd`，实际用Windows PowerShell 5执行包内启动脚本，参数为Java8、独立含空格运行根、demo、18180和明确前端来源15180。

后端PID58664实际Java1.8.0_472启动，外置Orekit从自身交付包加载，独立H2在运行根建库，Flyway实际应用10条迁移。没有以验证ManagedServer替代本轮启动脚本，运行命令不引用原工程、baseline类或源码target。demo/H2验收不冒称MySQL或达梦生产库已运行。

Node标准库验收网关从自身包的正式frontend静态产物提供15180页面，并代理实际18180 API；该网关与产品Nginx配置的覆盖范围分开报告。Chrome真实打开生产静态地址后，从浏览器`location.origin=http://127.0.0.1:15180`直接fetch独立18180：卫星GET200、目标筛选JSON POST200，其真实跨域预检及响应可读，pageerror0。见[root-deployed-page.png](root-deployed-page.png)。不是APIRequestContext绕过浏览器CORS，也不是Vite开发服务器或路由mock。

最后通过真实前台PTY Ctrl+C触发Spring ShutdownHook：应用记录Graceful shutdown complete及Hikari Shutdown completed，Java进程消失、18180实际socket复绑。Ctrl+C使外层PowerShell工具命令退出1；本轮不虚构已退出Java的自然退出码0。实施者独立ManagedServer正常context.close/退出码的证据另列。根Chrome已close，Node网关27232按实际命令/PID核验后清理。

[干净候选数值汇总](root-clean-numeric-summary.json)使用干净副本的classes和Orekit数据，重新生成弹道15、空间9及原新完整空间21文件，JSON Decimal结构/数值/数组顺序全等、CSV/报告字节全等，0差异，不设容差。[class绑定](root-clean-class-bindings.json)证明T27全部287旧class与本候选逐字节一致，仅新增CORS配置，当前classes与候选一致，后端JAR没有Vue静态页或test-only静态fixture。

新工作区遗留的T06 ignored生成static经绝对路径范围和git ls-files为空核验后，移动到本项ignored runtime备份；原软件目录未操作。后端当前构建不再依赖该生成物。此为工作区清理，与干净副本中先复现再修复的测试资源问题分开。
