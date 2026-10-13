"""本人真实Chrome结果对照冻结原Controller/Fitter；仅映射服务csvPath，全部字段与CSV字节严格比较。"""
import csv
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
RUNTIME = ROOT / 'verification/runtime/T25'
EVIDENCE = ROOT / 'verification/T25'


def read_result(name):
    text = (RUNTIME / name).read_text(encoding='utf-8-sig')
    return json.JSONDecoder().raw_decode(text.split('### Result\n', 1)[1].lstrip())[0]


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def equal_response(actual, expected):
    expected = json.loads(json.dumps(expected))
    for index, file in enumerate(actual.get('files', [])):
        expected['files'][index]['csvPath'] = file['csvPath']
    assert actual == expected, (actual, expected)


def table(path):
    with Path(path).open(encoding='utf-8-sig', newline='') as source:
        return list(csv.DictReader(source))


def main():
    main_flow = read_result('browser-main-cli-final.txt')
    edge = read_result('browser-edge-cli-final.txt')
    (RUNTIME / 'browser-main-http-full.json').write_text(json.dumps(main_flow, ensure_ascii=False, indent=2), encoding='utf-8')
    (EVIDENCE / 'browser-edge-http.json').write_text(json.dumps(edge, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    default = RUNTIME / 'download-default.csv'
    golden = ROOT / 'test-data/baseline/ballistic/retime'
    assert default.read_bytes() == (golden / 'trajectory-0.csv').read_bytes()
    equal_response(json.loads(main_flow['computations'][0]['raw']), json.loads((golden / 'result.json').read_text(encoding='utf-8-sig')))
    assert (RUNTIME / 'download-response.json').read_bytes() == main_flow['computations'][0]['raw'].encode('utf-8')
    assert json.loads((RUNTIME / 'download-input.json').read_text(encoding='utf-8')) == main_flow['computations'][0]['request']
    source = ROOT / 'test-data/baseline/ballistic/simulate/trajectory-0.csv'
    assert Path(main_flow['inputReference']['serverPath']).read_bytes() == source.read_bytes()
    ecef = ['ECEF_X', 'ECEF_Y', 'ECEF_Z', 'ECEF_Vx', 'ECEF_Vy', 'ECEF_Vz']
    eci = ['ECI_X', 'ECI_Y', 'ECI_Z', 'ECI_VX', 'ECI_VY', 'ECI_VZ']
    original, retimed = table(source), table(default)
    assert len(original) == len(retimed) == 2576
    assert all(all(a[field] == b[field] for field in ecef) for a, b in zip(original, retimed))
    assert all(all(a[field] != b[field] for field in eci) for a, b in zip(original, retimed))
    comparisons = []
    for case in main_flow['computations'] + [edge['boundary']] + edge['negatives']:
        name = case['name']
        request_file, response_file, status_file = (RUNTIME / f'{name}-{kind}.{extension}' for kind, extension in [('request', 'json'), ('frozen-response', 'json'), ('frozen-status', 'txt')])
        request_file.write_text(json.dumps(case['request'], ensure_ascii=False, indent=2), encoding='utf-8')
        command = ['C:/Program Files/Eclipse Adoptium/jdk-8.0.472.8-hotspot/bin/java.exe', '-Dfile.encoding=UTF-8', '-Djava.awt.headless=true', '-cp', str(RUNTIME / 'root-reference'), 'FrozenSimulationReference', str(ROOT / 'verification/baseline-copies/ballistic/source/target/classes'), str(ROOT / 'verification/baseline-copies/ballistic/dependencies/runtime-jars'), str(request_file), str(RUNTIME / f'frozen-{name}'), str(response_file), str(status_file), 'retime']
        execution = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', check=True)
        (RUNTIME / f'{name}-frozen.log').write_text(execution.stdout + execution.stderr, encoding='utf-8')
        (RUNTIME / f'{name}-frozen-command.json').write_text(json.dumps(command, ensure_ascii=False, indent=2), encoding='utf-8')
        actual, frozen = json.loads(case['raw']), json.loads(response_file.read_text(encoding='utf-8'))
        assert int(status_file.read_text()) == case['status']
        equal_response(actual, frozen)
        check = {'name': name, 'status': case['status'], 'allResponseFieldsEqualAfterCsvPathMapping': True, 'originalClassOriginAsserted': True}
        if case['status'] == 200:
            browser_csv = RUNTIME / f'download-{name}.csv'
            assert browser_csv.read_bytes() == Path(frozen['files'][0]['csvPath']).read_bytes()
            check.update(csvByteEqual=True, csvBytes=browser_csv.stat().st_size, csvSHA256=digest(browser_csv), pointCount=actual['files'][0]['pointCount'])
        comparisons.append(check)
    boundary_source = table(ROOT / 'test-data/reference/ballistic/T25/retime-irregular-reordered-source.csv')
    boundary = table(RUNTIME / 'download-boundary.csv')
    assert all(all(a[field] == b[field] for field in ecef + ['来源说明', 'isOriginal']) for a, b in zip(boundary_source, boundary))
    assert [row['timeIntervalMs'] for row in boundary] == ['0.000', '1250.000', '1625.000']
    assert 'dt' not in boundary[0] and [row['isOriginal'] for row in boundary] == ['1', '-1', '0']
    window = json.loads(main_flow.pop('winRaw'))
    main_flow['winResponseSummary'] = {key: window['best'][key] for key in ['finalVisibleWindow', 'visibleArcCount', 'visiblePointCount', 'visibleDurationSec']}
    main_flow['windowAllResultCount'] = len(window['allResults'])
    # 生产风格全轨迹原文和multipart字节仅存ignored运行证据；提交审查摘要保留真实请求、引用和结果数量。
    for item in main_flow['auxiliary']:
        item.pop('request', None)
        if '/preview?' in item['url']:
            preview = json.loads(item.pop('raw'))
            item['preview'] = {key: preview[key] for key in ['fileId', 'header', 'totalRows', 'page', 'limit']}
            item['preview']['rowCount'] = len(preview['rows'])
    assert not main_flow['errors'] and not main_flow['pageErrors'] and not edge['successConsoleErrors'] and not edge['pageErrors']
    assert main_flow['winRequest']['ballistics'][0]['csvPath'] == main_flow['windowReference']['serverPath']
    (EVIDENCE / 'browser-main-http.json').write_text(json.dumps(main_flow, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    summary = {'passed': True, 'differences': [], 'default': {'rows': 2576, 'columns': 22, 'csvSHA256': digest(default), 'csvBytes': default.stat().st_size, 'goldenResponseOnlyCsvPathMappedEqual': True, 'responseDownloadRawByteEqual': True, 'submittedDownloadEqual': True, 'uploadSourceByteEqual': True, 'allSixEcefTextPreserved': True, 'allSixEciRecomputed': True}, 'window': {**main_flow['winResponseSummary'], 'exactRegisteredSource': True}, 'boundaryEcefUnknownMarkersPreserved': True, 'frozenComparisons': comparisons}
    (EVIDENCE / 'browser-evidence-compare.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
