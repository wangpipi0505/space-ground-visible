"""显式冻结原版模式：独立 JVM 采集、重采集比较与来源摘要；绝不加载当前产品 classes。"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--frozen-reference', action='store_true', required=True)
    args = parser.parse_args()
    runtime = ROOT / 'verification/runtime/T18/probe'
    runtime.mkdir(parents=True, exist_ok=True)
    evidence = ROOT / 'verification/T18'
    golden = ROOT / 'test-data/baseline/geometry'
    java = Path('C:/Program Files/Eclipse Adoptium/jdk-8.0.472.8-hotspot/bin')
    roots = [ROOT / 'verification/runtime/T04/space-java8-validation/target/classes',
             ROOT / 'verification/baseline-copies/ballistic/source/target/classes']
    for path in roots:
        path.resolve(strict=True)
    dependencies = [(ROOT / p).read_text().strip() for p in [
        'verification/runtime/T04/space-runner/classpath.txt',
        'verification/runtime/T17/ballistic-runner/classpath.txt']]
    cp = os.pathsep.join([str(p) for p in roots] + dependencies)
    def run(name, command):
        result = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (evidence / name).write_bytes(result.stdout)
        result.check_returncode()
    run('original-probe-compile-final.log', [str(java / 'javac.exe'), '-encoding', 'UTF-8', '-cp', cp,
        '-d', str(runtime), str(evidence / 'OriginalGeometryProbe.java')])
    outputs = [runtime / 'original.json', runtime / 'repeat.json', runtime / 'source-jar.json']
    for i, output in enumerate(outputs):
        current_cp = cp if i != 2 else cp.replace(str(roots[1]), str(ROOT / 'verification/baseline-copies/ballistic/build/alg-toolkit-1.0.0-plain.jar'))
        run('original-probe-final-%d.log' % i, [str(java / 'java.exe'), '-Dfile.encoding=UTF-8',
            '-cp', str(runtime) + os.pathsep + current_cp, 'OriginalGeometryProbe', str(output)])
    assert outputs[0].read_bytes() == outputs[1].read_bytes() == outputs[2].read_bytes()
    # 新增精确disc==0场景，原129组逐条不变，不能重写先前黄金掩盖差异。
    if (golden / 'geometry.json').exists():
        before = {r['id']: r for r in json.loads((golden / 'geometry.json').read_text(encoding='utf-8'))}
        after = {r['id']: r for r in json.loads(outputs[0].read_text(encoding='utf-8'))}
        assert all(after[key] == value for key, value in before.items())
    golden.mkdir(parents=True, exist_ok=True)
    (golden / 'geometry.json').write_bytes(outputs[0].read_bytes())
    sources = ['verification/T18/OriginalGeometryProbe.java',
        'verification/baseline-copies/ballistic/source/src/main/java/com/tx/alg/OrbitCalculator.java',
        'verification/baseline-copies/ballistic/source/target/classes/com/tx/alg/OrbitCalculator.class',
        'verification/baseline-copies/ballistic/build/alg-toolkit-1.0.0-plain.jar',
        'verification/baseline-copies/space/backend/src/main/java/com/runisys/orbit/spacevisibility/domain/geometry/Vector3.java',
        'verification/baseline-copies/space/backend/src/main/java/com/runisys/orbit/spacevisibility/domain/geometry/GroundStationGeometry.java',
        'verification/runtime/T04/space-java8-validation/target/classes/com/runisys/orbit/spacevisibility/domain/geometry/GroundStationGeometry.class']
    report = dict(javaVersion='1.8.0_472', pairs=130, rotatedCases=520, encoding='Double.doubleToRawLongBits hexadecimal',
        repeatedIdentical=True, originalBallisticJarIdentical=True,
        sources=[dict(file=p, sha256=digest(ROOT / p)) for p in sources],
        files=[dict(file='geometry.json', sha256=digest(golden / 'geometry.json'))])
    (golden / 'provenance.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    main()
