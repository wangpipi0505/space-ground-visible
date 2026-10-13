"""真实服务模拟→登记→预览→下载，原JSON及两个CSV严格对照冻结基准。"""
import argparse
import copy
import csv
import hashlib
import io
import json
from pathlib import Path
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-url', default='http://127.0.0.1:18080')
    parser.add_argument('--storage-root', type=Path, default=ROOT / 'verification/runtime/T18/storage')
    args = parser.parse_args()
    runtime = ROOT / 'verification/runtime/T18/live-http'
    runtime.mkdir(parents=True, exist_ok=True)
    statuses = []

    def send(path, body=None, expected=200):
        payload = None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8')
        headers = {} if payload is None else {'Content-Type': 'application/json'}
        with urlopen(Request(args.base_url + path, data=payload, headers=headers), timeout=120) as response:
            data = response.read()
            statuses.append({'path': path, 'status': response.status, 'bytes': len(data)})
            assert response.status == expected
            return data

    assert send('/api/trajectory/health') == b'OK'
    conditions = json.loads((ROOT / 'test-data/baseline/ballistic/conditions.json').read_text(encoding='utf-8'))
    request = copy.deepcopy(next(case['request'] for case in conditions if case['name'] == 'simulate'))
    request.pop('outputDir')
    result_bytes = send('/api/trajectory/simulate', request)
    (runtime / 'simulate-response.json').write_bytes(result_bytes)
    actual = json.loads(result_bytes)
    expected = json.loads((ROOT / 'test-data/baseline/ballistic/simulate/result.json').read_text(encoding='utf-8'))
    normalized = copy.deepcopy(actual)
    for index, file in enumerate(normalized['files']):
        # 文件名含本轮生成时刻；只映射该路径字段，警告、模型、计数和所有其它值严格比较。
        file['csvPath'] = 'trajectory-' + str(index) + '.csv'
    assert normalized == expected, '模拟响应与原版完整字段有差异'
    files = []
    for index, file in enumerate(actual['files']):
        actual_path = Path(file['csvPath']).resolve(strict=True)
        actual_path.relative_to((args.storage_root / 'results').resolve(strict=True))
        matching = []
        for metadata_path in (args.storage_root / 'metadata').glob('*.json'):
            record = json.loads(metadata_path.read_text(encoding='utf-8'))
            if Path(record['reference']['serverPath']).resolve() == actual_path:
                matching.append(record['reference'])
        assert len(matching) == 1, '首次辅助登记前必须已有唯一计算登记'
        automatic = matching[0]
        reference = json.loads(send('/api/v1/ballistic/files/register-result', {
            'csvPath': file['csvPath'], 'taskName': actual['taskName'], 'targetId': file['targetId']
        }, expected=201))
        repeated = json.loads(send('/api/v1/ballistic/files/register-result', {
            'csvPath': file['csvPath'], 'taskName': actual['taskName'], 'targetId': file['targetId']
        }, expected=201))
        assert automatic['fileId'] == reference['fileId'] == repeated['fileId']
        data = send('/api/v1/ballistic/files/' + reference['fileId'] + '/download')
        original = (ROOT / 'test-data/baseline/ballistic/simulate' / ('trajectory-' + str(index) + '.csv')).read_bytes()
        assert data == original, '目标' + file['targetId'] + '的CSV字节有差异'
        (runtime / ('download-' + str(index) + '.csv')).write_bytes(data)
        preview = json.loads(send('/api/v1/ballistic/files/' + reference['fileId'] + '/preview?page=1&limit=3'))
        cells = list(csv.reader(io.StringIO(data.decode('utf-8-sig'))))
        assert preview['header'] == cells[0]
        assert preview['rows'] == cells[1:4]
        assert preview['totalRows'] == len(cells) - 1 == file['pointCount']
        assert reference['sizeBytes'] == len(data)
        files.append({'targetId': file['targetId'], 'pointCount': file['pointCount'], 'bytes': len(data),
                      'sha256': hashlib.sha256(data).hexdigest(), 'sameIdOnReregister': True,
                      'csvByteExact': True, 'previewCellExact': True,
                      'automaticRegistrationBeforeAuxiliaryCall': True, 'managedResultPath': True})
    evidence = {'passed': True, 'targetCount': actual['targetCount'], 'files': files,
                'requestStatuses': statuses, 'managedRegistrationAccepted': True,
                'responseComparison': '仅files[].csvPath映射原夹具文件名，其它字段严格相等'}
    (ROOT / 'verification/T18/live-http-compare.json').write_text(
        json.dumps(evidence, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(evidence, ensure_ascii=False))


if __name__ == '__main__':
    main()
