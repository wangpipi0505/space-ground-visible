"""只读复核本项冻结黄金与原实现双跑，汇总精确比较、普通Java工具和最终包身份。"""
from pathlib import Path
import hashlib
import json
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from run_baseline import compare

runtime, evidence = ROOT / 'verification/runtime/T27', ROOT / 'verification/T27'
captures = runtime / 'final-regression'
checks = []
for name, expected, actual in [
    ('ballistic-10', ROOT / 'test-data/baseline/ballistic', captures / 'ballistic-actual'),
    ('space-9', ROOT / 'test-data/baseline/space', captures / 'space-actual'),
    ('space-complete-21', captures / 'original-actual', captures / 'migrated-actual')]:
    differences = compare(expected, actual)
    assert not differences, (name, differences)
    checks.append(dict(name=name, fileCount=sum(p.is_file() for p in actual.rglob('*')), differences=differences))
tools = []
for name in ('ballistic-java-run.log', 'plain-java-run.log'):
    raw = (evidence / name).read_bytes()
    text = raw.decode('utf-16' if raw.startswith((b'\xff\xfe', b'\xfe\xff')) else 'utf-8-sig')
    success = [line for line in text.splitlines() if line.startswith('PASS ')]
    assert success, name
    tools.extend(success)
result = dict(passed=True, jarSha256=hashlib.sha256((runtime / 'space-target-visible-T27.jar').read_bytes()).hexdigest(),
              comparisons=checks, ordinaryJava=tools,
              comparison='JSON Decimal exact values, structure and order; CSV/report exact bytes',
              phase='取消修复后最终候选重新capture全部15/9/21文件；普通Java工具源未变，逐bits证据与最终未变数值class绑定。')
(evidence / 'regression-summary.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(result, ensure_ascii=False, indent=2))
