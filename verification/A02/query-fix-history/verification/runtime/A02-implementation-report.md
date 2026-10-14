# A02 实现交接报告

状态：**DONE_WITH_CONCERNS**，文件已稳定，供根独立审查及第三方OpenAPI校验。当前仅A02；未执行A03/D；未修改产品/锁/模型/两原目录，未操作Git/标签/台账/远端，未重跑业务或压测。

## 交付

- `docs/接口文档.md`：259942B，35操作完整参数/请求/响应/错误/取消/内容类型及PowerShell curl，完整嵌套/继承字段、十标准字段与两身份、旧摘要分列、Long/字符串ID、两UTC、21/22/20列CSV、速度/dt/间隔、透明selection与Vue推荐结构、outputDir/上传/登记/下载/预览/TTL/租约/重启/路径边界、普通Java工具入口。
- `docs/openapi/openapi.json`：478553B，OAS3.0.3，35操作/84组件/682字段定义（677组件顶层定义+metadata内嵌5字段），SHA256 `bfeb596dcb4dd1879c233bdbc2ec314f62ad97ea18f9763b4a6163a176ddba35`。
- README新版入口，并明确旧T03/T29为历史来源；原历史文档不改写。
- `verification/A02/验证报告.md`、68完整实际样例（35成功+33错误边界）、15请求文件、源码/JAR/前端锁指纹、实际命令stdout/stderr/exit/时间、语法/结构/curl验证与编制失败转录。单一schema源是`build_contract.py`，中文Markdown与OpenAPI共生成；没有两个独立schema源。

## 最终本项新命令及证据

最新执行 `python verification/A02/run_verification.py`，2026-10-14 UTC 01:07:00.430—01:07:08.787（北京时间09:07），最终5命令全部exit0、stderr空；41件交付/证据manifest在`verification/A02/artifact-manifest.json`，命令及原输出`commands.json`、`generate.txt`、`compile.txt`、`curl-transport.txt`、`contract.txt`。

- Controller注解独立抽取35 / A01矩阵35 / OpenAPI35完全一致，含正式继承health，不凑未扫描旧Controller。
- 184 `$ref`有效；916 schema对象、91默认值、64枚举值、474 nullable位置自身校验；直接`$ref`无兄弟键，nullable同层有type；可空对象引用采用anyOf+仅null分支，不依靠allOf忽略约束。日期format/pattern、required、数组、类型/单位描述均核验。
- 59公开源类型/521字段逐声明对照；全部247原响应SHA匹配，224 JSON/文本递归结构及84可读取成功请求体，23二进制/空体分列；172271值检查无错误。未将这些结构检查冒充新业务测试。
- 286本地Markdown链接、185锚点、74 JSON围栏语法通过。
- 真powershell.exe调用curl.exe35操作，接收器核验方法/path/query、15请求JSON及multipart源CSV原字节，全部exit0；接收器已关闭，不冒充业务服务重跑。curl版本8.21.0，Python3.14环境见environment.json。
- 既有A01正式JAR只重算SHA：`d9c9956c389e79d1d52619c3e8310fb4b887d2009ad8f4c226d597cc030e3c71`，与A01报告一致；64源文件逐SHA，前端锁SHA仍`bf63ad64c42dd1ce18737b0b0c917e4fdd8cde2da005a9a55bae23a174bd7fc8`。

## 关切与已明确的静态限制

根仍需独立审查，固定swagger-cli工具尚未由本实现代理运行，未提前写工具通过；没有代码生成器兼容性实跑。本项没有业务/性能/外部MySQL/DM/MinIO/Nginx重跑，这些保持A01原证据范围。

OAS3.0.3无法完整表达成对null、按类型0/-1、min/max比较、任务时间先后、粗精选关系、目录/组合成员、真实路径及租约/TTL、outputDir是否出现、取消竞态等；正文及description均明确真实规则。selection三个值为透明JsonNode，只有selections非空数组强制；附完整Vue推荐结构而不伪造服务器强校验。动态A01ID/服务器路径已过期，例子明确取当次响应；curl只证明传输语法。

已修正旧资料笼统null省略口径：默认Jackson显式null保留，只有各字段NON_NULL/NON_EMPTY省略；手写窗口null独立。标准preview请求仍完整绑定14字段DTO（含2个标准路径不读取的旧兼容字段），响应严格12字段。负保护/断链可在链路执行归一化失败后体现为200 skippedLinks，未虚构所有非法参数都400。记录selection备注仅存原文，不声称其实际更新记录remark。

编制期工具失败均在development-failures.json转录并解释，最终未解决失败0。未申请/重复询问常规许可。下一步由根独审、修复收敛、台账/中文提交与acceptance标签；本代理不会推进这些权限外步骤。


## 第一轮独审修复实现交接（2026-10-14）

前文为首轮实现历史记录；其中“原输出保存”及旧命令exit0不能证明四份失真stdout的完整性，已在history-v1逐字节留存并纠正。以下是本轮新的实际结果，不覆盖旧记录。

# A02 第一轮独立审查修复报告

状态：**DONE_WITH_CONCERNS**。I01—I06、M01—M02 已一次覆盖修复；当前六条 A02 检查命令全部 exit 0，八组限定回归通过，独立字节审计 exit 0。此状态是实现交接，不代替原 reviewer 复审或根的新版外部格式核验。

本轮仅修改 A02 生成/核验辅助、生成的接口文档/OpenAPI 及 A02 证据；无产品、依赖、前端锁、两原目录、A01/Txx 原证据、Git、台账、计划或 .gitattributes 操作。没有启动 Java 业务、浏览器、完整套件或压测，没有执行 freeze_baselines.py，也没有推进 A03/D。

## 历史留存与失败定位

修改前逐字节保存 **47 件**旧文档、schema、生成器、核验器、README、原命令/清单/报告/stdout/stderr 和 runtime 实现报告到 [history-v1](../A02/history-v1/byte-manifest.json)，该 byte-manifest SHA256 为 `184a702d5912d2e3abe513c94ddc65c0708a2b35ea6374547ed79a400bbd54c1`。保留的旧 schema SHA256 为 `bfeb596dcb4dd1879c233bdbc2ec314f62ad97ea18f9763b4a6163a176ddba35`。旧 external-openapi-check.* 和独立格式核验.md 均未改写，它们继续只证明旧 schema；根对新版重新检查。

[修复前限定命令](../A02/review-before-command.json) exit 1，[完整失败输出](../A02/review-before.txt) 实际捕获全部八项红灯，包括 description 污染 74 处和四份日志 SHA 不一致。首次修复重跑 [fix-attempt1](../A02/fix-attempt1/commands.json) 的 contract exit 1，原因是历史 lease-full-download 未保存响应头；其余前四命令 exit 0。第二次 [fix-attempt2](../A02/fix-attempt2/commands.json) 的原五命令全通过、review-fixes exit 1，原因是限定测试错误假定 VisibilityPlanRequest 继承 TargetQueryRequest；已改用真实 CoverageWindowResponse 继承 WindowResponse。本项产品行为无此失败，两次原失败输出、核验脚本和字节清单均独立留存。

旧 stdout 的实际保存字节与 commands.json 绑定 SHA 不一致：curl-version.txt/generate.txt/curl-transport.txt/contract.txt 的 CRCRLF 分别为 4/1/6/28。仅还原 CRCRLF 为 CRLF 后 SHA 与旧记录一致，定位为 Windows write_text 换行转换。**旧命令 exit 0 不能表述为旧日志完整性通过**。现在 stdout/stderr 使用 write_bytes 保存，SHA 从实际保存文件 read_bytes 计算；新六命令 12 份日志的 SHA 全匹配且 CRCRLF 为 0。

## 逐项修复与限定覆盖

共同限定测试文件为 [check_review_fixes.py](../A02/check_review_fixes.py)，实际结果为 [review-fixes.txt](../A02/review-fixes.txt)。测试复用实际递归校验器，读交付文件和只读源码；不替代 Java 业务重跑。

| 项 | 最小修复 | 本次真实覆盖 |
| --- | --- | --- |
| I01 | FitRequest/SimulationRequest/RetimeTrajectoryRequest.outputDir 删除 HTTP default；x-java-initial-value 说明内部点目录，x-http-omission-behavior/中文表写明缺失分配管理 results 独占目录；说明显式值按字段存在性走旧分支 | 三个交付字段没有 default，三个正式 Controller 的 body.has("outputDir") 分支源码核对 |
| I02 | 下传 satelliteIds/stations/links 保持 required/minItems=1，移除 nullable | 三数组各缺失/null/空/有效非空四类，共 12 例；前三类拒绝、非空接受；核对三处源码 null/empty 拒绝 |
| I03 | PayloadDef 保留兼容宽 DTO，不无条件给标准范围；description 与正文明确 SPACE/GROUND 标准分支条件和机器 schema 限制。StandardPayloadRequest/PayloadProfileRequest 保持距离≥0、太阳半角0..180 | 旧中文带三项无关负值可绑定；只读源码确认旧 forPayload 不读取它们。两个严格标准 schema 的负 min/max、负太阳、太阳181 共8例拒绝，有效标准例接受；未放宽真实标准合同 |
| I04 | 只提取紧邻声明的完整 Javadoc，不能越过 */，删除固定650字符截断；保留中文源业务说明，重新生成两文档 | 扫描全部842个 descriptions 及中文Markdown，无 private/public/protected 声明污染；长中文注释与相邻字段/getter 小输入验证完整提取 |
| I05 | stdout/stderr 原字节保存，SHA绑定 read_bytes；保留旧错误证据后重跑必要 A02 检查 | 原五命令＋限定回归全通过；审计全部12日志及交付清单实际字节、47旧留存文件及两次失败清单；旧四文件差异如上单列 |
| I06 | 下载200内容保留常见类型并新增 */* binary，正文明确 Content-Type 来自合法登记类型；按实际响应头先精确、再主类型范围、再 */* 匹配 | 233条有实际 Content-Type 的 A01非空响应按类型匹配；限定 text/csv带charset、text/plain、Excel/xlsx四例及text/*优先规则通过。1条无头历史事件不核验类型，明列缺口 |
| M01 | 用已加载 OpenAPI components.schemas 展开$ref/allOf，独立展开 Java父模型；不再用生成器 properties 自比 | 59类型/534含继承字段对照；删除所有A01样例未出现的 ObservationConstraintRequest.minDurationSec 被检出；删父 WindowResponse.durationSec 也在 CoverageWindowResponse 被检出，共两种篡改 |
| M02 | JSON接收体==请求文件read_bytes 且 Content-Type==原-H参数；multipart用标准库MIME解包断言CSV原字节及有效multipart媒体类型 | 真 PowerShell/curl.exe 35操作，其中15份JSON文件逐字节/媒体类型核对，multipart完整CSV核对；增加空白但保持JSON结构相同、改text/plain两种篡改均拒绝，监听器已关闭 |

## 精确命令、输出与 SHA

工作目录 `G:/5-YG/space-ground-visible`。入口为 `python -X utf8 verification/A02/run_verification.py`；最终六命令于 `2026-10-14T01:33:07.522374+00:00` 至 `2026-10-14T01:33:16.766089+00:00` 执行。完整参数数组、时间、退出码、stdout/stderr路径及SHA见 [commands.json](../A02/commands.json)，字节审计命令见 [byte-audit-command.json](../A02/byte-audit-command.json)。表中输出为实际保存字节。

| 命令 | 精确命令 | exit | stdout | stdout SHA256 |
| --- | --- | ---: | --- | --- |
| curl-version | `curl.exe --version` | 0 | [curl-version.txt](../A02/curl-version.txt) | `462db4e956d97703eae92a1863090a26987ab7b9bc55c43b4e11565918cc56a4` |
| generate | `C:\Python314\python.exe -X utf8 verification/A02/build_contract.py` | 0 | [generate.txt](../A02/generate.txt) | `ae53f0d212b20022a57806b4e5e71638fff75feb1da0a2c1f7a226ab032c075c` |
| compile | `C:\Python314\python.exe -m py_compile verification/A02/build_contract.py verification/A02/validate_contract.py verification/A02/verify_curl.py verification/A02/run_verification.py verification/A02/check_review_fixes.py verification/A02/audit_evidence.py` | 0 | [compile.txt](../A02/compile.txt) | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| curl-transport | `C:\Python314\python.exe -X utf8 verification/A02/verify_curl.py` | 0 | [curl-transport.txt](../A02/curl-transport.txt) | `b149c8a5ff753ac0f7d976a9457d7cc31c1c64d8c32680d01a07cf3241228091` |
| contract | `C:\Python314\python.exe -X utf8 verification/A02/validate_contract.py` | 0 | [contract.txt](../A02/contract.txt) | `78b5ac77882ffa7c3d7f8ce717faef7bfc24729d164b8dd93d8fb44f3b1eebd7` |
| review-fixes | `C:\Python314\python.exe -X utf8 verification/A02/check_review_fixes.py` | 0 | [review-fixes.txt](../A02/review-fixes.txt) | `f107b8d6cfdcae47359d49ca2a6612e9ddae38e708e3d90077a78403ff52ca11` |
| byte-audit | `C:\Python314\python.exe -X utf8 verification/A02/audit_evidence.py` | 0 | [byte-audit.txt](../A02/byte-audit.txt) | `c158ffbd024e0f62018fd122db19b9cc4882b12d14c16a59de089ecbe4ebf43c` |

各 stderr 文件均存在且空，全部实际 SHA256 为 `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`；compile.stdout 同样为空。修复前及两次中间失败的精确命令/输出/SHA在上述留存 JSON 中，不手改其状态或计数。

## 当前合同与字节核对

当前 OpenAPI SHA256：`4455f9d0e1c90b7d02982e6e030870ea2074dac4fb7e0c88db5e1d1e8f0a95b4`，442206B；中文接口文档 SHA256：`778027a45eb39bc08af694f2b0d45ad7497de641bd64dff86652e537a5c86fce`，224532B。全部 Controller/A01/OpenAPI 操作35一致，84组件、682字段定义、184引用、916 schema对象、88默认值、64枚举值、471 nullable位置；247原响应SHA、224 JSON/文本与84成功请求体结构核验、23二进制/空体分列，172268值检查无错误。[validation-result.json](../A02/validation-result.json) 保存实际计数。

[byte-audit.txt](../A02/byte-audit.txt) SHA256 `c158ffbd024e0f62018fd122db19b9cc4882b12d14c16a59de089ecbe4ebf43c`，核对实际日志/manifest与历史byte清单，并绑定64源码、原前端锁和已验收JAR。JAR仍为 `d9c9956c389e79d1d52619c3e8310fb4b887d2009ad8f4c226d597cc030e3c71`；此为重新计算既有文件指纹，不是本次重新构建或业务验收。报告追加后以 `python -X utf8 verification/A02/run_verification.py --manifest-only` 更新实际字节清单，再执行同一字节审计确认输出一致。

## 明确未测边界与交接

A01 lifecycle 的 lease-full-download（16,777,220B）只有原体SHA、没有原Content-Type，不能从该事件核验媒体类型。本轮仅重算原体SHA，validation-result.mediaEvidenceUnavailable 明列该1项；不补造头、不重跑A01。动态媒体匹配由233条有头事件及限定媒体例覆盖，限定例是机器合同/匹配逻辑检查，不是新的TXT/Excel上传下载业务成功证明。

共享 PayloadDef 有意保留宽兼容类型，无法自动表达全部标准分支 required/范围、成对姿态、SPACE/GROUND特殊值、距离比较与其它跨字段规则；标准计算分支仍严格执行原源码，正文/description清楚说明。OAS3.0.3其余时间/目录/租约/TTL/取消时序限制仍如原文。echoReceiver **仅检查传输语法与原字节**，不证明新的业务HTTP成功，不证明过期动态引用可重用。

本轮未执行外部swagger-cli、代码生成客户端、Java重建、业务服务、浏览器、完整业务/数值/性能或数据库/MinIO/Nginx验证。旧外部格式证据只绑定旧schema；新版格式核验及原reviewer复审由根收口。没有未解决的本轮辅助检查失败，历史两次失败及最初红灯完整保留。

## 报告追加后的最终合同核验

`C:\Python314\python.exe -X utf8 verification/A02/validate_contract.py` 于 `2026-10-14T01:36:00.176201+00:00` 实际执行，exit 0；stdout [post-report-contract.txt](../A02/post-report-contract.txt)，SHA256 `a48512e3efca5aada237ee4d5f5dd875014dd035f25c7f319202dc90cc309c95`；stderr [post-report-contract-stderr.txt](../A02/post-report-contract-stderr.txt) 空，SHA256 `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`，完整记录 [post-report-contract-command.json](../A02/post-report-contract-command.json)。新增报告入口后287本地Markdown链接、185锚点及74 JSON围栏通过，其他合同统计保持上述口径。最终清单仅在报告稳定后按实际文件字节刷新，再由同一独立字节审计命令确认。


## 复审非阻断 P3 文案修正交接（2026-10-14）

完整报告见 [文案修正报告](../A02/文案修正报告.md)。本轮只修targetFilterBreakdown公开历史注释处理；原源码/源清单原文、原六命令与4455schema格式证明不变。生成/编译/contract/review-fixes/限定文案回归五命令及新byte-audit全exit0；没有重跑curl/业务/压测。当前新schemaSHA256 `6eeb15cf98a22e553221c1a6ad3b02e44838307e17038c229a2b5003d359c7c4`。当前validation-result最新来源为copy-edit新review-fixes内部实际核验，独立副本copy-edit-validation-result.json时间`2026-10-14T01:44:40.102433+00:00`，SHA256 `29aec43dc028cfe47e4e971364c98c9b12b17b783eaf49de8ee1256e1b8cd149`，不归于旧六命令。65件文案前原字节留存，58件上一轮保护文件未变。新精确命令/exit/stdout/stderr/SHA全在copy-edit-commands.json与copy-edit-byte-audit-command.json，并在文案修正报告逐项列出；旧缺Content-Type租约事件限制保持。根对新schema运行外部格式终核后，仅刷新manifest收口。
