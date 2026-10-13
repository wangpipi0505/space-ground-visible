"""可执行中文HTTP示例：仅对隔离demo服务运行，使用仓库有来源fixture。

python docs/examples/http_flow.py --base-url http://127.0.0.1:18131
会保存请求/原字节响应于verification/runtime/T29/http-example；不会访问原版目录。
演示空间配置保存、记录维护和计算生成文件，因此请使用新运行根而非生产服务。
仅依赖Python标准库；服务已按README部署并启用demo，演示ID为观测星1/目标2。
"""
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import argparse
import copy
import csv
import hashlib
import io
import json
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
FILES = '/api/v1/ballistic/files'
SPACE = '/api/v1/space'
TASKS = SPACE + '/visibility/tasks'


def standard(kind='SPACE'):
    """明确十业务字段；标识身份只在弹道preview/内嵌载荷使用。"""
    return dict(payloadType=kind, minDistanceKm=0, maxDistanceKm=0,
                azimuthMinDeg=None, azimuthMaxDeg=None, elevationMinDeg=None,
                elevationMaxDeg=None, angleRateDegPerSec=-1 if kind == 'SPACE' else 180,
                sunAvoidHalfAngleDeg=0, startupAngleRateDegPerSec=-1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:18131')
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'verification/runtime/T29/http-example')
    args = parser.parse_args()
    out = args.output_dir.resolve()
    out.mkdir(parents=True, exist_ok=True)
    events, comparisons = [], []

    def http(method, path, name, body=None, expected=200, content_type='application/json'):
        """保留准确HTTP方法/请求/状态/响应，400等真实反例同样必须断言。"""
        payload = body if isinstance(body, bytes) else None if body is None else json.dumps(body, ensure_ascii=False, allow_nan=False).encode('utf-8')
        headers = {'Content-Type': content_type} if payload is not None else {}
        if payload is not None:
            (out / (name + '-request.bin')).write_bytes(payload)
        try:
            response = urlopen(Request(args.base_url + path, payload, headers, method=method), timeout=180)
        except HTTPError as error:
            response = error
        with response:
            raw = response.read()
            event = dict(name=name, method=method, path=path, status=response.status,
                         bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest(), headers=dict(response.headers))
        events.append(event)
        (out / (name + '-response.bin')).write_bytes(raw)
        assert event['status'] == expected, (name, event, raw[:500])
        print(name, event['status'], len(raw), flush=True)
        return raw

    def data(method, path, name, body=None, expected=200):
        raw = http(method, path, name, body, expected)
        return json.loads(raw) if raw else None

    def fixture(name):
        return json.loads((ROOT / name).read_text(encoding='utf-8-sig'))

    def upload(path, name):
        """先201获得真实fileId/serverPath，再预览、下载或用于原路径字段。"""
        binary = path.read_bytes()
        boundary = 'Example-' + uuid.uuid4().hex
        prefix = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="'
                  + path.name + '"\r\nContent-Type: text/csv\r\n\r\n').encode('utf-8')
        raw = http('POST', FILES, name, prefix + binary + ('\r\n--' + boundary + '--\r\n').encode('ascii'),
                   201, 'multipart/form-data; boundary=' + boundary)
        reference = json.loads(raw)
        assert reference['sizeBytes'] == len(binary)
        assert http('GET', FILES + '/' + reference['fileId'] + '/download', name + '-download') == binary
        preview = data('GET', FILES + '/' + reference['fileId'] + '/preview?page=1&limit=3', name + '-preview')
        assert len(preview['rows']) <= 3
        return reference

    def result_files(result, golden_dir, name):
        """原计算响应不加fileId；用实际csvPath登记，下载原字节与来源黄金比较。"""
        references = []
        expected = fixture('test-data/baseline/ballistic/' + golden_dir + '/result.json')
        normalized = copy.deepcopy(result)
        for i, file in enumerate(normalized['files']):
            file['csvPath'] = 'trajectory-' + str(i) + '.csv'
        assert normalized == expected, name + '响应除本轮路径外必须严格相同'
        for i, file in enumerate(result['files']):
            registration = dict(csvPath=file['csvPath'], taskName=result['taskName'], targetId=file['targetId'])
            reference = data('POST', FILES + '/register-result', name + '-register-' + str(i), registration, 201)
            again = data('POST', FILES + '/register-result', name + '-reregister-' + str(i), registration, 201)
            assert reference['fileId'] == again['fileId']
            binary = http('GET', FILES + '/' + reference['fileId'] + '/download', name + '-download-' + str(i))
            golden = ROOT / 'test-data/baseline/ballistic' / golden_dir / ('trajectory-' + str(i) + '.csv')
            assert binary == golden.read_bytes(), str(golden)
            preview = data('GET', FILES + '/' + reference['fileId'] + '/preview?page=1&limit=3', name + '-preview-' + str(i))
            rows = list(csv.reader(io.StringIO(binary.decode('utf-8-sig'))))
            assert preview['header'] == rows[0] and preview['rows'] == rows[1:4]
            assert preview['totalRows'] == file['pointCount'] == len(rows) - 1
            comparisons.append(dict(name=name, targetId=file['targetId'], pointCount=file['pointCount'],
                                    sha256=hashlib.sha256(binary).hexdigest(), csvByteExact=True, previewCellExact=True))
            references.append(reference)
        return references

    def terminal(identity, name):
        """200/RUNNING取消响应是受理，终态必须由状态接口真实轮询确认。"""
        deadline = time.monotonic() + 120
        index = 0
        while time.monotonic() < deadline:
            status = data('GET', TASKS + '/' + identity, name + '-' + str(index))
            if status['status'] in ('SUCCEEDED', 'FAILED', 'CANCELLED'):
                return status
            index += 1
            time.sleep(0.2)
        raise AssertionError('任务未按期终结')

    assert data('GET', SPACE + '/health', 'space-health')['status'] == 'UP'
    assert http('GET', '/api/orbit/health', 'orbit-health') == b'OK'
    assert http('GET', '/api/trajectory/health', 'trajectory-health') == b'OK'
    for path in ('satellites', 'satellites/page?pageNumber=1&pageSize=10', 'satellites/options',
                 'stations', 'defaults', 'targets/belong-options', 'satellites/1/payload', 'satellites/1/payload-profiles'):
        data('GET', SPACE + '/' + path, 'catalog-' + path.split('?')[0].replace('/', '-'))
    query = fixture('test-data/baseline/space/target-query-request.json')
    data('POST', SPACE + '/targets/query', 'target-query', query)
    profile = standard()
    data('PUT', SPACE + '/satellites/1/payload-profiles', 'space-standard-save', profile)
    ground = dict(standard('GROUND'), angleRateDegPerSec=0)
    saved_ground = data('PUT', SPACE + '/satellites/1/payload-profiles', 'ground-zero-real-limit', ground)
    assert saved_ground['activePayloadType'] == 'GROUND'
    assert {item['payloadType'] for item in saved_ground['profiles']} == {'SPACE', 'GROUND'}
    data('PUT', SPACE + '/satellites/1/payload-profiles', 'restore-space-active', profile)
    legacy = dict(profile)
    legacy.pop('payloadType'); legacy['minDistanceM'] = legacy.pop('minDistanceKm') * 1000
    legacy['maxDistanceM'] = legacy.pop('maxDistanceKm') * 1000
    data('PUT', SPACE + '/satellites/1/payload', 'space-legacy-metres-save', legacy)
    downlink = fixture('test-data/baseline/space/downlink-request.json')
    station = data('GET', SPACE + '/stations', 'stations-for-link')[0]['id']
    downlink['stations'][0]['stationId'] = station
    downlink['links'][0]['stationId'] = station
    data('POST', SPACE + '/downlink/plan', 'downlink-plan', downlink)
    data('POST', SPACE + '/downlink/validate-window', 'downlink-validation', downlink)
    plan = fixture('test-data/baseline/space/visibility-request.json')
    sync = data('POST', SPACE + '/visibility/plan', 'sync', plan)
    identity = data('POST', TASKS, 'async-submit', plan)['taskId']
    assert terminal(identity, 'async-poll')['status'] == 'SUCCEEDED'
    assert data('GET', TASKS + '/' + identity + '/result', 'async-result') == sync
    # 长范围加1秒粗精选步长，确保取消发生在仍运行的任务上；不用延时猜终态。
    long_plan = dict(plan, endTime='2026-07-03T10:00:00Z', coarseStepSec=1, fineStepSec=1)
    cancelled = data('POST', TASKS, 'cancel-submit', long_plan)['taskId']
    data('DELETE', TASKS + '/' + cancelled, 'cancel-accepted')
    assert terminal(cancelled, 'cancel-poll')['status'] == 'CANCELLED'
    conflict = data('GET', TASKS + '/' + cancelled + '/result', 'cancel-result-409', expected=409)
    assert 'CANCELLED' in conflict['error']
    data('GET', TASKS + '/unknown-example', 'unknown-task-404', expected=404)
    record_path = SPACE + '/visibility/task-results'
    cancel_deadline = time.monotonic() + 10
    while True:
        cancel_page = data('GET', record_path + '?keyword=' + cancelled, 'cancel-record-' + str(len(events)))
        if cancel_page['records'] and cancel_page['records'][0]['status'] == 'CANCELLED':
            break
        assert time.monotonic() < cancel_deadline
        time.sleep(0.1)
    deadline = time.monotonic() + 10
    while True:
        records = data('GET', record_path + '?keyword=' + identity, 'record-page-' + str(len(events)))
        if records['records'] and records['records'][0]['status'] == 'SUCCEEDED':
            break
        assert time.monotonic() < deadline
        time.sleep(0.1)
    record_id = records['records'][0]['id']
    detail = data('GET', record_path + '/' + record_id, 'record-detail')
    assert isinstance(detail['requestJson'], str) and isinstance(detail['resultJson'], str)
    data('PUT', record_path + '/' + record_id, 'record-remark', dict(remark='中文HTTP示例'))
    assert sync['results'], '演示标准profile应产生真实目标结果'
    selection = dict(planningWindow=dict(startTime=plan['startTime'], endTime=plan['endTime']),
                     mergedTimeRange=dict(segments=[], totalDurationSec=0),
                     selections=[sync['results'][0]], remark='示例保存实际首个目标结果')
    assert data('PUT', record_path + '/' + record_id + '/selection', 'record-selection', selection)['received']
    http('DELETE', record_path + '/' + record_id, 'record-delete')
    data('GET', record_path + '/' + record_id, 'deleted-record-404', expected=404)

    reference = upload(ROOT / 'test-data/reference/ballistic/trajectory-0.1s-standard.csv', 'upload-window')
    cases = fixture('test-data/baseline/ballistic/conditions.json')
    # visibility-http-ground是冻结的真实Controller合同；其余visibility-legacy案例
    # 由Java入口显式换成140度简单锥，不能拿它们当HTTP PayloadDef同口径黄金。
    window = copy.deepcopy(next(case['request'] for case in cases if case['name'] == 'visibility-http-ground'))
    for target in window['ballistics']:
        target['csvPath'] = reference['serverPath']
    legacy_result = data('POST', '/api/orbit/visibility/search', 'window-legacy', window)
    # 比较真实旧合同；只有实际上传导致的服务端路径允许映射，数值与顺序全保留。
    normalized = copy.deepcopy(legacy_result)
    golden = fixture('test-data/baseline/ballistic/visibility-http-ground/result.json')
    def normalize_path(value):
        if isinstance(value, dict):
            return {key: '<REFERENCE_CSV>' if key == 'csvPath' else normalize_path(item) for key, item in value.items()}
        if isinstance(value, list):
            return [normalize_path(item) for item in value]
        return value
    assert normalize_path(normalized) == normalize_path(golden)
    preview_payload = dict(standard(), payloadId='DOC-SPACE', payloadName='中文标准示例')
    assert data('POST', '/api/v1/ballistic/payloads/preview', 'payload-preview', preview_payload) == preview_payload
    invalid = dict(preview_payload, payloadType='GROUND', angleRateDegPerSec=-1)
    assert data('POST', '/api/v1/ballistic/payloads/preview', 'ground-minus-one-400', invalid, 400)['code'] == 'INVALID_ARGUMENT'
    invalid = dict(preview_payload, azimuthMinDeg=0, azimuthMaxDeg=None)
    data('POST', '/api/v1/ballistic/payloads/preview', 'single-angle-end-400', invalid, 400)
    for satellite in window['satellites']:
        satellite['payloads'] = [dict(preview_payload)]
    standard_result = data('POST', '/api/orbit/visibility/search', 'window-standard', window)
    assert standard_result['payloadType'] == 'SPACE'
    assert all(standard_result['visibilityConstraint'][key] is None for key in
               ('simpleConeHalfAngleDeg', 'halfConeAngleDeg', 'innerConeAngleDeg', 'outerConeAngleDeg'))
    for satellite in window['satellites']:
        satellite['payloads'][0]['startupAngleRateDegPerSec'] = 1000000
    assert data('POST', '/api/orbit/visibility/search', 'startup-only-metadata', window) == standard_result
    for name, route in [('fit', '/api/trajectory/fit/package102'), ('simulate', '/api/trajectory/simulate')]:
        request = copy.deepcopy(next(case['request'] for case in cases if case['name'] == name))
        request.pop('outputDir', None)
        result = data('POST', route, name, request)
        references = result_files(result, name, name)
        if name == 'simulate':
            simulation_reference = references[0]
            # 生成轨迹与星历必须在同一UTC日；这是引用结果，不重新上传或改CSV。
            reused = copy.deepcopy(window)
            reused.update(ballistics=[dict(csvPath=simulation_reference['serverPath'], targetId='SIM-001')],
                          startOffsetSec=0, numCalculations=1)
            reused['satellites'] = [copy.deepcopy(window['satellites'][0])]
            reused['satellites'][0]['epochUtc'] = '2026/06/04 04:00:00.000'
            simulation_window = data('POST', '/api/orbit/visibility/search', 'simulation-reference-window', reused)
            assert simulation_window['best']['visibleArcCount'] > 0
    retime = copy.deepcopy(next(case['request'] for case in cases if case['name'] == 'retime'))
    retime.pop('outputDir', None)
    # 重定时需要原模拟ECEF列名；不能拿窗口标准文件偷偷改解析器。
    retime['trajectoryCsvPath'] = simulation_reference['serverPath']
    retimed_reference = result_files(data('POST', '/api/trajectory/retime', 'retime', retime), 'retime', 'retime')[0]
    reused['ballistics'][0].update(csvPath=retimed_reference['serverPath'], targetId='RETIMED-TGT')
    reused['satellites'][0]['epochUtc'] = retime['launchTimeUtc']
    retimed_window = data('POST', '/api/orbit/visibility/search', 'retime-reference-window', reused)
    assert retimed_window['best']['visibleArcCount'] > 0
    data('GET', FILES + '/unknown-example/preview', 'unknown-file-404', expected=404)
    summary = dict(passed=True, baseUrl=args.base_url, events=events, csvComparisons=comparisons,
                   legacyWindowGoldenExact=True, syncAsyncExact=True, cancelledResultStatus=409, cancelledRecordStatus='CANCELLED',
                   simulationReuseArcs=simulation_window['best']['visibleArcCount'],
                   retimeReuseArcs=retimed_window['best']['visibleArcCount'],
                   sourceFixtures='test-data/reference及baseline；未修改参考数值字节')
    (out / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('PASS HTTP requests=', len(events), 'CSV golden comparisons=', len(comparisons))


if __name__ == '__main__':
    main()
