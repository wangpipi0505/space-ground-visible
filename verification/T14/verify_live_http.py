"""用真实独立服务验证上传引用可直接计算，并严格对照冻结 HTTP 请求业务结果。"""
import argparse
import copy
import hashlib
import json
import uuid
from decimal import Decimal
from pathlib import Path
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
SOURCE = '<WORKSPACE>\\test-data\\reference\\ballistic\\trajectory-0.1s-standard.csv'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-url', default='http://127.0.0.1:18080')
    args = parser.parse_args()
    runtime = ROOT / 'verification/runtime/T14/live-http'
    runtime.mkdir(parents=True, exist_ok=True)
    statuses = []

    def send(path, body=None, content_type=None):
        headers = {'Content-Type': content_type} if content_type else {}
        with urlopen(Request(args.base_url + path, data=body, headers=headers), timeout=120) as response:
            data = response.read()
            statuses.append({'path': path, 'status': response.status, 'bytes': len(data)})
            return data

    assert send('/api/orbit/health') == b'OK'
    original = (ROOT / 'test-data/reference/ballistic/trajectory-0.1s-standard.csv').read_bytes()
    boundary = 't14-' + uuid.uuid4().hex
    prefix = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; '
              'filename="trajectory-live.csv"\r\nContent-Type: text/csv\r\n\r\n').encode('ascii')
    upload = json.loads(send('/api/v1/ballistic/files', prefix + original
                            + ('\r\n--' + boundary + '--\r\n').encode('ascii'),
                            'multipart/form-data; boundary=' + boundary))
    assert upload['sizeBytes'] == len(original)
    conditions = json.loads((ROOT / 'test-data/baseline/ballistic/conditions.json').read_text(encoding='utf-8'))
    request = copy.deepcopy(next(case['request'] for case in conditions if case['name'] == 'visibility-http-ground'))
    for target in request['ballistics']:
        assert target['csvPath'] == SOURCE
        target['csvPath'] = upload['serverPath']
    actual_bytes = send('/api/orbit/visibility/search',
                        json.dumps(request, ensure_ascii=False).encode('utf-8'), 'application/json')
    (runtime / 'response.json').write_bytes(actual_bytes)
    actual = json.loads(actual_bytes, parse_float=Decimal)
    expected = json.loads((ROOT / 'test-data/baseline/ballistic/visibility-http-ground/result.json').read_text(
        encoding='utf-8'), parse_float=Decimal)
    mapped = 0

    def canonical(value):
        nonlocal mapped
        if isinstance(value, dict):
            normalized = {}
            for key, item in value.items():
                # 只把本次输入上传引用映射为原夹具标识，其它字符串和所有数值均原样比较。
                if key in ('csvPath', 'sourceCsvPath') and item == upload['serverPath']:
                    normalized[key] = SOURCE
                    mapped += 1
                else:
                    normalized[key] = canonical(item)
            return normalized
        if isinstance(value, list):
            return [canonical(item) for item in value]
        return value

    assert canonical(actual) == expected, '完整 HTTP 结果与原版冻结业务结果有差异'
    assert len(actual['allResults']) == 3
    assert [entry['status'] for entry in statuses] == [200, 201, 200]
    evidence = {'passed': True, 'requestStatuses': statuses, 'mappedInputPathFields': mapped,
                'bestOffsetSecond': actual['best']['offsetSecond'], 'allOffsetsCompared': 3,
                'fixture': 'visibility-http-ground', 'visibleArcCount': actual['best']['visibleArcCount'],
                'inputSha256': hashlib.sha256(original).hexdigest(),
                'note': '此原HTTP对地夹具为空窗口；非空窗口另由本项原版同请求测试验证。'}
    (ROOT / 'verification/T14/live-http-compare.json').write_text(
        json.dumps(evidence, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(evidence, ensure_ascii=False))


if __name__ == '__main__':
    main()
