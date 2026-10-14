"""独立按实际保存字节核对 A02 日志/清单/历史/源指纹；旧错误仅作历史定位，不称为通过。"""
import hashlib
import json
import sys
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'verification/A02'
errors=[]
def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

commands=json.loads((OUT/'commands.json').read_text(encoding='utf-8'))
assert not commands.get('running') and commands['passed'] and len(commands['commands'])==6
for command in commands['commands']:
    if command['exitCode']!=0: errors.append('非零退出 '+command['name'])
    for stream in ['stdout','stderr']:
        path=OUT/command[stream]
        if sha(path)!=command[stream+'Sha256']: errors.append('新日志 SHA 不一致 '+command[stream])
        if b'\r\r\n' in path.read_bytes(): errors.append('新日志 CRCRLF '+command[stream])

manifest=json.loads((OUT/'artifact-manifest.json').read_text(encoding='utf-8'))
declared={record['path'] for record in manifest}
actual={p.relative_to(ROOT).as_posix() for p in [ROOT/'README.md',ROOT/'docs/接口文档.md',ROOT/'docs/openapi/openapi.json']+list(OUT.rglob('*'))
        if p.is_file() and '__pycache__' not in p.parts and p.name!='artifact-manifest.json'}
if actual!=declared: errors.append('清单覆盖差异 '+repr(sorted(actual^declared)))
for record in manifest:
    path=ROOT/record['path']
    if len(path.read_bytes())!=record['bytes'] or sha(path)!=record['sha256']: errors.append('清单实际字节不一致 '+record['path'])

for base in [OUT/'history-v1',OUT/'fix-attempt1',OUT/'fix-attempt2']:
    records=json.loads((base/'byte-manifest.json').read_text(encoding='utf-8'))
    for record in records:
        path=base/record['path']
        if len(path.read_bytes())!=record['bytes'] or sha(path)!=record['sha256']: errors.append('历史留存字节变化 '+str(path))

# 四份旧 stdout 的记录 SHA 绑定的是转换前字节；这里只定位旧换行失真，明确不能判旧完整性通过。
historical=[]
old=OUT/'history-v1/verification/A02'
for command in json.loads((old/'commands.json').read_text(encoding='utf-8'))['commands']:
    path=old/command['stdout'];data=path.read_bytes()
    historical.append({'file':command['stdout'],'savedShaMatches':sha(path)==command['stdoutSha256'],
                       'crcrlf':data.count(b'\r\r\n'),
                       'normalizedCrLfShaMatches':hashlib.sha256(data.replace(b'\r\r\n',b'\r\n')).hexdigest()==command['stdoutSha256']})
assert len([x for x in historical if not x['savedShaMatches']])==4

inventory=json.loads((OUT/'source-inventory.json').read_text(encoding='utf-8'))
for source in inventory['sources']:
    if sha(ROOT/source['path'])!=source['sha256']: errors.append('产品源码指纹变化 '+source['path'])
jar=inventory['jar']
if sha(ROOT/jar['path'])!=jar['sha256'] or jar['sha256']!='d9c9956c389e79d1d52619c3e8310fb4b887d2009ad8f4c226d597cc030e3c71': errors.append('既有验收 JAR 指纹变化')
environment=json.loads((OUT/'environment.json').read_text(encoding='utf-8'))
if sha(ROOT/'frontend/pnpm-lock.yaml')!=environment['frontendLockSha256']: errors.append('前端锁指纹变化')

result={'passed':not errors,'commandLogs':12,'logShaMismatches':0 if not errors else None,
        'manifestAllActualBytesChecked':True,'historyFiles':len(json.loads((OUT/'history-v1/byte-manifest.json').read_text(encoding='utf-8'))),
        'historicalStdoutAudit':historical,'sourceFingerprints':len(inventory['sources']),
        'existingJarSha256':jar['sha256'],'currentSchemaSha256':sha(ROOT/'docs/openapi/openapi.json'),
        'boundary':'旧四份日志保存字节不一致；旧 exit0 不代表旧日志完整性通过。当前只检查证据字节，不新构建/运行业务。',
        'errors':errors}
print(json.dumps(result,ensure_ascii=False,indent=2))
sys.exit(0 if result['passed'] else 1)
