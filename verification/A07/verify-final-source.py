"""只读核对修后源码清单和新真实拟合下载，避免重写阶段证据。"""
from pathlib import Path
import hashlib
import json

root = Path(__file__).resolve().parents[2]
folder = root / 'verification/A07/review-fix'
runtime = root / 'verification/runtime/A07/review-fix'
manifest = json.loads((folder / 'final-source-sha256.json').read_bytes())
sha = lambda raw: hashlib.sha256(raw).hexdigest()
for row in manifest['files']:
    raw = (root / row['path']).read_bytes()
    assert len(raw) == row['bytes'] and sha(raw) == row['sha256'], row['path']
summary = json.loads((folder / 'byte-source-summary.json').read_bytes())
assert sha((folder / 'final-source-sha256.json').read_bytes()) == summary['sourceManifestSha256']
state = json.loads((runtime / 'browser-state.json').read_bytes())
for row in state['downloads']:
    assert (runtime / row['localName']).read_bytes() == Path(row['csvPath']).read_bytes()
assert (runtime / 'fit-response.json').read_bytes() == state['raw'].encode('utf-8')
print(json.dumps({'finalSourceFiles': len(manifest['files']), 'sourceDifferences': 0,
                  'newFitDownloadsIdentical': len(state['downloads']), 'rawResponseIdentical': True,
                  'warningCounts': [len(row['warnings']) for row in state['result']['files']]}, ensure_ascii=False))
