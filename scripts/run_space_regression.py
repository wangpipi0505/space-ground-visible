"""T07 同一真实 Spring 驱动双跑：固定物理条件，比较全部数值、顺序与报告文本。"""
from pathlib import Path
import json
import os
import shutil
import sys
from run_baseline import ROOT, JAVA_HOME, run, compare


def main():
    """原验证副本只读；输出和浏览器隔离库均位于 verification/runtime/T07。"""
    sys.stdout.reconfigure(encoding='utf-8')
    runtime = ROOT / 'verification/runtime/T07'
    runtime.mkdir(parents=True, exist_ok=True)
    evidence = ROOT / 'verification/T07'
    dependencies = (ROOT / 'verification/runtime/T04/space-runner/classpath.txt').read_text().strip()
    for label, backend in [('original', ROOT / 'verification/runtime/T04/space-java8-validation'),
                           ('migrated', ROOT / 'backend')]:
        classpath = str(backend / 'target/classes') + os.pathsep + dependencies
        runner = runtime / label
        runner.mkdir(exist_ok=True)
        run([str(JAVA_HOME / 'bin/javac.exe'), '-encoding', 'UTF-8', '-cp', classpath,
             '-d', str(runner), str(evidence / 'SpaceRegression.java')], ROOT, evidence / (label + '-compile.log'))
        output = runtime / (label + '-actual')
        output.mkdir(exist_ok=True)
        arguments = [str(JAVA_HOME / 'bin/java.exe'), '-Dfile.encoding=UTF-8', '-Djava.awt.headless=true',
                     '-Dorekit.data.path=' + str(ROOT / 'verification/baseline-copies/space/backend/orekit-data'),
                     '-cp', str(runner) + os.pathsep + classpath, 'SpaceRegression', str(output)]
        if label == 'migrated':
            arguments.append('jdbc:h2:file:' + (runtime / 'browser-fixture').as_posix()
                             + ';MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1')
        run(arguments, ROOT, evidence / (label + '-run.log'))
        print(label + ' executed')
    differences = compare(runtime / 'original-actual', runtime / 'migrated-actual')
    # 原九文件复制至专属目录，再严格比较完整文件集合；不得过滤比较器的差异。
    legacy_actual = runtime / 'enhanced-legacy-actual'
    legacy_actual.mkdir(exist_ok=True)
    for source in (ROOT / 'test-data/baseline/space').glob('*.json'):
        shutil.copy2(runtime / 'migrated-actual' / source.name, legacy_actual / source.name)
    legacy = compare(ROOT / 'test-data/baseline/space', legacy_actual)
    report = dict(passed=not differences and not legacy, enhancedDifferences=differences,
                  originalGoldenDifferences=legacy)
    (evidence / 'compare.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False))
    return bool(differences or legacy)


if __name__ == '__main__':
    sys.exit(main())
