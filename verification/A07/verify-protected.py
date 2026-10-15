"""只读核对本轮保护范围及共享空间编辑器的实际布局，不重新计算物理结果。"""
from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'verification/A07'
baseline = json.loads((OUT / 'baseline.json').read_bytes())
protected = json.loads((OUT / 'protected-before.json').read_bytes())
exceptions = {row['path']: row for row in json.loads((OUT / 'protected-presentation-exceptions.json').read_bytes())}
differences = []
unchanged = 0
presentation = []
for row in protected:
    name = row['path']
    raw = (ROOT / name).read_bytes()
    actual = hashlib.sha256(raw).hexdigest()
    if name not in exceptions:
        if actual != row['sha256'] or len(raw) != row['bytes']:
            differences.append({'path': name, 'expected': row['sha256'], 'actual': actual})
        else:
            unchanged += 1
        continue
    # 两个轨迹组件仅允许改说明呈现；脚本继续使用原实现，保留基线逐字节核对。
    original = subprocess.check_output(['git', 'show', baseline['base'] + ':' + name], cwd=ROOT)
    assert hashlib.sha256(original).hexdigest() == exceptions[name]['baselineSha256'], name
    script = lambda content: re.search(rb'<script setup[^>]*>(.*?)</script>', content, re.S).group(1)
    same_script = script(original) == script(raw)
    presentation.append({'path': name, 'scriptIdentical': same_script, 'currentSha256': actual})
    if not same_script:
        differences.append({'path': name, 'error': 'script内容变化'})
before = json.loads((OUT / 'space-before-metrics.json').read_bytes())
after = json.loads((OUT / 'space-after-metrics.json').read_bytes())
record = {'base': baseline['base'], 'protectedCount': len(protected), 'strictUnchanged': unchanged,
          'presentationExceptions': presentation, 'spaceMetricsIdentical': before == after,
          'differences': differences}
print(json.dumps(record, ensure_ascii=False, indent=2))
assert not differences and before == after
