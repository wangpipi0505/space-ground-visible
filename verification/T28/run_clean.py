"""T28 干净副本和命令原字节证据：只从 Git 跟踪对象复制，不携带已有构建产物。"""
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / 'verification/T28'
CLEAN = ROOT / 'verification/runtime/T28/clean-source'
JAVA = Path('C:/Program Files/Eclipse Adoptium/jdk-8.0.472.8-hotspot')


def execute(name, arguments, cwd):
    """不经过 PowerShell 文本管道，UTF-8 控制台字节原样保留；失败退出码单独记录。"""
    environment = dict(os.environ, JAVA_HOME=str(JAVA))
    environment['PATH'] = str(JAVA / 'bin') + os.pathsep + environment['PATH']
    result = subprocess.run(arguments, cwd=cwd, env=environment, capture_output=True)
    (EVIDENCE / (name + '.txt')).write_bytes(result.stdout + result.stderr)
    (EVIDENCE / (name + '-exit.json')).write_text(json.dumps(dict(command=arguments, cwd=str(cwd), exitCode=result.returncode)), encoding='utf-8')
    print(name, result.returncode)
    return result.returncode


if __name__ == '__main__':
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    if sys.argv[1] == 'copy':
        assert not CLEAN.exists(), '必须使用新的独立副本，不能覆盖构建产物'
        files = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode('utf-8').split('\0')
        manifest = []
        for name in filter(None, files):
            if not name.startswith(('backend/', 'frontend/', 'test-data/')):
                continue
            source = ROOT / name
            target = CLEAN / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
            manifest.append(dict(path=name, sha256=hashlib.sha256(source.read_bytes()).hexdigest()))
        (EVIDENCE / 'clean-source-manifest.json').write_text(json.dumps(dict(commit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT).decode().strip(), files=manifest), ensure_ascii=False, indent=2), encoding='utf-8')
        print('copied', len(manifest))
    elif sys.argv[1] == 'reproduce':
        sys.exit(execute('clean-static-before-fix', ['mvn.cmd', '-B', '-Dtest=SpaceVisibilityControllerIntegrationTest#servesBundledSinglePageApplication', 'test'], CLEAN / 'backend'))
    elif sys.argv[1] == 'backend':
        sys.exit(execute('clean-backend-verify', ['mvn.cmd', '-B', 'clean', 'verify'], CLEAN / 'backend'))
    elif sys.argv[1] == 'frontend':
        for name, args in [('clean-pnpm-install', ['pnpm.cmd', 'install', '--frozen-lockfile']), ('clean-frontend-test', ['pnpm.cmd', 'test', '--run']), ('clean-frontend-build', ['pnpm.cmd', 'build'])]:
            code = execute(name, args, CLEAN / 'frontend')
            if code:
                sys.exit(code)
