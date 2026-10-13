"""汇总本项真实Chrome控件运行证据；CSV逐字节、下载JSON与真实HTTP正文逐字节比较。"""
from pathlib import Path
import hashlib
import json
import shutil
import xml.etree.ElementTree as ET
from decimal import Decimal

ROOT = Path(__file__).resolve().parents[2]
RUNTIME = ROOT / 'verification/runtime/T27'
EVIDENCE = ROOT / 'verification/T27'


def fingerprint(path):
    data = path.read_bytes()
    return {'path': path.relative_to(ROOT).as_posix(), 'bytes': len(data),
            'sha256': hashlib.sha256(data).hexdigest()}


def read_result(name):
    """CLI原生chooser会暂停run-code；upload继续后从同page读取已经完成的真实结果。"""
    text = (RUNTIME / (name + '-result-cli.txt')).read_text(encoding='utf-8-sig')
    assert text.startswith('### Result\n'), name
    value = json.loads(text.splitlines()[1])
    (RUNTIME / (name + '-full-evidence.json')).write_text(json.dumps(value, ensure_ascii=False), encoding='utf-8')
    return value


def compact(value, name):
    """运行正文放ignored runtime，提交实际正文SHA、字节与请求/状态；不提交生成轨迹。"""
    if isinstance(value, dict):
        result = {}
        for key, item in value.items():
            if key in ('raw', 'rawText', 'winRaw') and isinstance(item, str):
                path = RUNTIME / (name + '-' + key + '.json')
                path.write_bytes(item.encode('utf-8'))
                result[key + 'Evidence'] = fingerprint(path)
            elif key == 'rows':
                result['actualRowCount'] = len(item)
            else:
                result[key] = compact(item, name + '-' + key)
        return result
    if isinstance(value, list):
        return [compact(item, name + '-' + str(index)) for index, item in enumerate(value)]
    return value


flows = {name: read_result(name) for name in ('window', 'fit', 'simulation', 'retime',
    'window-edge', 'fit-edge', 'retime-edge', 'fit-aux-failure', 'fit-aux-recovery')}
# 为404恢复而独立重新计算的fit仍使用原完整输入；下载正文绑定最后实际生成的响应路径。
flows['fit'] = read_result('fit-aux-prepare')
downloads = []
for actual, expected in [
    ('window/download-original.csv', 'test-data/reference/ballistic/trajectory-0.1s-standard.csv'),
    ('fit/download-trajectory-0.csv', 'test-data/baseline/ballistic/fit/trajectory-0.csv'),
    ('fit/download-trajectory-1.csv', 'test-data/baseline/ballistic/fit/trajectory-1.csv'),
    ('fit/download-recovered.csv', 'test-data/baseline/ballistic/fit/trajectory-0.csv'),
    ('simulation/download-default.csv', 'test-data/baseline/ballistic/simulate/trajectory-0.csv'),
    ('retime/download-default.csv', 'test-data/baseline/ballistic/retime/trajectory-0.csv')]:
    actual_path, expected_path = RUNTIME / actual, ROOT / expected
    assert actual_path.read_bytes() == expected_path.read_bytes(), actual
    downloads.append({'actual': fingerprint(actual_path), 'expected': fingerprint(expected_path), 'exactBytesEqual': True})
for kind, file, raw in [
    ('window', 'download-response.json', flows['window']['rawText']),
    ('fit', 'download-response.json', flows['fit']['rawText']),
    ('simulation', 'download-response.json', flows['simulation']['raw']),
    ('simulation', 'download-window-response.json', flows['simulation']['winRaw']),
    ('retime', 'download-response.json', flows['retime']['computations'][0]['raw'])]:
    path = RUNTIME / kind / file
    assert path.read_bytes() == raw.encode('utf-8'), (kind, file)
    downloads.append({'actual': fingerprint(path), 'actualHttpBodyExactBytesEqual': True})
assert flows['fit-aux-failure']['browserDownloads'] == 0
assert flows['fit-aux-recovery']['computeCalls'] == 0
assert all(not flows[name].get('pageErrors') for name in flows)
for name in ('window', 'fit', 'simulation', 'retime'):
    assert not flows[name].get('consoleErrors', flows[name].get('errors')), name
original_responses = []
for kind, baseline, field, index in [('fit', 'fit', 'rawText', None),
        ('simulation', 'simulate', 'raw', None), ('retime', 'retime', 'raw', 0)]:
    entry = flows[kind]['computations'][index] if index is not None else flows[kind]
    actual = json.loads(entry[field], parse_float=Decimal, parse_int=Decimal)
    expected = json.loads((ROOT / 'test-data/baseline/ballistic' / baseline / 'result.json').read_text(encoding='utf-8'),
                          parse_float=Decimal, parse_int=Decimal)
    for number, file in enumerate(actual['files']):
        file['csvPath'] = expected['files'][number]['csvPath']
    assert actual == expected, kind
    original_responses.append({'operation': kind, 'allJsonFieldsExactEqual': True,
                               'normalization': '仅csvPath映射本次管理文件路径至原黄金路径；完整Decimal与数组顺序严格相同'})
(EVIDENCE / 'browser-original-response-compare.json').write_text(
    json.dumps(original_responses, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
screenshots = EVIDENCE / 'screenshots'
screenshots.mkdir(exist_ok=True)
for source in sorted((ROOT / 'output/playwright/T27').rglob('*.png')):
    shutil.copy2(source, screenshots / (source.parent.name + '-' + source.name))
result = {'passed': True, 'browser': 'Chrome headless', 'locale': 'zh-CN', 'timezone': 'Asia/Shanghai',
          'viewport': {'width': 1920, 'height': 1200}, 'baseUrl': 'http://127.0.0.1:15128',
          'flows': {name: compact(value, name) for name, value in flows.items()}, 'downloads': downloads,
          'inputSources': [fingerprint(ROOT / path) for path in (
              'test-data/reference/ballistic/trajectory-0.1s-standard.csv',
              'test-data/reference/ballistic/target_trajectory_fit_package102_default.json',
              'test-data/reference/ballistic/target_trajectory_simulate_default.json',
              'test-data/baseline/ballistic/simulate/trajectory-0.csv',
              'test-data/reference/ballistic/T25/retime-irregular-reordered-source.csv')],
          'screenshots': [fingerprint(path) for path in sorted(screenshots.glob('*.png'))],
          'pnpmLock': fingerprint(ROOT / 'frontend/pnpm-lock.yaml'),
          # 本轮四个弹道主流的实际服务使用nio/server.jar，后续取消修复候选不能冒充该轮包。
          'jar': fingerprint(RUNTIME / 'nio/server.jar')}
(EVIDENCE / 'browser-summary.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

# 原162项与迁入后的实际方法名对应；原38弹道测试用T04记录明确区分Swing/诊断排除范围。
def test_names(directory):
    suites, names = [], set()
    for path in sorted(directory.glob('TEST-*.xml')):
        suite = ET.parse(path).getroot()
        suites.append({key: suite.attrib.get(key) for key in ('name', 'tests', 'failures', 'errors', 'skipped')})
        names.update((case.attrib['classname'], case.attrib['name']) for case in suite.findall('testcase'))
    return suites, names

original_suites, original_names = test_names(RUNTIME / 'original-space-validation/target/surefire-reports')
current_suites, current_names = test_names(ROOT / 'backend/target/surefire-reports')
missing = sorted(original_names - current_names)
mapping = {'originalSpaceTests': len(original_names), 'originalSuites': original_suites,
           'matchingCurrentTests': len(original_names & current_names), 'missingCurrentTests': missing,
           'originalBallisticBaseline': 'T04: 38 tests; current: 31 core migrated, 3 Swing and 4 diagnostic excluded'}
(EVIDENCE / 'original-test-mapping.json').write_text(json.dumps(mapping, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
assert len(original_names) == 162 and not missing, mapping
print(json.dumps({'passed': True, 'flows': list(flows), 'downloadsCompared': len(downloads),
                  'originalSpaceTests': len(original_names), 'matchingCurrentTests': len(original_names & current_names)}, ensure_ascii=False))
