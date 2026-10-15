"""核对真实浏览器下载原字节及最终前端身份；完整运行响应、CSV仍留ignored目录。"""
import datetime
import hashlib
import json
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding='utf-8', newline='\n')
root = Path('G:/5-YG/space-ground-visible')
evidence = root / 'verification/A07/review-fix'
runtime = root / 'verification/runtime/A07/review-fix'
sha = lambda value: hashlib.sha256(value).hexdigest()
before_path = root / 'verification/A07/pre-review-source.json'
before = json.loads(before_path.read_text(encoding='utf-8'))
allowed = ['frontend/src/components/BallisticFitFileResult.vue', 'frontend/src/views/BallisticFitView.spec.ts']
source = []
changed = []
for entry in before['files']:
    path = root / entry['path']
    data = path.read_bytes()
    source.append(dict(path=entry['path'], bytes=len(data), sha256=sha(data)))
    if sha(data) != entry['sha256']:
        changed.append(entry['path'])
manifest = dict(capturedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(), scope='A07审查修复后的最终前端源码，89文件仅两授权文件变化', predecessor='verification/A07/pre-review-source.json', predecessorSha256=sha(before_path.read_bytes()), declaredChanges=allowed, actualChanges=changed, files=source)
assert len(source) == 89 and sorted(changed) == sorted(allowed)
for relative in allowed:
    assert b'\r\n' not in (root / relative).read_bytes(), f'不是LF：{relative}'
(evidence / 'final-source-sha256.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
state = json.loads((runtime / 'browser-state.json').read_text(encoding='utf-8'))
comparisons = []
for download in state['downloads']:
    local = (runtime / download['localName']).read_bytes()
    server = Path(download['csvPath']).read_bytes()
    comparisons.append(dict(name=download['localName'], bytes=len(local), downloadedSha256=sha(local), serviceSha256=sha(server), equal=local == server))
response = (runtime / 'fit-response.json').read_bytes()
comparisons.append(dict(name='原响应JSON下载', bytes=len(response), downloadedSha256=sha(response), responseSha256=sha(state['raw'].encode('utf-8')), equal=response == state['raw'].encode('utf-8')))
assert all(item['equal'] for item in comparisons)
assert state['status'] == 200 and not state['errors']
assert [len(file['warnings']) for file in state['result']['files']] == [8, 6]
summary = dict(verifiedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(), sourceFiles=len(source), changedFiles=changed, sourceManifestSha256=sha((evidence / 'final-source-sha256.json').read_bytes()), browserStateSha256=sha((runtime / 'browser-state.json').read_bytes()), comparisons=comparisons, status=state['status'], pointCounts=[file['pointCount'] for file in state['result']['files']], warningCounts=[len(file['warnings']) for file in state['result']['files']], screens=state['screens'], keyboardExpandedWarningCount=state['expandedWarningCount'], browserErrors=state['errors'])
(evidence / 'byte-source-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(summary, ensure_ascii=False, indent=2))
