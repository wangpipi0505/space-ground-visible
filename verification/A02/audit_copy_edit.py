"""核对文案修正范围、旧证明保护、新命令原字节与当前完整清单；不写旧证据。"""
import hashlib
import json
import subprocess
import sys
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'verification/A02'
history=OUT/'copy-edit-history'
def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

original=json.loads((history/'docs/openapi/openapi.json').read_text(encoding='utf-8'))
current=json.loads((ROOT/'docs/openapi/openapi.json').read_text(encoding='utf-8'))
differences=[]
def compare(before,after,path=''):
    if isinstance(before,dict) and isinstance(after,dict):
        for key in sorted(set(before)|set(after)):
            if key not in before or key not in after: differences.append(path+'/'+key)
            else: compare(before[key],after[key],path+'/'+key)
    elif isinstance(before,list) and isinstance(after,list) and len(before)==len(after):
        for i,(a,b) in enumerate(zip(before,after)): compare(a,b,path+'/'+str(i))
    elif before!=after: differences.append(path)
compare(original,current)
prefix='/components/schemas/VisibilityPlanResponse/properties/targetFilterBreakdown/'
assert set(differences)=={prefix+'description',prefix+'x-source-comment-status'},differences

records=json.loads((history/'byte-manifest.json').read_text(encoding='utf-8'))
allowed={'docs/接口文档.md','docs/openapi/openapi.json','verification/A02/build_contract.py',
         'verification/A02/validation-result.json','verification/A02/artifact-manifest.json',
         'verification/runtime/A02-implementation-report.md','verification/A02/独立复审报告.md'}
protected=0
for record in records:
    saved=history/record['path']
    assert len(saved.read_bytes())==record['bytes'] and sha(saved)==record['sha256'], '留存原字节变化 '+record['path']
    if record['path'] not in allowed:
        assert sha(ROOT/record['path'])==record['sha256'], '保护的原证据被覆盖 '+record['path']
        protected+=1
new=json.loads((OUT/'copy-edit-commands.json').read_text(encoding='utf-8'))
assert new['passed'] and len(new['commands'])==5
for command in new['commands']:
    assert command['exitCode']==0
    for stream in ['stdout','stderr']:
        data=(OUT/command[stream]).read_bytes()
        assert hashlib.sha256(data).hexdigest()==command[stream+'Sha256']
        assert b'\r\r\n' not in data
before=json.loads((OUT/'copy-edit-before-command.json').read_text(encoding='utf-8'))
assert before['exitCode']==1
for stream in ['stdout','stderr']:
    assert sha(OUT/before[stream])==before[stream+'Sha256']

# 原审计继续验证十二旧通过日志、当前manifest、原A01指纹和history-v1；输出保存在新的文案审计日志。
args=[sys.executable,'-X','utf8','verification/A02/audit_evidence.py']
result=subprocess.run(args,cwd=ROOT,capture_output=True,creationflags=subprocess.CREATE_NO_WINDOW)
assert result.returncode==0,result.stdout.decode('utf-8')+result.stderr.decode('utf-8')
assert not result.stderr
audit=json.loads(result.stdout)
audit['copyEdit']={'onlySchemaDifferences':differences,'oldSchemaSha256':sha(history/'docs/openapi/openapi.json'),
                   'newSchemaSha256':sha(ROOT/'docs/openapi/openapi.json'),'historyFiles':len(records),
                   'protectedPriorFilesUnchanged':protected,'newCommands':5,'newCommandLogsShaMatched':10,
                   'staticRegressionBeforeExit':1,'staticRegressionAfterExit':0,
                   'noNewCurlOrBusinessRun':True}
print(json.dumps(audit,ensure_ascii=False,indent=2))
