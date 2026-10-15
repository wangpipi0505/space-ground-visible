"""核对首轮稳定版至审查修复版的源文件边界，不依赖代理口头声明。"""
from pathlib import Path
import hashlib
import json
import re
import subprocess

root = Path(__file__).resolve().parents[2]
folder = root / 'verification/A07'
pre = json.loads((folder / 'pre-review-source.json').read_bytes())
expected = {'frontend/src/components/BallisticFitFileResult.vue', 'frontend/src/views/BallisticFitView.spec.ts'}
changed = []
for row in pre['files']:
    raw = (root / row['path']).read_bytes()
    if len(raw) != row['bytes'] or hashlib.sha256(raw).hexdigest() != row['sha256']:
        changed.append(row['path'])
assert set(changed) == expected, changed
names = set()
for args in [['git','ls-files','-z','frontend'], ['git','ls-files','--others','--exclude-standard','-z','frontend']]:
    names.update(filter(None, subprocess.check_output(args, cwd=root).decode().split('\0')))
assert names == {r['path'] for r in pre['files']}
component = 'frontend/src/components/BallisticFitFileResult.vue'
base = json.loads((folder / 'baseline.json').read_bytes())['base']
before = subprocess.check_output(['git', 'show', base + ':' + component], cwd=root)
current = (root / component).read_bytes()
script = lambda raw: re.search(rb'<script setup[^>]*>(.*?)</script>', raw, re.S).group(1)
old_script, current_script = script(before), script(current)
# 初轮A07已将默认展示词“原拟合”精简为“拟合”；具名允许这一处UI文字，其他脚本逐字节核对。
assert current_script.count(b"resultLabel: '") == 1
assert old_script == current_script.replace("resultLabel: '拟合'".encode(), "resultLabel: '原拟合'".encode(), 1)
print(json.dumps({'frontendFiles':len(names), 'onlyChangedSincePreReview':changed,
                  'sharedResultScriptBaselineOnlyUiLabelChange':True}, ensure_ascii=False))
