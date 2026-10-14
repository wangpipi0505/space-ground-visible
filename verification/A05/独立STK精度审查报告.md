# A05 独立 STK 精度审查报告

审查结论：**规格通过；质量 Approved；Ready to merge: Yes（A05 实现与证据范围）**。未发现 Critical、Important 或 Minor 问题。原精确模拟请求、旧窗口诊断引用 CSV 和独立 STK Access 起止表缺失，属于已明确披露的证据边界；本结论不将这些未测项改为通过。

基准提交：`e38880d85883a7780dd01bc115d95cb3ac2aee20`。独立审查完成时间：2026-10-14 08:18 UTC。审查者只读产品、原目录、输入、旧报告和已有运行结果，只新增本报告；没有执行 Git/index/refs 写操作，没有重跑物理计算、Maven、JUnit、前端、性能或已有定向 suite。

## 1. 审查输入与范围

完整阅读以下三份输入，并分段阅读审查包中的 QA 源码、报告、请求、命令原流和已跟踪差异；大清单、原 CSV 和运行结果直接通过独立只读程序核对。

| 输入 | 身份/用途 |
| --- | --- |
| `docs/superpowers/plans/2026-10-14-stk-precision-supplement.md` | 需求、全局约束及 A05 交付范围；本次读取时仍待根代理最终勾选 |
| `verification/A05/实现与执行报告.md` | 实现说明、实际执行与缺失资产边界 |
| `verification/runtime/A05-review.md` | 240228 字节；SHA256 `5632766304a54aa5534c5d9c387af93c0caaeb9e66322e86daf7385b2d04fc8b`；完整变更审查包 |

审查覆盖 `run_stk_validation.py`、`StkStateProbe.java`、`test_stk_validation.py`、`delivery_check.py`、`generate_report.py`、失败解析器/探针快照、全部机器摘要、参考来源说明、三份原 CSV、隔离运行请求及全部命令证据。另读取当前真实 J2 状态实现、UTC 日历转换实现、旧技术说明第 7/9 章、旧 STK 窗口诊断说明及 `alg_gap_1120_fov130_j2rk4/README_STK_ACCESS_DIAGNOSTIC.md`，交叉核对公式、旧统计量与场景参数。

关键审查对象 SHA256：

| 对象 | SHA256 |
| --- | --- |
| `run_stk_validation.py` | `2b11d2cc7457bc79e9a1dfeed4e48b36b558bec73095cb6704f39913d2a8d687` |
| `StkStateProbe.java` | `c4ba9a8519efb4d2ac18ed28f0ef82f0f43eedcbc842690818428a89f471a172` |
| `delivery_check.py` | `9c540f01036178416718ae70aa6fee1a4adc6f2b240fff044109c5b6fde1babe` |
| `candidate-manifest.json` | `64def832077eb29bb2abe4919498d78a5e69ce36540bd68c7266da24d604ef3b` |
| `metrics-final.json` | `c33462f0b35fc7d05c1fc20959052cf73ace6927aa5af2f6fdda1b2b5f754f40` |
| `metrics-ecef.json` | `bc9ea89d11cec1d6786aa598fb2b8c7b1a8f942801f9e7c0885048b739ca5eb6` |
| `metrics-legacy-roi.json` | `59f71022981ed1e6678e8192fff2aaa1d3b33a357e2df707b2dddccd8cceb317` |
| `STK原始数据精度补充测试报告.md` | `8e2604ffaada9256bdb4008fe8c1a885a944c163629a870303fda1444d450f1e` |

## 2. 本次实际独立核验

在工程根目录实际运行 `C:/Python314/python.exe -B verification/A05/delivery_check.py`，进程退出 0。输出为 `PASS_READ_ONLY_DELIVERY`，候选 786 项、必要源码/输入 635 项、命令 42 份、原参考行数 `[7371,8641,8641]`；明确 `runtimeRequired=false`、`jarClassesRequired=false` 和 `NOT_TESTED_MISSING_STK_ACCESS`。终端对该次 JSON 中文显示存在编码替换，英文状态及计数可读；没有因此重编码或改写既有原流。

另用通过 PowerShell here-string 输入 `C:/Python314/python.exe -B -` 的独立标准库只读程序完成下列核验。这些程序直接读取 CSV/JSON 并独立实现解析和统计，没有调用被审统计器的 `compare`、`statistics` 或 `measure`，没有生成任何物理输出。

| 实际核验 | 结果 |
| --- | --- |
| `protected-before.json` 全部 3917 项重新计算 SHA | 全部相符，含原目录 697 项、原 STK、产品及旧证据 |
| 新副本全部 635 项源/输入重新计算 SHA | 全部相符 |
| 双 JAR、299 项新 classes/资源、两个探针 class | 与绑定 SHA 全部相符 |
| 场景配置和旧报告源 SHA；配置摘录行号/原文 | 全部相符 |
| 全部 42 份命令的 stdout/stderr 原字节 SHA | 全部相符；classpath 原版绑定原 JAR，当前绑定新 classes，后续依赖未混入另一版产品 |
| 2 个 runtime 输入、18 个 runtime 输出及 ECEF 补跑两份 CSV SHA | 全部相符 |
| 三份外部原 CSV 与仓库参考逐 SHA 对照 | 全部相同；完整行数 7371/8641/8641 |
| 独立检查完整 UTC 网格、有限状态、首末时域 | 目标间隔为 0.1 秒及唯一末端 0.09 秒，卫星为 10 秒；未缺行、重排或错位 |
| 独立重算 ECI/ECEF 两种重建、原/当前、两卫星参考全天与两个 ROI、两重建相互差异 | 21 组完整对照、896 个数值统计项，最大差值为 0；对应全部最差 UTC、计数和首末 UTC 相同 |
| 两种重建各自原/当前完整 CSV 原字节对照 | 两组均完全相同 |
| 四种窗口完整 JSON 逐层差异及全部弧段抽取 | 原 STK 两场景无差异；重建两场景各 2 处差异且均为准确的原/当前 `trajectory.csv` 来源路径；没有其他差异 |
| 候选路径解析后唯一性和可提交范围 | 786 个解析后路径全部唯一、位于工程内，无 JAR/class |
| 已存在的无 runtime 候选复制树 | runtime/JAR/classes 确实缺失，786 项文件 SHA 全部符合原候选清单 |
| 失败探针与修复探针文本差异 | 仅新增 `package com.tx.alg.api;` 及空行 |

独立统计程序成功结束于 `2026-10-14T08:15:46.818628+00:00`，最后输出：

```text
independent statistics 21 comparisons 896 numeric fields max delta 0 PASS
window stk-0 arcs 1 exact differences 0 only expected csvPath PASS
window stk-1120 arcs 4 exact differences 0 only expected csvPath PASS
window reconstructed-0 arcs 1 exact differences 2 only expected csvPath PASS
window reconstructed-1120 arcs 4 exact differences 2 only expected csvPath PASS
READ_ONLY_INDEPENDENT_REVIEW_PASS
```

路径/无 runtime 复制树及探针对照检查结束于 `2026-10-14T08:16:52.894684+00:00`，均成功。独立统计脚本首次读取 CSV 尾部空行时因自身未跳过空行发生 `IndexError`；修正审查脚本的空行处理后完成上述全量核验。这不是被审代码失败，也没有修改参考、实现或已有统计。

## 3. 规格与质量判断

1. **参考身份、列位和单位正确。** `run_stk_validation.py:87` 对重复表头按位置读取 Fixed/J2000 两组，km 和 km/s 明确乘 1000；拒绝列数、列名或单位失配。`compare`（第 141 行）要求全量 UTC 同序相等，不通过近邻匹配或截断隐藏错位。原 CSV SHA 与外部来源相符，独立网格检查支持完整时域声明。
2. **时间与速度定义有明确来源。** 旧技术说明第 7.9 节说明 STK Fixed 输出为惯性速度在地固轴的分量，未扣除 Ω×r；本次探针使用 `eciToStkFixedVelocity`，未将地固位置差分当成同一速度。报告第 80 行披露转换诊断及其边界。实际旧 UTC 日历实现起算于 2000-01-01 12:00，未替换为 Orekit TT。
3. **原版/当前是真实隔离调用。** Java 探针分别通过原私有 `propagateJ2Numerical` 状态和当前 `BallisticOrbitPropagation` 的 J2 `stateAt` 取得真实速度；当前产品源中 J2 分支直接返回 RK4 速度，与其他模型的差分速度分支区分清楚。每时刻对公开位置作同值断言。四模拟、两卫星状态、八窗口进程及两帧诊断均有具名成功命令和原流；新副本构建使用 Java 8，Maven 明确 `-DskipTests`，报告没有声称本轮 JUnit 已运行。
4. **统计公式与全量结果吻合。** 第 134 行统计采用分量有符号均值、RMSE、绝对值 nearest-rank P95 和最大值/最差 UTC；范数为三维欧氏距离。全部分量和范数的独立重算与机器证据一致。ECEF 重建位置 RMSE 0.543834207/0.734780423 m、速度 RMSE 0.001415040/0.001442027 m/s；与旧约 0.546/0.746/0.00142/0.00145 分列，未设为新的通过阈值。
5. **窗口没有漏段或宽化数值比较。** `window_arcs` 只遍历 `allResults`，避免将 `best` 重复最优结果再计一次。`physical_window_json`（第 318 行）仅去除 `csvPath` 来源字段；独立完整差异检查确认本次实际输入满足这一归一前提，且模拟 CSV 先有相同 SHA。四场景全部 1/4/1/4 弧段、起止、时长、点数和其余完整字段严格相等。0.5 秒采样和 double/毫秒打印限制未被包装成绝对精度 SLA。
6. **旧结论引用没有混用时域和参考。** 旧诊断 README 明确 base 08:24、offset 1120、130°全锥/65°半锥和 0.5 秒采样。HPOP +1120 ROI 的平均 361.986641 m、最大 501.399311 m 与旧约 362/501 条件相对应。nodrag 全天平均/RMSE/最大 996.640070/1154.135937/2590.957396 m 与旧全天报告相符，HPOP 全天单列；74 点 ROI 没有补造未采样末点。
7. **缺失资产和参数冲突如实保留。** 报告第 5 行及来源章节明确原精确请求、旧引用 CSV 未找到；两类原始精确端点重建均具名，未声称完全复现。旧文字近似落点约 0 米、CSV 实际约 1000 米、后改 `.mi` 日期时刻、8560 点 profile 和 738 点软件 CSV 的冲突均披露。文件名及当前 `UseDrag No` 快照未被用来倒推历史 HPOP 完整力模型。
8. **绝对窗口精度保持未测。** 报告第 126 行、全部窗口摘要及交付检查均为 `NOT_TESTED_MISSING_STK_ACCESS`。`.e`、软件诊断 CSV 和 Access 配置没有被当成独立 STK 起止真值，窗口一致性零差异没有被称为绝对窗口零误差。
9. **失败、修复与交付范围透明。** 已读真实重复表头 RED AssertionError、6 项 GREEN 原流，以及探针包可见性/缺省目标/缺省卫星失败记录；原失败现场保留。原/当前路径差异现场保留，修复仅涉及 QA 请求和统计。候选与默认入口明确区分摘要核验和物理重算；完整源码运行副本、JAR/classes、逐点生产型运行结果保留在 runtime，未进入候选。

## 4. 分级问题与交付边界

| 等级 | 本次发现 |
| --- | --- |
| Critical | 无 |
| Important | 无 |
| Minor | 无 |

**Ready to merge: Yes。** 此结论针对上列 SHA 绑定的 A05 实现、输入和证据，允许根代理继续最终收口。尚未完成或尚未纳入本独立审查的根操作包括：台账/README/计划最终更新、A04 当前版本元数据清单刷新、包含本报告及后续命令证据的 A05 最终清单、实际 Git blob 核对、中文提交、新 A05 标签与正常远端同步。不得引用本报告声称这些后续操作已经完成。原 task/A01—A04 标签及旧 870/1426/927 与核心数值报告仍须保持；D01—D06 继续暂缓。
