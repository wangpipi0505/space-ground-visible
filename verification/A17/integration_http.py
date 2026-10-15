"""启动44新增文件加4原空间公共类的隔离工程，验证组件边界及11项弹道HTTP操作。"""
from pathlib import Path
import hashlib
import json
import os
import socket
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
RUN = ROOT / 'verification/runtime/A17'
OUT = ROOT / 'verification/A17'
work = RUN / 'integration-probe-r2'
data = RUN / 'isolated-data-r3'
data.mkdir(exist_ok=False)
with socket.socket() as reserve:
    reserve.bind(('127.0.0.1', 0))
    port = reserve.getsockname()[1]
args = ['C:/Program Files/Eclipse Adoptium/jdk-8.0.472.8-hotspot/bin/java.exe', '-jar',
        'target/ballistic-integration-probe-1.0.0.jar', '--server.port=' + str(port),
        '--ballistic.files.root=' + str(data)]
base = 'http://127.0.0.1:' + str(port)
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
operations = []
outlog, errlog = OUT / 'isolated-r3-http-stdout.txt', OUT / 'isolated-r3-http-stderr.txt'


def call(method, path, payload=None, raw=None, content_type='application/json', status=200):
    body = raw if raw is not None else (json.dumps(payload).encode('utf-8') if payload is not None else None)
    request = urllib.request.Request(base + path, data=body, method=method, headers={'Content-Type': content_type})
    with opener.open(request, timeout=60) as response:
        value = response.read()
        assert response.status == status, (path, response.status)
        operations.append(dict(method=method, path=path, status=response.status, responseBytes=len(value),
                               responseSha256=hashlib.sha256(value).hexdigest()))
        return value


fixtures = json.loads((ROOT / 'verification/A12/final-r2/request-examples.json').read_text(encoding='utf-8'))
summary = dict(args=args, cwd=str(work), successfulOperations=operations, preservedOriginalSpaceTypes=4)
with outlog.open('wb') as stdout, errlog.open('wb') as stderr:
    process = subprocess.Popen(args, cwd=work, env=dict(os.environ), stdout=stdout, stderr=stderr,
                               creationflags=subprocess.CREATE_NO_WINDOW)
    try:
        start = time.monotonic()
        while time.monotonic() - start < 35:
            assert process.poll() is None, '服务启动前退出'
            try:
                with opener.open(base + '/api/orbit/health', timeout=0.7) as response:
                    if response.status == 200 and response.read() == b'OK':
                        break
            except Exception:
                time.sleep(0.3)
        assert call('GET', '/api/orbit/health') == b'OK'
        assert call('GET', '/api/trajectory/health') == b'OK'
        request = next(item['value'] for item in fixtures if item['kind'] == 'visibility' and item['value'].get('satellites'))
        payload = request['satellites'][0]['payloads'][0]
        checked = json.loads(call('POST', '/api/v1/ballistic/payloads/preview', payload))
        assert checked['payloadType'] == payload['payloadType']
        sample = (ROOT / 'frontend/public/samples/trajectory-0.1s-standard.csv').read_bytes()
        boundary = 'A16IntegrationBoundary'
        multipart = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="trajectory.csv"\r\nContent-Type: text/csv\r\n\r\n').encode() + sample + ('\r\n--' + boundary + '--\r\n').encode()
        uploaded = json.loads(call('POST', '/api/v1/ballistic/files', raw=multipart,
                                   content_type='multipart/form-data; boundary=' + boundary, status=201))
        preview = json.loads(call('GET', '/api/v1/ballistic/files/' + uploaded['fileId'] + '/preview?page=1&limit=2'))
        assert preview.get('rows'), preview
        downloaded = call('GET', '/api/v1/ballistic/files/' + uploaded['fileId'] + '/download')
        assert downloaded == sample
        request['ballistics'] = [dict(csvPath=uploaded['serverPath'], targetId='A16-TGT', targetName='集成验证')]
        request['startOffsetSec'] = 0
        request['numCalculations'] = 1
        request['targetSampleStepSec'] = 5.0
        request['trajectoryPointOutput'] = 0
        window = json.loads(call('POST', '/api/orbit/visibility/search', request))
        assert 'best' in window and len(window['allResults']) == 1
        fit = json.loads(call('POST', '/api/trajectory/fit/package102', next(item['value'] for item in fixtures if item['kind'] == 'fit')))
        assert fit['targetCount'] == 1 and fit['files'][0]['pointCount'] > 0
        simulated = json.loads(call('POST', '/api/trajectory/simulate', next(item['value'] for item in fixtures if item['kind'] == 'simulate')))
        assert simulated['targetCount'] == 1 and simulated['files'][0]['pointCount'] > 0
        retime = next(item['value'] for item in fixtures if item['kind'] == 'retime')
        retime['trajectoryCsvPath'] = simulated['files'][0]['csvPath']
        retimed = json.loads(call('POST', '/api/trajectory/retime', retime))
        assert retimed['files'][0]['pointCount'] == simulated['files'][0]['pointCount']
        registered = json.loads(call('POST', '/api/v1/ballistic/files/register-result',
                                dict(csvPath=fit['files'][0]['csvPath'], taskName=fit['taskName'], targetId=fit['files'][0]['targetId']), status=201))
        assert registered.get('fileId') and len(operations) == 11
        summary.update(passed=True, fitPoints=fit['files'][0]['pointCount'], simulationPoints=simulated['files'][0]['pointCount'],
                       retimePoints=retimed['files'][0]['pointCount'], downloadByteEqual=True,
                       sampleSha256=hashlib.sha256(sample).hexdigest())
    finally:
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=10)
        summary['stoppedOwnProcess'] = True
        summary['processId'] = process.pid
        summary['stdoutSha256'] = hashlib.sha256(outlog.read_bytes()).hexdigest()
        summary['stderrSha256'] = hashlib.sha256(errlog.read_bytes()).hexdigest()
        text = outlog.read_text(encoding='utf-8', errors='replace')
        summary['registeredOperationCount'] = text.count('BALLISTIC_MAPPING=')
        (OUT / 'isolated-r3-http.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')
assert summary['registeredOperationCount'] == 11, summary
print('PASS：44新增+4原空间公共类，11操作唯一注册及11项HTTP操作成功，上传下载原字节一致。')
