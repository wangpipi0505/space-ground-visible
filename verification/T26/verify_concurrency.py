"""真实HTTP并发/UTC/文件隔离与断开恢复；所有原始响应只写T26运行目录，不修改冻结黄金。"""
import argparse
import concurrent.futures as futures
import copy
import ctypes
from ctypes import wintypes
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import socket
import struct
import threading
import time
from urllib.error import HTTPError
from urllib.parse import urlparse
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
RUNTIME = ROOT / 'verification/runtime/T26/http'


def main():
    global RUNTIME
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-url', default='http://127.0.0.1:18126')
    parser.add_argument('--pid', type=int, required=True)
    parser.add_argument('--name', default='final-nio2')
    args = parser.parse_args()
    RUNTIME = RUNTIME / args.name
    RUNTIME.mkdir(parents=True, exist_ok=True)
    records, metrics = [], []
    done = threading.Event()

    # Windows进程working-set与句柄数，100ms采样；不是JVM heap，不主动GC。
    class Memory(ctypes.Structure):
        _fields_ = [('cb', wintypes.DWORD), ('PageFaultCount', wintypes.DWORD)] + [
            (key, ctypes.c_size_t) for key in ('PeakWorkingSetSize', 'WorkingSetSize',
            'QuotaPeakPagedPoolUsage', 'QuotaPagedPoolUsage', 'QuotaPeakNonPagedPoolUsage',
            'QuotaNonPagedPoolUsage', 'PagefileUsage', 'PeakPagefileUsage', 'PrivateUsage')]
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel.GetProcessHandleCount.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    psapi = ctypes.WinDLL('psapi', use_last_error=True)
    psapi.GetProcessMemoryInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(Memory), wintypes.DWORD]
    process = kernel.OpenProcess(0x0410, False, args.pid)
    assert process, ctypes.get_last_error()
    def sample():
        while not done.is_set():
            memory, handles = Memory(), wintypes.DWORD()
            memory.cb = ctypes.sizeof(memory)
            assert psapi.GetProcessMemoryInfo(process, ctypes.byref(memory), memory.cb)
            assert kernel.GetProcessHandleCount(process, ctypes.byref(handles))
            metrics.append({'time': time.time(), 'workingSetBytes': memory.WorkingSetSize,
                            'privateBytes': memory.PrivateUsage, 'handleCount': handles.value})
            done.wait(.1)
    sampler = threading.Thread(target=sample)
    sampler.start()

    def request(path, body=None, name='request', status=200, content_type='application/json'):
        if isinstance(body, dict):
            body = json.dumps(body, ensure_ascii=False, allow_nan=False).encode('utf-8')
        start = time.perf_counter()
        try:
            response = urlopen(Request(args.base_url + path, data=body,
                headers={'Content-Type': content_type}), timeout=180)
        except HTTPError as error:
            response = error
        with response:
            raw, actual = response.read(), response.status
        elapsed = time.perf_counter() - start
        (RUNTIME / (name + '.bin')).write_bytes(raw)
        records.append({'name': name, 'status': actual, 'bytes': len(raw), 'seconds': elapsed})
        assert actual == status, (name, actual, raw[:600])
        return raw

    def post(path, body, name, status=200):
        return json.loads(request(path, body, name, status), parse_float=Decimal)

    try:
        csv = (ROOT / 'test-data/reference/ballistic/trajectory-0.1s-standard.csv').read_bytes()
        boundary = 'T26-actual-multipart-boundary'
        multipart = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="trajectory.csv"\r\nContent-Type: text/csv\r\n\r\n').encode() + csv + ('\r\n--' + boundary + '--\r\n').encode()
        uploaded = json.loads(request('/api/v1/ballistic/files', multipart, 'upload', 201, 'multipart/form-data; boundary=' + boundary))
        assert uploaded['sizeBytes'] == len(csv)
        # 原重定时要求含ECEF列，使用冻结模拟输出作为第二个真实上传输入。
        retime_csv = (ROOT / 'test-data/baseline/ballistic/simulate/trajectory-0.csv').read_bytes()
        retime_multipart = multipart.replace(csv, retime_csv)
        retime_source = json.loads(request('/api/v1/ballistic/files', retime_multipart, 'retime-upload', 201, 'multipart/form-data; boundary=' + boundary))
        conditions = json.loads((ROOT / 'test-data/baseline/ballistic/conditions.json').read_text(encoding='utf-8'))
        original = next(c['request'] for c in conditions if c['name'] == 'visibility-http-ground')
        window_inputs = []
        for model in ('TWO_BODY', 'J2_SECULAR', 'J2_NUMERICAL'):
            for day in (1, 2):
                body = copy.deepcopy(original)
                body.update(orbitModel=model, trajectoryPointOutput=1)
                for index, target in enumerate(body['ballistics']):
                    target.update(csvPath=uploaded['serverPath'], baseLaunchUTC=f'2026/07/0{day} 07:57:{index * 10:02}.000')
                second = copy.deepcopy(body['satellites'][0]); second.update(satelliteId='SAT-B', name='SAT-B')
                body['satellites'][1] = second
                for satellite in body['satellites']:
                    satellite['epochUtc'] = f'2026/07/0{day} 10:00:00.000'
                    satellite['payloads'] = [
                        {'payloadId': kind, 'payloadName': kind, 'payloadType': kind, 'minDistanceKm': 0,
                         'maxDistanceKm': 0, 'angleRateDegPerSec': -1 if kind == 'SPACE' else 180,
                         'sunAvoidHalfAngleDeg': 0} for kind in ('SPACE', 'GROUND')]
                    satellite['payloads'].append({'payloadId': 'LEGACY', 'payloadType': '对空跟踪', 'sunExclusionAngleDeg': 0})
                window_inputs.append((model + '-day' + str(day), body))
        # 独立请求先串行获取同场景对照，再乱序并发；不放宽数值，不忽略数组顺序。
        expected = {name: post('/api/orbit/visibility/search', body, 'serial-' + name) for name, body in window_inputs}
        for name, body in window_inputs:
            (RUNTIME / (name + '-request.json')).write_text(json.dumps(body, ensure_ascii=False), encoding='utf-8')
        with futures.ThreadPoolExecutor(max_workers=6) as pool:
            pending = {pool.submit(post, '/api/orbit/visibility/search', body, 'parallel-' + name): name for name, body in reversed(window_inputs)}
            for result in futures.as_completed(pending):
                name = pending[result]
                assert result.result() == expected[name], '串行/并发完整Decimal与顺序差异:' + name
        for name, body in (window_inputs[2], window_inputs[0], window_inputs[2]):
            assert post('/api/orbit/visibility/search', body, 'reuse-' + name + '-' + str(len(records))) == expected[name]

        operations = []
        for operation in ('fit', 'simulate', 'retime'):
            for day in (1, 2):
                body = copy.deepcopy(next(c['request'] for c in conditions if c['operation'] == operation))
                body.pop('outputDir', None)
                # 同名任务并发，依靠服务拥有的独立目录隔离，不用人为不同任务名躲避碰撞。
                if operation == 'retime':
                    body['trajectoryCsvPath'] = retime_source['serverPath']
                    body['launchTimeUtc'] = f'2026-07-0{day} 07:57:00.000'
                elif day == 2:
                    raw = json.dumps(body).replace('2026-07-01', '2026-07-02').replace('2026-06-04', '2026-06-05')
                    body = json.loads(raw)
                operations.append((operation + '-day' + str(day), operation, body))
        def generate(item, prefix):
            name, operation, body = item
            response = post('/api/trajectory/' + ('fit/package102' if operation == 'fit' else operation), body, prefix + name)
            result_files = []
            for index, result in enumerate(response['files']):
                reference = post('/api/v1/ballistic/files/register-result', {'csvPath': result['csvPath'], 'taskName': body['taskName'], 'targetId': result['targetId']}, prefix + name + '-register' + str(index), 201)
                raw = request('/api/v1/ballistic/files/' + reference['fileId'] + '/download', name=prefix + name + '-download' + str(index))
                result_files.append({'path': reference['serverPath'], 'fileId': reference['fileId'], 'sha256': hashlib.sha256(raw).hexdigest(), 'bytes': len(raw)})
            return result_files
        serial = {item[0]: generate(item, 'serial-') for item in operations}
        parallel = {}
        with futures.ThreadPoolExecutor(max_workers=6) as pool:
            pending = {pool.submit(generate, item, 'parallel-'): item[0] for item in reversed(operations)}
            for result in futures.as_completed(pending):
                name = pending[result]; parallel[name] = result.result()
                assert [r['sha256'] for r in parallel[name]] == [r['sha256'] for r in serial[name]], '输出CSV差异:' + name
        all_files = [f for values in list(serial.values()) + list(parallel.values()) for f in values]
        assert len({f['path'] for f in all_files}) == len(all_files)
        assert len({f['fileId'] for f in all_files}) == len(all_files)

        storage = Path(uploaded['serverPath']).parent.parent
        def snapshot():
            return sorted(p.relative_to(storage).as_posix() for p in storage.rglob('*'))
        # 原writer已写第一行后第二行ECEF解析失败，验证真实HTTP拥有目录和登记标记完整回滚。
        lines = retime_csv.decode('utf-8-sig').splitlines()
        bad_row = lines[2].split(','); bad_row[6] = 'invalid-position'
        broken_csv = ('\n'.join([lines[0], lines[1], ','.join(bad_row)]) + '\n').encode()
        broken = json.loads(request('/api/v1/ballistic/files', multipart.replace(csv, broken_csv), 'broken-upload', 201, 'multipart/form-data; boundary=' + boundary))
        files_before_failure = snapshot()
        post('/api/trajectory/retime', {'trajectoryCsvPath': broken['serverPath'], 'launchTimeUtc': '2026-07-02 07:57:00.000'}, 'owned-partial-write-failure', 400)
        assert snapshot() == files_before_failure, '失败独占目录或元数据未回滚'
        # 同一结果自动登记后再次并发登记必须复用ID，不增加元数据。
        entry = all_files[0]
        with futures.ThreadPoolExecutor(max_workers=2) as pool:
            repeated = [pool.submit(post, '/api/v1/ballistic/files/register-result', {'csvPath': entry['path']}, 'repeat-register-' + str(i), 201) for i in range(2)]
            assert all(result.result()['fileId'] == entry['fileId'] for result in repeated)
        assert snapshot() == files_before_failure

        # 同一输入同时下载与预览，同时新输出失败；已有输入/其他输出必须完整保留。
        failure = copy.deepcopy(operations[0][2]); failure['outputDir'] = uploaded['serverPath']
        with futures.ThreadPoolExecutor(max_workers=3) as pool:
            download = pool.submit(request, '/api/v1/ballistic/files/' + uploaded['fileId'] + '/download', name='same-source-download')
            preview = pool.submit(post, '/api/v1/ballistic/files/' + uploaded['fileId'] + '/preview', None, 'same-source-preview')
            failed = pool.submit(post, '/api/trajectory/fit/package102', failure, 'explicit-output-file-failure', 500)
            assert download.result() == csv
            assert preview.result()['totalRows'] == 8560
            failed.result()
        invalid = copy.deepcopy(window_inputs[0][1])
        invalid['satellites'][0]['payloads'][0]['minDistanceKm'] = -1
        post('/api/orbit/visibility/search', invalid, 'bad-window', 400)
        request('/api/v1/ballistic/files/missing/preview', name='missing-file', status=404)
        assert request('/api/v1/ballistic/files/' + uploaded['fileId'] + '/download', name='source-after-failure') == csv
        for index, item in enumerate(all_files):
            assert hashlib.sha256(request('/api/v1/ballistic/files/' + item['fileId'] + '/download', name='output-after-failure-' + str(index))).hexdigest() == item['sha256']

        # 真实TCP RST分别中断大响应与下载；之后完整请求成功，原同步计算不增加新取消语义。
        parsed = urlparse(args.base_url)
        disconnects = []
        for path, body in [('/api/orbit/visibility/search', window_inputs[0][1]), ('/api/v1/ballistic/files/' + uploaded['fileId'] + '/download', None)]:
            raw = json.dumps(body, ensure_ascii=False).encode() if body else b''
            method = 'POST' if body else 'GET'
            with socket.create_connection((parsed.hostname, parsed.port), timeout=120) as client:
                header = f'{method} {path} HTTP/1.1\r\nHost: {parsed.hostname}\r\nContent-Type: application/json\r\nContent-Length: {len(raw)}\r\nConnection: close\r\n\r\n'.encode()
                client.sendall(header + raw)
                received = client.recv(16384)
                assert b'200' in received.split(b'\r\n')[0], received[:150]
                client.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack('HH', 1, 0))
                disconnects.append({'path': path, 'receivedBeforeReset': len(received)})
        assert post('/api/orbit/visibility/search', window_inputs[0][1], 'window-after-rst') == expected[window_inputs[0][0]]
        assert request('/api/v1/ballistic/files/' + uploaded['fileId'] + '/download', name='download-after-rst') == csv
        best = expected[window_inputs[0][0]]['best']
        summary = {'passed': True, 'requests': records, 'windowCases': len(window_inputs), 'parallelism': 6,
                   'allDecimalResponsesEqual': True, 'outputFilesUnique': len(all_files), 'allCsvBytesEqual': True,
                   'bestArcCount': best['visibleArcCount'], 'bestPointCount': best['visiblePointCount'],
                   'disconnects': disconnects, 'sourceSha256': hashlib.sha256(csv).hexdigest(),
                   'ownedPartialWriteRollback': True, 'concurrentRegistrationIdempotent': True,
                   'fileCounts': {key: sum(p.is_file() for p in (storage / key).rglob('*')) for key in ('uploads', 'results', 'metadata', 'temp')},
                   'directoryCounts': {key: sum(p.is_dir() for p in (storage / key).rglob('*')) for key in ('uploads', 'results', 'metadata', 'temp')},
                   'metadataJsonCount': len(list((storage / 'metadata').glob('*.json'))),
                   'workspaceMarkerCount': len(list((storage / 'metadata').glob('*.workspace'))),
                   'pid': args.pid, 'service': args.base_url,
                   'jarSha256': hashlib.sha256((ROOT / 'verification/runtime/T26/space-target-visible-T26.jar').read_bytes()).hexdigest()}
        assert summary['metadataJsonCount'] == 3 + len(all_files)
        assert summary['fileCounts']['temp'] == 0
    finally:
        done.set(); sampler.join(); kernel.CloseHandle(process)
        (RUNTIME / 'process-samples.json').write_text(json.dumps(metrics, indent=2), encoding='utf-8')
        (RUNTIME / 'requests.json').write_text(json.dumps(records, indent=2), encoding='utf-8')
    summary['metrics'] = {'kind': 'Windows process working set/private bytes/handles, 100ms sampling; no forced GC',
        'samples': len(metrics), 'before': metrics[0], 'after': metrics[-1],
        'maxWorkingSetBytes': max(m['workingSetBytes'] for m in metrics),
        'maxPrivateBytes': max(m['privateBytes'] for m in metrics), 'maxHandleCount': max(m['handleCount'] for m in metrics)}
    (ROOT / ('verification/T26/concurrency-' + args.name + '.json')).write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({k: v for k, v in summary.items() if k != 'requests'}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
