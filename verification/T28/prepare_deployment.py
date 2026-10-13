"""测试正式打包脚本并启动独立冻结包；实际运行入口依旧从 Boot JAR 加载。"""
from pathlib import Path
import json
import os
import shutil
import subprocess
import sys
import time
sys.path.insert(0, str(Path(__file__).resolve().parent))
from run_clean import ROOT, CLEAN, EVIDENCE, JAVA, execute

RUNTIME = ROOT / 'verification/runtime/T28'
PACKAGE = RUNTIME / 'deployment-package-v2'
SERVER = RUNTIME / 'independent-server'

if __name__ == '__main__':
    shutil.copytree(ROOT / 'deploy', CLEAN / 'deploy', dirs_exist_ok=True)
    code = execute('package-script', ['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', str(CLEAN / 'deploy/package.ps1'), '-Destination', str(PACKAGE)], ROOT)
    assert not code
    SERVER.mkdir()
    loader = RUNTIME / 'managed-classes'
    loader.mkdir()
    dependencies = (ROOT / 'verification/runtime/T04/space-runner/classpath.txt').read_text().strip()
    assert not execute('managed-compile', [str(JAVA / 'bin/javac.exe'), '-encoding', 'UTF-8', '-cp', str(CLEAN / 'backend/target/classes') + os.pathsep + dependencies, '-d', str(loader), str(ROOT / 'verification/T26/ManagedServer.java')], ROOT)
    database = SERVER / 'database'
    database.mkdir()
    # 复制仅测试用途的隔离 H2 fixture，不复制原工程数据库或任何生产结果。
    shutil.copy2(ROOT / 'verification/runtime/T27/final-regression/browser-fixture.mv.db', database / 'test-fixture.mv.db')
    arguments = [str(JAVA / 'bin/java.exe'), '-Dfile.encoding=UTF-8', '-Djava.awt.headless=true', '-Duser.timezone=Asia/Shanghai', '-Dt26.control=' + str(SERVER), '-Dloader.path=' + str(loader), '-Dloader.main=ManagedServer', '-Dorekit.data.path=' + str(PACKAGE / 'orekit-data'), '-cp', str(PACKAGE / 'backend/server.jar'), 'org.springframework.boot.loader.PropertiesLauncher', '--spring.profiles.active=demo', '--server.port=18130', '--spring.datasource.url=jdbc:h2:file:' + (database / 'test-fixture').as_posix() + ';MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1', '--ballistic.files.root=' + str(SERVER / 'files'), '--logging.file.name=' + str(SERVER / 'backend.log'), '--space.visibility.web.allowed-origins=http://127.0.0.1:15130']
    out = (SERVER / 'stdout.log').open('wb'); err = (SERVER / 'stderr.log').open('wb')
    process = subprocess.Popen(arguments, cwd=SERVER, stdout=out, stderr=err, creationflags=subprocess.CREATE_NO_WINDOW)
    (SERVER / 'command.json').write_text(json.dumps(dict(pid=process.pid, executable=arguments[0], arguments=arguments[1:]), ensure_ascii=False, indent=2), encoding='utf-8')
    for _ in range(80):
        if (SERVER / 'READY').exists(): break
        assert process.poll() is None
        time.sleep(0.5)
    assert (SERVER / 'READY').exists()
    gateway_args = ['node.exe', str(EVIDENCE / 'static-validation-gateway.cjs'), str(PACKAGE / 'frontend'), 'http://127.0.0.1:18130', '15130']
    gateway = subprocess.Popen(gateway_args, cwd=SERVER, stdout=(SERVER / 'gateway-stdout.log').open('wb'), stderr=(SERVER / 'gateway-stderr.log').open('wb'), creationflags=subprocess.CREATE_NO_WINDOW)
    (SERVER / 'gateway-command.json').write_text(json.dumps(dict(pid=gateway.pid, arguments=gateway_args), ensure_ascii=False), encoding='utf-8')
    print('server', process.pid, 'gateway', gateway.pid)
