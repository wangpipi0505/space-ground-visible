# 空间与弹道目标可见性工具

以空间目标可见性软件为底座，集成弹道窗口、拟合、模拟及重定时，采用前后端独立的 B/S 架构。

空间功能包括目录筛选、SPACE/GROUND 载荷配置、站星下传、单星/共同观测、同步/异步规划、取消及历史记录。弹道功能包括多目标多卫星多载荷偏移搜索、102 包拟合、模拟、重定时和受管理文件上传/预览/下载。全部生产计算经 Vue 页面及 Spring Boot 服务执行。

## 目录与版本

| 目录 | 用途 |
| --- | --- |
| `backend/` | 标准 Maven 工程，`api/application/domain/infrastructure` 分层；Java 8、Spring Boot 2.7.18、Orekit 12.0 |
| `frontend/` | 独立 Vue 3 / TypeScript / Element Plus / Vite 工程；依赖版本由原 `pnpm-lock.yaml` 冻结 |
| `deploy/` | 正式独立构建、打包、启动脚本及 Nginx/外置配置模板 |
| `docs/` | 接口、载荷、数值与后续方案；`docs/examples/` 提供可执行 HTTP 示例 |
| `test-data/` | 有来源/校验值的必要参考数据及黄金基准，属于回归输入，不是生产输出 |
| `verification/` | 各事项实际命令、报告及审查证据；`verification/runtime/` 为忽略的运行数据 |

已实测工具组合：Java `1.8.0_472`、Maven `3.9.16`、Node `24.16.0`、pnpm `11.21.0`。工具版本与依赖版本分别管理，不自动升级原锁文件或后端依赖；详见[部署说明](deploy/部署说明.md)。当前进度与完成条件以[待办事项台账](docs/待办事项台账.md)为准。

## 本机快速演示

从项目根运行以下 PowerShell 命令，将 Java8 路径调整为本机 JDK。目标交付目录必须尚不存在，运行目录独立于源码。脚本也兼容 Windows PowerShell 5.1。

```powershell
$java8 = 'C:/Program Files/Eclipse Adoptium/jdk-8.0.472.8-hotspot'
./deploy/build.ps1 -Component backend -JavaHome $java8
./deploy/build.ps1 -Component frontend
./deploy/package.ps1 -Destination 'G:/space-ground-delivery/demo-v1'
& 'G:/space-ground-delivery/demo-v1/start-backend.ps1' -JavaHome $java8 -RuntimeRoot 'G:/space-ground-runtime/demo-v1' -Port 8080 -Profile demo
```

`demo` 使用独立 H2，首次创建一观测星、一目标和一站，轨道为标注的非生产样例。后端健康检查为 `GET http://localhost:8080/api/v1/space/health`，返回 `{"status":"UP"}`；弹道 `/api/orbit/health` 与 `/api/trajectory/health` 返回文本 `OK`。停止前台脚本使用 Ctrl+C 并等待正常关闭。

开发联调可在另一终端执行：

```powershell
Set-Location frontend
pnpm install --frozen-lockfile
pnpm dev --host 127.0.0.1
```

打开 Vite 打印的地址；默认 `/api` 代理到 `http://localhost:8080`，空间接口根 `/api/v1/space`，弹道辅助根 `/api/v1/ballistic`，原计算根 `/api`。开发后端端口不同，在启动 Vite 前设置 `VITE_DEV_API_TARGET`，它只配置开发代理；生产后端地址按 Nginx 等反向代理配置，不由该变量决定。其余完整根变量见[前端载荷说明](docs/前端载荷配置说明.md)。Vite 仅用于开发；正式部署使用打包的 `frontend/` 静态页。

## 部署与存储

正式包中 `backend/server.jar`、`frontend/`、`orekit-data/`、`config/application-deploy.yml` 分开交付；后端不内嵌前端，也不依赖原工程目录或桌面环境。按[部署说明](deploy/部署说明.md)配置数据库及 Nginx 同源 `/api/` 代理。正式默认 MySQL；达梦驱动/手工建表适配见后端配置，集成方须在实际库环境验证。H2 演示结果不表示生产 MySQL/达梦已实跑，Nginx 模板也与已实测标准库验收网关分列。

运行根包含数据库、上传、结果、临时文件、登记元数据和日志。`BALLISTIC_STORAGE_ROOT` 或各子目录变量、限额/TTL 等见[部署说明](deploy/部署说明.md)；运行数据、密码及本地环境配置不得提交。浏览器文件须先上传得到 `fileId/serverPath`，不能把本机 `C:\\fakepath` 或绝对路径作为后端输入；生成结果通过登记、预览、下载获得原字节。

## 接口、工具与验证入口

- [Swing与Web参数对照（A06）](docs/Swing与Web参数对照.md)：恢复弹道卫星六根数表、四种示例星座、目标与搜索参数，以及102包拟合、模拟、重定时的主要表单和原UI默认值。空间入口保留；弹道一级入口为“可视窗口计算”“目标弹道拟合”，拟合三子页作为二级页签。标准SPACE/GROUND载荷保持，旧GUI独立3D与非零全局UTC偏移的HTTP边界逐项列明；[参数与实际验证报告](verification/A06/参数对照与验证报告.md)。
- [原始STK精度补充报告（A05）](verification/A05/STK原始数据精度补充测试报告.md)：三份原始STK参考、7371点弹道及8641点卫星位置/真实速度对照、旧诊断时段与四种窗口场景；[独立审查](verification/A05/独立STK精度审查报告.md)。原精确模拟请求缺失，分别采用具名ECI/ECEF端点重建；独立STK Access起止表缺失，绝对窗口精度仍未测。在项目根运行 `python -X utf8 -B verification/A05/delivery_check.py` 可只读核对参考与已交付证据，完整物理重算的本机资产要求见报告。
- [专项精度测试报告（A04）](verification/A04/精度测试报告.md)：新编译77项专项测试、15/9/21严格新旧对照、同刻插值留出误差和时间/坐标边界；[独立复审](verification/A04/独立精度复审报告.md)。在项目根运行 `python -X utf8 -B verification/A04/delivery_check.py` 可只读核对已交付证据，完整数值重算的本机资产前提见报告第10节。
- [软件功能使用说明（A03编写、A06更新）](docs/软件功能使用说明.md)：安装/正式启动、空间操作链、弹道两级菜单与参数表、完整非生产输入与真实截图、时间/单位/文件失败恢复；[A03历史来源核验](verification/A03/验证报告.md)。
- [新版完整接口文档（A02）](docs/接口文档.md)与[OpenAPI 3.0.3](docs/openapi/openapi.json)：35条活动操作、完整嵌套字段、实际状态/样例、载荷/UTC/CSV和文件生命周期合同；[A02验证证据](verification/A02/验证报告.md)。
- [T29 HTTP 说明与可执行示例（历史来源）](docs/接口使用说明.md)：保留原示例入口；当前完整字段、序列化及状态合同以新版 A02 文档为准。
- [载荷、时间与 CSV 口径](docs/载荷时间与文件口径.md)：十字段单位/特殊值、两种时间口径及速度约定。
- [独立外推工具及 Orekit 后续方案](docs/独立外推工具与后续方案.md)：普通 Java8 调用、模型与线程边界，D01—D06 进入条件。
- [T03 冻结基线](docs/接口与数值口径基线.md)：历史契约设计快照；当前活动接口合同以新版 A02 完整接口文档为准。
- [T27 精度及完整 B/S 报告](verification/T27/验证报告.md)、[T28 独立交付报告](verification/T28/验证报告.md)、[T29 文档/示例/注释核验](verification/T29/验证报告.md)：实际验证范围、差异与限制。
- [开发与提交规范](docs/开发与提交规范.md)：中文注释、原栈、编码及提交规则。

A06的初轮输入差异已集中修复，最终131项前端测试及实际HTTP复验通过；[修复与复测](verification/A06/审查修复与复测报告.md)、[独立复审](verification/A06/独立输入与导航复审报告.md)记录具体范围和边界，提交同步状态见[补充验收台账](docs/补充验收台账.md)。

本阶段保留两套原传播数学主体、接口及文件格式，只提取公共工具和已证实可共用的基础数学。后续 Orekit 替换仍暂缓，未经同口径验证不称精度等价。
