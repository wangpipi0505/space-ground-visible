"""核对本次限定差异、少量原字节、所有上一轮原证据和新命令日志；不修改历史。"""
import hashlib
import json
import subprocess
import sys
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'verification/A02'
HISTORY=OUT/'query-fix-history'
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
before=json.loads((HISTORY/'docs/openapi/openapi.json').read_text(encoding='utf-8'))
after=json.loads((ROOT/'docs/openapi/openapi.json').read_text(encoding='utf-8'))
differences=[]
def compare(a,b,path=''):
    if isinstance(a,dict) and isinstance(b,dict):
        for key in sorted(set(a)|set(b)):
            if key not in a or key not in b: differences.append(path+'/'+key)
            else: compare(a[key],b[key],path+'/'+key)
    elif isinstance(a,list) and isinstance(b,list) and len(a)==len(b):
        for i,(x,y) in enumerate(zip(a,b)): compare(x,y,path+'/'+str(i))
    elif a!=b: differences.append(path)
compare(before,after)
expected=set()
for owner,names in [('TargetQueryRequest',['maxApproximationDays','planningStartTime','planningEndTime']),
                    ('TargetFilterBreakdownResponse',['belongsHit','weightHit','epochHit','epochConstraintHit','ephemerisWarningHit','excludeHit','maxApproximationDays']),
                    ('TargetQueryResponse',['filterBreakdown']),('VisibilityPlanResponse',['targetFilterBreakdown'])]:
    for name in names: expected.add('/components/schemas/'+owner+'/properties/'+name+'/description')
for name in ['maxApproximationDays','planningStartTime','planningEndTime']:
    expected.add('/components/schemas/TargetQueryRequest/properties/'+name+'/x-source-comment-status')
expected.add('/components/schemas/TargetQueryRequest/properties/maxApproximationDays/minimum')
assert set(differences)==expected, {'actual':differences,'expected':sorted(expected)}
saved=json.loads((HISTORY/'byte-manifest.json').read_text(encoding='utf-8'))
for record in saved:
    path=HISTORY/record['path']
    assert len(path.read_bytes())==record['bytes'] and sha(path)==record['sha256'], record['path']
assert sha(HISTORY/'docs/openapi/openapi.json')=='6eeb15cf98a22e553221c1a6ad3b02e44838307e17038c229a2b5003d359c7c4'
protected=json.loads((HISTORY/'protected-prior-files.json').read_text(encoding='utf-8'))
for record in protected:
    path=ROOT/record['path']
    assert len(path.read_bytes())==record['bytes'] and sha(path)==record['sha256'], '原证据变化 '+record['path']
records=json.loads((OUT/'query-fix-commands.json').read_text(encoding='utf-8'))
assert records['passed'] and len(records['commands'])==6
red=json.loads((OUT/'query-fix-before-command.json').read_text(encoding='utf-8'))
assert red['exitCode']==1
for record in records['commands']+[red]:
    for stream in ['stdout','stderr']:
        data=(OUT/record[stream]).read_bytes()
        assert hashlib.sha256(data).hexdigest()==record[stream+'Sha256']
        assert b'\r\r\n' not in data
red_findings=json.loads((OUT/red['stdout']).read_text(encoding='utf-8'))['results']
assert [r['finding'] for r in red_findings if not r['passed']]==['I07','I08']
assert (OUT/'source-inventory.json').read_bytes()==(HISTORY/'verification/A02/source-inventory.json').read_bytes()
result=subprocess.run([sys.executable,'-X','utf8','verification/A02/audit_evidence.py'],cwd=ROOT,capture_output=True,creationflags=subprocess.CREATE_NO_WINDOW)
assert result.returncode==0, result.stdout.decode('utf-8')+result.stderr.decode('utf-8')
assert not result.stderr
audit=json.loads(result.stdout)
audit['queryFix']={'schemaDifferences':differences,'oldSchemaSha256':sha(HISTORY/'docs/openapi/openapi.json'),
    'newSchemaSha256':sha(ROOT/'docs/openapi/openapi.json'),'savedOriginalFiles':len(saved),
    'protectedPriorFilesUnchanged':len(protected),'newCommands':6,'newLogsShaMatched':12,
    'redExit':1,'redFindings':['I07','I08'],'greenExit':0,'noNewCurlOrBusinessRun':True}
print(json.dumps(audit,ensure_ascii=False,indent=2))
