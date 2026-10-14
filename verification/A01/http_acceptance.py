"""A01 真实 HTTP 扩展验收，仅使用标准库，不启动或重载服务。

full 复用 T29 的完整业务示例，并补充原合同边界及全部 35 路由矩阵；
lifecycle 只用于根代理启动的短 TTL 隔离服务，验证完整下载租约与清理；
prepare-recovery / recovery 由根代理在同一持久目录真实关闭、重启前后调用。
请求、响应和本机隔离运行文件均保存在 ignored runtime，摘要显式 LF。
算法错误沿用冻结状态码，不把原 500 自动判作新增缺陷。
"""
import argparse
import copy
import hashlib
import http.client
import json
import re
import subprocess
import sys
import time
import traceback
import uuid
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
SPACE = '/api/v1/space'
TASKS = SPACE + '/visibility/tasks'
RECORDS = SPACE + '/visibility/task-results'
FILES = '/api/v1/ballistic/files'
PREVIEW = '/api/v1/ballistic/payloads/preview'
WINDOW = '/api/orbit/visibility/search'
# 显式活动路由来自正式 Controller。旧 com.tx Controller 被扫描排除，不能重复计数。
ROUTES = [
    ('GET', SPACE + '/health'), ('GET', SPACE + '/satellites'),
    ('GET', SPACE + '/satellites/page'), ('GET', SPACE + '/satellites/options'),
    ('GET', SPACE + '/satellites/{satelliteId}/payload'),
    ('PUT', SPACE + '/satellites/{satelliteId}/payload'),
    ('GET', SPACE + '/satellites/{satelliteId}/payload-profiles'),
    ('PUT', SPACE + '/satellites/{satelliteId}/payload-profiles'),
    ('GET', SPACE + '/stations'), ('GET', SPACE + '/defaults'),
    ('POST', SPACE + '/targets/query'), ('GET', SPACE + '/targets/belong-options'),
    ('POST', SPACE + '/downlink/plan'), ('POST', SPACE + '/downlink/validate-window'),
    ('POST', SPACE + '/visibility/plan'), ('POST', TASKS), ('GET', TASKS + '/{taskId}'),
    ('GET', TASKS + '/{taskId}/result'), ('DELETE', TASKS + '/{taskId}'),
    ('GET', RECORDS), ('GET', RECORDS + '/{id}'), ('PUT', RECORDS + '/{id}'),
    ('PUT', RECORDS + '/{id}/selection'), ('DELETE', RECORDS + '/{id}'),
    ('POST', FILES), ('POST', FILES + '/register-result'),
    ('GET', FILES + '/{fileId}/preview'), ('GET', FILES + '/{fileId}/download'),
    ('POST', PREVIEW), ('GET', '/api/orbit/health'), ('POST', WINDOW),
    ('GET', '/api/trajectory/health'), ('POST', '/api/trajectory/fit/package102'),
    ('POST', '/api/trajectory/simulate'), ('POST', '/api/trajectory/retime')]


def fixture(relative):
    """直接读取仓库来源固定的参考请求，不改变参考内容或创建新黄金。"""
    return json.loads((ROOT / relative).read_text(encoding='utf-8-sig'))


def standard(kind='SPACE'):
    return dict(payloadId='A01-' + kind, payloadName='全面验收载荷', payloadType=kind,
                minDistanceKm=0, maxDistanceKm=0, azimuthMinDeg=None, azimuthMaxDeg=None,
                elevationMinDeg=None, elevationMaxDeg=None,
                angleRateDegPerSec=-1 if kind == 'SPACE' else 180,
                sunAvoidHalfAngleDeg=0, startupAngleRateDegPerSec=-1)


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('w', encoding='utf-8', newline='\n') as output:
        output.write(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + '\n')


def match_route(method, path):
    """查询字符串不改变路由；先按完整模板匹配，防止 page/options 被认成动态主键。"""
    path = path.split('?', 1)[0]
    return next((verb + ' ' + template for verb, template in ROUTES
                 if verb == method and re.fullmatch(re.sub(r'\{\w+\}', '[^/]+', template), path)), None)


class Probe:
    """只负责原字节证据与断言。任何失败留下 FAIL，不吞掉连接错误或替换为成功。"""
    def __init__(self, args):
        self.args = args
        self.out = args.output_dir.resolve() / args.mode
        # 同模式定向复测保留每次原字节与首错快照；编号目录同时作为唯一阶段键，避免覆盖历史。
        reserved = json.loads(args.summary_path.read_text(encoding='utf-8')).get('phases', {}) if args.summary_path.exists() else {}
        run = 1
        while self.out.name in reserved or (self.out.exists() and any(self.out.iterdir())):
            run += 1
            self.out = args.output_dir.resolve() / ('%s-%02d' % (args.mode, run))
        self.phase_name = self.out.name
        self.out.mkdir(parents=True, exist_ok=True)
        self.events, self.checks, self.failures = [], [], []

    def http(self, method, path, name, body=None, expected=200, content_type='application/json'):
        payload = body if isinstance(body, bytes) else None if body is None else json.dumps(
            body, ensure_ascii=False, allow_nan=False).encode('utf-8')
        ordinal = len(self.events) + 1
        prefix = '%04d-%s' % (ordinal, name)
        if payload is not None:
            (self.out / (prefix + '-request.bin')).write_bytes(payload)
        event = dict(name=name, method=method, path=path, expected=expected,
                     route=match_route(method, path), phase='extended-' + self.phase_name)
        started = time.monotonic()
        try:
            try:
                response = urlopen(Request(self.args.base_url + path, payload,
                                          {'Content-Type': content_type} if payload is not None else {},
                                          method=method), timeout=180)
            except HTTPError as error:
                response = error
            with response:
                raw = response.read()
                event.update(status=response.status, bytes=len(raw), headers=dict(response.headers),
                             sha256=hashlib.sha256(raw).hexdigest())
            (self.out / (prefix + '-response.bin')).write_bytes(raw)
            event['passed'] = event['status'] == expected
            assert event['passed'], (name, event['status'], expected, raw[:500])
            return raw
        except Exception as error:
            event.update(passed=False, failure=str(error))
            raise
        finally:
            event['elapsedMs'] = (time.monotonic() - started) * 1000
            self.events.append(event)
            print(name, event.get('status', 'CONNECTION_ERROR'), flush=True)

    def data(self, method, path, name, body=None, expected=200):
        raw = self.http(method, path, name, body, expected)
        return json.loads(raw) if raw else None

    def check(self, name, function):
        """独立边界失败不阻断其它边界；有依赖的整个业务组作为一个检查，不伪造后续数据。"""
        before = len(self.events)
        try:
            function()
            self.checks.append(dict(name=name, status='PASS', requests=len(self.events) - before))
        except Exception as error:
            failure = dict(name=name, status='FAIL', requests=len(self.events) - before,
                           error=str(error), traceback=traceback.format_exc())
            self.checks.append(failure)
            self.failures.append(failure)
            print('FAIL', name, str(error), flush=True)

    def upload(self, binary, name, filename='全面验收.csv', expected=201):
        boundary = 'A01-' + uuid.uuid4().hex
        body = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="'
                + filename + '"\r\nContent-Type: text/csv\r\n\r\n').encode('utf-8')
        body += binary + ('\r\n--' + boundary + '--\r\n').encode('ascii')
        return json.loads(self.http('POST', FILES, name, body, expected,
                                    'multipart/form-data; boundary=' + boundary))

    def terminal(self, task_id, name):
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            status = self.data('GET', TASKS + '/' + task_id, name)
            if status['status'] in ('SUCCEEDED', 'FAILED', 'CANCELLED'):
                return status
            time.sleep(0.2)
        raise AssertionError('任务终态等待超过120秒')

    def record(self, task_id, expected):
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            page = self.data('GET', RECORDS + '?keyword=' + task_id, 'persist-' + expected)
            if page['records'] and page['records'][0]['status'] == expected:
                return page['records'][0]
            time.sleep(0.1)
        raise AssertionError('持久记录终态未出现：' + expected)


def payloads(p):
    """原 0/-1/null 哨兵完整保留，分别验证 preview 和持久化保存共用的领域合同。"""
    for kind in ('SPACE', 'GROUND'):
        for rate in (0, 1, 180):
            for startup in (None, -1, 0, 1):
                body = dict(standard(kind), angleRateDegPerSec=rate, startupAngleRateDegPerSec=startup,
                            azimuthMinDeg=0, azimuthMaxDeg=360,
                            elevationMinDeg=-90 if kind == 'SPACE' else -91, elevationMaxDeg=90)
                def echo(body=body):
                    assert p.data('POST', PREVIEW, 'valid-payload', body) == body
                p.check('payload-%s-rate%s-startup%s' % (kind, rate, startup), echo)
    for field in ('minDistanceKm', 'maxDistanceKm', 'angleRateDegPerSec', 'sunAvoidHalfAngleDeg'):
        for absent in (False, True):
            body = dict(standard())
            if absent:
                del body[field]
            else:
                body[field] = None
            p.check('payload-required-%s-%s' % (field, absent),
                    lambda body=body: p.data('POST', PREVIEW, 'required-400', body, 400))
    invalids = [dict(minDistanceKm=-1), dict(minDistanceKm=2, maxDistanceKm=1),
                dict(maxDistanceKm=-1), dict(maxDistanceKm=1e308), dict(angleRateDegPerSec=-2),
                dict(startupAngleRateDegPerSec=-2), dict(sunAvoidHalfAngleDeg=-1),
                dict(sunAvoidHalfAngleDeg=181), dict(azimuthMinDeg=0),
                dict(azimuthMinDeg=-1, azimuthMaxDeg=2), dict(azimuthMinDeg=10, azimuthMaxDeg=9),
                dict(azimuthMinDeg=0, azimuthMaxDeg=361), dict(elevationMinDeg=-91, elevationMaxDeg=90),
                dict(elevationMinDeg=0), dict(elevationMinDeg=0, elevationMaxDeg=91),
                dict(elevationMinDeg=1, elevationMaxDeg=1), dict(payloadType='GROUND', angleRateDegPerSec=-1),
                dict(payloadType='GROUND', elevationMinDeg=-91.1, elevationMaxDeg=0),
                dict(payloadType='UNKNOWN'), dict(minDistanceKm='非法字符串'), dict(azimuthMinDeg=[])]
    for index, change in enumerate(invalids):
        p.check('payload-invalid-%02d' % index,
                lambda change=change: p.data('POST', PREVIEW, 'invalid-payload-400', dict(standard(), **change), 400))
    def normalized_type():
        echo = p.data('POST', PREVIEW, 'type-trim-case', dict(standard(), payloadType=' space '))
        assert echo == standard()
    p.check('payload-type-trim-case', normalized_type)
    p.check('payload-json-syntax', lambda: p.http('POST', PREVIEW, 'malformed-400', b'{', 400))
    for kind in ('SPACE', 'GROUND'):
        def persisted(kind=kind):
            body = dict(standard(kind), angleRateDegPerSec=0, startupAngleRateDegPerSec=None)
            body.pop('payloadId'); body.pop('payloadName')
            saved = p.data('PUT', SPACE + '/satellites/1/payload-profiles', 'persist-' + kind, body)
            assert saved['activePayloadType'] == kind
            actual = next(item for item in saved['profiles'] if item['payloadType'] == kind)
            for key, value in body.items():
                assert actual.get(key) == value, (key, actual, body)
            assert p.data('GET', SPACE + '/satellites/1/payload-profiles', 'read-' + kind) == saved
        p.check('persist-full-profile-' + kind, persisted)
    # 恢复 SPACE，避免随后原规划示例受活动类型影响。
    body = standard(); body.pop('payloadId'); body.pop('payloadName')
    p.check('profile-restore', lambda: p.data('PUT', SPACE + '/satellites/1/payload-profiles', 'restore-space', body))
    p.check('profile-type-invalid', lambda: p.data('PUT', SPACE + '/satellites/1/payload-profiles', 'bad-type',
                                                 dict(body, payloadType='UNKNOWN'), 400))
    p.check('legacy-distance-unit-mixed', lambda: p.data('PUT', SPACE + '/satellites/1/payload', 'mixed-unit',
                                                        dict(body, minDistanceM=1), 400))


def catalog_boundaries(p):
    for path in (SPACE + '/satellites/page', RECORDS):
        for query in ('pageNumber=0', 'pageSize=0', 'pageSize=201', 'pageNumber=-1'):
            p.check('page-invalid-' + path.split('/')[-1] + '-' + query,
                    lambda path=path, query=query: p.data('GET', path + '?' + query, 'page-invalid', expected=400))
        def empty_page(path=path):
            result = p.data('GET', path + '?pageNumber=999999&pageSize=200', 'empty-last-page')
            assert result['records'] == [] and result['pageSize'] == 200
        p.check('page-upper-empty-' + path, empty_page)
    p.check('record-status-invalid', lambda: p.data('GET', RECORDS + '?status=INVALID', 'invalid-status', expected=400))
    p.check('target-regex-invalid', lambda: p.data('POST', SPACE + '/targets/query', 'bad-regex',
                                                  dict(excludeNameRegex='['), 400))
    for suffix in ('payload', 'payload-profiles'):
        p.check('satellite-missing-' + suffix, lambda suffix=suffix: p.data(
            'GET', SPACE + '/satellites/999999/' + suffix, 'missing-satellite', expected=400))
    for method, suffix, body in [('GET', '', None), ('PUT', '', dict(remark='不存在')),
                                 ('PUT', '/selection', dict(selections=[{}])), ('DELETE', '', None)]:
        p.check('record-missing-' + method + suffix, lambda method=method, suffix=suffix, body=body:
                p.http(method, RECORDS + '/999999999' + suffix, 'unknown-record', body, 404))
    for method, suffix in [('GET', '/result'), ('DELETE', '')]:
        p.check('task-missing-' + method, lambda method=method, suffix=suffix:
                p.http(method, TASKS + '/A01-unknown' + suffix, 'unknown-task', expected=404))


def asynchronous(p):
    """失败、取消与成功是三个独立真实任务；数据库回调与状态接口存在短时间先后差异。"""
    plan = fixture('test-data/baseline/space/visibility-request.json')
    p.data('POST', TASKS, 'instant-invalid-original-500', dict(plan, startTime='INVALID-UTC'), 500)
    reverse = dict(plan, endTime='2026-07-01T09:00:00Z')
    p.data('POST', SPACE + '/visibility/plan', 'reverse-sync-400', reverse, 400)
    task = p.data('POST', TASKS, 'reverse-async-submit', reverse)['taskId']
    assert p.terminal(task, 'failed-poll')['status'] == 'FAILED'
    p.data('GET', TASKS + '/' + task + '/result', 'failed-result-409', expected=409)
    p.record(task, 'FAILED')
    long_plan = dict(plan, endTime='2026-07-03T10:00:00Z', coarseStepSec=1, fineStepSec=1)
    task = p.data('POST', TASKS, 'long-async-submit', long_plan)['taskId']
    before = p.data('GET', TASKS + '/' + task, 'status-before-cancel')
    assert before['status'] in ('PENDING', 'RUNNING')
    p.data('GET', TASKS + '/' + task + '/result', 'pending-result-409', expected=409)
    p.data('DELETE', TASKS + '/' + task, 'cancel-real-active')
    assert p.terminal(task, 'cancel-poll')['status'] == 'CANCELLED'
    p.record(task, 'CANCELLED')
    task = p.data('POST', TASKS, 'after-failure-recovery-submit', plan)['taskId']
    assert p.terminal(task, 'recover-poll')['status'] == 'SUCCEEDED'
    result = p.data('GET', TASKS + '/' + task + '/result', 'recover-result')
    assert result == p.data('POST', SPACE + '/visibility/plan', 'recover-sync', plan)
    assert p.data('DELETE', TASKS + '/' + task, 'cancel-completed')['status'] == 'SUCCEEDED'
    row = p.record(task, 'SUCCEEDED')
    path = RECORDS + '/' + str(row['id'])
    p.data('PUT', path, 'remark-513-rejected', dict(remark='中' * 513), 400)
    assert p.data('PUT', path, 'remark-512-boundary', dict(remark='中' * 512))['remark'] == '中' * 512
    def clear_remark():
        assert p.data('PUT', path, 'remark-null-clear', dict(remark=None))['remark'] is None
    p.check('remark-null-clear', clear_remark)
    p.data('PUT', path + '/selection', 'selection-null-invalid', dict(selections=None), 400)
    p.data('PUT', path + '/selection', 'selection-empty-invalid', dict(selections=[]), 400)
    receipt = p.data('PUT', path + '/selection', 'selection-real-result-valid', dict(selections=[result['results'][0]]))
    assert receipt['received'] and receipt['selectionCount'] == 1
    detail = p.data('GET', path, 'record-preserved-detail')
    assert json.loads(detail['resultJson']) == result
    selection = json.loads(detail['selectionJson'])
    assert selection['selections'] == [result['results'][0]]
    write_json(p.args.output_dir / 'recovery-state.json', dict(taskId=task, recordId=row['id'], result=result, selection=selection))


def files(p):
    """中文名、CSV 引号/嵌入换行/尾空列和分页边界，下载始终按原字节比较。"""
    binary = '名称,值,尾列\r\n"甲,乙","第一行\n第二行",\r\n丙,2,\r\n'.encode('utf-8')
    ref = p.upload(binary, 'upload-quoted-chinese', '弹道 示例.csv')
    assert ref['originalName'] == '弹道 示例.csv' and ref['sizeBytes'] == len(binary)
    path = FILES + '/' + ref['fileId']
    assert p.http('GET', path + '/download', 'chinese-download') == binary
    headers = {key.lower(): value for key, value in p.events[-1]['headers'].items()}
    assert headers['x-content-type-options'] == 'nosniff'
    assert "filename*=UTF-8''" in headers['content-disposition']
    assert int(headers['content-length']) == len(binary)
    view = p.data('GET', path + '/preview?page=1&limit=1', 'quoted-first-page')
    assert view['header'] == ['名称', '值', '尾列']
    assert view['rows'] == [['甲,乙', '第一行\n第二行', '']] and view['totalRows'] == 2
    assert p.data('GET', path + '/preview?page=2&limit=1', 'quoted-second-page')['rows'] == [['丙', '2', '']]
    assert p.data('GET', path + '/preview?page=999&limit=500', 'quoted-last-empty')['rows'] == []
    for query in ('page=0', 'limit=0', 'limit=501'):
        p.data('GET', path + '/preview?' + query, 'file-page-invalid', expected=400)
    p.data('GET', FILES + '/unknown/download', 'unknown-download-404', expected=404)
    p.data('POST', FILES + '/register-result', 'register-empty-path', dict(csvPath=''), 400)
    p.data('POST', FILES + '/register-result', 'register-upload-reject', dict(csvPath=ref['serverPath']), 400)
    p.data('POST', FILES + '/register-result', 'register-source-outside-reject',
           dict(csvPath=str(ROOT / 'test-data/reference/ballistic/trajectory-0.1s-standard.csv')), 400)
    before = set((p.args.storage_root / 'uploads').glob('*')) if p.args.storage_root else None
    # 默认 64 MiB+1，Servlet 与存储层共同限额；响应原字节和超限后上传目录均实际观察。
    p.upload(b'x' * (p.args.max_bytes + 1), 'upload-real-limit-plus-one', 'oversize.csv', 413)
    if before is not None:
        assert set((p.args.storage_root / 'uploads').glob('*')) == before, '超限上传遗留实体'
    p.upload(b'a\n1\n', 'upload-after-limit-recovery')


def window(p):
    """同一真实 CSV、两个独立发射历元、两星身份与三可见载荷；点开关只改变点数组。"""
    conditions = fixture('test-data/baseline/ballistic/conditions.json')
    body = copy.deepcopy(next(item['request'] for item in conditions if item['name'] == 'visibility-http-ground'))
    body['ballistics'][0]['csvPath'] = str(ROOT / 'test-data/reference/ballistic/trajectory-0.1s-standard.csv')
    body['ballistics'][1]['csvPath'] = body['ballistics'][0]['csvPath']
    body['satellites'][1] = dict(copy.deepcopy(body['satellites'][0]), satelliteId='SAT-B', name='SAT-B')
    hidden = dict(standard(), payloadId='HIDDEN', minDistanceKm=1e9)
    for satellite in body['satellites']:
        satellite['payloads'] = [standard(), standard('GROUND'),
                                dict(payloadId='LEGACY', payloadName='旧载荷', payloadType='对空跟踪', sunExclusionAngleDeg=0), hidden]
    results = []
    for output in (0, 1):
        body['trajectoryPointOutput'] = output
        result = p.data('POST', WINDOW, 'mixed-two-target-two-satellite-points' + str(output), body)
        assert len(result['allResults']) == 3 and len(result['best']['targets']) == 2
        assert result['best']['visibleArcCount'] > 0
        for offset in result['allResults']:
            assert len(offset['visibleSatellites']) == 2
            for satellite in offset['visibleSatellites']:
                assert {item['payloadId'] for item in satellite['payloadResults']} == {'A01-SPACE', 'A01-GROUND', 'LEGACY'}
                for payload in satellite['payloadResults']:
                    assert len(payload['targets']) == 2
                    for target in payload['targets']:
                        for arc in target['visibleArcs']:
                            assert bool(arc['trajectoryPoints']) == bool(output)
                            assert arc['payloadId'] == payload['payloadId'] and arc['payloadType'] == payload['payloadType']
        results.append(result)
    def without_points(value):
        if isinstance(value, dict):
            return {key: [] if key == 'trajectoryPoints' else without_points(item) for key, item in value.items()}
        if isinstance(value, list):
            return [without_points(item) for item in value]
        return value
    assert without_points(results[0]) == without_points(results[1])
    body['trajectoryPointOutput'] = 0
    for startup in (None, 0, 1):
        for satellite in body['satellites']:
            for payload in satellite['payloads']:
                if payload['payloadType'] in ('SPACE', 'GROUND'):
                    payload['startupAngleRateDegPerSec'] = startup
        assert p.data('POST', WINDOW, 'startup-metadata-' + str(startup), body) == results[0]
    for model in ('KEPLER', 'J2_ANALYTICAL', 'J2_NUMERICAL'):
        candidate = dict(copy.deepcopy(body), orbitModel=model)
        result = p.data('POST', WINDOW, 'model-' + model, candidate)
        assert result['best']['visibleArcCount'] > 0 and len(result['allResults']) == 3
    for satellite in body['satellites']:
        satellite['payloads'] = [hidden]
    assert p.data('POST', WINDOW, 'empty-real-distance', body)['best']['visibleSatellites'] == []
    invalid = dict(body, stepSizeSec=0)
    assert set(p.data('POST', WINDOW, 'window-bad-step', invalid, 400)) == {'error'}
    body['satellites'][0]['payloads'][0] = dict(standard(), minDistanceKm=-1)
    assert set(p.data('POST', WINDOW, 'window-bad-standard', body, 400)) == {'error'}


def trajectory_errors(p):
    cases = fixture('test-data/baseline/ballistic/conditions.json')
    for name, route, field in [('fit', '/api/trajectory/fit/package102', 'interpolationStepSec'),
                               ('simulate', '/api/trajectory/simulate', 'outputPointDurationSec'),
                               ('retime', '/api/trajectory/retime', 'launchTimeUtc')]:
        body = copy.deepcopy(next(item['request'] for item in cases if item['name'] == name))
        body.pop('outputDir', None)
        p.data('POST', route, name + '-empty-original-400', {}, 400)
        p.data('POST', route, name + '-type-original-500', dict(body, **{field: {}}), 500)
    simulation = copy.deepcopy(next(item['request'] for item in cases if item['name'] == 'simulate'))
    simulation.pop('outputDir', None)
    # 无分隔符文本走 Double.parseDouble，NumberFormatException 属 IllegalArgumentException，原合同400。
    p.data('POST', '/api/trajectory/simulate', 'simulation-time-original-400',
           dict(simulation, launchTimeUtc='INVALID'), 400)
    # 半径错误在结果作用域已分配后发生，真实比较目录及所有权标记验证失败回滚，不推断HTTP400即可证明。
    before_results = set((p.args.storage_root / 'results').glob('*')) if p.args.storage_root else None
    before_markers = set((p.args.storage_root / 'metadata').glob('*.workspace')) if p.args.storage_root else None
    p.data('POST', '/api/trajectory/simulate', 'simulation-radius-400',
           dict(simulation, launchPointX=0, launchPointY=0, launchPointZ=0), 400)
    if before_results is not None:
        assert set((p.args.storage_root / 'results').glob('*')) == before_results, '失败模拟遗留独占结果目录'
        assert set((p.args.storage_root / 'metadata').glob('*.workspace')) == before_markers, '失败模拟遗留所有权标记'
    p.data('POST', '/api/trajectory/simulate', 'simulation-reversed-400',
           dict(simulation, impactTimeUtc='2026-06-04 03:59:59.000'), 400)
    outputs, binaries = [], []
    for slash in (False, True):
        request = dict(simulation, outputPointDurationSec=60)
        if slash:
            for key in ('launchTimeUtc', 'impactTimeUtc', 'taskStartTime', 'taskEndTime'):
                request[key] = request[key].replace('-', '/')
        result = p.data('POST', '/api/trajectory/simulate', 'two-utc-format-' + str(slash), request)
        reference = p.data('POST', FILES + '/register-result', 'utc-result-register-' + str(slash),
                           dict(csvPath=result['files'][0]['csvPath']), 201)
        binaries.append(p.http('GET', FILES + '/' + reference['fileId'] + '/download', 'utc-result-download-' + str(slash)))
        normalized = copy.deepcopy(result)
        for file in normalized['files']:
            file['csvPath'] = '<GENERATED>'
            # 原 warning 回显调用者的 UTC 字符串，明确只把格式别名的日期分隔符映射后比较。
            file['warnings'] = [warning.replace('2026/06/04', '2026-06-04') for warning in file['warnings']]
        outputs.append(normalized)
    assert outputs[0] == outputs[1], 'UTC横杠和斜杠格式改变原计算结果'
    assert binaries[0] == binaries[1], 'UTC格式别名改变原CSV字节'


def lifecycle(p):
    """真实 socket 下载未消费时让服务线程持有租约；观察跨 TTL 实体仍在，完整消费后再等回收。

    只接受本次隔离目录与明确短 TTL，避免默认 24h 服务上无意义等待。未写存储时间或元数据，
    过期由服务自己的调度器发生。本机 socket 回压需要文件明显大于双方网络缓冲。
    """
    assert p.args.storage_root and p.args.ttl_ms and p.args.ttl_ms <= 30000
    # 这两个目录由本轮隔离服务独占；只观察名字集合，不改时间戳、登记内容或实体。
    # 正常业务失败在已分配的ResultWorkspace中退出，必须回滚独占结果目录及所有权标记。
    cases = fixture('test-data/baseline/ballistic/conditions.json')
    failed_simulation = copy.deepcopy(next(item['request'] for item in cases if item['name'] == 'simulate'))
    failed_simulation.pop('outputDir', None)
    failed_simulation.update(launchPointX=0, launchPointY=0, launchPointZ=0)
    before_results = set((p.args.storage_root / 'results').glob('*'))
    before_markers = set((p.args.storage_root / 'metadata').glob('*.workspace'))
    p.data('POST', '/api/trajectory/simulate', 'lifecycle-failed-workspace-rollback', failed_simulation, 400)
    assert set((p.args.storage_root / 'results').glob('*')) == before_results
    assert set((p.args.storage_root / 'metadata').glob('*.workspace')) == before_markers
    binary = b'a,b\n' + b'1,2\n' * (4 * 1024 * 1024)
    assert len(binary) <= p.args.max_bytes
    ref = p.upload(binary, 'lease-upload-large')
    address = urlsplit(p.args.base_url)
    connection = http.client.HTTPConnection(address.hostname, address.port, timeout=180)
    connection.request('GET', FILES + '/' + ref['fileId'] + '/download')
    response = connection.getresponse()
    assert response.status == 200
    time.sleep((p.args.ttl_ms + 2 * p.args.cleanup_ms) / 1000)
    assert Path(ref['serverPath']).exists(), '下载仍持有租约时实体被TTL删除'
    raw = response.read()
    connection.close()
    assert raw == binary
    (p.out / 'lease-download-response.bin').write_bytes(raw)
    p.events.append(dict(name='lease-full-download', method='GET', path=FILES + '/' + ref['fileId'] + '/download',
                         route='GET ' + FILES + '/{fileId}/download', status=200, expected=200,
                         bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest(), passed=True, phase='lifecycle',
                         leaseRetainedAcrossTtl=True))
    time.sleep((p.args.ttl_ms + 2 * p.args.cleanup_ms) / 1000)
    assert not Path(ref['serverPath']).exists()
    p.data('GET', FILES + '/' + ref['fileId'] + '/download', 'expired-download-404', expected=404)
    assert not (p.args.storage_root / 'metadata' / (ref['fileId'] + '.json')).exists()
    p.upload(b'a\n1\n', 'after-expiry-recovery')


def prepare_recovery(p):
    asynchronous(p)
    state = json.loads((p.args.output_dir / 'recovery-state.json').read_text(encoding='utf-8'))
    binary = b'a,b\n1,2\n'
    ref = p.upload(binary, 'persistent-file-before-restart')
    state.update(fileReference=ref, fileSha256=hashlib.sha256(binary).hexdigest())
    write_json(p.args.output_dir / 'recovery-state.json', state)


def prepare_recovery_minimal(p):
    """在当前服务真实提交新任务和上传文件，给根代理的下一次关闭重启生成当前数据库专属标记。

    不引用旧 fresh ID，不修改载荷或触发长任务；即使浏览器已切换载荷导致空窗口，
    仍验证这次原结果的持久一致。共同观测及非空窗口由完整流程证据单独承担。
    """
    plan = fixture('test-data/baseline/space/visibility-request.json')
    task = p.data('POST', TASKS, 'prepare-current-task', plan)['taskId']
    assert p.terminal(task, 'prepare-current-poll')['status'] == 'SUCCEEDED'
    result = p.data('GET', TASKS + '/' + task + '/result', 'prepare-current-result')
    row = p.record(task, 'SUCCEEDED')
    detail = p.data('GET', RECORDS + '/' + str(row['id']), 'prepare-current-detail')
    assert json.loads(detail['resultJson']) == result
    binary = b'a,b\n1,2\n'
    ref = p.upload(binary, 'prepare-current-persistent-file')
    write_json(p.args.output_dir / 'recovery-state.json', dict(taskId=task, recordId=row['id'], result=result,
               selection=json.loads(detail['selectionJson']) if detail.get('selectionJson') else None,
               fileReference=ref, fileSha256=hashlib.sha256(binary).hexdigest()))


def supplement(p):
    """首轮失败依赖之后的遗漏用例定向补测，不重跑已通过的约200次业务验收。"""
    previous = json.loads(p.args.summary_path.read_text(encoding='utf-8'))
    event = next(item for item in previous['phases']['full']['events'] if item['name'] == 'recover-result')
    task = event['path'].split('/')[-2]
    result = p.data('GET', event['path'], 'supplement-success-result')
    row = p.record(task, 'SUCCEEDED')
    path = RECORDS + '/' + str(row['id'])
    p.data('PUT', path + '/selection', 'selection-null-invalid', dict(selections=None), 400)
    p.data('PUT', path + '/selection', 'selection-empty-invalid', dict(selections=[]), 400)
    selected = dict(selections=[result['results'][0]])
    receipt = p.data('PUT', path + '/selection', 'selection-real-result-valid', selected)
    assert receipt['received'] and receipt['selectionCount'] == 1
    detail = p.data('GET', path, 'record-selection-detail')
    assert json.loads(detail['resultJson']) == result
    selection = json.loads(detail['selectionJson'])
    assert selection['selections'] == selected['selections']
    binary = b'a,b\n1,2\n'
    ref = p.upload(binary, 'persistent-file-before-restart')
    write_json(p.args.output_dir / 'recovery-state.json', dict(taskId=task, recordId=row['id'], result=result,
               selection=selection, fileReference=ref, fileSha256=hashlib.sha256(binary).hexdigest()))
    trajectory_errors(p)


def recovery(p):
    """根代理真正关闭重启后执行。任务内存缓存不会恢复，结果记录和文件登记应持久恢复。"""
    state = json.loads((p.args.output_dir / 'recovery-state.json').read_text(encoding='utf-8'))
    record = p.data('GET', RECORDS + '/' + str(state['recordId']), 'record-after-real-restart')
    assert record['taskId'] == state['taskId'] and record['status'] == 'SUCCEEDED'
    assert json.loads(record['resultJson']) == state['result']
    assert (json.loads(record['selectionJson']) if record.get('selectionJson') else None) == state['selection']
    p.http('GET', TASKS + '/' + state['taskId'], 'volatile-task-after-restart-404', expected=404)
    if 'fileReference' in state:
        raw = p.http('GET', FILES + '/' + state['fileReference']['fileId'] + '/download', 'file-after-real-restart')
        assert hashlib.sha256(raw).hexdigest() == state['fileSha256']


def remark_retest(p):
    """修复最终JAR真实socket验收：仅备注改变，所有其他明细字段逐结构不变；未知ID的null请求仍404。"""
    state = json.loads((p.args.output_dir / 'recovery-state.json').read_text(encoding='utf-8'))
    path = RECORDS + '/' + str(state['recordId'])
    initial = p.data('PUT', path, 'remark-retest-set', dict(remark='修复后清空边界'))
    expected = copy.deepcopy(initial); expected['remark'] = None
    assert p.data('PUT', path, 'remark-retest-null-clear', dict(remark=None)) == expected
    assert p.data('GET', path, 'remark-retest-null-read') == expected
    expected['remark'] = ''
    assert p.data('PUT', path, 'remark-retest-empty-string', dict(remark='')) == expected
    assert p.data('GET', path, 'remark-retest-empty-read') == expected
    # 主键数据库列为BIGINT，合法未知数字检验404；非数字串会走冻结类型绑定/SQL错误500，不能拿来反证清空回归。
    p.http('PUT', RECORDS + '/9223372036854775806', 'unknown-numeric-null-remark-404', dict(remark=None), 404)


def finish_prepare_evidence(p):
    """准备阶段已收到成功HTTP但工具处理空字段失败时，只用SHA核验后的原响应完成标记，不再发请求。"""
    previous = json.loads(p.args.summary_path.read_text(encoding='utf-8'))
    name, phase = next((name, phase) for name, phase in previous['phases'].items()
                       if name.startswith('prepare-recovery-minimal') and phase['events'])
    def original(label):
        index, event = next((index, event) for index, event in enumerate(phase['events']) if event['name'] == label)
        raw = (p.args.output_dir / name / ('%04d-%s-response.bin' % (index + 1, label))).read_bytes()
        assert event['passed'] and hashlib.sha256(raw).hexdigest() == event['sha256']
        return json.loads(raw)
    result = original('prepare-current-result')
    detail = original('prepare-current-detail')
    ref = original('prepare-current-persistent-file')
    assert json.loads(detail['resultJson']) == result and detail['status'] == 'SUCCEEDED'
    state = dict(taskId=detail['taskId'], recordId=detail['id'], result=result,
                 selection=json.loads(detail['selectionJson']) if detail.get('selectionJson') else None,
                 fileReference=ref, fileSha256=hashlib.sha256(b'a,b\n1,2\n').hexdigest())
    write_json(p.args.output_dir / 'recovery-state.json', state)


def merge_sources(paths):
    """只合并现存真实阶段；同阶段完整JSON指纹去重，不重复统计累计快照中的历史事件。

    两次相同路由的实际请求含不同日期、耗时和响应SHA，仍是不同调用，必须保留。
    工具在任何HTTP之前失败的阶段计0请求，但仍保留失败检查和原traceback。
    """
    phases, provenance, seen = {}, [], set()
    for path in paths:
        binary = path.read_bytes()
        document = json.loads(binary.decode('utf-8-sig'))
        provenance.append(dict(path=str(path.resolve()), sha256=hashlib.sha256(binary).hexdigest()))
        for name, phase in document.get('phases', {}).items():
            fingerprint = hashlib.sha256(json.dumps(phase, ensure_ascii=False, sort_keys=True).encode('utf-8')).hexdigest()
            if fingerprint in seen:
                continue
            seen.add(fingerprint)
            key, sequence = name, 1
            while key in phases:
                sequence += 1
                key = '%s-%02d' % (name, sequence)
            phases[key] = copy.deepcopy(phase)
    return phases, provenance


def audit_response_bytes(root, events):
    """只读核验现存原响应字节。多次同内容响应允许对应同SHA文件，不据文件数冒充请求数。

    旧工具曾覆写相同模式目录；只在确切SHA仍可定位时认定字节证据完整，
    无法定位的事件逐项列出，不根据JSON摘要重建假响应。
    """
    hashes, file_count = set(), 0
    for path in root.rglob('*response.bin'):
        hashes.add(hashlib.sha256(path.read_bytes()).hexdigest())
        file_count += 1
    missing = [dict(name=event['name'], path=event['path'], sha256=event.get('sha256'))
               for event in events if event.get('sha256') not in hashes]
    return dict(responseBinFiles=file_count, uniqueByteHashes=len(hashes),
                matchedEventHashes=len(events) - len(missing), missingEventHashes=missing)


def summary(p, baseline=None):
    existing = json.loads(p.args.summary_path.read_text(encoding='utf-8')) if p.args.summary_path.exists() else {}
    if p.args.summary_source:
        sources = [path.resolve() for path in p.args.summary_source]
        # 目标本身若作为输入，先保留确切原字节，后续写总汇总不会丢失源快照。
        if p.args.summary_path.resolve() in sources:
            original = p.out / 'input-current-summary.json'
            original.write_bytes(p.args.summary_path.read_bytes())
            sources = [original if path == p.args.summary_path.resolve() else path for path in sources]
        phases, provenance = merge_sources(sources)
    else:
        phases, provenance = existing.get('phases', {}), existing.get('phaseSources', [])
    events = (baseline or {}).get('events', []) + p.events
    for event in events:
        event.setdefault('route', match_route(event['method'], event['path']))
        event.setdefault('passed', True)
        event.setdefault('phase', 'original-example')
    if p.args.mode != 'merge-evidence':
        phases[p.phase_name] = dict(checks=p.checks, events=events, failures=p.failures,
                                   passed=not p.failures,
                                   baselineCsvComparisons=(baseline or {}).get('csvComparisons', []))
    all_events = [event for phase in phases.values() for event in phase['events']]
    matrix = []
    for method, path in ROUTES:
        route = method + ' ' + path
        found = [event for event in all_events if event['route'] == route]
        matrix.append(dict(route=route, requestCount=len(found), statuses=sorted(set(
            event['status'] for event in found if 'status' in event)),
            successCovered=any(event.get('passed') and 200 <= event.get('status', 0) < 300 for event in found),
            boundaryCovered=any(event.get('passed') and event.get('status', 0) >= 400 for event in found)))
    failures = copy.deepcopy([failure for phase in phases.values() for failure in phase['failures']])
    succeeded_modes = {name.rsplit('-', 1)[0] if re.search(r'-\d+$', name) else name
                       for name, phase in phases.items() if phase.get('passed')}
    corrected_full = 'full' in succeeded_modes
    # 首轮失败快照永久保留；汇总只增加处置，不把原500或工具误期望写成当时通过。
    for failure in failures:
        error = failure.get('error', '')
        if 'simulation-time-original-500' in error and ('supplement' in succeeded_modes or corrected_full):
            failure.update(disposition='TOOL_EXPECTATION_CORRECTED', resolvedBy='supplement',
                           explanation='INVALID走Double.parseDouble，原NumberFormatException属于IllegalArgumentException，合同400；首次误期望500已修正并定向通过。')
        elif 'remark-null-clear' in error and ('remark-retest' in succeeded_modes or corrected_full):
            failure.update(disposition='PRODUCT_DEFECT_FIXED_AND_RETESTED',
                           resolvedBy='remark-retest' if 'remark-retest' in succeeded_modes else 'full')
        elif 'is not in the subpath' in error:
            failure.update(disposition='TOOL_PATH_ERROR_CORRECTED',
                           explanation='相对输出路径在发HTTP之前触发Path.relative_to错误，阶段0请求；现已resolve输入并直接读取state。')
        elif 'unknown-null-remark-404' in error and any(
                event.get('name') == 'unknown-null-remark-404' and '/missing-a01-null' in event.get('path', '')
                for phase in phases.values() for event in phase['events']):
            failure.update(disposition='TOOL_INPUT_EXPECTATION_CORRECTED' if 'remark-retest' in succeeded_modes
                           else 'TOOL_INPUT_EXPECTATION_ERROR',
                           explanation='工具误用非数字missing-a01-null期待BIGINT主键404，真实原500响应保留；同次前5请求已证实完整字段清空成功，合法未知数字ID另行复测。')
        elif failure.get('name') == 'prepare-recovery-minimal' and error == "'selectionJson'" and 'finish-prepare-evidence' in succeeded_modes:
            failure.update(disposition='TOOL_NULL_FIELD_ERROR_CORRECTED', resolvedBy='finish-prepare-evidence',
                           explanation='新记录未选择时selectionJson空字段被HTTP省略，工具误用下标读取；已用.get处理，并从7份SHA验证原响应离线完成本次state，未重复提交请求。')
        else:
            failure['disposition'] = 'UNRESOLVED'
    unresolved = [failure for failure in failures if failure['disposition'] == 'UNRESOLVED']
    byte_audit = audit_response_bytes(p.args.output_dir, all_events)
    result = dict(passed=not unresolved and not byte_audit['missingEventHashes'], currentPhasePassed=not p.failures,
                  mode=p.args.mode, baseUrl=p.args.base_url, phases=phases,
                  activeRoutes=35, successCoveredRoutes=sum(item['successCovered'] for item in matrix),
                  totalRequests=len(all_events), assertedChecks=sum(len(phase['checks']) for phase in phases.values()),
                  routeMatrix=matrix, failures=failures, unresolvedFailures=unresolved, phaseSources=provenance,
                  responseByteAudit=byte_audit,
                  pendingLifecycle='lifecycle' not in succeeded_modes, pendingRealRestart='recovery' not in succeeded_modes,
                  pendingNumericUnknownNullIdVerification=any('unknown-null-remark-404' in failure.get('error', '')
                      for failure in failures) and 'remark-retest' not in succeeded_modes,
                  evidenceRoot=str(p.args.output_dir.resolve()),
                  sourceFixtures='test-data/reference、test-data/baseline；原例子仅重新运行，历史证据未覆写')
    write_json(p.out / 'summary.json', result)
    write_json(p.args.summary_path, result)
    print(json.dumps(dict(passed=result['passed'], totalRequests=result['totalRequests'],
                          successCoveredRoutes=result['successCoveredRoutes'],
                          assertedChecks=result['assertedChecks'], failures=len(failures)), ensure_ascii=False), flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:18140')
    parser.add_argument('--mode', choices=['full', 'supplement', 'lifecycle', 'prepare-recovery', 'prepare-recovery-minimal',
                                         'finish-prepare-evidence', 'recovery', 'remark-retest', 'merge-evidence', 'self-check'], default='full')
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'verification/runtime/A01/http-extended')
    parser.add_argument('--summary-path', type=Path, default=ROOT / 'verification/A01/http-extended-summary.json')
    parser.add_argument('--storage-root', type=Path)
    parser.add_argument('--max-bytes', type=int, default=67108864)
    parser.add_argument('--ttl-ms', type=int)
    parser.add_argument('--cleanup-ms', type=int, default=1000)
    parser.add_argument('--summary-source', type=Path, action='append', default=[])
    args = parser.parse_args()
    args.output_dir = args.output_dir.resolve()
    args.summary_path = args.summary_path.resolve()
    if args.mode == 'self-check':
        assert len(ROUTES) == len(set(ROUTES)) == 35
        for method, template in ROUTES:
            assert match_route(method, re.sub(r'\{\w+\}', 'example', template) + '?page=1') == method + ' ' + template
        assert match_route('GET', SPACE + '/satellites/page') == 'GET ' + SPACE + '/satellites/page'
        assert match_route('GET', '/unregistered') is None
        # 合并已有证据是离线操作，自检真实快照去重及不同阶段保留；不调用任何HTTP。
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'source.json'
            write_json(path, dict(phases=dict(full=dict(events=[dict(status=200)], checks=[], failures=[], passed=True))))
            phases, _ = merge_sources([path, path])
            assert len(phases) == 1
            other = Path(directory) / 'second-run.json'
            write_json(other, dict(phases=dict(full=dict(events=[dict(status=201)], checks=[], failures=[], passed=True))))
            phases, _ = merge_sources([path, other])
            assert len(phases) == 2 and phases['full-02']['events'][0]['status'] == 201
        print('PASS 35 unique route templates and exact matching; no HTTP executed')
        return
    p = Probe(args)
    baseline = None
    if args.mode == 'full':
        # 原例子不变，通过显式新输出目录重跑。返回码和 stdout/stderr 保留，不能继承旧68次结果。
        command = [sys.executable, str(ROOT / 'docs/examples/http_flow.py'), '--base-url', args.base_url,
                   '--output-dir', str(args.output_dir / 'original-example')]
        completed = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd=str(ROOT))
        (p.out / 'original-example-stdout.txt').write_bytes(completed.stdout)
        (p.out / 'original-example-stderr.txt').write_bytes(completed.stderr)
        write_json(p.out / 'original-example-command.json', dict(command=command, exitCode=completed.returncode))
        if completed.returncode:
            p.failures.append(dict(name='original-example', status='FAIL', exitCode=completed.returncode,
                                   error=completed.stderr.decode('utf-8', errors='replace')))
        else:
            baseline = json.loads((args.output_dir / 'original-example/summary.json').read_text(encoding='utf-8'))
            p.checks.append(dict(name='original-complete-example', status='PASS', requests=len(baseline['events'])))
        payloads(p)
        catalog_boundaries(p)
        p.check('async-failure-cancel-recovery-record-boundaries', lambda: asynchronous(p))
        p.check('files-quote-page-limit-rollback-recovery', lambda: files(p))
        p.check('multi-target-multi-satellite-multi-payload-model-points', lambda: window(p))
        p.check('trajectory-invalid-utc-formats-recovery', lambda: trajectory_errors(p))
    elif args.mode != 'merge-evidence':
        p.check(args.mode, lambda: {'supplement': supplement, 'lifecycle': lifecycle, 'prepare-recovery': prepare_recovery,
                                   'prepare-recovery-minimal': prepare_recovery_minimal,
                                   'finish-prepare-evidence': finish_prepare_evidence,
                                   'remark-retest': remark_retest,
                                   'recovery': recovery}[args.mode](p))
    result = summary(p, baseline)
    # 定向复测命令的退出码反映本阶段；总汇总仍明确保留未解决的首轮缺陷。
    if not result['currentPhasePassed']:
        sys.exit(1)


if __name__ == '__main__':
    main()
