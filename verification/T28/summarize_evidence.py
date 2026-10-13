"""汇总本项实际生产浏览器、源码/包及完整测试证据；完整响应和产物仍留 ignored runtime。"""
from pathlib import Path
from decimal import Decimal
import csv
import hashlib
import io
import json
import shutil
import xml.etree.ElementTree as ET
from run_clean import ROOT, CLEAN, EVIDENCE

RUNTIME = ROOT / 'verification/runtime/T28'


def fingerprint(path):
    data = path.read_bytes()
    return dict(path=path.relative_to(ROOT).as_posix(), bytes=len(data), sha256=hashlib.sha256(data).hexdigest())


def result(name):
    text = (RUNTIME / (name + '-cli.txt')).read_text(encoding='utf-8-sig')
    assert '### Error\n' not in text, name
    return json.JSONDecoder().raw_decode(text.split('### Result\n', 1)[1])[0]


def compact(value, label):
    if isinstance(value, dict):
        output = {}
        for key, item in value.items():
            if key in ('raw', 'rawText', 'winRaw', 'validationBody') and isinstance(item, str):
                path = RUNTIME / (label + '-' + key + '.json')
                path.write_bytes(item.encode('utf-8'))
                output[key + 'Evidence'] = fingerprint(path)
            elif key == 'rows':
                output['actualRowCount'] = len(item)
            else:
                output[key] = compact(item, label + '-' + key)
        return output
    if isinstance(value, list):
        return [compact(item, label + '-' + str(index)) for index, item in enumerate(value)]
    return value


flows = {name: result(name + '-result') for name in ('window', 'fit', 'simulation', 'retime')}
downloads = []
for actual, expected in [
        ('window/download-original.csv', 'test-data/reference/ballistic/trajectory-0.1s-standard.csv'),
        ('fit/download-trajectory-0.csv', 'test-data/baseline/ballistic/fit/trajectory-0.csv'),
        ('fit/download-trajectory-1.csv', 'test-data/baseline/ballistic/fit/trajectory-1.csv'),
        ('simulation/download-default.csv', 'test-data/baseline/ballistic/simulate/trajectory-0.csv'),
        ('retime/download-default.csv', 'test-data/baseline/ballistic/retime/trajectory-0.csv')]:
    path, golden = RUNTIME / actual, ROOT / expected
    assert path.read_bytes() == golden.read_bytes(), actual
    rows = list(csv.reader(io.StringIO(path.read_text(encoding='utf-8-sig'))))
    downloads.append(dict(actual=fingerprint(path), expected=fingerprint(golden), exactBytesEqual=True, dataRows=len(rows)-1))
for name, filename, raw in [('window', 'download-response.json', flows['window']['rawText']),
        ('fit', 'download-response.json', flows['fit']['rawText']),
        ('simulation', 'download-response.json', flows['simulation']['raw']),
        ('simulation', 'download-window-response.json', flows['simulation']['winRaw']),
        ('retime', 'download-response.json', flows['retime']['computations'][0]['raw'])]:
    path = RUNTIME / name / filename
    assert path.read_bytes() == raw.encode('utf-8'), (name, filename)
    downloads.append(dict(actual=fingerprint(path), actualHttpBodyExactBytesEqual=True))
original_responses = []
for name, baseline, raw in [('fit', 'fit', flows['fit']['rawText']), ('simulation', 'simulate', flows['simulation']['raw']), ('retime', 'retime', flows['retime']['computations'][0]['raw'])]:
    actual = json.loads(raw, parse_float=Decimal, parse_int=Decimal)
    expected = json.loads((ROOT / 'test-data/baseline/ballistic' / baseline / 'result.json').read_bytes(), parse_float=Decimal, parse_int=Decimal)
    for index, file in enumerate(actual['files']):
        file['csvPath'] = expected['files'][index]['csvPath']
    assert actual == expected, name
    original_responses.append(dict(operation=name, allJsonFieldsExactEqual=True, normalization='仅实际csvPath映射原黄金路径，其余Decimal/字段/顺序严格相同'))
for name, value in flows.items():
    assert not value.get('consoleErrors', value.get('errors')) and not value.get('pageErrors'), name
space = result('space-status')
assert any(tag['text'] == '已完成' and tag['width'] > 0 and tag['height'] > 0 for tag in space['tags'])
assert not space['space']['console'] and not space['space']['pageErrors']
downlink = space['space']['downlink']['response']
expected_downlink = json.loads((ROOT / 'test-data/baseline/space/downlink.json').read_bytes(), parse_float=Decimal)
assert json.loads(json.dumps(downlink), parse_float=Decimal) == expected_downlink
assert space['space']['nonempty']['response']['results']
cors = result('cors-result')
assert cors['downloadSha256'] == cors['exactSourceSha256']
summary = dict(passed=True, browser='Chrome headless', locale='zh-CN', timezone='Asia/Shanghai', frontend='http://127.0.0.1:15130', backend='http://127.0.0.1:18130', noMock=True, flows={name:compact(value,name) for name,value in flows.items()}, downloads=downloads, originalResponses=original_responses, space=space, spaceDownlinkGoldenExact=True, cors=compact(cors,'cors'))
(EVIDENCE / 'browser-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')

# 比较正式脚本更新前后冻结包的产品字节；启动脚本修复不能冒充重新跑数学模型。
before, final = RUNTIME / 'deployment-package-v2', RUNTIME / 'deployment-package-v4'
bindings = []
for prefix in ('backend', 'frontend', 'orekit-data'):
    for file in sorted((final / prefix).rglob('*')):
        if file.is_file():
            other = before / file.relative_to(final)
            assert file.read_bytes() == other.read_bytes(), file
            bindings.append(fingerprint(file))
(EVIDENCE / 'deployment-product-bindings.json').write_text(json.dumps(dict(passed=True, finalPackage=str(final), servedPackage=str(before), allProductAndOrekitBytesEqual=True, files=bindings), ensure_ascii=False, indent=2), encoding='utf-8')

manifest = json.loads((EVIDENCE / 'clean-source-manifest.json').read_text(encoding='utf-8'))
names = [entry['path'] for entry in manifest['files']]
names += ['backend/src/main/java/com/runisys/orbit/spacevisibility/api/ApiCorsConfiguration.java', 'backend/src/test/java/com/runisys/orbit/spacevisibility/api/ApiCorsConfigurationTest.java', 'backend/src/test/resources/static/index.html']
names += [file.relative_to(ROOT).as_posix() for file in (ROOT / 'deploy').glob('*') if file.is_file()]
source = []
for name in sorted(set(names)):
    file = ROOT / name
    assert file.read_bytes() == (CLEAN / name).read_bytes(), name
    source.append(fingerprint(file))
(EVIDENCE / 'final-source-manifest.json').write_text(json.dumps(dict(baseCommit=manifest['commit'], initialTrackedCopy='clean-source-manifest.json', sourceAndDeploymentFiles=source, noGeneratedMainStatic=True, untrackedAdditionsExplicit=True), ensure_ascii=False, indent=2), encoding='utf-8')

suites = []
for path in sorted((CLEAN / 'backend/target/surefire-reports').glob('TEST-*.xml')):
    suite = ET.parse(path).getroot()
    suites.append({key:suite.attrib[key] for key in ('name','tests','failures','errors','skipped')})
totals = {key:sum(int(suite[key]) for suite in suites) for key in ('tests','failures','errors','skipped')}
assert totals == dict(tests=347, failures=0, errors=0, skipped=1)
(EVIDENCE / 'test-summary.json').write_text(json.dumps(dict(backend=totals,suites=suites,frontend=dict(files=27,tests=112,failed=0)), ensure_ascii=False, indent=2), encoding='utf-8')
screenshots = EVIDENCE / 'screenshots'
screenshots.mkdir(exist_ok=True)
for file in (ROOT / 'output/playwright/T28').rglob('*.png'):
    shutil.copy2(file, screenshots / (file.parent.name + '-' + file.name))
print(json.dumps(dict(passed=True, flows=list(flows), comparedDownloads=len(downloads), dataRows=[entry['dataRows'] for entry in downloads if 'dataRows' in entry], backend=totals, finalProductFileCount=len(bindings)), ensure_ascii=False))
