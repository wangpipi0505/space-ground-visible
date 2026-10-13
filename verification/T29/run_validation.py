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
RUNTIME = ROOT / 'verification/runtime/T29'
EVIDENCE = ROOT / 'verification/T29'
CLEAN = RUNTIME / 'clean-source'
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
    if mode == 'prepare':
        assert not CLEAN.exists()
        names = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode('utf-8').split('\0')
        for name in filter(None, names):
            if name.startswith(('backend/', 'test-data/')):
                target = CLEAN / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(ROOT / name, target)
        execute('clean-package', ['mvn.cmd', '-B', '-DskipTests', 'package'], CLEAN / 'backend')
        # 代表性回归覆盖受文档核对的标准载荷、传播、CSV与取消，完整矩阵沿用T27/T28对应证据。
    elif mode == 'checks':
        names = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode('utf-8').split('\0')
        for name in filter(None, names):
            if name.startswith('backend/src/') and 'orekit-data' not in Path(name).parts:
                target = CLEAN / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(ROOT / name, target)
        selected = 'OrbitPropagationToolTest,SpaceOrbitStateProviderTest,BallisticOrbitBindingTest,StandardPayloadMathTest,StandardPayloadSolarContextTest,StandardPayloadValidationTest,StandardPayloadRequestTest'
        execute('selected-java-package', ['mvn.cmd', '-B', '-Dtest=' + selected, 'package'], CLEAN / 'backend')
        execute('frontend-test', ['pnpm.cmd', 'test'], ROOT / 'frontend')
        execute('frontend-build', ['pnpm.cmd', 'build'], ROOT / 'frontend')
    elif mode == 'start':
        server = server_directory
        assert not server.exists()
        server.mkdir(parents=True)
        loader = RUNTIME / 'managed-classes'
        loader.mkdir(exist_ok=True)
        dependencies = (ROOT / 'verification/runtime/T04/space-runner/classpath.txt').read_text(encoding='utf-8').strip()
        execute('managed-compile', [str(JAVA / 'bin/javac.exe'), '-encoding', 'UTF-8', '-cp',
                str(CLEAN / 'backend/target/classes') + os.pathsep + dependencies,
                '-d', str(loader), str(ROOT / 'verification/T26/ManagedServer.java')])
        jar = CLEAN / 'backend/target/space-target-visible-1.0.0-SNAPSHOT.jar'
        # 原T27/T28隔离fixture含两颗观测星，demo仍只补缺项；原版/生产库不读取。
        if not stage:
            shutil.copy2(ROOT / 'verification/runtime/T27/final-regression/browser-fixture.mv.db', server / 'demo.mv.db')
        arguments = [str(JAVA / 'bin/java.exe'), '-Djava.awt.headless=true', '-Dfile.encoding=UTF-8',
                     '-Duser.timezone=Asia/Shanghai', '-Dt26.control=' + str(server),
                     '-Dloader.path=' + str(loader), '-Dloader.main=ManagedServer',
                     '-Dorekit.data.path=' + str(ROOT / 'backend/orekit-data'), '-cp', str(jar),
                     'org.springframework.boot.loader.PropertiesLauncher', '--spring.profiles.active=demo',
                     '--server.port=18131', '--spring.datasource.url=jdbc:h2:file:' + (server / 'demo').as_posix()
                     + ';MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1',
                     '--ballistic.files.root=' + str(server / 'files'), '--logging.file.name=' + str(server / 'backend.log')]
        process = subprocess.Popen(arguments, cwd=server, stdout=(server / 'stdout.log').open('wb'),
                                   stderr=(server / 'stderr.log').open('wb'), creationflags=subprocess.CREATE_NO_WINDOW)
        (EVIDENCE / ('service-binding' + label + '.json')).write_text(json.dumps(dict(pid=process.pid, arguments=arguments,
                jarSha256=hashlib.sha256(jar.read_bytes()).hexdigest(), sourceClasses='clean-source/backend/target/classes',
                port=18131, independentDemoFixture=True, freshDemo=bool(stage)), ensure_ascii=False, indent=2), encoding='utf-8')
        for _ in range(100):
            if (server / 'READY').exists():
                break
            assert process.poll() is None
            time.sleep(0.3)
        assert (server / 'READY').exists()
        print('READY', process.pid)
    elif mode == 'http':
        output = RUNTIME / ('http-example' + label)
        execute('http-example' + label, ['python', str(ROOT / 'docs/examples/http_flow.py'), '--base-url', 'http://127.0.0.1:18131', '--output-dir', str(output)])
        shutil.copy2(output / 'summary.json', EVIDENCE / ('http-example' + label + '-summary.json'))
    elif mode == 'tools':
        for name, script, extra in [('plain-space', 'scripts/verify_orbit_tool.ps1', ['-ValidationTask', 'T09']),
                                    ('plain-ballistic', 'scripts/verify_ballistic_tool.ps1', [])]:
            execute(name, ['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
                    str(ROOT / script), '-JavaHome', str(JAVA), '-EvidenceTask', 'T29', *extra])
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
                      source='只读冻结原class与最终T29干净package的javap -public -s', differences=differences)
        (EVIDENCE / 'legacy-abi-compare.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        assert not differences
        print('legacy ABI', report['publicTypes'], report['declarationLinesIncludingDescriptors'])
    elif mode == 'close':
        server = server_directory
        (server / 'STOP').write_text('normal close', encoding='utf-8')
        pid = json.loads((EVIDENCE / ('service-binding' + label + '.json')).read_text(encoding='utf-8'))['pid']
        deadline = time.monotonic() + 40
        while time.monotonic() < deadline:
            status = subprocess.run(['tasklist.exe', '/FI', 'PID eq ' + str(pid), '/NH'], capture_output=True)
            if str(pid).encode('ascii') not in status.stdout:
                break
            time.sleep(0.3)
        else:
            raise AssertionError('自然关闭未退出')
        output = (server / 'stdout.log').read_bytes() + (server / 'stderr.log').read_bytes()
        (EVIDENCE / ('service-start-stop' + label + '.txt')).write_bytes(output)
        assert b'T26_CONTEXT_CLOSE_END uncaught=0' in output and b'T26_MAIN_RETURN' in output
        (EVIDENCE / ('service-close' + label + '.json')).write_text(json.dumps(dict(pid=pid, naturalExit=True, uncaught=0)), encoding='utf-8')
        print('closed', pid)
