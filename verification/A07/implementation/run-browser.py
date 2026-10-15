"""从固定脚本文件调用同一个 Playwright CLI 会话，避免 shell 引号改变浏览器断言。"""
import runpy, sys
from pathlib import Path
name, script = sys.argv[1:]
sys.argv = ['run-command.py', name, 'C:/Users/Administrator/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe', 'C:/Users/Administrator/AppData/Local/npm-cache/_npx/31e32ef8478fbf80/node_modules/@playwright/cli/playwright-cli.js', '-s=A07impl', 'run-code', Path(script).read_text(encoding='utf-8')]
runpy.run_path(str(Path(__file__).with_name('run-command.py')), run_name='__main__')
