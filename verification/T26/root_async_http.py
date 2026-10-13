"""根代理独立真实HTTP验收：异步取消、结果冲突、恢复及同步/异步同口径对照。

只向独立demo/H2发请求，保留原样响应；不用mock，不改算法或数值容差。
长请求采用同一真实观测星/目标和1秒采样，取消响应仅表示取消请求，终态另轮询确认。
"""
import copy
import hashlib
import json
import sys
import time
from decimal import Decimal
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError

ROOT = Path(__file__).resolve().parents[2]
SERVER_NAME = sys.argv[1] if len(sys.argv) > 1 else 'root-nio'
OUT = ROOT / 'verification/runtime/T26' / SERVER_NAME / 'async-probe-verified'
BASE = 'http://127.0.0.1:18176'
TASKS = '/api/v1/space/visibility/tasks'
OUT.mkdir(parents=True, exist_ok=True)
events = []


def http(method, path, label, body=None, expect=200):
    payload = None if body is None else json.dumps(body, ensure_ascii=False, allow_nan=False).encode('utf-8')
    if payload is not None:
        (OUT / (label + '-request.json')).write_bytes(payload)
    started = time.monotonic()
    try:
        response = urlopen(Request(BASE + path, payload, {'Content-Type': 'application/json'}, method=method), timeout=120)
    except HTTPError as error:
        response = error
    with response:
        raw = response.read()
        status = response.status
    (OUT / (label + '-response.json')).write_bytes(raw)
    events.append({'label': label, 'method': method, 'path': path, 'status': status,
                   'bytes': len(raw), 'elapsedSec': time.monotonic() - started,
                   'sha256': hashlib.sha256(raw).hexdigest()})
    assert status == expect, (label, status, raw[:1000])
    return json.loads(raw, parse_float=Decimal)


def wait_terminal(task_id, label):
    deadline = time.monotonic() + 100
    index = 0
    while time.monotonic() < deadline:
        status = http('GET', TASKS + '/' + task_id, label + '-' + str(index))
        print(json.dumps({'label': label, 'status': status['status'], 'completedPairs': status.get('completedPairs')}, ensure_ascii=False), flush=True)
        if status['status'] in ('SUCCEEDED', 'CANCELLED', 'FAILED'):
            return status
        index += 1
        time.sleep(0.25)
    raise AssertionError('任务未在验收时间内终结')


def main():
    request = json.loads((ROOT / 'verification/runtime/T20/migrated-actual/visibility-request.json').read_bytes())
    long_request = copy.deepcopy(request)
    long_request.update(endTime='2026-07-03T10:00:00Z', coarseStepSec=1.0, fineStepSec=1.0)
    submitted = http('POST', TASKS, 'long-submit', long_request)
    cancelled_id = submitted['taskId']
    before = http('GET', TASKS + '/' + cancelled_id, 'before-cancel')
    assert before['status'] in ('PENDING', 'RUNNING'), before
    cancel_response = http('DELETE', TASKS + '/' + cancelled_id, 'cancel')
    cancelled = wait_terminal(cancelled_id, 'cancel-poll')
    assert cancelled['status'] == 'CANCELLED', cancelled
    conflict = http('GET', TASKS + '/' + cancelled_id + '/result', 'cancelled-result', expect=409)
    assert 'CANCELLED' in conflict['error']

    malformed = copy.deepcopy(request)
    malformed['startTime'] = 'INVALID-UTC'
    # 原空间全局handler将Instant反序列化异常交给Exception通路，返回500；此项保留旧契约。
    # 文件/弹道专用400口径不能反推空间接口应改400。另测业务非法时间段的原400响应。
    http('POST', TASKS, 'invalid-utc-original-500', malformed, expect=500)
    reversed_range = copy.deepcopy(request)
    reversed_range['endTime'] = '2026-07-01T09:00:00Z'
    http('POST', '/api/v1/space/visibility/plan', 'invalid-range-sync-400', reversed_range, expect=400)
    failed_id = http('POST', TASKS, 'invalid-range-async-accepted', reversed_range)['taskId']
    failed = wait_terminal(failed_id, 'failed-poll')
    assert failed['status'] == 'FAILED' and '规划时间段或步长无效' in failed['errorMessage'], failed
    http('GET', TASKS + '/' + failed_id + '/result', 'failed-result', expect=409)
    recovered_id = http('POST', TASKS, 'recovered-submit', request)['taskId']
    recovered = wait_terminal(recovered_id, 'recovered-poll')
    assert recovered['status'] == 'SUCCEEDED', recovered
    asynchronous = http('GET', TASKS + '/' + recovered_id + '/result', 'async-result')
    synchronous = http('POST', '/api/v1/space/visibility/plan', 'sync-result', request)
    assert asynchronous == synchronous, '同请求异步与同步JSON存在差异'
    persisted = {}
    for label, identity, expected in [('cancelled', cancelled_id, 'CANCELLED'), ('failed', failed_id, 'FAILED'), ('recovered', recovered_id, 'SUCCEEDED')]:
        # terminal listener在状态更新后持久化；状态响应和记录读取是两个独立HTTP边界。
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            page = http('GET', '/api/v1/space/visibility/task-results?keyword=' + identity, label + '-records')
            rows = page.get('records', page.get('items', []))
            if rows and rows[0]['status'] == expected:
                persisted[label] = rows[0]
                break
            time.sleep(0.1)
        assert label in persisted, page
    service_jar = ROOT / 'verification/runtime/T26' / SERVER_NAME / 'server.jar'
    summary = {'candidateJarSha256': hashlib.sha256(service_jar.read_bytes()).hexdigest(),
               'cancelledTask': cancelled_id, 'statusBeforeCancel': before['status'], 'cancelResponseStatus': cancel_response['status'],
               'cancelledTerminal': cancelled, 'failedTask': failed_id, 'failedTerminal': failed,
               'recoveredTask': recovered_id, 'recoveredTerminal': recovered,
               'syncAsyncJsonExact': True, 'persistedTerminals': {key: value['status'] for key, value in persisted.items()},
               'events': events}
    destination = ROOT / 'verification/T26/root-async-http-summary.json'
    destination.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({key: value for key, value in summary.items() if key != 'events'}, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
