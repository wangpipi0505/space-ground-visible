"""提取本人CLI真实结果并严格比较黄金/独立原实现；只映射csvPath，不忽略任何其他字段/数值/告警。"""
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
RUNTIME = ROOT / 'verification/runtime/T24'
EVIDENCE = ROOT / 'verification/T24'


def read_result(name):
    text = (RUNTIME / name).read_text(encoding='utf-8-sig')
    return json.loads(text.split('### Result\n')[1].split('\n###')[0])


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def compare_response(actual, expected):
    # 路径仅管理映射；任意其他字段改变都使整个响应对象比较失败。
    if 'files' in actual:
        expected = json.loads(json.dumps(expected))
        for index, file in enumerate(actual['files']):
            expected['files'][index]['csvPath'] = file['csvPath']
    assert actual == expected, (actual, expected)


def main():
    main_flow = read_result('browser-main-cli-final.txt')
    window = json.loads(main_flow.pop('winRaw'))
    best = window['best']
    main_flow['winResponseSummary'] = {key: best[key] for key in ['finalVisibleWindow', 'visibleArcCount', 'visiblePointCount', 'visibleDurationSec']}
    csv = RUNTIME / 'download-default.csv'
    golden = ROOT / 'test-data/baseline/ballistic/simulate'
    assert csv.read_bytes() == (golden / 'trajectory-0.csv').read_bytes()
    compare_response(json.loads(main_flow['raw']), json.loads((golden / 'result.json').read_text(encoding='utf-8-sig')))
    assert (RUNTIME / 'download-response.json').read_bytes() == main_flow['raw'].encode('utf-8')
    # 窗口响应原字节直接从CLI原raw读取，避免重新序列化数字/格式。
    window_raw = read_result('browser-main-cli-final.txt')['winRaw'].encode('utf-8')
    assert (RUNTIME / 'download-window-response.json').read_bytes() == window_raw
    assert main_flow['winRequest']['ballistics'][0]['csvPath'] == main_flow['reference']['serverPath']
    assert main_flow['reference']['fileId'] == main_flow['preview']['fileId']
    main_flow['csvSHA256'], main_flow['csvBytes'] = digest(csv), csv.stat().st_size
    (EVIDENCE / 'browser-main-http.json').write_text(json.dumps(main_flow, ensure_ascii=False, indent=2), encoding='utf-8')

    edge = read_result('browser-edge-cli.txt')
    (EVIDENCE / 'browser-edge-http.json').write_text(json.dumps(edge, ensure_ascii=False, indent=2), encoding='utf-8')
    comparisons = []
    for case in edge['outcomes'] + edge['negatives']:
        name = case['name']
        request_file, response_file, status_file = (RUNTIME / f'{name}-{kind}.{extension}' for kind, extension in [('request', 'json'), ('frozen-response', 'json'), ('frozen-status', 'txt')])
        request_file.write_text(json.dumps(case['request'], ensure_ascii=False, indent=2), encoding='utf-8')
        command = ['C:/Program Files/Eclipse Adoptium/jdk-8.0.472.8-hotspot/bin/java.exe', '-Dfile.encoding=UTF-8', '-Djava.awt.headless=true', '-cp', str(RUNTIME / 'root-reference'), 'FrozenSimulationReference', str(ROOT / 'verification/baseline-copies/ballistic/source/target/classes'), str(ROOT / 'verification/baseline-copies/ballistic/dependencies/runtime-jars'), str(request_file), str(RUNTIME / f'frozen-{name}'), str(response_file), str(status_file)]
        execution = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', check=True)
        (RUNTIME / f'{name}-frozen.log').write_text(execution.stdout + execution.stderr, encoding='utf-8')
        actual, frozen = json.loads(case['raw']), json.loads(response_file.read_text(encoding='utf-8'))
        assert int(status_file.read_text()) == case['status']
        compare_response(actual, frozen)
        check = {'name': name, 'status': case['status'], 'allResponseFieldsEqualAfterCsvPathMapping': True, 'originalClassOriginAsserted': True}
        if case['status'] == 200:
            original_csv = Path(frozen['files'][0]['csvPath'])
            browser_csv = RUNTIME / f'download-{name}.csv'
            assert browser_csv.read_bytes() == original_csv.read_bytes()
            check.update(csvBytesEqual=True, csvBytes=browser_csv.stat().st_size, csvSHA256=digest(browser_csv), pointCount=actual['files'][0]['pointCount'])
        comparisons.append(check)
    assert edge['invalidLocalHttpCalls'] == 0 and edge['recoveryStatus'] == 200 and not edge['successConsoleErrors'] and not edge['pageErrors']
    summary = {'passed': True, 'differences': [], 'default': {'csvSHA256': digest(csv), 'csvBytes': csv.stat().st_size, 'goldenResponseEqualAfterCsvPathMapping': True, 'responseRawDownloadBytesEqual': True}, 'window': {**main_flow['winResponseSummary'], 'rawDownloadBytesEqual': True, 'exactRegisteredSource': True}, 'frozenComparisons': comparisons, 'negativeLocalHttpCalls': 0}
    (EVIDENCE / 'browser-evidence-compare.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
