"""使用已有 Playwright CLI 执行可重放的页面取证脚本。"""
from pathlib import Path
import subprocess
import sys
import json

root = Path(__file__).resolve().parents[2]
script = (root / sys.argv[1]).read_text(encoding='utf-8')
if len(sys.argv) > 3:
    script = script.replace('__STAGE__', sys.argv[2]).replace('__URL__', sys.argv[3].rstrip('/'))
cli = 'C:/Users/Administrator/AppData/Local/npm-cache/_npx/31e32ef8478fbf80/node_modules/@playwright/cli/playwright-cli.js'
destination = root / ('verification/runtime/A19/http' if sys.argv[2].endswith('-http') else 'verification/A19')
destination.mkdir(parents=True, exist_ok=True)
result = subprocess.run(['C:/Program Files/nodejs/node.exe', cli, '-s=A19', 'run-code', script], cwd=root, capture_output=True, creationflags=subprocess.CREATE_NO_WINDOW)
(destination / (sys.argv[2] + '.txt')).write_bytes(result.stdout + result.stderr)
text = result.stdout.decode('utf-8', errors='replace')
if '### Result\n' in text:
    value, _ = json.JSONDecoder().raw_decode(text.split('### Result\n', 1)[1])
    (destination / (sys.argv[2] + '.json')).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'result': sys.argv[2], 'keys': list(value)}, ensure_ascii=True))
else:
    print(text[:1500])
raise SystemExit(result.returncode)
