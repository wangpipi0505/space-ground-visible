"""用本事项独立安装包验证实际上传、重定时、自动登记和完整结果下载，不代替正式 Vue 页面验收。"""
import argparse
import copy
import csv
import hashlib
import io
import json
import uuid
from decimal import Decimal
from pathlib import Path
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-url', default='http://127.0.0.1:18080')
    parser.add_argument('--storage-root', type=Path, default=ROOT / 'verification/runtime/T17/storage')
    args = parser.parse_args()
    runtime = ROOT / 'verification/runtime/T17/live-http'
    runtime.mkdir(parents=True, exist_ok=True)
    statuses = []

    def send(path, body=None, expected=200, content_type=None):
        if body is not None and not isinstance(body, bytes):
            body = json.dumps(body, ensure_ascii=False).encode('utf-8')
            content_type = 'application/json'
        headers = {} if content_type is None else {'Content-Type': content_type}
        with urlopen(Request(args.base_url + path, data=body, headers=headers), timeout=120) as response:
            data = response.read()
            statuses.append({'path': path, 'status': response.status, 'bytes': len(data)})
            assert response.status == expected
            return data

    assert send('/api/trajectory/health') == b'OK'
    source = (ROOT / 'test-data/baseline/ballistic/simulate/trajectory-0.csv').read_bytes()
    boundary = 'T17-' + uuid.uuid4().hex
    # 原 CSV 内容不变，浏览器本地文件通过标准 multipart 传到服务端后使用 serverPath。
    upload_body = (('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; '
                    'filename="T17-上传弹道.csv"\r\nContent-Type: text/csv\r\n\r\n').encode('utf-8')
                   + source + ('\r\n--' + boundary + '--\r\n').encode('ascii'))
    uploaded = json.loads(send('/api/v1/ballistic/files', upload_body, 201,
                               'multipart/form-data; boundary=' + boundary))
    assert uploaded['originalName'] == 'T17-上传弹道.csv'
    assert uploaded['sizeBytes'] == len(source)
    assert send('/api/v1/ballistic/files/' + uploaded['fileId'] + '/download') == source
    conditions = json.loads((ROOT / 'test-data/baseline/ballistic/conditions.json').read_text(encoding='utf-8'))
    request = copy.deepcopy(next(case['request'] for case in conditions if case['name'] == 'retime'))
    request.pop('outputDir', None)
    request['trajectoryCsvPath'] = uploaded['serverPath']
    result_bytes = send('/api/trajectory/retime', request)
    (runtime / 'retime-response.json').write_bytes(result_bytes)
    actual = json.loads(result_bytes, parse_float=Decimal)
    expected = json.loads((ROOT / 'test-data/baseline/ballistic/retime/result.json').read_text(encoding='utf-8'),
                          parse_float=Decimal)
    normalized = copy.deepcopy(actual)
    for index, file in enumerate(normalized['files']):
        file['csvPath'] = 'trajectory-' + str(index) + '.csv'
    assert normalized == expected, '重定时响应与原版完整字段有差异'
    files = []
    for index, file in enumerate(actual['files']):
        actual_path = Path(file['csvPath']).resolve(strict=True)
        actual_path.relative_to((args.storage_root / 'results').resolve(strict=True))
        # 辅助 register-result 之前直接核验持久元数据，避免把其自身登记误写为计算自动登记。
        matching = []
        for metadata_path in (args.storage_root / 'metadata').glob('*.json'):
            record = json.loads(metadata_path.read_text(encoding='utf-8'))
            if Path(record['reference']['serverPath']).resolve() == actual_path:
                matching.append(record['reference'])
        assert len(matching) == 1, '计算结果在首次辅助登记前没有唯一自动登记'
        automatic = matching[0]
        reference = json.loads(send('/api/v1/ballistic/files/register-result', {
            'csvPath': file['csvPath'], 'taskName': actual['taskName'], 'targetId': file['targetId']
        }, 201))
        repeated = json.loads(send('/api/v1/ballistic/files/register-result', {
            'csvPath': file['csvPath'], 'taskName': actual['taskName'], 'targetId': file['targetId']
        }, 201))
        assert automatic['fileId'] == reference['fileId'] == repeated['fileId']
        data = send('/api/v1/ballistic/files/' + reference['fileId'] + '/download')
        original = (ROOT / 'test-data/baseline/ballistic/retime' / ('trajectory-' + str(index) + '.csv')).read_bytes()
        assert data == original, '重定时CSV完整字节有差异'
        (runtime / ('download-' + str(index) + '.csv')).write_bytes(data)
        preview = json.loads(send('/api/v1/ballistic/files/' + reference['fileId'] + '/preview?page=1&limit=3'))
        cells = list(csv.reader(io.StringIO(data.decode('utf-8-sig'))))
        assert preview['header'] == cells[0]
        assert preview['rows'] == cells[1:4]
        assert preview['totalRows'] == len(cells) - 1 == file['pointCount']
        assert reference['sizeBytes'] == len(data)
        files.append({'targetId': file['targetId'], 'pointCount': file['pointCount'], 'bytes': len(data),
                      'sha256': hashlib.sha256(data).hexdigest(), 'sameIdOnReregister': True,
                      'automaticRegistrationBeforeAuxiliaryCall': True, 'csvByteExact': True,
                      'previewCellExact': True, 'managedResultPath': True})
    evidence = {'passed': True, 'targetCount': actual['targetCount'], 'files': files,
                'requestStatuses': statuses, 'uploadedSourceByteExact': True,
                'responseComparison': '仅files[].csvPath映射原夹具文件名，其它字段严格相等'}
    (ROOT / 'verification/T17/live-http-compare.json').write_text(
        json.dumps(evidence, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(evidence, ensure_ascii=False))


if __name__ == '__main__':
    main()
