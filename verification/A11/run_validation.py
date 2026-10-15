"""A11 重构验证：隔离编译、数值黄金对照及公开签名核对，不覆盖历史证据。"""
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
BASE_OUT = ROOT / 'verification/A11'
BASE_RUN = ROOT / 'verification/runtime/A11'
ROUND = os.environ.get('A11_ROUND', '')
OUT = BASE_OUT / ROUND
RUN = BASE_RUN / ROUND
JAVA = Path('C:/Program Files/Eclipse Adoptium/jdk-8.0.472.8-hotspot')
FINAL = RUN / ('candidate' if ROUND else 'after-final-v2')
BEFORE = BASE_RUN / 'before'


def save(name, value):
    (OUT / (name + '.json')).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


def execute(name, argv, cwd=ROOT):
    """命令输出按原字节保存；元信息记录参数、耗时和退出码。"""
    sys.stdout.reconfigure(encoding='utf-8')
    stdout, stderr = OUT / (name + '-stdout.log'), OUT / (name + '-stderr.log')
    if stdout.exists() or stderr.exists():
        raise FileExistsError('拒绝覆盖已有命令证据：' + name)
    env = dict(os.environ, JAVA_HOME=str(JAVA), PYTHONIOENCODING='utf-8', PYTHONDONTWRITEBYTECODE='1')
    env['PATH'] = str(JAVA / 'bin') + os.pathsep + env['PATH']
    start = time.time()
    result = subprocess.run(list(map(str, argv)), cwd=cwd, env=env, capture_output=True)
    stdout.write_bytes(result.stdout)
    stderr.write_bytes(result.stderr)
    save(name + '-command', dict(argv=list(map(str, argv)), cwd=str(cwd), startedUnix=start,
         durationSec=time.time() - start, exitCode=result.returncode,
         stdoutSha256=hashlib.sha256(result.stdout).hexdigest(), stderrSha256=hashlib.sha256(result.stderr).hexdigest()))
    print(name, 'exit', result.returncode, flush=True)
    if result.returncode:
        print(result.stdout[-5000:].decode('utf-8', errors='replace'), flush=True)
        print(result.stderr[-3000:].decode('utf-8', errors='replace'), flush=True)
    result.check_returncode()


def prepare(stage):
    """从当前实际源码复制独立 backend；不带 target、服务配置或运行文件。"""
    backend = RUN / stage / 'backend'
    backend.mkdir(parents=True)
    for directory in ('src', 'orekit-data'):
        shutil.copytree(ROOT / 'backend' / directory, backend / directory)
    shutil.copy2(ROOT / 'backend/pom.xml', backend / 'pom.xml')
    # 已有数值/HTTP测试按 ../test-data 读取参考文件，隔离构建也需保留相同相对结构。
    shutil.copytree(ROOT / 'test-data', RUN / stage / 'test-data')
    files = [dict(path=p.relative_to(backend).as_posix(), sha256=hashlib.sha256(p.read_bytes()).hexdigest())
             for p in sorted(backend.rglob('*')) if p.is_file()]
    save(stage + '-source', files)
    print('prepared', stage, len(files), flush=True)


def build(stage):
    argv = [shutil.which('mvn.cmd'), '-B', '-Dstyle.color=never']
    if stage == 'before':
        argv.append('-DskipTests')
    argv.append('package')
    execute(stage + '-maven', argv, RUN / stage / 'backend')
    if stage != 'before':
        totals = dict(tests=0, failures=0, errors=0, skipped=0)
        reports = []
        for path in sorted((RUN / stage / 'backend/target/surefire-reports').glob('TEST-*.xml')):
            root = ET.parse(path).getroot()
            counts = {key: int(root.attrib[key]) for key in totals}
            reports.append(dict(file=path.name, **counts))
            for key, count in counts.items():
                totals[key] += count
        save('test-summary', dict(totals=totals, reports=reports))
        assert totals['tests'] > 0 and totals['failures'] == totals['errors'] == 0, totals
        print(totals, flush=True)


def numeric():
    """复用已验收的15/9/21完整输出比较流程，仅改变本轮证据和运行目录。"""
    sys.path.insert(0, str(ROOT / 'verification/A01'))
    from acceptance_shared import Acceptance
    driver = Acceptance(final=True)
    driver.OUT, driver.RUN, driver.CLEAN = OUT, RUN, FINAL
    driver.execute = execute
    driver.numeric()


def abi():
    """旧版与重构前公开声明均需保留；私有实现移动不作为对外契约。"""
    sys.path.insert(0, str(ROOT / 'verification/T10'))
    from verify_java_contract import inspect, ORIGINAL, BASE_TYPES
    reports = []
    for label, baseline in [('original', ORIGINAL), ('before', BEFORE / 'backend/target/classes')]:
        names = []
        for base in [*BASE_TYPES, 'com.tx.alg.api.TargetTrajectoryFitController']:
            stem = baseline / base.replace('.', '/')
            names.extend(p.relative_to(baseline).as_posix().removesuffix('.class').replace('/', '.')
                         for p in sorted(stem.parent.glob(stem.name + '*.class')))
        before = inspect(baseline, names)
        public = [re.search(r'(?:class|interface|enum)\s+([^\s<{]+)', h)[1] for h in before]
        after = inspect(FINAL / 'backend/target/classes', public)
        differences = [dict(type=h, missing=list((expected - after.get(h, {})).elements()))
                       for h, expected in before.items() if h not in after or expected - after[h]]
        reports.append(dict(baseline=label, publicTypes=len(before),
                       declarationLinesIncludingDescriptors=sum(sum(v.values()) for v in before.values()), differences=differences))
    save('abi-compare', dict(passed=all(not r['differences'] for r in reports), comparisons=reports))
    assert all(not r['differences'] for r in reports), reports
    print('ABI passed', [(r['baseline'], r['publicTypes']) for r in reports], flush=True)


def serialization():
    """同一探针分别编译绑定重构前后class，再逐字节比较完整产物集合。"""
    dependencies = (RUN / 'ballistic-runner/classpath.txt').read_text().strip()
    for stage in ('before', 'after'):
        compiled = RUN / ('probe-' + stage)
        compiled.mkdir()
        classpath = str((BEFORE if stage == 'before' else FINAL) / 'backend/target/classes') + os.pathsep + dependencies
        execute('serialization-' + stage + '-compile', [JAVA / 'bin/javac.exe', '-encoding', 'UTF-8', '-cp',
                classpath, '-d', compiled, BASE_OUT / 'SerializationProbe.java'])
        execute('serialization-' + stage + '-run', [JAVA / 'bin/java.exe', '-Dfile.encoding=UTF-8',
                '-Djava.awt.headless=true', '-cp', str(compiled) + os.pathsep + classpath,
                'com.tx.alg.SerializationProbe', RUN / ('serialization-' + stage), RUN / 'ballistic-runner'])
    before, after = RUN / 'serialization-before', RUN / 'serialization-after'
    left = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in before.iterdir()}
    right = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in after.iterdir()}
    differences = [name for name in left.keys() | right.keys() if left.get(name) != right.get(name)]
    save('serialization-compare', dict(passed=not differences, files=len(left), differences=differences,
         beforeSha256=left, afterSha256=right, comparison='原字节SHA256；含双includeBest、中文、真实请求与人工边界'))
    assert not differences, differences
    print('serialization bytes passed', len(left), flush=True)


def bindings():
    """检查实际源码、构建产物、测试统计及原字节日志，避免报告绑定过期候选。"""
    from zipfile import ZipFile
    manifest = json.loads((OUT / 'candidate-source.json').read_text(encoding='utf-8'))
    differences = []
    for entry in manifest:
        for base in (ROOT / 'backend', FINAL / 'backend'):
            if hashlib.sha256((base / entry['path']).read_bytes()).hexdigest() != entry['sha256']:
                differences.append(str(base / entry['path']))
    jar = FINAL / 'backend/target/space-target-visible-1.0.0-SNAPSHOT.jar'
    with ZipFile(jar) as archive:
        classes = [name for name in archive.namelist() if name.startswith('BOOT-INF/classes/') and name.endswith('.class')]
        for name in classes:
            if archive.read(name) != (FINAL / 'backend/target/classes' / name[len('BOOT-INF/classes/'):]).read_bytes():
                differences.append(name)
    commands = list(OUT.glob('*-command.json'))
    for metadata in commands:
        data = json.loads(metadata.read_text(encoding='utf-8'))
        name = metadata.name.removesuffix('-command.json')
        for stream in ('stdout', 'stderr'):
            path = OUT / (name + '-' + stream + '.log')
            if hashlib.sha256(path.read_bytes()).hexdigest() != data[stream + 'Sha256']:
                differences.append(str(path))
    for name in ('abi-compare', 'numeric-summary', 'serialization-compare'):
        assert json.loads((OUT / (name + '.json')).read_text(encoding='utf-8'))['passed'], name
    tests = json.loads((OUT / 'test-summary.json').read_text(encoding='utf-8'))['totals']
    assert tests == dict(tests=351, failures=0, errors=0, skipped=1), tests
    save('bindings', dict(passed=not differences, sourceFiles=len(manifest), jarClasses=len(classes),
         commandLogs=len(commands) * 2, differences=differences, jarSha256=hashlib.sha256(jar.read_bytes()).hexdigest()))
    assert not differences, differences
    print('bindings passed', len(manifest), len(classes), flush=True)


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    OUT.mkdir(exist_ok=True)
    RUN.mkdir(exist_ok=True)
    action = sys.argv[1]
    if action in ('prepare', 'build'):
        globals()[action](sys.argv[2])
    else:
        globals()[action]()
