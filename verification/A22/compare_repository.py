"""比较指定权威工作区源码、已有运行产物和合并版的真实窗口JSON，不修改数值实现。"""
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
AUTHORITY = Path('G:/Code/Code-V3/visibility-trajectory')
DELIVERY = Path('G:/Code/Code-V3/alg/deliverables/alg-0723')
EVIDENCE = ROOT / 'verification/A22'
RUNTIME = ROOT / 'verification/runtime/A22'
JAVA = Path('C:/Program Files/Eclipse Adoptium/jdk-8.0.472.8-hotspot/bin')
LIBS = str(DELIVERY / 'dependencies/runtime-jars/*')
PROBE = ROOT / 'verification/A21/WindowOutputProbe.java'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def save(name, value):
    (EVIDENCE / (name + '.json')).write_text(
        json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def hashes(folder, pattern):
    return {p.relative_to(folder).as_posix(): sha(p.read_bytes())
            for p in sorted(folder.rglob(pattern)) if p.is_file()}


def execute(name, args):
    """按实际字节保存双流；已有记录拒绝覆盖，失败不生成通过结论。"""
    if (EVIDENCE / (name + '-command.json')).exists():
        raise FileExistsError(name)
    start = time.time()
    result = subprocess.run(list(map(str, args)), cwd=RUNTIME, capture_output=True,
                            creationflags=subprocess.CREATE_NO_WINDOW)
    for stream, raw in [('stdout', result.stdout), ('stderr', result.stderr)]:
        (EVIDENCE / (name + '-' + stream + '.txt')).write_bytes(raw)
    save(name + '-command', {'args': list(map(str, args)), 'cwd': str(RUNTIME),
                            'exitCode': result.returncode, 'durationSec': time.time() - start,
                            'stdoutSha256': sha(result.stdout), 'stderrSha256': sha(result.stderr)})
    if result.returncode:
        raise RuntimeError(name + '执行失败，请检查原始双流')


def differences(a, b, path='$'):
    """递归保持数组顺序和精确十进制值；键顺序另由原字节比较覆盖。"""
    if type(a) is not type(b):
        return [{'path': path, 'kind': 'type'}]
    result = []
    if isinstance(a, dict):
        if a.keys() != b.keys():
            result.append({'path': path, 'kind': 'fields', 'authorityOnly': sorted(a.keys() - b.keys()),
                           'currentOnly': sorted(b.keys() - a.keys())})
        for key in sorted(a.keys() & b.keys()):
            result.extend(differences(a[key], b[key], path + '.' + key))
    elif isinstance(a, list):
        if len(a) != len(b):
            result.append({'path': path, 'kind': 'length', 'authority': len(a), 'current': len(b)})
        for index, (x, y) in enumerate(zip(a, b)):
            result.extend(differences(x, y, path + '[' + str(index) + ']'))
    elif a != b:
        result.append({'path': path, 'kind': 'value', 'authority': str(a), 'current': str(b)})
    return result


def summary(value):
    """逐弧核验实际点数组与汇总，记录可直接定位的轨迹点路径。"""
    counts, sample_path, errors = [], None, []
    for label, result in [('best', value['best'])] + [
            ('allResults[' + str(i) + ']', x) for i, x in enumerate(value['allResults'])]:
        arcs = points = 0
        for si, satellite in enumerate(result['visibleSatellites']):
            for pi, payload in enumerate(satellite['payloadResults']):
                for ti, target in enumerate(payload['targets']):
                    for ai, arc in enumerate(target['visibleArcs']):
                        arcs += 1
                        points += len(arc['trajectoryPoints'])
                        if len(arc['trajectoryPoints']) != arc['pointCount']:
                            errors.append(label + ': 弧段点数不符')
                        if sample_path is None and arc['trajectoryPoints']:
                            sample_path = (label + '.visibleSatellites[' + str(si) + '].payloadResults['
                                           + str(pi) + '].targets[' + str(ti) + '].visibleArcs['
                                           + str(ai) + '].trajectoryPoints[0]')
        if arcs != result['visibleArcCount'] or points != result['visiblePointCount']:
            errors.append(label + ': 汇总计数不符')
        counts.append((arcs, points))
    best = value['best']
    return {'offsets': len(value['allResults']), 'bestOffsetSecond': best['offsetSecond'],
            'bestArcs': counts[0][0], 'bestPoints': counts[0][1],
            'bestDurationSec': str(best['visibleDurationSec']),
            'allOffsetArcs': sum(x[0] for x in counts[1:]),
            'allOffsetPoints': sum(x[1] for x in counts[1:]),
            'firstPointPath': sample_path, 'countErrors': errors}


def main():
    RUNTIME.mkdir(parents=True, exist_ok=False)
    source_roots = {'authority-source': AUTHORITY / 'src/main/java',
                    'current-source': ROOT / 'backend/src/main/java'}
    initial_sources = {v: hashes(p, '*.java') for v, p in source_roots.items()}
    initial_classes = hashes(AUTHORITY / 'target/classes', '*.class')
    jar = AUTHORITY / 'target/alg-toolkit-1.0.0.jar.original'
    jar_hash = sha(jar.read_bytes())
    status_before = subprocess.check_output(['git', '-C', str(AUTHORITY), 'status', '--porcelain', '-z'])
    authority_head = subprocess.check_output(['git', '-C', str(AUTHORITY), 'rev-parse', 'HEAD']).decode().strip()
    request = json.loads((ROOT / 'verification/A21/request.json').read_text(encoding='utf-8'))
    csv = AUTHORITY / 'trajectory-0.1s-standard.csv'
    csv_hash = sha(csv.read_bytes())
    request['ballistics'][0]['csvPath'] = csv.as_posix()
    request_file = EVIDENCE / 'request.json'
    request_file.write_text(json.dumps(request, ensure_ascii=False, indent=2), encoding='utf-8')
    stamp = 'window-output-comparison-A22-20261026-' + time.strftime('%H%M%S')
    output_dirs = {'authority': AUTHORITY / 'output' / stamp, 'current': ROOT / 'output' / stamp}
    outputs = {'authority-source': output_dirs['authority'] / 'visibility-result.json',
               'current-source': output_dirs['current'] / 'visibility-result.json',
               'authority-jar': output_dirs['authority'] / 'visibility-result-existing-jar.json',
               'authority-classes': output_dirs['authority'] / 'visibility-result-existing-classes.json'}
    classpaths = {}
    for version, source in source_roots.items():
        classes = RUNTIME / (version + '-classes')
        classes.mkdir()
        controller = ('com/tx/alg/api/OrbitVisibilityController.java' if version == 'authority-source'
                      else 'com/runisys/orbit/spacevisibility/api/ballistic/BallisticVisibilityController.java')
        execute(version + '-compile', [JAVA / 'javac.exe', '-encoding', 'UTF-8', '-source', '8',
                '-target', '8', '-cp', LIBS, '-sourcepath', source, '-d', classes,
                source / controller, PROBE])
        classpaths[version] = str(classes) + os.pathsep + LIBS
    probe_only = RUNTIME / 'probe-only'
    probe_only.mkdir()
    shutil.copy2(RUNTIME / 'authority-source-classes/WindowOutputProbe.class', probe_only)
    classpaths['authority-jar'] = os.pathsep.join([str(probe_only), str(jar), LIBS])
    classpaths['authority-classes'] = os.pathsep.join([str(probe_only), str(AUTHORITY / 'target/classes'), LIBS])
    for version, classpath in classpaths.items():
        execute(version + '-run', [JAVA / 'java.exe', '-Dfile.encoding=UTF-8', '-Djava.awt.headless=true',
                '-cp', classpath, 'WindowOutputProbe',
                'current' if version == 'current-source' else 'original', request_file, outputs[version]])
    assert initial_sources == {v: hashes(p, '*.java') for v, p in source_roots.items()}
    assert initial_classes == hashes(AUTHORITY / 'target/classes', '*.class')
    assert jar_hash == sha(jar.read_bytes()) and csv_hash == sha(csv.read_bytes())
    data = {v: p.read_bytes() for v, p in outputs.items()}
    values = {v: json.loads(raw, parse_float=Decimal) for v, raw in data.items()}
    comparisons = {}
    for version in ['authority-source', 'authority-jar', 'authority-classes']:
        diff = differences(values[version], values['current-source'])
        comparisons[version + '-vs-current'] = {'byteIdentical': data[version] == data['current-source'],
                                               'jsonIdentical': not diff, 'differences': diff}
    record = {'authorityHead': authority_head, 'authorityStatusBeforeHex': status_before.hex(),
              'mode': '各自真实控制器及StreamingResponseBody，未经过HTTP传输',
              'request': str(request_file), 'requestSha256': sha(request_file.read_bytes()),
              'trajectory': str(csv), 'trajectorySha256': csv_hash,
              'sharedDependencyDirectory': LIBS, 'probeSha256': sha(PROBE.read_bytes()),
              'sourceHashes': initial_sources, 'authorityClassHashes': initial_classes,
              'authorityJar': {'path': str(jar), 'sha256': jar_hash}, 'protectedInputsUnchanged': True,
              'outputs': {v: {'path': str(p), 'bytes': len(data[v]), 'sha256': sha(data[v])}
                          for v, p in outputs.items()},
              'comparisons': comparisons, 'summaries': {v: summary(value) for v, value in values.items()}}
    save('comparison', record)
    print(json.dumps({k: record[k] for k in ['outputs', 'comparisons', 'summaries']}, ensure_ascii=True))
    assert all(not value['countErrors'] and value['bestPoints'] > 0 for value in record['summaries'].values())


if __name__ == '__main__':
    main()
