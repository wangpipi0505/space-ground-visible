"""用独立A07fix会话运行文件脚本，避免命令行改写引号与断言。"""
import runpy
import sys
from pathlib import Path

name, script = sys.argv[1:]
sys.argv = ['run-command.py', name, 'C:/Users/Administrator/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe', 'C:/Users/Administrator/AppData/Local/npm-cache/_npx/31e32ef8478fbf80/node_modules/@playwright/cli/playwright-cli.js', '-s=A07fix', 'run-code', Path(script).read_text(encoding='utf-8')]
runpy.run_path(str(Path(__file__).with_name('run-command.py')), run_name='__main__')
