"""T23实际浏览器证据整理：原JSON仅合法路径差，CSV逐字节核验；不修改或产生黄金。"""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RUNTIME = ROOT / 'verification/runtime/T23'
EVIDENCE = ROOT / 'verification/T23'

def extract(name):
    text = (RUNTIME / name).read_text(encoding='utf-8-sig')
    marker = '### Result\n'
    assert marker in text, name + '没有实际浏览器结果'
    return json.JSONDecoder().raw_decode(text.split(marker, 1)[1])[0]

def sha(data):
    return hashlib.sha256(data).hexdigest()

def main():
    actual = extract('browser-main-cli-final.txt')
    source = json.loads((ROOT / 'test-data/reference/ballistic/target_trajectory_fit_package102_default.json').read_text(encoding='utf-8'))
    assert 'outputDir' in source
    del source['outputDir']
    assert actual['request'] == source
    exported = json.loads((RUNTIME / 'download-input.json').read_text(encoding='utf-8'))
    assert exported == source
    response_bytes = (RUNTIME / 'download-response.json').read_bytes()
    assert response_bytes == actual['rawText'].encode('utf-8')
    response = json.loads(response_bytes)
    golden = json.loads((ROOT / 'test-data/baseline/ballistic/fit/result.json').read_text(encoding='utf-8'))
    path_mapping = []
    comparisons = []
    for index, (file, expected) in enumerate(zip(response['files'], golden['files'])):
        actual_path = Path(file['csvPath'])
        assert actual_path.is_relative_to(RUNTIME / 'storage/results'), '结果不属于本次管理运行根'
        assert actual_path.is_file()
        path_mapping.append({'targetId': file['targetId'], 'actual': str(actual_path), 'golden': expected['csvPath'], 'reason': '本次Web管理目录唯一运行路径'})
        file['csvPath'] = expected['csvPath']
        downloaded = (RUNTIME / f'download-trajectory-{index}.csv').read_bytes()
        expected_csv = (ROOT / 'test-data/baseline/ballistic/fit' / expected['csvPath']).read_bytes()
        assert downloaded == expected_csv, '下载CSV不逐字节一致'
        assert actual_path.read_bytes() == expected_csv, '管理目录CSV不逐字节一致'
        rows = downloaded.decode('utf-8').splitlines()[1:]
        assert len(rows) == expected['pointCount']
        original_rows = [row.split(',') for row in rows if row.split(',')[-1] == '1']
        assert len(original_rows) == 4
        comparisons.append({'targetId': file['targetId'], 'bytes': len(downloaded), 'rows': len(rows), 'originalCount': len(original_rows), 'downloadSha256': sha(downloaded), 'goldenSha256': sha(expected_csv), 'identicalBytes': True, 'originalUtc': ['-'.join(row[:3]) + ' ' + ':'.join(row[3:6]) for row in original_rows]})
    assert response == golden, '除明确管理路径差外原结果JSON不同'
    summary = {key: actual[key] for key in ['browser', 'timezone', 'source', 'status', 'targetEvidence', 'consoleErrors', 'pageErrors']}
    summary.update({'inputOnlyOutputDirOmitted': True, 'inputExportOriginalValuesEqual': True, 'rawResponseDownloadIdenticalBytes': True, 'rawResponseBytes': len(response_bytes), 'rawResponseSha256': sha(response_bytes), 'resultOnlyManagedCsvPathDifference': True, 'pathMapping': path_mapping, 'csv': comparisons, 'http': []})
    for http in actual['files']:
        entry = {key: value for key, value in http.items() if key != 'rawText'}
        if 'rawText' in http:
            raw = http['rawText'].encode('utf-8')
            entry.update({'responseBytes': len(raw), 'responseSha256': sha(raw)})
            parsed = json.loads(http['rawText'])
            if '/preview?' in http['url']:
                entry.update({key: parsed[key] for key in ['page', 'limit', 'totalRows', 'header', 'metadata']})
                entry['returnedRows'] = len(parsed['rows'])
            else:
                entry['reference'] = parsed
        summary['http'].append(entry)
    (EVIDENCE / 'browser-main-http.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    if (RUNTIME / 'browser-aux-recovery-cli-final.txt').exists():
        failure = extract('browser-aux-failure-cli-final.txt')
        recovery = extract('browser-aux-recovery-cli-final.txt')
        assert failure['preview']['status'] == failure['download']['status'] == 404
        assert failure['browserDownloads'] == 0 and failure['otherTargetUnaffected']
        assert recovery['registration']['status'] == 201 and recovery['preview']['status'] == 200
        assert recovery['computeCalls'] == 0 and recovery['errorsCleared']
        recovered_bytes = (RUNTIME / 'download-recovered.csv').read_bytes()
        assert recovered_bytes == (ROOT / 'test-data/baseline/ballistic/fit/trajectory-0.csv').read_bytes()
        auxiliary = {'failure': failure, 'recovery': recovery, 'recoveredCsvIdenticalGoldenBytes': True, 'recoveredCsvBytes': len(recovered_bytes), 'recoveredCsvSha256': sha(recovered_bytes)}
        (EVIDENCE / 'browser-aux-http.json').write_text(json.dumps(auxiliary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    if (RUNTIME / 'browser-edge-cli-final.txt').exists():
        edge = extract('browser-edge-cli-final.txt')
        assert edge['pageErrors'] == [] and edge['twoPointGraphPathCounts'] == [31] * 4
        assert [entry.get('status') for entry in edge['evidence']] == [None, None, 200, 400, 400]
        for entry in edge['evidence']:
            if 'request' in entry:
                request = entry.pop('request')
                entry.update({'requestTaskName': request['taskName'], 'requestPackageCount': len(request['package102']), 'requestUtcStart': request['taskStartTime'], 'requestUtcEnd': request['taskEndTime'], 'requestStepSec': request['interpolationStepSec'], 'requestSha256': sha(json.dumps(request, ensure_ascii=False, separators=(',', ':')).encode('utf-8'))})
            if 'rawText' in entry:
                raw = entry.pop('rawText')
                entry.update({'rawResponseBytes': len(raw.encode('utf-8')), 'rawResponseSha256': sha(raw.encode('utf-8'))})
                parsed = json.loads(raw)
                if entry['status'] == 200:
                    entry.update({'responseTaskName': parsed['taskName'], 'targetCount': parsed['targetCount'], 'files': [{key: value for key, value in file.items() if key != 'csvPath'} for file in parsed['files']]})
                else:
                    entry['originalError'] = parsed
        (EVIDENCE / 'browser-edge-http.json').write_text(json.dumps(edge, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'passed': True, 'csv': comparisons, 'onlyOutputDirOmitted': True, 'onlyManagedCsvPathDifference': True}, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    main()
