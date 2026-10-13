"""真实上传和非空多目标多载荷窗口，与独立冻结原控制器完整JSON精确对照。"""
import argparse
import copy
import hashlib
import json
import subprocess
import uuid
from decimal import Decimal
from pathlib import Path
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-url', default='http://127.0.0.1:18080')
    args = parser.parse_args()
    runtime = ROOT / 'verification/runtime/T19/live-http'
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
    boundary = 'T19-' + uuid.uuid4().hex
    prefix = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; '
              'filename="trajectory-live.csv"\r\nContent-Type: text/csv\r\n\r\n').encode('ascii')
    upload = json.loads(send('/api/v1/ballistic/files', prefix + original
                            + ('\r\n--' + boundary + '--\r\n').encode('ascii'),
                            'multipart/form-data; boundary=' + boundary))
    assert upload['sizeBytes'] == len(original)
    conditions = json.loads((ROOT / 'test-data/baseline/ballistic/conditions.json').read_text(encoding='utf-8'))
    request = copy.deepcopy(next(case['request'] for case in conditions if case['name'] == 'visibility-http-ground'))
    for target in request['ballistics']:
        target['csvPath'] = upload['serverPath']
    for satellite in request['satellites']:
        for payload in satellite['payloads']:
            payload['payloadType'] = '对空跟踪'
    # 完整窗口/点数/时长metadata比较；既有原冷/热JIT点高度末位观察由T14解释执行专项覆盖。
    request['trajectoryPointOutput'] = 0
    encoded = json.dumps(request, ensure_ascii=False).encode('utf-8')
    (runtime / 'request.json').write_bytes(encoded)
    actual_bytes = send('/api/orbit/visibility/search', encoded, 'application/json')
    (runtime / 'response.json').write_bytes(actual_bytes)

    java = Path('C:/Program Files/Eclipse Adoptium/jdk-8.0.472.8-hotspot/bin')
    probe = runtime / 'probe'
    probe.mkdir(exist_ok=True)
    commands = [
        [str(java / 'javac.exe'), '-encoding', 'UTF-8', '-d', str(probe),
         str(ROOT / 'verification/T19/FrozenVisibilityReference.java')],
        [str(java / 'java.exe'), '-Dfile.encoding=UTF-8', '-Djava.awt.headless=true', '-cp', str(probe),
         'FrozenVisibilityReference', str(ROOT / 'verification/baseline-copies/ballistic/source/target/classes'),
         str(ROOT / 'verification/baseline-copies/ballistic/dependencies/runtime-jars'),
         str(runtime / 'request.json'), str(runtime / 'reference-response.json')]
    ]
    for index, command in enumerate(commands):
        result = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (runtime / ('reference-' + str(index) + '.log')).write_bytes(result.stdout)
        result.check_returncode()
    actual = json.loads(actual_bytes, parse_float=Decimal)
    expected = json.loads((runtime / 'reference-response.json').read_bytes(), parse_float=Decimal)
    assert actual == expected, '完整实际HTTP与独立原控制器结果有差异'
    assert len(actual['allResults']) == 3
    assert len(actual['best']['targets']) == 2
    assert actual['best']['visibleArcCount'] > 0
    assert [entry['status'] for entry in statuses] == [200, 201, 200]
    evidence = {'passed': True, 'requestStatuses': statuses, 'allOffsetsCompared': 3,
                'targetCount': 2, 'satelliteCount': 2, 'payloadCountPerSatellite': 2,
                'bestOffsetSecond': actual['best']['offsetSecond'],
                'visibleArcCount': actual['best']['visibleArcCount'],
                'visiblePointCount': actual['best']['visiblePointCount'],
                'visibleDurationSec': str(actual['best']['visibleDurationSec']),
                'inputSha256': hashlib.sha256(original).hexdigest(),
                'comparison': '同一实际上传路径，完整JSON结构/Decimal数值/顺序精确比较，无字段忽略或路径映射',
                'reference': '独立URLClassLoader，父仅JDK，冻结原Controller来源路径实测断言',
                'trajectoryPointOutput': 0}
    (ROOT / 'verification/T19/live-http-compare.json').write_text(
        json.dumps(evidence, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(evidence, ensure_ascii=False))


if __name__ == '__main__':
    main()
