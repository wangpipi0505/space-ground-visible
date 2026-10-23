"""执行一条验收命令，保留原始输出、退出码与实际工具路径。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument('name')
parser.add_argument('--cwd', default='frontend')
parser.add_argument('command', nargs=argparse.REMAINDER)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
output = root / 'verification/A19'
command = args.command[1:] if args.command[:1] == ['--'] else args.command
start = time.time()
environment = os.environ.copy()
environment['PATH'] = str(Path(command[0]).parent) + os.pathsep + environment['PATH']
environment['NO_COLOR'] = '1'
result = subprocess.run(command, cwd=root / args.cwd, capture_output=True, env=environment,
                        creationflags=subprocess.CREATE_NO_WINDOW)
for name, data in [('stdout', result.stdout), ('stderr', result.stderr)]:
    (output / f'{args.name}-{name}.txt').write_bytes(data)
record = {'command': command, 'cwd': str(root / args.cwd), 'startedUnix': start,
          'durationSec': time.time() - start, 'exitCode': result.returncode,
          'stdoutSha256': hashlib.sha256(result.stdout).hexdigest(),
          'stderrSha256': hashlib.sha256(result.stderr).hexdigest()}
(output / f'{args.name}-command.json').write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(record, ensure_ascii=False))
raise SystemExit(result.returncode)
