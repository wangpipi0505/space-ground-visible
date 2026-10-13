"""T04 原空间真实业务基准；H2 隔离库、原 Spring Controller/传播，严格复用公共比较器。"""
import argparse
import json
from pathlib import Path
import os
import shutil
import sys
from run_baseline import ROOT, JAVA_HOME, run, compare


def main():
    """未来后端可传 --classes 和 --classpath；不替换 DTO 或窗口数值口径。"""
    sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['capture', 'compare'])
    parser.add_argument('--backend', type=Path, default=ROOT / 'verification/runtime/T04/space-java8-validation')
    parser.add_argument('--classes', type=Path)
    parser.add_argument('--classpath', help='Maven 依赖类路径字符串，包含 H2 测试依赖')
    parser.add_argument('--orekit-data', type=Path, default=ROOT / 'verification/baseline-copies/space/backend/orekit-data')
    parser.add_argument('--output', type=Path, default=ROOT / 'test-data/baseline/space')
    parser.add_argument('--actual', type=Path)
    args = parser.parse_args()
    if args.action == 'compare':
        if args.actual is None:
            parser.error('compare 需要 --actual')
        found = compare(args.output, args.actual)
        print(json.dumps(dict(passed=not found, differences=found), ensure_ascii=False, indent=2))
        return bool(found)
    runtime = ROOT / 'verification/runtime/T04/space-runner'
    evidence = ROOT / 'verification/T04'
    runtime.mkdir(parents=True, exist_ok=True)
    classes = args.classes.resolve() if args.classes else args.backend.resolve() / 'target/classes'
    dependencies = args.classpath
    if not dependencies:
        cp_file = runtime / 'classpath.txt'
        run([shutil.which('mvn.cmd') or shutil.which('mvn'), 'dependency:build-classpath',
             '-Dmdep.outputFile=' + str(cp_file)], args.backend.resolve(), evidence / 'space-runner-classpath.log')
        dependencies = cp_file.read_text().strip()
    classpath = str(classes) + os.pathsep + dependencies
    run([str(JAVA_HOME / 'bin/javac.exe'), '-encoding', 'UTF-8', '-cp', classpath, '-d', str(runtime),
         str(evidence / 'SpaceBaseline.java')], ROOT, evidence / 'space-runner-compile.log')
    run([str(JAVA_HOME / 'bin/java.exe'), '-Dfile.encoding=UTF-8', '-Djava.awt.headless=true',
         '-Dorekit.data.path=' + str(args.orekit_data.resolve()), '-cp',
         str(runtime) + os.pathsep + classpath, 'SpaceBaseline', str(args.output.resolve())],
        ROOT, evidence / 'space-runner.log')
    print('空间真实业务基准已生成：' + str(args.output))
    return 0


if __name__ == '__main__':
    sys.exit(main())
