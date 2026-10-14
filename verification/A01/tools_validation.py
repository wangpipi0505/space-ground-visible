"""隔离T29构建/演示服务/普通Java工具验证；不覆盖历史输出或根服务端口。

prepare从实际规范化源码复制干净backend，再Java8打包；start使用正式JAR和
验收控制器ManagedServer，只以控制文件自然关闭，不给产品增加退出HTTP接口。
"""
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
RUNTIME = ROOT / 'verification/runtime/A01'
EVIDENCE = ROOT / 'verification/A01'
CLEAN = RUNTIME / 'clean-source'
if os.environ.get('A01_FINAL'):
    CLEAN = RUNTIME / 'final-candidate/final-source-v3'
    EVIDENCE = ROOT / 'verification/A01/final'
JAVA = Path('C:/Program Files/Eclipse Adoptium/jdk-8.0.472.8-hotspot')


def execute(name, arguments, cwd=ROOT):
    env = dict(os.environ, JAVA_HOME=str(JAVA), PYTHONIOENCODING='utf-8')
    env['PATH'] = str(JAVA / 'bin') + os.pathsep + env['PATH']
    result = subprocess.run(arguments, cwd=cwd, env=env, capture_output=True)
    (EVIDENCE / (name + '.txt')).write_bytes(result.stdout + result.stderr)
    (EVIDENCE / (name + '-exit.json')).write_text(json.dumps(dict(command=arguments, cwd=str(cwd), exitCode=result.returncode)), encoding='utf-8')
    print(name, result.returncode, flush=True)
    result.check_returncode()


if __name__ == '__main__':
    mode = sys.argv[1]
    stage = sys.argv[2] if len(sys.argv) > 2 else ''
    label = '-' + stage if stage else ''
    server_directory = RUNTIME / ('server' + label)
    if mode == 'tools':
        for name, script, extra in [('plain-space', 'scripts/verify_orbit_tool.ps1', ['-ValidationTask', 'T09']),
                                    ('plain-ballistic', 'scripts/verify_ballistic_tool.ps1', [])]:
            execute(name, ['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
                    str(ROOT / script), '-JavaHome', str(JAVA), '-EvidenceTask', 'A01', *extra])
    elif mode == 'abi':
        # 复用既有javap读取器，证据只写T29，不覆盖T10历史报告。
        import re
        sys.path.insert(0, str(ROOT / 'verification/T10'))
        from verify_java_contract import inspect, ORIGINAL, BASE_TYPES
        names = []
        for base_type in [*BASE_TYPES, 'com.tx.alg.api.TargetTrajectoryFitController']:
            stem = ORIGINAL / base_type.replace('.', '/')
            names.extend(path.relative_to(ORIGINAL).as_posix().removesuffix('.class').replace('/', '.')
                         for path in sorted(stem.parent.glob(stem.name + '*.class')))
        before = inspect(ORIGINAL, names)
        public_names = [re.search(r'(?:class|interface|enum)\s+([^\s<{]+)', header).group(1) for header in before]
        after = inspect(CLEAN / 'backend/target/classes', public_names)
        differences = []
        for header, expected in before.items():
            missing = expected - after.get(header, {}) if header in after else expected
            if missing:
                differences.append(dict(type=header, missing=list(missing.elements())))
        report = dict(passed=not differences, publicTypes=len(before),
                      declarationLinesIncludingDescriptors=sum(sum(value.values()) for value in before.values()),
                      source='A01本轮新javap -public -s：只读冻结原class与最终隔离classes', currentClasses=str(CLEAN / 'backend/target/classes'), originalClasses=str(ORIGINAL), differences=differences)
        (EVIDENCE / 'legacy-abi-compare.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        assert not differences
        print('legacy ABI', report['publicTypes'], report['declarationLinesIncludingDescriptors'])
