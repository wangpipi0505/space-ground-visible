"""A17 分层整理验证：隔离构建，命令原始输出保留，历史参考只读。"""
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'verification/A17'
RUN = ROOT / 'verification/runtime/A17'
JAVA = Path('C:/Program Files/Eclipse Adoptium/jdk-8.0.472.8-hotspot')


def save(name, data):
    (OUT / (name + '.json')).write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def execute(name, argv, cwd=ROOT):
    """独立命令失败立即停止；不覆盖同名证据。"""
    stdout, stderr = OUT / (name + '-stdout.txt'), OUT / (name + '-stderr.txt')
    assert not stdout.exists() and not stderr.exists(), name
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
        print(result.stdout[-6000:].decode('utf-8', errors='replace'), flush=True)
        print(result.stderr[-3000:].decode('utf-8', errors='replace'), flush=True)
    result.check_returncode()


def prepare(stage):
    """副本采用实际当前源字节，不复用 target 或服务配置。"""
    backend = RUN / stage / 'backend'
    backend.mkdir(parents=True)
    for directory in ('src', 'orekit-data'):
        shutil.copytree(ROOT / 'backend' / directory, backend / directory)
    shutil.copy2(ROOT / 'backend/pom.xml', backend / 'pom.xml')
    shutil.copytree(ROOT / 'test-data', RUN / stage / 'test-data')
    files = [dict(path=p.relative_to(backend).as_posix(), sha256=hashlib.sha256(p.read_bytes()).hexdigest())
             for p in sorted(backend.rglob('*')) if p.is_file()]
    save(stage + '-source', files)


def build(stage):
    execute(stage + '-maven', [shutil.which('mvn.cmd'), '-B', '-Dstyle.color=never', 'package'], RUN / stage / 'backend')
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    reports = []
    for path in sorted((RUN / stage / 'backend/target/surefire-reports').glob('TEST-*.xml')):
        root = ET.parse(path).getroot()
        counts = {key: int(root.attrib[key]) for key in totals}
        reports.append(dict(file=path.name, **counts))
        for key, count in counts.items():
            totals[key] += count
    save(stage + '-test-summary', dict(totals=totals, reports=reports))
    assert totals['tests'] and totals['failures'] == totals['errors'] == 0, totals
    print(totals, flush=True)


def adapt_probe(text):
    """只适配 Java 包绑定和显式加载端口，冻结请求与计算语句保持。"""
    base = 'com.runisys.orbit.spacevisibility.'
    text = text.replace('com.tx.alg.trajectory', base + 'domain.ballistic')
    text = text.replace('com.tx.alg.api', base + 'api.ballistic').replace('com.tx.alg', base + 'domain.ballistic')
    for old, new in [('FitRequest', 'FitInput'), ('SimulationRequest', 'SimulationInput'), ('RetimeTrajectoryRequest', 'RetimeInput')]:
        text = text.replace('.' + old, '.' + new)
    text = text.replace('import ' + base + 'api.ballistic.OrbitVisibilityController;',
        'import ' + base + 'api.ballistic.BallisticVisibilityController;\nimport ' + base + 'application.ballistic.BallisticVisibilityService;')
    text = text.replace('new OrbitVisibilityController()', 'new BallisticVisibilityController(new BallisticVisibilityService())')
    text = text.replace('SensorVisibilityConfig.legacySimpleCone(input.getSensorFovDeg()), null);',
        'SensorVisibilityConfig.legacySimpleCone(input.getSensorFovDeg()), null,\n                    ' +
        base + 'infrastructure.files.TrajectoryFileResolver::parseBallisticCsvWithReference);')
    return text


def probes():
    for source, destination in [('verification/T04/BallisticBaseline.java', 'BallisticBaseline.java'),
                                ('verification/A11/SerializationProbe.java', 'SerializationProbe.java')]:
        (OUT / destination).write_text(adapt_probe((ROOT / source).read_text(encoding='utf-8')), encoding='utf-8')


def numeric(stage):
    """复用已验收的严格 Decimal/CSV 比较器，参考输入仅核对不覆盖。"""
    sys.path.insert(0, str(ROOT / 'verification/A01'))
    from acceptance_shared import Acceptance
    target = OUT / ('numeric-' + stage)
    target.mkdir()
    runtime = RUN / ('numeric-' + stage)
    runtime.mkdir()
    driver = Acceptance(final=True)
    driver.OUT, driver.RUN, driver.CLEAN = target, runtime, RUN / stage
    def command(name, argv, cwd=ROOT):
        return execute('numeric-' + stage + '-' + name, argv, cwd)
    driver.execute = command
    driver.numeric()


def serialization(stage):
    dependencies = (RUN / ('numeric-' + stage) / 'ballistic-runner/classpath.txt').read_text().strip()
    runtime = RUN / ('serialization-' + stage)
    runtime.mkdir()
    cp = str(RUN / stage / 'backend/target/classes') + os.pathsep + dependencies
    source = ROOT / ('verification/A11/SerializationProbe.java' if stage == 'before' else 'verification/A17/SerializationProbe.java')
    name = ('com.tx.alg.' if stage == 'before' else 'com.runisys.orbit.spacevisibility.domain.ballistic.') + 'SerializationProbe'
    execute('serialization-' + stage + '-compile', [JAVA / 'bin/javac.exe', '-encoding', 'UTF-8', '-cp', cp, '-d', runtime, source])
    execute('serialization-' + stage + '-run', [JAVA / 'bin/java.exe', '-Dfile.encoding=UTF-8', '-Djava.awt.headless=true',
        '-cp', str(runtime) + os.pathsep + cp, name, runtime / 'output', RUN / ('numeric-' + stage) / 'ballistic-runner'])


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    OUT.mkdir(exist_ok=True)
    action, stage = sys.argv[1:3]
    if action == 'prepare':
        prepare(stage)
    elif action == 'build':
        build(stage)
    elif action == 'probes':
        probes()
    elif action == 'numeric':
        numeric(stage)
    elif action == 'serialization':
        serialization(stage)
