"""T30 干净最终验收：仅写本项证据/runtime，不改产品源码、黄金或原目录。

阶段 prepare/build/numeric/package/http/original 独立执行并保存原字节输出。
导出来源为 Git HEAD；正式 Windows PowerShell5 构建/打包，HTTP绑定本轮包。
"""
from pathlib import Path
import hashlib
import io
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tarfile
import time
import xml.etree.ElementTree as ET
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'verification/T30'
RUN = ROOT / 'verification/runtime/T30'
CLEAN = RUN / 'clean-source'
PACKAGE = RUN / 'release'
JAVA = Path('C:/Program Files/Eclipse Adoptium/jdk-8.0.472.8-hotspot')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(name, value):
    (OUT / (name + '.json')).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def execute(name, arguments, cwd=ROOT):
    """stdout/stderr合并保留原字节，不经PowerShell文本重定向或截尾。"""
    env = dict(os.environ, JAVA_HOME=str(JAVA), PYTHONIOENCODING='utf-8')
    env['PATH'] = str(JAVA / 'bin') + os.pathsep + env['PATH']
    started = time.time()
    with (OUT / (name + '.txt')).open('wb') as stream:
        result = subprocess.run(arguments, cwd=cwd, env=env, stdout=stream, stderr=subprocess.STDOUT)
    save(name + '-exit', dict(command=arguments, cwd=str(cwd), exitCode=result.returncode,
                             durationSec=time.time() - started, stdoutSha256=sha(OUT / (name + '.txt'))))
    print(name, 'exit=', result.returncode, flush=True)
    result.check_returncode()


def prepare():
    """仅导出当前已提交对象，避免历史static/target/node_modules或工作树改动污染。"""
    assert not CLEAN.exists(), '拒绝覆盖已存在干净源码'
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT).decode().strip()
    archive = subprocess.check_output(['git', 'archive', '--format=tar', head], cwd=ROOT)
    RUN.mkdir(parents=True, exist_ok=True)
    CLEAN.mkdir()
    with tarfile.open(fileobj=io.BytesIO(archive)) as stream:
        stream.extractall(CLEAN, filter='data')
    files = [dict(path=p.relative_to(CLEAN).as_posix(), sha256=sha(p)) for p in sorted(CLEAN.rglob('*')) if p.is_file()]
    assert not (CLEAN / 'backend/src/main/resources/static').exists()
    assert not any(set(Path(i['path']).parts) & {'node_modules', 'target', 'baseline-copies'} for i in files)
    save('clean-source-manifest', dict(sourceCommit=head, archiveSha256=hashlib.sha256(archive).hexdigest(),
                                      fileCount=len(files), files=files))
    for name, command in [('java-version', [str(JAVA / 'bin/java.exe'), '-version']),
                          ('maven-version', ['mvn.cmd', '-version']), ('node-version', ['node.exe', '--version']),
                          ('pnpm-version', ['pnpm.cmd', '--version']),
                          ('powershell-version', ['powershell.exe', '-NoProfile', '-Command', '$PSVersionTable.PSVersion.ToString()'])]:
        execute(name, command)
    print('HEAD', head, 'exported files', len(files), flush=True)


def build():
    execute('build-all', ['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
                         str(CLEAN / 'deploy/build.ps1'), '-Component', 'all', '-JavaHome', str(JAVA)], CLEAN)
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    reports = []
    for p in sorted((CLEAN / 'backend/target/surefire-reports').glob('TEST-*.xml')):
        node = ET.parse(p).getroot()
        item = dict(file=p.name, sha256=sha(p), **{key: int(node.attrib[key]) for key in totals})
        reports.append(item)
        for key in totals:
            totals[key] += item[key]
    shutil.copytree(CLEAN / 'backend/target/surefire-reports', RUN / 'surefire-reports')
    text = (OUT / 'build-all.txt').read_text(encoding='utf-8', errors='replace')
    assert totals == dict(tests=347, failures=0, errors=0, skipped=1), totals
    assert re.search(r'Test Files\s+27 passed', text) and re.search(r'Tests\s+112 passed', text)
    assert 'vue-tsc -b && vite build' in text
    save('test-summary', dict(backend=totals, backendPassed=346, backendReports=reports,
                              frontendFiles=27, frontendTests=112, typeAndBuildExit=0,
                              lockSha256=sha(CLEAN / 'frontend/pnpm-lock.yaml')))


def numeric():
    """复用原驱动/Decimal比较器；阻止capture重写已冻结参考，全部输出为新目录。"""
    sys.path.insert(0, str(ROOT / 'scripts'))
    import run_baseline as baseline
    original_copy = baseline.shutil.copy2

    def safe_copy(source, destination, *args, **kwargs):
        dest = Path(destination)
        if dest.is_relative_to(ROOT / 'test-data'):
            assert Path(source).read_bytes() == dest.read_bytes(), '参考输入不符，禁止覆盖'
            return str(dest)
        return original_copy(source, destination, *args, **kwargs)

    baseline.shutil.copy2 = safe_copy
    ballistic_logs = OUT / 'ballistic'
    ballistic_logs.mkdir()
    baseline.capture(CLEAN / 'backend', RUN / 'ballistic-actual', RUN / 'ballistic-runner', ballistic_logs)
    dependencies = (ROOT / 'verification/runtime/T04/space-runner/classpath.txt').read_text().strip()
    execute('space9-capture', [sys.executable, str(ROOT / 'scripts/run_space_baseline.py'), 'capture',
                             '--classes', str(CLEAN / 'backend/target/classes'), '--classpath', dependencies,
                             '--orekit-data', str(CLEAN / 'backend/orekit-data'), '--output', str(RUN / 'space-actual'),
                             '--runtime', str(RUN / 'space-runner'), '--evidence', str(OUT / 'space')])
    outputs = {}
    for label, classes in [('original', ROOT / 'verification/runtime/T04/space-java8-validation/target/classes'),
                           ('current', CLEAN / 'backend/target/classes')]:
        runner = RUN / ('complete21-' + label + '-runner')
        runner.mkdir()
        actual = RUN / ('complete21-' + label + '-actual')
        actual.mkdir()
        cp = str(classes) + os.pathsep + dependencies
        execute('complete21-' + label + '-compile', [str(JAVA / 'bin/javac.exe'), '-encoding', 'UTF-8', '-cp', cp,
                 '-d', str(runner), str(ROOT / 'verification/T07/SpaceRegression.java')])
        execute('complete21-' + label + '-run', [str(JAVA / 'bin/java.exe'), '-Dfile.encoding=UTF-8',
                 '-Djava.awt.headless=true', '-Dorekit.data.path=' + str(CLEAN / 'backend/orekit-data'),
                 '-cp', str(runner) + os.pathsep + cp, 'SpaceRegression', str(actual)])
        outputs[label] = actual
    comparisons = []
    for name, expected, actual in [('ballistic15', ROOT / 'test-data/baseline/ballistic', RUN / 'ballistic-actual'),
                                  ('space9', ROOT / 'test-data/baseline/space', RUN / 'space-actual'),
                                  ('spaceComplete21', outputs['original'], outputs['current'])]:
        differences = baseline.compare(expected, actual)
        comparisons.append(dict(name=name, expectedFiles=sum(p.is_file() for p in expected.rglob('*')),
                                 actualFiles=sum(p.is_file() for p in actual.rglob('*')), differences=differences))
    save('numeric-summary', dict(passed=all(not i['differences'] for i in comparisons), comparisons=comparisons,
                                 comparator='原JSON Decimal结构/数值/顺序严格相等；CSV/报告逐字节，无新增容差'))
    assert all(not i['differences'] for i in comparisons), comparisons
    print('PASS strict numeric 15/9/21', flush=True)


def package():
    execute('package', ['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
                       str(CLEAN / 'deploy/package.ps1'), '-Destination', str(PACKAGE)], CLEAN)
    jar = PACKAGE / 'backend/server.jar'
    assert sha(jar) == sha(CLEAN / 'backend/target/space-target-visible-1.0.0-SNAPSHOT.jar')
    manifest = json.loads((PACKAGE / 'manifest.json').read_text(encoding='utf-8-sig'))
    actual = {p.relative_to(PACKAGE).as_posix(): sha(p) for p in PACKAGE.rglob('*') if p.is_file() and p.name != 'manifest.json'}
    assert {i['path']: i['sha256'] for i in manifest} == actual
    with ZipFile(jar) as z:
        prefix = 'BOOT-INF/classes/'
        names = [n for n in z.namelist() if n.startswith(prefix) and n.endswith('.class')]
        mismatch = [n for n in names if z.read(n) != (CLEAN / 'backend/target/classes' / n[len(prefix):]).read_bytes()]
        static = [n for n in z.namelist() if n.startswith(prefix + 'static/')]
        assert not mismatch and not static
        assert any('spring-boot-2.7.18.jar' in n for n in z.namelist())
        assert any('orekit-12.0.jar' in n for n in z.namelist())
    orekit = [p for p in (PACKAGE / 'orekit-data').rglob('*') if p.is_file()]
    assert len(orekit) == 349
    for i in json.loads((OUT / 'clean-source-manifest.json').read_text(encoding='utf-8'))['files']:
        assert sha(CLEAN / i['path']) == i['sha256'], i['path']
    save('package-bindings', dict(path=str(PACKAGE), jarSha256=sha(jar), manifestSha256=sha(PACKAGE / 'manifest.json'),
                                files=len(manifest), manifestMatchesEveryFile=True, classesCompared=len(names),
                                classesEqualOwnBuild=True, bundledStatic=static, orekitFiles=len(orekit),
                                frontendFiles=sum(p.is_file() for p in (PACKAGE / 'frontend').rglob('*')),
                                sourceFilesUnchanged=True, lockSha256=sha(CLEAN / 'frontend/pnpm-lock.yaml')))
    print('package', jar, sha(jar), flush=True)


def inherited():
    """核对T29去debug等价证据确实绑定当前源码，保留浏览器/压力证据继承边界。"""
    previous = json.loads((ROOT / 'verification/T29/source-after.json').read_text(encoding='utf-8'))
    for item in previous['files']:
        assert sha(CLEAN / item['path']) == item['sha256'], item['path']
    protected = json.loads((ROOT / 'verification/T29/source-before.json').read_text(encoding='utf-8'))['protected']
    for item in protected:
        assert sha(CLEAN / item['path']) == item['sha256'], item['path']
    target = RUN / 'no-debug-classes'
    target.mkdir(exist_ok=True)
    sources = sorted((CLEAN / 'backend/src/main/java').rglob('*.java'))
    argfile = RUN / 'javac-no-debug.args'
    argfile.write_text('\n'.join('"' + p.as_posix() + '"' for p in sources), encoding='utf-8')
    cp = (ROOT / 'verification/runtime/T29/compile-classpath.txt').read_text(encoding='utf-8').strip()
    if not any(target.rglob('*.class')):
        execute('compile-no-debug', [str(JAVA / 'bin/javac.exe'), '-encoding', 'UTF-8', '-g:none', '-cp', cp,
                                   '-d', str(target), '@' + str(argfile)])
    current = {p.relative_to(target).as_posix(): sha(p) for p in sorted(target.rglob('*.class'))}
    assert current == previous['classHashes']
    frontend = json.loads((ROOT / 'verification/T29/frontend-content-compare.json').read_text(encoding='utf-8'))
    # 此处sha256是转译/规范后的内容SHA，原字节绑定必须来自source-after.files。
    raw = {item['path']: item['sha256'] for item in previous['files']}
    for item in frontend['files']:
        assert sha(CLEAN / item['path']) == raw[item['path']], item['path']
    paths = ['verification/T29/source-after.json', 'verification/T29/frontend-content-compare.json',
             'verification/T29/legacy-abi-compare.json', 'verification/T27/验证报告.md', 'verification/T28/验证报告.md']
    save('inherited-evidence-bindings', dict(sourceFiles=len(previous['files']), javaSources=len(sources),
        noDebugClasses=len(current), noDebugMatchesT29=True, frontendContentFiles=len(frontend['files']),
        protectedFiles=len(protected), protectedUnchanged=True, evidence=[dict(path=p, sha256=sha(ROOT / p)) for p in paths],
        boundary='新完整build/package/15+9+21/HTTP属于T30；T27/T28浏览器/压力/正式启动脚本矩阵仅继承，不声称重跑'))
    print('PASS inherited bindings', len(current), 'no-debug classes;', len(frontend['files']), 'frontend files', flush=True)


def http():
    """新的demo/H2和外置runtime，从最终冻结JAR加载，finally真实自然关闭且检查退出码。"""
    server = RUN / 'server'
    server.mkdir()
    loader = RUN / 'managed-classes'
    loader.mkdir()
    dependencies = (ROOT / 'verification/runtime/T04/space-runner/classpath.txt').read_text().strip()
    execute('managed-compile', [str(JAVA / 'bin/javac.exe'), '-encoding', 'UTF-8', '-cp',
                str(CLEAN / 'backend/target/classes') + os.pathsep + dependencies,
                '-d', str(loader), str(ROOT / 'verification/T26/ManagedServer.java')])
    jar = PACKAGE / 'backend/server.jar'
    initial = sha(jar)
    arguments = [str(JAVA / 'bin/java.exe'), '-Djava.awt.headless=true', '-Dfile.encoding=UTF-8',
                 '-Duser.timezone=Asia/Shanghai', '-Dt26.control=' + str(server), '-Dloader.path=' + str(loader),
                 '-Dloader.main=ManagedServer', '-Dorekit.data.path=' + str(PACKAGE / 'orekit-data'), '-cp', str(jar),
                 'org.springframework.boot.loader.PropertiesLauncher', '--spring.profiles.active=demo', '--server.port=18132',
                 '--spring.datasource.url=jdbc:h2:file:' + (server / 'database/demo').as_posix()
                 + ';MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1',
                 '--ballistic.files.root=' + str(server / 'files'), '--logging.file.name=' + str(server / 'backend.log')]
    (server / 'database').mkdir()
    stdout = (server / 'stdout.log').open('wb')
    process = subprocess.Popen(arguments, cwd=server, stdout=stdout, stderr=subprocess.STDOUT,
                               creationflags=subprocess.CREATE_NO_WINDOW)
    save('service-binding', dict(pid=process.pid, command=arguments, jarSha256=initial, freshDemo=True, port=18132))
    try:
        deadline = time.monotonic() + 60
        while not (server / 'READY').exists():
            assert process.poll() is None, '服务启动失败'
            assert time.monotonic() < deadline, '服务READY超时'
            time.sleep(0.2)
        execute('http-flow', [sys.executable, str(CLEAN / 'docs/examples/http_flow.py'), '--base-url',
                             'http://127.0.0.1:18132', '--output-dir', str(RUN / 'http-example')])
        summary = json.loads((RUN / 'http-example/summary.json').read_text(encoding='utf-8'))
        save('http-summary', summary)
        assert summary['passed'] and len(summary['csvComparisons']) == 4
        from urllib.error import HTTPError
        from urllib.request import urlopen
        try:
            urlopen('http://127.0.0.1:18132/index.html').close()
            raise AssertionError('API包不应夹带前端index')
        except HTTPError as error:
            assert error.code == 404
            save('index-separation', dict(backendIndexStatus=error.code, packageFrontendIndex=sha(PACKAGE / 'frontend/index.html')))
        print('HTTP', len(summary['events']), 'CSV', len(summary['csvComparisons']), flush=True)
    finally:
        (server / 'STOP').write_text('T30 context.close', encoding='utf-8')
        code = process.wait(timeout=45)
        stdout.close()
        raw = (server / 'stdout.log').read_bytes()
        (OUT / 'service-start-stop.txt').write_bytes(raw)
        with socket.socket() as port:
            port.bind(('127.0.0.1', 18132))
        save('shutdown-summary', dict(pid=process.pid, naturalExitCode=code, contextCloseReturned=b'T26_CONTEXT_CLOSE_END uncaught=0' in raw,
                                     mainReturned=b'T26_MAIN_RETURN' in raw, uncaught=0 if b'uncaught=0' in raw else None,
                                     remainingNonDaemon=b'T26_NON_DAEMON_REMAINING' in raw, portRebind=True,
                                     jarUnchanged=sha(jar) == initial))
        assert code == 0 and b'T26_CONTEXT_CLOSE_END uncaught=0' in raw and b'T26_MAIN_RETURN' in raw
        assert b'T26_NON_DAEMON_REMAINING' not in raw and sha(jar) == initial


def original():
    comparisons = []
    for name, path in [('space', Path('G:/5-YG/target-visible-alg/space-target-visible-main-集成交付')),
                       ('ballistic', Path('G:/Code/Code-V3/alg/deliverables/alg-0723'))]:
        baseline = json.loads((ROOT / ('verification/T02/' + name + '-manifest.json')).read_text(encoding='utf-8-sig'))
        records = [dict(path=i['path'], expected=i['sha256'], actual=sha(path / i['path'])) for i in baseline]
        differences = [i for i in records if i['expected'] != i['actual']]
        comparisons.append(dict(name=name, root=str(path), files=len(records), differences=differences, records=records))
    save('original-directory-check', dict(total=sum(i['files'] for i in comparisons), comparisons=comparisons,
                                         passed=all(not i['differences'] for i in comparisons), action='只读重新hash，不覆写初始清单'))
    assert sum(i['files'] for i in comparisons) == 697 and all(not i['differences'] for i in comparisons)
    print('PASS original directory SHA 526+171=697', flush=True)


if __name__ == '__main__':
    OUT.mkdir(parents=True, exist_ok=True)
    sys.stdout.reconfigure(encoding='utf-8')
    globals()[sys.argv[1]]()
