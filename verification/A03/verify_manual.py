"""A03 只读来源核验：当前控件/参数/合同、完整示例、既有真实截图及链接；不发业务请求。

日志由外层子进程驱动直接保存字节。本核验不生成截图、不重建 JAR，也不改 A01/A02。
"""
import csv
import hashlib
import io
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'verification/A03'
errors, checks, links = [], [], []


def fingerprint(path):
    data = path.read_bytes()
    return {'path': path.relative_to(ROOT).as_posix(), 'bytes': len(data),
            'sha256': hashlib.sha256(data).hexdigest()}


def read(path):
    return (ROOT / path).read_text(encoding='utf-8-sig')


def check(name, condition, detail=None):
    checks.append({'name': name, 'passed': bool(condition), 'detail': detail})
    if not condition:
        errors.append(name)


def slug(text):
    return re.sub(r'[^\w\-\s]', '', text.lower()).replace(' ', '-')


manual_path = ROOT / 'docs/软件功能使用说明.md'
manual = manual_path.read_text(encoding='utf-8')
schema_path = ROOT / 'docs/openapi/openapi.json'
api = json.loads(schema_path.read_text(encoding='utf-8'))
schemas = api['components']['schemas']
browser = json.loads(read('verification/A01/final/browser-summary.json'))
source_inventory = json.loads(read('verification/A02/source-inventory.json'))

# 不执行 A02 核验器，避免它重写 A02 结果；仅读取其已保存指纹并独立重新计算。
for source in source_inventory['sources']:
    check('A02产品来源未变:' + source['path'],
          fingerprint(ROOT / source['path'])['sha256'] == source['sha256'])
jar = fingerprint(ROOT / source_inventory['jar']['path'])
check('A01既有JAR原字节', jar['sha256'] == source_inventory['jar']['sha256'])
check('绑定当前OpenAPI合同', fingerprint(schema_path)['sha256'] ==
      'df3bbd2ad3d1b7561ae0f7f56adce0c38db2e18f282f34494ee0f07eff69ebb6')
check('前端冻结锁原字节', fingerprint(ROOT / 'frontend/pnpm-lock.yaml')['sha256'] ==
      'bf63ad64c42dd1ce18737b0b0c917e4fdd8cde2da005a9a55bae23a174bd7fc8')

# 只检查本项新报告/说明和 README 的相对链接，旧历史报告的绝对路径不改写。
for doc in [manual_path, ROOT / 'README.md', OUT / '验证报告.md']:
    body = doc.read_text(encoding='utf-8')
    for target in re.findall(r'!?\[[^\]]*\]\(([^)]+)\)', body):
        target = unquote(target.strip('<>'))
        if re.match(r'^[a-z]+://', target):
            continue
        path_text, _, anchor = target.partition('#')
        path = (doc.parent / path_text).resolve() if path_text else doc
        ok = path.is_file()
        if ok and anchor:
            headings = re.findall(r'^#{1,6}\s+(.+)$', path.read_text(encoding='utf-8'), re.M)
            ok = anchor in [slug(h) for h in headings]
        links.append({'document': doc.relative_to(ROOT).as_posix(), 'target': target, 'exists': ok})
        check('相对链接/锚点:' + target, ok)

app = read('frontend/src/App.vue')
tabs = re.findall(r'<el-tab-pane label="([^"]+)" name="([^"]+)"', app)
check('六个真实页签', len(tabs) == 6 and all(label in manual for label, _ in tabs), tabs)
check('无虚构VueRouter子路由', 'vue-router' not in read('frontend/package.json') and '没有 Vue Router 子路由' in manual)
ui_sources = [p for p in (ROOT / 'frontend/src').rglob('*.vue') if '.spec.' not in p.name]
ui_text = '\n'.join(p.read_text(encoding='utf-8') for p in ui_sources)
controls = ['筛选卫星', '保存并启用', '计算下传窗口', '校验自选', '按当前条件加载目标',
            '添加组合约束', '开始规划', '取消任务', '确认选择', '明细', '备注',
            '新增弹道目标', '新增手工卫星', '采用非生产原基准根数', '校验并预览',
            '执行弹道窗口计算', '查看全部候选偏移', '查看轨迹', '执行原102包拟合',
            '执行原模拟弹道', '执行原弹道重定时', 'HTTP 下载原CSV', '下载实际提交 JSON',
            '下载原始响应 JSON', '重试结果文件登记', '重新登记结果文件', '重试文件预览',
            '重试完整CSV轨迹', '预览上一页', '预览下一页', '导入网络地址']
for control in controls:
    check('真实控件与手册:' + control, control in ui_text and control in manual)
planning_ui = read('frontend/src/components/VisibilityPlanningStep.vue')
check('页面仅后台任务且手册说明同步接口边界', '后台任务（显示进度）' in planning_ui and
      '当前页面没有同步/异步切换按钮' in manual and ':value="sync"' not in planning_ui)
check('页面轮询真实2000ms', 'VISIBILITY_TASK_POLL_INTERVAL_MS = 2000' in
      read('frontend/src/composables/useVisibilityWorkbench.ts') and '每2000ms轮询' in manual)
for script, params in [('build.ps1', ['Component', 'JavaHome']),
                       ('package.ps1', ['Destination']),
                       ('start-backend.ps1', ['JavaHome', 'RuntimeRoot', 'Port', 'Profile', 'AllowedOrigins'])]:
    text = read('deploy/' + script)
    for param in params:
        check('当前脚本参数:' + script + '/' + param,
              re.search(r'\$' + param + r'\b', text) is not None and '-' + param in manual)
check('打包拒绝已存在目录', 'Test-Path -LiteralPath $packageRoot' in read('deploy/package.ps1') and '必须尚不存在' in manual)

# 以交付 OpenAPI 的实际字段/required/type/enum/range 递归检查示例，不借用过期源码注释。
value_checks = 0


def validate(value, schema, where):
    global value_checks
    value_checks += 1
    if '$ref' in schema:
        schema = schemas[schema['$ref'].split('/')[-1]]
    if 'anyOf' in schema:
        for part in schema['anyOf']:
            old_errors, old_checks = len(errors), len(checks)
            validate(value, part, where)
            if len(errors) == old_errors:
                return
            del errors[old_errors:]
            del checks[old_checks:]
        check('输入anyOf:' + where, False)
        return
    if 'allOf' in schema:
        for part in schema['allOf']:
            validate(value, part, where)
        return
    if value is None:
        check('允许null:' + where, schema.get('nullable', False) and 'type' in schema and
              ('enum' not in schema or None in schema['enum']))
        return
    kind = schema.get('type')
    types = {'object': dict, 'array': list, 'string': str, 'integer': int,
             'number': (int, float), 'boolean': bool}
    check('输入类型:' + where, kind in types and isinstance(value, types[kind]) and
          (kind not in ['integer', 'number'] or not isinstance(value, bool)))
    if kind not in types or not isinstance(value, types[kind]):
        return
    if 'enum' in schema:
        check('输入枚举:' + where, value in schema['enum'])
    if kind == 'object':
        for required in schema.get('required', []):
            check('输入必填:' + where + '/' + required, required in value)
        for key, item in value.items():
            if key in schema.get('properties', {}):
                validate(item, schema['properties'][key], where + '/' + key)
    if kind == 'array':
        check('输入数组长度:' + where, len(value) >= schema.get('minItems', 0))
        for index, item in enumerate(value):
            validate(item, schema['items'], where + '/' + str(index))
    if kind in ['number', 'integer']:
        check('输入数值范围:' + where, schema.get('minimum', float('-inf')) <= value <=
              schema.get('maximum', float('inf')))
    if kind == 'string' and schema.get('pattern'):
        check('输入模式:' + where, re.search(schema['pattern'], value) is not None)


def validate_request(request, path, where):
    operation = api['paths'][path]['post']
    validate(request, operation['requestBody']['content']['application/json']['schema'], where)


fit_path = 'test-data/reference/ballistic/target_trajectory_fit_package102_default.json'
sim_path = 'test-data/reference/ballistic/target_trajectory_simulate_default.json'
fit, simulation = json.loads(read(fit_path)), json.loads(read(sim_path))
for request, path, label in [(fit, '/api/trajectory/fit/package102', '拟合完整原输入'),
                             (simulation, '/api/trajectory/simulate', '模拟完整原输入')]:
    submitted = {k: v for k, v in request.items() if k != 'outputDir'}
    validate_request(submitted, path, label)
    actual = browser['fit' if label.startswith('拟合') else 'simulation']['result']['request']
    check(label + ':逐值等于A01实际提交（仅省略outputDir）', submitted == actual)
check('拟合原包8条两目标', len(fit['package102']) == 8 and
      {item['track_tgt_id'] for item in fit['package102']} == {'TGT-001', 'TGT-002'})
check('拟合黄金完整点1633与31', [item['pointCount'] for item in browser['fit']['result']['targetEvidence']] == [1633, 31])
simulation_baseline = json.loads(read('test-data/baseline/ballistic/simulate/result.json'))
check('模拟完整点2576', browser['simulation']['result']['pathCounts'] == [2576] * 4 and
      simulation_baseline['files'][0]['pointCount'] == 2576)
retime = browser['retime']['result']['computations'][0]
validate_request(retime['request'], '/api/trajectory/retime', 'A01重定时完整实际请求')
check('重定时示例新UTC一致', retime['request']['launchTimeUtc'] == '2026-07-02 07:57:00.000')
window = browser['window']['result']['request']
validate_request(window, '/api/orbit/visibility/search', 'A01多目标多星四载荷实际请求')
check('窗口两目标两星每星两载荷', len(window['ballistics']) == len(window['satellites']) == 2 and
      all(len(s['payloads']) == 2 for s in window['satellites']))
check('窗口扫描示例1200/10/3/1/1', [window[k] for k in ['startOffsetSec', 'stepSizeSec',
      'numCalculations', 'targetSampleStepSec', 'trajectoryPointOutput']] == [1200, 10, 3, 1, 1])
window_raw_path = ROOT / 'verification/runtime/A01/final-candidate/window/download-response.json'
check('A01既有窗口响应原字节摘要', fingerprint(window_raw_path)['sha256'] ==
      browser['window']['result']['rawText']['sha256'])
window_response = json.loads(window_raw_path.read_bytes())
check('A01既有窗口best1220八弧及全部三个偏移', window_response['best']['offsetSecond'] == 1220 and
      window_response['best']['visibleArcCount'] == 8 and len(window_response['allResults']) == 3)
sync = json.loads(read('verification/A02/requests/operation15.json'))
validate_request(sync, '/api/v1/space/visibility/plan', 'A02同步接口集成输入')
check('空间A01配置/下传/选窗/目录/单星/共同/选择/备注清空/取消全链来源',
      {'save-space', 'save-ground', 'downlink', 'validate-window', 'target-query',
       'single-satellite-results', 'two-satellite-common', 'selection', 'browser-clear-remark',
       'browser-cancel', 'browser-cancel-history'}.issubset(
          {e['name'] for e in browser['space']['result']['evidence']}))

csv_inputs = []
for path, columns, count in [('test-data/reference/ballistic/trajectory-0.1s-standard.csv', 20, None),
                              ('test-data/baseline/ballistic/simulate/trajectory-0.csv', 21, 2576)]:
    rows = list(csv.reader(io.StringIO(read(path))))
    csv_inputs.append({**fingerprint(ROOT / path), 'columns': len(rows[0]), 'dataRows': len(rows) - 1,
                       'header': rows[0]})
    check('完整CSV结构:' + path, len(rows[0]) == columns and all(len(r) == columns for r in rows[1:]) and
          (count is None or len(rows) - 1 == count))
required_retime = {'YYYY', 'MM', 'dd', 'HH', 'mm', 'ss.SSS', 'ECEF_X', 'ECEF_Y', 'ECEF_Z',
                  'ECI_X', 'ECI_Y', 'ECI_Z', 'ECI_VX', 'ECI_VY', 'ECI_VZ', 'ECEF_Vx', 'ECEF_Vy', 'ECEF_Vz'}
check('重定时输入必要命名列', required_retime.issubset(csv_inputs[1]['header']))

screenshots = []
by_path = {s['path']: s for s in browser['screenshots']}
for link in links:
    if link['document'] != 'docs/软件功能使用说明.md' or not link['target'].endswith('.png'):
        continue
    path = (manual_path.parent / link['target']).resolve()
    item = fingerprint(path)
    expected = by_path.get(item['path'])
    check('A01已提交真实截图原字节:' + item['path'], expected and
          item['sha256'] == expected['sha256'] and item['bytes'] == expected['bytes'])
    screenshots.append(item)
check('既有12浏览器流程27图11下载均PASS（未重新执行）',
      len([v for v in browser.values() if isinstance(v, dict) and v.get('status') == 'PASS']) == 12 and
      len(browser['screenshots']) == 27 and len(browser['downloads']) == 11)
for download in browser['downloads']:
    if 'source' in download:
        path = Path(download['source'])
        check('既有下载源原字节:' + path.name, fingerprint(path)['sha256'] == download['sha256'])

# 这些语义必须同时来自当前A02，而非过期源注释；只核验来源匹配，不声称重新测试业务。
contract = read('docs/接口文档.md')
for phrase in ['目录查询业务不读取它', '五个候选计数均为selectedTargetCount',
               '超出只产生风险提示，不硬排除', '按最后访问', 'DELETE 200可能仍RUNNING']:
    check('当前合同关键边界:' + phrase, phrase in contract)
for phrase in ['候选统计', '成功传播数量', 'planningStartTime/planningEndTime/maxApproximationDays',
               '仍尝试传播', '受理200仍可能RUNNING', '当前页面没有同步/异步切换按钮',
               '24h', '413', '不宣称重新走过浏览器']:
    check('手册关键边界:' + phrase, phrase in manual)

protected = json.loads((OUT / 'protected-before.json').read_text(encoding='utf-8'))
for item in protected:
    current = fingerprint(ROOT / item['path'])
    check('保护文件核验:' + item['path'], current == item)

source_files = sorted(set([ROOT / 'docs/接口文档.md', schema_path, ROOT / 'deploy/部署说明.md',
                          ROOT / fit_path, ROOT / sim_path, ROOT / 'verification/A01/final/browser-summary.json',
                          ROOT / 'verification/A01/验收用例矩阵.json', ROOT / 'frontend/vite.config.ts'] +
                         ui_sources + list((ROOT / 'deploy').glob('*.ps1')) +
                         list((ROOT / 'frontend/src/composables').glob('*.ts'))))
result = {'timeUtc': datetime.now(timezone.utc).isoformat(), 'passed': not errors,
          'scope': '静态来源/完整输入/链接/原字节核验；没有业务请求、浏览器重跑、构建或压测',
          'checks': len(checks), 'valueChecks': value_checks, 'links': links, 'screenshots': screenshots,
          'csvInputs': csv_inputs, 'sources': [fingerprint(p) for p in source_files], 'jar': jar,
          'protectedFiles': len(protected), 'errors': errors, 'details': checks}
(OUT / 'validation-result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n',
                                            encoding='utf-8', newline='\n')
print(json.dumps({k: result[k] for k in ['passed', 'checks', 'valueChecks', 'protectedFiles', 'errors']}, ensure_ascii=False))
print('links=' + str(len(links)) + '; referencedScreenshots=' + str(len(screenshots)) + '; csvInputs=' + str(len(csv_inputs)))
sys.exit(0 if result['passed'] else 1)
