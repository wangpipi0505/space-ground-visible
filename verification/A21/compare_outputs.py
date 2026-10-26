"""从两个指定目录的现行源码独立编译/计算，分别落盘并严格比较原始窗口JSON。"""
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
OLD = Path('G:/Code/Code-V3/alg/deliverables/alg-0723')
EVIDENCE = ROOT / 'verification/A21'
RUNTIME = ROOT / 'verification/runtime/A21'
JAVA = Path('C:/Program Files/Eclipse Adoptium/jdk-8.0.472.8-hotspot/bin')
LIBS = str(OLD / 'dependencies/runtime-jars/*')


def sha(data):
    return hashlib.sha256(data).hexdigest()


def save(name, value):
    (EVIDENCE / (name + '.json')).write_text(
        json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def execute(name, command):
    """保存实际命令双流及退出码，失败时不覆盖本轮已有记录。"""
    target = EVIDENCE / (name + '-command.json')
    if target.exists():
        raise FileExistsError(target)
    start = time.time()
    result = subprocess.run(list(map(str, command)), cwd=RUNTIME, capture_output=True,
                            creationflags=subprocess.CREATE_NO_WINDOW)
    for stream, content in [('stdout', result.stdout), ('stderr', result.stderr)]:
        (EVIDENCE / (name + '-' + stream + '.txt')).write_bytes(content)
    save(name + '-command', {'args': list(map(str, command)), 'cwd': str(RUNTIME),
                            'exitCode': result.returncode, 'durationSec': time.time() - start,
                            'stdoutSha256': sha(result.stdout), 'stderrSha256': sha(result.stderr)})
    if result.returncode:
        raise RuntimeError(name + '失败，请检查原始双流')


def files_under(folder):
    return {p.relative_to(folder).as_posix(): sha(p.read_bytes())
            for p in sorted(folder.rglob('*.java'))}


def main():
    RUNTIME.mkdir(parents=True, exist_ok=False)
    original_source = OLD / 'source/src/main/java'
    current_source = ROOT / 'backend/src/main/java'
    sources = {'original': files_under(original_source), 'current': files_under(current_source)}
    trajectory = OLD / 'data/trajectory-0.1s-standard.csv'
    request = json.loads((OLD / 'data/examples/orbit_visibility_multi_payload_default.json')
                         .read_text(encoding='utf-8-sig'))
    # 共用绝对CSV路径，保证路径回显也相同；使用原示例的载荷值，只扩大扫描至当前页面默认范围。
    request['ballistics'][0]['csvPath'] = trajectory.as_posix()
    request.update(startOffsetSec=-500, stepSizeSec=10, numCalculations=361)
    request_path = EVIDENCE / 'request.json'
    request_path.write_text(json.dumps(request, ensure_ascii=False, indent=2), encoding='utf-8')
    stamp = '20261026-' + time.strftime('%H%M%S')
    outputs = {'original': OLD / 'output' / ('window-output-comparison-' + stamp) / 'visibility-result.json',
               'current': ROOT / 'output' / ('window-output-comparison-' + stamp) / 'visibility-result.json'}
    for version, source in [('original', original_source), ('current', current_source)]:
        classes = RUNTIME / (version + '-classes')
        classes.mkdir()
        controller = ('com/tx/alg/api/OrbitVisibilityController.java' if version == 'original'
                      else 'com/runisys/orbit/spacevisibility/api/ballistic/BallisticVisibilityController.java')
        execute(version + '-compile', [JAVA / 'javac.exe', '-encoding', 'UTF-8', '-source', '8',
                '-target', '8', '-cp', LIBS, '-sourcepath', source, '-d', classes,
                source / controller, EVIDENCE / 'WindowOutputProbe.java'])
        execute(version + '-run', [JAVA / 'java.exe', '-Dfile.encoding=UTF-8',
                '-Djava.awt.headless=true', '-cp', str(classes) + os.pathsep + LIBS,
                'WindowOutputProbe', version, request_path, outputs[version]])
    assert sources == {'original': files_under(original_source), 'current': files_under(current_source)}
    data = {version: path.read_bytes() for version, path in outputs.items()}
    values = {version: json.loads(content, parse_float=Decimal) for version, content in data.items()}
    differences = []

    def compare(a, b, path='$'):
        if type(a) is not type(b):
            differences.append({'path': path, 'kind': 'type'}); return
        if isinstance(a, dict):
            if a.keys() != b.keys():
                differences.append({'path': path, 'kind': 'fields',
                                    'originalOnly': sorted(a.keys() - b.keys()),
                                    'currentOnly': sorted(b.keys() - a.keys())})
            for key in a.keys() & b.keys():
                compare(a[key], b[key], path + '.' + key)
        elif isinstance(a, list):
            if len(a) != len(b): differences.append({'path': path, 'kind': 'length'})
            for i, (x, y) in enumerate(zip(a, b)): compare(x, y, path + '[' + str(i) + ']')
        elif a != b:
            differences.append({'path': path, 'kind': 'value', 'original': str(a), 'current': str(b)})

    compare(values['original'], values['current'])
    counts = {}
    for version, value in values.items():
        best = value['best']
        counts[version] = {'offsets': len(value['allResults']), 'bestOffsetSecond': best['offsetSecond'],
                           'visibleArcCount': best['visibleArcCount'],
                           'visiblePointCount': best['visiblePointCount'],
                           'visibleDurationSec': str(best['visibleDurationSec']),
                           'visibleSatellites': len(best['visibleSatellites']),
                           'payloads': [p['payloadId'] for s in best['visibleSatellites']
                                        for p in s['payloadResults']]}
    record = {'mode': '各自真实控制器计算并执行原StreamingResponseBody；未经过HTTP传输',
              'request': str(request_path), 'requestSha256': sha(request_path.read_bytes()),
              'trajectory': str(trajectory), 'trajectorySha256': sha(trajectory.read_bytes()),
              'sourcesUnchanged': True, 'sourceHashes': sources,
              'outputs': {v: {'path': str(p), 'bytes': len(data[v]), 'sha256': sha(data[v])}
                          for v, p in outputs.items()},
              'byteIdentical': data['original'] == data['current'],
              'jsonIdentical': not differences, 'differences': differences, 'counts': counts}
    save('comparison', record)
    print(json.dumps({key: record[key] for key in ['outputs', 'byteIdentical', 'jsonIdentical', 'counts']},
                     ensure_ascii=True))
    assert all(c['visibleArcCount'] > 0 for c in counts.values()), '结果为空，不能据此确认嵌套结构'
    assert not differences, '两个实际输出存在差异，已记录，不自动修改或补齐'


if __name__ == '__main__':
    main()
