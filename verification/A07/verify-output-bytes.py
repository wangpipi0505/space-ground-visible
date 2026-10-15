"""根独立只读核对浏览器实际产物与既有黄金；输入/响应仍在ignored运行目录。"""
from pathlib import Path
import csv
import hashlib
import json

root = Path(__file__).resolve().parents[2]
folder = root / 'verification/A07'
runtime = root / 'verification/runtime/A07/implementation'
summary = json.loads((folder / 'implementation/byte-and-source-summary.json').read_bytes())
state = json.loads((runtime / 'browser-state.json').read_bytes())
sha = lambda raw: hashlib.sha256(raw).hexdigest()
checked = 0
for row in summary['comparisons']:
    assert row['equal']
    if 'left' in row:
        left, right = root / row['left'], root / row['right']
        assert left.read_bytes() == right.read_bytes()
        assert sha(left.read_bytes()) == row['leftSha256'] == row['rightSha256']
    checked += 1
for name in ('fit', 'simulation', 'retime'):
    assert (runtime / (name + '-response.json')).read_bytes() == state['runs'][name]['raw'].encode('utf-8')
assert (runtime / 'default-response.json').read_bytes() == state['window']['rawResponseText'].encode('utf-8')
def rows(name):
    with (runtime / name).open(encoding='utf-8-sig', newline='') as stream:
        return list(csv.DictReader(stream))
simulated, retimed = rows('simulation.csv'), rows('retime.csv')
assert len(simulated) == len(retimed) == 2576
columns = ['ECEF_X', 'ECEF_Y', 'ECEF_Z', 'ECEF_Vx', 'ECEF_Vy', 'ECEF_Vz']
assert all(a[key] == b[key] for a, b in zip(simulated, retimed) for key in columns)
pre = {r['path']: r for r in json.loads((folder / 'pre-review-source.json').read_bytes())['files']}
source = json.loads((folder / 'implementation/source-sha256.json').read_bytes())
assert all(pre[r['path']]['sha256'] == r['sha256'] and pre[r['path']]['bytes'] == r['bytes'] for r in source)
print(json.dumps({'comparisonsVerified': checked, 'rawResponseDownloads': 4,
                  'retimeRows': len(retimed), 'ecefPvStringsIdentical': True,
                  'preReviewSourceBound': len(source),
                  'scope': '真实运行产物的只读独立复核；未重算物理或补造STK Access'}, ensure_ascii=False))
