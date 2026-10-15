"""启动本轮两个独立 JAR，对照真实 HTTP 状态、内容及下载文件；只结束自行启动的进程。"""
from pathlib import Path
import copy
import hashlib
import json
import os
import re
import socket
import subprocess
import sys
import time
import urllib.request
import urllib.error
from run_validation import ROOT, OUT, RUN, JAVA, save
sys.path.insert(0, str(ROOT / 'scripts'))
from run_baseline import differences, load_json


def port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def normalize(value):
    if isinstance(value, dict):
        return {k: '<generated>' if k in ('csvPath', 'serverPath', 'fileId', 'createdAt', 'expiresAt', 'requestId') else normalize(v)
                for k, v in value.items()}
    if isinstance(value, list):
        return [normalize(x) for x in value]
    return value


def capture(stage):
    work = RUN / stage / 'backend'
    runtime = RUN / ('http-r2-' + stage)
    runtime.mkdir()
    address = port()
    url = 'http://127.0.0.1:' + str(address)
    log = (OUT / ('http-r2-' + stage + '-stdout.txt')).open('xb')
    err = (OUT / ('http-r2-' + stage + '-stderr.txt')).open('xb')
    args = [str(JAVA / 'bin/java.exe'), '-Dfile.encoding=UTF-8', '-Djava.awt.headless=true',
        '-Dorekit.data.path=' + str(work / 'orekit-data'), '-jar', str(work / 'target/space-target-visible-1.0.0-SNAPSHOT.jar'),
        '--spring.profiles.active=demo', '--server.address=127.0.0.1', '--server.port=' + str(address),
        '--spring.datasource.url=jdbc:h2:mem:a17;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE',
        '--ballistic.files.root=' + str(runtime / 'storage'),
        '--space.visibility.runtime.tomcat-protocol=org.apache.coyote.http11.Http11Nio2Protocol']
    process = subprocess.Popen(args, cwd=work, stdout=log, stderr=err, creationflags=subprocess.CREATE_NO_WINDOW)
    records = []
    def request(name, route, body=None, expected=200):
        payload = None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8')
        req = urllib.request.Request(url + route, data=payload, headers={'Content-Type': 'application/json'})
        try:
            response = urllib.request.urlopen(req, timeout=120)
        except urllib.error.HTTPError as error:
            response = error
        raw = response.read()
        media = response.headers.get('Content-Type', '').split(';')[0]
        parsed = json.loads(raw) if media == 'application/json' else raw.decode('utf-8')
        record = dict(name=name, status=response.code, contentType=media, response=normalize(parsed))
        records.append(record)
        assert response.code == expected, record
        return parsed
    try:
        deadline = time.time() + 75
        while True:
            try:
                urllib.request.urlopen(url + '/api/orbit/health', timeout=2).close()
                break
            except Exception:
                assert process.poll() is None, '服务启动退出'
                if time.time() > deadline:
                    raise TimeoutError('隔离服务启动超时')
                time.sleep(.5)
        request('orbit-health', '/api/orbit/health')
        request('trajectory-health', '/api/trajectory/health')
        source = ROOT / 'test-data/reference/ballistic'
        for operation, file in [('fit/package102', 'target_trajectory_fit_package102_default.json'),
                                ('simulate', 'target_trajectory_simulate_default.json')]:
            original = json.loads((source / file).read_text(encoding='utf-8-sig'))
            original.pop('outputDir', None)
            result = request(operation, '/api/trajectory/' + operation, original)
            for i, output in enumerate(result['files']):
                raw = Path(output['csvPath']).read_bytes()
                records.append(dict(name=operation + '-csv-' + str(i), bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest()))
            if operation == 'simulate':
                retime = dict(taskName='A17-retime', targetId='RETIMED-TGT', trajectoryCsvPath=result['files'][0]['csvPath'],
                              launchTimeUtc='2026-07-02 07:57:00.000')
                retimed = request('retime', '/api/trajectory/retime', retime)
                raw = Path(retimed['files'][0]['csvPath']).read_bytes()
                records.append(dict(name='retime-csv', bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest()))
        # 字段绑定、默认值、outputDir 的缺失/null/空串语义及错误优先级通过真实 MVC 验证。
        for operation in ('fit/package102', 'simulate', 'retime'):
            cases = [('empty', {}), ('null-output', {'outputDir': None}), ('blank-output', {'outputDir': ''}),
                     ('bad-number', {'interpolationStepSec': {}, 'durationSec': {}, 'launchTimeUtc': []})]
            for name, body in cases:
                expected = 500 if name == 'bad-number' else 400
                request(operation + '-' + name, '/api/trajectory/' + operation, body, expected)
        for model in ('TWO_BODY', 'J2_SECULAR', 'J2_NUMERICAL'):
            for points in (0, 1):
                body = json.loads((RUN / 'numeric-before/ballistic-runner' / ('visibility-' + model.lower() + '-points' + str(points) + '-request.json')).read_text(encoding='utf-8'))
                body['orbitModel'], body['trajectoryPointOutput'] = model, points
                request('visibility-' + model + '-' + str(points), '/api/orbit/visibility/search', body)
        request('visibility-invalid', '/api/orbit/visibility/search', {'ballisticCsvPath': ''}, 400)
        save('http-' + stage, dict(pid=process.pid, args=args, records=records))
        return records
    finally:
        process.terminate()
        process.wait(timeout=25)
        log.close()
        err.close()


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    left, right = capture('before'), capture('after-v2')
    found = differences(left, right)
    save('http-compare', dict(passed=not found, records=len(left), differences=found,
        normalization='仅生成路径/文件标识/时间戳/requestId，数值与数组顺序不变；CSV以原字节SHA比较'))
    assert not found, found[:20]
    print('HTTP before/after PASS', len(left))
