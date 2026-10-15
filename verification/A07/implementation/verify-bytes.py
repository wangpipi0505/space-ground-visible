"""只读核对本轮浏览器下载、既有黄金及保护清单；运行产物只留在 ignored 目录。"""
import csv, datetime, hashlib, json
from pathlib import Path

root=Path('G:/5-YG/space-ground-visible')
runtime=root/'verification/runtime/A07/implementation'
evidence=root/'verification/A07/implementation'
state=json.loads((runtime/'browser-state.json').read_text(encoding='utf-8'))
sha=lambda b:hashlib.sha256(b).hexdigest()
comparisons=[]
def compare(name,left,right):
    a=left.read_bytes();b=right.read_bytes()
    comparisons.append(dict(name=name,left=str(left.relative_to(root)),right=str(right.relative_to(root)),leftSha256=sha(a),rightSha256=sha(b),equal=a==b))
compare('默认文件下载原字节',runtime/'default-original.csv',root/'frontend/public/samples/trajectory-0.1s-standard.csv')
for index in [1,2]:compare(f'拟合目标{index}既有黄金',runtime/f'fit-{index}.csv',root/f'test-data/baseline/ballistic/fit/trajectory-{index-1}.csv')
compare('模拟既有黄金',runtime/'simulation.csv',root/'test-data/baseline/ballistic/simulate/trajectory-0.csv')
compare('跨页复用重定时原字节',runtime/'retime.csv',runtime/'retime-reused.csv')
for key,run in state['runs'].items():
    for i,file in enumerate(run['result']['files']):
        name=f'fit-{i+1}.csv' if key=='fit' else f'{key}.csv'
        compare(f'{name}服务文件与浏览器下载',runtime/name,Path(file['csvPath']))
    data=(runtime/f'{key}-response.json').read_bytes();expected=run['raw'].encode('utf-8')
    comparisons.append(dict(name=f'{key}响应原字节',sha256=sha(data),bytes=len(data),equal=data==expected))
data=(runtime/'default-response.json').read_bytes()
comparisons.append(dict(name='窗口响应原字节',sha256=sha(data),bytes=len(data),equal=data==state['window']['rawResponseText'].encode('utf-8')))
def csv_rows(name):
    with (runtime/name).open(encoding='utf-8-sig',newline='') as f:return list(csv.DictReader(f))
sim=csv_rows('simulation.csv');retimed=csv_rows('retime.csv')
ecef=[key for key in sim[0] if 'ECEF_' in key]
preserved=len(sim)==len(retimed) and all(a[k]==b[k] for a,b in zip(sim,retimed) for k in ecef)
protected=json.loads((root/'verification/A07/protected-before.json').read_text(encoding='utf-8'))
changed=[entry['path'] for entry in protected if sha((root/entry['path']).read_bytes())!=entry['sha256']]
exceptions=['frontend/src/components/BallisticFitTrajectory.vue','frontend/src/components/BallisticTrajectoryView.vue']
sourceFiles=sorted(p for p in (root/'frontend/src').rglob('*') if p.is_file() and p.suffix in ['.vue','.ts','.css'])
source=[dict(path=str(p.relative_to(root)).replace('\\','/'),sha256=sha(p.read_bytes()),bytes=p.stat().st_size) for p in sourceFiles]
(evidence/'source-sha256.json').write_text(json.dumps(source,ensure_ascii=False,indent=2),encoding='utf-8')
result=dict(verifiedUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),comparisons=comparisons,retimeEcefColumns=ecef,retimeEcefRows=len(retimed),retimeEcefStringsPreserved=preserved,protectedCount=len(protected),protectedChanged=changed,declaredPresentationExceptions=exceptions,protectedOnlyDeclaredExceptions=sorted(changed)==sorted(exceptions),sourceCount=len(source),sourceManifestSha256=sha((evidence/'source-sha256.json').read_bytes()),browserStateSha256=sha((runtime/'browser-state.json').read_bytes()),browserErrors=state['window']['errors'],interactions=state['interactions'],recovery=state['recovery'])
(evidence/'byte-and-source-summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(result,ensure_ascii=False,indent=2))
assert all(item['equal'] for item in comparisons)
assert preserved and len(retimed)==2576
assert result['protectedOnlyDeclaredExceptions']
assert not state['window']['errors']
