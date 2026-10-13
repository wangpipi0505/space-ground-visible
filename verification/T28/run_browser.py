"""Chrome CLI 输出按原 UTF-8 字节保存；原生 chooser 暂停时须实际 upload 后再读取同页结果。"""
from pathlib import Path
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8')
session, script, output = sys.argv[1:]
result = subprocess.run(['npx.cmd', '--yes', '--package', '@playwright/cli', 'playwright-cli', '-s=' + session,
                         'run-code', '--filename=' + script], capture_output=True)
Path(output).write_bytes(result.stdout + result.stderr)
print(result.stdout.decode('utf-8', errors='replace')[:1200])
raise SystemExit(result.returncode)
