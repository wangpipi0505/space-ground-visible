"""A07 命令证据：原始双流分别保存，摘要记录真实UTC、参数、工作目录和退出码。"""
import datetime, hashlib, json, subprocess, sys
from pathlib import Path

root = Path('G:/5-YG/space-ground-visible')
evidence = root / 'verification/A07/implementation'
runtime = root / 'verification/runtime/A07/implementation'
name, *argv = sys.argv[1:]
started = datetime.datetime.now(datetime.timezone.utc).isoformat()
result = subprocess.run(argv, cwd=runtime, capture_output=True)
record = dict(startedUtc=started, finishedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(), argv=argv, cwd=str(runtime), exitCode=result.returncode)
for stream, data in [('stdout',result.stdout),('stderr',result.stderr)]:
    (evidence / f'{name}.{stream}.log').write_bytes(data)
    record[stream+'Sha256'] = hashlib.sha256(data).hexdigest()
(evidence / f'{name}.json').write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(record,ensure_ascii=False))
print(result.stdout.decode('utf-8',errors='replace')[-6000:])
print(result.stderr.decode('utf-8',errors='replace')[-4000:])
sys.exit(result.returncode)
