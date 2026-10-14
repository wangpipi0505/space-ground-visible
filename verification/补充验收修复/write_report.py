"""从已保存原始命令流生成完整交接；计数读取unittest输出，不手改通过结果。"""
import hashlib
import json
import re
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def record(path):
    return {'path': path.relative_to(ROOT).as_posix(), 'bytes': path.stat().st_size, 'sha256': sha(path)}


def main():
    commands = [json.loads(p.read_bytes()) | {'record': p.relative_to(ROOT).as_posix(), 'recordSha256': sha(p)}
                for p in sorted(OUT.glob('*-command.json'))]
    for command in commands:
        for stream in ['stdout', 'stderr']:
            path = ROOT / command[stream]
            assert sha(path) == command[stream + 'Sha256']
            assert path.stat().st_size == command[stream + 'Bytes']
        stderr = (ROOT / command['stderr']).read_bytes().decode('utf-8').replace('\r', '')
        count = re.search(r'Ran (\d+) tests in', stderr)
        outcome = re.search(r'^FAILED \(([^\n]+)\)$|^OK$', stderr, re.M)
        command['unittest'] = {'tests': int(count.group(1)), 'outcome': outcome.group(0)} if count and outcome else None
    protection = json.loads((OUT / 'protection-after.json').read_bytes())
    before = json.loads((OUT / 'protection-before.json').read_bytes())
    groups = Counter('/'.join(r['path'].split('/')[:2]) for r in before['protected'])
    current = [record(ROOT / relative) for relative in before['allowed']]
    for item in current:
        assert item in protection['current']
        data = (ROOT / item['path']).read_bytes()
        assert b'\r' not in data and not data.startswith(b'\xef\xbb\xbf')
        data.decode('utf-8')
    bindings = current + [record(OUT / 'test_targeted_contract.py'), record(OUT / 'evidence.py'),
                         record(OUT / 'history/test_targeted_contract.red.py.bin'),
                         record(ROOT / 'verification/A02/validate_contract.py'),
                         record(OUT / 'generated/examples.json'), record(OUT / 'generated/source-inventory.json')]
    external = json.loads((OUT / 'external-openapi-current-check.json').read_bytes())
    assert external['schemaSha256'] == sha(ROOT / 'docs/openapi/openapi.json')
    for stream in ['stdout', 'stderr']:
        assert sha(ROOT / external[stream + 'File']) == external[stream + 'Sha256']
    bindings += [record(OUT / 'external-openapi-current-check.json'),
                 record(ROOT / external['stdoutFile']), record(ROOT / external['stderrFile'])]
    result = {'time': datetime.now(timezone.utc).isoformat(), 'commands': commands, 'bindings': bindings,
              'protectionPassed': protection['passed'], 'utf8LfCurrentFiles': len(current)}
    (OUT / 'handoff-bindings.json').write_bytes((json.dumps(result, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))
    parts = ['# 整体审查完整修复波交接\n\n',
             '本轮状态 **DONE_WITH_CONCERNS**：I01/I02/M01/M03/M04 文档修复已通过限定检查，M02 的已披露运行边界原样保留。当前四个允许文件已稳定；根已保存当前SHA的固定Swagger CLI4.0.4格式检查exit0；原审查者定向复审及根台账/提交/标签/同步尚待根执行，未宣称这些步骤完成。\n\n',
             '## 修改与源码依据\n\n',
             '- I01：新增 owner 优先说明覆盖 SimulationRequest 的 taskStartTime/taskEndTime/durationSec 与 PhaseEvent 的 startTime/endTime。模拟任务时间可空/空白，非空仍走原 parseTimeCode、告警与命名；CSV 采样仍由 plan.timeOfFlightSec 决定，不裁剪或扩展。durationSec 默认600、类型double及旧兼容性质保留；事件只读取数量、文本不解析也不做起止顺序校验。FitRequest 的首末观测覆盖、成对顺序和原解析规则、空间 durationSec 未变。实际字段没有通用时间 pattern/format，本轮未新增或放宽拟合时间模式。\n',
             '- I02：模拟两单位缺失/null/空串/纯空白均回退 m，两框架同样回退 J2000；源码 simulationEndpointEci 与 simulationEndpointRangeReference 均调用 blankToDefault。102 boundaryField 无有效单位返回 null，positionUnitScale 才回退0.1m；地理分支提前返回，经纬度度/高度米且忽略单位。公共默认句与四字段说明同步；default/nullable/type/算法不变。\n',
             '- M01：窗口404恢复行明确重新登记按钮依赖该生成页自己的 previewError/trajectoryError/downloadError；无按钮先使用该结果 HTTP 下载原CSV，真实成功按成功处理，实际辅助失败才在原CSV仍存在或已恢复条件下登记，也可直接重算。不改 Vue 控件，不人为制造错误。\n',
             '- M03：只将文件 preview 的 page/limit 查询参数声明为 int32，保持1/50默认、minimum1、配置上限与现行错误；Controller 实际是 int，空间 Long 分页保持 int64。\n',
             '- M04：StationEntity.id 独立说明原字符串站点主键；公共约定限定卫星/目标目录ID与下传satelliteId为数字、stationId为字符串，未全局改Long。\n',
             '- M02：只读核对 A01 原报告及真实 R2 日志，MVC SimpleAsyncTaskExecutor 与关闭线程 WARN、最高并发6、N10/N12、v4五行进程/两行jstat、无SLA、外部BLOCKED、符号链接SKIP及长期/热卸载未测仍披露；本轮不扩大产品改动，也不声称无WARN或已证明长期泄漏/无泄漏。\n\n',
             '具名源码为 TargetTrajectoryFitter.java 的 simulateToCsv/simulate/buildSimulationEndpointPlan、simulationEndpointEci/RangeReference、boundaryInfo/boundaryField/parseBoundaryPositionEci/positionUnitScale/blankToDefault/parseTimeCode；BallisticFileController.java 的 preview int 参数；StationEntity.java 的 String id/getId；BallisticFitFileResult.vue 的按钮 v-if 与 download 捕获/成功保存。它们全部在本轮保护快照中。\n\n',
             '## 限定 RED/GREEN 与核验含义\n\n',
             '完整测试文件：`verification/补充验收修复/test_targeted_contract.py`。修复前最终可复现版本另保存 `history/test_targeted_contract.red.py.bin`。11 个 unittest 方法包含字段 subTest；不能把 subTest failures 当作独立业务场景总数。最终 RED 实际运行11方法，12个断言失败、0 errors（对应7个失败方法）；最终 GREEN 为11/11、0 failures/0 errors、exit0。\n\n',
             '初轮 red 还包含一个测试脚本 owner 定位错误（站星包装没有durationSec）以及命名调用匹配错误；red-corrected/red-final 继续保留命名定位错误，后按真实 firstNonBlank 调用纠正，red-source-corrected 成为无工具错误的最终 RED。green-attempt1 有两处过于字面化的检查：否定句“或共同观测窗口”被误认为旧交集语义、“也可直接重算”被要求精确匹配“或重算”。将断言收窄至旧错误短句和真实重算说明之后 GREEN；交付文件在 generate 后未再次修改。所有初始输出及退出码都保留，不删除失败或改写为通过。\n\n',
             '检查直接读取当前交付 OAS、Markdown、手册及真实 Java/Vue 源码；合成反例覆盖可缺失/可空/空串/空白/兼容标签、字符串与数字错型、未读durationSec数值、事件逆序兼容文本、preview正边界与int32溢出。OAS不能完整表达原时间parse与跨字段业务规则，合成schema接收检查不等于实际运行 Java；非空模拟时间仍需原parser，Fit真实规则由来源断言保留。单位/地理实际分支由源码绑定核对，不在Python中伪造动力学或声称重跑数值。\n\n',
             '限定结构结果为35/35活动Controller路径、184引用、59份选定真实响应结构、15份选定真实请求结构。生成器保留68组历史选例，其中二进制/空响应不作为JSON验证；没有重跑247 HTTP。旧核验器只从AST加载七个纯函数，未执行顶层完整247来源套件及旧结果写入。机器结构差异检查确认当前OAS除10个字段description、preview两format外与修复前完全相同，拟合和空间字段未受波及。\n\n',
             '## 历史保护和新轮入口\n\n',
             '先用 write_bytes 原样保存将改的四个文件，SHA绑定 `history/preserved-manifest.json`；旧OpenAPI df3bbd2ad3d1b7561ae0f7f56adce0c38db2e18f282f34494ee0f07eff69ebb6、旧手册a9b79890b259c70e72ee42cd21bcd5108dd2815cb6e47b55e1aa3eda2e69c96d 均在history保留。A02的714037e验收标签及271条artifact-manifest、A03原58条清单是旧核验时点；未向它们倒填当前SHA，未将新契约冒充旧格式已验证。\n\n',
             f'当前轮保护结果：{protection["protected"]}项当前保护文件及两个原目录的{protection["originals"]}项具名文件，差异{len(protection["differences"])}、保护范围新增路径{len(protection["newProtectedPaths"])}，实际exit0。保护包括A01 {groups["verification/A01"]}件、A02 {groups["verification/A02"]}件（排除唯一允许当前生成源）、A03 {groups["verification/A03"]}件，产品/依赖/锁/参考数据、实际读取的HTTP原字节与JAR、HEAD/index/packed-refs/分支及标签引用。包含归档副本的当前文件数量不是原清单声明数量。\n\n',
             '按根明确范围只绑定具名runtime/HTTP原字节及JAR，不扫描runtime冻结源码副本、缓存和大运行数据；根的当前执行恢复.md、台账/计划合法修改不纳入本波禁止变化。首次全runtime保护进程过宽，读取阶段由精确PID38444终止，未得到保护结果；随后收敛到上述明确范围重建，初始四文件仍未修改。本轮没有操作Git/index/提交/tag/台账/计划/attrs。\n\n',
             '生成器增加 `--evidence-dir` 单一参数，实际命令指定新目录generated；examples/source-inventory/请求/curl都仅写新目录，当前两文档使用UTF8/LF原字节写入。历史默认入口仍服务原流程，本波不使用它；仓库README保留A03使用说明入口，本轮新证据由正式修复报告和根最终交付记录引用，README不再改。旧验证入口不运行，新结果全部留本目录。\n\n',
             '## 当前稳定字节\n\n',
             '| 文件 | 字节数 | SHA256 |\n| --- | --- | --- |\n']
    for item in bindings:
        parts.append(f'| `{item["path"]}` | {item["bytes"]} | `{item["sha256"]}` |\n')
    parts += ['\n当前四文件均检查UTF8无BOM、无CR、以LF保存；stdout/stderr保留实际原始字节和原行尾，未重编码“美化”。新生成source-inventory对原JAR只计算指纹，没有新构建或运行服务。完整机器绑定见 `handoff-bindings.json`。\n\n',
              '## 精确命令和原流\n\n',
              '以下为 evidence.py 实际子进程argv，cwd统一仓库根；命令记录本身及原流均绑定保存字节SHA，UTC起止与真实exit如下。unittest常规进度写stderr，不将非空stderr等同失败。\n\n']
    for command in commands:
        parts += [f'### {Path(command["record"]).stem}\n\n',
                  '```json\n' + json.dumps(command['argv'], ensure_ascii=False) + '\n```\n\n',
                  f'- UTC：`{command["started"]}` → `{command["ended"]}`；exit `{command["exit"]}`。\n',
                  f'- 记录：`{command["record"]}`；SHA `{command["recordSha256"]}`。\n',
                  f'- stdout：`{command["stdout"]}`，{command["stdoutBytes"]}B；SHA `{command["stdoutSha256"]}`。\n',
                  f'- stderr：`{command["stderr"]}`，{command["stderrBytes"]}B；SHA `{command["stderrSha256"]}`。\n']
        if command['unittest']:
            parts += [f'- 原输出统计：{command["unittest"]["tests"]}方法，`{command["unittest"]["outcome"]}`。\n']
        parts += ['\n']
    parts += ['## 根保存的当前外部格式检查\n\n',
              '```json\n' + json.dumps(external['command'], ensure_ascii=False) + '\n```\n\n',
              f'根记录时间 `{external["time"]}`，耗时 `{external["durationSeconds"]}` 秒，toolVersion `{external["toolVersion"]}`，实际exitCode `{external["exitCode"]}`，绑定当前OAS `{external["schemaSha256"]}`；sourceUnchanged `{external["sourceUnchanged"]}`。本实现者只复核该新记录和stdout/stderr保存字节SHA，没有重复执行该命令。该工具记录仅有时间与持续时长，未推算伪造结束时间。\n\n',
              '## 未执行与交接\n\n',
              '本轮未重跑351/112前后端、15/9/21数值、247 HTTP、12 Chrome、负载或外部部署，本实现者未新装依赖、启动服务；这些仍是原阶段证据。根新4.0.4结果只证明当前OAS格式规范，不把旧df3b结果或新格式检查写成新业务/精度通过。A01已有WARN/有限样本/无SLA/外部BLOCKED等限制保持。\n\n',
              '根下一步：让原最强reviewer针对六项与保护/新证据复审；随后按既有授权更新台账/计划、中文提交、新A03验收标签及指定远端正常同步。任何合法提交后的Git指纹变化须按新时点解释。当前修复文件稳定，不再由本实现者编辑。\n']
    parts += ['\n正式可提交报告为 `verification/补充验收修复/正式修复报告.md`；runtime同字节副本仅用于任务交接，属于忽略的运行记录，不直接纳入最终artifact-manifest。最终清单及字节审计见本目录artifact-manifest.json/artifact-audit.json；审计自身输出按明示例外保存，精确命令、UTC起止与原流见final-audit-public-command.json及对应stdout/stderr，不造成报告/清单循环SHA。\n']
    report = ROOT / 'verification/runtime/acceptance-final-fix-report.md'
    report.write_bytes(''.join(parts).encode('utf-8'))
    (OUT / '正式修复报告.md').write_bytes(report.read_bytes())
    print(json.dumps({'report': record(report), 'commandCount': len(commands), 'protectionPassed': protection['passed'],
                      'currentStable': current}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    __import__('sys').stdout.reconfigure(encoding='utf-8')
    main()
