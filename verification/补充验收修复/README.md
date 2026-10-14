# 整体审查限定修复证据

本目录是 I01/I02/M01/M03/M04 的新轮证据，M02 保留既有运行边界。只改当前接口文档、OpenAPI、软件说明和 `verification/A02/build_contract.py`；不启动 Java、前端服务、HTTP、Chrome 或负载测试。

从仓库根执行下列入口。生成器为新轮增加了一个输出目录参数；不使用其历史默认目录，也不运行旧 A02/A03 顶层验证入口，因为它们会写旧结果。

```powershell
python -B verification/补充验收修复/evidence.py run generate C:/Python314/python.exe -X utf8 -B verification/A02/build_contract.py --evidence-dir verification/补充验收修复/generated
python -B verification/补充验收修复/evidence.py run green C:/Python314/python.exe -B verification/补充验收修复/test_targeted_contract.py
python -B verification/补充验收修复/evidence.py run protection C:/Python314/python.exe -B verification/补充验收修复/evidence.py protect
```

上述命令用于理解已保存证据；后续重验应更换 `run` 的标签以及生成输出目录，保留本轮命令和流。不要再次运行 `preserve`，本轮初始指纹已经固定。根最终提交之后 Git 保护指纹会合法变化，不再把该时点变化当成修复波污染。

`test_targeted_contract.py` 从旧核验器 AST 只加载七个纯函数，独立核对实际 Controller、公开字段、引用和选定真实样例；不会执行旧脚本的顶层扫描和 `validation-result.json` 写入。`history` 保存四个允许文件修复前原字节及实际 SHA，包括旧 df3b OpenAPI、a9b7 手册。历史 A02 271 条清单和 A03 58 条清单仍属于其原核验时点，本目录没有倒填它们。

完整修复、真实 RED/GREEN、精确 argv/UTC 起止/exit/stdout/stderr 字节 SHA 和保护范围见 [正式修复报告](正式修复报告.md)；runtime同字节副本仅供交接，不进入可提交artifact-manifest。机器命令记录为本目录 `*-command.json`，输出为 `*-stdout.bin` 和 `*-stderr.bin`。最后保护结果见 [protection-after.json](protection-after.json)。新生成源清单/68 组选例/请求/curl 数据位于 `generated`；正文继续链接原 A02 请求示例，旧请求未重写。最终新轮清单/字节审计见 [artifact-manifest.json](artifact-manifest.json) 和 [artifact-audit.json](artifact-audit.json)，包含根新4.0.4格式结果；审计自身五个输出采用明确自引用例外，精确命令见 [final-audit-public-command.json](final-audit-public-command.json)。

根已在本目录保存绑定当前Schema SHA的固定Swagger CLI 4.0.4新格式结果，exit0；该格式结果不证明业务运行。原整体审查者定向复审、台账/提交/新标签与正常远端同步由根接手。本轮没有把旧格式结果或旧全量测试宣称为当前新执行。
