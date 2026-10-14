"""本波独立原字节日志驱动；原八命令/原流保持不动，当前清单最后刷新。"""
import hashlib
import json
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'verification/A03'
HISTORY = OUT / 'review-fix-history'
EVIDENCE = OUT / 'review-fix-evidence'


def fingerprint(path):
    data = path.read_bytes()
    return {'path': path.relative_to(ROOT).as_posix(), 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


def refresh():
    paths = [ROOT / 'README.md', ROOT / 'docs/软件功能使用说明.md', ROOT / 'verification/runtime/A03-implementation-report.md'] + [
        path for path in OUT.rglob('*') if path.is_file() and path.name != 'artifact-manifest.json' and '__pycache__' not in path.parts]
    write_json(OUT / 'artifact-manifest.json', [fingerprint(path) for path in sorted(paths)])


if sys.argv[1] == 'snapshot':
    HISTORY.mkdir(exist_ok=False)
    records = []
    for name in ['docs/软件功能使用说明.md', 'verification/A03/验证报告.md', 'verification/A03/validation-result.json',
                 'verification/A03/byte-audit-result.json', 'verification/A03/artifact-manifest.json',
                 'verification/runtime/A03-implementation-report.md']:
        source = ROOT / name
        saved = HISTORY / (name.replace('/', '__') + '.before.bin')
        saved.write_bytes(source.read_bytes())
        records.append({'original': fingerprint(source), 'saved': fingerprint(saved)})
    # 原命令及其流只存指纹，不复制无关大文件；后续逐字节核验是否保持原值。
    original_commands = json.loads((OUT / 'commands.json').read_text(encoding='utf-8'))
    originals = [fingerprint(OUT / 'commands.json'), fingerprint(OUT / 'environment.json'), fingerprint(OUT / 'protected-before.json')]
    originals += [item[stream] for item in original_commands['commands'] for stream in ['stdout', 'stderr']]
    originals += [fingerprint(OUT / name) for name in ['verify-stdout.txt', 'verify-stderr.txt']]
    write_json(HISTORY / 'preserved-originals.json', {'saved': records, 'unchanged': originals})
    print('original byte copies=' + str(len(records)) + '; unchanged fingerprints=' + str(len(originals)))
    sys.exit(0)

if sys.argv[1] == 'refresh':
    refresh()
    print('current manifest refreshed without self reference')
    sys.exit(0)

EVIDENCE.mkdir(exist_ok=True)
history_path = EVIDENCE / 'commands.json'
records = json.loads(history_path.read_text(encoding='utf-8')) if history_path.exists() else []
tools = {'recovery': 'verify_recovery.py', 'source': 'verify_manual.py', 'audit': 'audit_artifacts.py', 'final': 'verify_review_fix_bytes.py'}
command = [sys.executable, '-B', 'verification/A03/' + tools[sys.argv[1]]]
env = dict(os.environ, PYTHONIOENCODING='utf-8', PYTHONDONTWRITEBYTECODE='1')
started = datetime.now(timezone.utc).isoformat()
process = subprocess.run(command, cwd=ROOT, env=env, capture_output=True)
streams = {}
for name, data in [('stdout', process.stdout), ('stderr', process.stderr)]:
    path = EVIDENCE / (str(len(records) + 1) + '-' + sys.argv[1] + '-' + name + '.txt')
    path.write_bytes(data)
    streams[name] = fingerprint(path)
records.append({'startUtc': started, 'endUtc': datetime.now(timezone.utc).isoformat(), 'cwd': str(ROOT),
                'command': command, 'exitCode': process.returncode, **streams})
write_json(history_path, records)
refresh()
sys.stdout.buffer.write(process.stdout)
sys.stderr.buffer.write(process.stderr)
sys.stdout.buffer.write(('actualExitCode=' + str(process.returncode) + '\n').encode('ascii'))
sys.exit(process.returncode)
