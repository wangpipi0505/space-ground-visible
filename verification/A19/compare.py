"""严格比较浏览器实际请求、数值结果与导出字节；只剥离已知的临时文件路径。"""
import hashlib
import json
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[2]
out = root / 'verification/A19'

def sha(data):
    return hashlib.sha256(data).hexdigest()

def normalize(value):
    if isinstance(value, dict):
        return {key: ('<临时文件路径>' if key in {'csvPath', 'trajectoryCsvPath'} else normalize(item)) for key, item in value.items()}
    if isinstance(value, list):
        return [normalize(item) for item in value]
    return value

def load(name):
    file = out / name
    if not file.exists():
        file = root / 'verification/runtime/A19/http' / name
    return json.loads(file.read_text(encoding='utf-8')), file

before, before_file = load('before-http.json')
after, after_file = load('after-http.json')
assert not before['errors'] and not after['errors']
requests = []
for a, b in zip(before['requests'], after['requests']):
    assert a['name'] == b['name'] and a['status'] == b['status'] == 200
    assert normalize(a['request']) == normalize(b['request']), a['name'] + ' 请求变化'
    assert normalize(a['response']) == normalize(b['response']), a['name'] + ' 结果变化'
    requests.append({'name': a['name'], 'status': 200, 'requestEqualExceptTemporaryPath': True,
                     'responseEqualExceptTemporaryPath': True, 'normalizedResponseSha256': sha(json.dumps(normalize(a['response']), ensure_ascii=False, sort_keys=True).encode())})
assert len(requests) == 4
files = []
assert len(before['fileResponses']) == len(after['fileResponses']) == 4
for a, b in zip(before['fileResponses'], after['fileResponses']):
    assert a == b, a['name'] + ' 导出变化'
    files.append({'name': a['name'], 'targetId': a['targetId'], 'bytes': len(a['csv'].encode()), 'sha256': sha(a['csv'].encode()), 'equal': True})
unchanged = []
paths = subprocess.check_output(['git', 'ls-files', '-z', 'backend', 'docs/openapi', 'docs/ORBIT_VISIBILITY_API.md', 'docs/TARGET_TRAJECTORY_FIT_API.md', 'frontend/src/style.css', 'frontend/src/styles/ballistic-workspace.css'], cwd=root).decode().strip('\0').split('\0')
for name in paths:
    expected = subprocess.check_output(['git', 'show', 'HEAD:' + name], cwd=root)
    actual = (root / name).read_bytes()
    # 源文件受仓库既有行尾配置影响，比较 Git clean 后的 blob；业务字节不因 CRLF/LF 差异误报。
    clean_hash = subprocess.check_output(['git', 'hash-object', '--path=' + name, name], cwd=root).decode().strip()
    head_hash = subprocess.check_output(['git', 'rev-parse', 'HEAD:' + name], cwd=root).decode().strip()
    assert clean_hash == head_hash, name
    unchanged.append({'path': name, 'gitBlob': head_hash})
assert not list((root / 'frontend/src').rglob('*.ts'))
best = before['requests'][0]['response']['best']
summary = {'requestComparisons': requests, 'fileComparisons': files, 'browserErrors': [],
           'unchangedCount': len(unchanged), 'unchanged': unchanged,
           'sourceBindings': [{'path': str(p.relative_to(root)), 'sha256': sha(p.read_bytes())} for p in [before_file, after_file]],
           'windowBest': {key: best[key] for key in ['offsetSecond', 'launchTimeUtc', 'visibleArcCount', 'visiblePointCount', 'visibleDurationSec']}}
(out / 'comparison.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({'requests': len(requests), 'files': files, 'unchanged': len(unchanged)}, ensure_ascii=True))
