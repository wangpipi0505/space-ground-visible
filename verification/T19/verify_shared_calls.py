"""核验最终字节码的真实共用关系、原空间公开ABI以及class与最终JAR一致性。"""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
WINDOW = 'com.runisys.orbit.spacevisibility.domain.window.'
CLASSES = ROOT / 'backend/target/classes'
JAVAP = 'C:/Program Files/Eclipse Adoptium/jdk-8.0.472.8-hotspot/bin/javap.exe'


def main():
    targets = {
        WINDOW + 'TimeWindowScanner': [('scanChangedRange(', 'SampledStateRuns.accept:')],
        WINDOW + 'CombinationWindowCalculator': [('calculate(', 'WindowIntervals.total:'), ('calculate(', 'WindowIntervals.intersect:')],
        'com.tx.alg.OrbitCalculator': [('analyzeLaunchOffsetV2(', 'SampledStateRuns.accept:'),
            ('visibilityWindows(', 'SampledStateRuns.accept:'), ('satelliteVisibilityWindows(', 'SampledStateRuns.accept:')],
        'com.tx.alg.OrbitCalculator$LaunchOffsetCalcResult': [('totalObservableDurationSec(', 'WindowIntervals.total:')],
        WINDOW + 'SampledStateRuns': [], WINDOW + 'SampledStateRuns$Segment': [],
        WINDOW + 'WindowIntervals': [], WINDOW + 'WindowIntervals$Totals': [],
    }
    evidence = ROOT / 'verification/T19'
    report = []
    jar = ROOT / 'backend/target/space-target-visible-1.0.0-SNAPSHOT.jar'
    with zipfile.ZipFile(jar) as archive:
        for owner, calls in targets.items():
            result = subprocess.run([JAVAP, '-J-Dfile.encoding=UTF-8', '-classpath', str(CLASSES), '-c', '-p', owner],
                capture_output=True, text=True, encoding='utf-8', check=True).stdout
            (evidence / (owner.rsplit('.', 1)[-1] + '-bytecode.log')).write_text(result, encoding='utf-8')
            blocks = re.split(r'\n(?=  (?:public|private|protected) )', result)
            for name, call in calls:
                matching = [block for block in blocks if name in block.split('\n', 1)[0]]
                assert matching and any(call in block for block in matching), (owner, name, call)
                report.append(dict(owner=owner, method=name, calls=call, verified=True))
            relative = owner.replace('.', '/') + '.class'
            assert archive.read('BOOT-INF/classes/' + relative) == (CLASSES / relative).read_bytes(), owner
    space_abi = []
    for name in ('TimeInterval', 'TimeWindowScanner', 'TimeWindowScanner$ScanStatistics', 'WindowNormalizer',
                 'CombinationWindowCalculator', 'StateInterval', 'CombinationWindow', 'CombinationConstraint'):
        def abi(path):
            return subprocess.run([JAVAP, '-J-Dfile.encoding=UTF-8', '-classpath', str(path), '-public', '-s', WINDOW+name],
                capture_output=True, text=True, encoding='utf-8', check=True).stdout
        before = abi(ROOT / 'verification/runtime/T04/space-java8-validation/target/classes')
        after = abi(CLASSES)
        assert before == after, name
        space_abi.append(dict(type=WINDOW+name, identical=True))
    summary = dict(passed=True, jarSha256=hashlib.sha256(jar.read_bytes()).hexdigest(), calls=report,
        classesEqualPackagedBytes=True, spacePublicAbi=space_abi,
        allocationScope='状态记录每扫描/载荷一个，非空段关闭创建快照，累计创建Totals；无每点时间装箱。未做profiling。')
    (evidence / 'shared-production-calls.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
