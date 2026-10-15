"""A07审查修复命令证据：在隔离运行目录执行，原始双流与退出码只追加保存。"""
import datetime
import hashlib
import json
import subprocess
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8')
root = Path('G:/5-YG/space-ground-visible')
evidence = root / 'verification/A07/review-fix'
runtime = root / 'verification/runtime/A07/review-fix'
runtime.mkdir(parents=True, exist_ok=True)
name, *argv = sys.argv[1:]
for suffix in ('json', 'stdout.log', 'stderr.log'):
    if (evidence / f'{name}.{suffix}').exists():
        raise SystemExit(f'拒绝覆盖已有证据：{name}.{suffix}')
started = datetime.datetime.now(datetime.timezone.utc).isoformat()
result = subprocess.run(argv, cwd=runtime, capture_output=True)
record = dict(startedUtc=started, finishedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(), argv=argv, cwd=str(runtime), exitCode=result.returncode)
for stream, data in [('stdout', result.stdout), ('stderr', result.stderr)]:
    (evidence / f'{name}.{stream}.log').write_bytes(data)
    record[stream + 'Sha256'] = hashlib.sha256(data).hexdigest()
(evidence / f'{name}.json').write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(record, ensure_ascii=False))
print(result.stdout.decode('utf-8', errors='replace')[-12000:])
print(result.stderr.decode('utf-8', errors='replace')[-6000:])
sys.exit(result.returncode)
