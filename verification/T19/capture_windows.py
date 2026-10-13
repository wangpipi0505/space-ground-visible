"""显式只读原版采集：原classes重复运行及原plain JAR交叉验证，不回退到当前产品。"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--frozen-reference', action='store_true', required=True)
    parser.parse_args()
    runtime = ROOT / 'verification/runtime/T19/probe'
    runtime.mkdir(parents=True, exist_ok=True)
    evidence = ROOT / 'verification/T19'
    golden = ROOT / 'test-data/baseline/windows'
    java = Path('C:/Program Files/Eclipse Adoptium/jdk-8.0.472.8-hotspot/bin')
    roots = [ROOT / 'verification/runtime/T04/space-java8-validation/target/classes',
             ROOT / 'verification/baseline-copies/ballistic/source/target/classes']
    jar = ROOT / 'verification/baseline-copies/ballistic/build/alg-toolkit-1.0.0-plain.jar'
    for path in roots + [jar]:
        path.resolve(strict=True)
    dependencies = [(ROOT / p).read_text().strip() for p in [
        'verification/runtime/T04/space-runner/classpath.txt',
        'verification/runtime/T17/ballistic-runner/classpath.txt']]
    cp = os.pathsep.join([str(p) for p in roots] + dependencies)
    source = 'backend/src/test/java/com/runisys/orbit/spacevisibility/domain/window/WindowReferenceScenarios.java'
    def run(name, command):
        result = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (evidence / name).write_bytes(result.stdout)
        if result.returncode:
            print(result.stdout.decode('utf-8', errors='replace'))
        result.check_returncode()
    run('original-compile.log', [str(java / 'javac.exe'), '-encoding', 'UTF-8', '-cp', cp,
        '-d', str(runtime), str(ROOT / source), str(evidence / 'OriginalWindowProbe.java')])
    outputs = [runtime / 'original.json', runtime / 'repeat.json', runtime / 'jar.json']
    for i, output in enumerate(outputs):
        current_cp = cp if i != 2 else cp.replace(str(roots[1]), str(jar))
        run('original-%d.log' % i, [str(java / 'java.exe'), '-Dfile.encoding=UTF-8',
            '-cp', str(runtime) + os.pathsep + current_cp, 'OriginalWindowProbe', str(output)])
    assert outputs[0].read_bytes() == outputs[1].read_bytes() == outputs[2].read_bytes()
    golden.mkdir(parents=True, exist_ok=True)
    if (golden / 'windows.json').exists():
        before = json.loads((golden / 'windows.json').read_text(encoding='utf-8'))
        after = json.loads(outputs[0].read_text(encoding='utf-8'))
        assert all(after.get(key) == value for key, value in before.items()), '扩充样例不得改变已有黄金'
    (golden / 'windows.json').write_bytes(outputs[0].read_bytes())
    sources = [source, 'verification/T19/OriginalWindowProbe.java', str(jar.relative_to(ROOT)),
        'verification/baseline-copies/ballistic/source/src/main/java/com/tx/alg/OrbitCalculator.java',
        'verification/baseline-copies/ballistic/source/target/classes/com/tx/alg/OrbitCalculator.class']
    for name in ['TimeWindowScanner', 'TimeInterval', 'WindowNormalizer', 'CombinationWindowCalculator']:
        sources += ['verification/baseline-copies/space/backend/src/main/java/com/runisys/orbit/spacevisibility/domain/window/' + name + '.java',
                    str(roots[0].relative_to(ROOT)) + '/com/runisys/orbit/spacevisibility/domain/window/' + name + '.class']
    digest = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    report = dict(javaVersion='1.8.0_472', repeatedIdentical=True, originalBallisticJarIdentical=True,
        scenarios=len(json.loads(outputs[0].read_text(encoding='utf-8'))),
        sources=[dict(file=p, sha256=digest(ROOT / p)) for p in sources],
        files=[dict(file='windows.json', sha256=digest(golden / 'windows.json'))])
    (golden / 'provenance.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    main()
