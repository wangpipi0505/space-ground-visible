"""真实子进程执行 A03 核验；stdout/stderr 直接写原字节，再从保存文件计算 SHA。"""
import hashlib
import json
import os
import platform
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'verification/A03'


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


def fingerprint(path):
    data = path.read_bytes()
    return {'path': path.relative_to(ROOT).as_posix(), 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}


if '--snapshot' in sys.argv:
    protected = []
    for directory in ['backend/src', 'frontend/src', 'deploy', 'verification/A01', 'verification/A02', 'output/playwright/A01', 'test-data']:
        protected.extend(p for p in (ROOT / directory).rglob('*') if p.is_file() and '__pycache__' not in p.parts)
    protected.extend([ROOT / 'backend/pom.xml', ROOT / 'frontend/pnpm-lock.yaml', ROOT / 'frontend/package.json',
                      ROOT / 'frontend/vite.config.ts', ROOT / '.gitattributes', ROOT / 'docs/待办事项台账.md',
                      ROOT / 'docs/补充验收台账.md', ROOT / 'docs/openapi/openapi.json', ROOT / 'docs/接口文档.md',
                      ROOT / 'docs/superpowers/plans/2026-10-14-comprehensive-acceptance-and-docs.md'])
    write_json(OUT / 'protected-before.json', [fingerprint(p) for p in sorted(set(protected))])
    print('protected snapshot files=' + str(len(set(protected))))
    sys.exit(0)

env = dict(os.environ, PYTHONIOENCODING='utf-8', PYTHONDONTWRITEBYTECODE='1')
tool = 'audit_artifacts.py' if '--audit' in sys.argv else 'verify_manual.py'
command = [sys.executable, '-B', 'verification/A03/' + tool]
history_path = OUT / 'commands.json'
history = json.loads(history_path.read_text(encoding='utf-8'))['commands'] if history_path.exists() else []
attempt = len(history) + 1
start = datetime.now(timezone.utc).isoformat()
process = subprocess.run(command, cwd=ROOT, env=env, capture_output=True)
prefix = 'audit-' if '--audit' in sys.argv else 'verify-'
stdout, stderr = OUT / (prefix + str(attempt) + '-stdout.txt'), OUT / (prefix + str(attempt) + '-stderr.txt')
stdout.write_bytes(process.stdout)
stderr.write_bytes(process.stderr)
record = {'startUtc': start, 'endUtc': datetime.now(timezone.utc).isoformat(), 'cwd': str(ROOT),
          'command': command, 'exitCode': process.returncode, 'stdout': fingerprint(stdout), 'stderr': fingerprint(stderr)}
write_json(OUT / 'commands.json', {'passed': process.returncode == 0, 'commands': history + [record]})
write_json(OUT / 'environment.json', {'timeUtc': start, 'platform': platform.platform(), 'python': sys.version,
                                    'executable': sys.executable, 'sourceBaseline': '714037e94f396fb8469b9676792d04a9d6a9a610',
                                    'scope': 'A03只读文档来源；未启动Java/Chrome/数据库，不改依赖/原证据'})
artifacts = [ROOT / 'README.md', ROOT / 'docs/软件功能使用说明.md'] + [
    p for p in OUT.rglob('*') if p.is_file() and p.name != 'artifact-manifest.json' and '__pycache__' not in p.parts]
write_json(OUT / 'artifact-manifest.json', [fingerprint(p) for p in sorted(artifacts)])
print(process.stdout.decode('utf-8'), end='')
print(process.stderr.decode('utf-8'), end='', file=sys.stderr)
print('actualExitCode=' + str(process.returncode))
sys.exit(process.returncode)
